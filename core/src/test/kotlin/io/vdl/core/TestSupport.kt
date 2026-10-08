package io.vdl.core

import io.vdl.core.internal.db.DownloadTaskEntity
import io.vdl.core.internal.logging.VdlLog
import io.vdl.core.internal.db.TaskState
import io.vdl.core.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
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

internal fun testLog(sink: PrintSink = PrintSink()): VdlLog = VdlLog(sink, debug = true)

internal suspend fun awaitTrue(timeoutMs: Long = 10_000, cond: () -> Boolean) {
    withTimeout(timeoutMs) {
        while (!cond()) delay(10)
    }
}

internal fun randomBytes(size: Int, seed: Int = 42): ByteArray =
    Random(seed).nextBytes(size)

internal fun makeEntity(
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
    id = "task-" + kotlin.math.abs((fileName + url).hashCode()),
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
) : mockwebserver3.Dispatcher() {

    val httpCalls = AtomicInteger(0)
    val rangeRequests = AtomicInteger(0)

    override fun dispatch(request: RecordedRequest): MockResponse {
        httpCalls.incrementAndGet()
        val range = request.headers["Range"]
        val ifRange = request.headers["If-Range"]

        if (request.method == "HEAD") {
            return MockResponse.Builder()
                .code(200)
                .addHeader("Accept-Ranges", if (supportRanges) "bytes" else "none")
                .addHeader("ETag", etag)
                .addHeader("Content-Length", data.size.toString())
                .build()
        }

        if (throttles > 0) {
            throttles--
            return MockResponse.Builder().code(429).addHeader("Retry-After", "0").build()
        }

        if (range == null || !supportRanges) {
            return MockResponse.Builder()
                .code(200)
                .addHeader("Accept-Ranges", if (supportRanges) "bytes" else "none")
                .addHeader("ETag", etag)
                .body(Buffer().write(data))
                .build()
        }

        // If-Range mismatch -> 200 with the full new body (RFC 7233)
        if (ifRange != null && ifRange != etag) {
            return MockResponse.Builder()
                .code(200)
                .addHeader("ETag", etag)
                .body(Buffer().write(data))
                .build()
        }

        rangeRequests.incrementAndGet()
        if (failFirstRange > 0) {
            failFirstRange--
            return MockResponse.Builder().code(200).addHeader("ETag", etag)
                .body(Buffer().write(data)).build()
        }
        if (disconnects > 0) {
            disconnects--
            return MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
        }

        val parsed = parseRange(range) ?: return MockResponse.Builder().code(416).build()
        val (start, end) = parsed
        if (start >= data.size || end >= data.size) {
            return MockResponse.Builder().code(416).build()
        }
        val slice = data.copyOfRange(start.toInt(), end.toInt() + 1)
        return MockResponse.Builder()
            .code(206)
            .addHeader("Content-Range", "bytes $start-$end/${data.size}")
            .addHeader("ETag", etag)
            .body(Buffer().write(slice))
            .build()
    }

    private fun parseRange(value: String): Pair<Long, Long>? {
        val m = Regex("^bytes=(\\d+)-(\\d+)$").find(value.trim()) ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        val end = m.groupValues[2].toLongOrNull() ?: return null
        return start to end
    }
}


/**
 * Body of unknown length: MockWebServer streams it with Transfer-Encoding: chunked,
 * forcing the engine to read to EOF (unknown total length scenario).
 */
internal class EofBody internal constructor(
    private val data: ByteArray
) : mockwebserver3.MockResponseBody {
    override fun writeTo(sink: okio.BufferedSink) {
        // Closing the sink emits the terminal chunk (chunked framing) /
        // half-closes the stream so the client can see EOF.
        sink.write(okio.Buffer().write(data), data.size.toLong())
        sink.close()
    }

    override val contentLength: Long get() = -1L
}
