package io.vdl.cloudkit.internal

import io.vdl.cloudkit.CloudKitKind
import io.vdl.cloudkit.CloudKitResult
import java.io.IOException

/**
 * DoodStream extractor (dood rotating-domain family).
 *
 * Ported from recloudstream/cloudstream `extractors/DoodExtractor.kt`
 * (GPL-3.0, https://github.com/recloudstream/cloudstream — commit master
 * @ 2026-10). Upstream mechanism (pass_md5 protocol):
 *
 *   1. GET /e/{id} (from /d/{id}); note the FINAL host after redirects —
 *      domains rotate (live evidence: doodstream.com -> playmogo.com);
 *   2. the page embeds "/pass_md5/{token}/";
 *   3. GET {host}/pass_md5/... returns a bare 10-char-less media path;
 *      append 10 random alphanumerics + "?token=" + the md5 id;
 *   4. the result is a direct mp4; the CDN expects the embed referer.
 *
 * Live status 2026-10-10: every family domain answers the JVM client
 * with a Cloudflare "Just a moment..." challenge (5637B page, captured
 * as fixture). Upstream's plain GET fails identically here — this host
 * is WebView-class on Android until Cloudflare relaxes. The port keeps
 * upstream behavior; the extractor degrades to null on such pages.
 */
internal class DoodExtractor(private val http: CloudHttp) : CloudKitExtractor {

    override fun matches(url: String): Boolean = KNOWN_DOMAINS.any { url.contains(it) }

    /** Never throws; null on any degradation (challenge, dead link). */
    override suspend fun resolve(url: String): CloudKitResult? {
        val embedUrl = url.replace("/d/", "/e/")
        val page = try {
            http.get(embedUrl)
        } catch (t: IOException) {
            return null
        }
        val passMd5Path = PASS_MD5_REGEX.find(page.body)?.value ?: return null
        val host = baseUrlOf(page.finalUrl)
        val md5Url = host + passMd5Path
        val mediaPath = try {
            http.get(md5Url, referer = page.finalUrl).body
        } catch (t: IOException) {
            return null
        }
        // md5Url ends with "/" (…/pass_md5/{id}/): trim so the token is the
        // id itself, never an empty string (regression caught by fixture test).
        val token = md5Url.trimEnd('/').substringAfterLast("/")
        val direct = mediaPath + randomHash10() + "?token=" + token
        return CloudKitResult(
            url = direct,
            kind = CloudKitKind.DIRECT,
            referer = host + "/",
            extractor = "Dood",
        )
    }

    internal companion object {
        // Upstream DoodLaExtractor subclasses (dood.la family) + live
        // redirect targets captured 2026-10-10 (playmogo.com).
        internal val KNOWN_DOMAINS: List<String> = listOf(
            "dood.la", "doodstream.com", "playmogo.com", "d0000d.com", "d000d.com",
            "dooood.com", "dood.wf", "dood.cx", "dood.sh", "dood.watch",
            "dood.pm", "dood.to", "dood.so", "dood.ws", "dood.yt",
            "dood.li", "ds2play.com", "ds2video.com", "dsvplay.com",
            "vide0.net", "myvidplay.com", "doods.pro",
        )

        internal val PASS_MD5_REGEX: Regex = Regex("""/pass_md5/[^']*""")

        private const val ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        /** Upstream createHashTable(): 10 random alphanumerics. */
        internal fun randomHash10(): String = buildString {
            repeat(10) { append(ALPHABET.random()) }
        }

        internal fun baseUrlOf(url: String): String {
            val idx = url.indexOf("://")
            if (idx < 0) return url
            val hostStart = idx + 3
            val slash = url.indexOf('/', hostStart)
            return if (slash < 0) url else url.substring(0, slash)
        }
    }
}
