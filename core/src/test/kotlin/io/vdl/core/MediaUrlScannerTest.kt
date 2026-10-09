package io.vdl.core

import io.vdl.core.internal.extract.MediaUrlScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure scanner behavior: extraction, de-escaping, relative resolution,
 * dedup and ranking. Real regexes against real markup shapes — no fakes.
 */
class MediaUrlScannerTest {

    private val scanner = MediaUrlScanner(testLog())

    private fun hits(html: String, base: String = "https://site.example/watch?v=1") =
        scanner.scan(html, base)

    @Test
    fun hlsOutranksDirectInMarkup() {
        val html = """
            <html><body>
            <source src="https://cdn.example/a/prog.mp4" type="video/mp4">
            <script>var h = "https://cdn.example/a/master.m3u8";</script>
            </body></html>
        """.trimIndent()
        val result = hits(html)
        assertEquals(2, result.size)
        assertEquals(SourceKind.HLS, result[0].kind)
        assertEquals("https://cdn.example/a/master.m3u8", result[0].url)
        assertEquals(SourceKind.DIRECT, result[1].kind)
    }

    @Test
    fun escapedJsUrlsAreDecoded() {
        // Kotlin unicode escapes would be compiled to real chars, so the
        // literal backslashes are doubled to survive into the HTML fixture
        val html = "var u = \"https:\\u002F\\u002Fcdn.example\\u002Fx.m3u8?e=1\\u0026tok=2\";"
        val result = hits(html)
        assertEquals(listOf("https://cdn.example/x.m3u8?e=1&tok=2"), result.map { it.url })
    }

    @Test
    fun relativeAndProtocolAgnosticResolveAgainstBase() {
        val html = """
            <source src="/static/v/master.m3u8">
            <video src="//mirror.example/low.ts">
        """.trimIndent()
        val result = hits(html, "https://site.example/watch")
        assertEquals(
            setOf(
                "https://site.example/static/v/master.m3u8",
                "https://mirror.example/low.ts"
            ),
            result.map { it.url }.toSet()
        )
    }

    @Test
    fun jsonUrlFieldIsFoundAndDashIsRanked() {
        val html = """{"player":{"url":"https://srv.example/d/manifest.mpd"},"q":1}"""
        val result = hits(html)
        assertEquals(1, result.size)
        assertEquals(SourceKind.DASH, result[0].kind)
    }

    @Test
    fun duplicatesCollapseAndNonHttpAreRejected() {
        val html = """
            <source src="https://cdn.example/v/one.mp4">
            <source src="https://cdn.example/v/one.mp4">
            <source src="data:video/mp4;base64,AAAA">
            <source src="blob:https://x/1">
        """.trimIndent()
        val result = hits(html)
        assertEquals(listOf("https://cdn.example/v/one.mp4"), result.map { it.url })
    }

    @Test
    fun domainContainingMp4IsNotATruncatedHit() {
        // real-world regression: "https://www.mp4upload.com/embed-x.html"
        // produced a bogus DIRECT hit "https://www.mp4" with the old
        // non-greedy regex stopping at the first ".mp4" inside the hostname.
        val page = """<a href="https://www.mp4upload.com/embed-v1mvrhx69yp7.html">mp4</a>""" +
            """ watch at https://www.mp4upload.com/embed-v1mvrhx69yp7.html now"""
        assertTrue(hits(page).none { it.url == "https://www.mp4" })
        assertTrue(hits(page).none { it.kind == SourceKind.DIRECT })
    }

    @Test
    fun extensionEmbeddedMidTokenIsNotAMatch() {
        assertEquals(emptyList<String>(), hits("""x https://cdn.example/file.mp4.html y""").map { it.url })
        // but a real direct file still matches
        assertEquals(listOf("https://cdn.example/file.mp4"),
            hits("""x https://cdn.example/file.mp4 y""").map { it.url })
    }

    @Test
    fun pageWithoutMediaYieldsEmptyList() {
        assertTrue(hits("<html><body>plain text page</body></html>").isEmpty())
    }
}
