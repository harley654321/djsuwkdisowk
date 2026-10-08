package io.vdl.core.internal.engine

import io.vdl.core.internal.db.DownloadTaskEntity
import io.vdl.core.internal.logging.VdlLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Remote probe: HEAD first, GET Range 0-0 as fallback.
 * Learns total size, range support, ETag and content type.
 */
internal class HttpProbe internal constructor(
    private val client: OkHttpClient,
    private val log: VdlLog
) {

    internal sealed interface ProbeResult {
        internal data class Info(
            internal val bytesTotal: Long,
            internal val acceptRanges: Boolean,
            internal val etag: String?,
            internal val contentType: String?
        ) : ProbeResult

        internal data class Failure(internal val outcome: EngineOutcome) : ProbeResult
    }

    internal fun probe(task: DownloadTaskEntity): ProbeResult {
        val head = runCall(task) { it.head() }
        when (head) {
            is ProbeResult.Info -> return head
            is ProbeResult.Failure ->
                log.i(TAG) { "probe HEAD failed (${head.outcome}), trying GET bytes=0-0 task=${task.id}" }
        }
        // HEAD may be 405/404 on some CDNs; a tiny ranged GET is the reliable probe.
        return runCall(task) { it.get().header("Range", "bytes=0-0") }
    }

    private fun runCall(
        task: DownloadTaskEntity,
        requestCustomizer: (Request.Builder) -> Unit
    ): ProbeResult {
        val request = newRequest(task, requestCustomizer)
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val retry = resp.code >= 500 || resp.code == 429
                    return ProbeResult.Failure(
                        if (retry) EngineOutcome.Retryable("probe http ${resp.code}", resp.code)
                        else EngineOutcome.Fatal("probe http ${resp.code}", resp.code)
                    )
                }
                val contentRange = resp.header("Content-Range")
                val ranged = resp.code == 206
                val total = if (ranged) {
                    parseTotalFromContentRange(contentRange)
                        ?: resp.header("Content-Length")?.toLongOrNull() ?: -1L
                } else {
                    resp.header("Content-Length")?.toLongOrNull() ?: -1L
                }
                ProbeResult.Info(
                    bytesTotal = total,
                    acceptRanges = ranged || equalsIgnoreCase(resp.header("Accept-Ranges"), "bytes"),
                    etag = resp.header("ETag"),
                    contentType = resp.header("Content-Type")
                )
            }
        } catch (e: java.io.IOException) {
            log.e(TAG, e) { "probe io fail task=${task.id} decision=retryable" }
            ProbeResult.Failure(EngineOutcome.Retryable("probe io: ${e.message}"))
        }
    }

    private fun newRequest(
        task: DownloadTaskEntity,
        customizer: (Request.Builder) -> Unit
    ): Request {
        val b = Request.Builder().url(task.url)
        for ((k, v) in io.vdl.core.DownloadRequestBuilder.decodeHeaders(task.headersEnc)) {
            b.header(k, v)
        }
        customizer(b)
        return b.build()
    }

    internal companion object {
        internal const val TAG = "[VDL][ENGINE][probe]"

        internal fun parseTotalFromContentRange(value: String?): Long? {
            // "bytes 0-0/12345" -> 12345 ; "bytes 0-0/*" -> null
            if (value == null) return null
            val afterSlash = value.substringAfter('/', "")
            return afterSlash.toLongOrNull()
        }

        private fun equalsIgnoreCase(a: String?, b: String): Boolean =
            a?.equals(b, ignoreCase = true) == true
    }
}
