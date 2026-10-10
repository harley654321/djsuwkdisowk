package io.vdl.cloudkit

import io.vdl.cloudkit.internal.CloudHttp
import io.vdl.cloudkit.internal.CloudKitExtractor
import io.vdl.cloudkit.internal.DoodExtractor
import io.vdl.cloudkit.internal.MixDropExtractor
import io.vdl.cloudkit.internal.StreamWishExtractor
import io.vdl.cloudkit.internal.UqloadExtractor
import io.vdl.cloudkit.internal.VoeExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * CloudKit — host-aware embed resolution backed by the recloudstream
 * extractor family (GPL-3.0, ported in this module; see LICENSE-NOTE.md).
 *
 * The downloader core hands an embed URL here and receives a ready-to-
 * fetch media URL (master.m3u8 or direct file) with the referer the CDN
 * expects. Routing is domain-based: each extractor declares the domains
 * it owns, first match wins. Unknown hosts return null — the caller
 * falls back to the generic resolver.
 *
 * Shell-challenge hosts (streamwish family as of 2026-10-10) and
 * Cloudflare-gated hosts (dood family as of 2026-10-10) degrade to null
 * here; upstream cloudstream resolves those through a WebView, which is
 * the Android app's wiring, not this module's JVM scope.
 *
 * Usage:
 * ```
 * val result = CloudKit.resolve(embedUrl, okHttpClient)
 * // result.kind == CloudKitKind.HLS -> hand to the HLS engine
 * ```
 */
public object CloudKit {

    /**
     * Resolves [url] to a direct media URL. Returns null when no extractor
     * claims the domain or the extraction degraded (site changed, link
     * dead). Network work runs on Dispatchers.IO; safe to call from any
     * dispatcher.
     *
     * @param client OkHttp client the extractor should reuse (connection
     *   pooling, timeouts and proxying stay under the app's control).
     */
    public suspend fun resolve(url: String, client: OkHttpClient): CloudKitResult? =
        withContext(Dispatchers.IO) {
            val http = CloudHttp(client)
            for (extractor in registry(http)) {
                if (extractor.matches(url)) {
                    return@withContext extractor.resolve(url)
                }
            }
            null
        }

    // Stateless extractors; ordering documents the first-match rule.
    // Voe stays last: its domain list is fully disjoint from the rest.
    private fun registry(http: CloudHttp): List<CloudKitExtractor> = listOf(
        MixDropExtractor(http),
        DoodExtractor(http),
        StreamWishExtractor(http),
        UqloadExtractor(http),
        VoeExtractor(http),
    )
}
