package io.vdl.cloudkit

import kotlinx.coroutines.runBlocking
import io.vdl.cloudkit.internal.CloudHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * LIVE validation (opt-in: run with CLOUDKIT_LIVE=1). Hits the real
 * hosts captured from the user's pages on 2026-10-10:
 *
 *  - mixdrop.ag/f/gjn98o4lbqk06z -> must resolve to the direct CDN mp4
 *    and the CDN must answer the media URL with 200 (full loop);
 *  - doodstream.com/d/cuekdui0sm0b -> Cloudflare-gated for JVM clients,
 *    must degrade to null (typed, never throw);
 *  - dhcplay.com/f/pqe67q5zix6x -> streamwish JS-challenge shell, must
 *    degrade to null (WebView-class).
 *
 * CI never runs this (no network in the runner); it is the evidence of
 * the standing rule "extractor enters only with fixture test + live
 * validation".
 */
class CloudKitLiveTest {

    private fun live(): Boolean = System.getenv("CLOUDKIT_LIVE") == "1"

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Test
    fun `live mixdrop resolves and the CDN serves the media`(): Unit = runBlocking {
        assumeTrue(live())
        val result = CloudKit.resolve("https://mixdrop.ag/f/gjn98o4lbqk06z", client())
        assertNotNull("live mixdrop must resolve (rotating domain mixdrop.ag -> mxdrop.top)", result)
        assertEquals("MixDrop", result!!.extractor)
        assertEquals(CloudKitKind.DIRECT, result.kind)
        assertTrue(result.url.startsWith("https://"))

        // full loop: the CDN must serve what the extractor produced.
        // mxcontent 403s non-browser UAs (probed live 2026-10-10: curl and
        // okhttp UAs -> 403, browser UA -> 200 regardless of referer), so
        // the media check sends the desktop UA exactly as the Android app
        // would when handing the URL to the download engine.
        client().newCall(
            Request.Builder().url(result.url)
                .header("Referer", result.referer ?: "")
                .header("User-Agent", CloudHttp.DESKTOP_UA)
                .build(),
        ).execute().use { resp ->
            assertEquals("media url serves with the extracted referer", 200, resp.code)
        }
    }

    @Test
    fun `live dood degrades to null under cloudflare`(): Unit = runBlocking {
        assumeTrue(live())
        val result = CloudKit.resolve("https://doodstream.com/d/cuekdui0sm0b", client())
        assertNull("Cloudflare-gated dood must degrade to null, never throw", result)
    }

    @Test
    fun `live streamwish shell degrades to null`(): Unit = runBlocking {
        assumeTrue(live())
        val result = CloudKit.resolve("https://dhcplay.com/f/pqe67q5zix6x", client())
        assertNull("shell-challenged streamwish must degrade to null, never throw", result)
    }
}
