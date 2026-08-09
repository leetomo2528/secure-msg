package com.yunjelee.securemsg

import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CredentialCipherTest {
    @Test
    fun `account scoped data resets only for a missing or different identity`() {
        assertEquals(false, isDifferentLocalIdentity("alice", 7, "alice", 7))
        assertEquals(true, isDifferentLocalIdentity(null, null, "alice", 7))
        assertEquals(true, isDifferentLocalIdentity("bob", 7, "alice", 7))
        assertEquals(true, isDifferentLocalIdentity("alice", 8, "alice", 7))
    }

    @Test
    fun `secret payload round trips arbitrary utf8`() {
        val expected = CredentialSecrets(
            token = "eyJhbGciOiJIUzI1NiJ9.한글.abc",
            boxSk = "box_-key",
            signSk = "sign_key",
        )

        assertEquals(expected, CredentialSecretCodec.decode(CredentialSecretCodec.encode(expected)))
    }

    @Test
    fun `secret payload rejects truncation and trailing data`() {
        val encoded = CredentialSecretCodec.encode(CredentialSecrets("token", "box", "sign"))

        assertThrows(IllegalArgumentException::class.java) {
            CredentialSecretCodec.decode(encoded.copyOf(encoded.size - 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CredentialSecretCodec.decode(encoded + 0x01)
        }
    }

    @Test
    fun `envelope is versioned base64url and round trips bytes`() {
        val nonce = ByteArray(AndroidCredentialCipher.NONCE_BYTES) { it.toByte() }
        val ciphertext = ByteArray(AndroidCredentialCipher.GCM_TAG_BYTES + 7) { (it + 20).toByte() }
        val serialized = CredentialEnvelope(nonce, ciphertext).serialize()
        val parsed = CredentialEnvelope.parse(serialized)

        assertEquals(3, serialized.split('.').size)
        assertEquals("v1", serialized.substringBefore('.'))
        assertArrayEquals(nonce, parsed.nonce)
        assertArrayEquals(ciphertext, parsed.ciphertext)
        serialized.split('.').drop(1).forEach {
            assertEquals(it, Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(it)))
        }
    }

    @Test
    fun `envelope rejects wrong version nonce and short tag`() {
        val valid = CredentialEnvelope(
            ByteArray(AndroidCredentialCipher.NONCE_BYTES),
            ByteArray(AndroidCredentialCipher.GCM_TAG_BYTES),
        ).serialize()

        assertThrows(IllegalArgumentException::class.java) {
            CredentialEnvelope.parse(valid.replaceFirst("v1", "v2"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CredentialEnvelope.parse("v1.AA.${"A".repeat(22)}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CredentialEnvelope.parse("v1.${valid.split('.')[1]}.AA")
        }
    }

    @Test
    fun `aes gcm uses a fresh nonce and hides plaintext`() {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val plaintext = "jwt-secret-value|private-box|private-sign".toByteArray()

        val first = CredentialAesGcm.encrypt(key, plaintext)
        val second = CredentialAesGcm.encrypt(key, plaintext)

        assertNotEquals(first, second)
        assertNotEquals(
            CredentialEnvelope.parse(first).nonce.toList(),
            CredentialEnvelope.parse(second).nonce.toList(),
        )
        assertEquals(false, first.contains("jwt-secret-value"))
        assertArrayEquals(plaintext, CredentialAesGcm.decrypt(key, first))
        assertArrayEquals(plaintext, CredentialAesGcm.decrypt(key, second))
    }

    @Test
    fun `provider generated nonce encrypts and round trips`() {
        val key = SecretKeySpec(ByteArray(32) { (it + 3).toByte() }, "AES")
        val plaintext = "provider-generated-iv".toByteArray()

        val first = CredentialAesGcm.encryptWithProviderNonce(key, plaintext)
        val second = CredentialAesGcm.encryptWithProviderNonce(key, plaintext)

        assertNotEquals(first, second)
        assertArrayEquals(plaintext, CredentialAesGcm.decrypt(key, first))
        assertArrayEquals(plaintext, CredentialAesGcm.decrypt(key, second))
    }

    @Test
    fun `aes gcm rejects ciphertext tampering`() {
        val key = SecretKeySpec(ByteArray(32) { (it + 7).toByte() }, "AES")
        val envelope = CredentialEnvelope.parse(CredentialAesGcm.encrypt(key, "secret".toByteArray()))
        envelope.ciphertext[0] = (envelope.ciphertext[0].toInt() xor 1).toByte()

        assertThrows(AEADBadTagException::class.java) {
            CredentialAesGcm.decrypt(key, envelope.serialize())
        }
    }
}
