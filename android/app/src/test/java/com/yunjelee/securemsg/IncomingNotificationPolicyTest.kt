package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingNotificationPolicyTest {
    @Test
    fun mmsPreviewIncludesUsefulAvailableDetails() {
        val content = RelayContent(
            type = RelayContentCodec.TYPE_MMS,
            text = "본문",
            subject = "제목",
            attachments = listOf(
                RelayAttachment("photo.jpg", "image/jpeg", "encoded", 3),
            ),
        )

        assertEquals("제목 · 본문 · 첨부파일 1개", IncomingNotificationPolicy.preview(content))
    }

    @Test
    fun emptyMmsStillHasReadablePreview() {
        val content = RelayContent(
            type = RelayContentCodec.TYPE_MMS,
            text = "",
        )

        assertEquals("MMS 메시지", IncomingNotificationPolicy.preview(content))
    }

    @Test
    fun smsPreviewRemainsTheMessageBody() {
        assertEquals(
            "hello",
            IncomingNotificationPolicy.preview(RelayContentCodec.text("hello")),
        )
    }
}
