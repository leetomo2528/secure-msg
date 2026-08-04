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

    private val sodium: LazySodiumAndroid by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LazySodiumAndroid(SodiumAndroid())
    }

    private fun b64u(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun unb64u(s: String): ByteArray =
        Base64.getUrlDecoder().decode(s)

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

    fun hashPassword(password: String, saltB64: String): String {
        val salt = unb64u(saltB64)
        return sodium.cryptoPwHash(
            password,
            32,
            salt,
            PwHash.OPSLIMIT_INTERACTIVE,
            PwHash.MEMLIMIT_INTERACTIVE,
            PwHash.Alg.PWHASH_ALG_ARGON2ID13,
        )
    }

    fun saltForUser(username: String): String {
        val out = ByteArray(16)
        val input = Normalizer.normalize(username, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .toByteArray(Charsets.UTF_8)
        sodium.cryptoGenericHash(out, 16, input, input.size.toLong())
        return b64u(out)
    }

    data class EnvelopeKey(val ek: String, val n: String)

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

    fun decryptMessage(
        env: Envelope,
        mySid: String,
        myKeypair: DeviceKeypair,
        senderPubKeyB64: String,
    ): String? {
        return try {
            val myKey = env.keys[mySid] ?: return null
            val mySk = unb64u(myKeypair.boxSk)
            val senderPk = unb64u(senderPubKeyB64)
            val ekBytes = unb64u(myKey.ek)
            val ekNonce = unb64u(myKey.n)
            val messageKey = ByteArray(32)
            if (!sodium.cryptoBoxOpenEasy(
                    messageKey, ekBytes, ekBytes.size.toLong(), ekNonce, senderPk, mySk,
                )
            ) return null

            val ctBytes = unb64u(env.ct)
            if (ctBytes.size < SecretBox.MACBYTES) return null
            val ctNonce = unb64u(env.nonce)
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

    fun envelopeToJson(env: Envelope): JSONObject {
        val keysObj = JSONObject()
        for ((sid, ek) in env.keys) {
            keysObj.put(sid, JSONObject().put("ek", ek.ek).put("n", ek.n))
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
            keys[sid] = EnvelopeKey(k.getString("ek"), k.getString("n"))
        }
        return Envelope(obj.getString("ct"), obj.getString("nonce"), keys)
    }
}
