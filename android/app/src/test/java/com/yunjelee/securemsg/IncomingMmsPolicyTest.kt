package com.yunjelee.securemsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    // --- settle: defer on a part that cannot be read yet, or persist ---------

    @Test
    fun aCompleteMessageIsPersistedAsIsAndNeverSpendsARetry() {
        val material = RelayMaterial(
            parts = listOf(ProviderMmsPart("photo.jpg", "image/jpeg", byteArrayOf(1))),
            omissions = listOf(IncomingOmissionNotice.Omission(IncomingOmissionNotice.Kind.VIDEO, 4_000_000)),
        )
        var asked = 0

        val settled = IncomingMmsPolicy.settle(material) { asked += 1; IncomingMmsPolicy.Deferral.RETRYING }

        assertSame(material, settled)
        assertEquals(0, asked)
    }

    @Test
    fun aPartStillLandingHoldsTheWholeMessageBackWhileARetryCanBeScheduled() {
        val material = RelayMaterial(
            parts = listOf(ProviderMmsPart("photo.jpg", "image/jpeg", byteArrayOf(1))),
            omissions = emptyList(),
            pending = listOf(candidate("image/jpeg", -1)),
        )
        var asked = 0

        // Null means: write nothing. Persisting here would claim the message
        // in the dedupe ledgers with the photo missing -- for good, for a photo
        // too big to join the identity, which no later read then changes.
        assertNull(IncomingMmsPolicy.settle(material) { asked += 1; IncomingMmsPolicy.Deferral.RETRYING })
        assertEquals(1, asked)
    }

    @Test
    fun withNoRetryLeftEachUnreadPartBecomesAnUnweighedOmission() {
        val arrived = ProviderMmsPart("a.jpg", "image/jpeg", byteArrayOf(1))
        val material = RelayMaterial(
            parts = listOf(arrived),
            omissions = emptyList(),
            pending = listOf(candidate("image/jpeg", 2_000_000), candidate("audio/amr", -1)),
        )

        val settled = IncomingMmsPolicy.settle(material) { IncomingMmsPolicy.Deferral.EXHAUSTED }!!

        // What did arrive is not held hostage by what did not.
        assertEquals(listOf(arrived), settled.parts)
        assertTrue(settled.pending.isEmpty())
        // No size even where the provider declared one: the part was never
        // read, so 용량이 커서 would be a guess about the cause.
        assertEquals(
            listOf(
                IncomingOmissionNotice.Omission(IncomingOmissionNotice.Kind.IMAGE, null),
                IncomingOmissionNotice.Omission(IncomingOmissionNotice.Kind.AUDIO, null),
            ),
            settled.omissions,
        )
        assertEquals(
            "봐\n[사진 1장은 받지 못했습니다, 음성 1개는 받지 못했습니다]",
            IncomingOmissionNotice.appendTo("봐", settled.omissions),
        )
    }

    @Test
    fun anUnreadPhotoSinksTheSizeClaimOfAWeighedOne() {
        val material = RelayMaterial(
            parts = emptyList(),
            omissions = listOf(IncomingOmissionNotice.Omission(IncomingOmissionNotice.Kind.IMAGE, 7_150_000)),
            pending = listOf(candidate("image/jpeg", -1)),
        )

        val settled = IncomingMmsPolicy.settle(material) { IncomingMmsPolicy.Deferral.EXHAUSTED }!!

        assertEquals("[사진 2장은 받지 못했습니다]", IncomingOmissionNotice.appendTo("", settled.omissions))
    }

    @Test
    fun aPhotoOnlyMmsWhosePhotoNeverLandsEndsWithANoticeNotAnEmptyBubble() {
        // The defect end to end: a photo-only MMS passes isReady on its
        // candidate, the photo read keeps failing, and the message used to be
        // persisted with an empty body and no attachment -- a blank bubble on
        // every device and in the notification. Now it defers while the retry
        // budget lasts (the real DeferredMmsRetries, with SmsBridgeService's
        // three delays) and then persists with a line saying what is missing.
        val row = mms(address = "+821012345678", relayCandidates = listOf(candidate("image/jpeg", -1)))
        assertTrue(IncomingMmsPolicy.isReady(row))
        val retries = DeferredMmsRetries(longArrayOf(15_000L, 60_000L, 240_000L), trackedMax = 512)
        var queued: DeferredMmsRetries.Ticket? = null
        // The broadcast, then each retry it chains, which begins before it reads.
        val outcomes = (1..4).map {
            queued?.let(retries::begin)
            queued = null
            val material = MmsProvider.materialize(
                row.relayCandidates,
                ImageShrinkPolicy.INCOMING_ATTACHMENT_BUDGET,
                read = { ImageShrinkPolicy.PartRead.Failed },
                readTruncated = { ByteArray(0) },
                shrink = { _, _, _ -> error("nothing was read, so nothing may be shrunk") },
            )
            // Decision -> "will a retry come back", as scheduleDeferredMmsRetry maps it.
            IncomingMmsPolicy.settle(material) {
                val decision = retries.schedule(row.id, rescan = false)
                if (decision is DeferredMmsRetries.Decision.Launch) queued = decision.ticket
                decision.deferral
            }
        }

        assertEquals(listOf(null, null, null), outcomes.take(3))
        assertNull("the budget ran out, so nothing is left queued", queued)
        val persisted = outcomes.last()!!
        assertTrue(persisted.parts.isEmpty())
        assertEquals("[사진 1장은 받지 못했습니다]", IncomingOmissionNotice.appendTo(row.body, persisted.omissions))
    }

    // --- SM-7: a full retry table is not a spent budget ----------------------

    @Test
    fun aFullRetryTableLeavesAPendingPhotoForTheNextSweepInsteadOfAnOmission() {
        val material = RelayMaterial(
            parts = emptyList(),
            omissions = emptyList(),
            pending = listOf(candidate("image/jpeg", -1)),
        )

        // Nothing written: the row stays in the provider, as a not-ready one does.
        assertNull(IncomingMmsPolicy.settle(material) { IncomingMmsPolicy.Deferral.NO_ROOM })
    }

    @Test
    fun fiveHundredTwelveBrokenMmsNoLongerCostTheNextPhotoItsRetries() {
        // The service's pieces, as processIncomingMms uses them: 512 MMS that
        // never become ready (no content) spend their whole budget, then 512
        // with a zero-length photo part spend theirs and are persisted with a
        // notice (settled). Before, all of those ids stayed in the table for
        // the life of the service, and the next photo still landing was
        // refused a retry (TABLE_FULL) and stored at once as
        // "[사진 1장은 받지 못했습니다]" -- for good.
        val retries = DeferredMmsRetries(longArrayOf(15_000L, 60_000L, 240_000L), trackedMax = 512)
        fun exhaust(id: Long) {
            while (true) {
                val decision = retries.schedule(id, rescan = true)
                if (decision is DeferredMmsRetries.Decision.Launch) retries.begin(decision.ticket) else break
            }
        }
        val pendingPhoto = RelayMaterial(emptyList(), emptyList(), pending = listOf(candidate("image/jpeg", -1)))
        for (id in 1_000L until 1_512L) exhaust(id) // not ready: never stored, never settled
        for (id in 2_000L until 2_512L) {
            exhaust(id)
            val persisted = IncomingMmsPolicy.settle(pendingPhoto) { retries.schedule(id, rescan = true).deferral }
            assertEquals(1, persisted!!.omissions.size)
            retries.settled(id) // persistCarrier committed
        }

        // A real photo whose bytes are still landing gets its retry.
        val photo = IncomingMmsPolicy.settle(pendingPhoto) { retries.schedule(9_999L, rescan = false).deferral }
        assertNull("deferred, not stored with a missing-photo notice", photo)
        assertTrue(retries.queuedLive(9_999L))
        assertTrue(retries.trackedCount() <= 512)
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
