package io.vdl.core.internal.engine

import kotlin.math.ceil
import kotlin.math.min

/**
 * Splits a known-size remote file into ranged chunks of at most [chunkSizeBytes],
 * downloaded in parallel by at most [threads] workers.
 */
internal object ChunkPlan {

    internal fun plan(bytesTotal: Long, chunkSizeBytes: Long, threads: Int): List<ChunkProgress> {
        if (bytesTotal <= 0) return emptyList()
        val size = chunkSizeBytes.coerceAtLeast(1L)
        val count = ceil(bytesTotal.toDouble() / size).toLong().coerceAtLeast(1L)
        // Cap chunk count so per-chunk bookkeeping stays sane (1MiB min per chunk).
        val minChunk = 256L * 1024
        val maxCount = (bytesTotal / minChunk).coerceAtLeast(1L)
        val chunkCount = min(count, maxCount)
        val actualSize = ceil(bytesTotal.toDouble() / chunkCount).toLong()
        val list = ArrayList<ChunkProgress>(chunkCount.toInt())
        var start = 0L
        while (start < bytesTotal) {
            val end = (start + actualSize - 1).coerceAtMost(bytesTotal - 1)
            list.add(ChunkProgress(start, end, 0L))
            start = end + 1
        }
        // threads only bound workers, not chunk count; sanity check in tests.
        require(threads >= 1) { "threads >= 1 required" }
        return list
    }
}
