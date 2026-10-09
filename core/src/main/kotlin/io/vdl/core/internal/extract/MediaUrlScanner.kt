package io.vdl.core.internal.extract

import io.vdl.core.SourceKind
import io.vdl.core.internal.logging.VdlLog
import java.net.URI

/**
 * Pure HTML media-URL scanner. No IO, no JS: finds direct media URLs in
 * markup and JSON strings, de-escapes them, resolves relative paths and
 * ranks candidates: HLS master > DASH > DIRECT, longer (likely higher
 * bitrate) first inside a kind.
 *
 * Untrusted page content is DATA: patterns are bounded and results are
 * validated before leaving this class (scheme check in [isHttp]).
 */
internal class MediaUrlScanner internal constructor(private val log: VdlLog) {

    internal data class Hit(val url: String, val kind: SourceKind)

    // bounded: no dot-all, explicit char class, media extension anchored
    // at the END of the candidate (greedy + terminator lookahead): a
    // non-greedy variant truncated at the first ".mp4" inside a hostname
    // (real-world: "www.mp4upload.com/embed-x.html" -> "https://www.mp4").
    private val plainMedia = Regex(
        """https?://[^\s"'<>\\)]+\.(?:m3u8|mpd|mp4|webm|ts)(?:\?[^\s"'<>\\)]*)?(?![\w.])""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val attrSrc = Regex(
        """(?:src|data-src|content|source|file)\s*[:=]\s*["']([^"']{8,2048})["']""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val jsonUrl = Regex(
        """"(?:url|src|file|source|stream)"\s*:\s*"((?:[^"\\]|\\.){8,2048})""""
    )

    internal fun scan(html: String, baseUrl: String): List<Hit> {
        val de = deescape(html)
        val found = LinkedHashMap<String, SourceKind>()
        var plain = 0
        var attr = 0
        var json = 0

        plainMedia.findAll(de).forEach { m ->
            add(found, m.value, baseUrl); plain++
        }
        attrSrc.findAll(de).forEach { m ->
            if (UrlKind.fromUrl(m.groupValues[1]) != null) {
                add(found, m.groupValues[1], baseUrl); attr++
            }
        }
        jsonUrl.findAll(de).forEach { m ->
            val u = deescape(m.groupValues[1])
            if (UrlKind.fromUrl(u) != null) {
                add(found, u, baseUrl); json++
            }
        }
        val hits = found.entries.map { Hit(it.key, it.value) }.sortedWith(
            compareByDescending<Hit> { rankKind(it.kind) }.thenByDescending { it.url.length }
        )
        log.i(TAG) {
            "scan plain=$plain attr=$attr json=$json unique=${hits.size} " +
                "kinds=${hits.groupBy { it.kind }.mapValues { it.value.size }}"
        }
        return hits
    }

    private fun add(found: MutableMap<String, SourceKind>, raw: String, baseUrl: String) {
        val url = resolveAgainst(raw.trim(), baseUrl) ?: return
        if (!isHttp(url)) return
        val kind = UrlKind.fromUrl(url) ?: return
        found.putIfAbsent(url, kind)
    }

    /** De-escape common JS/HTML encodings into a plain working copy. */
    private fun deescape(s: String): String = s
        .replace("\\u0026", "&")
        .replace("\\u002F", "/")
        .replace("\\u002f", "/")
        .replace("\\/", "/")
        .replace("&amp;", "&")

    private fun resolveAgainst(raw: String, baseUrl: String): String? {
        if (raw.startsWith("//")) return "https:$raw"
        if (isHttp(raw)) return raw
        // relative (also protocol-agnostic) — resolve against the page URL
        return runCatching { URI(baseUrl).resolve(raw).toString() }
            .getOrNull()?.takeIf { isHttp(it) }
    }

    private fun isHttp(u: String) = u.startsWith("http://") || u.startsWith("https://")

    private fun rankKind(kind: SourceKind): Int = when (kind) {
        SourceKind.HLS -> 3
        SourceKind.DASH -> 2
        SourceKind.DIRECT -> 1
    }

    internal companion object {
        internal const val TAG = "[VDL][EXTRACT][SCAN]"
    }
}
