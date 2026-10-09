package io.vdl.core

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.vdl.core.AndroidLogSink
import io.vdl.core.internal.db.DownloadTaskEntity
import io.vdl.core.internal.db.RoomTaskRepository
import io.vdl.core.internal.db.TaskState
import io.vdl.core.internal.db.VdlDatabase
import io.vdl.core.internal.engine.ChunkedHttpEngine
import io.vdl.core.internal.dash.DashQueueEngine
import io.vdl.core.internal.hls.HlsQueueEngine
import io.vdl.core.internal.logging.VdlLog
import io.vdl.core.internal.net.NetworkMonitor
import io.vdl.core.internal.queue.QueueManager
import io.vdl.core.internal.service.DownloadService
import io.vdl.core.internal.extract.QuickJsEngine
import io.vdl.core.internal.extract.SourceResolver
import io.vdl.core.internal.service.NotificationHelper
import io.vdl.core.internal.storage.AndroidPartFileFactory
import io.vdl.core.internal.storage.AndroidSpaceChecker
import io.vdl.core.internal.storage.AndroidStoragePublisher
import io.vdl.core.internal.work.ResumeWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Thrown by download() when the same url+fileName is already queued. */
public class DuplicateDownloadException internal constructor(
    url: String,
    fileName: String
) : Exception("A download of \"$fileName\" for this url is already queued: $url")

/**
 * Internal engine wiring. Lives for the whole process; the queue keeps
 * running while the process is alive, and Room + ResumeWorker survive it.
 */
internal class VdlEngine internal constructor(
    private val appContext: Context,
    internal val config: VdlConfig
) {
    private val log = VdlLog(config.logger ?: AndroidLogSink(), config.debugLogging)
    private val database = VdlDatabase.build(appContext)
    private val repository = RoomTaskRepository(database.taskDao())
    private val client = buildClient()
    private val networkMonitor = NetworkMonitor(appContext, log)
    internal val queue = QueueManager(
        hlsEngine = HlsQueueEngine(client, log),
        dashEngine = DashQueueEngine(client, log),
        repository = repository,
        engine = ChunkedHttpEngine(client, log, config.progressIntervalMs),
        partFactory = AndroidPartFileFactory(appContext),
        publisher = AndroidStoragePublisher(appContext, log),
        spaceChecker = AndroidSpaceChecker(appContext),
        networkGate = networkMonitor,
        log = log,
        maxParallel = config.maxParallelDownloads
    )
    private val helper = NotificationHelper(appContext, log)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notifiedCompleted = Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private val serviceStarted = AtomicBoolean(false)

    init {
        log.i(TAG) { "engine init debug=${config.debugLogging} maxParallel=${config.maxParallelDownloads}" }
        queue.start()
        networkMonitor.register { queue.networkChanged() }
        scope.launch { recoverOrphanTasks() }
        scope.launch { notificationLoop() }
        scheduleResumeWorker()
    }

    /** Resolves a page (or direct media) URL into a downloadable source. */
    internal suspend fun resolve(url: String): ResolveOutcome =
        SourceResolver(client, log) { QuickJsEngine(log) }.resolve(url)

    internal suspend fun submit(request: DownloadRequest): String {
        val entity = DownloadTaskEntity.fromRequest(request, System.currentTimeMillis())
        if (!queue.submit(entity)) throw DuplicateDownloadException(request.url, request.fileName)
        return entity.id
    }

    internal fun observe(id: String): Flow<DownloadState> =
        combine(queue.observeTask(id), queue.progressFlow()) { entity, progress ->
            mapState(entity, progress[id])
        }

    internal fun observeAll(): Flow<List<TaskSnapshot>> =
        combine(queue.observeAllTasks(), queue.progressFlow()) { list, progress ->
            list.map { it.toSnapshot(progress[it.id]) }
        }

    internal fun pause(id: String) = queue.pause(id)
    internal fun resume(id: String) = queue.resume(id)
    internal fun cancel(id: String) = queue.cancel(id)
    internal fun cancelAll() = queue.cancelAll()
    internal suspend fun clearCompleted(): Int = repository.clearCompleted()

    internal suspend fun getTask(id: String): TaskSnapshot? =
        repository.get(id)?.toSnapshot(null)

    internal fun pauseAllInternal() {
        log.w(TAG) { "pauseAll: ${queue.runningIds().size} running" }
        for (id in queue.runningIds()) queue.pause(id)
    }

    internal fun onDataSyncTimeout() {
        log.w(TAG) { "dataSync timeout (6h/24h budget) decision=systemPause" }
        queue.setSystemPaused(true)
        pauseAllInternal()
    }

    internal suspend fun recoverOrphanTasks() {
        val orphans = repository.runningOrphans()
        if (orphans.isEmpty()) return
        log.i(TAG) { "recovering ${orphans.size} orphan task(s) after process death" }
        for (o in orphans) {
            repository.update(o.withState(TaskState.PENDING, System.currentTimeMillis()))
        }
        queue.networkChanged()
    }

    internal fun progressNotification(): android.app.Notification =
        helper.foregroundNotification(null, 0L, -1L)

    internal fun cancelNotification() = helper.cancel()

    internal fun logLine(message: String) {
        log.i(TAG) { message }
    }

    internal fun shutdown() {
        log.i(TAG) { "engine shutdown" }
        queue.shutdown()
        scope.cancel()
        runCatching { database.close() }
    }

    private suspend fun notificationLoop() {
        combine(queue.observeAllTasks(), queue.progressFlow()) { t, p -> t to p }
            .collect { (tasks, progress) ->
                val active = tasks.filter { it.state == TaskState.RUNNING.name }
                if (active.isEmpty()) {
                    if (serviceStarted.compareAndSet(true, false)) {
                        log.i(TAG) { "queue idle decision=stopService" }
                        DownloadService.stop(appContext)
                    }
                } else {
                    val shown = active.firstOrNull { it.showNotification } ?: active.first()
                    if (shown.showNotification) {
                        helper.progress(
                            shown.fileName,
                            progress[shown.id]?.bytesDownloaded ?: shown.bytesDownloaded,
                            progress[shown.id]?.bytesTotal ?: shown.bytesTotal,
                            actions = true
                        )
                    }
                    if (serviceStarted.compareAndSet(false, true)) {
                        log.i(TAG) { "active=${active.size} decision=startService" }
                        DownloadService.start(appContext)
                    }
                }
                for (t in tasks) {
                    if (t.state == TaskState.COMPLETED.name && t.showNotification && notifiedCompleted.add(t.id)) {
                        helper.complete(t.fileName, t.resultPath)
                    }
                }
            }
    }

    private fun scheduleResumeWorker() {
        try {
            val wm = WorkManager.getInstance(appContext)
            val work = OneTimeWorkRequestBuilder<ResumeWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            wm.enqueueUniqueWork("vdl_resume", ExistingWorkPolicy.KEEP, work)
            log.i(TAG) { "resume worker scheduled" }
        } catch (t: Throwable) {
            log.e(TAG, t) { "WorkManager unavailable decision=skip-resume-worker" }
        }
    }

    private fun buildClient(): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
        if (config.debugLogging) {
            b.addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        }
        return b.build()
    }

    internal companion object {
        internal const val TAG = "[VDL][ENGINE-FACADE]"
    }
}

/** Internal bridge the service/worker use to reach the living engine. */
internal class VdlBridge internal constructor() {
    internal var engine: VdlEngine? = null

    internal fun initialize(appContext: Context, config: VdlConfig): Boolean {
        if (engine != null) return false
        engine = VdlEngine(appContext, config)
        return true
    }

    internal fun require(): VdlEngine =
        checkNotNull(engine) { "VdlDownloader.initialize(context) was not called" }

    internal fun pauseAll() { engine?.pauseAllInternal() }
    internal fun cancelAll() { engine?.cancelAll() }
    internal fun onDataSyncTimeout() { engine?.onDataSyncTimeout() }
    internal fun logLine(message: String) { engine?.logLine(message) }
    internal fun cancelNotification() { engine?.cancelNotification() }
    internal fun progressNotification(@Suppress("UNUSED_PARAMETER") context: Context) =
        engine?.progressNotification()
            ?: android.app.Notification()

    internal suspend fun recoverOrphanTasks() { engine?.recoverOrphanTasks() }
}
