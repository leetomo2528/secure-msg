package com.yunjelee.securemsg

import com.yunjelee.securemsg.ui.isLocalTestHost
import com.yunjelee.securemsg.ui.validateServerUrl
import com.yunjelee.securemsg.ui.ACCOUNT_RECOVERY_WARNING
import com.yunjelee.securemsg.ui.NEW_DEVICE_HISTORY_WARNING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class LoginValidationTest {

    @Test
    fun onboardingWarnsAboutAccountRecoveryAndSessionExpiry() {
        assertTrue(ACCOUNT_RECOVERY_WARNING.contains("이메일 인증코드"))
        assertTrue(ACCOUNT_RECOVERY_WARNING.contains("비밀번호를 재설정할 수 있습니다"))
    }

    @Test
    fun onboardingWarnsAboutNewDeviceHistoryCutoff() {
        assertTrue(NEW_DEVICE_HISTORY_WARNING.contains("기기 등록 이전 메시지를 복호화할 수 없습니다"))
        assertTrue(NEW_DEVICE_HISTORY_WARNING.contains("'이전 대화 공유'를 실행해야"))
    }

    @Test
    fun localhostAliasesAreAllowed() {
        assertTrue(isLocalTestHost("localhost"))
        assertTrue(isLocalTestHost("127.0.0.1"))
        assertTrue(isLocalTestHost("10.0.2.2"))
    }

    @Test
    fun rfc1918RangesAreAllowed() {
        assertTrue(isLocalTestHost("10.0.0.5"))
        assertTrue(isLocalTestHost("172.16.0.1"))
        assertTrue(isLocalTestHost("172.30.1.95"))
        assertTrue(isLocalTestHost("172.31.255.255"))
        assertTrue(isLocalTestHost("192.168.0.10"))
    }

    @Test
    fun publicAndNearbyPrivateRangesAreRejected() {
        assertFalse(isLocalTestHost("172.15.0.1"))
        assertFalse(isLocalTestHost("172.32.0.1"))
        assertFalse(isLocalTestHost("192.169.0.1"))
        assertFalse(isLocalTestHost("8.8.8.8"))
        assertFalse(isLocalTestHost("msg.yunjelee.com"))
        assertFalse(isLocalTestHost("1.2.3"))
        assertFalse(isLocalTestHost("1.2.3.4.5"))
        assertFalse(isLocalTestHost("300.1.2.3"))
    }

    @Test
    fun serverUrlKeepsOriginAndDropsTheTrailingSlash() {
        assertEquals("https://msg.example.com", validateServerUrl("https://msg.example.com/"))
        assertEquals("https://msg.example.com:8443", validateServerUrl("https://msg.example.com:8443"))
    }

    @Test
    fun plainHttpIsAcceptedOnlyForLocalTestHosts() {
        assertEquals("http://192.168.0.10:5000", validateServerUrl("http://192.168.0.10:5000"))
        val rejected = assertThrows(IllegalArgumentException::class.java) {
            validateServerUrl("http://msg.example.com")
        }
        assertEquals("원격 서버는 HTTPS 주소를 사용해야 합니다.", rejected.message)
    }

    @Test
    fun serverUrlRejectsAnythingBeyondHostAndPort() {
        for (url in listOf(
            "https://user:pw@msg.example.com",
            "https://msg.example.com/relay",
            "https://msg.example.com/?probe=1",
            "https://msg.example.com/#frag",
        )) {
            val rejected = assertThrows(IllegalArgumentException::class.java) {
                validateServerUrl(url)
            }
            assertEquals("서버 URL은 도메인과 포트까지만 입력하세요.", rejected.message)
        }
    }

    @Test
    fun unparseableServerUrlIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { validateServerUrl("msg.example.com") }
        assertThrows(IllegalArgumentException::class.java) { validateServerUrl("") }
    }
}
