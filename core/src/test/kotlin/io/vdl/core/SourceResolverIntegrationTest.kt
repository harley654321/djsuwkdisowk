package io.vdl.core

import io.vdl.core.internal.extract.QuickJsEngine
import io.vdl.core.internal.extract.SourceResolver
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Full resolver pipeline against a REAL MockWebServer and the REAL
 * QuickJS engine: direct URLs, content-type detection, HTML scan and
 * JS-obfuscated recovery, with the evidence log collected in the sink.
 */
class SourceResolverIntegrationTest {

    private lateinit var server: MockWebServer
    private val sink = PrintSink()
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdown()
        server.close()
    }

    private fun url(path: String) = server.url(path).toString()

    private fun resolver() =
        SourceResolver(client, testLog(sink)) { QuickJsEngine(testLog(sink)) }

    @Test
    fun directMediaUrlResolvesWithoutAnyRequest() = runBlocking {
        val outcome = resolver().resolve("https://cdn.example/files/movie.mkv")
        val src = (outcome as ResolveOutcome.Success).source
        assertEquals(SourceKind.DIRECT, src.kind)
        assertEquals("direct", src.origin)
        assertEquals("https://cdn.example/files/movie.mkv", src.url)
    }

    @Test
    fun mediaContentTypeResolvesToHlsDirect() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "application/vnd.apple.mpegurl")
                .body("#EXTM3U")
                .build()
        )
        val outcome = resolver().resolve(url("/live/stream"))
        val src = (outcome as ResolveOutcome.Success).source
        assertEquals(SourceKind.HLS, src.kind)
        assertEquals("direct", src.origin)
    }

    @Test
    fun htmlPageScansToBestCandidate() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .body(
                    "<html><title>My Video</title><body>" +
                        "<source src=\"" + url("/h/master.m3u8") + "\" type=\"vnd.apple.mpegurl\">" +
                        "<source src=\"" + url("/h/low.mp4") + "\" type=\"video/mp4\">" +
                        "</body></html>"
                )
                .build()
        )
        val outcome = resolver().resolve(url("/watch/1"))
        val src = (outcome as ResolveOutcome.Success).source
        assertEquals(SourceKind.HLS, src.kind)
        assertEquals("html-scan", src.origin)
        assertEquals(url("/h/master.m3u8"), src.url)
        assertEquals("My Video", src.title)
    }

    @Test
    fun obfuscatedPageIsSolvedByRealQuickJs() = runBlocking {
        val manifest = url("/d/manifest.mpd")
        val base = url("/").removeSuffix("/") // http://host:port
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/html")
                .body(
                    "<html><script>" +
                        "var f = '$base' + '/d/' + 'manifest.mpd'; boot(atob); doNothing();" +
                        "</script></html>"
                )
                .build()
        )
        val outcome = resolver().resolve(url("/embed/2"))
        val src = (outcome as ResolveOutcome.Success).source
        assertEquals("js-solve:js-concat", src.origin)
        assertEquals(manifest, src.url)
        assertEquals(SourceKind.DASH, src.kind)
        // evidence: the QuickJS path must leave its solve trace
        assertTrue(sink.lines.any { it.contains("solve hit") && it.contains("js-concat") })
    }

    @Test
    fun pageWithNothingResolvesFatalTyped() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/html")
                .body("<html><body>no media here at all</body></html>")
                .build()
        )
        val outcome = resolver().resolve(url("/about"))
        val fatal = outcome as ResolveOutcome.Fatal
        assertTrue(fatal.error is DownloadError.InvalidRequest)
    }

    @Test
    fun httpErrorIsFatalTypedWithCode() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).build())
        val outcome = resolver().resolve(url("/gone"))
        val fatal = outcome as ResolveOutcome.Fatal
        assertEquals(404, (fatal.error as DownloadError.Http).code)
    }

    @Test
    fun everyResolutionLeavesEvidenceInLog() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/html")
                .body("<html><source src=\"/m/index.m3u8\"></html>")
                .build()
        )
        resolver().resolve(url("/w"))
        assertTrue(sink.lines.any { it.contains("resolve start") })
        // the /m/index.m3u8 hit arrives via the attribute path, not plain
        assertTrue(sink.lines.any { it.contains("scan plain=0 attr=1") })
        assertTrue(sink.lines.any { it.contains("resolve end") })
    }
}
