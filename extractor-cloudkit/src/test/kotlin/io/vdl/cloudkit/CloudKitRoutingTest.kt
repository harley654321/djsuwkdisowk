package io.vdl.cloudkit

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * CloudKit registry routing tests. A MockWebServer acts as HTTP proxy so
 * a KNOWN mixdrop domain URL is routed through the registry, hits the
 * local server and resolves — proving dispatch without external
 * network (the proxy carries the absolute URI, so no DNS happens for
 * mixdrop.co). Unknown hosts must return null untouched.
 */
class CloudKitRoutingTest {

    private fun fixture(name: String): String {
        val url = javaClass.classLoader.getResource("fixtures/$name")
            ?: error("fixture missing: $name")
        return File(url.toURI()).readText()
    }

    private fun proxiedClient(server: MockWebServer): OkHttpClient {
        val port = server.port
        return OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            .build()
    }

    @Test
    fun `known mixdrop domain is dispatched through the registry`(): Unit = runBlocking {
        val page = fixture("mixdrop-e-real.html")
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(page).build())

            val result = CloudKit.resolve(
                "http://mixdrop.co/f/gjn98o4lbqk06z",
                proxiedClient(server),
            )
            assertNotNull("registry claims the mixdrop domain and resolves", result)
            org.junit.Assert.assertEquals("MixDrop", result!!.extractor)
            org.junit.Assert.assertTrue(result.url.startsWith("https://30xplewoo.mxcontent.net/"))
        }
    }

    @Test
    fun `unknown host returns null without extractor claims`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val result = CloudKit.resolve("https://not-a-known-host.test/e/abc", proxiedClient(server))
            assertNull("no extractor claims unknown domains", result)
        }
    }
}
