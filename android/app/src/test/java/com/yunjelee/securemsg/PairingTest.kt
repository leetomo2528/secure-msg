package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PairingTest {
    private val one = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE"
    private val two = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI"
    private val three = "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM"
    private val four = "BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ"

    private fun qr(overrides: String = ""): String =
        """{"v":1,"type":"securemsg-pairing","server":"https://msg.example.com",""" +
            """"username":"yunje","sid":"android_A1","challenge":"$one",""" +
            """"box_pk":"$two","sig_pk":"$three","nonce_new":"$four",""" +
            """"expires_at":1750000000$overrides}"""

    @Test fun parsesAWellFormedPayload() {
        val parsed = parsePairingQr(qr())
        assertNotNull(parsed)
        assertEquals("android_A1", parsed!!.sid)
        assertEquals(four, parsed.nonceNew)
        assertEquals("https://msg.example.com", parsed.server)
    }

    @Test fun rejectsMalformedOrForeignPayloads() {
        assertNull(parsePairingQr("not json"))
        assertNull(parsePairingQr("""{"v":2,"type":"securemsg-pairing"}"""))
        assertNull(parsePairingQr("""{"v":1,"type":"something-else"}"""))
        // A non-http server, a short SID, and a truncated key are each fatal:
        // every one of them would otherwise reach the pairing endpoint.
        assertNull(parsePairingQr(qr().replace("https://msg.example.com", "ftp://x")))
        assertNull(parsePairingQr(qr().replace("android_A1", "short")))
        assertNull(parsePairingQr(qr().replace(""""box_pk":"$two"""", """"box_pk":"AA"""")))
    }

    @Test fun ourOwnQrPayloadParsesBackWithEveryFieldIntact() {
        // The encoder and the parser live in different files; a renamed key
        // would otherwise only surface when a real phone failed to pair.
        val payload = com.yunjelee.securemsg.ui.pairingQrPayload(
            server = "https://msg.example.com",
            username = "yunje",
            sid = "android_A1",
            challenge = one,
            boxPk = two,
            sigPk = three,
            nonceNew = four,
            nowSeconds = 1_750_000_000L,
        )
        val parsed = parsePairingQr(payload)
        assertNotNull(parsed)
        assertEquals("https://msg.example.com", parsed!!.server)
        assertEquals("yunje", parsed.username)
        assertEquals("android_A1", parsed.sid)
        assertEquals(one, parsed.challenge)
        assertEquals(two, parsed.boxPk)
        assertEquals(three, parsed.sigPk)
        assertEquals(four, parsed.nonceNew)
        assertEquals(1_750_000_600L, parsed.expiresAt)
    }

    @Test fun safetyNumberMatchesTheCrossPlatformGoldenVector() {
        // Identical input must produce this exact string on web
        // (frontend/src/crypto/pairing.test.ts). If these two ever disagree,
        // the two screens show different numbers and every pairing looks like
        // an attack.
        assertEquals(
            "620892-730283-655820-764924-640994",
            pairingSafetyNumber(
                nonceNew = three,
                nonceApprover = four,
                sid = "android_A1",
                pubKey = one,
                sigPub = two,
            ),
        )
    }

    @Test fun safetyNumberChangesWithEveryBoundField() {
        val base = pairingSafetyNumber(three, four, "android_A1", one, two)
        assertEquals(false, base == pairingSafetyNumber(four, four, "android_A1", one, two))
        assertEquals(false, base == pairingSafetyNumber(three, three, "android_A1", one, two))
        assertEquals(false, base == pairingSafetyNumber(three, four, "android_A2", one, two))
        assertEquals(false, base == pairingSafetyNumber(three, four, "android_A1", two, two))
        assertEquals(false, base == pairingSafetyNumber(three, four, "android_A1", one, one))
    }
}
