package io.vdl.core

import io.vdl.core.internal.hls.Aes128
import io.vdl.core.internal.hls.HlsDownloader
import io.vdl.core.internal.hls.HlsOutcome
import io.vdl.core.internal.hls.HlsPlan
import io.vdl.core.internal.mp4.Fmp4Muxer
import io.vdl.core.internal.mp4.Mp4
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

    // ---------------------------------------------------------- resume

    @Test
    fun resumesOnlyRemainingUnitsAfterInterruptedRun() {
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
        val healthy = AtomicBoolean(false)
        val initHits = AtomicInteger(0)
        val seg1Hits = AtomicInteger(0)
        val seg2Hits = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.url.encodedPath) {
                    "/master.m3u8" -> MockResponse.Builder().code(200).body(m3u(playlist)).build()
                    "/init.mp4" -> { initHits.incrementAndGet(); MockResponse.Builder().code(200).body(Buffer().write(init)).build() }
                    "/media.1.m4s" -> { seg1Hits.incrementAndGet(); MockResponse.Builder().code(200).body(Buffer().write(s1)).build() }
                    "/media.2.m4s" -> {
                        seg2Hits.incrementAndGet()
                        if (healthy.get()) MockResponse.Builder().code(200).body(Buffer().write(s2)).build()
                        else MockResponse.Builder().code(500).build()
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        val out = File(tmp, "video.mp4")
        val ledgerFile = File(tmp, "video.mp4.vdl-units")

        // run 1: the second segment exhausts its attempts -> Retryable
        val r1 = kotlinx.coroutines.runBlocking {
            downloader().download(url("/master.m3u8"), out, maxHeight = null, policy = policy()) { }
        }
        assertTrue("expected retryable but was $r1", r1 is HlsOutcome.Retryable)
        assertTrue("ledger expected after interrupted run", ledgerFile.exists())
        assertEquals(
            "ledger entries: init(idx=0)+seg1(idx=1)",
            listOf("0 0 712", "1 712 40000"),
            ledgerFile.readLines()
        )
        assertArrayEquals("partial file must hold completed units", init + s1, out.readBytes())

        // run 2 (resume): only the missing segment is fetched
        sink.lines.clear()
        healthy.set(true)
        val r2 = kotlinx.coroutines.runBlocking {
            downloader().download(url("/master.m3u8"), out, maxHeight = null, policy = policy(), resume = true) { }
        }
        assertTrue("expected success but was $r2", r2 is HlsOutcome.Success)
        assertEquals("init must NOT be re-fetched", 1, initHits.get())
        assertEquals("seg1 must NOT be re-fetched", 1, seg1Hits.get())
        assertEquals("seg2: 4 failed attempts + 1 resume fetch", 5, seg2Hits.get())
        assertArrayEquals(init + s1 + s2, out.readBytes())
        assertFalse("ledger must be cleared on success", ledgerFile.exists())
        assertTrue(
            "resume evidence log expected",
            sink.lines.any { it.contains("resume kind=video unitsDone=2") }
        )
        assertTrue(
            "unit skip evidence log expected",
            sink.lines.any { it.contains("unit skip kind=video idx=1") }
        )
    }

    @Test
    fun resumeWithStaleLedgerRestartsFromZero() {
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
        val healthy = AtomicBoolean(false)
        val initHits = AtomicInteger(0)
        val seg1Hits = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.url.encodedPath) {
                    "/master.m3u8" -> MockResponse.Builder().code(200).body(m3u(playlist)).build()
                    "/init.mp4" -> { initHits.incrementAndGet(); MockResponse.Builder().code(200).body(Buffer().write(init)).build() }
                    "/media.1.m4s" -> { seg1Hits.incrementAndGet(); MockResponse.Builder().code(200).body(Buffer().write(s1)).build() }
                    "/media.2.m4s" ->
                        if (healthy.get()) MockResponse.Builder().code(200).body(Buffer().write(s2)).build()
                        else MockResponse.Builder().code(500).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        val out = File(tmp, "video.mp4")
        val ledgerFile = File(tmp, "video.mp4.vdl-units")

        // run 1 interrupted: ledger + partial file
        val r1 = kotlinx.coroutines.runBlocking {
            downloader().download(url("/master.m3u8"), out, maxHeight = null, policy = policy()) { }
        }
        assertTrue(r1 is HlsOutcome.Retryable)
        assertTrue(ledgerFile.exists())

        // stale: the output shrank below the ledger range -> ledger must be discarded
        RandomAccessFile(out, "rw").use { it.setLength(100L) }
        healthy.set(true)
        sink.lines.clear()
        val r2 = kotlinx.coroutines.runBlocking {
            downloader().download(url("/master.m3u8"), out, maxHeight = null, policy = policy(), resume = true) { }
        }
        assertTrue("expected success but was $r2", r2 is HlsOutcome.Success)
        assertEquals("init re-fetched after ledger discard", 2, initHits.get())
        assertEquals("seg1 re-fetched after ledger discard", 2, seg1Hits.get())
        assertArrayEquals("full restart must rebuild the file exactly", init + s1 + s2, out.readBytes())
        assertFalse(ledgerFile.exists())
        assertTrue(
            "ledger discard evidence log expected",
            sink.lines.any { it.contains("ledger beyond file") }
        )
    }

}

// -------------------------------------------- separate audio rendition track

class HlsAudioTrackIntegrationTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    private lateinit var tmp: File
    private val sink = PrintSink()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tmp = Files.createTempDirectory("vdl-hls-audio").toFile()
    }

    @After
    fun tearDown() {
        server.close()
        tmp.deleteRecursively()
    }

    private fun downloader(): HlsDownloader = HlsDownloader(
        client = client,
        log = testLog(sink),
        sleeper = { _ -> }
    )

    private fun url(path: String): String = server.url(path).toString()

    private fun policy(): RetryPolicy =
        RetryPolicy.Exponential(baseDelayMs = 5L, maxAttempts = 4, maxDelayMs = 20L)

    @Test
    fun separateAudioRenditionDownloadsBothTracksByteForByte() {
        val videoKey = randomBytes(16, seed = 61)
        val audioKey = randomBytes(16, seed = 62)
        val vInit = randomBytes(700, seed = 63)
        val v1 = randomBytes(30_000, seed = 64)
        val v2 = randomBytes(31_000, seed = 65)
        val aInit = randomBytes(400, seed = 66)
        val a1 = randomBytes(9_000, seed = 67)
        val a2 = randomBytes(9_500, seed = 68)

        // different IVs per track, explicit hex
        val vIv = ByteArray(16) { (it + 1).toByte() }
        val aIv = ByteArray(16) { (it + 101).toByte() }
        val (cv1, cv2) = listOf(v1, v2).map { Aes128.encrypt(it, videoKey, vIv) }
        val (ca1, ca2) = listOf(a1, a2).map { Aes128.encrypt(it, audioKey, aIv) }
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,CHANNELS="2",URI="audio.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,AUDIO="aud"
            video.m3u8
        """.trimIndent()
        val videoPl = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-KEY:METHOD=AES-128,URI="vkey.bin",IV=0x${hex(vIv)}
            #EXT-X-MAP:URI="vinit.mp4"
            #EXTINF:4.0,
            vseg1.m4s
            #EXTINF:4.0,
            vseg2.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        val audioPl = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-KEY:METHOD=AES-128,URI="akey.bin",IV=0x${hex(aIv)}
            #EXT-X-MAP:URI="ainit.mp4"
            #EXTINF:4.0,
            aseg1.m4s
            #EXTINF:4.0,
            aseg2.m4s
            #EXT-X-ENDLIST
        """.trimIndent()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.url.encodedPath) {
                    "/master.m3u8" -> MockResponse.Builder().code(200).body(Buffer().writeUtf8(master)).build()
                    "/video.m3u8" -> MockResponse.Builder().code(200).body(Buffer().writeUtf8(videoPl)).build()
                    "/audio.m3u8" -> MockResponse.Builder().code(200).body(Buffer().writeUtf8(audioPl)).build()
                    "/vkey.bin" -> MockResponse.Builder().code(200).body(Buffer().write(videoKey)).build()
                    "/akey.bin" -> MockResponse.Builder().code(200).body(Buffer().write(audioKey)).build()
                    "/vinit.mp4" -> MockResponse.Builder().code(200).body(Buffer().write(vInit)).build()
                    "/vseg1.m4s" -> MockResponse.Builder().code(200).body(Buffer().write(cv1)).build()
                    "/vseg2.m4s" -> MockResponse.Builder().code(200).body(Buffer().write(cv2)).build()
                    "/ainit.mp4" -> MockResponse.Builder().code(200).body(Buffer().write(aInit)).build()
                    "/aseg1.m4s" -> MockResponse.Builder().code(200).body(Buffer().write(ca1)).build()
                    "/aseg2.m4s" -> MockResponse.Builder().code(200).body(Buffer().write(ca2)).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val video = File(tmp, "video.mp4")
        val audio = File(tmp, "audio.mp4")
        val result = kotlinx.coroutines.runBlocking {
            downloader().download(url("/master.m3u8"), video, policy = policy(), onProgress = { }, audioOut = audio)
        }
        assertTrue("expected success but was $result", result is HlsOutcome.Success)
        val s = result as HlsOutcome.Success
        // video: init is ENCRYPTED under the same key (RFC 8216: MAP shares key)
        assertArrayEquals(Aes128.decrypt(vInit, videoKey, vIv) + v1 + v2, video.readBytes())
        assertArrayEquals(Aes128.decrypt(aInit, audioKey, aIv) + a1 + a2, audio.readBytes())
        assertEquals(3, s.units)          // vinit + 2 segments
        assertEquals(3, s.audioUnits)    // ainit + 2 segments
        assertTrue(s.hasSeparateAudio)
        assertEquals((vInit + v1 + v2).size.toLong(), s.bytesWritten)
        assertEquals((aInit + a1 + a2).size.toLong(), s.audioBytesWritten)
        // evidence: both tracks logged with kind=, audio decision present
        assertTrue(sink.lines.any { it.contains("variant resolved") && it.contains("audio=English") })
        assertTrue(sink.lines.any { it.contains("track done kind=video") })
        assertTrue(sink.lines.any { it.contains("track done kind=audio") })
    }

    @Test
    fun audioRenditionWithoutAudioOutIsSkippedWithWarning() {
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:2
            #EXTINF:2.0,
            seg.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",DEFAULT=YES,URI="audio.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720,AUDIO="aud"
            media.m3u8
        """.trimIndent()
        val seg = randomBytes(4_096, seed = 71)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.url.encodedPath) {
                    "/master.m3u8" -> MockResponse.Builder().code(200).body(Buffer().writeUtf8(master)).build()
                    "/media.m3u8" -> MockResponse.Builder().code(200).body(Buffer().writeUtf8(media)).build()
                    "/seg.ts" -> MockResponse.Builder().code(200).body(Buffer().write(seg)).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        val video = File(tmp, "video.ts")
        val result = kotlinx.coroutines.runBlocking {
            downloader().download(url("/master.m3u8"), video, policy = policy(), onProgress = { })
        }
        assertTrue(result is HlsOutcome.Success)
        val s = result as HlsOutcome.Success
        assertTrue(!s.hasSeparateAudio)
        assertEquals(0, s.audioUnits)
        assertTrue(sink.lines.any { it.contains("no audioOut decision=skip") })
    }

    @Test
    fun audioPlaylistFetchFailureIsFatalAfterVideoSucceeded() {
        val videoPl = """
            #EXTM3U
            #EXT-X-TARGETDURATION:2
            #EXTINF:2.0,
            seg.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",DEFAULT=YES,URI="audio.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720,AUDIO="aud"
            video.m3u8
        """.trimIndent()
        val seg = randomBytes(4_096, seed = 81)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.url.encodedPath) {
                    "/master.m3u8" -> MockResponse.Builder().code(200).body(Buffer().writeUtf8(master)).build()
                    "/video.m3u8" -> MockResponse.Builder().code(200).body(Buffer().writeUtf8(videoPl)).build()
                    "/seg.ts" -> MockResponse.Builder().code(200).body(Buffer().write(seg)).build()
                    else -> MockResponse.Builder().code(404).build() // audio.m3u8 404
                }
            }
        }
        val video = File(tmp, "video.ts")
        val audio = File(tmp, "audio.mp4")
        val result = kotlinx.coroutines.runBlocking {
            downloader().download(url("/master.m3u8"), video, policy = policy(), onProgress = { }, audioOut = audio)
        }
        // audio fetch failed: the whole outcome is fatal, video file remains but is not reported
        assertTrue("expected fatal but was $result", result is HlsOutcome.Fatal)
        assertEquals(404, (result as HlsOutcome.Fatal).httpCode)
        assertTrue(sink.lines.any { it.contains("fetch fatal") && it.contains("audio-media") })
    }
}

// ------------------------------------------------ HLS download -> MP4 mux

class HlsToMuxPipelineIntegrationTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    private lateinit var tmp: File
    private val sink = PrintSink()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tmp = Files.createTempDirectory("vdl-pipe").toFile()
    }

    @After
    fun tearDown() {
        server.close()
        tmp.deleteRecursively()
    }

    /**
     * Full pipeline evidence: an HLS origin serves fMP4 init + segments
     * for video and audio; the downloader fetches and concatenates both
     * tracks; the muxer merges them into ONE fragmented MP4.
     */
    @Test
    fun hlsDownloadThenMuxProducesSingleFileWithBothTracks() {
        // video: track 1, fragments at decode time 0 / 1000
        val vPayloads = listOf(randomBytes(1000, seed = 91), randomBytes(1100, seed = 92))
        val aPayloads = listOf(randomBytes(500, seed = 93), randomBytes(550, seed = 94))
        val videoInit = TestFmp4Fixtures.initStream(1, 1000, "vide", audio = false)
        val audioInit = TestFmp4Fixtures.initStream(1, 44_100, "soun", audio = true)
        val vSegs = listOf(
            TestFmp4Fixtures.segment(1, 0, 0L, vPayloads[0]),
            TestFmp4Fixtures.segment(1, 1, 1000L, vPayloads[1])
        )
        val aSegs = listOf(
            TestFmp4Fixtures.segment(1, 0, 0L, aPayloads[0]),
            TestFmp4Fixtures.segment(1, 1, 960L, aPayloads[1])
        )

        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",DEFAULT=YES,URI="audio.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,AUDIO="aud"
            video.m3u8
        """.trimIndent()
        val videoPl = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-MAP:URI="vinit.mp4"
            #EXTINF:4.0,
            v0.m4s
            #EXTINF:4.0,
            v1.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        val audioPl = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-MAP:URI="ainit.mp4"
            #EXTINF:4.0,
            a0.m4s
            #EXTINF:4.0,
            a1.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.url.encodedPath) {
                    "/master.m3u8" -> MockResponse.Builder().code(200).body(Buffer().writeUtf8(master)).build()
                    "/video.m3u8" -> MockResponse.Builder().code(200).body(Buffer().writeUtf8(videoPl)).build()
                    "/audio.m3u8" -> MockResponse.Builder().code(200).body(Buffer().writeUtf8(audioPl)).build()
                    "/vinit.mp4" -> MockResponse.Builder().code(200).body(Buffer().write(videoInit)).build()
                    "/ainit.mp4" -> MockResponse.Builder().code(200).body(Buffer().write(audioInit)).build()
                    "/v0.m4s" -> MockResponse.Builder().code(200).body(Buffer().write(vSegs[0])).build()
                    "/v1.m4s" -> MockResponse.Builder().code(200).body(Buffer().write(vSegs[1])).build()
                    "/a0.m4s" -> MockResponse.Builder().code(200).body(Buffer().write(aSegs[0])).build()
                    "/a1.m4s" -> MockResponse.Builder().code(200).body(Buffer().write(aSegs[1])).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        // 1. HLS download: both tracks concatenated (styp dropped later by mux)
        val video = File(tmp, "video.mp4")
        val audio = File(tmp, "audio.mp4")
        val dl = kotlinx.coroutines.runBlocking {
            HlsDownloader(client, testLog(sink), sleeper = { _ -> }).download(
                url("/master.m3u8"), video, policy = RetryPolicy.Exponential(baseDelayMs = 5L, maxAttempts = 4), audioOut = audio
            ) { }
        }
        assertTrue("hls download failed: $dl", dl is HlsOutcome.Success)
        assertEquals(3, (dl as HlsOutcome.Success).units)
        assertEquals(3, dl.audioUnits)

        // 2. mux both concatenated streams into one fragmented MP4
        val muxLog = testLog(PrintSink())
        val single = File(tmp, "final.mp4")
        val muxResult = Fmp4Muxer.muxFiles(video, audio, single, muxLog)
        assertEquals(4, muxResult.videoFragments + muxResult.audioFragments)
        assertEquals(1L, muxResult.videoTrackId)
        assertEquals(2L, muxResult.audioTrackId)

        // 3. verify the single file: structure + interleaving + payloads
        val out = single.readBytes()
        val boxes = Mp4.readBoxes(out)
        assertEquals(listOf("ftyp", "moov", "moof", "mdat", "moof", "mdat", "moof", "mdat", "moof", "mdat"),
            boxes.map { it.type })
        val moovChildren = Mp4.readBoxes(boxes[1].body).map { it.type }
        assertEquals(listOf("mvhd", "trak", "trak", "mvex"), moovChildren)
        // fragment order by tfdt: v(0), a(0), a(960), v(1000)
        var moofIdx = 2
        val expect = listOf(
            Triple(1L, 0L, vPayloads[0]), Triple(2L, 0L, aPayloads[0]),
            Triple(2L, 960L, aPayloads[1]), Triple(1L, 1000L, vPayloads[1])
        )
        for (e in expect) {
            val moof = boxes[moofIdx]
            val children = Mp4.readBoxes(moof.body)
            val traf = Mp4.readBoxes(children.first { it.type == "traf" }.body)
            val tfhd = traf.first { it.type == "tfhd" }
            val tfdt = traf.first { it.type == "tfdt" }
            assertEquals(e.first, Mp4.readU32(tfhd.body, 4))
            assertEquals(e.second, Mp4.readU32(tfdt.body, 4))
            assertArrayEquals(e.third, boxes[moofIdx + 1].body)
            moofIdx += 2
        }
        // every styp from the HLS segments was dropped in the final file
        assertTrue(Mp4.readBoxes(out).none { it.type == "styp" })
    }

    private fun url(path: String): String = server.url(path).toString()
}
