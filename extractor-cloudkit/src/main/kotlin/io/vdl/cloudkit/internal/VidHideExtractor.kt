package io.vdl.cloudkit.internal

import io.vdl.cloudkit.CloudKitKind
import io.vdl.cloudkit.CloudKitResult

import java.io.IOException

/**
 * VidHide extractor (vidhide family: VidHidePro + EarnVids mirrors).
 *
 * Ported from recloudstream/cloudstream `extractors/VidHidePro.kt`
 * (GPL-3.0, https://github.com/recloudstream/cloudstream — master @ 2026-10).
 * Upstream mechanism:
 *
 *   1. /file/, /d/, /download/ and /f/ paths normalize to /v/ (player);
 *   2. the player page carries a packed script OR a sources: script
 *      (jwplayer family, same shape StreamWish serves);
 *   3. the media URL is fetched with Referer/Origin of the player host.
 *
 * Live status 2026-10-10: movearnpre.com refuses connections from this
 * sandbox (connection reset, two independent captures), so the port is
 * validated against a synthetic packed fixture shaped like the family's
 * pages, and live resolution degrades to null (never throws) until a
 * reachable mirror confirms the protocol. The domain list keeps
 * upstream's mirrors plus movearnpre.com from the user's real page.
 */
internal class VidHideExtractor(private val http: CloudHttp) : CloudKitExtractor {

    override fun matches(url: String): Boolean = KNOWN_DOMAINS.any { url.contains(it) }

    /** Never throws; null on dead link, page change or connection refusal. */
    override suspend fun resolve(url: String): CloudKitResult? {
        val playerUrl = toPlayerUrl(url)
        val page = try {
            http.get(playerUrl)
        } catch (t: IOException) {
            return null
        }
        val script = StreamWishExtractor.scriptWithSources(page.body) ?: return null
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
            extractor = "VidHide",
        )
    }

    /** Upstream getEmbedUrl: every download-ish path becomes /v/. */
    internal fun toPlayerUrl(url: String): String = when {
        url.contains("/file/") -> url.replace("/file/", "/v/")
        url.contains("/d/") -> url.replace("/d/", "/v/")
        url.contains("/download/") -> url.replace("/download/", "/v/")
        url.contains("/f/") -> url.replace("/f/", "/v/")
        else -> url
    }

    internal companion object {
        // Upstream VidHidePro subclasses (vidhide + EarnVids mirrors) +
        // movearnpre.com captured in the user's real page (2026-10-09).
        internal val KNOWN_DOMAINS: List<String> = listOf(
            "vidhidepro.com", "vidhidehub.com", "vidhidevip.com", "vidhidepre.com",
            "movearnpre.com", "filelions.live", "filelions.online", "filelions.to",
            "kinoger.be", "ryderjet.com", "smoothpre.com", "dhtpre.com",
            "peytonepre.com",
        )
    }
}
