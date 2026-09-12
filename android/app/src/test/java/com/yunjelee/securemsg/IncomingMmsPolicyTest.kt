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

    @Test
    fun acceptsAPhotoOnlyRowWhoseOnlyPartWasTooLargeForTheIdentityList() {
        // The defect this clause closes: a 7 MB camera photo is dropped from
        // the identity part list for being over the attachment cap, subject
        // and body are empty, and the row was therefore deferred forever --
        // never stored, never notified, never relayed, for a message that had
        // in fact arrived complete.
        assertTrue(
            IncomingMmsPolicy.isReady(
                mms(
                    address = "+821012345678",
                    relayCandidates = listOf(candidate("image/jpeg", 7_150_000)),
                ),
            ),
        )
    }

    @Test
    fun stillDefersARowCarryingNothingButTheLayoutScript() {
        // Every MMS has a smil part; one that has nothing else yet really is
        // the empty shell the platform publishes before the download lands.
        assertFalse(
            IncomingMmsPolicy.isReady(
                mms(
                    address = "+821012345678",
                    relayCandidates = listOf(candidate("application/smil", 402)),
                ),
            ),
        )
        assertFalse(IncomingMmsPolicy.isReady(mms(address = "+821012345678")))
    }

    private fun candidate(contentType: String, declaredSize: Int) = ProviderMmsCandidate(
        partId = 7L,
        name = "part-7",
        contentType = contentType,
        declaredSize = declaredSize,
    )

    private fun mms(
        address: String,
        subject: String? = null,
        body: String = "",
        parts: List<ProviderMmsPart> = emptyList(),
        relayCandidates: List<ProviderMmsCandidate> = emptyList(),
    ) = ProviderMms(
        id = 42L,
        address = address,
        subject = subject,
        body = body,
        date = 1L,
        parts = parts,
        relayCandidates = relayCandidates,
    )
}
