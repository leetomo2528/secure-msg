package com.yunjelee.securemsg

import com.yunjelee.securemsg.ui.isLocalTestHost
import com.yunjelee.securemsg.ui.ACCOUNT_RECOVERY_WARNING
import com.yunjelee.securemsg.ui.NEW_DEVICE_HISTORY_WARNING
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class LoginValidationTest {

    @Test
    fun onboardingWarnsAboutAccountRecoveryAndSessionExpiry() {
        assertTrue(ACCOUNT_RECOVERY_WARNING.contains("비밀번호 재설정·계정 복구 수단이 없습니다"))
        assertTrue(ACCOUNT_RECOVERY_WARNING.contains("세션은 만료 전까지 동작할 수"))
        assertTrue(ACCOUNT_RECOVERY_WARNING.contains("세션 만료 후에는 다시 로그인할 수 없습니다"))
    }

    @Test
    fun onboardingWarnsAboutNewDeviceHistoryCutoff() {
        assertTrue(NEW_DEVICE_HISTORY_WARNING.contains("기기 등록 이전 메시지를 복호화할 수 없습니다"))
        assertTrue(NEW_DEVICE_HISTORY_WARNING.contains("기존 기기 전송이나 암호화 백업 기능을 제공하지 않습니다"))
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
}
