package io.vdl.core.internal.hls

import io.vdl.core.Progress
import io.vdl.core.internal.db.DownloadTaskEntity
import io.vdl.core.internal.engine.ChunkProgress
import io.vdl.core.internal.engine.DownloadEngine
import io.vdl.core.internal.engine.EngineOutcome
import io.vdl.core.internal.engine.EngineState
import io.vdl.core.internal.logging.VdlLog
import io.vdl.core.internal.mp4.Fmp4Muxer
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import java.io.File
import java.io.RandomAccessFile

/**
 * Queue seam for HLS tasks (entity.kind == HLS): playlist -> variant ->
 * segments (AES-128 aware) -> atom-level mux into ONE part file that the
 * standard queue publisher then stores like any DIRECT task. No transcode.
 *
 * Scratch files live beside the part file as "<id>.audio"/"<id>.muxed"
 * and are removed by the engine (or by PartFileFactory.cleanup on
 * cancel/publish-failure, which must delete every "<id>.*").
 *
 * Resume semantics: HLS runs are unit-sequential but stateless across
 * runs, so a paused-and-resumed task restarts from unit 0. Segment-level
 * resume is a later stage; this is logged, never hidden.
 *
 * TS + separate audio rendition: MPEG-TS cannot be atom-muxed by Fmp4Muxer
 * (no ftyp/moov). Decision: publish the VIDEO track and delete the audio
 * scratch, with a WARN carrying both sizes - a playable single file beats
 * a dead task, and nothing is published silently.
 */
internal class HlsQueueEngine internal constructor(
    private val client: OkHttpClient,
    private val log: VdlLog,
    private val clockNanos: () -> Long = { System.nanoTime() }
) : DownloadEngine {

    override suspend fun execute(
        task: DownloadTaskEntity,
        partFile: File,
        state: EngineState,
        onProgress: (Progress) -> Unit
    ): EngineOutcome {
        val t0 = clockNanos()
        val dir = partFile.parentFile ?: File(".")
        val audioFile = File(dir, "${task.id}.audio")
        val muxedFile = File(dir, "${task.id}.muxed")
        log.i(TAG) { "hls engine start task=${task.id} url=${task.url} maxHeight=${task.maxHeight ?: "best"}" }

        val outcome = HlsDownloader(client, log).download(
            playlistUrl = task.url,
            out = partFile,
            maxHeight = task.maxHeight,
            policy = task.retryPolicy(),
            audioOut = audioFile,
            onProgress = onProgress
        )

        return when (outcome) {
            is HlsOutcome.Fatal -> {
                audioFile.delete()
                log.e(TAG) { "hls engine fatal task=${task.id} reason=${outcome.reason} code=${outcome.httpCode ?: "-"} dt=${elapsedMs(t0)}ms" }
                EngineOutcome.Fatal(outcome.reason, outcome.httpCode)
            }
            is HlsOutcome.Retryable -> {
                audioFile.delete()
                log.e(TAG) { "hls engine retryable-exhausted task=${task.id} reason=${outcome.reason} dt=${elapsedMs(t0)}ms" }
                EngineOutcome.Retryable(outcome.reason)
            }
            is HlsOutcome.Success -> finish(task, partFile, audioFile, muxedFile, outcome, state, t0)
        }
    }

    private fun finish(
        task: DownloadTaskEntity,
        partFile: File,
        audioFile: File,
        muxedFile: File,
        outcome: HlsOutcome.Success,
        state: EngineState,
        t0: Long
    ): EngineOutcome {
        var bytes = outcome.bytesWritten
        val fmp4 = isFmp4(partFile)

        if (outcome.hasSeparateAudio && fmp4) {
            // atom-level merge of both tracks into one playable MP4
            try {
                val r = Fmp4Muxer.muxFiles(partFile, audioFile, muxedFile, log, clockNanos)
                if (!partFile.delete() || !muxedFile.renameTo(partFile)) {
                    muxedFile.delete()
                    log.e(TAG) { "mux swap failed task=${task.id} decision=fatal" }
                    return EngineOutcome.Fatal("mux swap failed for ${partFile.name}")
                }
                bytes = partFile.length()
                log.i(TAG) {
                    "mux done task=${task.id} bytes=$bytes videoFrags=${r.videoFragments} audioFrags=${r.audioFragments} tracks=${r.videoTrackId}+${r.audioTrackId ?: "-"} dt=${elapsedMs(t0)}ms"
                }
            } catch (ce: CancellationException) {
                muxedFile.delete()
                throw ce
            } catch (t: Throwable) {
                muxedFile.delete()
                log.e(TAG, t) { "mux failed task=${task.id} decision=fatal audioKept=${audioFile.exists()}" }
                return EngineOutcome.Fatal("mux failed: ${t.message ?: t.javaClass.simpleName}")
            }
        } else if (outcome.hasSeparateAudio) {
            log.w(TAG) {
                "ts+audio cannot atom-mux task=${task.id} decision=video-only videoBytes=${outcome.bytesWritten} audioBytes=${outcome.audioBytesWritten} audioScratchDeleted=${audioFile.delete()}"
            }
        }
        audioFile.delete()

        // One synthetic chunk covering the final file keeps the queue's
        // completeness check and COMPLETED bookkeeping exact.
        state.bytesTotal = bytes
        state.chunks = listOf(ChunkProgress(start = 0L, end = bytes - 1L, downloaded = bytes))
        state.contentType = if (fmp4) "video/mp4" else "video/mp2t"
        log.i(TAG) { "hls engine success task=${task.id} bytes=$bytes contentType=${state.contentType} dt=${elapsedMs(t0)}ms" }
        return EngineOutcome.Success(bytes)
    }

    /** fMP4 has "ftyp" at offset 4; MPEG-TS starts with sync byte 0x47. */
    private fun isFmp4(f: File): Boolean {
        return runCatching {
            RandomAccessFile(f, "r").use { raf ->
                val h = ByteArray(8)
                raf.readFully(h)
                h.size >= 8 && h[4] == 'f'.code.toByte() && h[5] == 't'.code.toByte() &&
                    h[6] == 'y'.code.toByte() && h[7] == 'p'.code.toByte()
            }
        }.getOrDefault(false)
    }

    private fun elapsedMs(t0: Long): Long = (clockNanos() - t0) / 1_000_000L

    private companion object {
        internal const val TAG = "[VDL][HLS][queue-engine]"
    }
}
