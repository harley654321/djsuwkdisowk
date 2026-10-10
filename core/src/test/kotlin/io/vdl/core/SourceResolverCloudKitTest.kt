package io.vdl.core

import io.vdl.core.internal.extract.QuickJsEngine
import io.vdl.core.internal.extract.SourceResolver
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Core <-> CloudKit integration: known embed hosts must be claimed by
 * the host-aware extractor family BEFORE the generic fetch/scan/js paths
 * run, and the result must carry the referer the CDN expects.
 *
 * The MockWebServer acts as HTTP proxy for the real mixdrop.co domain
 * (a plain-http absolute URI rides the proxy, so no DNS/network happens;
 * https would require CONNECT tunneling the mock does not do), serving
 * the REAL embed page captured from mxdrop.top 2026-10-10.
 */
class SourceResolverCloudKitTest {

    private fun fixture(name: String): String {
        val url = javaClass.classLoader.getResource("fixtures/$name")
            ?: error("fixture missing: $name")
        return File(url.toURI()).readText()
    }

    private fun proxiedClient(server: MockWebServer): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", server.port)))
            .build()

    private fun resolver(client: OkHttpClient) =
        SourceResolver(client, testLog()) { QuickJsEngine(testLog()) }

    @Test
    fun `known mixdrop embed resolves through cloudkit with referer`(): Unit = runBlocking {
        val page = fixture("mixdrop-e-real.html")
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(page).build())

            val outcome = resolver(proxiedClient(server))
                .resolve("http://mixdrop.co/f/gjn98o4lbqk06z")

            val source = (outcome as ResolveOutcome.Success).source
            assertEquals("cloudkit:MixDrop", source.origin)
            assertEquals(SourceKind.DIRECT, source.kind)
            assertTrue(source.url.startsWith("https://"))
            assertEquals("http://mixdrop.co/f/gjn98o4lbqk06z", source.referer)

            // Exactly one network hit: the embed page, claimed by cloudkit
            // before the generic fetcher could add its own.
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `unknown host falls through to the generic path untouched`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            // The generic path fetches the page; the server answers an
            // empty 200 so the resolver finishes as Fatal (no media found).
            server.enqueue(
                MockResponse.Builder()
                    .addHeader("Content-Type", "text/html")
                    .body("<html><title>nothing</title></html>")
                    .build(),
            )

            val outcome = resolver(proxiedClient(server))
                .resolve("http://not-a-known-host.test/e/abc")

            val fatal = outcome as ResolveOutcome.Fatal
            // No media anywhere on an empty page: typed Fatal, never thrown.
            assertTrue(fatal.error is DownloadError.InvalidRequest)
            assertEquals(1, server.requestCount)
        }
    }
}
