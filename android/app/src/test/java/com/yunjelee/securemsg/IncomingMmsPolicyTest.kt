package com.yunjelee.securemsg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingMmsPolicyTest {
    @Test
    fun retriesWhenProviderReadFails() {
        assertFalse(IncomingMmsPolicy.isReady(null))
    }

    @Test
    fun retriesBlankDownloadPlaceholder() {
        assertFalse(IncomingMmsPolicy.isReady(mms(address = "")))
        assertFalse(IncomingMmsPolicy.isReady(mms(address = "+821012345678")))
    }

    @Test
    fun retriesPayloadUntilSenderAddressAppears() {
        assertFalse(IncomingMmsPolicy.isReady(mms(address = "", body = "downloaded")))
    }

    @Test
    fun acceptsAnyMeaningfulMmsPayload() {
        assertTrue(IncomingMmsPolicy.isReady(mms(address = "+821012345678", body = "hello")))
        assertTrue(IncomingMmsPolicy.isReady(mms(address = "+821012345678", subject = "subject")))
        assertTrue(
            IncomingMmsPolicy.isReady(
                mms(
                    address = "+821012345678",
                    parts = listOf(ProviderMmsPart("photo.jpg", "image/jpeg", byteArrayOf(1))),
                ),
            ),
        )
    }

    private fun mms(
        address: String,
        subject: String? = null,
        body: String = "",
        parts: List<ProviderMmsPart> = emptyList(),
    ) = ProviderMms(
        id = 42L,
        address = address,
        subject = subject,
        body = body,
        date = 1L,
        parts = parts,
    )
}
