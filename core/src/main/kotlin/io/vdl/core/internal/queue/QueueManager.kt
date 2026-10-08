package io.vdl.core.internal.queue

import io.vdl.core.DownloadError
import io.vdl.core.Progress
import io.vdl.core.internal.db.DownloadTaskEntity
import io.vdl.core.internal.db.ErrorCodec
import io.vdl.core.internal.db.TaskRepository
import io.vdl.core.internal.db.TaskState
import io.vdl.core.internal.engine.ChunkCodec
import io.vdl.core.internal.engine.DownloadEngine
import io.vdl.core.internal.engine.EngineOutcome
import io.vdl.core.internal.engine.EngineState
import io.vdl.core.internal.logging.VdlLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import io.vdl.core.internal.storage.DestinationFileExistsException
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface PartPublisher {
    suspend fun publish(part: File, task: DownloadTaskEntity): PublishedResult
}

internal data class PublishedResult internal constructor(
    internal val uri: String?,
    internal val filePath: String?
)

internal fun interface SpaceChecker {
    fun availableBytes(): Long
}

internal fun interface NetworkGate {
    fun canRunNow(task: DownloadTaskEntity): Boolean
}

internal interface PartFileFactory {
    fun partFor(id: String, fileName: String): File
    fun cleanup(id: String)
}

internal sealed interface QueueEvent {
    data class Start(internal val id: String) : QueueEvent
    data class Pause(internal val id: String) : QueueEvent
    data class Resume(internal val id: String) : QueueEvent
    data class Cancel(internal val id: String) : QueueEvent
    object CancelAll : QueueEvent
    object NetworkChanged : QueueEvent
    data class EngineFinished(
        internal val id: String,
        internal val outcome: EngineOutcome?,
        internal val engineState: EngineState
    ) : QueueEvent

    data class ProgressTick(
        internal val id: String,
        internal val progress: Progress
    ) : QueueEvent
}

internal class RunningTask internal constructor(
    internal val job: Job,
    internal val engineState: EngineState,
    internal val entity: DownloadTaskEntity
)

/**
 * Single-writer event loop: every state mutation flows through one channel
 * consumed by one coroutine, so there are no check-then-act races. Engine
 * coroutines only append progress/finish events.
 */
internal class QueueManager internal constructor(
    private val hlsEngine: DownloadEngine? = null,
    private val repository: TaskRepository,
    private val engine: DownloadEngine,
    private val partFactory: PartFileFactory,
    private val publisher: PartPublisher,
    private val spaceChecker: SpaceChecker,
    private val networkGate: NetworkGate,
    internal val log: VdlLog,
    private val maxParallel: Int,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = Channel<QueueEvent>(Channel.UNLIMITED)
    private val progressMap = MutableStateFlow<Map<String, Progress>>(emptyMap())
    private val running = ConcurrentHashMap<String, RunningTask>()
    private val lastPersist = HashMap<String, Long>()
    private val systemPaused = AtomicBoolean(false)

    init {
        require(maxParallel >= 1) { "maxParallel >= 1" }
    }

    internal fun start() {
        scope.launch {
            log.i(TAG) { "queue loop start maxParallel=$maxParallel" }
            loop()
        }
    }

    internal fun shutdown() {
        log.i(TAG) { "queue shutdown: ${running.size} running tasks paused" }
        for (id in running.keys) pause(id)
        scope.cancel()
    }

    internal suspend fun submit(entity: DownloadTaskEntity): Boolean {
        val ok = repository.add(entity)
        log.i(TAG) { "submit task=${entity.id} ok=$ok file=${entity.fileName}" }
        if (ok) events.trySend(QueueEvent.Start(entity.id))
        return ok
    }

    internal fun pause(id: String) { events.trySend(QueueEvent.Pause(id)) }
    internal fun resume(id: String) { events.trySend(QueueEvent.Resume(id)) }
    internal fun cancel(id: String) { events.trySend(QueueEvent.Cancel(id)) }
    internal fun cancelAll() { events.trySend(QueueEvent.CancelAll) }
    internal fun networkChanged() { events.trySend(QueueEvent.NetworkChanged) }

    /** Blocks dispatch entirely (dataSync timeout budget exhausted). */
    internal fun setSystemPaused(v: Boolean) { systemPaused.set(v) }

    internal fun progressFlow(): StateFlow<Map<String, Progress>> = progressMap
    internal fun runningIds(): Set<String> = running.keys.toSet()
    internal fun observeTask(id: String): Flow<DownloadTaskEntity?> = repository.observe(id)
    internal fun observeAllTasks(): Flow<List<DownloadTaskEntity>> = repository.observeAll()

    private suspend fun loop() = coroutineScope {
        for (e in events) {
            try {
                handle(e)
            } catch (ce: CancellationException) {
                log.i(TAG) { "queue loop cancelled" }
                throw ce
            } catch (t: Throwable) {
                log.e(TAG, t) { "event=${e::class.simpleName} handler crash decision=continue" }
            }
            // pump() runs OUTSIDE handle's try on purpose: a dispatch failure
            // (storage error, hostile filename) must degrade that one dispatch,
            // never kill the loop and brick the whole queue.
            try {
                pump()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                log.e(TAG, t) { "pump crash decision=continue" }
            }
        }
    }

    private suspend fun handle(e: QueueEvent) {
        when (e) {
            is QueueEvent.Start -> Unit

            QueueEvent.NetworkChanged ->
                log.i(TAG) { "network changed decision=repump" }

            is QueueEvent.ProgressTick -> {
                val rt = running[e.id] ?: return
                progressMap.value = progressMap.value + (e.id to e.progress)
                maybePersistProgress(rt, e.progress)
            }

            is QueueEvent.Resume -> {
                systemPaused.set(false)
                val t = repository.get(e.id) ?: return
                if (t.state != TaskState.PAUSED.name && t.state != TaskState.FAILED.name) {
                    log.w(TAG) { "resume ignored task=${e.id} state=${t.state}" }
                    return
                }
                repository.update(t.copy(state = TaskState.PENDING.name, updatedAt = clockMs()))
                log.i(TAG) { "resume task=${e.id} stateOld=${t.state}->PENDING" }
            }

            is QueueEvent.Pause -> pauseTask(e.id)

            is QueueEvent.Cancel -> cancelTask(e.id)

            QueueEvent.CancelAll -> {
                val active = repository.activeTasks()
                log.i(TAG) { "cancelAll n=${active.size}" }
                for (t in active) cancelTask(t.id)
            }

            is QueueEvent.EngineFinished -> engineFinished(e)
        }
    }

    private suspend fun pauseTask(id: String) {
        val rt = running.remove(id)
        if (rt != null) {
            rt.job.cancel()
            rt.job.join()
            persistSnapshot(rt, TaskState.PAUSED)
            log.i(TAG) { "paused task=$id have=${rt.engineState.chunks.sumOf { it.downloaded }}" }
        } else {
            val t = repository.get(id) ?: return
            if (t.state == TaskState.PENDING.name || t.state == TaskState.FAILED.name) {
                repository.update(t.withState(TaskState.PAUSED, clockMs()))
                log.i(TAG) { "paused(not started) task=$id" }
            } else {
                log.w(TAG) { "pause ignored task=$id state=${t.state}" }
            }
        }
    }

    private suspend fun cancelTask(id: String) {
        val rt = running.remove(id)
        if (rt != null) {
            rt.job.cancel()
            rt.job.join()
            log.i(TAG) { "cancelled(running) task=$id" }
        }
        partFactory.cleanup(id)
        val t = repository.get(id) ?: return
        if (t.state != TaskState.COMPLETED.name) {
            repository.update(
                t.copy(state = TaskState.CANCELLED.name, updatedAt = clockMs())
            )
        }
        progressMap.value = progressMap.value - id
    }

    private suspend fun engineFinished(e: QueueEvent.EngineFinished) {
        val rt = running.remove(e.id) ?: return
        val outcome = e.outcome
        if (outcome == null) {
            // cancelled: Pause/Cancel already persisted the snapshot
            log.i(TAG) { "engine finished(cancelled) task=${e.id}" }
            return
        }
        when (outcome) {
            is EngineOutcome.Success -> {
                val updated = rt.entity.copy(
                    bytesTotal = if (e.engineState.bytesTotal > 0) e.engineState.bytesTotal else rt.entity.bytesTotal,
                    bytesDownloaded = e.engineState.chunks.sumOf { it.downloaded },
                    etag = e.engineState.etag,
                    acceptRanges = e.engineState.acceptRanges,
                    contentType = e.engineState.contentType,
                    chunksEnc = ChunkCodec.encode(e.engineState.chunks)
                )
                if (updated.bytesTotal > 0 && updated.bytesDownloaded < updated.bytesTotal) {
                    repository.update(rt.entity.copy(
                        state = TaskState.FAILED.name,
                        attempt = rt.entity.attempt + 1,
                        lastError = ErrorCodec.encode(DownloadError.Network("incomplete download", null)),
                        updatedAt = clockMs()
                    ))
                    log.e(TAG) { "task=${e.id} incomplete have=${updated.bytesDownloaded}/${updated.bytesTotal} decision=failed" }
                    return
                }
                try {
                    val part = partFactory.partFor(e.id, updated.fileName)
                    val published = publisher.publish(part, updated)
                    repository.update(
                        updated.copy(
                            state = TaskState.COMPLETED.name,
                            resultUri = published.uri,
                            resultPath = published.filePath,
                            updatedAt = clockMs()
                        )
                    )
                    partFactory.cleanup(e.id)
                    log.i(TAG) { "completed task=${e.id} bytes=${updated.bytesTotal} uri=${published.uri ?: "-"}" }
                } catch (t: Throwable) {
                    log.e(TAG, t) { "publish failed task=${e.id} decision=failed" }
                    partFactory.cleanup(e.id)
                    val error = if (t is DestinationFileExistsException) {
                        DownloadError.FileAlreadyExists
                    } else {
                        DownloadError.Storage("publish failed: ${t.message}", t.javaClass.simpleName)
                    }
                    repository.update(
                        updated.copy(
                            state = TaskState.FAILED.name,
                            attempt = rt.entity.attempt + 1,
                            lastError = ErrorCodec.encode(error),
                            updatedAt = clockMs()
                        )
                    )
                }
            }

            is EngineOutcome.Retryable, is EngineOutcome.Fatal -> {
                repository.update(persistableSnapshot(rt, e.engineState).copy(
                    state = TaskState.FAILED.name,
                    attempt = rt.entity.attempt + 1,
                    lastError = ErrorCodec.encode(mapError(outcome)),
                    updatedAt = clockMs()
                ))
                log.e(TAG) { "failed task=${e.id} outcome=$outcome attempt=${rt.entity.attempt + 1}" }
            }
        }
    }

    private suspend fun pump() {
        if (systemPaused.get()) {
            log.i(TAG) { "pump skipped reason=systemPaused" }
            return
        }
        if (running.size >= maxParallel) return
        val candidates = repository.pendingOrdered()
        for (c in candidates) {
            if (running.size >= maxParallel) return
            if (running.containsKey(c.id)) continue
            if (!networkGate.canRunNow(c)) {
                log.i(TAG) { "dispatch deferred task=${c.id} reason=network(wifiOnly=${c.wifiOnly})" }
                continue
            }
            val avail = spaceChecker.availableBytes()
            if (c.bytesTotal > 0 && avail in 0 until c.bytesTotal) {
                // Log BEFORE the observable mutation: an observer awaiting the
                // FAILED state must find the evidence line already emitted.
                log.e(TAG) { "dispatch task=${c.id} reason=insufficientSpace need=${c.bytesTotal} avail=$avail old=${c.state} new=FAILED decision=fail-fast" }
                repository.update(c.withState(TaskState.FAILED, clockMs(), ErrorCodec.encode(DownloadError.InsufficientSpace)))
                continue
            }
            startEngine(c)
        }
    }

    private suspend fun startEngine(c: DownloadTaskEntity) {
        val entity = c.copy(state = TaskState.RUNNING.name, updatedAt = clockMs())
        val es = EngineState().apply {
            bytesTotal = entity.bytesTotal
            etag = entity.etag
            acceptRanges = entity.acceptRanges
            contentType = entity.contentType
            chunks = ChunkCodec.decode(entity.chunksEnc)
        }
        val engine = if (entity.kind == "HLS") hlsEngine else {
            log.i(TAG) { "engine kind=DIRECT task=${entity.id}" }
            this.engine
        }
        if (engine == null) {
            // Log BEFORE the observable mutation: observers awaiting FAILED
            // must find the evidence line already emitted.
            log.e(TAG) { "dispatch task=${entity.id} reason=hls-engine-not-configured old=${entity.state} new=FAILED decision=fail-fast" }
            repository.update(entity.withState(TaskState.FAILED, clockMs(), ErrorCodec.encode(DownloadError.InvalidRequest("HLS engine not configured"))))
            return
        }
        val part = partFactory.partFor(entity.id, entity.fileName)
        val startedAt = clockMs()
        log.i(TAG) { "engine start task=${entity.id} kind=${entity.kind} url=${entity.url} resumeChunks=${es.chunks.size} have=${entity.bytesDownloaded}" }
        // LAZY: guarantees running[] is populated before the engine can
        // emit EngineFinished, otherwise an instant engine would no-op.
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val outcome = try {
                engine.execute(entity, part, es) { p -> events.trySend(QueueEvent.ProgressTick(entity.id, p)) }
            } catch (ce: CancellationException) {
                null
            } catch (t: Throwable) {
                log.e(TAG, t) { "engine crashed task=${entity.id} dt=${clockMs() - startedAt}ms decision=fatal" }
                EngineOutcome.Fatal("unexpected engine failure: ${t.message}")
            }
            log.i(TAG) { "engine end task=${entity.id} dt=${clockMs() - startedAt}ms outcome=$outcome" }
            events.trySend(QueueEvent.EngineFinished(entity.id, outcome, es))
        }
        repository.update(entity)
        running[entity.id] = RunningTask(job, es, entity)
        job.start()
    }

    private suspend fun maybePersistProgress(rt: RunningTask, p: Progress) {
        val now = clockMs()
        val last = lastPersist[rt.entity.id] ?: 0L
        if (now - last < 2000L) return
        lastPersist[rt.entity.id] = now
        repository.update(
            rt.entity.copy(
                bytesDownloaded = p.bytesDownloaded,
                bytesTotal = if (p.bytesTotal > 0) p.bytesTotal else rt.entity.bytesTotal,
                chunksEnc = ChunkCodec.encode(rt.engineState.chunks),
                updatedAt = now
            )
        )
    }

    private suspend fun persistSnapshot(rt: RunningTask, state: TaskState) {
        repository.update(persistableSnapshot(rt, rt.engineState).withState(state, clockMs()))
    }

    private fun persistableSnapshot(rt: RunningTask, es: EngineState): DownloadTaskEntity =
        rt.entity.copy(
            bytesTotal = if (es.bytesTotal > 0) es.bytesTotal else rt.entity.bytesTotal,
            bytesDownloaded = es.chunks.sumOf { it.downloaded },
            etag = es.etag,
            acceptRanges = es.acceptRanges,
            contentType = es.contentType,
            chunksEnc = ChunkCodec.encode(es.chunks)
        )

    private fun mapError(outcome: EngineOutcome): DownloadError = when (outcome) {
        is EngineOutcome.Retryable ->
            if (outcome.httpCode != null) DownloadError.Http(outcome.httpCode, outcome.message)
            else DownloadError.Network(outcome.message, null)
        is EngineOutcome.Fatal ->
            if (outcome.httpCode != null) DownloadError.Http(outcome.httpCode, outcome.message)
            else DownloadError.Network("fatal: ${outcome.message}", null)
        is EngineOutcome.Success -> DownloadError.Network("unexpected success mapped as error", null)
    }

    internal companion object {
        internal const val TAG = "[VDL][QUEUE]"
    }
}
