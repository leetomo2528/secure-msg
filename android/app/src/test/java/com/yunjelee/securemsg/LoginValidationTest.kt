package com.yunjelee.securemsg

import com.yunjelee.securemsg.ui.isLocalTestHost
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginValidationTest {

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
