package io.vdl.core.internal.extract

import io.vdl.core.SourceKind
import io.vdl.core.internal.logging.VdlLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Single-shot page fetch with a hard read cap. NOT a retrying transport:
 * resolve() is an interactive on-demand operation; the download itself
 * retries later. Evidence log for every decision; durations monotonic.
 *
 * Classification happens BEFORE reading the body: media content types
 * never download page-sized payloads.
 */
internal class PageFetcher internal constructor(
    private val client: OkHttpClient,
    private val log: VdlLog
) {

    internal sealed interface Fetched {
        /** HTML (or other text) page body, truncated to [MAX_BYTES]. */
        data class Html(val body: String, val contentType: String?) : Fetched

        /** Server said the URL IS media; no body was read. */
        data class Media(val kind: SourceKind, val contentType: String?) : Fetched

        data class Failure(val reason: String, val httpCode: Int?) : Fetched
    }

    internal fun fetch(url: String): Fetched {
        val t0 = System.nanoTime()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val ct = response.header("Content-Type")
                val dtMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                when {
                    !response.isSuccessful -> {
                        log.w(TAG) { "page fetch fail url=$url code=${response.code} dt=${dtMs}ms" }
                        Fetched.Failure("HTTP ${response.code}", response.code)
                    }
                    UrlKind.fromContentType(ct) != null -> {
                        log.i(TAG) { "page fetch media url=$url ct=$ct dt=${dtMs}ms decision=direct" }
                        Fetched.Media(UrlKind.fromContentType(ct)!!, ct)
                    }
                    else -> {
                        // cap the read; a hostile page will not exhaust memory
                        val bytes = response.body?.byteStream()?.let { src ->
                            val cap = ByteArray(MAX_BYTES)
                            var total = 0
                            while (total < MAX_BYTES) {
                                val n = src.read(cap, total, MAX_BYTES - total)
                                if (n < 0) break
                                total += n
                            }
                            cap.copyOf(total)
                        } ?: ByteArray(0)
                        log.i(TAG) { "page fetch text url=$url ct=$ct bytes=${bytes.size} dt=${dtMs}ms" }
                        Fetched.Html(String(bytes, Charsets.UTF_8), ct)
                    }
                }
            }
        } catch (t: Throwable) {
            val dtMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
            log.e(TAG, t) { "page fetch error url=$url dt=${dtMs}ms decision=fatal" }
            Fetched.Failure(t.message ?: t.javaClass.simpleName, null)
        }
    }

    internal companion object {
        internal const val TAG = "[VDL][EXTRACT][PAGE]"
        private const val MAX_BYTES = 4 * 1024 * 1024
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
