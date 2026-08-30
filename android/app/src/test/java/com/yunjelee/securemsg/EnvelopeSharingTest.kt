package com.yunjelee.securemsg

import com.goterl.lazysodium.interfaces.Box
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Envelope support for history sharing: a key entry re-wrapped by another
 * device of the same account carries `by`, and is opened with THAT device's
 * public key instead of the message sender's.
 */
class EnvelopeSharingTest {

    private val messageKeyBytes = 32

    @Test
    fun envelopeJsonRoundTripsWithoutBy() {
        val env = CryptoUtil.Envelope(
            ct = "Y3Q", nonce = "bm9uY2U",
            keys = mapOf("sid-a" to CryptoUtil.EnvelopeKey("ZWs", "bg")),
        )

        val json = CryptoUtil.envelopeToJson(env)
        val entry = json.getJSONObject("keys").getJSONObject("sid-a")

        // Absent, not null: older clients read a missing `by` as a sender key.
        assertFalse(entry.has("by"))
        assertEquals(env, CryptoUtil.envelopeFromJson(json))
        assertNull(CryptoUtil.envelopeFromJson(json).keys.getValue("sid-a").by)
    }

    @Test
    fun envelopeJsonRoundTripsWithBy() {
        val env = CryptoUtil.Envelope(
            ct = "Y3Q", nonce = "bm9uY2U",
            keys = mapOf(
                "sid-a" to CryptoUtil.EnvelopeKey("ZWs", "bg"),
                "sid-c" to CryptoUtil.EnvelopeKey("ZWsy", "bjI", by = "sid-b"),
            ),
        )

        val json = CryptoUtil.envelopeToJson(env)
        val decoded = CryptoUtil.envelopeFromJson(json)

        assertEquals("sid-b", json.getJSONObject("keys").getJSONObject("sid-c").getString("by"))
        assertEquals(env, decoded)
        assertNull(decoded.keys.getValue("sid-a").by)
    }

    @Test
    fun envelopeFromJsonTreatsBlankByAsAbsent() {
        val json = JSONObject(
            """{"ct":"Y3Q","nonce":"bm9uY2U","keys":{"sid-a":{"ek":"ZWs","n":"bg","by":""}}}""",
        )

        assertNull(CryptoUtil.envelopeFromJson(json).keys.getValue("sid-a").by)
    }

    @Test
    fun wrongLengthEkIsRejectedBeforeTheNativeCall() {
        // cryptoBoxOpenEasy writes ek.size - MACBYTES bytes into a 32-byte
        // buffer, so an over-long ek must never reach it.
        val overLong = CryptoUtil.b64u(ByteArray(messageKeyBytes + Box.MACBYTES + 1))
        val tooShort = CryptoUtil.b64u(ByteArray(messageKeyBytes + Box.MACBYTES - 1))
        val nonce = CryptoUtil.b64u(ByteArray(Box.NONCEBYTES))
        val keypair = dummyKeypair()

        for (ek in listOf(overLong, tooShort, "")) {
            val env = envelopeWith(CryptoUtil.EnvelopeKey(ek, nonce))
            assertNull(CryptoUtil.decryptMessage(env, "sid-a", keypair, keypair.boxPk))
            assertNull(
                CryptoUtil.rewrapMessageKey(env, "sid-a", keypair, keypair.boxPk, keypair.boxPk),
            )
        }
    }

    @Test
    fun wrongLengthNonceOrKeyIsRejectedBeforeTheNativeCall() {
        val ek = CryptoUtil.b64u(ByteArray(messageKeyBytes + Box.MACBYTES))
        val keypair = dummyKeypair()

        val shortNonce = envelopeWith(
            CryptoUtil.EnvelopeKey(ek, CryptoUtil.b64u(ByteArray(Box.NONCEBYTES - 1))),
        )
        assertNull(CryptoUtil.decryptMessage(shortNonce, "sid-a", keypair, keypair.boxPk))

        val goodNonce = envelopeWith(
            CryptoUtil.EnvelopeKey(ek, CryptoUtil.b64u(ByteArray(Box.NONCEBYTES))),
        )
        val shortPubKey = CryptoUtil.b64u(ByteArray(Box.PUBLICKEYBYTES - 1))
        assertNull(CryptoUtil.decryptMessage(goodNonce, "sid-a", keypair, shortPubKey))
    }

    @Test
    fun byEntryFailsClosedWithoutAResolvedWrapperKey() {
        val env = envelopeWith(
            CryptoUtil.EnvelopeKey(
                CryptoUtil.b64u(ByteArray(messageKeyBytes + Box.MACBYTES)),
                CryptoUtil.b64u(ByteArray(Box.NONCEBYTES)),
                by = "sid-b",
            ),
        )
        val keypair = dummyKeypair()

        // No resolver at all, and a resolver that knows nothing about sid-b:
        // neither may silently fall back to the sender key.
        assertNull(CryptoUtil.decryptMessage(env, "sid-a", keypair, keypair.boxPk))
        assertNull(CryptoUtil.decryptMessage(env, "sid-a", keypair, keypair.boxPk) { null })
    }

    @Test
    fun rewrappedKeyLetsALaterDeviceReadAnOldMessage() {
        assumeTrue("host libsodium unavailable", HostSodium.available)
        val sender = CryptoUtil.generateKeypair()
        val sharer = CryptoUtil.generateKeypair()
        val late = CryptoUtil.generateKeypair()
        val plaintext = "이전 대화 본문"
        val env = CryptoUtil.encryptMessage(
            plaintext, listOf(CryptoUtil.Recipient("sid-sharer", sharer.boxPk)), sender,
        )

        // The later device is not an original recipient.
        assertNull(CryptoUtil.decryptMessage(env, "sid-late", late, sender.boxPk))

        val rewrapped = CryptoUtil.rewrapMessageKey(
            env, "sid-sharer", sharer, sender.boxPk, late.boxPk,
        )!!
        assertEquals("sid-sharer", rewrapped.by)

        // Through the wire format, as the relay stores and returns it.
        val shared = CryptoUtil.envelopeFromJson(
            CryptoUtil.envelopeToJson(env.copy(keys = env.keys + ("sid-late" to rewrapped))),
        )

        assertEquals(
            plaintext,
            CryptoUtil.decryptMessage(shared, "sid-late", late, sender.boxPk) { wrapper ->
                sharer.boxPk.takeIf { wrapper == "sid-sharer" }
            },
        )
        // The sealer is the sharer, so the sender key must not open it, and a
        // resolver handing back the wrong device's key must fail closed.
        assertNull(CryptoUtil.decryptMessage(shared, "sid-late", late, sender.boxPk))
        assertNull(
            CryptoUtil.decryptMessage(shared, "sid-late", late, sender.boxPk) { sender.boxPk },
        )
        // The original recipient's own entry is untouched.
        assertEquals(
            plaintext,
            CryptoUtil.decryptMessage(shared, "sid-sharer", sharer, sender.boxPk),
        )
    }

    @Test
    fun rewrapRefusesADeviceThatCannotOpenTheEnvelope() {
        assumeTrue("host libsodium unavailable", HostSodium.available)
        val sender = CryptoUtil.generateKeypair()
        val recipient = CryptoUtil.generateKeypair()
        val outsider = CryptoUtil.generateKeypair()
        val env = CryptoUtil.encryptMessage(
            "본문", listOf(CryptoUtil.Recipient("sid-recipient", recipient.boxPk)), sender,
        )

        // No entry for this device.
        assertNull(
            CryptoUtil.rewrapMessageKey(env, "sid-outsider", outsider, sender.boxPk, outsider.boxPk),
        )
        // Entry present, but opened with the wrong sealer key.
        assertNull(
            CryptoUtil.rewrapMessageKey(
                env, "sid-recipient", recipient, outsider.boxPk, outsider.boxPk,
            ),
        )
    }

    @Test
    fun rewrapChainsThroughAnAlreadySharedKey() {
        assumeTrue("host libsodium unavailable", HostSodium.available)
        val sender = CryptoUtil.generateKeypair()
        val sharer = CryptoUtil.generateKeypair()
        val second = CryptoUtil.generateKeypair()
        val third = CryptoUtil.generateKeypair()
        val env = CryptoUtil.encryptMessage(
            "본문", listOf(CryptoUtil.Recipient("sid-sharer", sharer.boxPk)), sender,
        )
        val toSecond = CryptoUtil.rewrapMessageKey(
            env, "sid-sharer", sharer, sender.boxPk, second.boxPk,
        )!!
        val shared = env.copy(keys = env.keys + ("sid-second" to toSecond))

        // The second device re-shares: its entry was sealed by the sharer, so
        // the sharer's key is the opener it must resolve.
        val toThird = CryptoUtil.rewrapMessageKey(
            shared, "sid-second", second, sharer.boxPk, third.boxPk,
        )!!
        assertEquals("sid-second", toThird.by)

        val chained = shared.copy(keys = shared.keys + ("sid-third" to toThird))
        assertEquals(
            "본문",
            CryptoUtil.decryptMessage(chained, "sid-third", third, sender.boxPk) { wrapper ->
                second.boxPk.takeIf { wrapper == "sid-second" }
            },
        )
        assertTrue(chained.keys.getValue("sid-sharer").by == null)
    }

    private fun envelopeWith(entry: CryptoUtil.EnvelopeKey) = CryptoUtil.Envelope(
        ct = CryptoUtil.b64u(ByteArray(64)),
        nonce = CryptoUtil.b64u(ByteArray(24)),
        keys = mapOf("sid-a" to entry),
    )

    /** Well-formed key material for the guards; never reaches libsodium. */
    private fun dummyKeypair() = CryptoUtil.DeviceKeypair(
        boxPk = CryptoUtil.b64u(ByteArray(Box.PUBLICKEYBYTES) { 1 }),
        boxSk = CryptoUtil.b64u(ByteArray(Box.SECRETKEYBYTES) { 2 }),
        signPk = CryptoUtil.b64u(ByteArray(32) { 3 }),
        signSk = CryptoUtil.b64u(ByteArray(64) { 4 }),
    )
}
