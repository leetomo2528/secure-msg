package com.yunjelee.securemsg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MmsProviderPolicyTest {
    @Test
    fun `provider MIME is lowercased and parameters are stripped`() {
        assertEquals("image/jpeg", MmsProvider.normalizePartContentType(" Image/JPEG; charset=UTF-8"))
        assertEquals("text/plain", MmsProvider.normalizePartContentType("TEXT/PLAIN ; format=flowed"))
    }

    @Test
    fun `blank and invalid provider MIME use binary fallback`() {
        val fallback = "application/octet-stream"
        assertEquals(fallback, MmsProvider.normalizePartContentType(null))
        assertEquals(fallback, MmsProvider.normalizePartContentType("  ; charset=utf-8"))
        assertEquals(fallback, MmsProvider.normalizePartContentType("text html; charset=utf-8"))
        assertEquals(fallback, MmsProvider.normalizePartContentType("image/"))
    }

    @Test
    fun `recent MMS processing continues after one row fails`() = runBlocking {
        val processed = mutableListOf<Long>()
        val failures = mutableListOf<Long>()

        MmsRowProcessor.process(listOf(31L, 32L, 33L), processRow = { id ->
            processed += id
            if (id == 32L) error("poison row")
        }, onFailure = { id, _ -> failures += id })

        assertEquals(listOf(31L, 32L, 33L), processed)
        assertEquals(listOf(32L), failures)
    }

    @Test(expected = CancellationException::class)
    fun `recent MMS processing preserves coroutine cancellation`() = runBlocking {
        MmsRowProcessor.process(listOf(1L), { throw CancellationException("stop") }, { _, _ -> })
        }
}
