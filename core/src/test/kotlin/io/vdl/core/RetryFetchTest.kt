package io.vdl.core

import io.vdl.core.internal.engine.FetchResult
import io.vdl.core.internal.engine.RetryFetch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression tests for REAL-WORLD bug #4 (found by the live-internet E2E):
 * a transport failure (connection reset / EOF mid-body) used to escape
 * RetryFetch as an uncaught IOException, bypassing the retry budget and
 * crashing the whole engine ("unexpected engine failure: Connection reset").
 *
 * Fix under test: IOException during connect/body-read consumes an attempt
 * with backoff exactly like 429/5xx; CancellationException is rethrown.
 *
 * retryOnConnectionFailure(false) keeps OkHttp's own transparent retry out
 * of the way so the RetryFetch budget is the only retry layer counted.
 */
class RetryFetchTest {

    private lateinit var server: MockWebServer
    private val sink = PrintSink()

    /** Serves a normal body after [resets] socket-level closes; counts calls. */
    private class ResetDispatcher(private val resets: Int) : Dispatcher() {
        val calls = AtomicInteger(0)
        override fun dispatch(request: RecordedRequest): MockResponse {
            val n = calls.incrementAndGet()
            if (n <= resets) {
                return MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Length", "11")
                    .onResponseStart(SocketEffect.CloseSocket())
                    .build()
            }
            return MockResponse.Builder()
                .code(200)
                .body(Buffer().write("hello-world".toByteArray()))
                .build()
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun fetcher(): RetryFetch =
        RetryFetch(
            OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
            testLog(sink),
            "[VDL][TEST][fetch]"
        )

    @Test
    fun connectionResetMidBodyIsRetriedWithinPolicy() = runBlocking {
        val dispatcher = ResetDispatcher(resets = 1)
        server.dispatcher = dispatcher
        val url = server.url("/manifest.m3u8").toString()

        val result = fetcher().text(
            url = url,
            policy = RetryPolicy.Exponential(baseDelayMs = 1, maxAttempts = 3),
            what = "unit"
        )

        assertTrue("expected Text, got $result", result is FetchResult.Text)
        assertEquals("hello-world", (result as FetchResult.Text).body)
        assertEquals("reset must cost exactly one extra call", 2, dispatcher.calls.get())
        assertTrue(
            "retry decision must be logged",
            sink.lines.any { it.contains("fetch io") && it.contains("decision=retry") && it.contains("attempt=0") }
        )
    }

    @Test
    fun ioFailuresExhaustBudgetToTypedRetryableExhausted() = runBlocking {
        val dispatcher = ResetDispatcher(resets = 99) // every call closes
        server.dispatcher = dispatcher
        val url = server.url("/seg.ts").toString()

        val result = fetcher().text(
            url = url,
            policy = RetryPolicy.Exponential(baseDelayMs = 1, maxAttempts = 2),
            what = "unit"
        )

        assertTrue("expected RetryableExhausted, got $result", result is FetchResult.RetryableExhausted)
        assertEquals("budget must be honored", 2, dispatcher.calls.get())
        val reason = (result as FetchResult.RetryableExhausted).reason
        assertTrue("reason must be io-typed, was: $reason", reason.contains("io "))
        assertTrue(
            "giveup must be logged",
            sink.lines.any { it.contains("fetch giveup") && it.contains("attempts=2") }
        )
    }
}
