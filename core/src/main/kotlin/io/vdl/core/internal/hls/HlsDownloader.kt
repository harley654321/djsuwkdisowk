package io.vdl.core.internal.hls

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
internal sealed interface HlsOutcome {
    data class Success(
        val bytesWritten: Long,
        val units: Int,
        val durationSec: Double,
        val variant: String,
        val audioBytesWritten: Long = 0L,
        val audioUnits: Int = 0,
        val hasSeparateAudio: Boolean = false
    ) : HlsOutcome
    data class Fatal(val reason: String, val httpCode: Int? = null) : HlsOutcome
    data class Retryable(val reason: String) : HlsOutcome
}

/**
 * HLS download orchestrator: playlist -> variant selection -> segment plan
 * -> sequential unit download (retry per unit) -> AES-128 decrypt ->
 * ordered concat (fMP4: init + segments; TS: segments) into [out].
 *
 * When the master selects a separate AUDIO rendition and [audioOut] is
 * provided, the audio track is downloaded to its own file after the video
 * track (sequential by design: video is the big payload; both tracks are
 * independently playable fMP4/TS files). Muxing both into a single MP4 is
 * a later, atom-level stage; no transcode ever happens.
 */
internal class HlsDownloader internal constructor(
    private val client: OkHttpClient,
    private val log: VdlLog,
    private val parser: HlsParser = HlsParser(),
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val clockNanos: () -> Long = { System.nanoTime() }
) {

    internal suspend fun download(
        playlistUrl: String,
        out: File,
        maxHeight: Int? = null,
        policy: RetryPolicy,
        audioOut: File? = null,
        resume: Boolean = false,
        onProgress: (Progress) -> Unit
    ): HlsOutcome = withContext(Dispatchers.IO) {
        val t0 = clockNanos()

        // 1. resolve master -> video media playlist (+ optional audio rendition)
        var audioUrl: String? = null
        var audioLabel: String? = null
        val (mediaUrl, variantLabel) = when (val masterText = fetchText(playlistUrl, policy, "master")) {
            is String ->
                when (val p = parser.parse(masterText, playlistUrl)) {
                    is MasterPlaylist -> {
                        val sel = HlsVariantSelector.select(p, maxHeight, log)
                        sel.audio?.let {
                            audioUrl = it.uri
                            audioLabel = it.name
                        }
                        sel.video.uri to "${sel.video.resolutionHeight ?: "?"}p/${sel.video.averageBandwidth ?: sel.video.bandwidth}bps"
                    }
                    is MediaPlaylist -> playlistUrl to "direct-media"
                }
            else -> return@withContext masterText as HlsOutcome
        }
        log.i(TAG) { "variant resolved url=$mediaUrl variant=$variantLabel audio=${audioLabel ?: "muxed"} src=$playlistUrl" }

        // 2. video track
        val video = when (val v = runMedia(mediaUrl, out, policy, "video", t0, resume, onProgress)) {
            is Track -> v
            else -> return@withContext v as HlsOutcome
        }

        // 3. audio track (separate rendition), after video completes
        var audio: Track? = null
        if (audioUrl != null && audioOut != null) {
            audio = when (val a = runMedia(audioUrl!!, audioOut, policy, "audio", t0, resume, onProgress)) {
                is Track -> a
                else -> return@withContext a as HlsOutcome
            }
        } else if (audioUrl != null && audioOut == null) {
            log.w(TAG) { "audio rendition present but no audioOut decision=skip audio=$audioLabel" }
        }

        log.i(TAG) {
            "hls done units=${video.units}+${audio?.units ?: 0} bytes=${video.bytes}+${audio?.bytes ?: 0} dt=${elapsedMs(t0)}ms out=${out.name} audio=${audioOut?.name ?: "-"}"
        }
        HlsOutcome.Success(
            bytesWritten = video.bytes,
            units = video.units,
            durationSec = video.durationSec,
            variant = variantLabel,
            audioBytesWritten = audio?.bytes ?: 0L,
            audioUnits = audio?.units ?: 0,
            hasSeparateAudio = audio != null
        )
    }

    // ------------------------------------------------------- track runner

    private class Track(val bytes: Long, val units: Int, val durationSec: Double)

    /** Fetch one media playlist and stream its units into [out]. */
    private suspend fun runMedia(
        mediaUrl: String,
        out: File,
        policy: RetryPolicy,
        kind: String,
        t0: Long,
        resume: Boolean,
        onProgress: (Progress) -> Unit
    ): Any {
        val mediaText = when (val t = fetchText(mediaUrl, policy, "$kind-media")) {
            is String -> t
            else -> return t as HlsOutcome
        }
        val media = parser.parse(mediaText, mediaUrl) as MediaPlaylist
        if (media.isLive) {
            log.w(TAG) { "live playlist detected kind=$kind url=$mediaUrl decision=download-current-window" }
        }
        val units = HlsPlan.build(media)
        log.i(TAG) { "plan built kind=$kind units=${units.size} duration=${media.totalDurationSec}s key=${units.firstOrNull()?.keyMethod ?: "none"}" }

        // keys once per URL
        val keys = HashMap<String, ByteArray>()
        for (unit in units) {
            val keyUrl = unit.keyUrl ?: continue
            if (!keys.containsKey(keyUrl)) {
                when (val k = fetchBytes(keyUrl, policy, "key")) {
                    is ByteArray -> {
                        if (k.size != 16) {
                            log.e(TAG) { "key size != 16 url=$keyUrl size=${k.size} decision=fatal" }
                            return HlsOutcome.Fatal("AES key must be 16 bytes, got ${k.size}")
                        }
                        keys[keyUrl] = k
                    }
                    else -> return k as HlsOutcome
                }
            }
        }

        // Unit-level resume: skip units whose byte range is already on disk.
        val ledger = UnitLedger(UnitLedger.forOut(out), log, TAG)
        var done = if (resume) ledger.load(if (out.exists()) out.length() else 0L) else emptyList()
        // the ledger must be a prefix of the CURRENT plan (same indices, same order);
        // a different plan (new variant, edited playlist) means the offsets are
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
                if (!currentCoroutineContext().isActive) throw CancellationException("hls $kind download cancelled at unit ${unit.index}")
                val already = doneIdx.firstOrNull()
                if (already != null && already.index == unit.index) {
                    // on disk and chained at the cursor: skip the fetch
                    sink.seek(already.offset + already.length)
                    written = already.offset + already.length
                    doneIdx.removeFirst()
                    log.d(TAG) { "unit skip kind=$kind idx=${unit.index} bytes=${already.length} total=$written dt=${elapsedMs(t0)}ms" }
                    continue
                }
                when (val bytes = fetchBytes(unit.url, policy, "unit", unit.byterangeOffset, unit.byterangeLength)) {
                    is ByteArray -> {
                        val plain = if (unit.keyMethod != null && unit.keyUrl != null) {
                            Aes128.decrypt(bytes, keys.getValue(unit.keyUrl), unit.iv)
                        } else bytes
                        sink.seek(written)
                        sink.write(plain)
                        val offset = written
                        written += plain.size
                        recorded.add(UnitLedger.Entry(unit.index, offset, plain.size.toLong()))
                        ledger.save(recorded)
                        log.d(TAG) { "unit done kind=$kind idx=${unit.index} bytes=${plain.size} total=$written dt=${elapsedMs(t0)}ms" }
                        onProgress(
                            Progress(
                                bytesDownloaded = written,
                                bytesTotal = units.size.coerceAtLeast(1).toLong(), // unit-count based
                                speedBps = 0L,
                                etaMs = 0L,
                                activeThreads = 1
                            )
                        )
                    }
                    else -> return bytes as HlsOutcome
                }
            }
        }
        ledger.clear()
        log.i(TAG) { "track done kind=$kind units=${units.size} bytes=$written duration=${media.totalDurationSec}s dt=${elapsedMs(t0)}ms out=${out.name}" }
        return Track(written, units.size, media.totalDurationSec)
    }

    // ------------------------------------------------------------- fetch

    /** Transport is the shared RetryFetch; failures map to typed outcomes. */
    private suspend fun fetchText(url: String, policy: RetryPolicy, what: String): Any =
        when (val r = RetryFetch(client, log, TAG, sleeper).text(url, policy, what)) {
            is FetchResult.Text -> r.body
            is FetchResult.Fatal -> HlsOutcome.Fatal(r.reason, r.httpCode)
            is FetchResult.RetryableExhausted -> HlsOutcome.Retryable(r.reason)
            is FetchResult.Bytes -> HlsOutcome.Fatal("unexpected bytes for text $what")
        }

    private suspend fun fetchBytes(url: String, policy: RetryPolicy, what: String, offset: Long? = null, length: Long? = null): Any =
        when (val r = RetryFetch(client, log, TAG, sleeper).bytes(url, policy, what, offset, length)) {
            is FetchResult.Bytes -> r.body
            is FetchResult.Fatal -> HlsOutcome.Fatal(r.reason, r.httpCode)
            is FetchResult.RetryableExhausted -> HlsOutcome.Retryable(r.reason)
            is FetchResult.Text -> HlsOutcome.Fatal("unexpected text for bytes $what")
        }

    private fun elapsedMs(t0: Long): Long = (clockNanos() - t0) / 1_000_000L

    private companion object {
        internal const val TAG = "[VDL][HLS][downloader]"
    }
}
