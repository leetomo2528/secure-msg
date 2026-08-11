package com.yunjelee.securemsg

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceTrustCryptoInstrumentedTest {
    @Test
    fun detachedApprovalSignatureVerifiesAndRejectsEveryTamperClass() {
        val approver = CryptoUtil.generateKeypair()
        val subject = CryptoUtil.generateKeypair()
        val challenge = CryptoUtil.b64u(ByteArray(32) { it.toByte() })
        val statement = DeviceApprovalStatement(
            uid = 912,
            subjectSid = "android_subject",
            pubKey = subject.boxPk,
            sigPub = subject.signPk,
            kind = "android_gateway",
            challenge = challenge,
            parentEpoch = 18,
        )
        val signature = DeviceTrustCrypto.signApproval(statement, approver.signSk)
        assertTrue(DeviceTrustCrypto.verifyApproval(statement, signature, approver.signPk))

        val tampered = listOf(
            statement.copy(uid = 913),
            statement.copy(subjectSid = "android_other"),
            statement.copy(pubKey = approver.boxPk),
            statement.copy(sigPub = approver.signPk),
            statement.copy(kind = "web"),
            statement.copy(challenge = CryptoUtil.b64u(ByteArray(32) { (it + 1).toByte() })),
            statement.copy(parentEpoch = 19),
        )
        tampered.forEach {
            assertFalse(DeviceTrustCrypto.verifyApproval(it, signature, approver.signPk))
        }
        assertFalse(DeviceTrustCrypto.verifyApproval(statement, signature, subject.signPk))

        val signatureBytes = CryptoUtil.unb64u(signature)
        signatureBytes[0] = (signatureBytes[0].toInt() xor 1).toByte()
        assertFalse(
            DeviceTrustCrypto.verifyApproval(
                statement, CryptoUtil.b64u(signatureBytes), approver.signPk,
            ),
        )
    }
}
