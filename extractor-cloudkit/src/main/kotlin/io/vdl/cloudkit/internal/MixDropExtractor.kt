package io.vdl.cloudkit.internal

import io.vdl.cloudkit.CloudKitKind
import io.vdl.cloudkit.CloudKitResult
import java.io.IOException

/**
 * MixDrop extractor (mxdrop/mixdrop rotating-domain family).
 *
 * Ported from recloudstream/cloudstream `extractors/MixDrop.kt` (GPL-3.0,
 * https://github.com/recloudstream/cloudstream — commit master @ 2026-10).
 * Upstream mechanism:
 *
 *   1. /f/{id} download link is the same page as the /e/{id} embed;
 *   2. the embed page carries a p.a.c.k.e.d script whose unpacked body
 *      sets MDCore.wurl = "//cdn.../v2/{id}.mp4?token...";
 *   3. the wurl is protocol-relative: httpsify (https:) it.
 *
 * Live evidence 2026-10-10: mixdrop.ag redirected to mxdrop.top (domain
 * rotation captured in the fixture), the real 78KB embed page unpacks and
 * yields wurl "//30xplewoo.mxcontent.net/v2/gjn98o4lbqk06z.mp4?s=...".
 */
internal class MixDropExtractor(private val http: CloudHttp) : CloudKitExtractor {

    override fun matches(url: String): Boolean = KNOWN_DOMAINS.any { url.contains(it) }

    /** Never throws; null on any degradation (dead link, page changed). */
    override suspend fun resolve(url: String): CloudKitResult? {
        val embedUrl = url.replaceFirst("/f/", "/e/")
        val page = try {
            http.get(embedUrl)
        } catch (t: IOException) {
            return null
        }
        val unpacked = Packer.getAndUnpack(page.body) ?: return null
        val wurl = WURL_REGEX.find(unpacked)?.groupValues?.get(1) ?: return null
        val direct = httpsify(wurl)
        return CloudKitResult(
            url = direct,
            kind = CloudKitKind.DIRECT,
            referer = url,
            extractor = "MixDrop",
        )
    }

    internal companion object {
        // Upstream MixDrop subclasses + mxdrop.top captured live 2026-10-10.
        internal val KNOWN_DOMAINS: List<String> = listOf(
            "mixdrop.co", "mixdrop.ps", "mixdrop.si", "mixdrop.bz", "mixdrop.ag",
            "mixdrop.ch", "mixdrop.to", "mxdrop.to", "mxdrop.top", "mdy48tn97.com",
        )

        internal val WURL_REGEX: Regex = Regex("""wurl.*?="(.*?)";""")

        /** Upstream httpsify: protocol-relative -> https:, bare -> https://. */
        internal fun httpsify(link: String): String = when {
            link.startsWith("//") -> "https:$link"
            link.startsWith("http") -> link
            else -> "https://$link"
        }
    }
}
