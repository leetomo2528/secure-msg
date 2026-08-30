package com.yunjelee.securemsg

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.PwHash
import com.goterl.lazysodium.interfaces.SecretBox
import com.goterl.lazysodium.interfaces.Sign
import org.json.JSONObject
import java.text.Normalizer
import java.util.Base64
import java.util.Locale

object CryptoUtil {

    /** Argon2id output length the relay server expects (base64url, no padding). */
    const val PW_HASH_BYTES = 32

    private val sodium: LazySodiumAndroid by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LazySodiumAndroid(SodiumAndroid())
    }

    internal fun b64u(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    internal fun unb64u(s: String): ByteArray =
        Base64.getUrlDecoder().decode(s)

    /**
     * 32 random bytes, base64url. Used for the QR pairing nonce, which is
     * public but must be unpredictable and unique per pending registration.
     * java.security.SecureRandom rather than libsodium so this stays usable
     * without the native binding (and in JVM unit tests).
     */
    fun randomNonceB64u(): String =
        b64u(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })

    data class DeviceKeypair(
        val boxPk: String,
        val boxSk: String,
        val signPk: String,
        val signSk: String,
    )

    fun generateKeypair(): DeviceKeypair {
        val boxPk = ByteArray(Box.PUBLICKEYBYTES)
        val boxSk = ByteArray(Box.SECRETKEYBYTES)
        check(sodium.cryptoBoxKeypair(boxPk, boxSk)) { "crypto_box key generation failed" }

        val signPk = ByteArray(Sign.ED25519_PUBLICKEYBYTES)
        val signSk = ByteArray(Sign.ED25519_SECRETKEYBYTES)
        check(sodium.cryptoSignKeypair(signPk, signSk)) { "signing key generation failed" }

        return DeviceKeypair(
            boxPk = b64u(boxPk),
            boxSk = b64u(boxSk),
            signPk = b64u(signPk),
            signSk = b64u(signSk),
        )
    }

    fun signDetached(message: ByteArray, signSecretKeyB64: String): String {
        val secretKey = unb64u(signSecretKeyB64)
        require(secretKey.size == Sign.ED25519_SECRETKEYBYTES) { "invalid signing secret key" }
        val signature = ByteArray(Sign.BYTES)
        check(sodium.cryptoSignDetached(signature, message, message.size.toLong(), secretKey)) {
            "detached signature failed"
        }
        return b64u(signature)
    }

    fun verifyDetached(message: ByteArray, signatureB64: String, signPublicKeyB64: String): Boolean =
        try {
            val signature = unb64u(signatureB64)
            val publicKey = unb64u(signPublicKeyB64)
            signature.size == Sign.BYTES && publicKey.size == Sign.ED25519_PUBLICKEYBYTES &&
                sodium.cryptoSignVerifyDetached(signature, message, message.size, publicKey)
        } catch (_: Exception) {
            false
        }

    fun hashPassword(password: String, saltB64: String): String {
        val salt = unb64u(saltB64)
        // NOTE: Lazysodium's String overload base64-encodes with android.util.Base64
        // NO_WRAP — that is STANDARD base64 ('+','/','=') , which the relay server
        // rejects ("pw_hash must be base64url for 32 bytes"). Hash into a raw
        // buffer and encode as url-safe base64 without padding, matching the web
        // client (libsodium b64u) byte-for-byte.
        val out = ByteArray(PW_HASH_BYTES)
        val pwBytes = password.toByteArray(Charsets.UTF_8)
        check(
            sodium.cryptoPwHash(
                out, out.size,
                pwBytes, pwBytes.size,
                salt,
                PwHash.OPSLIMIT_INTERACTIVE,
                PwHash.MEMLIMIT_INTERACTIVE,
                PwHash.Alg.PWHASH_ALG_ARGON2ID13,
            )
        ) { "password hashing failed" }
        return b64u(out)
    }

    fun saltForUser(username: String): String {
        val out = ByteArray(16)
        val input = Normalizer.normalize(username, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .toByteArray(Charsets.UTF_8)
        sodium.cryptoGenericHash(out, 16, input, input.size.toLong())
        return b64u(out)
    }

    /** Symmetric message key length; crypto_secretbox_KEYBYTES. */
    private const val MESSAGE_KEY_BYTES = 32

    /**
     * One wrapped copy of the message key.
     *
     * [by] is the sid of a device that re-wrapped the key for history sharing.
     * That device sealed the box with ITS secret key, so the entry opens with
     * that device's public key — not the message sender's. Entries written by
     * the original sender have no [by] and open with the sender's key.
     */
    data class EnvelopeKey(val ek: String, val n: String, val by: String? = null)

    data class Envelope(
        val ct: String,
        val nonce: String,
        val keys: Map<String, EnvelopeKey>,
    )

    data class Recipient(val sid: String, val pubKey: String)

    fun encryptMessage(
        plaintext: String,
        recipients: List<Recipient>,
        sender: DeviceKeypair,
    ): Envelope {
        require(recipients.isNotEmpty()) { "at least one recipient device is required" }
        val messageKey = sodium.randomBytesBuf(32)

        val nonce = sodium.randomBytesBuf(SecretBox.NONCEBYTES)

        val ptBytes = plaintext.toByteArray(Charsets.UTF_8)
        val ct = ByteArray(ptBytes.size + SecretBox.MACBYTES)
        check(sodium.cryptoSecretBoxEasy(ct, ptBytes, ptBytes.size.toLong(), nonce, messageKey)) {
            "message encryption failed"
        }

        val senderSk = unb64u(sender.boxSk)
        val keys = mutableMapOf<String, EnvelopeKey>()
        for (r in recipients) {
            val rPk = unb64u(r.pubKey)
            val boxNonce = sodium.randomBytesBuf(Box.NONCEBYTES)
            val ek = ByteArray(messageKey.size + Box.MACBYTES)
            check(sodium.cryptoBoxEasy(ek, messageKey, messageKey.size.toLong(), boxNonce, rPk, senderSk)) {
                "message-key wrapping failed"
            }
            keys[r.sid] = EnvelopeKey(b64u(ek), b64u(boxNonce))
        }
        return Envelope(b64u(ct), b64u(nonce), keys)
    }

    /**
     * Open one key entry into the raw message key.
     *
     * [openerPubKeyB64] must be the public key of the device that SEALED this
     * entry: the message sender, or the re-wrapper named by [EnvelopeKey.by].
     *
     * Every length is checked before the call: cryptoBoxOpenEasy writes
     * `ek.size - MACBYTES` bytes into the output buffer and reads NONCEBYTES /
     * key lengths from its inputs without validating them, so a relay that
     * returns an over-long `ek` (or a short nonce/key) would corrupt memory
     * inside the native library rather than fail.
     */
    private fun openMessageKey(
        entry: EnvelopeKey,
        myKeypair: DeviceKeypair,
        openerPubKeyB64: String,
    ): ByteArray? {
        val ekBytes = unb64u(entry.ek)
        if (ekBytes.size != MESSAGE_KEY_BYTES + Box.MACBYTES) return null
        val ekNonce = unb64u(entry.n)
        if (ekNonce.size != Box.NONCEBYTES) return null
        val openerPk = unb64u(openerPubKeyB64)
        if (openerPk.size != Box.PUBLICKEYBYTES) return null
        val mySk = unb64u(myKeypair.boxSk)
        if (mySk.size != Box.SECRETKEYBYTES) return null
        val messageKey = ByteArray(MESSAGE_KEY_BYTES)
        if (!sodium.cryptoBoxOpenEasy(
                messageKey, ekBytes, ekBytes.size.toLong(), ekNonce, openerPk, mySk,
            )
        ) return null
        return messageKey
    }

    /**
     * @param resolveWrapperPubKey public key of a device that re-wrapped this
     *   entry ([EnvelopeKey.by]). It MUST come from the caller's pinned trust
     *   store: a key taken from a server response would let a hostile relay
     *   name itself as the wrapper and hand over a key it controls. Entries
     *   without `by` never reach it and keep using [senderPubKeyB64].
     */
    fun decryptMessage(
        env: Envelope,
        mySid: String,
        myKeypair: DeviceKeypair,
        senderPubKeyB64: String,
        resolveWrapperPubKey: (String) -> String? = { null },
    ): String? {
        return try {
            val myKey = env.keys[mySid] ?: return null
            val openerPubKey = myKey.by?.let { resolveWrapperPubKey(it) ?: return null }
                ?: senderPubKeyB64
            val messageKey = openMessageKey(myKey, myKeypair, openerPubKey) ?: return null

            val ctBytes = unb64u(env.ct)
            if (ctBytes.size < SecretBox.MACBYTES) return null
            val ctNonce = unb64u(env.nonce)
            if (ctNonce.size != SecretBox.NONCEBYTES) return null
            val ptBytes = ByteArray(ctBytes.size - SecretBox.MACBYTES)
            if (!sodium.cryptoSecretBoxOpenEasy(
                    ptBytes, ctBytes, ctBytes.size.toLong(), ctNonce, messageKey,
                )
            ) return null
            String(ptBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Re-wrap this device's copy of a message key for [targetPubKeyB64], so a
     * device that was not an original recipient can read existing history.
     *
     * [openerPubKeyB64] is whichever key opens THIS device's entry — the
     * message sender's, or the re-wrapper's when the entry carries a `by`.
     * The caller resolves it (from its pinned trust store) because only the
     * caller can say which sid is trusted for that role.
     *
     * The result is sealed with this device's secret key, so it is stamped
     * `by = mySid`: the target must open it with THIS device's public key.
     */
    fun rewrapMessageKey(
        env: Envelope,
        mySid: String,
        myKeypair: DeviceKeypair,
        openerPubKeyB64: String,
        targetPubKeyB64: String,
    ): EnvelopeKey? {
        return try {
            val myKey = env.keys[mySid] ?: return null
            val messageKey = openMessageKey(myKey, myKeypair, openerPubKeyB64) ?: return null
            val targetPk = unb64u(targetPubKeyB64)
            if (targetPk.size != Box.PUBLICKEYBYTES) return null
            val mySk = unb64u(myKeypair.boxSk)
            if (mySk.size != Box.SECRETKEYBYTES) return null
            val boxNonce = sodium.randomBytesBuf(Box.NONCEBYTES)
            val ek = ByteArray(messageKey.size + Box.MACBYTES)
            if (!sodium.cryptoBoxEasy(
                    ek, messageKey, messageKey.size.toLong(), boxNonce, targetPk, mySk,
                )
            ) return null
            EnvelopeKey(b64u(ek), b64u(boxNonce), by = mySid)
        } catch (e: Exception) {
            null
        }
    }

    fun envelopeToJson(env: Envelope): JSONObject {
        val keysObj = JSONObject()
        for ((sid, ek) in env.keys) {
            val entry = JSONObject().put("ek", ek.ek).put("n", ek.n)
            // Absent, not null: an entry without `by` is an original-sender key
            // and every existing client reads it that way.
            ek.by?.let { entry.put("by", it) }
            keysObj.put(sid, entry)
        }
        return JSONObject()
            .put("ct", env.ct)
            .put("nonce", env.nonce)
            .put("keys", keysObj)
    }

    fun envelopeFromJson(obj: JSONObject): Envelope {
        val keysObj = obj.getJSONObject("keys")
        val keys = mutableMapOf<String, EnvelopeKey>()
        val it = keysObj.keys()
        while (it.hasNext()) {
            val sid = it.next()
            val k = keysObj.getJSONObject(sid)
            keys[sid] = EnvelopeKey(
                k.getString("ek"),
                k.getString("n"),
                k.optString("by").takeIf { it.isNotBlank() },
            )
        }
        return Envelope(obj.getString("ct"), obj.getString("nonce"), keys)
    }
}
