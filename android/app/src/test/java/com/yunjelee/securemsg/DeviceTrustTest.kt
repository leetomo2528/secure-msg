package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTrustTest {
    private val zero = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private val one = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE"
    private val two = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI"
    private val three = "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM"

    private fun statement() = DeviceApprovalStatement(
        uid = 42,
        subjectSid = "android_A1",
        pubKey = one,
        sigPub = two,
        kind = "android_gateway",
        challenge = zero,
        parentEpoch = 7,
    )

    @Test fun approvalCanonicalGoldenIsByteExact() {
        assertEquals(
            "securemsg-device-approval-v1\n" +
                "uid=42\n" +
                "subject_sid=android_A1\n" +
                "pub_key=$one\n" +
                "sig_pub=$two\n" +
                "kind=android_gateway\n" +
                "challenge=$zero\n" +
                "parent_epoch=7\n",
            statement().canonical(),
        )
    }

    private val four = "BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ"

    private fun pairing() = PairingBinding(
        pairingId = "pair_abc-123",
        nonceNew = three,
        nonceApprover = four,
    )

    @Test fun approvalCanonicalV2GoldenIsByteExact() {
        // The same field order and framing are pinned in the relay
        // (server/tests/test_smoke.py) and the web client
        // (frontend/src/crypto/deviceTrust.test.ts). A drift on any one side
        // invalidates every certificate the other two verify.
        assertEquals(
            "securemsg-device-approval-v2\n" +
                "uid=42\n" +
                "subject_sid=android_A1\n" +
                "pub_key=$one\n" +
                "sig_pub=$two\n" +
                "kind=android_gateway\n" +
                "challenge=$zero\n" +
                "pairing_id=pair_abc-123\n" +
                "nonce_new=$three\n" +
                "nonce_approver=$four\n" +
                "parent_epoch=7\n",
            DeviceApprovalStatementV2(statement(), pairing()).canonical(),
        )
    }

    @Test fun canonicalFormFollowsTheStatementsOwnDomainLine() {
        val v1 = statement().canonical()
        val v2 = DeviceApprovalStatementV2(statement(), pairing()).canonical()
        assertEquals(v1, canonicalApprovalForStatement(statement(), v1))
        assertEquals(v2, canonicalApprovalForStatement(statement(), v2))
        // Claiming v2 without carrying a binding must not fall back to v1.
        val mislabelled = v1.replace("securemsg-device-approval-v1", "securemsg-device-approval-v2")
        assertEquals(null, canonicalApprovalForStatement(statement(), mislabelled))
    }

    @Test fun v2StatementRejectsTamperedNonces() {
        val genuine = DeviceApprovalStatementV2(statement(), pairing()).canonical()
        val swapped = genuine.replace("nonce_approver=$four", "nonce_approver=$zero")
        // The parse still succeeds, but re-rendering no longer matches, which
        // is exactly the check that rejects it.
        assertEquals(false, swapped == canonicalApprovalForStatement(statement(), genuine))
        assertTrue(pairingBindingFromStatement(swapped)!!.nonceApprover == zero)
    }

    @Test fun approvalCanonicalRejectsInjectionAndBadChallenge() {
        val injected = statement().copy(subjectSid = "sid\nparent_epoch=0")
        assertFails { injected.canonical() }
        assertFails { statement().copy(challenge = "AA").canonical() }
        assertFails { statement().copy(parentEpoch = -1).canonical() }
    }

    @Test fun revokeCanonicalGoldenIsByteExactAndTamperSensitive() {
        val revoke = DeviceRevokeStatement(42, "android_A1", one, two, "android_actor", 9)
        assertEquals(
            "securemsg-device-revoke-v1\nuid=42\nsubject_sid=android_A1\n" +
                "subject_pub_key=$one\nsubject_sig_pub=$two\nactor_sid=android_actor\n" +
                "parent_epoch=9\nreason=user_revoked\n",
            revoke.canonical(),
        )
        assertFalse(revoke.canonical() == revoke.copy(parentEpoch = 10).canonical())
        assertFails { revoke.copy(actorSid = "bad\nactor").canonical() }
    }

    @Test fun fingerprintGoldenAndKeySensitive() {
        assertEquals(
            "210E C9F7 9269 9D9C A28D 6CD7 FD46 AE71 C117 1D25 E794 6EC8 1391 F4BF 879C 72E2",
            DeviceTrustCrypto.deviceFingerprint(one, two),
        )
        assertFalse(
            DeviceTrustCrypto.deviceFingerprint(one, two) ==
                DeviceTrustCrypto.deviceFingerprint(two, one),
        )
    }

    @Test fun safetyNumberIsStableFormattedAndIdentityBound() {
        val expected = "122083 575428 947849 506636 071391 222958 646031 925509 008341 528924 136567 190218"
        assertEquals(expected, DeviceTrustCrypto.safetyNumber(42, three))
        assertEquals(expected, DeviceTrustCrypto.safetyNumber(42, three))
        assertEquals(expected, DeviceTrustCrypto.safetyNumber(43, three))
        assertTrue(DeviceTrustCrypto.safetyQrPayload(42, three).contains("opRkm10jxRdUFUH7WBYXdXE7KkjlFC4pITU7a7LKS-M"))
    }

    @Test fun directoryOrderDoesNotChangeHash() {
        val a = descriptor("device_a", one, two)
        val b = descriptor("device_b", two, one)
        assertEquals(
            DeviceTrustCrypto.directoryHash(snapshot(4, listOf(a, b))),
            DeviceTrustCrypto.directoryHash(snapshot(4, listOf(b, a))),
        )
    }

    @Test fun claimedDirectoryHashMustMatchCanonicalDirectory() {
        val original = snapshot(1, listOf(descriptor("device_a", one, two)))
        val valid = original.copy(claimedDirectoryHash = DeviceTrustCrypto.directoryHash(original))
        assertTrue(TrustDirectoryValidator.validate(valid, null, emptyList()) is TrustDecision.Accept)
        assertRejects("hash mismatch", original.copy(claimedDirectoryHash = zero), null, emptyList())
    }

    @Test fun recipientKeysetHashIsOrderStableAndTamperSensitive() {
        val a = TrustedRecipientKey(42, "device_a", one, two)
        val b = TrustedRecipientKey(42, "device_b", two, one)
        val expected = DeviceTrustCrypto.recipientKeysetHash(listOf(a, b))
        assertEquals(expected, DeviceTrustCrypto.recipientKeysetHash(listOf(b, a)))
        assertFalse(expected == DeviceTrustCrypto.recipientKeysetHash(listOf(a.copy(pubKey = two), b)))
    }

    @Test fun firstUseIsAcceptedButUnsignedNewDeviceIsRejected() {
        val initial = snapshot(1, listOf(descriptor("device_a", one, two)))
        val first = TrustDirectoryValidator.validate(initial, null, emptyList())
        assertTrue(first is TrustDecision.Accept && first.firstUse)
        val state = stateFor(initial)
        val pin = pinFor(initial.devices.single())
        val next = snapshot(2, initial.devices + descriptor("device_b", two, one))
        assertTrue(TrustDirectoryValidator.validate(next, state, listOf(pin)) is TrustDecision.Reject)
    }

    @Test fun lateStartApprovalCertificateChainAndTamperAreVerified() {
        val bootstrap = descriptor("device_a", one, two)
        val subject = descriptor("device_b", two, one)
        val base = TrustedDirectorySnapshot(42, two, 5, listOf(bootstrap, subject))
        val hash = DeviceTrustCrypto.directoryHash(base)
        val approval = DeviceApprovalStatement(42, subject.sid, subject.pubKey, subject.sigPub,
            subject.kind, zero, 4)
        val signature = CryptoUtil.b64u(ByteArray(64))
        val proof = DirectoryProof(
            42, two, 5, hash, 1,
            listOf(
                DeviceHistoryEntry(bootstrap.sid, bootstrap.kind, bootstrap.pubKey, bootstrap.sigPub,
                    "approved", zero, bootstrap.sid, null),
                DeviceHistoryEntry(subject.sid, subject.kind, subject.pubKey, subject.sigPub,
                    "approved", zero, bootstrap.sid, signature),
            ),
            listOf(ApprovalCertificate(subject.sid, bootstrap.sid, 4, 5,
                approval.canonical(), signature)),
        )
        val snapshot = base.copy(claimedDirectoryHash = hash, proof = proof)
        assertEquals(null, verifyDirectoryProof(proof, snapshot, false) { _, sig, pk ->
            sig == signature && pk == bootstrap.sigPub
        })
        assertTrue(verifyDirectoryProof(
            proof.copy(approvalCertificates = proof.approvalCertificates.map { it.copy(statement = it.statement + "x") }),
            snapshot, false,
        ) { _, _, _ -> true }!!.contains("invalid"))
    }

    @Test fun inflatedVerifiedEpochAfterCertificateChainIsRejected() {
        val bootstrap = descriptor("device_a", one, two)
        val subject = descriptor("device_b", two, one)
        val base = TrustedDirectorySnapshot(42, two, 6, listOf(bootstrap, subject))
        val hash = DeviceTrustCrypto.directoryHash(base)
        val approval = DeviceApprovalStatement(
            42, subject.sid, subject.pubKey, subject.sigPub, subject.kind, zero, 4,
        )
        val signature = CryptoUtil.b64u(ByteArray(64))
        val proof = DirectoryProof(
            42, two, 6, hash, 1,
            listOf(
                DeviceHistoryEntry(
                    bootstrap.sid, bootstrap.kind, bootstrap.pubKey, bootstrap.sigPub,
                    "approved", zero, bootstrap.sid, null,
                ),
                DeviceHistoryEntry(
                    subject.sid, subject.kind, subject.pubKey, subject.sigPub,
                    "approved", zero, bootstrap.sid, signature,
                ),
            ),
            listOf(
                ApprovalCertificate(
                    subject.sid, bootstrap.sid, 4, 5, approval.canonical(), signature,
                ),
            ),
        )
        val snapshot = base.copy(claimedDirectoryHash = hash, proof = proof)

        assertEquals(
            "certificate epoch does not match directory epoch",
            verifyDirectoryProof(proof, snapshot, false) { _, _, _ -> true },
        )
    }

    @Test fun rootOnlyVerifiedDirectoryRequiresEpochOne() {
        val accepted = snapshot(1, listOf(descriptor("device_a", one, two)))
        assertEquals(null, verifyDirectoryProof(accepted.proof!!, accepted, true))

        val inflated = snapshot(2, accepted.devices)
        assertEquals(
            "root-only verified directory must have security epoch 1",
            verifyDirectoryProof(inflated.proof!!, inflated, true),
        )
    }

    @Test fun legacyRootOnlyDirectoryKeepsArbitraryEpochCompatibility() {
        val legacy = snapshot(
            7, listOf(descriptor("device_a", one, two)), securityMode = "legacy_v1",
        )
        assertEquals(null, verifyDirectoryProof(legacy.proof!!, legacy, true))
    }

    @Test fun rollbackAndSameEpochEquivocationAreRejected() {
        val accepted = snapshot(7, listOf(descriptor("device_a", one, two)))
        val state = stateFor(accepted)
        assertRejects("rollback", snapshot(6, accepted.devices), state, emptyList())
        assertRejects(
            "equivocation",
            snapshot(7, accepted.devices + descriptor("device_b", two, one)),
            state,
            emptyList(),
        )
    }

    @Test fun sameSidBoxSignOrKindChangeIsRejected() {
        val original = descriptor("device_a", one, two)
        val pin = pinFor(original)
        val state = stateFor(snapshot(2, listOf(original)))
        assertRejects("key changed", snapshot(3, listOf(original.copy(pubKey = two))), state, listOf(pin))
        assertRejects("key changed", snapshot(3, listOf(original.copy(sigPub = one))), state, listOf(pin))
        assertRejects("key changed", snapshot(3, listOf(original.copy(kind = "web"))), state, listOf(pin))
    }

    @Test fun identityKeyChangeAndDuplicateSidAreRejected() {
        val original = snapshot(2, listOf(descriptor("device_a", one, two)))
        assertRejects("identity", original.copy(epoch = 3, identityKey = zero), stateFor(original), emptyList())
        assertTrue(
            TrustDirectoryValidator.validate(
                original.copy(devices = original.devices + original.devices.single()), null, emptyList(),
            ) is TrustDecision.Reject,
        )
    }

    private fun descriptor(sid: String, box: String, sign: String) =
        TrustedDeviceDescriptor(sid, box, sign, "android_gateway", sid)

    private fun snapshot(
        epoch: Long,
        devices: List<TrustedDeviceDescriptor>,
        securityMode: String = "verified_v2",
    ): TrustedDirectorySnapshot {
        val base = TrustedDirectorySnapshot(42, two, epoch, devices)
        val hash = DeviceTrustCrypto.directoryHash(base)
        val history = devices.mapIndexed { index, d ->
            DeviceHistoryEntry(
                d.sid, d.kind, d.pubKey, d.sigPub, "approved", zero,
                if (index == 0) d.sid else "legacy_tofu", null,
            )
        }
        val proof = DirectoryProof(
            42, two, epoch, hash, 1, history, emptyList(), securityMode = securityMode,
        )
        return base.copy(claimedDirectoryHash = hash, proof = proof)
    }

    private fun stateFor(snapshot: TrustedDirectorySnapshot) = TrustDirectoryState(
        snapshot.uid, snapshot.identityKey, snapshot.epoch,
        DeviceTrustCrypto.directoryHash(snapshot), DeviceTrustCrypto.safetyNumber(snapshot.uid, snapshot.identityKey), 1,
    )

    private fun pinFor(d: TrustedDeviceDescriptor) = TrustedDevicePin(
        d.sid, 42, d.name, d.kind, d.pubKey, d.sigPub,
        DeviceTrustCrypto.deviceFingerprint(d.pubKey, d.sigPub), 1, 1,
    )

    private fun assertRejects(
        expected: String,
        snapshot: TrustedDirectorySnapshot,
        state: TrustDirectoryState?,
        pins: List<TrustedDevicePin>,
    ) {
        val result = TrustDirectoryValidator.validate(snapshot, state, pins)
        assertTrue(result is TrustDecision.Reject)
        assertTrue((result as TrustDecision.Reject).reason.contains(expected))
    }

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }
}
