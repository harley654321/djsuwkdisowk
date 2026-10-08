package io.vdl.core

import io.vdl.core.internal.db.ErrorCodec
import io.vdl.core.internal.engine.Backoff
import io.vdl.core.internal.engine.ChunkCodec
import io.vdl.core.internal.engine.ChunkPlan
import io.vdl.core.internal.engine.ChunkProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BackoffTest {

    private val policy = RetryPolicy.Exponential(baseDelayMs = 100, maxAttempts = 6, maxDelayMs = 1_000)

    @Test
    fun growsExponentiallyAndCaps() {
        val fixed = Random(1)
        val seq = (0 until 6).map { Backoff.delayMs(policy, it, random = fixed) }
        assertTrue(seq[0] in 80L..120L)
        assertTrue(seq[2] in 320L..480L)
        // attempt >= 4 hits the 1s cap; jitter keeps it under cap*1.2
        assertTrue(seq[4] <= 1_200L)
        assertTrue(seq[5] <= 1_200L)
    }

    @Test
    fun retryAfterWinsWhenLarger() {
        val d = Backoff.delayMs(policy, 0, retryAfterMs = 60_000)
        assertEquals(60_000L, d)
    }

    @Test
    fun nonePolicyHasNoDelay() {
        assertEquals(0L, Backoff.delayMs(RetryPolicy.None, 3))
    }

    @Test
    fun retryAfterHeaderParsesBothFormats() {
        assertEquals(0L, Backoff.retryAfterMs("0"))
        assertEquals(5L, Backoff.retryAfterMs("5"))
        assertNull(Backoff.retryAfterMs(null))
        // HTTP-date parses to >= 0
        assertTrue((Backoff.retryAfterMs("Wed, 21 Oct 2099 07:28:00 GMT") ?: -1L) >= 0L)
    }
}

class ChunkCodecTest {

    @Test
    fun roundTrip() {
        val chunks = listOf(
            ChunkProgress(0, 999, 500),
            ChunkProgress(1000, 1999, 1000),
            ChunkProgress(2000, 2000, 1)
        )
        assertEquals(chunks, ChunkCodec.decode(ChunkCodec.encode(chunks)))
    }

    @Test
    fun corruptInputIsSanitized() {
        val decoded = ChunkCodec.decode("0-99-999999;garbage;;5-9-1")
        // oversized downloaded is clamped to chunk length; garbage dropped
        assertEquals(2, decoded.size)
        assertTrue(decoded[0].downloaded <= 100L)
        assertTrue(decoded.all { it.downloaded >= 0 })
    }

    @Test
    fun overlappingChunksAreDeduped() {
        val decoded = ChunkCodec.decode("0-199-50;100-299-60")
        // starts are distinct after dedup by start
        assertEquals(2, decoded.size)
    }

    @Test
    fun emptyInputGivesEmptyList() {
        assertTrue(ChunkCodec.decode(null).isEmpty())
        assertTrue(ChunkCodec.decode("").isEmpty())
        assertTrue(ChunkCodec.encode(emptyList()).isEmpty())
    }
}

class ChunkPlanTest {

    @Test
    fun coversFileExactly() {
        val chunks = ChunkPlan.plan(10L * 1024 * 1024, 256L * 1024, 4)
        assertEquals(0L, chunks.first().start)
        assertEquals(10L * 1024 * 1024 - 1, chunks.last().end)
        // contiguous, no holes, no overlap
        chunks.zipWithNext().forEach { (a, b) -> assertEquals(a.end + 1, b.start) }
        assertEquals(10L * 1024 * 1024, chunks.sumOf { it.length })
    }

    @Test
    fun tinyFileIsOneChunk() {
        val chunks = ChunkPlan.plan(100L, 256L * 1024, 4)
        assertEquals(1, chunks.size)
        assertEquals(0L to 99L, chunks[0].start to chunks[0].end)
    }

    @Test
    fun zeroSizeGivesNothing() {
        assertTrue(ChunkPlan.plan(0L, 256L * 1024, 4).isEmpty())
        assertTrue(ChunkPlan.plan(-5L, 256L * 1024, 4).isEmpty())
    }

    @Test
    fun manyChunksRespectMinChunkSize() {
        val total = 10L * 1024 * 1024
        val chunks = ChunkPlan.plan(total, 1024L, 4) // would be 10240 chunks uncapped
        assertTrue(chunks.size <= (total / (256L * 1024)).toInt() + 1)
        assertEquals(total, chunks.sumOf { it.length })
    }
}

class ErrorCodecTest {

    @Test
    fun roundTripAllTypes() {
        val errors = listOf(
            DownloadError.Network("timeout", "SocketTimeoutException"),
            DownloadError.Http(503, "service unavailable"),
            DownloadError.Storage("write failed", "IOException"),
            DownloadError.InvalidRequest("bad url"),
            DownloadError.FileAlreadyExists,
            DownloadError.InsufficientSpace
        )
        for (e in errors) {
            assertEquals(e, ErrorCodec.decode(ErrorCodec.encode(e)))
        }
    }

    @Test
    fun unknownTypeDecodesToNull() {
        assertNull(ErrorCodec.decode("WhatIsThis|abc|"))
        assertNull(ErrorCodec.decode(null))
    }
}
