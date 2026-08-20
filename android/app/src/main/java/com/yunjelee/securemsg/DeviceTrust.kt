package com.yunjelee.securemsg

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.math.BigInteger
import java.util.Locale

private const val APPROVAL_DOMAIN = "securemsg-device-approval-v1"
private const val APPROVAL_DOMAIN_V2 = "securemsg-device-approval-v2"
private const val FINGERPRINT_DOMAIN = "securemsg-device-fingerprint-v1\n"
private const val SAFETY_DOMAIN = "securemsg-account-safety-v1\n"

data class DeviceLoginStatement(
    val uid: Long, val sid: String, val challengeId: String,
    val challenge: String, val sessionVersion: Long,
) {
    fun canonical(): String {
        require(uid >= 0 && sessionVersion >= 0)
        requireToken("sid", sid)
        requireToken("challenge_id", challengeId)
        requireB64u("challenge", challenge, 32)
        return "securemsg-device-login-v1\nuid=$uid\nsid=$sid\n" +
            "challenge_id=$challengeId\nchallenge=$challenge\n" +
            "session_version=$sessionVersion\n"
    }
}

data class DeviceApprovalStatement(
    val uid: Long,
    val subjectSid: String,
    val pubKey: String,
    val sigPub: String,
    val kind: String,
    val challenge: String,
    val parentEpoch: Long,
) {
    fun canonical(): String {
        require(uid >= 0) { "uid must be non-negative" }
        require(parentEpoch >= 0) { "parent_epoch must be non-negative" }
        requireToken("subject_sid", subjectSid)
        requireToken("kind", kind)
        requireB64u("pub_key", pubKey, 32)
        requireB64u("sig_pub", sigPub, 32)
        requireB64u("challenge", challenge, 32)
        return "$APPROVAL_DOMAIN\n" +
            "uid=$uid\n" +
            "subject_sid=$subjectSid\n" +
            "pub_key=$pubKey\n" +
            "sig_pub=$sigPub\n" +
            "kind=$kind\n" +
            "challenge=$challenge\n" +
            "parent_epoch=$parentEpoch\n"
    }
}

/** The QR pairing session an approval certificate is bound to (v2 only). */
data class PairingBinding(
    val pairingId: String,
    val nonceNew: String,
    val nonceApprover: String,
)

/**
 * v2 binds one QR pairing session into the approval certificate so a signature
 * can never be replayed onto a different scan. Field order must match the
 * relay's `approval_statement` and the web client byte for byte; all three are
 * pinned by golden-vector tests.
 */
data class DeviceApprovalStatementV2(
    val fields: DeviceApprovalStatement,
    val pairing: PairingBinding,
) {
    fun canonical(): String {
        require(fields.uid >= 0) { "uid must be non-negative" }
        require(fields.parentEpoch >= 0) { "parent_epoch must be non-negative" }
        requireToken("subject_sid", fields.subjectSid)
        requireToken("kind", fields.kind)
        requireB64u("pub_key", fields.pubKey, 32)
        requireB64u("sig_pub", fields.sigPub, 32)
        requireB64u("challenge", fields.challenge, 32)
        requireToken("pairing_id", pairing.pairingId)
        requireB64u("nonce_new", pairing.nonceNew, 32)
        requireB64u("nonce_approver", pairing.nonceApprover, 32)
        return "$APPROVAL_DOMAIN_V2\n" +
            "uid=${fields.uid}\n" +
            "subject_sid=${fields.subjectSid}\n" +
            "pub_key=${fields.pubKey}\n" +
            "sig_pub=${fields.sigPub}\n" +
            "kind=${fields.kind}\n" +
            "challenge=${fields.challenge}\n" +
            "pairing_id=${pairing.pairingId}\n" +
            "nonce_new=${pairing.nonceNew}\n" +
            "nonce_approver=${pairing.nonceApprover}\n" +
            "parent_epoch=${fields.parentEpoch}\n"
    }
}

/** Read one `key=value` line out of a canonical statement. */
private fun statementField(statement: String, key: String): String? =
    statement.lineSequence().firstOrNull { it.startsWith("$key=") }?.substring(key.length + 1)

/**
 * Recover the pairing binding a v2 statement claims. The caller MUST re-render
 * the canonical statement from the result and compare it to the original —
 * that byte comparison, not this parse, is what rejects smuggled content.
 */
fun pairingBindingFromStatement(statement: String): PairingBinding? {
    val pairingId = statementField(statement, "pairing_id") ?: return null
    val nonceNew = statementField(statement, "nonce_new") ?: return null
    val nonceApprover = statementField(statement, "nonce_approver") ?: return null
    return PairingBinding(pairingId, nonceNew, nonceApprover)
}

/**
 * The canonical form a stored certificate must match. v1 (password +
 * fingerprint compare) and v2 (QR pairing) certificates coexist in one chain,
 * and the statement's own domain line decides which. A statement that claims
 * v2 but carries no binding is rejected rather than re-checked under v1.
 */
fun canonicalApprovalForStatement(
    fields: DeviceApprovalStatement,
    statement: String,
): String? = if (!statement.startsWith("$APPROVAL_DOMAIN_V2\n")) {
    runCatching { fields.canonical() }.getOrNull()
} else {
    pairingBindingFromStatement(statement)?.let { binding ->
        runCatching { DeviceApprovalStatementV2(fields, binding).canonical() }.getOrNull()
    }
}

data class DeviceRevokeStatement(
    val uid: Long,
    val subjectSid: String,
    val subjectPubKey: String,
    val subjectSigPub: String,
    val actorSid: String,
    val parentEpoch: Long,
) {
    fun canonical(): String {
        require(uid >= 0 && parentEpoch >= 0)
        requireToken("subject_sid", subjectSid)
        requireToken("actor_sid", actorSid)
        requireB64u("subject_pub_key", subjectPubKey, 32)
        requireB64u("subject_sig_pub", subjectSigPub, 32)
        return "securemsg-device-revoke-v1\n" +
            "uid=$uid\nsubject_sid=$subjectSid\nsubject_pub_key=$subjectPubKey\n" +
            "subject_sig_pub=$subjectSigPub\nactor_sid=$actorSid\n" +
            "parent_epoch=$parentEpoch\nreason=user_revoked\n"
    }
}

data class LegacySecurityUpgradeStatement(
    val uid: Long, val identitySid: String, val identitySigPub: String, val parentEpoch: Long,
) {
    fun canonical(): String {
        require(uid >= 0 && parentEpoch >= 0)
        requireToken("identity_sid", identitySid)
        requireB64u("identity_sig_pub", identitySigPub, 32)
        return "securemsg-legacy-upgrade-v1\nuid=$uid\nidentity_sid=$identitySid\n" +
            "identity_sig_pub=$identitySigPub\nparent_epoch=$parentEpoch\n"
    }
}

data class TrustedDeviceDescriptor(
    val sid: String,
    val pubKey: String,
    val sigPub: String,
    val kind: String,
    val name: String = sid,
)

data class TrustedRecipientKey(
    val userId: Long,
    val sid: String,
    val pubKey: String,
    val sigPub: String,
)

data class TrustedDirectorySnapshot(
    val uid: Long,
    val identityKey: String,
    val epoch: Long,
    val devices: List<TrustedDeviceDescriptor>,
    val claimedDirectoryHash: String? = null,
    val proof: DirectoryProof? = null,
)

data class DeviceHistoryEntry(
    val sid: String,
    val kind: String,
    val pubKey: String,
    val sigPub: String,
    val trustState: String,
    val challenge: String,
    val approvedBySid: String?,
    val approvalSignature: String?,
)

data class ApprovalCertificate(
    val subjectSid: String,
    val approverSid: String,
    val parentEpoch: Long,
    val resultingEpoch: Long,
    val statement: String,
    val signature: String,
)

data class RevocationCertificate(
    val subjectSid: String, val actorSid: String, val parentEpoch: Long,
    val resultingEpoch: Long, val reason: String, val statement: String, val signature: String,
)

data class SecurityUpgradeCertificate(
    val identitySid: String, val parentEpoch: Long, val resultingEpoch: Long,
    val statement: String, val signature: String,
)

data class DirectoryProof(
    val userId: Long,
    val identitySigPub: String,
    val securityEpoch: Long,
    val directoryHash: String,
    val trustEnforcedAt: Long?,
    val deviceHistory: List<DeviceHistoryEntry>,
    val approvalCertificates: List<ApprovalCertificate>,
    val securityMode: String = "verified_v2",
    val revocationCertificates: List<RevocationCertificate> = emptyList(),
    val securityUpgradeCertificates: List<SecurityUpgradeCertificate> = emptyList(),
)

data class PendingDeviceApproval(
    val uid: Long,
    val sid: String,
    val name: String,
    val kind: String,
    val pubKey: String,
    val sigPub: String,
    val challenge: String,
    val parentEpoch: Long,
    val requestedAt: Long? = null,
) {
    fun statement(): DeviceApprovalStatement = DeviceApprovalStatement(
        uid, sid, pubKey, sigPub, kind, challenge, parentEpoch,
    )
}

sealed interface PendingDevicesResult {
    data class Available(val devices: List<PendingDeviceApproval>) : PendingDevicesResult
    data object Unsupported : PendingDevicesResult
    data class Failed(val message: String) : PendingDevicesResult
}

interface TrustedDeviceApi {
    fun pendingDevices(): PendingDevicesResult
    fun approveDevice(
        device: PendingDeviceApproval,
        signature: String,
        pairing: PairingBinding? = null,
    ): Boolean
    fun rejectPendingDevice(device: PendingDeviceApproval): Boolean
    /** Returns (pairing_id, nonce_approver) or null when the relay refuses. */
    fun openPairingSession(device: PendingDeviceApproval, nonceNew: String): Pair<String, String>?
}

class RelayTrustedDeviceApi(
    private val api: RelayApi,
    private val uid: Long,
) : TrustedDeviceApi {
    internal fun loadDeviceResponse(): JSONObject = api.listDevices()
    internal fun loadDirectoryResponse(): JSONObject = api.keyDirectory()
    internal fun pendingStatus(): JSONObject = api.pendingDeviceStatus()
    internal fun revokeOwnPending(): Boolean = api.revokeOwnPendingDevice().optBoolean("ok")
    internal fun upgradeLegacy(parentEpoch: Long, signature: String): Boolean =
        api.upgradeLegacySecurity(parentEpoch, signature).optBoolean("ok")

    override fun pendingDevices(): PendingDevicesResult = try {
        val response = loadDeviceResponse()
        if (!response.optBoolean("ok")) {
            if (response.optInt("_http_status") == 404) PendingDevicesResult.Unsupported
            else PendingDevicesResult.Failed(response.optString("error", "기기 목록 조회 실패"))
        } else {
            val devices = response.optJSONArray("devices") ?: JSONArray()
            val epoch = response.optLong("security_epoch", -1)
            val pending = (0 until devices.length()).mapNotNull { index ->
                val obj = devices.optJSONObject(index) ?: return@mapNotNull null
                if (obj.optString("trust_state") != "pending") return@mapNotNull null
                val challenge = obj.optString("challenge")
                if (challenge.isBlank()) return@mapNotNull null
                PendingDeviceApproval(
                    uid = obj.optLong("uid", uid),
                    sid = obj.getString("sid"),
                    name = obj.optString("name", obj.getString("sid")),
                    kind = obj.getString("kind"),
                    pubKey = obj.getString("pub_key"),
                    sigPub = obj.getString("sig_pub"),
                    challenge = challenge,
                    parentEpoch = epoch,
                    requestedAt = obj.optLong("created_at").takeIf { obj.has("created_at") },
                )
            }
            PendingDevicesResult.Available(pending)
        }
    } catch (e: Exception) {
        PendingDevicesResult.Failed(e.message ?: "기기 목록 조회 실패")
    }

    override fun approveDevice(
        device: PendingDeviceApproval,
        signature: String,
        pairing: PairingBinding?,
    ): Boolean = api.approveDevice(device.sid, device.parentEpoch, signature, pairing)
        .optBoolean("ok")

    override fun openPairingSession(
        device: PendingDeviceApproval,
        nonceNew: String,
    ): Pair<String, String>? {
        val response = api.createPairingSession(device.sid, device.challenge, nonceNew)
        if (!response.optBoolean("ok")) return null
        val pairingId = response.optString("pairing_id")
        val nonceApprover = response.optString("nonce_approver")
        if (pairingId.isEmpty() || nonceApprover.isEmpty()) return null
        return pairingId to nonceApprover
    }

    override fun rejectPendingDevice(device: PendingDeviceApproval): Boolean =
        api.rejectPendingDevice(device.sid, device.challenge, device.parentEpoch).optBoolean("ok")
}

/** A live pairing session plus the number the two screens must agree on. */
data class PairingHandshake(
    val binding: PairingBinding,
    val safetyNumber: String,
)

data class DeviceSecurityView(
    val pending: List<PendingDeviceApproval> = emptyList(),
    val serverUnsupported: Boolean = false,
    val error: String? = null,
    val trustWarning: String? = null,
    val selfPending: Boolean = false,
    /** This device's own registration challenge while it awaits approval. */
    val selfPendingChallenge: String? = null,
    val securityMode: String? = null,
)

class DeviceSecurityController(
    private val api: RelayTrustedDeviceApi,
    private val credentials: SavedCredentials,
    private val trustRepository: DeviceTrustRepository,
) {
    suspend fun refresh(): DeviceSecurityView {
        return try {
        val directoryResponse = api.loadDirectoryResponse()
        if (!directoryResponse.optBoolean("ok")) {
            if (directoryResponse.optInt("_http_status") == 404) return DeviceSecurityView(serverUnsupported = true)
            if (directoryResponse.optInt("_http_status") == 403) {
                val status = runCatching { api.pendingStatus() }.getOrNull()
                if (status?.optBoolean("ok") == true && status.optString("trust_state") == "pending") {
                    return DeviceSecurityView(
                        selfPending = true,
                        selfPendingChallenge = status.optString("challenge").takeIf { it.isNotEmpty() },
                    )
                }
            }
            return DeviceSecurityView(error = directoryResponse.optString("error", "키 디렉터리 조회 실패"))
        }
        val identity = directoryResponse.optString("identity_sig_pub")
        val epoch = directoryResponse.optLong("security_epoch", -1)
        val array = directoryResponse.optJSONArray("devices") ?: JSONArray()
        val approved = mutableListOf<TrustedDeviceDescriptor>()
        val pending = mutableListOf<PendingDeviceApproval>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            when (obj.optString("trust_state")) {
                "approved", "" -> approved += TrustedDeviceDescriptor(
                    sid = obj.getString("sid"),
                    pubKey = obj.getString("pub_key"),
                    sigPub = obj.getString("sig_pub"),
                    kind = obj.getString("kind"),
                    name = obj.optString("name", obj.getString("sid")),
                )
            }
        }
        // Pending challenges are intentionally absent from the public key directory.
        // Only an already-approved caller can see them via /api/devices.
        val devicesResponse = api.loadDeviceResponse()
        val pendingArray = devicesResponse.optJSONArray("devices") ?: JSONArray()
        for (i in 0 until pendingArray.length()) {
            val obj = pendingArray.optJSONObject(i) ?: continue
            if (obj.optString("trust_state") == "pending" && obj.optString("challenge").isNotBlank()) {
                    pending += PendingDeviceApproval(
                        uid = obj.optLong("uid", credentials.uid.toLong()),
                        sid = obj.getString("sid"),
                        name = obj.optString("name", obj.getString("sid")),
                        kind = obj.getString("kind"),
                        pubKey = obj.getString("pub_key"),
                        sigPub = obj.getString("sig_pub"),
                        challenge = obj.getString("challenge"),
                        parentEpoch = obj.optLong("approval_epoch", epoch),
                        requestedAt = obj.optLong("created_at").takeIf { obj.has("created_at") },
                    )
            }
        }
        if (identity.isBlank() || epoch < 0) {
            return DeviceSecurityView(
                pending = pending,
                trustWarning = "서버가 계정 identity/보안 epoch를 제공하지 않아 키 디렉터리를 신뢰할 수 없습니다.",
            )
        }
        val own = approved.firstOrNull { it.sid == credentials.sid }
        if (own == null || own.pubKey != credentials.keypair.boxPk ||
            own.sigPub != credentials.keypair.signPk || own.kind != "android_gateway"
        ) {
            return DeviceSecurityView(
                pending = pending,
                trustWarning = "키 디렉터리에 현재 기기가 없거나 현재 기기의 공개키가 변경되었습니다.",
            )
        }
        when (val decision = trustRepository.apply(
            TrustedDirectorySnapshot(
                credentials.uid.toLong(), identity, epoch, approved,
                directoryResponse.optString("directory_hash").takeIf { it.isNotBlank() },
                directoryProofFromJson(directoryResponse),
            ),
        )) {
            is TrustDecision.Accept -> DeviceSecurityView(
                pending = pending,
                securityMode = directoryResponse.optString("security_mode"),
                trustWarning = if (directoryResponse.optString("security_mode") == "legacy_v1") {
                    "레거시 TOFU 계정입니다. identity 기기에서 보안 업그레이드가 필요합니다."
                } else null,
            )
            is TrustDecision.Reject -> DeviceSecurityView(
                pending = pending,
                trustWarning = "키 디렉터리 검증 차단: ${decision.reason}",
            )
        }
        } catch (e: Exception) {
            DeviceSecurityView(error = e.message ?: "기기 보안 조회 실패")
        }
    }

    fun approve(device: PendingDeviceApproval): Boolean {
        require(device.uid == credentials.uid.toLong()) { "pending device belongs to another account" }
        val signature = DeviceTrustCrypto.signApproval(device.statement(), credentials.keypair.signSk)
        return api.approveDevice(device, signature)
    }

    /**
     * Bind a scanned QR to one pending device. Returns the safety number both
     * screens must show; the caller shows it to the user and only calls
     * [approvePaired] once a human confirms the two match.
     */
    fun openPairing(device: PendingDeviceApproval, scanned: PairingQrFields): PairingHandshake? {
        require(device.uid == credentials.uid.toLong()) { "pending device belongs to another account" }
        // The relay's own pending row is the authority on the subject's keys.
        // A QR claiming different ones is stale or an attempted key swap.
        if (device.sid != scanned.sid || device.pubKey != scanned.boxPk ||
            device.sigPub != scanned.sigPk || device.challenge != scanned.challenge
        ) return null
        val (pairingId, nonceApprover) =
            api.openPairingSession(device, scanned.nonceNew) ?: return null
        return PairingHandshake(
            binding = PairingBinding(pairingId, scanned.nonceNew, nonceApprover),
            safetyNumber = pairingSafetyNumber(
                nonceNew = scanned.nonceNew,
                nonceApprover = nonceApprover,
                sid = device.sid,
                pubKey = device.pubKey,
                sigPub = device.sigPub,
            ),
        )
    }

    fun approvePaired(device: PendingDeviceApproval, handshake: PairingHandshake): Boolean {
        require(device.uid == credentials.uid.toLong()) { "pending device belongs to another account" }
        val signature = DeviceTrustCrypto.signApprovalV2(
            DeviceApprovalStatementV2(device.statement(), handshake.binding),
            credentials.keypair.signSk,
        )
        return api.approveDevice(device, signature, handshake.binding)
    }

    fun reject(device: PendingDeviceApproval): Boolean = api.rejectPendingDevice(device)

    fun cancelOwnPending(): Boolean = api.revokeOwnPending()

    suspend fun upgradeLegacySecurity(): Boolean {
        val state = trustRepository.currentState(credentials.uid.toLong()) ?: return false
        if (state.identityKey != credentials.keypair.signPk) return false
        val statement = LegacySecurityUpgradeStatement(
            credentials.uid.toLong(), credentials.sid, credentials.keypair.signPk, state.epoch,
        )
        val signature = CryptoUtil.signDetached(
            statement.canonical().toByteArray(Charsets.UTF_8), credentials.keypair.signSk,
        )
        return api.upgradeLegacy(state.epoch, signature)
    }
}

object DeviceTrustCrypto {
    fun signApproval(statement: DeviceApprovalStatement, signSecretKey: String): String =
        CryptoUtil.signDetached(statement.canonical().toByteArray(Charsets.UTF_8), signSecretKey)

    fun signApprovalV2(statement: DeviceApprovalStatementV2, signSecretKey: String): String =
        CryptoUtil.signDetached(statement.canonical().toByteArray(Charsets.UTF_8), signSecretKey)

    fun verifyApproval(
        statement: DeviceApprovalStatement,
        signature: String,
        signerPublicKey: String,
    ): Boolean = CryptoUtil.verifyDetached(
        statement.canonical().toByteArray(Charsets.UTF_8), signature, signerPublicKey,
    )

    fun deviceFingerprint(pubKey: String, sigPub: String): String {
        requireB64u("pub_key", pubKey, 32)
        requireB64u("sig_pub", sigPub, 32)
        val digest = sha256(
            "$FINGERPRINT_DOMAIN".toByteArray() +
                "pub_key=$pubKey\nsig_pub=$sigPub\n".toByteArray(),
        )
        return digest.joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xff) }
            .chunked(4).joinToString(" ")
    }

    fun safetyNumber(uid: Long, identityKey: String): String {
        require(uid >= 0) { "uid must be non-negative" }
        requireB64u("identity_key", identityKey, 32)
        val digest = sha256(
            "$SAFETY_DOMAIN".toByteArray() + "identity_sig_pub=$identityKey\n".toByteArray(),
        )
        val modulus = BigInteger.TEN.pow(72)
        val digits = BigInteger(1, digest.copyOfRange(0, 30)).mod(modulus)
            .toString().padStart(72, '0')
        return digits.chunked(6).joinToString(" ")
    }

    fun safetyQrPayload(uid: Long, identityKey: String): String {
        safetyNumber(uid, identityKey)
        val hash = CryptoUtil.b64u(sha256(
            "$SAFETY_DOMAIN".toByteArray() + "identity_sig_pub=$identityKey\n".toByteArray(),
        ))
        return "securemsg://account-safety/v1?uid=$uid&identity_sig_pub=" +
            URLEncoder.encode(identityKey, StandardCharsets.UTF_8.name()) + "&hash=" +
            URLEncoder.encode(hash, StandardCharsets.UTF_8.name())
    }

    fun directoryHash(snapshot: TrustedDirectorySnapshot): String {
        require(snapshot.uid >= 0 && snapshot.epoch >= 0)
        requireB64u("identity_key", snapshot.identityKey, 32)
        require(snapshot.devices.map { it.sid }.distinct().size == snapshot.devices.size) {
            "duplicate sid in directory"
        }
        require(snapshot.devices.isNotEmpty()) { "directory must contain an approved device" }
        // Byte-for-byte equivalent to server json.dumps(records,
        // separators=(",", ":"), ensure_ascii=True).
        val canonical = buildString {
            append('[')
            snapshot.devices.sortedBy { it.sid }.forEachIndexed { index, d ->
                requireToken("sid", d.sid)
                requireToken("kind", d.kind)
                requireB64u("pub_key", d.pubKey, 32)
                requireB64u("sig_pub", d.sigPub, 32)
                if (index > 0) append(',')
                append("[\"").append(d.sid).append("\",\"").append(d.pubKey)
                    .append("\",\"").append(d.sigPub).append("\",\"").append(d.kind).append("\"]")
            }
            append(']')
        }
        return CryptoUtil.b64u(sha256(canonical.toByteArray()))
    }

    fun recipientKeysetHash(recipients: List<TrustedRecipientKey>): String {
        require(recipients.isNotEmpty()) { "recipient keyset must not be empty" }
        require(recipients.map { it.sid }.distinct().size == recipients.size) { "duplicate recipient sid" }
        val canonical = buildString {
            append('[')
            recipients.sortedWith(
                compareBy<TrustedRecipientKey> { it.userId }.thenBy { it.sid }
                    .thenBy { it.pubKey }.thenBy { it.sigPub },
            ).forEachIndexed { index, recipient ->
                require(recipient.userId >= 0)
                requireToken("sid", recipient.sid)
                requireB64u("pub_key", recipient.pubKey, 32)
                requireB64u("sig_pub", recipient.sigPub, 32)
                if (index > 0) append(',')
                append('[').append(recipient.userId).append(",\"").append(recipient.sid)
                    .append("\",\"").append(recipient.pubKey).append("\",\"")
                    .append(recipient.sigPub).append("\"]")
            }
            append(']')
        }
        return CryptoUtil.b64u(sha256(canonical.toByteArray()))
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

sealed interface TrustDecision {
    data class Accept(val directoryHash: String, val firstUse: Boolean) : TrustDecision
    data class Reject(val reason: String) : TrustDecision
}

object TrustDirectoryValidator {
    fun validate(
        snapshot: TrustedDirectorySnapshot,
        state: TrustDirectoryState?,
        pins: List<TrustedDevicePin>,
    ): TrustDecision {
        val hash = try { DeviceTrustCrypto.directoryHash(snapshot) } catch (e: Exception) {
            return TrustDecision.Reject(e.message ?: "invalid directory")
        }
        snapshot.claimedDirectoryHash?.let { claimed ->
            if (runCatching { requireB64u("directory_hash", claimed, 32) }.isFailure) {
                return TrustDecision.Reject("invalid server directory hash")
            }
            if (claimed != hash) return TrustDecision.Reject("server directory hash mismatch")
        }
        if (state != null) {
            if (state.accountUid != snapshot.uid) return TrustDecision.Reject("account identity mismatch")
            if (state.identityKey != snapshot.identityKey) return TrustDecision.Reject("account identity key changed")
            if (snapshot.epoch < state.epoch) return TrustDecision.Reject("directory epoch rollback")
            if (snapshot.epoch == state.epoch && hash != state.directoryHash) {
                return TrustDecision.Reject("same-epoch directory equivocation")
            }
        }
        val existing = pins.associateBy { it.sid }
        for (device in snapshot.devices) {
            val pin = existing[device.sid] ?: continue
            if (pin.accountUid != snapshot.uid || pin.pubKey != device.pubKey ||
                pin.sigPub != device.sigPub || pin.kind != device.kind
            ) return TrustDecision.Reject("trusted key changed for sid ${device.sid}")
        }
        val proof = snapshot.proof ?: return TrustDecision.Reject("directory proof missing")
        val history = proof.deviceHistory.associateBy { it.sid }
        for (pin in pins) {
            val historical = history[pin.sid] ?: return TrustDecision.Reject("pinned device missing from history")
            if (historical.pubKey != pin.pubKey || historical.sigPub != pin.sigPub ||
                historical.kind != pin.kind
            ) return TrustDecision.Reject("pinned history key changed for sid ${pin.sid}")
        }
        val proofError = verifyDirectoryProof(proof, snapshot, state == null, existing.keys)
        if (proofError != null) return TrustDecision.Reject(proofError)
        return TrustDecision.Accept(hash, state == null)
    }
}

internal fun verifyDirectoryProof(
    proof: DirectoryProof,
    snapshot: TrustedDirectorySnapshot,
    firstUse: Boolean,
    knownTrustedSids: Set<String> = emptySet(),
    // Takes the ALREADY-canonical statement text, so v1 and v2 approvals share
    // one verifier and unit tests can still inject a stub instead of the
    // native sodium binding.
    verifySignature: (String, String, String) -> Boolean = { statement, signature, signerPublicKey ->
        CryptoUtil.verifyDetached(statement.toByteArray(Charsets.UTF_8), signature, signerPublicKey)
    },
): String? {
    if (proof.userId != snapshot.uid || proof.identitySigPub != snapshot.identityKey ||
        proof.securityEpoch != snapshot.epoch || proof.directoryHash != snapshot.claimedDirectoryHash
    ) return "directory proof checkpoint mismatch"
    if (proof.trustEnforcedAt == null || proof.trustEnforcedAt < 0) return "trust enforcement timestamp missing"
    val history = proof.deviceHistory.associateBy { it.sid }
    if (history.size != proof.deviceHistory.size) return "duplicate sid in device history"
    val active = snapshot.devices.associateBy { it.sid }
    if (active.size != snapshot.devices.size) return "duplicate active sid"
    val root = proof.deviceHistory.firstOrNull() ?: return "directory proof has no root device"
    if (root.sigPub != proof.identitySigPub ||
        (root.approvedBySid != root.sid && root.approvedBySid != "legacy_tofu")
    ) return "identity bootstrap device is invalid"
    val trusted = mutableSetOf(root.sid)
    val activeAtEpoch = mutableSetOf(root.sid)
    if (proof.securityMode == "legacy_v1") {
        // Explicitly unverified TOFU state; UI/service must not claim v2 security.
        proof.deviceHistory.filter { it.approvedBySid == "legacy_tofu" }.forEach {
            trusted += it.sid
            if (it.trustState == "approved") activeAtEpoch += it.sid
        }
    } else if (proof.securityMode != "verified_v2") return "unknown security mode"

    data class Event(val epoch: Long, val parent: Long, val type: Int, val index: Int)
    val events = buildList {
        proof.approvalCertificates.forEachIndexed { i, c -> add(Event(c.resultingEpoch, c.parentEpoch, 0, i)) }
        proof.revocationCertificates.forEachIndexed { i, c -> add(Event(c.resultingEpoch, c.parentEpoch, 1, i)) }
        proof.securityUpgradeCertificates.forEachIndexed { i, c -> add(Event(c.resultingEpoch, c.parentEpoch, 2, i)) }
    }.sortedBy { it.epoch }
    var previousEpoch: Long? = null
    for (event in events) {
        if (event.epoch != event.parent + 1 ||
            (previousEpoch != null && event.parent != previousEpoch) || event.epoch > proof.securityEpoch
        ) return "certificate epoch chain invalid"
        when (event.type) {
            0 -> {
                val cert = proof.approvalCertificates[event.index]
                val subject = history[cert.subjectSid] ?: return "approval subject missing from history"
                val approver = history[cert.approverSid] ?: return "approval signer missing from history"
                if (cert.approverSid !in trusted || cert.approverSid !in activeAtEpoch ||
                    cert.subjectSid in trusted || subject.approvedBySid != cert.approverSid
                ) return "approval certificate chain is not anchored"
                val statement = DeviceApprovalStatement(
                    proof.userId, subject.sid, subject.pubKey, subject.sigPub, subject.kind,
                    subject.challenge, cert.parentEpoch,
                )
                val canonical = canonicalApprovalForStatement(statement, cert.statement)
                if (canonical == null || cert.statement != canonical ||
                    cert.signature != subject.approvalSignature ||
                    !verifySignature(canonical, cert.signature, approver.sigPub)
                ) return "approval certificate signature or statement invalid"
                trusted += subject.sid
                activeAtEpoch += subject.sid
            }
            1 -> {
                val cert = proof.revocationCertificates[event.index]
                val subject = history[cert.subjectSid] ?: return "revocation subject missing"
                val actor = history[cert.actorSid] ?: return "revocation actor missing"
                if (cert.reason != "user_revoked" || cert.subjectSid !in activeAtEpoch ||
                    cert.actorSid !in activeAtEpoch
                ) return "revocation certificate chain is not anchored"
                val statement = DeviceRevokeStatement(
                    proof.userId, subject.sid, subject.pubKey, subject.sigPub,
                    actor.sid, cert.parentEpoch,
                )
                if (cert.statement != runCatching { statement.canonical() }.getOrNull() ||
                    !CryptoUtil.verifyDetached(
                        cert.statement.toByteArray(Charsets.UTF_8), cert.signature, actor.sigPub,
                    )
                ) return "revocation certificate signature or statement invalid"
                activeAtEpoch -= subject.sid
            }
            else -> {
                val cert = proof.securityUpgradeCertificates[event.index]
                val statement = LegacySecurityUpgradeStatement(
                    proof.userId, root.sid, root.sigPub, cert.parentEpoch,
                )
                if (cert.identitySid != root.sid ||
                    cert.statement != runCatching { statement.canonical() }.getOrNull() ||
                    !CryptoUtil.verifyDetached(
                        cert.statement.toByteArray(Charsets.UTF_8), cert.signature, root.sigPub,
                    )
                ) return "legacy security upgrade certificate invalid"
            }
        }
        previousEpoch = event.epoch
    }
    if (proof.securityMode == "verified_v2") {
        if (previousEpoch == null) {
            if (proof.securityEpoch != 1L) {
                return "root-only verified directory must have security epoch 1"
            }
        } else if (previousEpoch != proof.securityEpoch) {
            return "certificate epoch does not match directory epoch"
        }
        val activeHistory = history.values.filter { it.trustState == "approved" }.map { it.sid }.toSet()
        if (activeHistory != activeAtEpoch) return "certificate final state differs from directory"
    }
    for ((sid, device) in active) {
        val historical = history[sid] ?: return "active device missing from history"
        if (proof.securityMode == "verified_v2" && sid !in activeAtEpoch) {
            return "active device has no verified approval chain"
        }
        if (historical.trustState != "approved" || historical.pubKey != device.pubKey ||
            historical.sigPub != device.sigPub || historical.kind != device.kind
        ) return "active device differs from verified history"
    }
    return null
}

class DeviceTrustRepository(private val db: AppDatabase) {
    fun observePins(uid: Long): Flow<List<TrustedDevicePin>> = db.deviceTrustDao().observePins(uid)
    fun observeState(uid: Long): Flow<TrustDirectoryState?> = db.deviceTrustDao().observeState(uid)
    suspend fun currentState(uid: Long): TrustDirectoryState? = db.deviceTrustDao().getState(uid)

    suspend fun apply(snapshot: TrustedDirectorySnapshot): TrustDecision = db.withTransaction {
        val dao = db.deviceTrustDao()
        val decision = TrustDirectoryValidator.validate(
            snapshot, dao.getState(snapshot.uid), dao.getPins(snapshot.uid),
        )
        if (decision !is TrustDecision.Accept) return@withTransaction decision
        val now = System.currentTimeMillis()
        snapshot.devices.forEach { d ->
            val existing = dao.getPin(d.sid)
            if (existing == null) {
                dao.insertPin(
                    TrustedDevicePin(
                        d.sid, snapshot.uid, d.name, d.kind, d.pubKey, d.sigPub,
                        DeviceTrustCrypto.deviceFingerprint(d.pubKey, d.sigPub), now, now,
                    ),
                )
            } else {
                dao.touchPin(d.sid, d.name, now)
            }
        }
        val state = TrustDirectoryState(
            snapshot.uid, snapshot.identityKey, snapshot.epoch, decision.directoryHash,
            DeviceTrustCrypto.safetyNumber(snapshot.uid, snapshot.identityKey), now,
        )
        if (dao.getState(snapshot.uid) == null) dao.insertState(state) else dao.updateState(state)
        decision
    }

    companion object {
        fun get(context: Context) = DeviceTrustRepository(AppDatabase.get(context))
    }
}

private fun requireToken(field: String, value: String): String {
    require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(value)) {
        "$field is invalid"
    }
    return value
}

private fun requireB64u(field: String, value: String, bytes: Int): ByteArray {
    require(Regex("^[A-Za-z0-9_-]+$").matches(value)) { "$field must be unpadded base64url" }
    val decoded = try { CryptoUtil.unb64u(value) } catch (_: Exception) { null }
    require(decoded?.size == bytes && CryptoUtil.b64u(decoded) == value) {
        "$field must encode $bytes bytes"
    }
    return decoded
}

internal fun pendingDeviceFromJson(obj: JSONObject): PendingDeviceApproval = PendingDeviceApproval(
    uid = obj.getLong("uid"),
    sid = obj.getString("sid"),
    name = obj.optString("name", obj.getString("sid")),
    kind = obj.getString("kind"),
    pubKey = obj.getString("pub_key"),
    sigPub = obj.getString("sig_pub"),
    challenge = obj.getString("challenge"),
    parentEpoch = obj.getLong("parent_epoch"),
    requestedAt = obj.optLong("requested_at").takeIf { obj.has("requested_at") },
)

internal fun pendingDevicesFromJson(array: JSONArray): List<PendingDeviceApproval> =
    (0 until array.length()).map { pendingDeviceFromJson(array.getJSONObject(it)) }

internal fun directoryProofFromJson(obj: JSONObject): DirectoryProof {
    val historyJson = obj.getJSONArray("device_history")
    val certsJson = obj.getJSONArray("approval_certificates")
    val revocationsJson = obj.getJSONArray("revocation_certificates")
    val upgradesJson = obj.getJSONArray("security_upgrade_certificates")
    return DirectoryProof(
        userId = obj.getLong("user_id"),
        identitySigPub = obj.getString("identity_sig_pub"),
        securityEpoch = obj.getLong("security_epoch"),
        directoryHash = obj.getString("directory_hash"),
        trustEnforcedAt = obj.optLong("trust_enforced_at").takeIf { !obj.isNull("trust_enforced_at") },
        deviceHistory = (0 until historyJson.length()).map { index ->
            val row = historyJson.getJSONObject(index)
            DeviceHistoryEntry(
                sid = row.getString("sid"), kind = row.getString("kind"),
                pubKey = row.getString("pub_key"), sigPub = row.getString("sig_pub"),
                trustState = row.getString("trust_state"), challenge = row.getString("challenge"),
                approvedBySid = row.optString("approved_by_sid").takeIf { !row.isNull("approved_by_sid") },
                approvalSignature = row.optString("approval_signature").takeIf { !row.isNull("approval_signature") },
            )
        },
        approvalCertificates = (0 until certsJson.length()).map { index ->
            val row = certsJson.getJSONObject(index)
            ApprovalCertificate(
                subjectSid = row.getString("subject_sid"), approverSid = row.getString("approver_sid"),
                parentEpoch = row.getLong("parent_epoch"), resultingEpoch = row.getLong("resulting_epoch"),
                statement = row.getString("statement"), signature = row.getString("signature"),
            )
        },
        securityMode = obj.getString("security_mode"),
        revocationCertificates = (0 until revocationsJson.length()).map { index ->
            val row = revocationsJson.getJSONObject(index)
            RevocationCertificate(
                row.getString("subject_sid"), row.getString("actor_sid"),
                row.getLong("parent_epoch"), row.getLong("resulting_epoch"),
                row.getString("reason"), row.getString("statement"), row.getString("signature"),
            )
        },
        securityUpgradeCertificates = (0 until upgradesJson.length()).map { index ->
            val row = upgradesJson.getJSONObject(index)
            SecurityUpgradeCertificate(
                row.getString("identity_sid"), row.getLong("parent_epoch"),
                row.getLong("resulting_epoch"), row.getString("statement"), row.getString("signature"),
            )
        },
    )
}
