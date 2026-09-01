package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNotesTest {

    @Test
    fun `strips heading, emphasis, code and link markers`() {
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
    fun `every list marker becomes the same bullet`() {
        val raw = """
            - 첫째
            * 둘째
            + 셋째
            1. 넷째
              - 중첩된 다섯째
        """.trimIndent()

        assertEquals(
            "• 첫째\n• 둘째\n• 셋째\n• 넷째\n• 중첩된 다섯째",
            UpdateNotes.format(raw),
        )
    }

    @Test
    fun `blank and whitespace-only bodies format to nothing`() {
        assertEquals("", UpdateNotes.format(""))
        assertEquals("", UpdateNotes.format("   \n\t\n  "))
        // Markers with no text behind them are just as empty.
        assertEquals("", UpdateNotes.format("##\u0020\n---\n**  **"))
    }

    @Test
    fun `text without markdown survives unchanged`() {
        assertEquals("업데이트 완료. 재시작이 필요합니다.", UpdateNotes.format("업데이트 완료. 재시작이 필요합니다."))
        assertEquals("5 * 3 = 15", UpdateNotes.format("5 * 3 = 15"))
        assertEquals("🚀 발사 준비", UpdateNotes.format("  🚀 발사 준비  "))
    }

    @Test
    fun `underscored identifiers keep their underscores`() {
        assertEquals(
            "KEY_PENDING_UPDATE 를 지웁니다",
            UpdateNotes.format("`KEY_PENDING_UPDATE` 를 지웁니다"),
        )
        assertEquals("굵게 기울임", UpdateNotes.format("__굵게__ _기울임_"))
    }

    @Test
    fun `underscores between Hangul survive too`() {
        // The guard runs on java.util.regex, where \w is ASCII-only unless the
        // pattern asks otherwise — so these are the cases that break first, and
        // they are the ones this project's release notes actually contain.
        assertEquals("설정_파일_이름 을 바꿉니다", UpdateNotes.format("설정_파일_이름 을 바꿉니다"))
        assertEquals("기기_인증_토큰_만료", UpdateNotes.format("기기_인증_토큰_만료"))
        assertEquals("가__나__다", UpdateNotes.format("가__나__다"))
        // A bare URL with a Hangul path has to stay resolvable.
        assertEquals(
            "링크: https://example.com/한글_경로_이름",
            UpdateNotes.format("링크: https://example.com/한글_경로_이름"),
        )
    }

    @Test
    fun `blank line runs collapse to a single separator`() {
        val raw = "\n\n첫 문단\n\n\n\n둘째 문단\n\n\n"

        assertEquals("첫 문단\n\n둘째 문단", UpdateNotes.format(raw))
    }

    @Test
    fun `fenced code keeps its own punctuation`() {
        val raw = """
            설정:
            ```kotlin
            val a = *b* // _keep_
            ```
        """.trimIndent()

        assertEquals("설정:\nval a = *b* // _keep_", UpdateNotes.format(raw))
    }

    @Test
    fun `result is bounded and never split through a surrogate pair`() {
        val plain = "가".repeat(50)
        val clipped = UpdateNotes.format(plain, 20)
        assertEquals("가".repeat(20) + "…", clipped)

        // The 20th char is the high half of a rocket: the cut backs off it
        // rather than emitting a lone surrogate.
        val emoji = "가".repeat(19) + "🚀🚀"
        val safe = UpdateNotes.format(emoji, 20)
        assertEquals("가".repeat(19) + "…", safe)
        assertFalse(safe.any { Character.isHighSurrogate(it) || Character.isLowSurrogate(it) })
    }

    @Test
    fun `capRaw bounds the persisted body without splitting a pair`() {
        assertEquals("짧은 본문", UpdateNotes.capRaw("짧은 본문"))

        val long = "가".repeat(UpdateNotes.MAX_LENGTH - 1) + "🚀"
        val capped = UpdateNotes.capRaw(long)
        assertEquals(UpdateNotes.MAX_LENGTH - 1, capped.length)
        assertFalse(Character.isHighSurrogate(capped.last()))

        assertEquals(UpdateNotes.MAX_LENGTH, UpdateNotes.capRaw("a".repeat(5000)).length)
    }

    @Test
    fun `the real v0-18-0 release body formats to readable text`() {
        val formatted = UpdateNotes.format(V0_18_0_BODY)

        assertTrue(formatted.length <= UpdateNotes.MAX_LENGTH)
        assertFalse(formatted.endsWith("…"))
        listOf("#", "**", "`", "](").forEach { marker ->
            assertFalse(marker, formatted.contains(marker))
        }
        assertTrue(formatted.startsWith("⚠️ 이번 업데이트는 앱 서명 키가 바뀝니다\n"))
        assertTrue(formatted.contains("• 디버그 키로 설치된 기기 → 이 업데이트를 정상 수락"))
        assertTrue(formatted.contains("• 이 업데이트를 받은 기기 → 이후 디버그 키로 서명된 APK는 거부"))
        // Headings keep their own line, and the section break stays one blank line.
        assertTrue(formatted.contains("\n\n디버그 빌드 배포 중단\n"))
        assertTrue(formatted.contains("(SigningRotationTest)"))
        assertTrue(formatted.endsWith("테스트 302개 통과."))
    }

    private companion object {
        /** `gh release view v0.18.0 --json body -q .body`, verbatim. */
        val V0_18_0_BODY = """
            ## ⚠️ 이번 업데이트는 앱 서명 키가 바뀝니다
            지금까지 모든 릴리스는 **안드로이드 디버그 키스토어**로 서명돼 있었습니다. 이 키는 비밀번호가 `android`로 공개돼 있어, 누구든 이 키로 서명한 APK를 만들 수 있었습니다. v0.13.0에서 **무인 자동 설치**가 들어가면서 이게 사실상 이 앱의 보안 전부가 됐습니다 — 키를 가진 사람이 GitHub 계정만 손에 넣으면, 문자와 2FA 코드를 읽는 기본 문자 앱에 확인창 없이 업데이트를 밀어넣을 수 있었습니다.

            이제 전용 키로 서명합니다. 기존 설치본이 그대로 업데이트받을 수 있도록 **v3 서명 회전 계보**를 함께 실었습니다:
            - 디버그 키로 설치된 기기 → 이 업데이트를 정상 수락
            - 이 업데이트를 받은 기기 → 이후 **디버그 키로 서명된 APK는 거부**

            두 방향 모두 앱 자신의 업데이트 검증 로직에 실제 인증서 다이제스트를 넣어 테스트로 고정했습니다(`SigningRotationTest`).

            ## 디버그 빌드 배포 중단
            지금까지 배포한 건 `app-debug.apk`, 즉 **`debuggable=true`인 기본 문자 앱**이었습니다. 이제 릴리스 빌드를 배포하며, 설정 화면의 개발자 도구(문자 위조 등)도 함께 사라집니다. APK 크기도 55.9MB → 50.0MB로 줄었습니다.

            R8 난독화는 아직 켜지 않았습니다. Room·socket.io·lazysodium이 모두 이름을 리플렉션으로 찾는데 실기기 검증 수단이 없어, 서명 문제를 런타임 문제로 바꾸는 셈이 되기 때문입니다. 별도로 다룹니다.

            ## 설치가 실패하면
            서명이 바뀌는 업데이트라 만에 하나 거부될 수 있습니다. **그럴 때 앱을 삭제하지 마세요** — 삭제하면 기기 키와 로컬 대화가 모두 사라집니다. 실패 화면을 알려주시면 원인을 확인하겠습니다.

            테스트 302개 통과.
        """.trimIndent()
    }

    @Test
    fun `markdown quoted inside a code span is not read as markup`() {
        // Release notes document syntax by backticking it. Stripping the
        // backticks first freed those characters into the line, where they
        // paired with real emphasis and left a stray marker in the shade.
        assertEquals("** 다음 여기", UpdateNotes.format("`**` 다음 **여기**"))
        assertEquals("*not italic* 를 쓰세요", UpdateNotes.format("`*not italic*` 를 쓰세요"))
        assertEquals("설정은 a **b** c 입니다", UpdateNotes.format("설정은 `a **b** c` 입니다"))
    }

    @Test
    fun `a body cannot forge the code-span placeholder`() {
        // The marker is stripped from the input, so the forged text simply
        // loses those characters instead of capturing a real span.
        val forged = "\uE0000\uE000 와 `진짜`"
        assertEquals("0 와 진짜", UpdateNotes.format(forged))
    }
}
