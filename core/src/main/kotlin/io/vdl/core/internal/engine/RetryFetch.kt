package io.vdl.core.internal.engine

import io.vdl.core.RetryPolicy
import io.vdl.core.internal.logging.VdlLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Typed outcome of one HTTP fetch with retry policy applied.
 * No string parsing downstream; callers map to their own outcomes.
 */
internal sealed interface FetchResult {
    data class Text(val body: String) : FetchResult
    data class Bytes(val body: ByteArray) : FetchResult
    data class Fatal(val reason: String, val httpCode: Int?) : FetchResult
    data class RetryableExhausted(val reason: String) : FetchResult
}

/**
 * Shared fetch-with-retry transport used by every media downloader
 * (HLS, DASH). Success = 200, or 206 when a byte range was requested;
 * 429/5xx consume attempts with backoff (Retry-After seeds the delay);
 * everything else is fatal for the caller to classify.
 */
internal class RetryFetch internal constructor(
    private val client: okhttp3.OkHttpClient,
    private val log: VdlLog,
    private val tag: String,
    private val sleeper: suspend (Long) -> Unit = { delay(it) }
) {

    internal suspend fun text(url: String, policy: RetryPolicy, what: String): FetchResult =
        fetch(url, policy, what, null, null) { it.body.byteStream().readBytes().decodeToString() }

    internal suspend fun bytes(
        url: String,
        policy: RetryPolicy,
        what: String,
        offset: Long? = null,
        length: Long? = null
    ): FetchResult = fetch(url, policy, what, offset, length) { it.body.byteStream().readBytes() }

    private suspend fun fetch(
        url: String,
        policy: RetryPolicy,
        what: String,
        offset: Long?,
        length: Long?,
        extract: (okhttp3.Response) -> Any
    ): FetchResult {
        var attempt = 0
        var lastReason = "unknown"
        var retryAfterMs: Long? = null
        while (true) {
            val req = okhttp3.Request.Builder().url(url).apply {
                if (offset != null || length != null) {
                    val to = if (length != null) (offset ?: 0L) + length - 1 else null
                    header("Range", "bytes=${offset ?: 0L}-${to ?: ""}")
                }
            }.build()
            try {
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 200 || (resp.code == 206 && offset != null) -> {
                            val v = extract(resp)
                            return if (v is String) FetchResult.Text(v) else FetchResult.Bytes(v as ByteArray)
                        }
                        resp.code == 429 || resp.code in 500..599 -> {
                            retryAfterMs = Backoff.retryAfterMs(resp.header("Retry-After"))
                            lastReason = "http ${resp.code}"
                            log.w(tag) { "fetch throttle what=$what attempt=$attempt code=${resp.code} url=$url retryAfter=${retryAfterMs ?: "-"}" }
                        }
                        else -> {
                            log.e(tag) { "fetch fatal what=$what code=${resp.code} url=$url decision=fatal" }
                            return FetchResult.Fatal("http ${resp.code} fetching $what", resp.code)
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (io: IOException) {
                // Transport failure (reset/EOF/timeout mid-body): retryable within
                // the same budget as 429/5xx. Never let it crash the whole engine -
                // that bypasses RetryPolicy and kills the task at queue level.
                retryAfterMs = null
                lastReason = "io ${io.javaClass.simpleName}"
                log.w(tag) {
                    "fetch io what=$what attempt=$attempt err=${io.javaClass.simpleName}: ${io.message ?: "-"} url=$url decision=retry"
                }
            }
            attempt++
            if (attempt >= policy.maxAttempts) {
                log.e(tag) { "fetch giveup what=$what attempts=$attempt reason=$lastReason url=$url" }
                return FetchResult.RetryableExhausted("$what attempts=$attempt: $lastReason")
            }
            sleeper(Backoff.delayMs(policy, attempt, retryAfterMs))
        }
    }
}
