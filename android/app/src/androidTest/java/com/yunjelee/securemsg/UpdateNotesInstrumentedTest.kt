package com.yunjelee.securemsg

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The same expectations UpdateNotesTest asserts, re-asserted on the engine the
 * phone actually runs.
 *
 * v0.19.0 shipped a pattern java.util.regex accepts and Android's ICU rejects:
 * the host suite stayed green while every launch died in this object's static
 * initialiser. Merely reaching [UpdateNotes.format] here re-runs that
 * initialiser on device, and the duplicated fixtures are the point — a value
 * that differs between the two engines is exactly the class of bug this
 * catches. The org.json half is the same story: the platform implementation
 * shadows the artifact the host tests link, and it disagrees about JSON null.
 */
@RunWith(AndroidJUnit4::class)
class UpdateNotesInstrumentedTest {

    @Test
    fun headingEmphasisCodeAndLinkMarkersAreStripped() {
        val raw = """
            ## ⚠️ 서명 키 변경
            **굵게** 와 *기울임* 과 `코드` 와 [자세히](https://example.com/a_b) 입니다.
            > 인용된 줄
        """.trimIndent()

        assertEquals(
            "⚠️ 서명 키 변경\n굵게 와 기울임 과 코드 와 자세히 입니다.\n인용된 줄",
            UpdateNotes.format(raw),
        )
    }

    @Test
    fun everyListMarkerBecomesTheSameBullet() {
        val raw = """
            - 첫째
            * 둘째
            + 셋째
            1. 넷째
              - 중첩된 다섯째
        """.trimIndent()

        assertEquals("• 첫째\n• 둘째\n• 셋째\n• 넷째\n• 중첩된 다섯째", UpdateNotes.format(raw))
    }

    @Test
    fun underscoresBetweenHangulSurviveOnTheDeviceEngine() {
        // The emphasis guards are spelled \p{L}\p{N}\p{M} precisely because
        // ICU has no (?U) and its \w is not the JVM's. This is the assertion
        // the v0.19.0 crash would have failed at, on the engine that crashed.
        assertEquals("설정_파일_이름 을 바꿉니다", UpdateNotes.format("설정_파일_이름 을 바꿉니다"))
        assertEquals("기기_인증_토큰_만료", UpdateNotes.format("기기_인증_토큰_만료"))
        assertEquals("가__나__다", UpdateNotes.format("가__나__다"))
        assertEquals(
            "링크: https://example.com/한글_경로_이름",
            UpdateNotes.format("링크: https://example.com/한글_경로_이름"),
        )
        assertEquals("굵게 기울임", UpdateNotes.format("__굵게__ _기울임_"))
        assertEquals(
            "KEY_PENDING_UPDATE 를 지웁니다",
            UpdateNotes.format("`KEY_PENDING_UPDATE` 를 지웁니다"),
        )
    }

    @Test
    fun fencedCodeAndCodeSpansKeepTheirOwnPunctuation() {
        val raw = """
            설정:
            ```kotlin
            val a = *b* // _keep_
            ```
        """.trimIndent()

        assertEquals("설정:\nval a = *b* // _keep_", UpdateNotes.format(raw))
        assertEquals("** 다음 여기", UpdateNotes.format("`**` 다음 **여기**"))
        assertEquals("설정은 a **b** c 입니다", UpdateNotes.format("설정은 `a **b** c` 입니다"))
    }

    @Test
    fun theBoundHoldsAndNeverSplitsASurrogatePair() {
        assertEquals("가".repeat(20) + "…", UpdateNotes.format("가".repeat(50), 20))

        val safe = UpdateNotes.format("가".repeat(19) + "🚀🚀", 20)
        assertEquals("가".repeat(19) + "…", safe)
        assertFalse(safe.any { Character.isHighSurrogate(it) || Character.isLowSurrogate(it) })

        val capped = UpdateNotes.capRaw("가".repeat(UpdateNotes.MAX_LENGTH - 1) + "🚀")
        assertEquals(UpdateNotes.MAX_LENGTH - 1, capped.length)
        assertFalse(Character.isHighSurrogate(capped.last()))
    }

    @Test
    fun aReleasePublishedWithNoDescriptionCarriesNoNotesOnDevice() {
        // The platform org.json hands back the four-character string "null"
        // where the host artifact honours optString's fallback, and that string
        // reads as a usable body all the way to the post-update notification.
        val empty = AppUpdater.parseRelease(
            """{"tag_name":"v0.20.0","body":null,"assets":[""" +
                """{"name":"app-release.apk","browser_download_url":"https://x/a.apk","size":50000000}]}""",
        )
        assertNotNull(empty)
        assertEquals("", empty!!.notes)
        assertEquals("", UpdateNotes.format(empty.notes))

        val described = AppUpdater.parseRelease(
            """{"tag_name":"v0.20.0","body":"- 새 기능 추가","assets":[""" +
                """{"name":"app-release.apk","browser_download_url":"https://x/a.apk","size":50000000}]}""",
        )
        assertNotNull(described)
        assertEquals("- 새 기능 추가", described!!.notes)
        assertEquals("• 새 기능 추가", UpdateNotes.format(described.notes))
    }
}
