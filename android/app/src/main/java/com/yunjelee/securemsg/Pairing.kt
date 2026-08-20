package com.yunjelee.securemsg

import org.json.JSONObject
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * QR device pairing, Android half (docs/QR_PAIRING_DESIGN.md).
 *
 * The QR carries PUBLIC data only: which pending device this is and the
 * one-time nonce it generated. Scanning authenticates nothing on its own —
 * the approver's Ed25519 signature over the v2 statement is the authorization,
 * and the human comparing the safety number on both screens is what makes the
 * pairing trustworthy. Keep both halves; a scan alone must never approve.
 */

private const val QR_TYPE = "securemsg-pairing"
private const val SAFETY_DOMAIN = "securemsg-pairing-safety-v1\n"
private val B64U_32 = Regex("^[A-Za-z0-9_-]{43}$")
private val SID_RE = Regex("^[A-Za-z0-9_-]{8,64}$")

data class PairingQrFields(
    val server: String,
    val username: String,
    val sid: String,
    val challenge: String,
    val boxPk: String,
    val sigPk: String,
    val nonceNew: String,
    val expiresAt: Long,
)

/** Strict parse. Anything unexpected returns null rather than a partial read. */
fun parsePairingQr(text: String): PairingQrFields? = runCatching {
    val obj = JSONObject(text)
    if (obj.optInt("v", 0) != 1 || obj.optString("type") != QR_TYPE) return null
    val server = obj.optString("server")
    val username = obj.optString("username")
    val sid = obj.optString("sid")
    val challenge = obj.optString("challenge")
    val boxPk = obj.optString("box_pk")
    val sigPk = obj.optString("sig_pk")
    val nonceNew = obj.optString("nonce_new")
    if (!server.startsWith("http://") && !server.startsWith("https://")) return null
    if (username.isEmpty() || !SID_RE.matches(sid)) return null
    if (!B64U_32.matches(challenge) || !B64U_32.matches(boxPk) ||
        !B64U_32.matches(sigPk) || !B64U_32.matches(nonceNew)
    ) return null
    if (!obj.has("expires_at")) return null
    PairingQrFields(
        server = server,
        username = username,
        sid = sid,
        challenge = challenge,
        boxPk = boxPk,
        sigPk = sigPk,
        nonceNew = nonceNew,
        expiresAt = obj.optLong("expires_at"),
    )
}.getOrNull()

/**
 * Short human-comparable safety number for one pairing session: SHA-256 over a
 * domain-separated statement, first 150 bits as five 30-bit groups rendered as
 * 6-digit numbers. The web client derives byte-identical output; both pin the
 * same golden vector in their unit tests.
 */
fun pairingSafetyNumber(
    nonceNew: String,
    nonceApprover: String,
    sid: String,
    pubKey: String,
    sigPub: String,
): String {
    val canonical = SAFETY_DOMAIN +
        "nonce_new=$nonceNew\n" +
        "nonce_approver=$nonceApprover\n" +
        "sid=$sid\n" +
        "pub_key=$pubKey\n" +
        "sig_pub=$sigPub\n"
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
    var value = BigInteger.ZERO
    for (index in 0 until 25) {
        value = value.shiftLeft(8).or(BigInteger.valueOf((digest[index].toInt() and 0xff).toLong()))
    }
    val mask = BigInteger.valueOf(0x3fffffffL)
    return (0 until 5).joinToString("-") { group ->
        val bits = value.shiftRight(120 - 30 * group).and(mask).toLong()
        (bits % 1_000_000L).toString().padStart(6, '0')
    }
}
