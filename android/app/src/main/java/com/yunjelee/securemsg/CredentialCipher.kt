package com.yunjelee.securemsg

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class CredentialSecrets(
    val token: String,
    val boxSk: String,
    val signSk: String,
)

/** Pure binary codec. Length-prefixing avoids delimiters leaking into or corrupting key material. */
internal object CredentialSecretCodec {
    private const val MAGIC = 0x534D4331 // "SMC1"
    private const val MAX_FIELD_BYTES = 64 * 1024

    fun encode(value: CredentialSecrets): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeField(value.token)
            output.writeField(value.boxSk)
            output.writeField(value.signSk)
        }
        bytes.toByteArray()
    }

    fun decode(encoded: ByteArray): CredentialSecrets = DataInputStream(
        ByteArrayInputStream(encoded),
    ).use { input ->
        require(input.readInt() == MAGIC) { "unknown credential payload" }
        val decoded = CredentialSecrets(
            token = input.readField(),
            boxSk = input.readField(),
            signSk = input.readField(),
        )
        require(input.available() == 0) { "trailing credential payload data" }
        decoded
    }

    private fun DataOutputStream.writeField(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FIELD_BYTES) { "credential field is too large" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readField(): String {
        val size = readInt()
        require(size in 0..MAX_FIELD_BYTES && size <= available()) {
            "invalid credential field length"
        }
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }
}

internal data class CredentialEnvelope(val nonce: ByteArray, val ciphertext: ByteArray) {
    fun serialize(): String = listOf(
        VERSION,
        B64U.encodeToString(nonce),
        B64U.encodeToString(ciphertext),
    ).joinToString(".")

    companion object {
        const val VERSION = "v1"
        val AAD: ByteArray = "com.yunjelee.securemsg.credentials|v1".toByteArray(Charsets.UTF_8)
        private val B64U = Base64.getUrlEncoder().withoutPadding()
        private val B64U_DECODER = Base64.getUrlDecoder()

        fun parse(serialized: String): CredentialEnvelope {
            val parts = serialized.split('.')
            require(parts.size == 3 && parts[0] == VERSION) { "unknown credential envelope" }
            val nonce = B64U_DECODER.decode(parts[1])
            val ciphertext = B64U_DECODER.decode(parts[2])
            require(nonce.size == AndroidCredentialCipher.NONCE_BYTES) { "invalid credential nonce" }
            require(ciphertext.size >= AndroidCredentialCipher.GCM_TAG_BYTES) {
                "invalid credential ciphertext"
            }
            return CredentialEnvelope(nonce, ciphertext)
        }
    }
}

/** Platform-independent AES-GCM core, kept separate so nonce/AAD integrity is unit-testable. */
internal object CredentialAesGcm {
    fun encrypt(key: SecretKey, plaintext: ByteArray, random: SecureRandom = SecureRandom()): String {
        val nonce = ByteArray(AndroidCredentialCipher.NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(AndroidCredentialCipher.TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(AndroidCredentialCipher.GCM_TAG_BITS, nonce))
        cipher.updateAAD(CredentialEnvelope.AAD)
        return CredentialEnvelope(nonce, cipher.doFinal(plaintext)).serialize()
    }

    fun decrypt(key: SecretKey, serialized: String): ByteArray {
        val envelope = CredentialEnvelope.parse(serialized)
        val cipher = Cipher.getInstance(AndroidCredentialCipher.TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(AndroidCredentialCipher.GCM_TAG_BITS, envelope.nonce),
        )
        cipher.updateAAD(CredentialEnvelope.AAD)
        return cipher.doFinal(envelope.ciphertext)
    }

    /**
     * Android Keystore keys created with randomized-encryption enforcement reject an app-supplied
     * IV. Let the provider generate it and persist the returned 96-bit nonce with the ciphertext.
     */
    fun encryptWithProviderNonce(key: SecretKey, plaintext: ByteArray): String {
        val cipher = Cipher.getInstance(AndroidCredentialCipher.TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(CredentialEnvelope.AAD)
        val ciphertext = cipher.doFinal(plaintext)
        val nonce = requireNotNull(cipher.iv) { "credential provider did not return an IV" }
        require(nonce.size == AndroidCredentialCipher.NONCE_BYTES) {
            "credential provider returned an invalid IV"
        }
        return CredentialEnvelope(nonce, ciphertext).serialize()
    }
}

/**
 * Android Keystore-backed AES-256-GCM encryption for persisted credentials.
 * The key deliberately requires no interactive authentication so the SMS bridge can reconnect
 * from a background service after normal boot/unlock.
 */
internal class AndroidCredentialCipher {
    fun encrypt(plaintext: ByteArray): String = retryWithFreshKeyIfNeeded { key ->
        CredentialAesGcm.encryptWithProviderNonce(key, plaintext)
    }

    fun decrypt(serialized: String): ByteArray {
        val key = existingKey() ?: throw IllegalStateException("credential key is missing")
        return CredentialAesGcm.decrypt(key, serialized)
    }

    fun deleteKey() {
        keyStore().deleteEntry(KEY_ALIAS)
    }

    private inline fun retryWithFreshKeyIfNeeded(block: (SecretKey) -> String): String {
        return try {
            block(getOrCreateKey())
        } catch (first: Exception) {
            // A restored DataStore or invalidated hardware key cannot be reused. Rotating here is
            // safe for save/migration; decrypt never rotates because that would hide corruption.
            runCatching(::deleteKey)
            try {
                block(getOrCreateKey())
            } catch (second: Exception) {
                second.addSuppressed(first)
                throw second
            }
        }
    }

    private fun getOrCreateKey(): SecretKey = existingKey() ?: KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES,
        ANDROID_KEYSTORE,
    ).run {
        init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        generateKey()
    }

    private fun existingKey(): SecretKey? = keyStore().getKey(KEY_ALIAS, null) as? SecretKey

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        internal const val NONCE_BYTES = 12
        internal const val GCM_TAG_BYTES = 16
        internal const val GCM_TAG_BITS = GCM_TAG_BYTES * 8
        internal const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "securemsg.credentials.aes.v1"
    }
}
