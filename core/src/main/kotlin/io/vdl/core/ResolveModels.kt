package io.vdl.core

/**
 * Result of resolving a page (or direct media) URL into a downloadable
 * media source via [VdlDownloader.resolve].
 */
public sealed interface ResolveOutcome {

    /** A media URL was found and classified. */
    public data class Success(public val source: ResolvedSource) : ResolveOutcome

    /** Resolution failed terminally (network, non-media page, nothing found). */
    public data class Fatal(public val error: DownloadError) : ResolveOutcome
}

/** Transport kind of the resolved source; maps 1:1 to download engines. */
public enum class SourceKind { DIRECT, HLS, DASH }

/**
 * A concrete, downloadable media URL.
 *
 * @param url absolute http(s) media URL, ready for [VdlDownloader.download]
 * @param kind transport kind; decides which engine will run
 * @param origin evidence of how it was found: "direct" (the URL itself is
 *   media), "html-scan" (found in the page markup) or "js-solve:<technique>"
 *   (recovered from obfuscated JavaScript)
 * @param title best-effort page title, null when unknown
 */
public data class ResolvedSource(
    public val url: String,
    public val kind: SourceKind,
    public val origin: String,
    public val title: String?
)
