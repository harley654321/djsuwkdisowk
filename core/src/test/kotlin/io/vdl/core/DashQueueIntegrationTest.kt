package io.vdl.core

import io.vdl.core.internal.db.DownloadTaskEntity
import io.vdl.core.internal.db.TaskRepository
import io.vdl.core.internal.db.TaskState
import io.vdl.core.internal.dash.DashQueueEngine
import io.vdl.core.internal.queue.NetworkGate
import io.vdl.core.internal.queue.PartFileFactory
import io.vdl.core.internal.queue.PartPublisher
import io.vdl.core.internal.queue.PublishedResult
import io.vdl.core.internal.queue.QueueManager
import io.vdl.core.internal.queue.SpaceChecker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * End-to-end DASH queue integration: a REAL DashQueueEngine behind the
 * REAL QueueManager - MPD with separate audio AdaptationSet, both tracks
 * downloaded, atom-muxed into ONE part, published, scratch cleaned,
 * COMPLETED bookkeeping exact. Plus the not-configured fail-fast.
 */
class DashQueueIntegrationTest {

    private lateinit var server: MockWebServer
    private lateinit var tmp: File
    private lateinit var sink: PrintSink
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tmp = createTempDir("vdl-dash-q")
        sink = PrintSink()
    }

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdown()
        server.close()
        tmp.deleteRecursively()
    }

    private fun url(p: String) = server.url(p).toString()

    private val videoInit = TestFmp4Fixtures.initStream(trackId = 1, timescale = 1000, handler = "vide", audio = false)
    private val audioInit = TestFmp4Fixtures.initStream(trackId = 2, timescale = 44100, handler = "soun", audio = true)
    private val vSeg1 = TestFmp4Fixtures.segment(1, 1, 0, randomBytes(400, 1))
    private val vSeg2 = TestFmp4Fixtures.segment(1, 2, 3600, randomBytes(400, 2))
    private val aSeg1 = TestFmp4Fixtures.segment(2, 1, 0, randomBytes(200, 3))
    private val aSeg2 = TestFmp4Fixtures.segment(2, 2, 1024, randomBytes(200, 4))

    private val mpd = """
        <?xml version="1.0"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT7.2S">
          <Period>
            <AdaptationSet mimeType="video/mp4">
              <SegmentTemplate timescale="1000" duration="3600" startNumber="1"
                               initialization="${'$'}RepresentationID${'$'}/init.mp4"
                               media="${'$'}RepresentationID${'$'}/seg_${'$'}Number${'$'}.m4s"/>
              <Representation id="v720" bandwidth="1400000" height="720" codecs="avc1.64001f"/>
            </AdaptationSet>
            <AdaptationSet mimeType="audio/mp4" lang="en">
              <SegmentTemplate timescale="1000" duration="3600" startNumber="1"
                               initialization="a-init.mp4" media="a-seg_${'$'}Number${'$'}.m4s"/>
              <Representation id="aen" bandwidth="128000" codecs="mp4a.40.2"/>
            </AdaptationSet>
          </Period>
        </MPD>
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
            flow.map { l -> l.firstOrNull { it.id == id } }.distinctUntilChanged()

        override fun observeAll(): Flow<List<DownloadTaskEntity>> = flow
        override suspend fun pendingOrdered(): List<DownloadTaskEntity> =
            current.filter { it.state == TaskState.PENDING.name }.sortedByDescending { it.priorityEnum().ordinal }

        override suspend fun runningOrphans(): List<DownloadTaskEntity> = emptyList()
        override suspend fun activeTasks(): List<DownloadTaskEntity> =
            current.filter { it.state in listOf(TaskState.PENDING.name, TaskState.RUNNING.name, TaskState.PAUSED.name) }
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

    private fun queue(repo: Repo, dash: DashQueueEngine?, pub: Published): QueueManager = QueueManager(
        hlsEngine = null,
        dashEngine = dash,
        repository = repo,
        engine = io.vdl.core.internal.engine.DownloadEngine { _, _, _, _ ->
            throw IllegalStateException("direct engine must not run for DASH tasks")
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

    private fun dashTask(url: String): DownloadTaskEntity =
        makeEntity(url, "movie.mp4").copy(kind = "DASH", contentType = null, destinationType = "APP_PRIVATE")

    // ---------------------------------------------------------------- tests

    @Test
    fun dashWithAudioSetMuxesToSinglePlayableFileAndCompletes() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/master.mpd" -> MockResponse.Builder().code(200).body(mpdBody()).build()
                "/v720/init.mp4" -> MockResponse.Builder().code(200).body(raw(videoInit)).build()
                "/v720/seg_1.m4s" -> MockResponse.Builder().code(200).body(raw(vSeg1)).build()
                "/v720/seg_2.m4s" -> MockResponse.Builder().code(200).body(raw(vSeg2)).build()
                "/a-init.mp4" -> MockResponse.Builder().code(200).body(raw(audioInit)).build()
                "/a-seg_1.m4s" -> MockResponse.Builder().code(200).body(raw(aSeg1)).build()
                "/a-seg_2.m4s" -> MockResponse.Builder().code(200).body(raw(aSeg2)).build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        val repo = Repo()
        val pub = Published()
        val q = queue(repo, DashQueueEngine(client, testLog(sink)), pub)
        q.start()
        val t = dashTask(url("/master.mpd"))
        assertTrue(q.submit(t))

        awaitTrue { runBlocking { repo.get(t.id)?.state } == TaskState.COMPLETED.name }

        val done = repo.get(t.id)!!
        assertEquals(TaskState.COMPLETED.name, done.state)
        assertEquals("video/mp4", done.contentType)

        val bytes = pub.bytes!!
        assertEquals(2, occurrences(bytes, "trak".toByteArray()))
        assertEquals(1, occurrences(bytes, "ftyp".toByteArray()))
        assertEquals(done.bytesTotal, bytes.size.toLong())
        assertTrue("muxed must exceed video-only", bytes.size > videoInit.size + vSeg1.size + vSeg2.size)

        assertTrue("leftover scratch", tmp.listFiles()!!.isEmpty())
        assertTrue(sink.lines.any { it.contains("[VDL][DASH][queue-engine]") && it.contains("mux done") })
        assertTrue(sink.lines.any { it.contains("completed task=${t.id}") })
        q.shutdown()
    }

    @Test
    fun dashWithoutEngineFailsFast() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(200).body(mpdBody()).build()
        }
        val repo = Repo()
        val pub = Published()
        val q = queue(repo, null, pub)
        q.start()
        val t = dashTask(url("/master.mpd"))
        assertTrue(q.submit(t))

        awaitTrue { runBlocking { repo.get(t.id)?.state } == TaskState.FAILED.name }
        val done = repo.get(t.id)!!
        assertEquals(TaskState.FAILED.name, done.state)
        assertTrue(done.lastError!!.contains("DASH engine not configured"))
        assertTrue(pub.task == null)
        assertTrue(sink.lines.any { it.contains("dash-engine-not-configured") })
        q.shutdown()
    }

    // -------------------------------------------------------------- helpers

    private fun mpdBody() = Buffer().writeUtf8(mpd)
    private fun raw(b: ByteArray) = Buffer().write(b)

    private fun occurrences(hay: ByteArray, needle: ByteArray): Int {
        var n = 0
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            n++
        }
        return n
    }
}
