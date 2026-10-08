package io.vdl.core

import io.vdl.core.internal.db.DownloadTaskEntity
import io.vdl.core.internal.logging.VdlLog
import io.vdl.core.internal.db.TaskState
import io.vdl.core.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Prints every structured log line; used by all JVM tests. */
class PrintSink : Logger {
    val lines = mutableListOf<String>()
    override fun log(level: Logger.Level, tag: String, message: String, error: Throwable?) {
        lines += "$level $tag $message" + (error?.let { " err=${it.message}" } ?: "")
    }
}

fun testLog(sink: PrintSink = PrintSink()): VdlLog = VdlLog(sink, debug = true)

suspend fun awaitTrue(timeoutMs: Long = 10_000, cond: () -> Boolean) {
    withTimeout(timeoutMs) {
        while (!cond()) delay(10)
    }
}

fun randomBytes(size: Int, seed: Int = 42): ByteArray =
    Random(seed).nextBytes(size)

fun makeEntity(
    url: String,
    fileName: String = "file.bin",
    threads: Int = 4,
    chunkSizeBytes: Long = 256L * 1024,
    bytesTotal: Long = 0L,
    acceptRanges: Boolean = false,
    etag: String? = null,
    chunksEnc: String = "",
    bytesDownloaded: Long = 0L,
    retryBaseDelayMs: Long = 10L,
    retryMaxAttempts: Int = 5,
    state: TaskState = TaskState.PENDING
): DownloadTaskEntity = DownloadTaskEntity(
    id = "$fileName-$url",
    url = url,
    fileName = fileName,
    destinationType = "APP_PRIVATE",
    destinationSubfolder = null,
    headersEnc = "",
    priority = "NORMAL",
    state = state.name,
    bytesTotal = bytesTotal,
    bytesDownloaded = bytesDownloaded,
    etag = etag,
    contentType = "application/octet-stream",
    acceptRanges = acceptRanges,
    chunksEnc = chunksEnc,
    attempt = 0,
    wifiOnly = false,
    showNotification = false,
    threads = threads,
    chunkSizeBytes = chunkSizeBytes,
    retryBaseDelayMs = retryBaseDelayMs,
    retryMaxAttempts = retryMaxAttempts,
    createdAt = System.currentTimeMillis(),
    updatedAt = System.currentTimeMillis(),
    lastError = null,
    resultUri = null,
    resultPath = null
)

/**
 * MockWebServer dispatcher that honors Range / If-Range semantics like a real
 * static file CDN: 206 partials, 200 full on etag change, 416 on bad range.
 */
class RangeDispatcher(
    val data: ByteArray,
    val supportRanges: Boolean = true,
    val etag: String = "\"v1\"",
    /** Set to make If-Range mismatches (simulates a changed resource). */
    var failFirstRange: Int = 0,
    /** Respond 429 this many times before serving. */
    var throttles: Int = 0,
    /** Disconnect this many range requests at socket level. */
    var disconnects: Int = 0
) : Dispatcher() {

    val httpCalls = AtomicInteger(0)
    val rangeRequests = AtomicInteger(0)

    override fun dispatch(request: RecordedRequest): MockResponse {
        httpCalls.incrementAndGet()
        val range = request.getHeader("Range")
        val ifRange = request.getHeader("If-Range")

        if (request.method == "HEAD") {
            return MockResponse()
                .setResponseCode(200)
                .setHeader("Accept-Ranges", if (supportRanges) "bytes" else "none")
                .setHeader("ETag", etag)
                .setHeader("Content-Length", data.size.toString())
        }

        if (throttles > 0) {
            throttles--
            return MockResponse().setResponseCode(429).setHeader("Retry-After", "0")
        }

        if (range == null || !supportRanges) {
            return MockResponse()
                .setResponseCode(200)
                .setHeader("Accept-Ranges", if (supportRanges) "bytes" else "none")
                .setHeader("ETag", etag)
                .setBody(Buffer().write(data))
        }

        // If-Range mismatch -> 200 with the full new body (RFC 7233)
        if (ifRange != null && ifRange != etag) {
            return MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", etag)
                .setBody(Buffer().write(data))
        }

        rangeRequests.incrementAndGet()
        if (failFirstRange > 0) {
            failFirstRange--
            return MockResponse().setResponseCode(200).setHeader("ETag", etag)
                .setBody(Buffer().write(data))
        }
        if (disconnects > 0) {
            disconnects--
            return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START)
        }

        val parsed = parseRange(range) ?: return MockResponse().setResponseCode(416)
        val (start, end) = parsed
        if (start >= data.size || end >= data.size) {
            return MockResponse().setResponseCode(416)
        }
        val slice = data.copyOfRange(start.toInt(), end.toInt() + 1)
        return MockResponse()
            .setResponseCode(206)
            .setHeader("Content-Range", "bytes $start-$end/${data.size}")
            .setHeader("ETag", etag)
            .setBody(Buffer().write(slice))
    }

    private fun parseRange(value: String): Pair<Long, Long>? {
        val m = Regex("^bytes=(\\d+)-(\\d+)$").find(value.trim()) ?: return null
        return m.groupValues[1].toLongOrNull() to m.groupValues[2].toLongOrNull()
    }
}
