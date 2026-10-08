package io.vdl.core

import io.vdl.core.internal.hls.Aes128
import io.vdl.core.internal.hls.HlsDownloader
import io.vdl.core.internal.hls.HlsOutcome
import io.vdl.core.internal.hls.HlsPlan
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Cipher

/**
 * Real-socket HLS integration evidence: a mock origin serves
 * master -> media playlist -> AES-128 key -> CTR-encrypted TS segments,
 * and the orchestrator must reproduce the plaintext byte for byte,
 * retrying throttles, logging every decision.
 */
class HlsDownloadIntegrationTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    private lateinit var tmp: File
    internal val sink = PrintSink()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tmp = Files.createTempDirectory("vdl-hls").toFile()
    }

    @After
    fun tearDown() {
        server.close()
        tmp.deleteRecursively()
    }

    private fun downloader(): HlsDownloader = HlsDownloader(
        client = client,
        log = testLog(sink),
        sleeper = { _ -> } // instant retry: tests stay fast
    )

    private fun url(path: String): String = server.url(path).toString()

    private fun policy(): RetryPolicy =
        RetryPolicy.Exponential(baseDelayMs = 5L, maxAttempts = 4, maxDelayMs = 20L)

    // ------------------------------------------------------------- helpers

    private fun m3u(body: String): Buffer = Buffer().writeUtf8(body)

    private fun aes(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray =
        Aes128.encrypt(plain, key, iv)

    // ------------------------------------------------------ fMP4, no crypto

    @Test
    fun downloadsFmp4InitPlusSegmentsConcatenated() {
        val init = randomBytes(712, seed = 1)
        val s1 = randomBytes(40_000, seed = 2)
        val s2 = randomBytes(41_000, seed = 3)
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4.0,
            media.1.m4s
            #EXTINF:4.0,
            media.2.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.url.encodedPath) {
                    "/master.m3u8" -> MockResponse.Builder().code(200).body(m3u(playlist)).build()
                    "/init.mp4" -> MockResponse.Builder().code(200).body(Buffer().write(init)).build()
                    "/media.1.m4s" -> MockResponse.Builder().code(200).body(Buffer().write(s1)).build()
                    "/media.2.m4s" -> MockResponse.Builder().code(200).body(Buffer().write(s2)).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        val out = File(tmp, "video.mp4")
        val result = kotlinx.coroutines.runBlocking {
            downloader().download(url("/master.m3u8"), out, maxHeight = null, policy = policy()) { }
        }
        assertTrue("expected success but was $result", result is HlsOutcome.Success)
        val success = result as HlsOutcome.Success
        assertEquals(3, success.units)
        assertEquals((init + s1 + s2).size.toLong(), success.bytesWritten)
        assertArrayEquals(init + s1 + s2, out.readBytes())
        assertTrue(sink.lines.any { it.contains("hls done") && it.contains("units=3") })
    }

    // -------------------------------------------------- AES-128 TS, master

    @Test
    fun downloadsAes128TsPlaylistByteForByte() {
        val key = randomBytes(16, seed = 11)
        val ivHex = "0x11223344556677889900aabbccddeeff"
        // explicit IV from the playlist must be honored
        val hexIv = ivHex.removePrefix("0x")
        val iv = ByteArray(16) { i -> hexIv.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
        val plain = randomBytes(3 * 32_768, seed = 12)
        val (p1, p2, p3) = listOf(
            plain.copyOfRange(0, 32_768),
            plain.copyOfRange(32_768, 65_536),
            plain.copyOfRange(65_536, 98_304)
        )
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
        val (c1, c2, c3) = listOf(p1, p2, p3).map { cipher.doFinal(it) }

        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720
            media.m3u8
        """.trimIndent()
        val keyHits = java.util.concurrent.atomic.AtomicInteger()
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=$ivHex
            #EXTINF:4.0,
            seg0.ts
            #EXTINF:4.0,
            seg1.ts
            #EXTINF:4.0,
            seg2.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.url.encodedPath) {
                    "/master.m3u8" -> MockResponse.Builder().code(200).body(m3u(master)).build()
                    "/media.m3u8" -> MockResponse.Builder().code(200).body(m3u(media)).build()
                    "/key.bin" -> {
                        keyHits.incrementAndGet()
                        MockResponse.Builder().code(200).body(Buffer().write(key)).build()
                    }
                    "/seg0.ts" -> MockResponse.Builder().code(200).body(Buffer().write(c1)).build()
                    "/seg1.ts" -> MockResponse.Builder().code(200).body(Buffer().write(c2)).build()
                    "/seg2.ts" -> MockResponse.Builder().code(200).body(Buffer().write(c3)).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        val out = File(tmp, "video.ts")
        val result = kotlinx.coroutines.runBlocking {
            downloader().download(url("/master.m3u8"), out, maxHeight = 720, policy = policy()) { }
        }
        assertTrue("expected success but was $result", result is HlsOutcome.Success)
        assertArrayEquals(plain, out.readBytes())
        assertEquals("720p/800000bps", (result as HlsOutcome.Success).variant)
        assertEquals(1, keyHits.get()) // key fetched exactly once
        // evidence: every segment decrypted against the same explicit IV
        assertTrue(sink.lines.none { it.contains("fetch throttle") })
    }

    // --------------------------------------------------- retry on 500 once

    @Test
    fun retriesThrottledSegmentThenSucceeds() {
        val seg = randomBytes(8_192, seed = 21)
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:2
            #EXTINF:2.0,
            seg0.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val hits = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.url.encodedPath) {
                    "/media.m3u8" -> MockResponse.Builder().code(200).body(m3u(playlist)).build()
                    "/seg0.ts" -> {
                        // 503 is transparently retried by OkHttp's follow-up
                        // interceptor (Retry-After: 0), so the app never sees
                        // it; 429 is the throttle the downloader must handle.
                        if (hits.incrementAndGet() == 1) {
                            MockResponse.Builder().code(429)
                                .addHeader("Retry-After", "0")
                                .build()
                        } else {
                            MockResponse.Builder().code(200).body(Buffer().write(seg)).build()
                        }
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        val out = File(tmp, "video.ts")
        val result = kotlinx.coroutines.runBlocking {
            downloader().download(url("/media.m3u8"), out, policy = policy()) { }
        }
        assertTrue("expected success but was $result", result is HlsOutcome.Success)
        assertArrayEquals(seg, out.readBytes())
        // evidence in logs: one throttle + one retry that succeeds
        assertTrue(sink.lines.any { it.contains("fetch throttle") && it.contains("code=429") })
        assertEquals(2, hits.get())
    }

    // ---------------------------------------------------- fatal: bad media

    @Test
    fun missingMediaPlaylistIsFatal() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000
            media.m3u8
        """.trimIndent()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.url.encodedPath == "/master.m3u8") {
                    MockResponse.Builder().code(200).body(m3u(master)).build()
                } else {
                    MockResponse.Builder().code(404).build()
                }
            }
        }
        val out = File(tmp, "video.ts")
        val result = kotlinx.coroutines.runBlocking {
            downloader().download(url("/master.m3u8"), out, policy = policy()) { }
        }
        assertTrue(result is HlsOutcome.Fatal)
        assertEquals(404, (result as HlsOutcome.Fatal).httpCode)
        assertTrue(sink.lines.any { it.contains("fetch fatal") })
    }

    // ------------------------------------------------- derived IV (no IV tag)

    @Test
    fun derivedIvFromMediaSequenceDecryptsCorrectly() {
        val key = randomBytes(16, seed = 31)
        // playlist has NO IV: downloader must derive seq=media-sequence IV
        val mediaSequence = 100L
        val iv = HlsPlan.sequenceIv(mediaSequence)
        val p1 = randomBytes(16_384, seed = 32)
        val p2 = randomBytes(16_384, seed = 33)
        // each segment derives ITS OWN sequence IV (seg100->100, seg101->101)
        val iv100 = HlsPlan.sequenceIv(mediaSequence)
        val iv101 = HlsPlan.sequenceIv(mediaSequence + 1)
        val (c1, c2) = listOf(aes(key, iv100, p1), aes(key, iv101, p2))
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:$mediaSequence
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:4.0,
            seg100.ts
            #EXTINF:4.0,
            seg101.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.url.encodedPath) {
                    "/media.m3u8" -> MockResponse.Builder().code(200).body(m3u(media)).build()
                    "/key.bin" -> MockResponse.Builder().code(200).body(Buffer().write(key)).build()
                    "/seg100.ts" -> MockResponse.Builder().code(200).body(Buffer().write(c1)).build()
                    "/seg101.ts" -> MockResponse.Builder().code(200).body(Buffer().write(c2)).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        val out = File(tmp, "video.ts")
        val result = kotlinx.coroutines.runBlocking {
            downloader().download(url("/media.m3u8"), out, policy = policy()) { }
        }
        assertTrue("expected success but was $result", result is HlsOutcome.Success)
        assertArrayEquals(p1 + p2, out.readBytes())
    }

    // ---------------------------------------------------- bad key size fatal

    @Test
    fun badKeySizeIsFatalWithEvidence() {
        val badKey = randomBytes(15, seed = 41)
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:2
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:2.0,
            seg0.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.url.encodedPath) {
                    "/media.m3u8" -> MockResponse.Builder().code(200).body(m3u(media)).build()
                    "/key.bin" -> MockResponse.Builder().code(200).body(Buffer().write(badKey)).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        val out = File(tmp, "video.ts")
        val result = kotlinx.coroutines.runBlocking {
            downloader().download(url("/media.m3u8"), out, policy = policy()) { }
        }
        assertTrue("expected fatal but was $result", result is HlsOutcome.Fatal)
        assertTrue((result as HlsOutcome.Fatal).reason.contains("16 bytes"))
        assertTrue(sink.lines.any { it.contains("key size != 16") })
    }

    // ------------------------------------------------------ byterange units

    @Test
    fun byterangeSegmentsDownloadCorrectSlices() {
        val blob = randomBytes(64_000, seed = 51)
        val slice1 = blob.copyOfRange(712, 712 + 40_000)
        val slice2 = blob.copyOfRange(712 + 40_000, 712 + 40_000 + (64_000 - 712 - 40_000))
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXTINF:4.0,
            #EXT-X-BYTERANGE:40000@712
            blob.bin
            #EXTINF:4.0,
            #EXT-X-BYTERANGE:${slice2.size}
            blob.bin
            #EXT-X-ENDLIST
        """.trimIndent()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.url.encodedPath) {
                    "/media.m3u8" -> MockResponse.Builder().code(200).body(m3u(playlist)).build()
                    "/blob.bin" -> {
                        val range = request.headers["Range"] ?: return MockResponse.Builder().code(200).body(Buffer().write(blob)).build()
                        // parse bytes=start-end
                        val m = Regex("bytes=(\\d+)-(\\d*)").find(range)!!
                        val start = m.groupValues[1].toLong()
                        val end = if (m.groupValues[2].isEmpty()) blob.size - 1L else m.groupValues[2].toLong()
                        val slice = blob.copyOfRange(start.toInt(), end.toInt() + 1)
                        MockResponse.Builder().code(206)
                            .addHeader("Content-Range", "bytes $start-${end}/${blob.size}")
                            .body(Buffer().write(slice))
                            .build()
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        val out = File(tmp, "video.bin")
        val result = kotlinx.coroutines.runBlocking {
            downloader().download(url("/media.m3u8"), out, policy = policy()) { }
        }
        assertTrue("expected success but was $result", result is HlsOutcome.Success)
        assertArrayEquals(slice1 + slice2, out.readBytes())
    }
}
