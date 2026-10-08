package io.vdl.core.internal.dash

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
 * Queue seam for DASH tasks (entity.kind == DASH): MPD -> selection ->
 * both tracks -> atom-level Fmp4Muxer merge into ONE part file that the
 * standard queue publisher stores like any DIRECT task. No transcode.
 *
 * Scratch files live beside the part as "<id>.audio"/"<id>.muxed" and are
 * removed here or by PartFileFactory.cleanup (which deletes "<id>.*").
 *
 * Resume semantics mirror HLS: unit-level via the UnitLedger sidecar
 * ("*.vdl-units") — completed units are skipped, mismatches discard.
 *
 * DASH segments are ISOBMFF (fMP4) by spec: after a successful mux the
 * contentType is video/mp4; TS AdaptationSets are refused with a typed
 * Fatal (mimeType video/mp2t) - never attempted.
 */
internal class DashQueueEngine internal constructor(
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
        log.i(TAG) { "dash engine start task=${task.id} url=${task.url} maxHeight=${task.maxHeight ?: "best"}" }

        val outcome = DashDownloader(client, log, clockNanos = clockNanos).download(
            mpdUrl = task.url,
            out = partFile,
            maxHeight = task.maxHeight,
            policy = task.retryPolicy(),
            audioOut = audioFile,
            resume = true, // unit ledger absent => clean start; present => skip done units
            onProgress = onProgress
        )

        return when (outcome) {
            is DashOutcome.Fatal -> {
                audioFile.delete()
                log.e(TAG) { "dash engine fatal task=${task.id} reason=${outcome.reason} dt=${elapsedMs(t0)}ms" }
                EngineOutcome.Fatal(outcome.reason)
            }
            is DashOutcome.Retryable -> {
                audioFile.delete()
                log.e(TAG) { "dash engine retryable-exhausted task=${task.id} reason=${outcome.reason} dt=${elapsedMs(t0)}ms" }
                EngineOutcome.Retryable(outcome.reason)
            }
            is DashOutcome.Success -> finish(task, partFile, audioFile, muxedFile, outcome, state, t0)
        }
    }

    private fun finish(
        task: DownloadTaskEntity,
        partFile: File,
        audioFile: File,
        muxedFile: File,
        outcome: DashOutcome.Success,
        state: EngineState,
        t0: Long
    ): EngineOutcome {
        var bytes = outcome.bytesWritten
        val fmp4 = isFmp4(partFile)

        if (!fmp4) {
            audioFile.delete()
            muxedFile.delete()
            log.e(TAG) { "non-ISOBMFF output task=${task.id} decision=fatal reason=ts-adaptation-set" }
            return EngineOutcome.Fatal("DASH output is not ISOBMFF (TS AdaptationSet)")
        }

        if (outcome.hasSeparateAudio) {
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
                log.e(TAG, t) { "mux failed task=${task.id} decision=fatal" }
                return EngineOutcome.Fatal("mux failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
        audioFile.delete()

        // One synthetic chunk covering the final file keeps the queue's
        // completeness check and COMPLETED bookkeeping exact.
        state.bytesTotal = bytes
        state.chunks = listOf(ChunkProgress(start = 0L, end = bytes - 1L, downloaded = bytes))
        state.contentType = "video/mp4"
        log.i(TAG) { "dash engine success task=${task.id} bytes=$bytes contentType=video/mp4 dt=${elapsedMs(t0)}ms" }
        return EngineOutcome.Success(bytes)
    }

    /** ISOBMFF has "ftyp" or "styp" at offset 4; DASH inits are ftyp. */
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
        internal const val TAG = "[VDL][DASH][queue-engine]"
    }
}
