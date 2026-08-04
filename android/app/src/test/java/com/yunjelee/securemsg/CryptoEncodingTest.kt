package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format regression for the relay server contract.
 *
 * The server validates every base64url field with the strict regex
 * `[A-Za-z0-9_-]+` (server/auth.py B64U_RE) and requires pw_hash to decode to
 * exactly 32 bytes. Lazysodium's String cryptoPwHash overload encodes with
 * android.util.Base64 NO_WRAP — STANDARD base64 with '+', '/' and '=' — which
 * the server rejects with "pw_hash must be base64url for 32 bytes". These
 * tests pin the url-safe, no-padding encoding used by CryptoUtil.
 */
class CryptoEncodingTest {

    // Same rule as server/auth.py: B64U_RE = re.compile(r"[A-Za-z0-9_-]+")
    private val SERVER_B64U_RE = Regex("[A-Za-z0-9_-]+")

    @Test
    fun b64uOf32BytesIs43CharsOfUrlSafeAlphabet() {
        val raw = ByteArray(CryptoUtil.PW_HASH_BYTES) { it.toByte() }
        val encoded = CryptoUtil.b64u(raw)

        // 32 bytes -> 43 chars without padding.
        assertEquals(43, encoded.length)
        assertTrue(SERVER_B64U_RE.matches(encoded))
        assertFalse(encoded.contains('+'))
        assertFalse(encoded.contains('/'))
        assertFalse(encoded.contains('='))
    }

    @Test
    fun b64uRoundTrips() {
        val raw = ByteArray(64) { (it * 7 % 256).toByte() }
        val decoded = CryptoUtil.unb64u(CryptoUtil.b64u(raw))
        assertTrue(raw.contentEquals(decoded))
    }
}
