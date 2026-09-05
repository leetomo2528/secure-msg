package com.yunjelee.securemsg

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cross-platform password-hash contract.
 *
 * The relay only bcrypts whatever `pw_hash` a client sends, so nothing on the
 * server side reconciles the two implementations: an account registered from
 * the web client is reachable from this app only while both derive the same 32
 * bytes. The web half of the same vector is in frontend/src/crypto/keys.test.ts.
 *
 * Instrumented rather than a host unit test because the constants that decide
 * the answer — lazysodium-android's OPSLIMIT/MEMLIMIT and the libsodium it
 * binds to — are the phone's, not the host's.
 */
@RunWith(AndroidJUnit4::class)
class CryptoUtilInstrumentedTest {

    @Test
    fun passwordHashMatchesTheWebClientsGoldenVector() {
        val salt = CryptoUtil.saltForUser("alice_92")

        assertEquals("Z8TMpFrk1L3TGTqifSaL2A", salt)
        assertEquals(
            "dzuVYr5AiVb52u3imbOmNAxzOtD1gwLxYUS1kVQLNfE",
            CryptoUtil.hashPassword("correct horse", salt),
        )
    }

    @Test
    fun theUsernameSaltFoldsCaseAndNfkcTheWayTheWebClientDoes() {
        // The salt is where the two implementations differ in shape — the web
        // client lowercases and then normalizes, this one normalizes first —
        // so the same account typed in a different case or from a full-width
        // IME has to land on the same salt on either client.
        val canonical = CryptoUtil.saltForUser("alice_92")

        assertEquals(canonical, CryptoUtil.saltForUser("ALICE_92"))
        assertEquals(canonical, CryptoUtil.saltForUser("ａｌｉｃｅ＿９２"))
        assertNotEquals(canonical, CryptoUtil.saltForUser("bob_92"))
    }
}
