package io.vdl.core.internal.engine

import io.vdl.core.DownloadRequestBuilder
import io.vdl.core.internal.db.DownloadTaskEntity
import io.vdl.core.internal.logging.VdlLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.coroutines.coroutineContext

/**
 * One HTTP chunk (or a whole stream) with per-chunk retry and backoff.
 * Both modes emit throttled progress through [ProgressEmitter].
 */
internal class ChunkTransfer internal constructor(
    private val client: OkHttpClient,
    private val log: VdlLog,
    private val progressIntervalMs: Long,
    private val sleeper: suspend (Long) -> Unit,
    private val clockNanos: () -> Long
) {

    internal suspend fun downloadChunk(
        task: DownloadTaskEntity,
        channel: FileChannel,
        board: ChunkBoard,
        chunkIndex: Int,
        state: EngineState,
        onProgress: (io.vdl.core.Progress) -> Unit
    ): TransferResult {
        val policy = task.retryPolicy()
        var attempt = 0
        var retryAfterMs: Long? = null
        var lastMessage = "init"
        var lastCode: Int? = null

        while (true) {
            currentCoroutineContext().ensureActive()
            val current = board.snapshot()[chunkIndex]
            if (current.isComplete) return TransferResult.Ok

            val rangeStart = current.start + current.downloaded
            val request = rangedRequest(task, "bytes=$rangeStart-${current.end}", state.etag)
            val call = client.newCall(request)
            val handle = currentCoroutineContext().job.invokeOnCompletion { cause ->
                if (cause != null) call.cancel()
            }
            try {
                call.execute().use { resp ->
                    when {
                        resp.code == 206 -> {
                            val expected = current.length - current.downloaded
                            val emitter = ProgressEmitter(clockNanos, progressIntervalMs, { state.bytesTotal }, board, onProgress)
                            val got = pump(resp.body.byteStream(), channel, rangeStart, expected, board, chunkIndex, emitter)
                            if (got >= expected) {
                                emitter.maybe(force = true)
                                return TransferResult.Ok
                            }
                            lastMessage = "short body got=$got expected=$expected"
                        }

                        resp.code == 200 ->
                            return TransferResult.Restart // If-Range mismatch (or Range ignored)

                        resp.code == 416 ->
                            if (board.snapshot()[chunkIndex].isComplete) return TransferResult.Ok
                            else return TransferResult.Fail(EngineOutcome.Fatal("416 invalid range", 416))

                        resp.code == 429 || resp.code in 500..599 -> {
                            lastCode = resp.code
                            throw Throttle("http ${resp.code}", Backoff.retryAfterMs(resp.header("Retry-After")))
                        }

                        else ->
                            return TransferResult.Fail(EngineOutcome.Fatal("http ${resp.code}", resp.code))
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throttle) {
                retryAfterMs = t.retryAfterMs
                lastMessage = t.message ?: "throttled"
            } catch (io: java.io.IOException) {
                lastMessage = "io: ${io.message}"
                log.d(TAG) { "chunk=$chunkIndex io attempt=$attempt task=${task.id} reason=$lastMessage" }
            } finally {
                handle.dispose()
            }

            attempt++
            if (attempt >= policy.maxAttempts) {
                return TransferResult.Fail(
                    EngineOutcome.Retryable("chunk=$chunkIndex attempts=$attempt: $lastMessage", lastCode)
                )
            }
            val backoff = Backoff.delayMs(policy, attempt, retryAfterMs)
            log.w(TAG) { "chunk=$chunkIndex retry attempt=$attempt backoff=${backoff}ms reason=$lastMessage task=${task.id}" }
            sleeper(backoff)
        }
    }

    /**
     * Sequential single-stream mode: no Range support or unknown size.
     * No resume: the part file is truncated on every attempt.
     */
    internal suspend fun streamOnce(
        task: DownloadTaskEntity,
        partFile: File,
        state: EngineState,
        onProgress: (io.vdl.core.Progress) -> Unit
    ): Any {
        val policy = task.retryPolicy()
        var attempt = 0
        var retryAfterMs: Long? = null
        var lastMessage = "init"

        while (true) {
            currentCoroutineContext().ensureActive()
            val request = rangedRequest(task, null, null)
            val call = client.newCall(request)
            val handle = currentCoroutineContext().job.invokeOnCompletion { cause ->
                if (cause != null) call.cancel()
            }
            try {
                var result: Any? = null
                call.execute().use { resp ->
                    result = when {
                        resp.code == 200 || resp.code == 206 ->
                            handleStreamBody(resp, partFile, task, state, onProgress)

                        resp.code == 429 || resp.code in 500..599 ->
                            throw Throttle("http ${resp.code}", Backoff.retryAfterMs(resp.header("Retry-After")))

                        else -> TransferResult.Fail(EngineOutcome.Fatal("http ${resp.code}", resp.code))
                    }
                }
                when (result) {
                    null -> Unit // retryable short body; message captured below
                    else -> return result
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throttle) {
                retryAfterMs = t.retryAfterMs
                lastMessage = t.message ?: "throttled"
            } catch (io: java.io.IOException) {
                lastMessage = "io: ${io.message}"
                log.d(TAG) { "stream io attempt=$attempt task=${task.id} reason=$lastMessage" }
            } finally {
                handle.dispose()
            }

            attempt++
            if (attempt >= policy.maxAttempts) {
                return TransferResult.Fail(EngineOutcome.Retryable("stream attempts=$attempt: $lastMessage"))
            }
            val backoff = Backoff.delayMs(policy, attempt, retryAfterMs)
            log.w(TAG) { "stream retry attempt=$attempt backoff=${backoff}ms reason=$lastMessage task=${task.id}" }
            sleeper(backoff)
        }
    }

    /** Returns EngineOutcome.Success or null (short body -> caller retries). */
    private suspend fun handleStreamBody(
        resp: okhttp3.Response,
        partFile: File,
        task: DownloadTaskEntity,
        state: EngineState,
        onProgress: (io.vdl.core.Progress) -> Unit
    ): EngineOutcome? {
        val total: Long
        if (resp.code == 206) {
            val cr = resp.header("Content-Range")
            val start = cr?.substringAfter("bytes ")?.substringBefore("-")?.trim()?.toLongOrNull() ?: 0L
            if (start != 0L) return EngineOutcome.Fatal("stream received mid-file 206")
            total = HttpProbe.parseTotalFromContentRange(cr) ?: -1L
        } else {
            total = resp.header("Content-Length")?.toLongOrNull() ?: -1L
        }
        RandomAccessFile(partFile, "rw").use { raf ->
            raf.setLength(0L)
            val channel = raf.channel
            val board = ChunkBoard(
                listOf(ChunkProgress(0, if (total > 0) total - 1 else -1, 0))
            )
            board.startWorker()
            try {
                val emitter = ProgressEmitter(clockNanos, progressIntervalMs, { if (total > 0) total else -1L }, board, onProgress)
                val got = pump(resp.body.byteStream(), channel, 0L, if (total > 0) total else -1L, board, 0, emitter)
                state.bytesTotal = if (total > 0) total else got
                state.chunks = listOf(ChunkProgress(0, if (total > 0) total - 1 else -1, got))
                emitter.maybe(force = true)
                return if (total > 0 && got < total) {
                    log.w(TAG) { "stream short body got=$got expected=$total task=${task.id} decision=retry" }
                    null
                } else {
                    log.i(TAG) { "stream done task=${task.id} bytes=$got declared=$total" }
                    EngineOutcome.Success(if (total > 0) total else got)
                }
            } finally {
                board.endWorker()
            }
        }
    }

    /** Copies [expected] bytes (or to EOF when expected < 0) from [src] to [channel] at [position]. */
    private suspend fun pump(
        src: InputStream,
        channel: FileChannel,
        position: Long,
        expected: Long,
        board: ChunkBoard?,
        chunkIndex: Int,
        emitter: ProgressEmitter?
    ): Long {
        val buf = ByteArray(64 * 1024)
        var pos = position
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val want = if (expected > 0) minOf(buf.size.toLong(), expected - total).toInt() else buf.size
            if (want <= 0) break
            val n = src.read(buf, 0, want)
            if (n < 0) break
            var off = 0
            while (off < n) {
                val w = channel.write(ByteBuffer.wrap(buf, off, n - off), pos)
                pos += w
                off += w
            }
            total += n
            board?.add(chunkIndex, n.toLong())
            emitter?.maybe()
        }
        return total
    }

    private fun rangedRequest(task: DownloadTaskEntity, range: String?, etag: String?): Request {
        val b = Request.Builder().url(task.url)
        for ((k, v) in DownloadRequestBuilder.decodeHeaders(task.headersEnc)) {
            b.header(k, v)
        }
        if (range != null) b.header("Range", range)
        if (range != null && etag != null) b.header("If-Range", etag)
        return b.build()
    }

    internal companion object {
        internal const val TAG = "[VDL][ENGINE][transfer]"
    }
}
