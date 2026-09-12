package com.yunjelee.securemsg

/**
 * Attachment byte budget the carrier's MMS ceiling actually leaves us.
 *
 * Nothing in this app has ever consulted that ceiling. The AOSP default
 * maximum message size is 300 KiB, which is BELOW our own
 * [RelayContentCodec.MAX_ATTACHMENT_BYTES], so a message the relay accepts and
 * [MmsPduComposer] happily encodes can still be refused by the MMSC — and a
 * carrier rejection arrives as an opaque failure with no size in it, which the
 * user reads as a send that silently did nothing. Downscaling to this budget
 * before composing is the only point where the limit can still be honoured.
 *
 * The SmsManager carrier-config read stays at the call site, not here, so this
 * object keeps no android.* dependency and stays unit testable.
 */
object MmsAttachmentBudget {
    /**
     * AOSP's default for SmsManager's MMS_CONFIG_MAX_MESSAGE_SIZE, used when
     * the carrier config reports nothing usable.
     */
    const val DEFAULT_MAX_MESSAGE_SIZE = 307_200

    /**
     * An absent carrier-config key reads back as 0, and OEM builds have been
     * seen reporting placeholder values in both directions. Clamping both ends
     * keeps a bogus report from making every photo unsendable, and from
     * claiming more room than any MMSC in service actually grants.
     */
    const val MIN_MAX_MESSAGE_SIZE = 51_200
    const val MAX_MAX_MESSAGE_SIZE = 2 * 1024 * 1024

    /**
     * Everything [MmsPduComposer.compose] writes outside the body parts: the
     * message type, a 32-character transaction id, the MMS version, the From
     * insert-address token, the To encoded-string with its /TYPE=PLMN suffix,
     * the multipart/related Content-Type with its start and type parameters,
     * and the part-count uintvar. That totals roughly 105 bytes for a real
     * recipient. 256 is a deliberate over-estimate: overshooting costs a little
     * image quality, while undershooting costs the whole message. The Subject
     * is excluded here and charged through subjectBytes, since its length
     * belongs to the caller.
     */
    private const val PDU_HEADER_BYTES = 256

    /**
     * Per body part: the header-length and data-length uintvars, plus the
     * header block [MmsPduComposer] writes for each part — a value-length, the
     * Content-Type token or string, the charset on the text part, the name
     * parameter, a quoted Content-ID, and a Content-Location. The sanitized
     * file name is emitted twice (name parameter and Content-Location), so the
     * real cost tracks name length: about 40 bytes for the names this app
     * generates. 96 is again a deliberate over-estimate for the same reason,
     * but it is not unbounded — a caller forwarding a user-chosen file name
     * should shorten it rather than lean on this margin.
     */
    private const val PART_HEADER_BYTES = 96

    /**
     * Bytes left for attachment data once PDU overhead is paid.
     *
     * @param maxMessageSize the carrier's reported ceiling, or
     *   [DEFAULT_MAX_MESSAGE_SIZE] when it reports none.
     * @param textBytes UTF-8 length of the caption. It must be the encoded byte
     *   count, never the character count: Korean costs three bytes per
     *   character, so a char count understates a Korean caption threefold and
     *   hands back a budget the PDU cannot hold.
     * @param subjectBytes UTF-8 length of the subject the composer will write.
     * @param partCount body parts in the PDU, the text part included — that is
     *   `attachments.size + 1`, matching what [MmsPduComposer.compose] emits.
     */
    fun forCarrier(
        maxMessageSize: Int,
        textBytes: Int,
        subjectBytes: Int,
        partCount: Int,
    ): Int {
        val ceiling = maxMessageSize.coerceIn(MIN_MAX_MESSAGE_SIZE, MAX_MAX_MESSAGE_SIZE).toLong()
        // Counts are floored at 0 individually: a negative count is nonsense
        // input, and letting one cancel out real overhead would buy back budget
        // the PDU never had. Long arithmetic keeps an absurd caller-supplied
        // count from wrapping into a large positive budget.
        val overhead = PDU_HEADER_BYTES +
            subjectBytes.coerceAtLeast(0).toLong() +
            textBytes.coerceAtLeast(0).toLong() +
            PART_HEADER_BYTES.toLong() * partCount.coerceAtLeast(0).toLong()
        return (ceiling - overhead).coerceIn(0L, ceiling).toInt()
    }
}
