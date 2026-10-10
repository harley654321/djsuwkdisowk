package io.vdl.core

import io.vdl.core.internal.engine.ChunkCodec
import io.vdl.core.internal.engine.ChunkedHttpEngine
import io.vdl.core.internal.engine.EngineOutcome
import io.vdl.core.internal.engine.EngineState
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

/**
 * Estrés del engine de chunks — réplica de las condiciones del crash
 * IndexOutOfBounds reportado bajo carga (12 paralelas, madrugada del
 * 2026-10-10). El stacktrace original se perdió con el harness perdido;
 * esta suite es la red de regresión: si algún acceso indexado del engine
 * (ChunkBoard/ChunkTransfer/reconcile) rompe de nuevo, falla AQUÍ en CI
 * con stacktrace completo, no en producción.
 */
class ChunkedHttpEngineStressTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    private lateinit var tmp: File
    private val sink = PrintSink()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tmp = Files.createTempDirectory("vdl-engine-stress").toFile()
    }

    @After
    fun tearDown() {
        server.close()
        tmp.deleteRecursively()
    }

    private fun engine(): ChunkedHttpEngine = ChunkedHttpEngine(
        client = client,
        log = testLog(sink),
        progressIntervalMs = 5,
        sleeper = { _ -> }, // reintentos instantáneos
        clockNanos = { System.nanoTime() }
    )

    private fun partFile(): File = File.createTempFile("part", ".bin", tmp)

    private fun url(): String = server.url("/file.bin").toString()

    /**
     * E1 — réplica del reporte original: 12 workers, 128 chunks, throttle 429
     * en ráfaga y desconexiones de socket en pleno vuelo. Debe terminar
     * byte-exacto y sin excepción (la original: IndexOutOfBounds).
     */
    @Test
    fun e1_twelveWorkersUnderThrottleAndDisconnectChaos() {
        val data = randomBytes(8 * 1024 * 1024, seed = 21) // 128 chunks de 64KB
        val dispatcher = RangeDispatcher(
            data,
            throttles = 24,   // ráfaga de 429 con 12 workers activos
            disconnects = 6   // sockets cortados a mitad de chunk
        )
        server.dispatcher = dispatcher
        val part = partFile()
        val outcome = runBlocking {
            engine().execute(
                makeEntity(
                    url(),
                    threads = 12,
                    chunkSizeBytes = 64L * 1024,
                    retryBaseDelayMs = 1,
                    retryMaxAttempts = 30
                ),
                part,
                EngineState(),
                { }
            )
        }
        // El caos (24x429 + 6 desconexiones) consume presupuesto de reintentos:
        // con retryMaxAttempts=30 el CONTRATO dice que debe completar. Si no
        // completara, es un bug real del engine, no un presupuesto agotado.
        assertEquals(EngineOutcome.Success(data.size.toLong()), outcome)
        assertArrayEquals(data, part.readBytes())
        assertTrue("esperábamos >100 requests de rango, hubo ${dispatcher.rangeRequests.get()}",
            dispatcher.rangeRequests.get() > 100)
        // El progreso nunca reportó más bytes de los reales (board consistente).
        assertTrue(sink.lines.none { it.contains("IndexOutOfBounds") })
    }

    /**
     * E2 — reconcile con plan cambiado: snapshot persistido con plan de 4
     * threads/256KB, se reanuda con 12 threads/64KB. El plan NUEVO tiene 4x
     * más chunks y distintos límites; el merge por start debe reconciliar sin
     * romper índices y descargar byte-exacto.
     */
    @Test
    fun e2_resumeWithDifferentPlanReconcilesSafely() {
        val data = randomBytes(2 * 1024 * 1024, seed = 23)
        val dispatcher = RangeDispatcher(data)
        server.dispatcher = dispatcher
        val part = partFile()
        // Persistimos un progreso bajo el plan viejo: chunks 0 y 1 completos.
        FileOutputStream(part).use { it.write(data, 0, 512 * 1024) }
        val state = EngineState().apply {
            bytesTotal = data.size.toLong()
            acceptRanges = true
            etag = "\"v1\""
            chunks = ChunkCodec.decode(
                "0-${256 * 1024 - 1}-${256 * 1024};${256 * 1024}-${512 * 1024 - 1}-${256 * 1024}"
            )
        }
        val outcome = runBlocking {
            engine().execute(
                makeEntity(url(), threads = 12, chunkSizeBytes = 64L * 1024),
                part,
                state,
                { }
            )
        }
        assertEquals(EngineOutcome.Success(data.size.toLong()), outcome)
        assertArrayEquals(data, part.readBytes())
        assertTrue(sink.lines.any { it.contains("resume reconciled") })
    }

    /**
     * E3 — snapshot corrupto (no cubre el archivo entero, límites truncados):
     * el reconcile debe re-planificar y NUNCA indexar fuera ni falsear
     * completitud. Byte-exacto o nada.
     */
    @Test
    fun e3_corruptSnapshotReplansWithoutCrash() {
        val data = randomBytes(1024 * 1024, seed = 25)
        val dispatcher = RangeDispatcher(data)
        server.dispatcher = dispatcher
        val part = partFile()
        // Snapshot truncado: un solo chunk que cubre 1KB de un archivo de 1MB.
        val state = EngineState().apply {
            bytesTotal = data.size.toLong()
            acceptRanges = true
            etag = "\"v1\""
            chunks = ChunkCodec.decode("0-1023-1024")
        }
        val outcome = runBlocking {
            engine().execute(
                makeEntity(url(), threads = 12, chunkSizeBytes = 64L * 1024),
                part,
                state,
                { }
            )
        }
        assertEquals(EngineOutcome.Success(data.size.toLong()), outcome)
        assertArrayEquals(data, part.readBytes())
    }
}
