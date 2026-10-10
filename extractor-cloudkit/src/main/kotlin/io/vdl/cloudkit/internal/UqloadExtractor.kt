package io.vdl.cloudkit.internal

import io.vdl.cloudkit.CloudKitKind
import io.vdl.cloudkit.CloudKitResult

import java.io.IOException

/**
 * Uqload extractor.
 *
 * Ported from recloudstream/cloudstream `extractors/Uqload.kt` (GPL-3.0,
 * https://github.com/recloudstream/cloudstream — commit master @ 2026-10).
 * Upstream mechanism: the embed page carries a jwplayer-style
 * `sources: "...media url"` assignment; the match is a direct link
 * (mp4) played with the uqload referer.
 */
internal class UqloadExtractor(private val http: CloudHttp) : CloudKitExtractor {

    override fun matches(url: String): Boolean = KNOWN_DOMAINS.any { url.contains(it) }

    /** Never throws; null on any degradation. */
    override suspend fun resolve(url: String): CloudKitResult? {
        val page = try {
            http.get(url)
        } catch (t: IOException) {
            return null
        }
        val link = SOURCES_REGEX.find(page.body)?.groupValues?.get(1) ?: return null
        return CloudKitResult(
            url = link,
            kind = CloudKitKind.DIRECT,
            referer = "${DoodExtractor.baseUrlOf(url)}/",
            extractor = "Uqload",
        )
    }

    internal companion object {
        internal val KNOWN_DOMAINS: List<String> = listOf(
            "uqload.com", "uqload.co", "uqload.cx", "uqload.bz",
        )

        internal val SOURCES_REGEX: Regex = Regex("""sources:.*"(.*?)".*""")
    }
}
