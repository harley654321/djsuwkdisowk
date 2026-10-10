package io.vdl.cloudkit

import io.vdl.cloudkit.internal.CloudHttp
import io.vdl.cloudkit.internal.UpnshareExtractor
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
 * UPNShare (uns.bio vidstack family) tests.
 *
 * The API fixture is the REAL hex ciphertext captured live 2026-10-10
 * from animeav1.uns.bio/api/v1/video?id=kjkatd (the user's page link),
 * and the expected m3u8 is the plaintext it decrypts to — the test
 * proves the full decrypt chain offline.
 */
class UpnshareExtractorTest {

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
    fun `resolve decrypts the real hex payload to the live m3u8`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().body(fixture("upnshare-api-real.txt")).build(),
            )

            val extractor = UpnshareExtractor(CloudHttp(client()))
            // Same shape as the real link: fragment carries the id.
            val input = server.url("/#kjkatd").toString()
            val result = extractor.resolve(input)

            assertNotNull("live capture must decrypt offline", result)
            assertEquals(CloudKitKind.HLS, result!!.kind)
            assertEquals("Upnshare", result.extractor)
            assertTrue(
                "the m3u8 the live API served on 2026-10-10",
                result.url.contains("/kjkatd/master.m3u8"),
            )
            // fragment-based id must be requested on the same origin's API
            val recorded = server.takeRequest()
            assertEquals("/api/v1/video", recorded.url.encodedPath)
            assertEquals("kjkatd", recorded.url.queryParameter("id"))
        }
    }

    @Test
    fun `resolve returns null when the api answers garbage`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("zz-not-valid-hex-zz").build())
            val extractor = UpnshareExtractor(CloudHttp(client()))
            assertNull(extractor.resolve(server.url("/#kjkatd").toString()))
        }
    }

    @Test
    fun `resolve returns null on connection failure`(): Unit = runBlocking {
        val extractor = UpnshareExtractor(CloudHttp(client()))
        assertNull(extractor.resolve("http://127.0.0.1:1/#kjkatd"))
    }
}
