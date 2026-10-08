package io.vdl.core.internal.hls

import io.vdl.core.Progress
import io.vdl.core.RetryPolicy
import io.vdl.core.internal.engine.Backoff
import io.vdl.core.internal.logging.VdlLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Typed orchestrator outcome; no string parsing downstream. */
internal sealed interface HlsOutcome {
    data class Success(
        val bytesWritten: Long,
        val units: Int,
        val durationSec: Double,
        val variant: String
    ) : HlsOutcome
    data class Fatal(val reason: String, val httpCode: Int? = null) : HlsOutcome
    data class Retryable(val reason: String) : HlsOutcome
}

/**
 * HLS download orchestrator: playlist -> variant selection -> segment plan
 * -> sequential unit download (retry per unit) -> AES-128 decrypt ->
 * ordered concat (fMP4: init + segments; TS: segments) into [out].
 *
 * Audio renditions are selected by [HlsVariantSelector] but downloaded as
 * a separate stream only in a later increment; muxing into one MP4 needs
 * a remux, which this library does without transcode at a later stage.
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
        onProgress: (Progress) -> Unit
    ): HlsOutcome = withContext(Dispatchers.IO) {
        val t0 = clockNanos()

        // 1. resolve master -> media playlist URL
        val (mediaUrl, variantLabel) = when (val masterText = fetchText(playlistUrl, policy, "master")) {
            is String ->
                when (val p = parser.parse(masterText, playlistUrl)) {
                    is MasterPlaylist -> {
                        val sel = HlsVariantSelector.select(p, maxHeight, log)
                        sel.video.uri to "${sel.video.resolutionHeight ?: "?"}p/${sel.video.averageBandwidth ?: sel.video.bandwidth}bps"
                    }
                    is MediaPlaylist -> playlistUrl to "direct-media"
                }
            else -> return@withContext masterText as HlsOutcome
        }
        log.i(TAG) { "variant resolved url=$mediaUrl variant=$variantLabel src=$playlistUrl" }

        // 2. media playlist -> ordered units
        val mediaText = when (val t = fetchText(mediaUrl, policy, "media")) {
            is String -> t
            else -> return@withContext t as HlsOutcome
        }
        val media = parser.parse(mediaText, mediaUrl) as MediaPlaylist
        if (media.isLive) {
            log.w(TAG) { "live playlist detected url=$mediaUrl decision=download-current-window" }
        }
        val units = HlsPlan.build(media)
        log.i(TAG) { "plan built units=${units.size} duration=${media.totalDurationSec}s key=${units.firstOrNull()?.keyMethod ?: "none"}" }

        // 3. fetch keys once per URL
        val keys = HashMap<String, ByteArray>()
        for (unit in units) {
            val keyUrl = unit.keyUrl ?: continue
            if (!keys.containsKey(keyUrl)) {
                when (val k = fetchBytes(keyUrl, policy, "key")) {
                    is ByteArray -> {
                        if (k.size != 16) {
                            log.e(TAG) { "key size != 16 url=$keyUrl size=${k.size} decision=fatal" }
                            return@withContext HlsOutcome.Fatal("AES key must be 16 bytes, got ${k.size}")
                        }
                        keys[keyUrl] = k
                    }
                    else -> return@withContext k as HlsOutcome
                }
            }
        }

        // 4. sequential download/decrypt/append with per-unit progress
        val totalBytes = units.size.coerceAtLeast(1).toLong()
        var written = 0L
        RandomAccessFile(out, "rw").use { sink ->
            sink.setLength(0L)
            for (unit in units) {
                if (!isActive) throw CancellationException("hls download cancelled at unit ${unit.index}")
                when (val bytes = fetchBytes(unit.url, policy, "unit", unit.byterangeOffset, unit.byterangeLength)) {
                    is ByteArray -> {
                        val plain = if (unit.keyMethod != null && unit.keyUrl != null) {
                            Aes128.decrypt(bytes, keys.getValue(unit.keyUrl), unit.iv)
                        } else bytes
                        sink.seek(written)
                        sink.write(plain)
                        written += plain.size
                        log.d(TAG) { "unit done idx=${unit.index} bytes=${plain.size} total=$written dt=${elapsedMs(t0)}ms" }
                        onProgress(
                            Progress(
                                bytesDownloaded = written,
                                bytesTotal = totalBytes, // unit-count based
                                speedBps = 0L,
                                etaMs = 0L,
                                activeThreads = 1
                            )
                        )
                    }
                    else -> return@withContext bytes as HlsOutcome
                }
            }
        }
        log.i(TAG) {
            "hls done units=${units.size} bytes=$written duration=${media.totalDurationSec}s dt=${elapsedMs(t0)}ms out=${out.name}"
        }
        HlsOutcome.Success(written, units.size, media.totalDurationSec, variantLabel)
    }

    // ------------------------------------------------------------- fetch

    private suspend fun fetchText(url: String, policy: RetryPolicy, what: String): Any =
        fetchWithRetry(url, policy, what, null, null) { it.body.byteStream().readBytes().decodeToString() }

    private suspend fun fetchBytes(url: String, policy: RetryPolicy, what: String, offset: Long? = null, length: Long? = null): Any =
        fetchWithRetry(url, policy, what, offset, length) { it.body.byteStream().readBytes() }

    /** Returns String/ByteArray on success, or the typed [HlsOutcome] failure. */
    private suspend fun fetchWithRetry(
        url: String,
        policy: RetryPolicy,
        what: String,
        offset: Long?,
        length: Long?,
        extract: (okhttp3.Response) -> Any
    ): Any {
        var attempt = 0
        var lastReason = "unknown"
        var retryAfterMs: Long? = null
        while (true) {
            val req = Request.Builder().url(url).apply {
                if (offset != null || length != null) {
                    val to = if (length != null) (offset ?: 0L) + length - 1 else null
                    header("Range", "bytes=${offset ?: 0L}-${to ?: ""}")
                }
            }.build()
            client.newCall(req).execute().use { resp ->
                when {
                    resp.code == 200 || (resp.code == 206 && offset != null) -> {
                        return extract(resp)
                    }
                    resp.code == 429 || resp.code in 500..599 -> {
                        retryAfterMs = Backoff.retryAfterMs(resp.header("Retry-After"))
                        lastReason = "http ${resp.code}"
                        log.w(TAG) { "fetch throttle what=$what attempt=$attempt code=${resp.code} url=$url retryAfter=${retryAfterMs ?: "-"}" }
                    }
                    else -> {
                        log.e(TAG) { "fetch fatal what=$what code=${resp.code} url=$url decision=fatal" }
                        return HlsOutcome.Fatal("http ${resp.code} fetching $what", resp.code)
                    }
                }
            }
            attempt++
            if (attempt >= policy.maxAttempts) {
                log.e(TAG) { "fetch giveup what=$what attempts=$attempt reason=$lastReason url=$url" }
                return HlsOutcome.Retryable("$what attempts=$attempt: $lastReason")
            }
            sleeper(Backoff.delayMs(policy, attempt, retryAfterMs))
        }
    }

    private fun elapsedMs(t0: Long): Long = (clockNanos() - t0) / 1_000_000L

    private companion object {
        internal const val TAG = "[VDL][HLS][downloader]"
    }
}
