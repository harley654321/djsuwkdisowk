package io.vdl.core

import io.vdl.core.internal.db.DownloadTaskEntity
import io.vdl.core.internal.db.TaskRepository
import io.vdl.core.internal.db.TaskState
import io.vdl.core.internal.engine.ChunkProgress
import io.vdl.core.internal.engine.DownloadEngine
import io.vdl.core.internal.engine.EngineOutcome
import io.vdl.core.internal.engine.EngineState
import io.vdl.core.internal.logging.VdlLog
import io.vdl.core.internal.queue.QueueManager
import io.vdl.core.internal.queue.PartFileFactory
import io.vdl.core.internal.queue.PublishedResult
import io.vdl.core.internal.queue.PartPublisher
import io.vdl.core.internal.queue.SpaceChecker
import io.vdl.core.internal.queue.NetworkGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

internal class InMemoryTaskRepository : TaskRepository {

    private val flow = MutableStateFlow<List<DownloadTaskEntity>>(emptyList())
    private val current: List<DownloadTaskEntity> get() = flow.value

    // Read-modify-write over flow.value MUST be mutually exclusive: the
    // queue coroutine updates rows concurrently with test submits, and a
    // stale-list write silently erases rows (observed as a 1-in-10 flake
    // where a submitted task vanished from the repo).
    private val mu = Mutex()

    override suspend fun add(task: DownloadTaskEntity): Boolean = mu.withLock {
        if (current.any { it.url == task.url && it.fileName == task.fileName }) {
            false
        } else {
            flow.value = current + task
            true
        }
    }

    override suspend fun update(task: DownloadTaskEntity) = mu.withLock {
        flow.value = current.map { if (it.id == task.id) task else it }
    }

    override suspend fun get(id: String): DownloadTaskEntity? = current.firstOrNull { it.id == id }

    override suspend fun delete(id: String) = mu.withLock {
        flow.value = current.filterNot { it.id == id }
    }

    override suspend fun clearCompleted(): Int {
        val done = current.filter { it.state == TaskState.COMPLETED.name }
        flow.value = current - done.toSet()
        return done.size
    }

    override fun observe(id: String): Flow<DownloadTaskEntity?> =
        flow.map { list -> list.firstOrNull { it.id == id } }.distinctUntilChanged()

    override fun observeAll(): Flow<List<DownloadTaskEntity>> = flow.asStateFlow()

    override suspend fun pendingOrdered(): List<DownloadTaskEntity> =
        current.filter { it.state == TaskState.PENDING.name }
            .sortedWith(compareBy({ priorityRank(it.priority) }, { it.createdAt }))

    override suspend fun runningOrphans(): List<DownloadTaskEntity> =
        current.filter { it.state == TaskState.RUNNING.name }

    override suspend fun activeTasks(): List<DownloadTaskEntity> =
        current.filter { it.state != TaskState.COMPLETED.name && it.state != TaskState.CANCELLED.name }

    private fun priorityRank(raw: String): Int = when (raw) {
        "HIGH" -> 0
        "NORMAL" -> 1
        else -> 2
    }
}

/** Scripted engine: hangs on a gate when asked, sets chunk state, returns a fixed outcome. */
internal class FakeEngine : DownloadEngine {
    internal val started = java.util.concurrent.CopyOnWriteArrayList<String>()
    internal var result: EngineOutcome = EngineOutcome.Success(1_000L)
    internal var hang = false
    internal val gate = CompletableDeferred<Unit>()

    override suspend fun execute(
        task: DownloadTaskEntity,
        partFile: File,
        state: EngineState,
        onProgress: (Progress) -> Unit
    ): EngineOutcome {
        started += task.id
        state.bytesTotal = 1_000L
        state.acceptRanges = true
        state.chunks = listOf(ChunkProgress(0, 999, 0))
        if (hang) gate.await()
        state.chunks = listOf(ChunkProgress(0, 999, 1000))
        onProgress(Progress(1000, 1000, 100, 0, 1))
        return result
    }

    fun release() {
        hang = false
        gate.complete(Unit)
    }
}

internal class FakeFactory(private val dir: File) : PartFileFactory {
    internal val created = mutableSetOf<String>()

    override fun partFor(id: String, fileName: String): File {
        created += id
        val f = File(dir, "$id.part")
        if (!f.exists()) f.createNewFile()
        return f
    }

    override fun cleanup(id: String) {
        File(dir, "$id.part").delete()
    }
}

class QueueManagerTest {

    private lateinit var repo: InMemoryTaskRepository
    private val engine = FakeEngine()
    private lateinit var dir: File
    private lateinit var factory: FakeFactory
    private val published = mutableListOf<String>()
    private val sink = PrintSink()
    private val log: VdlLog = testLog(sink)
    private var queue: QueueManager? = null

    @Before
    fun setUp() {
        repo = InMemoryTaskRepository()
        dir = Files.createTempDirectory("vdl-queue-test").toFile()
        factory = FakeFactory(dir)
    }

    @After
    fun tearDown() {
        // REGRA (StressLab Q10): shutdown es suspend desde v0.2 (pausa sincrona
        // de RUNNING -> PAUSED persistido). En tearDown garantizamos que el
        // queue muere sin dejar huérfanos RUNNING antes de borrar el scratch dir.
        queue?.let { runBlocking { it.shutdown() } }
        dir.deleteRecursively()
    }

    private fun newQueue(
        maxParallel: Int = 3,
        space: Long = -1L,
        gate: NetworkGate = NetworkGate { _ -> true }
    ): QueueManager = QueueManager(
        repository = repo,
        engine = engine,
        partFactory = factory,
        publisher = PartPublisher { _, task ->
            published += task.fileName
            PublishedResult("content://fake/${task.id}", "/fake/${task.fileName}")
        },
        spaceChecker = SpaceChecker { space },
        networkGate = gate,
        log = log,
        maxParallel = maxParallel
    ).also {
        it.start()
        queue = it
    }

    private fun submit(url: String, fileName: String, priority: String = "NORMAL"): String = runBlocking {
        val q = queue!!
        val entity = makeEntity(url, fileName).copy(priority = priority)
        assertTrue(q.submit(entity))
        entity.id
    }

    private fun stateOf(id: String): String? = runBlocking { repo.get(id)?.state }

    private fun entityOf(id: String): DownloadTaskEntity? = runBlocking { repo.get(id) }

    @Test
    fun dispatchesHighestPriorityFirst() = runBlocking<Unit> {
        // No preemption exists by design: dispatch ORDER is priority-based,
        // so gate all three behind systemPause, then release and observe
        // that the pump hands the single slot to HIGH first, then NORMAL, then LOW.
        newQueue(maxParallel = 1)
        queue!!.setSystemPaused(true)
        val low = submit("https://a/low", "low.bin", priority = "LOW")
        val normal = submit("https://a/normal", "normal.bin", priority = "NORMAL")
        val high = submit("https://a/high", "high.bin", priority = "HIGH")
        awaitTrue { listOf(low, normal, high).all { stateOf(it) == TaskState.PENDING.name } }
        queue!!.setSystemPaused(false)
        queue!!.networkChanged()
        awaitTrue { stateOf(low) == TaskState.COMPLETED.name }
        assertEquals(listOf(high, normal, low), engine.started)
    }

    @Test
    fun respectsParallelLimit() = runBlocking<Unit> {
        engine.hang = true
        newQueue(maxParallel = 2)
        val a = submit("https://a/1", "a.bin")
        val b = submit("https://a/2", "b.bin")
        val c = submit("https://a/3", "c.bin")
        awaitTrue { queue!!.runningIds().size == 2 }
        assertEquals(TaskState.PENDING.name, stateOf(c))
        engine.release()
        awaitTrue { stateOf(a) == TaskState.COMPLETED.name && stateOf(b) == TaskState.COMPLETED.name }
        awaitTrue { stateOf(c) == TaskState.COMPLETED.name }
        assertEquals(3, engine.started.size)
    }

    @Test
    fun pausePersistsChunksAndResumeCompletes() = runBlocking<Unit> {
        engine.hang = true
        newQueue(maxParallel = 1)
        val id = submit("https://a/1", "video.mp4")
        awaitTrue { stateOf(id) == TaskState.RUNNING.name }
        queue!!.pause(id)
        awaitTrue { stateOf(id) == TaskState.PAUSED.name }
        val paused = entityOf(id)!!
        assertEquals("0-999-0", paused.chunksEnc)
        assertEquals(true, paused.acceptRanges)
        // resume and finish
        engine.release()
        queue!!.resume(id)
        awaitTrue { stateOf(id) == TaskState.COMPLETED.name }
        assertEquals("content://fake/$id", entityOf(id)!!.resultUri)
        assertTrue(published.contains("video.mp4"))
    }

    @Test
    fun duplicateRejected() = runBlocking<Unit> {
        newQueue(maxParallel = 3)
        val q = queue!!
        val first = makeEntity("https://a/1", "same.bin")
        val dup = makeEntity("https://a/1", "same.bin")
        assertTrue(q.submit(first))
        assertFalse(q.submit(dup))
    }

    @Test
    fun retryableFailureMarksFailedTyped() = runBlocking<Unit> {
        engine.result = EngineOutcome.Retryable("boom", 503)
        newQueue(maxParallel = 3)
        val id = submit("https://a/1", "failing.bin")
        awaitTrue { stateOf(id) == TaskState.FAILED.name }
        val failed = entityOf(id)!!
        assertEquals(1, failed.attempt)
        val error = io.vdl.core.internal.db.ErrorCodec.decode(failed.lastError)
        assertEquals(io.vdl.core.DownloadError.Http(503, "boom"), error)
    }

    @Test
    fun cancelRunningDeletesPart() = runBlocking<Unit> {
        engine.hang = true
        newQueue(maxParallel = 1)
        val id = submit("https://a/1", "cancel.bin")
        awaitTrue { stateOf(id) == TaskState.RUNNING.name }
        assertTrue(File(dir, "$id.part").exists())
        queue!!.cancel(id)
        awaitTrue { stateOf(id) == TaskState.CANCELLED.name }
        assertFalse(File(dir, "$id.part").exists())
        engine.release()
    }

    @Test
    fun insufficientSpaceFailsFastWhenSizeKnown() = runBlocking<Unit> {
        engine.hang = true
        newQueue(maxParallel = 1, space = 100L)
        val occupier = submit("https://a/1", "occupier.bin")
        awaitTrue { stateOf(occupier) == TaskState.RUNNING.name }
        val second = submit("https://a/2", "second.bin")
        // simulate: engine learned the size (progress persist) for the queued task
        repo.update(entityOf(second)!!.copy(bytesTotal = 5_000L))
        engine.release()
        awaitTrue { stateOf(second) == TaskState.FAILED.name }
        val error = io.vdl.core.internal.db.ErrorCodec.decode(entityOf(second)!!.lastError)
        assertEquals(io.vdl.core.DownloadError.InsufficientSpace, error)
        assertTrue(sink.lines.any { it.contains("insufficientSpace") })
    }

    @Test
    fun systemPauseBlocksDispatchUntilResumed() = runBlocking<Unit> {
        newQueue(maxParallel = 3)
        queue!!.setSystemPaused(true)
        val id = submit("https://a/1", "sys.bin")
        delay(150)
        assertEquals(TaskState.PENDING.name, stateOf(id))
        queue!!.resume(id) // explicit user resume clears the system pause
        awaitTrue { stateOf(id) == TaskState.COMPLETED.name }
    }

    @Test
    fun wifiOnlyDeferredUntilNetworkAllows() = runBlocking<Unit> {
        var allowed = false
        newQueue(maxParallel = 3, gate = NetworkGate { _ -> allowed })
        val id = submit("https://a/1", "wifi.bin")
        delay(150)
        assertEquals(TaskState.PENDING.name, stateOf(id))
        allowed = true
        queue!!.networkChanged()
        awaitTrue { stateOf(id) == TaskState.COMPLETED.name }
        assertTrue(sink.lines.any { it.contains("reason=network") })
    }

    @Test
    fun cancelAllCancelsRunningAndPending() = runBlocking<Unit> {
        engine.hang = true
        newQueue(maxParallel = 2)
        val a = submit("https://a/1", "a.bin")
        val b = submit("https://a/2", "b.bin")
        val c = submit("https://a/3", "c.bin")
        awaitTrue { queue!!.runningIds().size == 2 }
        queue!!.cancelAll()
        awaitTrue {
            stateOf(a) == TaskState.CANCELLED.name &&
                stateOf(b) == TaskState.CANCELLED.name &&
                stateOf(c) == TaskState.CANCELLED.name
        }
        engine.release()
    }
}
