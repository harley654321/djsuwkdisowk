package io.vdl.cloudkit

import io.vdl.cloudkit.internal.CloudHttp
import io.vdl.cloudkit.internal.VidHideExtractor
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * VidHide extractor tests against a synthetic fixture shaped like the
 * family's jwplayer pages (upstream VidHidePro scans packed scripts or
 * sources: blocks, same as StreamWish).
 *
 * Live status: movearnpre.com refuses sandbox connections (connection
 * reset, 2026-10-10), so live validation happens when a reachable mirror
 * appears; the fixture proves the parsing path offline.
 */
class VidHideExtractorTest {

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
    fun `resolve extracts the source from the player page`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().body(fixture("vidhide-player-synthetic.html")).build(),
            )

            val extractor = VidHideExtractor(CloudHttp(client()))
            // Real link shape: /file/ download path from the user's page.
            val input = server.url("/file/hzd7fiqnsz2y").toString()
            val result = extractor.resolve(input)

            assertNotNull(result)
            assertEquals(CloudKitKind.HLS, result!!.kind)
            assertEquals("VidHide", result.extractor)
            assertEquals(
                "https://vidhidecdn.mock.local/hls2/04/hzd7fiqnsz2y/master.m3u8?t=1770000000&s=vh456",
                result.url,
            )
            assertEquals(server.url("/").toString(), result.referer)
            // download path must be rewritten to the player path
            val recorded = server.takeRequest()
            assertEquals("/v/hzd7fiqnsz2y", recorded.url.encodedPath)
        }
    }

    @Test
    fun `toPlayerUrl rewrites every download path family`(): Unit {
        val extractor = VidHideExtractor(CloudHttp(client()))
        assertEquals(
            "https://movearnpre.com/v/hzd7fiqnsz2y",
            extractor.toPlayerUrl("https://movearnpre.com/file/hzd7fiqnsz2y"),
        )
        assertEquals(
            "https://movearnpre.com/v/abc",
            extractor.toPlayerUrl("https://movearnpre.com/d/abc"),
        )
        assertEquals(
            "https://movearnpre.com/v/abc",
            extractor.toPlayerUrl("https://movearnpre.com/download/abc"),
        )
        assertEquals(
            "https://movearnpre.com/v/abc",
            extractor.toPlayerUrl("https://movearnpre.com/f/abc"),
        )
        assertEquals(
            "https://movearnpre.com/v/abc",
            extractor.toPlayerUrl("https://movearnpre.com/v/abc"),
        )
    }

    @Test
    fun `resolve returns null without sources`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("<html><body>empty</body></html>").build())
            val extractor = VidHideExtractor(CloudHttp(client()))
            assertNull(extractor.resolve(server.url("/v/abc").toString()))
        }
    }

    @Test
    fun `matches recognizes vidhide and earnvids domains`(): Unit {
        val extractor = VidHideExtractor(CloudHttp(client()))
        assertTrue(extractor.matches("https://movearnpre.com/file/hzd7fiqnsz2y"))
        assertTrue(extractor.matches("https://vidhidepro.com/v/abc"))
        assertTrue(extractor.matches("https://smoothpre.com/v/abc")) // EarnVids mirror
        assertFalse(extractor.matches("https://example.com/v/abc"))
    }
}
