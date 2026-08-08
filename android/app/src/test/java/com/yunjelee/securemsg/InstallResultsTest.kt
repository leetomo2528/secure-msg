package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallResultsTest {

    @Test
    fun constantsMirrorPackageInstallerStatusCodes() {
        // Values must match android.content.pm.PackageInstaller.STATUS_*.
        assertEquals(-1, InstallResults.PENDING_USER_ACTION)
        assertEquals(0, InstallResults.SUCCESS)
        assertEquals(1, InstallResults.FAILURE)
        assertEquals(2, InstallResults.FAILURE_BLOCKED)
        assertEquals(3, InstallResults.FAILURE_ABORTED)
        assertEquals(4, InstallResults.FAILURE_INVALID)
        assertEquals(5, InstallResults.FAILURE_CONFLICT)
        assertEquals(6, InstallResults.FAILURE_STORAGE)
        assertEquals(7, InstallResults.FAILURE_INCOMPATIBLE)
    }

    @Test
    fun blockedOffersSafeSystemDetailsOrManualInstall() {
        val guidance = InstallResults.guidance(InstallResults.FAILURE_BLOCKED)
        assertTrue(guidance.contains("Play Protect"))
        assertTrue(guidance.contains("시스템 세부정보"))
        assertTrue(guidance.contains("GitHub"))
        assertTrue(!guidance.contains("끄기"))
    }

    @Test
    fun abortedIsDistinguishedFromBlocked() {
        val aborted = InstallResults.guidance(InstallResults.FAILURE_ABORTED)
        assertTrue(aborted.contains("취소"))
        assertTrue(!aborted.contains("차단"))
        assertTrue(!aborted.contains("Play Protect"))
    }

    @Test
    fun conflictSuggestsReinstall() {
        for (status in listOf(InstallResults.FAILURE_CONFLICT, InstallResults.FAILURE_INCOMPATIBLE)) {
            val g = InstallResults.guidance(status)
            assertTrue(g.contains("서명"))
            assertTrue(g.contains("삭제"))
            assertTrue(g.contains("로컬 암호화 키"))
            assertTrue(g.contains("메시지"))
            assertTrue(g.contains("allowBackup=false"))
        }
    }

    @Test
    fun storageAndInvalidHaveSpecificMessages() {
        assertTrue(InstallResults.guidance(InstallResults.FAILURE_STORAGE).contains("저장"))
        assertTrue(InstallResults.guidance(InstallResults.FAILURE_INVALID).contains("손상"))
    }

    @Test
    fun unknownStatusGetsGenericMessage() {
        val g = InstallResults.guidance(99)
        assertTrue(g.contains("다시 시도"))
        assertTrue(InstallResults.guidance(InstallResults.FAILURE).isNotEmpty())
    }
}
