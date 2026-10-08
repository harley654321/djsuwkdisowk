package io.vdl.core

import java.net.URLDecoder
import java.net.URLEncoder

/** Where the finished file ends up. */
public sealed interface Destination {
    /** Public Downloads folder. MediaStore + IS_PENDING on API 29+, legacy path below. */
    public data class PublicDownloads(public val subfolder: String? = null) : Destination

    /** App-private external files dir. No permissions, removed on uninstall. */
    public data class AppPrivate(public val subfolder: String? = null) : Destination

    /**
     * Gallery collections (Movies for video, Music for audio).
     * MediaStore.Video/Audio + IS_PENDING on API 29+ (atomic, gallery
     * apps only see the finished item), legacy public dir + media scan
     * below.
     */
    public data class Gallery(public val subfolder: String? = null) : Destination

    /**
     * A user-picked SAF tree (ACTION_OPEN_DOCUMENT_TREE). The app must
     * hold a persisted uri permission for [treeUri]. SAF writes are not
     * transactional: the document becomes visible when the stream
     * closes; a failure deletes the partial document.
     */
    public data class SafTree(public val treeUri: String) : Destination

    public companion object {
        internal fun fromStrings(type: String, subfolder: String?): Destination = when (type) {
            "PUBLIC_DOWNLOADS" -> PublicDownloads(subfolder)
            "GALLERY" -> Gallery(subfolder)
            // the subfolder column carries the tree uri for SAF
            "SAF" -> SafTree(subfolder ?: "")
            else -> AppPrivate(subfolder)
        }
    }
}

/** Retry behavior for a download. */
public sealed interface RetryPolicy {
    public val baseDelayMs: Long
    public val maxAttempts: Int

    /** Exponential backoff with jitter. attempt 0 -> baseDelay, doubling up to [maxDelayMs]. */
    public data class Exponential(
        override val baseDelayMs: Long = 1_000L,
        override val maxAttempts: Int = 5,
        public val maxDelayMs: Long = 30_000L
    ) : RetryPolicy

    public data object None : RetryPolicy {
        override val baseDelayMs: Long = 0L
        override val maxAttempts: Int = 1
    }

    public companion object {
        internal fun fromStrings(base: Long, attempts: Int): RetryPolicy =
            if (attempts <= 1) None else Exponential(baseDelayMs = base, maxAttempts = attempts)
    }
}

internal class DownloadRequest internal constructor(
    val url: String,
    val fileName: String,
    val destination: Destination,
    val priority: Priority,
    val wifiOnly: Boolean,
    val showNotification: Boolean,
    val threads: Int,
    val chunkSizeBytes: Long,
    val retryPolicy: RetryPolicy,
    val headers: Map<String, String>
)

/** DSL builder consumed by [VdlDownloader.download]. */
public class DownloadRequestBuilder internal constructor() {
    public var url: String = ""
    public var fileName: String? = null
    public var destination: Destination = Destination.AppPrivate()
    public var priority: Priority = Priority.NORMAL
    public var wifiOnly: Boolean = false
    public var showNotification: Boolean = true
    public var threads: Int = 4
    public var chunkSizeBytes: Long = 4L * 1024 * 1024
    public var retryPolicy: RetryPolicy = RetryPolicy.Exponential()
    public val headers: MutableMap<String, String> = LinkedHashMap()

    internal fun build(): DownloadRequest {
        val trimmed = url.trim()
        require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            "url must be http(s), got: $trimmed"
        }
        require(threads in 1..16) { "threads must be in 1..16, got $threads" }
        require(chunkSizeBytes >= 256L * 1024) { "chunkSizeBytes must be >= 256KB, got $chunkSizeBytes" }
        require(headers.keys.none { it.equals("Range", ignoreCase = true) }) {
            "Range header is managed by the engine and cannot be set manually"
        }
        return DownloadRequest(
            url = trimmed,
            fileName = fileName ?: fileNameFromUrl(trimmed),
            destination = destination,
            priority = priority,
            wifiOnly = wifiOnly,
            showNotification = showNotification,
            threads = threads,
            chunkSizeBytes = chunkSizeBytes,
            retryPolicy = retryPolicy,
            headers = headers.toMap()
        )
    }

    private fun fileNameFromUrl(u: String): String {
        val seg = u.substringBefore('?').substringAfterLast('/')
        return FileNameSanitizer.sanitize(seg)
    }

    internal companion object {
        internal fun encodeHeaders(headers: Map<String, String>): String =
            headers.entries.joinToString(";") { (k, v) ->
                "${urlEncode(k)}=${urlEncode(v)}"
            }

        internal fun decodeHeaders(raw: String?): Map<String, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            val map = LinkedHashMap<String, String>()
            for (pair in raw.split(';')) {
                val idx = pair.indexOf('=')
                if (idx <= 0) continue
                map[urlDecode(pair.take(idx))] = urlDecode(pair.substringAfter('='))
            }
            return map
        }

        private fun urlDecode(s: String): String =
            runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

        private fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}
