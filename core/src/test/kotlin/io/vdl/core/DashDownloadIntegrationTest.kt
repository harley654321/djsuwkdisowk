package io.vdl.core

import io.vdl.core.internal.dash.DashDownloader
import io.vdl.core.internal.dash.DashOutcome
import io.vdl.core.internal.logging.VdlLog
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * DASH downloader against MockWebServer: real MPD -> real segments,
 * byte-for-byte video output, throttle-then-retry evidence, DRM refusal.
 * Latency documented via structured logs (dt=ms in every line).
 */
class DashDownloadIntegrationTest {

    private lateinit var server: MockWebServer
    private lateinit var tmp: File
    private lateinit var sink: PrintSink
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tmp = createTempDir("vdl-dash-dl")
        sink = PrintSink()
    }

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdown()
        server.close()
        tmp.deleteRecursively()
    }

    private fun url(p: String) = server.url(p).toString()
    private fun m3u(body: String) = Buffer().writeUtf8(body)
    private fun raw(b: ByteArray) = Buffer().write(b)

    private val videoInit = TestFmp4Fixtures.initStream(trackId = 1, timescale = 1000, handler = "vide", audio = false)
    private val audioInit = TestFmp4Fixtures.initStream(trackId = 2, timescale = 44100, handler = "soun", audio = true)
    private val vSeg = listOf(
        TestFmp4Fixtures.segment(1, 1, 0, randomBytes(400, 1)),
        TestFmp4Fixtures.segment(1, 2, 3600, randomBytes(400, 2)),
        TestFmp4Fixtures.segment(1, 3, 7200, randomBytes(400, 5))
    )
    private val aSeg = listOf(
        TestFmp4Fixtures.segment(2, 1, 0, randomBytes(200, 3)),
        TestFmp4Fixtures.segment(2, 2, 1024, randomBytes(200, 4))
    )

    private fun mpd(durationFixed: Boolean = true, drm: Boolean = false): String = """
        <?xml version="1.0"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT7.2S">
          <Period>
            <AdaptationSet mimeType="video/mp4">
              <SegmentTemplate timescale="1000" duration="3600" startNumber="1"
                               initialization="${'$'}RepresentationID${'$'}/init.mp4"
                               media="${'$'}RepresentationID${'$'}/seg_${'$'}Number${'$'}.m4s"/>
              <Representation id="video/720" bandwidth="1400000" height="720" codecs="avc1.64001f"/>
              <Representation id="video/360" bandwidth="500000" height="360" codecs="avc1.64001e"/>
            </AdaptationSet>
            <AdaptationSet mimeType="audio/mp4" lang="en">
              <SegmentTemplate timescale="1000" duration="3600" startNumber="1"
                               initialization="audio-init.mp4"
                               media="audio-seg_${'$'}Number${'$'}.m4s"/>
              <Representation id="en" bandwidth="128000" codecs="mp4a.40.2"/>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent().let { if (drm) it.replace("<AdaptationSet mimeType=\"video/mp4\">",
        "<AdaptationSet mimeType=\"video/mp4\"><ContentProtection schemeIdUri=\"urn:mpeg:dash:mp4protection:2011\" value=\"cenc\"/>") else it }

    private fun policy() = RetryPolicy.Exponential(baseDelayMs = 10L, maxAttempts = 4)

    private fun downloader(): DashDownloader = DashDownloader(client, testLog(sink))

    @Test
    fun downloadsBothTracksByteForByteAndSelectsUnderCap() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/master.mpd" -> MockResponse.Builder().code(200).body(m3u(mpd())).build()
                "/video/720/init.mp4" -> MockResponse.Builder().code(200).body(raw(videoInit)).build()
                "/video/720/seg_1.m4s" -> MockResponse.Builder().code(200).body(raw(vSeg[0])).build()
                "/video/720/seg_2.m4s" -> MockResponse.Builder().code(200).body(raw(vSeg[1])).build()
                "/video/360/init.mp4" -> MockResponse.Builder().code(200).body(raw(videoInit)).build()
                "/video/360/seg_1.m4s" -> MockResponse.Builder().code(200).body(raw(vSeg[0])).build()
                "/audio-init.mp4" -> MockResponse.Builder().code(200).body(raw(audioInit)).build()
                "/audio-seg_1.m4s" -> MockResponse.Builder().code(200).body(raw(aSeg[0])).build()
                "/audio-seg_2.m4s" -> MockResponse.Builder().code(200).body(raw(aSeg[1])).build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        val out = File(tmp, "v.part")
        val aout = File(tmp, "a.part")
        val oc = downloader().download(url("/master.mpd"), out, maxHeight = 720, policy = policy(), audioOut = aout) {}

        assertTrue("outcome=$oc", oc is DashOutcome.Success)
        val s = oc as DashOutcome.Success
        assertTrue(s.hasSeparateAudio)
        assertEquals(3, s.units) // init + 2 (PT7.2S / 3.6s per seg at 1000 timescale)
        assertEquals(3, s.audioUnits)
        // byte-for-byte: video = init + segments in order
        assertArrayEquals(videoInit + vSeg[0] + vSeg[1], out.readBytes())
        assertArrayEquals(audioInit + aSeg[0] + aSeg[1], aout.readBytes())
        // selection evidence: the 720 rep was chosen, never 360 video URLs
        assertEquals(0, sink.lines.count { it.contains("video/360/seg_") && it.contains("unit done") })
        assertTrue(sink.lines.any { it.contains("selected video=video/720") })
    }

    @Test
    fun retriesThrottledSegmentThenSucceeds() = runBlocking {
        val hits = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.url.encodedPath == "/master.mpd" -> MockResponse.Builder().code(200).body(m3u(mpd())).build()
                request.url.encodedPath == "/video/720/init.mp4" -> MockResponse.Builder().code(200).body(raw(videoInit)).build()
                // 503 + Retry-After: 0 is transparently retried by OkHttp's
                // follow-up interceptor (the app never sees it); 429 is the
                // throttle the downloader itself must handle.
                request.url.encodedPath == "/video/720/seg_1.m4s" && hits.incrementAndGet() == 1 ->
                    MockResponse.Builder().code(429).addHeader("Retry-After", "0").build()
                request.url.encodedPath == "/video/720/seg_1.m4s" -> MockResponse.Builder().code(200).body(raw(vSeg[0])).build()
                request.url.encodedPath == "/video/720/seg_2.m4s" -> MockResponse.Builder().code(200).body(raw(vSeg[1])).build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        val out = File(tmp, "v.part")
        val oc = downloader().download(url("/master.mpd"), out, maxHeight = 720, policy = policy()) {}
        assertTrue("outcome=$oc", oc is DashOutcome.Success)
        assertArrayEquals(videoInit + vSeg[0] + vSeg[1], out.readBytes())
        // retry evidence: one throttle log then a unit-done for the same URL
        assertTrue(sink.lines.any { it.contains("fetch throttle what=video-unit attempt=0 code=429") })
        assertTrue(sink.lines.any { it.contains("unit done kind=video idx=1") })
    }

    @Test
    fun refusesDrmProtectedMpdWithTypedFatal() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(200).body(m3u(mpd(drm = true))).build()
        }
        val out = File(tmp, "v.part")
        val oc = downloader().download(url("/master.mpd"), out, maxHeight = null, policy = policy()) {}
        assertTrue("outcome=$oc", oc is DashOutcome.Fatal)
        assertEquals("DRM-protected content is not supported", (oc as DashOutcome.Fatal).reason)
        assertTrue(sink.lines.any { it.contains("DRM-protected MPD") })
        // no segment URL was ever requested
        assertEquals(1, server.requestCount)
    }
}
