package io.vdl.core.internal.extract

import io.vdl.core.DownloadError
import io.vdl.core.ResolvedSource
import io.vdl.core.ResolveOutcome
import io.vdl.core.SourceKind
import io.vdl.core.internal.logging.VdlLog
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Orchestrates page resolution end to end:
 *
 *   1. URL already looks like media -> direct, zero network.
 *   2. fetch (capped); media content type -> direct.
 *   3. HTML scan (markup + JSON) -> best-ranked candidate.
 *   4. JS solve (atob / fromCharCode / QuickJS concat) -> candidates.
 *   5. Otherwise Fatal with full evidence in the log.
 *
 * The JsEngine is created per resolve() call and always closed: QuickJS
 * runtimes hold native memory, resolve() is rare (interactive), and a
 * per-call lifetime makes leaks structurally impossible.
 */
internal class SourceResolver internal constructor(
    private val client: OkHttpClient,
    private val log: VdlLog,
    private val jsEngineFactory: () -> JsEngine
) {

    internal suspend fun resolve(url: String): ResolveOutcome {
        val t0 = System.nanoTime()
        log.i(TAG) { "resolve start url=$url" }
        try {
            // 1. URL itself is media: no fetch at all.
            UrlKind.fromUrl(url)?.let { kind ->
                log.i(TAG) { "resolve direct-by-url kind=$kind fetch=no" }
                return ok(url, kind, "direct")
            }

            // 2. Fetch the page (or discover the URL is media).
            when (val f = PageFetcher(client, log).fetch(url)) {
                is PageFetcher.Fetched.Media ->
                    return ok(url, f.kind, "direct")
                is PageFetcher.Fetched.Failure -> {
                    val err = if (f.httpCode != null) {
                        DownloadError.Http(f.httpCode, f.reason)
                    } else {
                        DownloadError.Network(f.reason, null)
                    }
                    return ResolveOutcome.Fatal(err)
                }
                is PageFetcher.Fetched.Html -> {
                    // 3. scan markup/JSON first (cheap, most sites)
                    val scanner = MediaUrlScanner(log)
                    val title = pageTitle(f.body)
                    scanner.scan(f.body, url).firstOrNull()?.let {
                        return ok(it.url, it.kind, "html-scan", title)
                    }
                    // 4. obfuscated JS — the QuickJS path
                    val js = jsEngineFactory()
                    try {
                        JsUrlSolver(log, js).solve(f.body, url).firstOrNull()?.let {
                            return ok(it.url, it.kind, "js-solve:${it.technique}", title)
                        }
                    } finally {
                        js.close()
                    }
                    log.w(TAG) { "resolve nothing-found url=$url decision=fatal" }
                    return ResolveOutcome.Fatal(
                        DownloadError.InvalidRequest("No media source found on the page")
                    )
                }
            }
        } finally {
            val dtMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
            log.i(TAG) { "resolve end url=$url dt=${dtMs}ms" }
        }
    }

    private fun ok(url: String, kind: SourceKind, origin: String, title: String? = null) =
        ResolveOutcome.Success(ResolvedSource(url = url, kind = kind, origin = origin, title = title))

    private fun pageTitle(html: String): String? {
        val m = Regex("""<title[^>]*>([^<]{1,256})</title>""", RegexOption.IGNORE_CASE)
            .find(html) ?: return null
        return m.groupValues[1].trim().takeIf { it.isNotEmpty() }
    }

    internal companion object {
        internal const val TAG = "[VDL][EXTRACT][RESOLVE]"
    }
}
