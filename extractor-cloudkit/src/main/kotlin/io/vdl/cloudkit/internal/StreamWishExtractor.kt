package io.vdl.cloudkit.internal

import io.vdl.cloudkit.CloudKitKind
import io.vdl.cloudkit.CloudKitResult

import java.io.IOException

/**
 * StreamWish extractor (streamwish rotating-domain family).
 *
 * Ported from recloudstream/cloudstream
 * `extractors/StreamWishExtractor.kt` (GPL-3.0,
 * https://github.com/recloudstream/cloudstream — commit master @ 2026-10).
 * Upstream mechanism:
 *
 *   1. /f/{id} and /e/{id} normalize to $base/{id};
 *   2. the player page carries a packed script OR a jwplayer("vplayer")
 *      setup with sources: [{file: "...m3u8"}];
 *   3. the m3u8 is fetched with Referer/Origin of the player host.
 *
 * Upstream falls back to a WebView interceptor when the page carries no
 * sources. Live evidence 2026-10-10: the whole family answers non-browser
 * clients with an 819B "Loading..." shell whose main.js runs a multi-layer
 * obfuscated challenge (string-array + queue + setInterval, environment
 * sensitive) — WebView-class, same bucket as Cloudflare hosts. This port
 * implements the JVM-reachable path and degrades to null on the shell
 * (never throws); the WebView fallback belongs to the Android app wiring.
 */
internal class StreamWishExtractor(private val http: CloudHttp) : CloudKitExtractor {

    override fun matches(url: String): Boolean = KNOWN_DOMAINS.any { url.contains(it) }

    /** Never throws; null on shell challenge, dead link or page change. */
    override suspend fun resolve(url: String): CloudKitResult? {
        val playerUrl = resolveEmbedUrl(url)
        val page = try {
            http.get(playerUrl)
        } catch (t: IOException) {
            return null
        }
        if (isShell(page.body)) return null // WebView-class challenge, typed degradation

        val script = scriptWithSources(page.body) ?: return null
        val source = JwPlayerParser.firstSource(script) ?: return null
        val host = DoodExtractor.baseUrlOf(page.finalUrl)
        return CloudKitResult(
            url = source,
            kind = if (source.contains(".m3u8") || source.contains("master.txt")) {
                CloudKitKind.HLS
            } else {
                CloudKitKind.DIRECT
            },
            referer = "$host/",
            extractor = "StreamWish",
        )
    }

    /** Upstream resolveEmbedUrl: /f/{id} and /e/{id} -> $base/{id}. */
    internal fun resolveEmbedUrl(url: String): String {
        val base = DoodExtractor.baseUrlOf(url)
        val id = when {
            url.contains("/f/") -> url.substringAfter("/f/")
            url.contains("/e/") -> url.substringAfter("/e/")
            else -> return url
        }
        return "$base/$id"
    }

    internal companion object {
        // Upstream StreamWishExtractor subclasses + dhcplay.com captured
        // in the user's real pages (2026-10-09/10).
        internal val KNOWN_DOMAINS: List<String> = listOf(
            "streamwish.to", "streamwish.site", "dhcplay.com", "mwish.pro", "dwish.pro",
            "embedwish.com", "hgcloud.to", "wishembed.pro", "kswplayer.info",
            "wishfast.top", "sfastwish.com", "strwish.xyz", "strwish.com",
            "flaswish.com", "awish.pro", "obeywish.com", "jodwish.com",
            "swhoi.com", "multimovies.cloud", "uqloads.xyz", "cdnwish.com",
            "asnwish.com", "nekowish.my.id", "neko-stream.click", "swdyu.com",
            "wishonly.site", "playerwish.com", "streamhls.to", "hlswish.com",
        )

        /** Shell marker: live evidence dhcplay /f/ = 452-819B "Loading..." page. */
        internal fun isShell(html: String): Boolean =
            html.contains("loading-container") && html.contains("/main.js")

        /**
         * Picks the script to parse, mirroring upstream precedence:
         * packed script (unpacked) > jwplayer vplayer setup > sources: data.
         */
        internal fun scriptWithSources(html: String): String? {
            Packer.getAndUnpack(html)?.let { return it }
            Regex(
                """(?s)<script[^>]*>(?:(?!</script>).)*jwplayer\("vplayer"\)\.setup\((?:(?!</script>).)*</script>""",
            ).find(html)?.let { return stripTags(it.value) }
            Regex(
                """(?s)<script[^>]*>(?:(?!</script>).)*\bsources\s*:(?:(?!</script>).)*</script>""",
            ).find(html)?.let { return stripTags(it.value) }
            return null
        }

        private val OPEN_TAG = Regex("^<script[^>]*>")
        private val CLOSE_TAG = Regex("</script>$")

        private fun stripTags(raw: String): String =
            CLOSE_TAG.replace(OPEN_TAG.replace(raw, ""), "")
    }
}

/**
 * JWPlayer source parser (upstream JwPlayerHelper, reduced to the JVM
 * path we need: sources:[{file:...}] blocks with a regex m3u8 fallback).
 */
internal object JwPlayerParser {

    private val SOURCES_REGEX = Regex(""""?sources"?:\s*(\[.*?])""")
    private val FILE_REGEX = Regex(""""?file"?\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val M3U8_REGEX = Regex("""[:=]\s*"([^"\s]+(?:\.m3u8|master\.txt)[^"\s]*)"""")

    /**
     * First playable source URL in [script], with \/ escapes decoded
     * (live evidence from the Voe port: JSON escapes appear in the wild).
     */
    internal fun firstSource(script: String): String? {
        for (block in SOURCES_REGEX.findAll(script)) {
            val file = FILE_REGEX.find(block.groupValues[1])?.groupValues?.get(1)
            if (!file.isNullOrEmpty()) return unescape(file)
        }
        return M3U8_REGEX.find(script)?.groupValues?.get(1)?.let { unescape(it) }
    }

    private fun unescape(raw: String): String = raw.replace("""\/""", "/")
}
