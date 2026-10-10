package io.vdl.core

import io.vdl.core.internal.db.DownloadTaskEntity
import io.vdl.core.internal.db.TaskRepository
import io.vdl.core.internal.db.TaskState
import io.vdl.core.internal.hls.HlsQueueEngine
import io.vdl.core.internal.queue.NetworkGate
import io.vdl.core.internal.queue.PartFileFactory
import io.vdl.core.internal.queue.PartPublisher
import io.vdl.core.internal.queue.PublishedResult
import io.vdl.core.internal.queue.QueueManager
import io.vdl.core.internal.queue.SpaceChecker
import io.vdl.core.internal.logging.VdlLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * End-to-end queue integration for HLS tasks: a REAL HlsQueueEngine behind
 * the REAL QueueManager against MockWebServer - master playlist with a
 * separate audio rendition, both tracks downloaded, atom-muxed into ONE
 * part file, published by the queue, scratch cleaned, COMPLETED exact.
 * Plus the not-configured fail-fast and the TS+audio degrade path.
 */
class QueueHlsIntegrationTest {

    private lateinit var server: MockWebServer
    private lateinit var tmp: File
    private lateinit var sink: PrintSink
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tmp = createTempDir("vdl-hls-queue")
        sink = PrintSink()
    }

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdown()
        server.close()
        tmp.deleteRecursively()
    }

    private fun url(path: String) = server.url(path).toString()

    // ------------------------------------------------------------- fixtures

    private val videoInit = TestFmp4Fixtures.initStream(trackId = 1, timescale = 1000, handler = "vide", audio = false)
    private val audioInit = TestFmp4Fixtures.initStream(trackId = 2, timescale = 44100, handler = "soun", audio = true)
    private val vSeg1 = TestFmp4Fixtures.segment(1, 1, 0, randomBytes(400, 1))
    private val vSeg2 = TestFmp4Fixtures.segment(1, 2, 3600, randomBytes(400, 2))
    private val aSeg1 = TestFmp4Fixtures.segment(2, 1, 0, randomBytes(200, 3))
    private val aSeg2 = TestFmp4Fixtures.segment(2, 2, 1024, randomBytes(200, 4))

    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=1280x720,AUDIO="aud"
        video.m3u8
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",URI="audio.m3u8"
    """.trimIndent()

    private val videoMedia = """
        #EXTM3U
        #EXT-X-TARGETDURATION:4
        #EXT-X-MEDIA-SEQUENCE:0
        #EXT-X-MAP:URI="init.mp4"
        #EXTINF:3.6,
        seg/v1.m4s
        #EXTINF:3.6,
        seg/v2.m4s
        #EXT-X-ENDLIST
    """.trimIndent()

    private val audioMedia = """
        #EXTM3U
        #EXT-X-TARGETDURATION:4
        #EXT-X-MEDIA-SEQUENCE:0
        #EXT-X-MAP:URI="audio-init.mp4"
        #EXTINF:3.6,
        seg/a1.m4s
        #EXTINF:3.6,
        seg/a2.m4s
        #EXT-X-ENDLIST
    """.trimIndent()

    // ------------------------------------------------------------- harness

    private class Repo : TaskRepository {
        private val flow = MutableStateFlow<List<DownloadTaskEntity>>(emptyList())
        private val current get() = flow.value
        private val mu = Mutex()

        override suspend fun add(task: DownloadTaskEntity): Boolean = mu.withLock {
            if (current.any { it.url == task.url && it.fileName == task.fileName }) false
            else { flow.value = current + task; true }
        }

        override suspend fun update(task: DownloadTaskEntity) = mu.withLock {
            flow.value = current.map { if (it.id == task.id) task else it }
        }

        override suspend fun get(id: String): DownloadTaskEntity? = current.firstOrNull { it.id == id }

        override suspend fun delete(id: String) = mu.withLock { flow.value = current.filterNot { it.id == id } }

        override suspend fun clearCompleted(): Int = 0
        override fun observe(id: String): Flow<DownloadTaskEntity?> =
            flow.map { list -> list.firstOrNull { it.id == id } }.distinctUntilChanged()

        override fun observeAll(): Flow<List<DownloadTaskEntity>> = flow
        override suspend fun pendingOrdered(): List<DownloadTaskEntity> =
            current.filter { it.state == TaskState.PENDING.name }.sortedByDescending { it.priorityEnum().ordinal }

        override suspend fun runningOrphans(): List<DownloadTaskEntity> = emptyList()
        override suspend fun activeTasks(): List<DownloadTaskEntity> =
            current.filter { it.state == TaskState.PENDING.name || it.state == TaskState.RUNNING.name || it.state == TaskState.PAUSED.name }
    }

    private class Factory(private val dir: File) : PartFileFactory {
        override fun partFor(id: String, fileName: String): File {
            val f = File(dir, "$id.part")
            if (!f.exists()) f.createNewFile()
            return f
        }

        override fun cleanup(id: String) {
            dir.listFiles { f -> f.name.startsWith("$id.") }?.forEach { it.delete() }
        }
    }

    private class Published(
        @Volatile var task: DownloadTaskEntity? = null,
        @Volatile var bytes: ByteArray? = null
    )

    private fun queue(repo: Repo, hls: HlsQueueEngine?, pub: Published): QueueManager = QueueManager(
        hlsEngine = hls,
        repository = repo,
        // the DIRECT engine must NEVER run for an HLS task
        engine = io.vdl.core.internal.engine.DownloadEngine { _, _, _, _ ->
            throw IllegalStateException("direct engine must not run for HLS tasks")
        },
        partFactory = Factory(tmp),
        publisher = PartPublisher { part, task ->
            pub.task = task
            pub.bytes = part.readBytes()
            PublishedResult("content://fake/${task.id}", null)
        },
        spaceChecker = SpaceChecker { -1L },
        networkGate = NetworkGate { _ -> true },
        log = testLog(sink),
        maxParallel = 2
    )

    private fun hlsTask(url: String): DownloadTaskEntity =
        makeEntity(url, "video.mp4").copy(kind = "HLS", contentType = null, destinationType = "APP_PRIVATE")

    private fun m3u(body: String) = Buffer().writeUtf8(body)
    private fun raw(b: ByteArray) = Buffer().write(b)

    private fun fmp4Server(): MockWebServer {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/page.html" -> MockResponse.Builder().code(200)
                    .addHeader("Content-Type", "text/html")
                    .body(m3u("<html><body><video><source src=\"/master.m3u8\" type=\"vnd.apple.mpegurl\"></video></body></html>"))
                    .build()
                "/master.m3u8" -> MockResponse.Builder().code(200).body(m3u(master)).build()
                "/video.m3u8" -> MockResponse.Builder().code(200).body(m3u(videoMedia)).build()
                "/audio.m3u8" -> MockResponse.Builder().code(200).body(m3u(audioMedia)).build()
                "/init.mp4" -> MockResponse.Builder().code(200).body(raw(videoInit)).build()
                "/audio-init.mp4" -> MockResponse.Builder().code(200).body(raw(audioInit)).build()
                "/seg/v1.m4s" -> MockResponse.Builder().code(200).body(raw(vSeg1)).build()
                "/seg/v2.m4s" -> MockResponse.Builder().code(200).body(raw(vSeg2)).build()
                "/seg/a1.m4s" -> MockResponse.Builder().code(200).body(raw(aSeg1)).build()
                "/seg/a2.m4s" -> MockResponse.Builder().code(200).body(raw(aSeg2)).build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        return server
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun hlsWithAudioRenditionMuxesToSinglePlayableFileAndCompletes() = runBlocking {
        fmp4Server()
        val repo = Repo()
        val pub = Published()
        val q = queue(repo, HlsQueueEngine(client, testLog(sink)), pub)
        q.start()
        val t = hlsTask(url("/master.m3u8"))
        assertTrue(q.submit(t))

        awaitTrue { runBlocking { repo.get(t.id)?.state } == TaskState.COMPLETED.name }

        val done = repo.get(t.id)!!
        assertEquals(TaskState.COMPLETED.name, done.state)
        assertNotNull(done.resultUri)

        val bytes = pub.bytes!!
        // merged file = init(moov with BOTH traks) + interleaved fragments
        assertEquals(2, occurrences(bytes, "trak".toByteArray()))
        assertEquals(1, occurrences(bytes, "ftyp".toByteArray()))
        assertEquals(done.bytesTotal, bytes.size.toLong())
        assertTrue("muxed must exceed video-only", bytes.size > videoInit.size + vSeg1.size + vSeg2.size)

        // scratch cleaned after publish: part + <id>.audio/<id>.muxed all gone
        assertTrue("leftover scratch: ${tmp.listFiles()!!.map { it.name }}", tmp.listFiles()!!.isEmpty())

        // evidence: mux + completion in the structured log
        assertTrue(sink.lines.any { it.contains("[VDL][HLS][queue-engine]") && it.contains("mux done") })
        assertTrue(sink.lines.any { it.contains("completed task=${t.id}") })
        runBlocking { q.shutdown() }
    }

    @Test
    fun resolvedFromHtmlPageDownloadsThroughTheRealQueue() = runBlocking {
        // stage 1: resolve an embed PAGE into the HLS master URL
        fmp4Server()
        val resolver = io.vdl.core.internal.extract.SourceResolver(
            client, testLog(sink)
        ) { io.vdl.core.internal.extract.QuickJsEngine(testLog(sink)) }
        val resolved = resolver.resolve(url("/page.html")) as ResolveOutcome.Success
        assertEquals(SourceKind.HLS, resolved.source.kind)
        assertEquals("html-scan", resolved.source.origin)
        assertEquals(url("/master.m3u8"), resolved.source.url)

        // stage 2: submit the RESOLVED url; the real queue downloads it
        val repo = Repo()
        val pub = Published()
        val q = queue(repo, HlsQueueEngine(client, testLog(sink)), pub)
        q.start()
        val t = hlsTask(resolved.source.url)
        assertTrue(q.submit(t))

        awaitTrue { runBlocking { repo.get(t.id)?.state } == TaskState.COMPLETED.name }

        val done = repo.get(t.id)!!
        assertEquals(TaskState.COMPLETED.name, done.state)
        assertEquals(2, occurrences(pub.bytes!!, "trak".toByteArray()))
        // evidence: the whole chain, page fetch -> resolve -> queue -> mux
        assertTrue(sink.lines.any { it.contains("page fetch text") })
        assertTrue(sink.lines.any { it.contains("resolve end") })
        assertTrue(sink.lines.any { it.contains("mux done") })
        runBlocking { q.shutdown() }
    }

    @Test
    fun hlsWithoutEngineFailsFast() = runBlocking {
        fmp4Server()
        val repo = Repo()
        val pub = Published()
        val q = queue(repo, null, pub)
        q.start()
        val t = hlsTask(url("/master.m3u8"))
        assertTrue(q.submit(t))

        awaitTrue { runBlocking { repo.get(t.id)?.state } == TaskState.FAILED.name }
        val done = repo.get(t.id)!!
        assertEquals(TaskState.FAILED.name, done.state)
        assertTrue(done.lastError!!.contains("HLS engine not configured"))
        assertTrue(pub.task == null)
        assertTrue(sink.lines.any { it.contains("hls-engine-not-configured") })
        runBlocking { q.shutdown() }
    }

    @Test
    fun tsWithAudioRenditionDegradesToVideoOnlyWithWarn() = runBlocking {
        // TS streams have no EXT-X-MAP: segments only, first byte = sync 0x47
        val tsMedia = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:3.6,
            seg/v1.m4s
            #EXTINF:3.6,
            seg/v2.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        val ts = { n: Int -> byteArrayOf(0x47) + randomBytes(300, n) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/page.html" -> MockResponse.Builder().code(200)
                    .addHeader("Content-Type", "text/html")
                    .body(m3u("<html><body><video><source src=\"/master.m3u8\" type=\"vnd.apple.mpegurl\"></video></body></html>"))
                    .build()
                "/master.m3u8" -> MockResponse.Builder().code(200).body(m3u(master)).build()
                "/video.m3u8" -> MockResponse.Builder().code(200).body(m3u(tsMedia)).build()
                "/audio.m3u8" -> MockResponse.Builder().code(200).body(m3u(tsMedia)).build()
                "/seg/v1.m4s" -> MockResponse.Builder().code(200).body(raw(ts(11))).build()
                "/seg/v2.m4s" -> MockResponse.Builder().code(200).body(raw(ts(12))).build()
                "/seg/a1.m4s" -> MockResponse.Builder().code(200).body(raw(ts(13))).build()
                "/seg/a2.m4s" -> MockResponse.Builder().code(200).body(raw(ts(14))).build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        val repo = Repo()
        val pub = Published()
        val q = queue(repo, HlsQueueEngine(client, testLog(sink)), pub)
        q.start()
        val t = hlsTask(url("/master.m3u8"))
        assertTrue(q.submit(t))

        awaitTrue { runBlocking { repo.get(t.id)?.state } == TaskState.COMPLETED.name }

        val bytes = pub.bytes!!
        // video-only TS: two segments, first byte = sync 0x47, no ftyp
        assertEquals(0x47, bytes[0].toInt() and 0xFF)
        assertEquals(0, occurrences(bytes, "ftyp".toByteArray()))
        assertEquals(602, bytes.size)

        val done = repo.get(t.id)!!
        assertEquals("video/mp2t", done.contentType)
        assertTrue(sink.lines.any { it.contains("ts+audio cannot atom-mux") && it.contains("decision=video-only") })
        assertTrue("leftover scratch: ${tmp.listFiles()!!.map { it.name }}", tmp.listFiles()!!.isEmpty())
        runBlocking { q.shutdown() }
    }

    private fun occurrences(hay: ByteArray, needle: ByteArray): Int {
        var n = 0
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            n++
        }
        return n
    }
}
