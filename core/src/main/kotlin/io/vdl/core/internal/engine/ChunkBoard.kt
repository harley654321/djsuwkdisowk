package io.vdl.core.internal.engine

import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe progress board shared by the engine workers. Writers are the
 * worker coroutines (one add per buffer write); the single reader is the
 * progress ticker plus the queue after cancellation (post-join, so no
 * concurrent access at read time for the final snapshot).
 */
internal class ChunkBoard internal constructor(
    initial: List<ChunkProgress>
) {
    private val lock = Any()
    private val chunks = initial.map { it.copy() }.toMutableList()
    private val totalAtStart = initial.sumOf { it.downloaded }.coerceAtLeast(0L)
    private val addedSinceStart = java.util.concurrent.atomic.AtomicLong(0L)
    private val active = AtomicInteger(0)

    internal fun startWorker() {
        active.incrementAndGet()
    }

    internal fun endWorker() {
        active.decrementAndGet()
    }

    /** Register [bytes] written for chunk [index]. */
    internal fun add(index: Int, bytes: Long) {
        synchronized(lock) {
            val c = chunks.getOrNull(index) ?: return
            val room = if (c.length > 0) c.length - c.downloaded else bytes
            val inc = bytes.coerceIn(0L, room)
            chunks[index] = c.copy(downloaded = c.downloaded + inc)
            addedSinceStart.addAndGet(inc)
        }
    }

    internal fun totalDownloaded(): Long = totalAtStart + addedSinceStart.get()

    internal fun bytesTotal(): Long = synchronized(lock) {
        val total = chunks.sumOf { if (it.length > 0) it.length else 0L }
        if (total > 0) total else -1L
    }

    internal fun remainingChunks(): List<Int> = synchronized(lock) {
        chunks.indices.filter { !chunks[it].isComplete }
    }

    internal fun snapshot(): List<ChunkProgress> = synchronized(lock) { chunks.toList() }

    internal fun activeWorkers(): Int = active.get()

    internal fun allComplete(): Boolean = synchronized(lock) {
        chunks.isNotEmpty() && chunks.all { it.length < 0 || it.isComplete }
    }
}
