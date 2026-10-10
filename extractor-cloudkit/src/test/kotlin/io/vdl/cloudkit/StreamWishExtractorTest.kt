package io.vdl.cloudkit

import io.vdl.cloudkit.internal.CloudHttp
import io.vdl.cloudkit.internal.StreamWishExtractor
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * StreamWish extractor tests. The REAL dhcplay shell capture (live
 * 2026-10-09/10) proves the typed degradation on the JS-challenge path
 * (WebView-class, same answer upstream gives through WebViewResolver);
 * the player parsing is exercised against a synthetic fixture built
 * from the upstream StreamWishExtractor.kt jwplayer("vplayer") shape.
 */
class StreamWishExtractorTest {

    private fun fixture(name: String): String {
        val url = javaClass.classLoader.getResource("fixtures/$name")
            ?: error("fixture missing: $name")
        return File(url.toURI()).readText()
    }

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Test
    fun `resolve parses the jwplayer setup to HLS`(): Unit = runBlocking {
        val page = fixture("streamwish-player-synthetic.html")
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(page).build())

            val extractor = StreamWishExtractor(CloudHttp(client()))
            val input = server.url("/e/pqe67q5zix6x").toString()
            val result = extractor.resolve(input)

            assertNotNull("player page resolves", result)
            assertEquals(CloudKitKind.HLS, result!!.kind)
            assertEquals("StreamWish", result.extractor)
            assertTrue(result.url.endsWith("/master.m3u8?t=1770000000&s=abc123"))
            assertEquals("referer is the final host root", server.url("/").toString(), result.referer)
        }
    }

    @Test
    fun `resolve degrades to null on the REAL shell challenge capture`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(fixture("streamwish-shell-real.html")).build())
            val extractor = StreamWishExtractor(CloudHttp(client()))
            assertNull(
                "live shell challenge yields null (WebView-class), never a throw",
                extractor.resolve(server.url("/f/pqe67q5zix6x").toString()),
            )
        }
    }

    @Test
    fun `resolveEmbedUrl normalizes f and e routes like upstream`() {
        val extractor = StreamWishExtractor(CloudHttp(client()))
        assertEquals(
            "https://dhcplay.com/pqe67q5zix6x",
            extractor.resolveEmbedUrl("https://dhcplay.com/f/pqe67q5zix6x"),
        )
        assertEquals(
            "https://dhcplay.com/pqe67q5zix6x",
            extractor.resolveEmbedUrl("https://dhcplay.com/e/pqe67q5zix6x"),
        )
    }

    @Test
    fun `matches recognizes the dhcplay domain from the user pages`() {
        val extractor = StreamWishExtractor(CloudHttp(client()))
        assertTrue(extractor.matches("https://dhcplay.com/f/pqe67q5zix6x"))
        assertTrue(extractor.matches("https://streamwish.to/e/abc"))
        assertTrue(!extractor.matches("https://example.com/e/abc"))
    }
}
