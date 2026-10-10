package io.vdl.cloudkit

import io.vdl.cloudkit.internal.CloudHttp
import io.vdl.cloudkit.internal.VoeExtractor
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Fixture tests for the Voe extractor. The fixtures are REAL captures from
 * the live matrix of 2026-10-09 (core repo x1-2026-10-09): the 755-byte
 * rotating-domain shell and the 159KB player page with its obfuscated
 * sources payload. These tests prove the decryptF7 chain against real
 * bytes, so a recloudstream upstream change breaks here loudly instead
 * of silently at download time.
 *
 * Note: VoeExtractor.resolve is suspend but performs no waiting beyond
 * the MockWebServer localhost calls; runBlocking is acceptable in tests.
 */
class VoeExtractorTest {

    private fun fixture(name: String): String {
        val url = javaClass.classLoader.getResource("fixtures/$name")
            ?: error("fixture missing: $name")
        return File(url.toURI()).readText()
    }

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // ---------------------------------------------------------------- decrypt

    @Test
    fun `decryptF7 recovers hls and mp4 from the real captured payload`() {
        val player = fixture("voe-player-after-redirect.html")
        val encoded = VoeExtractor.JSON_SCRIPT_REGEX.find(player)?.groupValues?.get(1)
        assertNotNull("obfuscated payload present in fixture", encoded)
        val decrypted = VoeExtractor(CloudHttp(client())).decryptF7(encoded!!)
        assertNotNull("real payload decrypts with the live-proven chain", decrypted)
        // Exact values captured live on 2026-10-09 from voe.sx/wodlv7os3van
        assertTrue(decrypted!!.source!!.contains("/engine/hls2"))
        assertTrue(decrypted.directAccessUrl!!.contains("/engine/download"))
    }

    @Test
    fun `regex finds rotating redirect in shell fixture`() {
        val shell = fixture("voe-shell-redirect.html")
        val target = VoeExtractor.REDIRECT_REGEX.find(shell)?.groupValues?.get(1)
        assertEquals(
            "shell redirects to the player domain captured on 2026-10-09",
            "https://teresapoliticallearn.com/wodlv7os3van",
            target,
        )
    }

    // ---------------------------------------------------------------- end-to-end (mock web server)

    @Test
    fun `resolve follows shell redirect then decrypts player to HLS`(): Unit = runBlocking {
        val shell = fixture("voe-shell-redirect.html")
        val player = fixture("voe-player-after-redirect.html")
        mockwebserver3.MockWebServer().use { server ->
            server.start()
            val serverUrl = server.url("/")
            // Rewrite the shell fixture to redirect to THIS mock server so the
            // chain is hermetic: shell -> local player page.
            val localShell = shell.replace(
                "https://teresapoliticallearn.com/wodlv7os3van",
                serverUrl.toString().trimEnd('/') + "/player",
            )
            server.enqueue(mockwebserver3.MockResponse.Builder().body(localShell).build())
            server.enqueue(mockwebserver3.MockResponse.Builder().body(player).build())

            val extractor = VoeExtractor(CloudHttp(client()))
            val result = extractor.resolve(serverUrl.toString().trimEnd('/') + "/wodlv7os3van")

            assertNotNull("voe chain resolves end to end", result)
            assertEquals(CloudKitKind.HLS, result!!.kind)
            assertEquals("Voe", result.extractor)
            assertTrue(
                "master playlist from the real capture",
                result.url.contains("/engine/hls2"),
            )
            // referer must be the player page (post-redirect), not the shell
            assertTrue(result.referer!!.endsWith("/player"))
        }
    }

    @Test
    fun `resolve returns null when player payload is absent`(): Unit = runBlocking {
        mockwebserver3.MockWebServer().use { server ->
            server.start()
            server.enqueue(mockwebserver3.MockResponse.Builder().body("<html><body>nothing</body></html>").build())
            val extractor = VoeExtractor(CloudHttp(client()))
            val result = extractor.resolve(server.url("/").toString())
            assertNull("degraded page yields null, never a throw", result)
        }
    }

    @Test
    fun `resolve returns null on connection failure`(): Unit = runBlocking {
        // port 1 on localhost: connection refused, fast and deterministic
        val extractor = VoeExtractor(CloudHttp(client()))
        val result = extractor.resolve("http://127.0.0.1:1/wodlv7os3van")
        assertNull("unreachable host yields null, never a throw", result)
    }

    @Test
    fun `matches recognizes known mirror domains`() {
        val extractor = VoeExtractor(CloudHttp(client()))
        assertTrue(extractor.matches("https://voe.sx/e/abc123"))
        assertTrue(extractor.matches("https://tubelessceliolymph.com/e/abc"))
        assertTrue(!extractor.matches("https://example.com/e/abc"))
    }
}
