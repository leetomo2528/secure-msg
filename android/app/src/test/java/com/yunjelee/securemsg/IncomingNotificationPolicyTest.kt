package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    // --- rescan gate -------------------------------------------------------

    private val now = 1_724_000_000_000L

    @Test
    fun `a rescan notifies for a message that just arrived`() {
        assertTrue(
            IncomingNotificationPolicy.shouldNotifyRescan(
                newlyCreated = true,
                receivedAt = now - 60_000L,
                now = now,
            ),
        )
    }

    @Test
    fun `a rescan never notifies for a row another path already claimed`() {
        assertFalse(
            IncomingNotificationPolicy.shouldNotifyRescan(
                newlyCreated = false,
                receivedAt = now,
                now = now,
            ),
        )
    }

    @Test
    fun `re-login after logout does not re-notify yesterday's messages`() {
        // Logout clears the dedupe ledgers, so every row the next startup sweep
        // sees reports newlyCreated = true. Age is the only remaining signal.
        assertFalse(
            IncomingNotificationPolicy.shouldNotifyRescan(
                newlyCreated = true,
                receivedAt = now - 24L * 60 * 60 * 1000,
                now = now,
            ),
        )
    }

    @Test
    fun `the age boundary is inclusive`() {
        val maxAge = IncomingNotificationPolicy.RESCAN_NOTIFY_MAX_AGE_MS
        assertTrue(
            IncomingNotificationPolicy.shouldNotifyRescan(true, now - maxAge, now),
        )
        assertFalse(
            IncomingNotificationPolicy.shouldNotifyRescan(true, now - maxAge - 1, now),
        )
    }

    @Test
    fun `a future carrier timestamp still notifies`() {
        // Clock skew between the carrier and the device must not swallow a real
        // message; showing an oddly-stamped notification is the safer failure.
        assertTrue(
            IncomingNotificationPolicy.shouldNotifyRescan(true, now + 60_000L, now),
        )
    }

    // --- live vs rescan ----------------------------------------------------

    @Test
    fun `a live delivery notifies however old its carrier timestamp is`() {
        // Out of coverage overnight, back in range at 07:00, and the SMSC hands
        // over a message stamped 22:00. The notification is the only signal the
        // user will ever get for it -- this app is the default SMS app.
        assertTrue(
            IncomingNotificationPolicy.shouldNotify(
                rescan = false,
                newlyCreated = true,
                receivedAt = now - 9L * 60 * 60 * 1000,
                now = now,
            ),
        )
    }

    @Test
    fun `a sweep applies the age gate to the same message`() {
        assertFalse(
            IncomingNotificationPolicy.shouldNotify(
                rescan = true,
                newlyCreated = true,
                receivedAt = now - 9L * 60 * 60 * 1000,
                now = now,
            ),
        )
    }

    @Test
    fun `neither path notifies for a row another path already claimed`() {
        assertFalse(IncomingNotificationPolicy.shouldNotify(false, false, now, now))
        assertFalse(IncomingNotificationPolicy.shouldNotify(true, false, now, now))
    }

    // --- conversation group key -------------------------------------------

    @Test
    fun `the group key survives a local to server cid migration`() {
        val beforeMigration = IncomingNotificationPolicy.conversationGroup(
            cid = "local_abc",
            normalizedPhone = "+821012345678",
        )
        val afterMigration = IncomingNotificationPolicy.conversationGroup(
            cid = "server_42",
            normalizedPhone = "+821012345678",
        )

        assertEquals(beforeMigration, afterMigration)
    }

    @Test
    fun `the cid identifies the conversation only when no phone number is known`() {
        assertEquals(
            "sms_local_abc",
            IncomingNotificationPolicy.conversationGroup("local_abc", ""),
        )
        assertNotEquals(
            IncomingNotificationPolicy.conversationGroup("local_abc", "+821012345678"),
            IncomingNotificationPolicy.conversationGroup("local_abc", "+821099998888"),
        )
    }

    @Test
    fun `an unidentifiable conversation has no group key`() {
        // An empty key must not become the prefix alone, which would collapse
        // unrelated conversations into one cancellable group.
        assertEquals("", IncomingNotificationPolicy.conversationGroup(null, ""))
        assertEquals("", IncomingNotificationPolicy.conversationGroup("  ", "   "))
    }
}
