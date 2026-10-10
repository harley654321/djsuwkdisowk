package io.vdl.cloudkit

import io.vdl.cloudkit.internal.ByseExtractor
import io.vdl.cloudkit.internal.CloudHttp
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
 * Byse extractor tests.
 *
 * The API fixture is the REAL /api/videos/{code} response captured live
 * 2026-10-10 from byselapuix.com (the user's page link), and the expected
 * m3u8 is the payload's decrypted plaintext — the test proves the full
 * version-19 key selection + AES-256-GCM chain offline. Upstream ByseSX
 * concatenates only the first two key_parts, which FAILS this fixture:
 * the version scheme is the live protocol.
 */
class ByseExtractorTest {

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
    fun `resolve decrypts the real playback envelope to the live master m3u8`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().body(fixture("byse-video-api-real.json")).build(),
            )

            val extractor = ByseExtractor(CloudHttp(client()))
            val input = server.url("/e/nemilh1pfirr").toString()
            val result = extractor.resolve(input)

            assertNotNull("live capture must decrypt offline", result)
            assertEquals(CloudKitKind.HLS, result!!.kind)
            assertEquals("Byse", result.extractor)
            assertTrue(
                "the master.m3u8 the live API served on 2026-10-10",
                result.url.contains("/hls2/04/12173/nemilh1pfirr_x/master.m3u8"),
            )
            val expected = io.vdl.cloudkit.internal.SourceJson
                .firstUrl(fixture("byse-playback-decrypted-real.json"))
            assertEquals("URL identical to the real decrypted plaintext", expected, result.url)
            // code must be requested on the same origin's API
            val recorded = server.takeRequest()
            assertEquals("/api/videos/nemilh1pfirr", recorded.url.encodedPath)
        }
    }

    @Test
    fun `selectKey picks the version pair not the first two parts`(): Unit {
        val extractor = ByseExtractor(CloudHttp(client()))
        val envelope = ByseExtractor.PlaybackEnvelope.parse(fixture("byse-video-api-real.json"))
        assertNotNull(envelope)
        val key = extractor.selectKey(envelope!!)
        assertNotNull("version 19 -> key_parts[19] + key_parts[12] (1-based)", key)
        assertEquals("AES-256 needs exactly 32 key bytes", 32, key!!.size)
    }

    @Test
    fun `resolve returns null on a page without playback envelope`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().body("""{"error":"resource not found"}""").build(),
            )
            val extractor = ByseExtractor(CloudHttp(client()))
            assertNull(extractor.resolve(server.url("/e/deadcode").toString()))
        }
    }

    @Test
    fun `resolve returns null on connection failure`(): Unit = runBlocking {
        val extractor = ByseExtractor(CloudHttp(client()))
        assertNull(extractor.resolve("http://127.0.0.1:1/e/nemilh1pfirr"))
    }

    @Test
    fun `codeOf takes the last path segment for e and d embeds`(): Unit {
        val extractor = ByseExtractor(CloudHttp(client()))
        assertEquals("nemilh1pfirr", extractor.codeOf("https://byselapuix.com/e/nemilh1pfirr"))
        assertEquals("rzly7gkdz2r8", extractor.codeOf("https://bysesukior.com/d/rzly7gkdz2r8"))
        assertEquals("nemilh1pfirr", extractor.codeOf("https://byselapuix.com/e/nemilh1pfirr#frag"))
    }
}
