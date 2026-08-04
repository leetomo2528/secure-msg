package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MmsContentLocationParserTest {
    @Test
    fun findsNullTerminatedCarrierUrlInsideNotificationPdu() {
        val prefix = byteArrayOf(0x8C.toByte(), 0x82.toByte(), 0x83.toByte())
        val url = "https://mmsc.example.invalid/message/abc"
        val data = prefix + url.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 0x8D.toByte())
        assertEquals(url, MmsContentLocationParser.find(data))
    }

    @Test
    fun rejectsPduWithoutHttpLocation() {
        assertNull(MmsContentLocationParser.find(byteArrayOf(0x8C.toByte(), 0x82.toByte(), 0)))
    }
}
