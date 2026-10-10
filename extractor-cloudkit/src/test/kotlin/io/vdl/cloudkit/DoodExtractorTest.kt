package io.vdl.cloudkit

import io.vdl.cloudkit.internal.CloudHttp
import io.vdl.cloudkit.internal.DoodExtractor
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
 * Dood extractor tests. Live status 2026-10-10: every family domain is
 * Cloudflare-gated for JVM clients (real capture included here proves
 * the typed degradation), so the pass_md5 protocol is exercised against
 * a synthetic fixture built from the upstream DoodExtractor.kt shape —
 * the JVM path upstream itself would take on a non-challenged response.
 */
class DoodExtractorTest {

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
    fun `resolve walks the pass_md5 protocol to a direct url`(): Unit = runBlocking {
        val page = fixture("dood-e-synthetic.html")
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString().trimEnd('/')
            server.enqueue(MockResponse.Builder().body(page.replace("https://dood.mock.local", base)).build())
            server.enqueue(MockResponse.Builder().body("$base/dl/oppie/8f3c1a99bd42e6ff2/cuekdui0sm0b.mp4").build())

            val extractor = DoodExtractor(CloudHttp(client()))
            val result = extractor.resolve(server.url("/d/cuekdui0sm0b").toString())

            assertNotNull("protocol resolves end to end", result)
            assertEquals(CloudKitKind.DIRECT, result!!.kind)
            assertEquals("Dood", result.extractor)
            // mediaPath + exactly 10 random chars + ?token=md5Id
            assertTrue("prefix is the pass_md5 body", result.url.startsWith("$base/dl/oppie/8f3c1a99bd42e6ff2/cuekdui0sm0b.mp4"))
            val suffix = result.url.removePrefix("$base/dl/oppie/8f3c1a99bd42e6ff2/cuekdui0sm0b.mp4")
            assertTrue("suffix = 10 chars + token param", Regex("""^[A-Za-z0-9]{10}\?token=8f3c1a99bd42e6ff2$""").matches(suffix))
            assertEquals("referer is the FINAL host root", "$base/", result.referer)
            // the second request must carry the embed referer
            val embedReq = server.takeRequest()
            // the /d/ -> /e/ rewrite happens BEFORE the first request
            assertEquals("/e/cuekdui0sm0b", embedReq.url.encodedPath)
            val passReq = server.takeRequest()
            assertTrue(passReq.url.encodedPath.startsWith("/pass_md5/8f3c1a99bd42e6ff2/"))
        }
    }

    @Test
    fun `resolve returns null on the real cloudflare capture`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(403).body(fixture("dood-cf-real.html")).build())
            val extractor = DoodExtractor(CloudHttp(client()))
            assertNull("Cloudflare-gated live capture yields null, never a throw", extractor.resolve(server.url("/e/cuekdui0sm0b").toString()))
        }
    }

    @Test
    fun `resolve returns null when pass_md5 is absent`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("<html><body>no protocol here</body></html>").build())
            val extractor = DoodExtractor(CloudHttp(client()))
            assertNull(extractor.resolve(server.url("/e/x").toString()))
        }
    }

    @Test
    fun `matches recognizes live-observed domains`() {
        val extractor = DoodExtractor(CloudHttp(client()))
        assertTrue(extractor.matches("https://doodstream.com/d/cuekdui0sm0b"))
        assertTrue(extractor.matches("https://playmogo.com/e/cuekdui0sm0b"))
        assertTrue(!extractor.matches("https://example.com/d/x"))
    }
}
