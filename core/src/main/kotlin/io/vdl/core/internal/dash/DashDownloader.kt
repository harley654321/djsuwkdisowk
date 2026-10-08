package io.vdl.core.internal.dash

import io.vdl.core.Progress
import io.vdl.core.RetryPolicy
import io.vdl.core.internal.engine.FetchResult
import io.vdl.core.internal.engine.RetryFetch
import io.vdl.core.internal.engine.UnitLedger
import io.vdl.core.internal.logging.VdlLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.RandomAccessFile

/** Typed orchestrator outcome; no string parsing downstream. */
internal sealed interface DashOutcome {
    data class Success(
        val bytesWritten: Long,
        val units: Int,
        val durationSec: Double,
        val variant: String,
        val audioBytesWritten: Long = 0L,
        val audioUnits: Int = 0,
        val hasSeparateAudio: Boolean = false
    ) : DashOutcome
    data class Fatal(val reason: String) : DashOutcome
    data class Retryable(val reason: String) : DashOutcome
}

/**
 * DASH download orchestrator: MPD fetch -> selection -> segment plan
 * -> sequential unit download (retry per unit, shared RetryFetch) ->
 * ordered concat into [out]. Clear content only: DRM (ContentProtection)
 * is refused with a typed Fatal, never silently attempted.
 *
 * When the MPD carries a separate audio AdaptationSet and [audioOut] is
 * provided, the audio track downloads to its own file after video.
 */
internal class DashDownloader internal constructor(
    private val client: OkHttpClient,
    private val log: VdlLog,
    private val parser: DashParser = DashParser(log),
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val clockNanos: () -> Long = { System.nanoTime() }
) {

    internal suspend fun download(
        mpdUrl: String,
        out: File,
        maxHeight: Int? = null,
        policy: RetryPolicy,
        audioOut: File? = null,
        resume: Boolean = false,
        onProgress: (Progress) -> Unit
    ): DashOutcome = withContext(Dispatchers.IO) {
        val t0 = clockNanos()

        val mpdText = when (val r = RetryFetch(client, log, TAG, sleeper).text(mpdUrl, policy, "mpd")) {
            is FetchResult.Text -> r.body
            is FetchResult.Fatal -> return@withContext DashOutcome.Fatal(r.reason)
            is FetchResult.RetryableExhausted -> return@withContext DashOutcome.Retryable(r.reason)
            is FetchResult.Bytes -> return@withContext DashOutcome.Fatal("unexpected bytes for mpd")
        }

        val mpd = try {
            parser.parse(mpdText)
        } catch (t: Throwable) {
            log.e(TAG, t) { "mpd parse failed url=$mpdUrl decision=fatal" }
            return@withContext DashOutcome.Fatal("invalid MPD: ${t.message ?: t.javaClass.simpleName}")
        }
        if (mpd.protected) {
            log.e(TAG) { "DRM-protected MPD url=$mpdUrl decision=fatal" }
            return@withContext DashOutcome.Fatal("DRM-protected content is not supported")
        }

        val sel = try {
            DashSelector.select(mpd, maxHeight, log)
        } catch (t: Throwable) {
            log.e(TAG, t) { "selection failed url=$mpdUrl decision=fatal" }
            return@withContext DashOutcome.Fatal("MPD selection failed: ${t.message ?: t.javaClass.simpleName}")
        }

        val period = mpd.periods.first()
        val video = when (val v = runTrack(mpdUrl, mpd, period, sel.video, out, policy, "video", t0, resume, onProgress)) {
            is Track -> v
            else -> return@withContext v as DashOutcome
        }

        var audio: Track? = null
        if (sel.audio != null && audioOut != null) {
            audio = when (val a = runTrack(mpdUrl, mpd, period, sel.audio, audioOut, policy, "audio", t0, resume, onProgress)) {
                is Track -> a
                else -> return@withContext a as DashOutcome
            }
        } else if (sel.audio != null && audioOut == null) {
            log.w(TAG) { "audio AdaptationSet present but no audioOut decision=skip audio=${sel.audioLang ?: "?"}" }
        }

        val variant = "${sel.video.height ?: "?"}p/${sel.video.bandwidth}bps"
        log.i(TAG) {
            "dash done units=${video.units}+${audio?.units ?: 0} bytes=${video.bytes}+${audio?.bytes ?: 0} dt=${elapsedMs(t0)}ms out=${out.name}"
        }
        DashOutcome.Success(
            bytesWritten = video.bytes,
            units = video.units,
            durationSec = mpd.presentationDurationSec ?: video.durationSec,
            variant = variant,
            audioBytesWritten = audio?.bytes ?: 0L,
            audioUnits = audio?.units ?: 0,
            hasSeparateAudio = audio != null
        )
    }

    // ------------------------------------------------------- track runner

    private class Track(val bytes: Long, val units: Int, val durationSec: Double)

    private suspend fun runTrack(
        mpdUrl: String,
        mpd: DashMpd,
        period: DashPeriod,
        rep: DashRepresentation,
        out: File,
        policy: RetryPolicy,
        kind: String,
        t0: Long,
        resume: Boolean,
        onProgress: (Progress) -> Unit
    ): Any {
        val units = try {
            DashPlan.build(mpd, period, rep, mpdUrl, log)
        } catch (t: Throwable) {
            log.e(TAG, t) { "plan failed kind=$kind rep=${rep.id} decision=fatal" }
            return DashOutcome.Fatal("plan failed: ${t.message ?: t.javaClass.simpleName}")
        }
        if (units.isEmpty()) return DashOutcome.Fatal("empty plan for ${rep.id}")

        val fetch = RetryFetch(client, log, TAG, sleeper)

        // Unit-level resume: skip units whose byte range is already on disk.
        val ledger = UnitLedger(UnitLedger.forOut(out), log, TAG)
        var done = if (resume) ledger.load(if (out.exists()) out.length() else 0L) else emptyList()
        // the ledger must be a prefix of the CURRENT plan (same indices, same order);
        // a different plan (new variant, edited MPD) means the offsets are
        // meaningless: discard and restart from zero.
        if (done.isNotEmpty() && done.map { it.index } != units.take(done.size).map { it.index }) {
            log.w(TAG) { "ledger plan mismatch kind=$kind done=${done.size} units=${units.size} decision=restart" }
            done = emptyList()
        }
        val doneIdx = ArrayDeque(done)
        if (done.isNotEmpty()) {
            log.i(TAG) { "resume kind=$kind unitsDone=${done.size} firstPending=${done.last().index + 1} out=${out.name}" }
        }

        var written = 0L
        RandomAccessFile(out, "rw").use { sink ->
            if (done.isEmpty()) {
                sink.setLength(0L)
                ledger.clear()
            }
            val recorded = mutableListOf<UnitLedger.Entry>()
            for (unit in units) {
                if (!currentCoroutineContext().isActive) {
                    throw CancellationException("dash $kind download cancelled at unit ${unit.index}")
                }
                val already = doneIdx.firstOrNull()
                if (already != null && already.index == unit.index) {
                    // on disk and chained at the cursor: skip the fetch
                    sink.seek(already.offset + already.length)
                    written = already.offset + already.length
                    doneIdx.removeFirst()
                    log.d(TAG) { "unit skip kind=$kind idx=${unit.index} bytes=${already.length} total=$written dt=${elapsedMs(t0)}ms" }
                    continue
                }
                when (val r = fetch.bytes(unit.url, policy, "$kind-unit")) {
                    is FetchResult.Bytes -> {
                        sink.seek(written)
                        sink.write(r.body)
                        val offset = written
                        written += r.body.size
                        recorded.add(UnitLedger.Entry(unit.index, offset, r.body.size.toLong()))
                        ledger.save(recorded)
                        log.d(TAG) { "unit done kind=$kind idx=${unit.index} bytes=${r.body.size} total=$written dt=${elapsedMs(t0)}ms" }
                        onProgress(
                            Progress(
                                bytesDownloaded = written,
                                bytesTotal = units.size.coerceAtLeast(1).toLong(),
                                speedBps = 0L,
                                etaMs = 0L,
                                activeThreads = 1
                            )
                        )
                    }
                    is FetchResult.Fatal -> return DashOutcome.Fatal(r.reason)
                    is FetchResult.RetryableExhausted -> return DashOutcome.Retryable(r.reason)
                    is FetchResult.Text -> return DashOutcome.Fatal("unexpected text for $kind-unit")
                }
            }
        }
        ledger.clear()
        log.i(TAG) { "track done kind=$kind rep=${rep.id} units=${units.size} bytes=$written dt=${elapsedMs(t0)}ms out=${out.name}" }
        return Track(written, units.size, 0.0)
    }

    private fun elapsedMs(t0: Long): Long = (clockNanos() - t0) / 1_000_000L

    private companion object {
        internal const val TAG = "[VDL][DASH][downloader]"
    }
}
