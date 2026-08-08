package com.yunjelee.securemsg

/** Pure, local-only filtering for the Android message UI. */
object MessageSearch {
    fun filterThreads(threads: List<SmsThread>, query: String): List<SmsThread> {
        val term = query.trim()
        if (term.isEmpty()) return threads

        return threads.filter { thread ->
            thread.displayName.contains(term, ignoreCase = true) ||
                thread.phoneNumber.contains(term, ignoreCase = true)
        }
    }

    fun filterMessages(messages: List<MessageRow>, query: String): List<MessageRow> {
        val term = query.trim()
        if (term.isEmpty()) return messages

        return messages.filter { message ->
            !message.blocked && (
                message.plaintext.contains(term, ignoreCase = true) ||
                    message.subject?.contains(term, ignoreCase = true) == true
                )
        }
    }
}
