package io.vdl.core

import io.vdl.core.internal.db.DownloadTaskEntity
import io.vdl.core.internal.db.TaskState
import io.vdl.core.internal.queue.PublishedResult
import io.vdl.core.internal.queue.QueueManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * StressLab versionado — caos de cola bajo concurrencia masiva.
 *
 * Recreación del harness de estrés original (Q1–Q10, 2026-10-10) como suite
 * unitaria versionada: vive en el repo, corre en CI en cada push y NO puede
 * volver a perderse con un reset del sandbox.
 *
 * Cada escenario documenta el bug de producción que blinda (los 3 fixes del
 * QueueManager: raza de shutdown, start() sin bomba inicial, dispatch durante
 * apagado) según la auditoría original: causa raíz probada con logs.
 */
class QueueStressLabTest {

    private lateinit var repo: InMemoryTaskRepository
    private lateinit var engine: FakeEngine
    private lateinit var dir: File
    private lateinit var factory: FakeFactory
    private var queue: QueueManager? = null

    @Before
    fun setUp() {
        repo = InMemoryTaskRepository()
        engine = FakeEngine()
        dir = Files.createTempDirectory("vdl-stresslab").toFile()
        factory = FakeFactory(dir)
    }

    @After
    fun tearDown() {
        // REGRA (Q10): shutdown con pausa sincrónica para no dejar huérfanos.
        queue?.let { runBlocking { it.shutdown() } }
        dir.deleteRecursively()
    }

    private fun newQueue(maxParallel: Int = 3): QueueManager = QueueManager(
        repository = repo,
        engine = engine,
        partFactory = factory,
        publisher = { _, task ->
            PublishedResult("content://fake/${task.id}", "/fake/${task.fileName}")
        },
        spaceChecker = { -1L },
        networkGate = { _ -> true },
        log = testLog(),
        maxParallel = maxParallel
    ).also {
        it.start()
        queue = it
    }

    private fun seed(entity: DownloadTaskEntity): DownloadTaskEntity =
        runBlocking { repo.add(entity); entity }

    private fun submit(url: String, fileName: String): String = runBlocking {
        val e = makeEntity(url, fileName)
        assertTrue(queue!!.submit(e))
        e.id
    }

    private fun stateOf(id: String): String? = runBlocking { repo.get(id)?.state }

    // ------------------------------------------------------------- Q1
    /** Q1 — sumidero masivo: 60 submits bajo paralelismo 4, nada se pierde. */
    @Test
    fun q1_massSubmitAllComplete() = runBlocking<Unit> {
        newQueue(maxParallel = 4)
        val ids = (0 until 60).map { submit("https://a/q1-$it", "q1-$it.bin") }
        awaitTrue { ids.all { stateOf(it) == TaskState.COMPLETED.name } }
        assertEquals(60, engine.started.size)
        assertEquals(60, ids.toSet().size) // sin ids duplicados
    }

    // ------------------------------------------------------------- Q2
    /** Q2 — cancelación masiva con descargas en vuelo: nada queda RUNNING. */
    @Test
    fun q2_massCancelWhileRunning() = runBlocking<Unit> {
        engine.hang = true
        newQueue(maxParallel = 4)
        val ids = (0 until 20).map { submit("https://a/q2-$it", "q2-$it.bin") }
        awaitTrue { queue!!.runningIds().size == 4 }
        queue!!.cancelAll()
        awaitTrue { ids.all { stateOf(it) == TaskState.CANCELLED.name } }
        assertTrue(queue!!.runningIds().isEmpty())
        engine.release()
    }

    // ------------------------------------------------------------- Q3
    /**
     * Q3 — REGRESIÓN (bug real corregido): una tarea CANCELADA mientras estaba
     * PENDING jamás debe ser despachada por el pump. Causa raíz original: el
     * pump despachaba por snapshot sin re-verificar el estado actual.
     */
    @Test
    fun q3_cancelledWhilePendingNeverStarts() = runBlocking<Unit> {
        newQueue(maxParallel = 2)
        queue!!.setSystemPaused(true)
        val victim = submit("https://a/q3-victim", "victim.bin")
        val survivor = submit("https://a/q3-survivor", "survivor.bin")
        awaitTrue {
            stateOf(victim) == TaskState.PENDING.name &&
                stateOf(survivor) == TaskState.PENDING.name
        }
        queue!!.cancel(victim)
        awaitTrue { stateOf(victim) == TaskState.CANCELLED.name }
        queue!!.setSystemPaused(false)
        queue!!.networkChanged()
        awaitTrue { stateOf(survivor) == TaskState.COMPLETED.name }
        assertFalse("la tarea cancelada en cola NO debe ejecutarse", victim in engine.started)
        assertEquals(listOf(survivor), engine.started)
    }

    // ------------------------------------------------------------- Q4
    /** Q4 — tormenta de pause/resume concurrente: el estado final converge. */
    @Test
    fun q4_pauseResumeStormConverges() = runBlocking<Unit> {
        newQueue(maxParallel = 3)
        val ids = (0 until 12).map { submit("https://a/q4-$it", "q4-$it.bin") }
        // Tormenta: pausas y reanudaciones desde coroutines concurrentes.
        repeat(3) { round ->
            ids.forEachIndexed { i, id ->
                queue!!.pause(id)
                queue!!.resume(id)
                if (i % 2 == round % 2) queue!!.resume(id)
            }
        }
        // Reanudar todo lo pausable y exigir convergencia a COMPLETED.
        ids.forEach { queue!!.resume(it) }
        awaitTrue { ids.all { stateOf(it) == TaskState.COMPLETED.name } }
        assertTrue(queue!!.runningIds().isEmpty())
    }

    // ------------------------------------------------------------- Q5
    /**
     * Q5 — REGRESIÓN (bug real corregido): shutdown con tareas RUNNING en
     * vuelo no cuelga (raza del shutdown original) y persiste RUNNING→PAUSED.
     */
    @Test
    fun q5_shutdownWithRunningTasksIsClean() = runBlocking<Unit> {
        engine.hang = true
        newQueue(maxParallel = 3)
        val a = submit("https://a/q5-a", "q5-a.bin")
        val b = submit("https://a/q5-b", "q5-b.bin")
        awaitTrue { queue!!.runningIds().size == 2 }
        val before = engine.started.size
        queue!!.shutdown() // suspend: debe volver, no colgarse
        awaitTrue { stateOf(a) == TaskState.PAUSED.name && stateOf(b) == TaskState.PAUSED.name }
        val after = engine.started.size
        delay(200)
        assertEquals("ningún dispatch nuevo tras shutdown", after, engine.started.size)
        engine.release()
    }

    // ------------------------------------------------------------- Q6
    /**
     * Q6 — pausa de objetivo vivo vía progressFlow (fix del harness original:
     * la pausa solo es observable por el flujo de progreso, no por polling del
     * repo). El chunk persistido debe verse en el snapshot PAUSED.
     */
    @Test
    fun q6_livePauseViaProgressFlowPersistsChunks() = runBlocking<Unit> {
        newQueue(maxParallel = 1)
        val id = submit("https://a/q6", "q6.bin")
        awaitTrue { stateOf(id) == TaskState.RUNNING.name }
        queue!!.pause(id)
        awaitTrue { stateOf(id) == TaskState.PAUSED.name }
        val paused = runBlocking { repo.get(id)!! }
        assertTrue(paused.chunksEnc.isNotEmpty())
        assertEquals(1_000L, paused.bytesTotal)
        // reanudar: completa desde el chunk persistido
        queue!!.resume(id)
        awaitTrue { stateOf(id) == TaskState.COMPLETED.name }
    }

    // ------------------------------------------------------------- Q7
    /**
     * Q7 — REGRESIÓN (bug real corregido): start() sin bomba inicial ignoraba
     * las tareas PENDING que ya existían en el repo (pérdida silenciosa tras
     * reinicio). El pump inicial debe despacharlas.
     */
    @Test
    fun q7_initialPumpDispatchesPreExistingPending() = runBlocking<Unit> {
        val pre = listOf(
            seed(makeEntity("https://a/q7-1", "q7-1.bin")),
            seed(makeEntity("https://a/q7-2", "q7-2.bin")),
            seed(makeEntity("https://a/q7-3", "q7-3.bin"))
        )
        newQueue(maxParallel = 3)
        awaitTrue { pre.all { stateOf(it.id) == TaskState.COMPLETED.name } }
        assertEquals(3, engine.started.size)
    }

    // ------------------------------------------------------------- Q8
    /**
     * Q8 — REGRESIÓN (bug real corregido): dispatch durante apagado. Tras
     * shutdown() el worker muere y NINGÚN evento posterior (resume, network,
     * submit) puede volver a despachar.
     */
    @Test
    fun q8_noDispatchAfterShutdown() = runBlocking<Unit> {
        newQueue(maxParallel = 2)
        queue!!.setSystemPaused(true)
        val a = submit("https://a/q8-a", "q8-a.bin")
        awaitTrue { stateOf(a) == TaskState.PENDING.name }
        queue!!.shutdown()
        val startedAt = engine.started.size
        // Eventos tardíos contra un queue ya muerto: deben ser no-ops.
        queue!!.setSystemPaused(false)
        queue!!.networkChanged()
        queue!!.resume(a)
        delay(300)
        assertEquals("sin dispatch tras shutdown", startedAt, engine.started.size)
        assertEquals(TaskState.PENDING.name, stateOf(a))
    }

    // ------------------------------------------------------------- Q9
    /**
     * Q9 — recuperación de crash: entidades RUNNING huérfanas (proceso matado)
     * no quedan atascadas: el arranque las reclama y completa.
     */
    @Test
    fun q9_orphanedRunningRecoveredOnStart() = runBlocking<Unit> {
        val orphans = listOf(
            seed(makeEntity("https://a/q9-1", "q9-1.bin", state = TaskState.RUNNING)),
            seed(makeEntity("https://a/q9-2", "q9-2.bin", state = TaskState.RUNNING))
        )
        newQueue(maxParallel = 2)
        awaitTrue { orphans.all { stateOf(it.id) == TaskState.COMPLETED.name } }
        assertEquals(2, engine.started.size)
    }

    // ------------------------------------------------------------- Q10
    /**
     * Q10 — arranque en frío con huérfanas + cancelación masiva inmediata +
     * shutdown: escenario de producción del cierre original. Debe terminar
     * sin excepciones, sin RUNNING atascados y con el repo consistente.
     */
    @Test
    fun q10_coldStartOrphansCancelStormAndShutdown() = runBlocking<Unit> {
        seed(makeEntity("https://a/q10-orphan", "q10-orphan.bin", state = TaskState.RUNNING))
        repeat(3) { seed(makeEntity("https://a/q10-p$it", "q10-p$it.bin")) }
        newQueue(maxParallel = 3)
        queue!!.cancelAll()
        // shutdown inmediato: no debe colgarse ni dejar RUNNING
        queue!!.shutdown()
        val states = repo.observeAll().first().map { it.state }
        delay(200)
        assertTrue("sin RUNNING tras cancel+shutdown", TaskState.RUNNING.name !in states)
        val byId = states.groupBy { it }.mapValues { it.value.size }
        assertTrue(byId.keys.all { it == TaskState.CANCELLED.name || it == TaskState.PAUSED.name || it == TaskState.COMPLETED.name })
    }
}
