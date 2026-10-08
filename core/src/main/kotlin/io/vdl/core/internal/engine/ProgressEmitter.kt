package io.vdl.core.internal.engine

import io.vdl.core.Progress

/**
 * Throttled progress emission with EMA speed and ETA.
 * Uses a monotonic clock source (injected); wall clock never touches durations.
 */
internal class ProgressEmitter internal constructor(
    private val clockNanos: () -> Long,
    private val intervalMs: Long,
    private val bytesTotal: () -> Long,
    private val board: ChunkBoard,
    private val sink: (Progress) -> Unit
) {
    private var lastNanos: Long = clockNanos()
    private var lastBytes: Long = board.totalDownloaded()
    private var emaBps: Double = 0.0

    internal fun maybe(force: Boolean = false) {
        val now = clockNanos()
        val dt = now - lastNanos
        if (!force && dt < intervalMs * 1_000_000L) return
        val cur = board.totalDownloaded()
        val bps = if (dt > 0) (cur - lastBytes) * 1_000_000_000.0 / dt else 0.0
        emaBps = if (emaBps == 0.0) bps else 0.5 * emaBps + 0.5 * bps
        val total = bytesTotal()
        val eta = if (emaBps > 0 && total > 0 && cur <= total) {
            ((total - cur) * 1_000_000_000.0 / emaBps).toLong()
        } else -1L
        sink(
            Progress(
                bytesDownloaded = cur,
                bytesTotal = if (total > 0) total else -1L,
                speedBps = emaBps.toLong().coerceAtLeast(0L),
                etaMs = eta,
                activeThreads = board.activeWorkers()
            )
        )
        lastNanos = now
        lastBytes = cur
    }
}
