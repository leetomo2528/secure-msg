package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test
    fun isNewerDetectsHigherVersion() {
        assertTrue(AppUpdater.isNewer("0.7.0", "0.6.1"))
        assertTrue(AppUpdater.isNewer("0.10.0", "0.9.9"))
        assertTrue(AppUpdater.isNewer("1.0.0", "0.99.99"))
        assertTrue(AppUpdater.isNewer("v0.7.0", "0.6.1"))
        assertTrue(AppUpdater.isNewer("0.6.1.1", "0.6.1"))
        // Regression for the first web-family release: a v0.10.0 install
        // must recognize the current v0.10.4 release.
        assertTrue(AppUpdater.isNewer("0.10.4", "0.10.0"))
    }

    @Test
    fun isNewerRejectsSameOrLowerVersion() {
        assertFalse(AppUpdater.isNewer("0.6.1", "0.6.1"))
        assertFalse(AppUpdater.isNewer("v0.6.1", "0.6.1"))
        assertFalse(AppUpdater.isNewer("0.5.9", "0.6.1"))
        assertFalse(AppUpdater.isNewer("0.9.9", "0.10.0"))
        assertFalse(AppUpdater.isNewer("", "0.6.1"))
        assertFalse(AppUpdater.isNewer("abc", "0.6.1"))
    }

    @Test
    fun parseReleaseExtractsApkAsset() {
        val json = """
            {
              "tag_name": "v0.7.0",
              "body": "- 새 기능 추가",
              "assets": [
                {"name": "notes.txt", "browser_download_url": "https://x/notes.txt", "size": 10},
                {"name": "app-debug.apk", "browser_download_url": "https://x/app-debug.apk", "size": 12345678}
              ]
            }
        """.trimIndent()
        val info = AppUpdater.parseRelease(json)
        assertNotNull(info)
        assertEquals("v0.7.0", info!!.tag)
        assertEquals("0.7.0", info.versionName)
        assertEquals("https://x/app-debug.apk", info.apkUrl)
        assertEquals(12345678L, info.sizeBytes)
        assertTrue(info.notes.contains("새 기능"))
    }

    @Test
    fun parseCurrentReleaseWithDebugApk() {
        val info = AppUpdater.parseRelease(
            """{"tag_name":"v0.10.4","assets":[{"name":"app-debug.apk","browser_download_url":"https://x/app-debug.apk","size":27473122}]}""",
        )
        assertNotNull(info)
        assertEquals("0.10.4", info!!.versionName)
        assertEquals(27473122L, info.sizeBytes)
    }

    @Test
    fun parseReleaseRejectsUnusablePayloads() {
        assertNull(AppUpdater.parseRelease(""))
        assertNull(AppUpdater.parseRelease("not json"))
        assertNull(AppUpdater.parseRelease("""{"tag_name": "v0.7.0"}"""))
        assertNull(
            AppUpdater.parseRelease(
                """{"tag_name": "v0.7.0", "assets": [{"name": "src.zip", "browser_download_url": "https://x/z"}]}""",
            ),
        )
        assertNull(
            AppUpdater.parseRelease(
                """{"tag_name": "", "assets": [{"name": "a.apk", "browser_download_url": "https://x/a"}]}""",
            ),
        )
        assertNull(
            AppUpdater.parseRelease(
                """{"tag_name": "v1", "assets": [{"name": "a.apk", "browser_download_url": ""}]}""",
            ),
        )
    }
}
