package io.vdl.core.internal.extract

import io.vdl.core.SourceKind

/**
 * Classifies a URL or HTTP content-type as a media transport kind, or null
 * when it is not media at all. Pure string work, no IO.
 *
 * Extension sniffing: everything after the LAST dot up to a query/fragment.
 * Content types follow RFC 6381 / common practice.
 */
internal object UrlKind {

    private val EXT_HLS = setOf("m3u8", "m3u")
    private val EXT_DASH = setOf("mpd", "mpd.xml")
    private val EXT_DIRECT = setOf(
        "mp4", "m4v", "webm", "ts", "mkv", "avi", "mov", "flv", "3gp", "ogv", "m4a", "mp3"
    )

    internal fun fromUrl(url: String): SourceKind? {
        val noQuery = url.substringBefore('#').substringBefore('?')
        val ext = noQuery.substringAfterLast('.', "").lowercase()
        return when {
            ext in EXT_HLS -> SourceKind.HLS
            ext in EXT_DASH -> SourceKind.DASH
            ext in EXT_DIRECT -> SourceKind.DIRECT
            else -> null
        }
    }

    internal fun fromContentType(contentType: String?): SourceKind? {
        if (contentType.isNullOrEmpty()) return null
        val ct = contentType.substringBefore(';').trim().lowercase()
        return when {
            ct == "application/vnd.apple.mpegurl" || ct == "application/x-mpegurl" ||
                ct == "audio/mpegurl" || ct.endsWith(".m3u8") -> SourceKind.HLS
            ct == "application/dash+xml" -> SourceKind.DASH
            ct.startsWith("video/") || ct.startsWith("audio/") -> SourceKind.DIRECT
            else -> null
        }
    }

    /** Best-effort combined classification; explicit content-type wins. */
    internal fun of(url: String, contentType: String?): SourceKind? =
        fromContentType(contentType) ?: fromUrl(url)
}
