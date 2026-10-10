package io.vdl.cloudkit

import io.vdl.cloudkit.internal.CloudHttp
import io.vdl.cloudkit.internal.UqloadExtractor
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
 * Uqload extractor tests against a synthetic fixture built from the
 * upstream Uqload.kt sources-regex shape (no live link exists in the
 * user's pages yet; live validation happens when one appears).
 */
class UqloadExtractorTest {

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
    fun `resolve extracts the direct source url`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(fixture("uqload-synthetic.html")).build())

            val extractor = UqloadExtractor(CloudHttp(client()))
            val input = server.url("/embed-abc.html").toString()
            val result = extractor.resolve(input)

            assertNotNull(result)
            assertEquals(CloudKitKind.DIRECT, result!!.kind)
            assertEquals("Uqload", result.extractor)
            assertEquals("https://uq.mock.local/1e5/6f5/3319/j6c9jrigrpct.mp4", result.url)
            assertEquals(server.url("/").toString(), result.referer)
        }
    }

    @Test
    fun `resolve returns null without sources`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("<html><body>empty</body></html>").build())
            val extractor = UqloadExtractor(CloudHttp(client()))
            assertNull(extractor.resolve(server.url("/e.html").toString()))
        }
    }

    @Test
    fun `matches recognizes uqload domains`() {
        val extractor = UqloadExtractor(CloudHttp(client()))
        assertTrue(extractor.matches("https://uqload.com/embed-abc.html"))
        assertTrue(!extractor.matches("https://example.com/e"))
    }
}
