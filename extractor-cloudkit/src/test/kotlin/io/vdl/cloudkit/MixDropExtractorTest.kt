package io.vdl.cloudkit

import io.vdl.cloudkit.internal.CloudHttp
import io.vdl.cloudkit.internal.MixDropExtractor
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
 * MixDrop extractor tests: the REAL 78KB embed capture from mxdrop.top
 * (redirect target of mixdrop.ag, live 2026-10-10) drives the end-to-end
 * path through a hermetic MockWebServer; degradation cases prove the
 * never-throw contract.
 */
class MixDropExtractorTest {

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
    fun `resolve unpacks the real capture to the httpsified wurl`(): Unit = runBlocking {
        val page = fixture("mixdrop-e-real.html")
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .addHeader("Location", "https://mxdrop.top/e/gjn98o4lbqk06z")
                    .code(302)
                    .body("")
                    .build(),
            )
            server.enqueue(MockResponse.Builder().body(page).build())

            val extractor = MixDropExtractor(CloudHttp(client()))
            val input = server.url("/f/gjn98o4lbqk06z").toString()
            val result = extractor.resolve(input)

            assertNotNull("real capture resolves end to end", result)
            assertEquals(CloudKitKind.DIRECT, result!!.kind)
            assertEquals("MixDrop", result.extractor)
            assertTrue(
                "wurl httpsified exactly as captured live",
                result.url.startsWith(
                    "https://30xplewoo.mxcontent.net/v2/gjn98o4lbqk06z.mp4?s=",
                ),
            )
            assertEquals("referer is the original input url", input, result.referer)
        }
    }

    @Test
    fun `resolve returns null when the page has no packed script`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("<html><body>nothing packed</body></html>").build())
            val extractor = MixDropExtractor(CloudHttp(client()))
            assertNull("degraded page yields null, never a throw", extractor.resolve(server.url("/e/x").toString()))
        }
    }

    @Test
    fun `resolve returns null on connection failure`(): Unit = runBlocking {
        val extractor = MixDropExtractor(CloudHttp(client()))
        assertNull(extractor.resolve("http://127.0.0.1:1/e/gjn98o4lbqk06z"))
    }

    @Test
    fun `matches recognizes the live-observed rotating domains`() {
        val extractor = MixDropExtractor(CloudHttp(client()))
        assertTrue(extractor.matches("https://mixdrop.ag/f/gjn98o4lbqk06z"))
        assertTrue(extractor.matches("https://mxdrop.top/e/gjn98o4lbqk06z"))
        assertTrue(extractor.matches("https://mdy48tn97.com/f/x"))
        assertTrue(!extractor.matches("https://example.com/f/x"))
    }
}
