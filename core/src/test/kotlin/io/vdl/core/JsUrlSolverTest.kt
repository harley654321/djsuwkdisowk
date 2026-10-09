package io.vdl.core

import io.vdl.core.internal.extract.JsUrlSolver
import io.vdl.core.internal.extract.QuickJsEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Solver techniques against REAL artifacts: Kotlin decodes for
 * atob/fromCharCode, and the js-concat path runs on the REAL QuickJS
 * engine (quickjs-kt) — the same engine production uses on Android.
 */
class JsUrlSolverTest {

    private val js = QuickJsEngine(testLog())
    private val solver = JsUrlSolver(testLog(), js)

    @After
    fun tearDown() {
        js.close()
    }

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    @Test
    fun atobLiteralIsDecodedInKotlin() = runBlocking {
        val target = "https://media.example/hls/index.m3u8"
        val html = "var src = atob('${b64(target)}'); player.play(src);"
        val hits = solver.solve(html, "https://site.example/watch")
        assertEquals(1, hits.size)
        assertEquals(target, hits[0].url)
        assertEquals(SourceKind.HLS, hits[0].kind)
        assertEquals("atob", hits[0].technique)
    }

    @Test
    fun fromCharCodeArrayIsDecoded() = runBlocking {
        val target = "https://media.example/dash/manifest.mpd"
        val codes = target.map { it.code.toString() }.joinToString(",")
        val html = "var u = String.fromCharCode($codes); load(u);"
        val hits = solver.solve(html, "https://site.example/watch")
        assertEquals(1, hits.size)
        assertEquals(target, hits[0].url)
        assertEquals("fromCharCode", hits[0].technique)
    }

    @Test
    fun concatenatedPiecesAreSolvedByRealQuickJs() = runBlocking {
        val html = """
            var base = 'https://files.example' + '/stream/' + 'video.m3u8';
            init({ src: 'irrelevant' });
        """.trimIndent()
        val hits = solver.solve(html, "https://site.example/watch")
        assertEquals(1, hits.size)
        assertEquals("https://files.example/stream/video.m3u8", hits[0].url)
        assertEquals("js-concat", hits[0].technique)
    }

    @Test
    fun nonUrlConcatResultsAreRejected() = runBlocking {
        val html = "var t = 'hello' + ' ' + 'world'; var q = 42;"
        assertTrue(solver.solve(html, "https://site.example").isEmpty())
    }

    @Test
    fun invalidBase64IsSkippedNotCrashed() = runBlocking {
        // matches the charset but is NOT valid base64 ('=' mid-string)
        val html = "atob('A+/=A+/=A+/=A+/=')"
        assertTrue(solver.solve(html, "https://site.example").isEmpty())
    }

    @Test
    fun nonAsciiCharCodesAreRefused() = runBlocking {
        val html = "String.fromCharCode(0x10FFFF, 0x68, 0x74)"
        assertTrue(solver.solve(html, "https://site.example").isEmpty())
    }
}
