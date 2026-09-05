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

    @Test
    fun ignoresSenderControlledUrlInFieldPrecedingContentLocation() {
        val decoy = "https://attacker.example.invalid/x"
        val url = "https://mmsc.example.invalid/message/abc"
        val data = byteArrayOf(0x8C.toByte(), 0x82.toByte()) +
            byteArrayOf(0x96.toByte()) + decoy.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) +
            byteArrayOf(0x83.toByte()) + url.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
        assertEquals(url, MmsContentLocationParser.find(data))
    }

    @Test
    fun findsQuotedContentLocationTextString() {
        val url = "https://mmsc.example.invalid/m/1"
        val data = byteArrayOf(0x83.toByte(), 0x7F) +
            url.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
        assertEquals(url, MmsContentLocationParser.find(data))
    }

    @Test
    fun prefersTrailingUrlWhenNoContentLocationFieldIsPresent() {
        val decoy = "https://attacker.example.invalid/x"
        val url = "http://mmsc.example.invalid/m/2"
        val data = byteArrayOf(0x96.toByte()) + decoy.toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0, 0x8D.toByte(), 0x90.toByte()) +
            url.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
        assertEquals(url, MmsContentLocationParser.find(data))
    }

    @Test
    fun keepsWholeUrlThatEmbedsAnotherScheme() {
        val url = "https://mmsc.example.invalid/fetch?next=http://mmsc.example.invalid/b"
        val data = byteArrayOf(0x8D.toByte()) +
            url.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
        assertEquals(url, MmsContentLocationParser.find(data))
    }
}
