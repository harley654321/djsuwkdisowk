package io.vdl.core.internal.engine

import io.vdl.core.Progress
import io.vdl.core.internal.db.DownloadTaskEntity
import io.vdl.core.internal.logging.VdlLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * Multi-threaded chunked HTTP engine.
 *
 * - resume via `Range: bytes=` + `If-Range: <etag>`; 206 = partial, 200 = changed -> restart once
 * - servers without range support fall back to a single sequential stream
 * - per-chunk exponential backoff with jitter, honoring Retry-After (429/5xx)
 * - every failure path logs its decision; nothing is silently swallowed
 */
internal class ChunkedHttpEngine internal constructor(
    private val client: OkHttpClient,
    private val log: VdlLog,
    private val progressIntervalMs: Long = 500L,
    sleeper: suspend (Long) -> Unit = { delay(it) },
    clockNanos: () -> Long = { System.nanoTime() }
) : DownloadEngine {

    private val probe = HttpProbe(client, log)
    private val transfer = ChunkTransfer(client, log, progressIntervalMs, sleeper, clockNanos)

    override suspend fun execute(
        task: DownloadTaskEntity,
        partFile: File,
        state: EngineState,
        onProgress: (Progress) -> Unit
    ): EngineOutcome = withContext(Dispatchers.IO) {
        if (state.chunks.isEmpty()) {
            when (val p = probe.probe(task)) {
                is HttpProbe.ProbeResult.Info -> {
                    state.bytesTotal = p.bytesTotal
                    state.acceptRanges = p.acceptRanges
                    state.etag = p.etag
                    state.contentType = p.contentType
                    state.chunks = if (p.acceptRanges && p.bytesTotal > 0) {
                        ChunkPlan.plan(p.bytesTotal, task.chunkSizeBytes, task.threads)
                    } else {
                        listOf(ChunkProgress(0, if (p.bytesTotal > 0) p.bytesTotal - 1 else -1, 0))
                    }
                    log.i(TAG) { "probe ok task=${task.id} total=${p.bytesTotal} ranges=${p.acceptRanges} chunks=${state.chunks.size}" }
                }

                is HttpProbe.ProbeResult.Failure -> return@withContext p.outcome
            }
        } else {
            // Reconcile the persisted snapshot against the freshly computed
            // plan: a snapshot that does not span the whole file (crashed
            // persist, schema change, truncated column) must never fake
            // completeness -> we re-plan and merge known chunk progress.
            if (state.acceptRanges && state.bytesTotal > 0) {
                val plan = ChunkPlan.plan(state.bytesTotal, task.chunkSizeBytes, task.threads)
                val saved = state.chunks.associateBy { it.start }
                state.chunks = plan.map { p ->
                    val s = saved[p.start]
                    if (s != null && s.end == p.end) {
                        s.copy(downloaded = s.downloaded.coerceIn(0L, p.length))
                    } else {
                        p
                    }
                }
                log.i(TAG) { "resume reconciled task=${task.id} planChunks=${plan.size} savedChunks=${saved.size}" }
            }
            log.i(TAG) { "resuming task=${task.id} chunks=${state.chunks.size} have=${task.bytesDownloaded} ranges=${state.acceptRanges}" }
        }

        var restarted = false
        while (true) {
            val outcome = coroutineScope {
                if (!state.acceptRanges || state.bytesTotal <= 0) {
                    return@coroutineScope transfer.streamOnce(task, partFile, state, onProgress)
                }
                runChunked(task, partFile, state, onProgress)
            }
            if (outcome === RestartSignal) {
                if (restarted) {
                    log.e(TAG) { "resource changed twice task=${task.id} decision=fatal" }
                    return@withContext EngineOutcome.Fatal("resource changed twice mid-flight")
                }
                restarted = true
                log.w(TAG) { "resource changed (If-Range 200), wiping part and restarting task=${task.id}" }
                RandomAccessFile(partFile, "rw").use { it.setLength(0L) }
                state.chunks = state.chunks.map { it.copy(downloaded = 0L) }
                continue
            }
            // streamOnce returns Either<EngineOutcome, TransferResult.Fail>;
            // unwrap the Fail envelope so a single-stream failure surfaces its
            // typed EngineOutcome instead of blowing up with a ClassCastException.
            return@withContext when (outcome) {
                is TransferResult.Fail -> outcome.outcome
                else -> outcome as EngineOutcome
            }
        }
        @Suppress("UNREACHABLE_CODE")
        EngineOutcome.Fatal("unreachable")
    }

    private suspend fun runChunked(
        task: DownloadTaskEntity,
        partFile: File,
        state: EngineState,
        onProgress: (Progress) -> Unit
    ): Any = coroutineScope {
        val board = ChunkBoard(state.chunks)
        val failure = AtomicReference<Any?>(null)
        RandomAccessFile(partFile, "rw").use { raf ->
            if (state.bytesTotal > 0) raf.setLength(state.bytesTotal)
            val channel = raf.channel
            val pending = board.remainingChunks()
            if (pending.isEmpty()) {
                log.i(TAG) { "nothing pending task=${task.id} (already complete)" }
                return@coroutineScope EngineOutcome.Success(state.bytesTotal)
            }
            val next = AtomicInteger(0)
            val workerCount = task.threads.coerceIn(1, pending.size)
            log.i(TAG) { "chunked start task=${task.id} pending=${pending.size} workers=$workerCount total=${state.bytesTotal}" }

            val jobs: List<Job> = (0 until workerCount).map {
                launch {
                    board.startWorker()
                    try {
                        while (coroutineContext.isActive) {
                            val idx = next.getAndIncrement()
                            if (idx >= pending.size) break
                            val chunkIndex = pending[idx]
                            when (val r = transfer.downloadChunk(task, channel, board, chunkIndex, state, onProgress)) {
                                is TransferResult.Ok -> log.d(TAG) { "chunk=$chunkIndex ok task=${task.id}" }
                                TransferResult.Restart -> {
                                    if (failure.compareAndSet(null, RestartSignal)) cancel()
                                    return@launch
                                }
                                is TransferResult.Fail -> {
                                    log.e(TAG) { "chunk=$chunkIndex terminal fail task=${task.id} code=${r.outcome}" }
                                    if (failure.compareAndSet(null, r.outcome)) cancel()
                                    return@launch
                                }
                            }
                        }
                    } finally {
                        board.endWorker()
                    }
                }
            }
            jobs.forEach { it.join() }
            state.chunks = board.snapshot()
            val err = failure.get()
            if (err === RestartSignal) return@coroutineScope RestartSignal
            if (err is EngineOutcome) return@coroutineScope err
            if (!board.allComplete()) {
                // Only reachable if the outer scope was cancelled (pause/cancel):
                // the outer join throws CancellationException first, so this is
                // a safety net that must never report success.
                log.w(TAG) { "chunks incomplete after join task=${task.id} decision=retryable-guard" }
                return@coroutineScope EngineOutcome.Retryable("cancelled while incomplete")
            } else {
                emitFinal(onProgress, board, state.bytesTotal)
                log.i(TAG) { "chunked done task=${task.id} total=${state.bytesTotal} parts=${board.snapshot().size}" }
            }
            EngineOutcome.Success(state.bytesTotal)
        }
    }

    private fun emitFinal(onProgress: (Progress) -> Unit, board: ChunkBoard, total: Long) {
        onProgress(
            Progress(
                bytesDownloaded = board.totalDownloaded(),
                bytesTotal = total,
                speedBps = 0L,
                etaMs = 0L,
                activeThreads = 0
            )
        )
    }

    internal companion object {
        internal const val TAG = "[VDL][ENGINE]"
    }
}
