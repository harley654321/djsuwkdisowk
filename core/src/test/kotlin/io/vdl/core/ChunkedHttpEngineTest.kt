package io.vdl.core

import io.vdl.core.internal.engine.ChunkedHttpEngine
import io.vdl.core.internal.engine.ChunkCodec
import io.vdl.core.internal.engine.EngineOutcome
import io.vdl.core.internal.engine.EngineState
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

class ChunkedHttpEngineTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    private lateinit var tmp: File
    private var lastProgress: io.vdl.core.Progress? = null
    private val sink = PrintSink()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tmp = Files.createTempDirectory("vdl-test").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        tmp.deleteRecursively()
    }

    private fun engine(): ChunkedHttpEngine = ChunkedHttpEngine(
        client = client,
        log = testLog(sink),
        progressIntervalMs = 10,
        sleeper = { _ -> }, // instant retry: tests stay fast
        clockNanos = { System.nanoTime() }
    )

    private fun partFile(): File = File.createTempFile("part", ".bin", tmp)

    private fun url(): String = server.url("/file.bin").toString()

    private fun onProgress(): (io.vdl.core.Progress) -> Unit = { lastProgress = it }

    @Test
    fun multiChunkDownloadMatchesSourceByteForByte() {
        val data = randomBytes(3 * 1024 * 1024, seed = 7)
        val dispatcher = RangeDispatcher(data)
        server.dispatcher = dispatcher
        val part = partFile()
        val outcome = runBlocking {
            engine().execute(
                makeEntity(url(), threads = 4, chunkSizeBytes = 256L * 1024),
                part,
                EngineState(),
                onProgress()
            )
        }
        assertEquals(EngineOutcome.Success(data.size.toLong()), outcome)
        assertEquals(data.size.toLong(), part.length())
        assertArrayEquals(data, part.readBytes())
        assertTrue("expected parallel range requests, got ${dispatcher.rangeRequests.get()}", dispatcher.rangeRequests.get() > 4)
        assertTrue(lastProgress != null)
        assertTrue(sink.lines.any { it.contains("chunked start") })
    }

    @Test
    fun noRangeSupportFallsBackToSingleStream() {
        val data = randomBytes(512 * 1024, seed = 9)
        val dispatcher = RangeDispatcher(data, supportRanges = false)
        server.dispatcher = dispatcher
        val part = partFile()
        val outcome = runBlocking {
            engine().execute(
                makeEntity(url(), threads = 4, chunkSizeBytes = 256L * 1024),
                part,
                EngineState(),
                onProgress()
            )
        }
        assertTrue(outcome is EngineOutcome.Success)
        assertArrayEquals(data, part.readBytes())
        assertEquals(0, dispatcher.rangeRequests.get())
    }

    @Test
    fun resumeSkipsCompletedChunks() {
        val data = randomBytes(1024 * 1024, seed = 11)
        val dispatcher = RangeDispatcher(data)
        server.dispatcher = dispatcher
        val part = partFile()
        // pre-write the first 256KB chunk
        FileOutputStream(part).use { it.write(data, 0, 256 * 1024) }
        val state = EngineState().apply {
            bytesTotal = data.size.toLong()
            acceptRanges = true
            etag = "\"v1\""
            chunks = ChunkCodec.decode("0-${256 * 1024 - 1}-${256 * 1024}")
        }
        val entity = makeEntity(
            url(), threads = 4, chunkSizeBytes = 256L * 1024,
            bytesTotal = data.size.toLong(),
            acceptRanges = true,
            etag = "\"v1\"",
            chunksEnc = "0-${256 * 1024 - 1}-${256 * 1024}",
            bytesDownloaded = 256L * 1024
        )
        val outcome = runBlocking { engine().execute(entity, part, state, onProgress()) }
        assertTrue(outcome is EngineOutcome.Success)
        assertArrayEquals(data, part.readBytes())
        // 3 remaining chunks of 256KB + probe skipped (chunks present)
        assertEquals(3, dispatcher.rangeRequests.get())
    }

    @Test
    fun changedResourceWithIfRangeRestartsAndEventuallyFailsCleanly() {
        val data = randomBytes(700 * 1024, seed = 13)
        val dispatcher = RangeDispatcher(data)
        dispatcher.failFirstRange = Int.MAX_VALUE // every range request returns 200
        server.dispatcher = dispatcher
        val part = partFile()
        val state = EngineState().apply {
            bytesTotal = data.size.toLong()
            acceptRanges = true
            etag = "\"v1\""
            chunks = ChunkPlanForTest(data.size.toLong())
        }
        val outcome = runBlocking {
            engine().execute(
                makeEntity(url(), bytesTotal = data.size.toLong(), acceptRanges = true, etag = "\"v1\""),
                part,
                state,
                onProgress()
            )
        }
        assertTrue(outcome is EngineOutcome.Fatal)
        assertTrue(sink.lines.any { it.contains("resource changed") })
    }

    @Test
    fun throttled429RetriesThenSucceeds() {
        val data = randomBytes(300 * 1024, seed = 15)
        val dispatcher = RangeDispatcher(data)
        dispatcher.throttles = 2
        server.dispatcher = dispatcher
        val part = partFile()
        val outcome = runBlocking {
            engine().execute(
                makeEntity(url(), threads = 4, chunkSizeBytes = 256L * 1024),
                part,
                EngineState(),
                onProgress()
            )
        }
        assertTrue(outcome is EngineOutcome.Success)
        assertArrayEquals(data, part.readBytes())
        assertTrue(sink.lines.any { it.contains("retry attempt=") })
    }

    @Test
    fun socketDisconnectRetriesThenSucceeds() {
        val data = randomBytes(300 * 1024, seed = 17)
        val dispatcher = RangeDispatcher(data)
        dispatcher.disconnects = 1
        server.dispatcher = dispatcher
        val part = partFile()
        val outcome = runBlocking {
            engine().execute(
                makeEntity(url(), threads = 4, chunkSizeBytes = 256L * 1024),
                part,
                EngineState(),
                onProgress()
            )
        }
        assertTrue(outcome is EngineOutcome.Success)
        assertArrayEquals(data, part.readBytes())
    }

    @Test
    fun missingResourceIsFatal() {
        server.enqueue(
            okhttp3.mockwebserver.MockResponse().setResponseCode(404)
        )
        server.enqueue(
            okhttp3.mockwebserver.MockResponse().setResponseCode(404)
        )
        val outcome = run(makeEntity(url()))
        assertTrue(outcome is EngineOutcome.Fatal)
        assertEquals(404, (outcome as EngineOutcome.Fatal).httpCode)
    }

    @Test
    fun unknownTotalLengthStreamsToEof() {
        val data = randomBytes(400 * 1024, seed = 19)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                return if (request.method == "HEAD") {
                    okhttp3.mockwebserver.MockResponse().setResponseCode(200)
                        .setHeader("Accept-Ranges", "none")
                } else {
                    okhttp3.mockwebserver.MockResponse().setResponseCode(200)
                        .setHeader("Accept-Ranges", "none")
                        .setChunkedBody(okio.Buffer().write(data), 16 * 1024)
                }
            }
        }
        val part = partFile()
        val outcome = runBlocking {
            engine().execute(
                makeEntity(url(), threads = 4, chunkSizeBytes = 256L * 1024),
                part,
                EngineState(),
                onProgress()
            )
        }
        assertTrue(outcome is EngineOutcome.Success)
        assertEquals(data.size.toLong(), (outcome as EngineOutcome.Success).bytesTotal)
        assertArrayEquals(data, part.readBytes())
    }

    @Test
    fun allRetriesExhaustedGivesUpWithRetryable() {
        val dispatcher = RangeDispatcher(randomBytes(300 * 1024, seed = 21))
        dispatcher.disconnects = Int.MAX_VALUE
        server.dispatcher = dispatcher
        val outcome = run(
            makeEntity(url(), retryBaseDelayMs = 1, retryMaxAttempts = 2)
        )
        assertTrue(outcome is EngineOutcome.Retryable)
        assertTrue(sink.lines.any { it.contains("decision=fatal") || it.contains("attempts=2") })
    }
}

internal fun ChunkPlanForTest(total: Long): List<ChunkProgress> =
    io.vdl.core.internal.engine.ChunkPlan.plan(total, 256L * 1024, 4)
