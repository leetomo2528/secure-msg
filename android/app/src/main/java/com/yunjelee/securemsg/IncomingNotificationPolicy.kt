package com.yunjelee.securemsg

/** Pure notification copy policy shared by non-UI carrier receive paths. */
object IncomingNotificationPolicy {
    fun preview(content: RelayContent): String {
        if (content.type != RelayContentCodec.TYPE_MMS) return content.text

        val details = buildList {
            content.subject?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            content.text.trim().takeIf { it.isNotEmpty() }?.let(::add)
            if (content.attachments.isNotEmpty()) add("첨부파일 ${content.attachments.size}개")
        }
        return details.joinToString(" · ").ifBlank { "MMS 메시지" }
    }
}
