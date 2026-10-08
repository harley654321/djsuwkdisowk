package io.vdl.core.internal.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.vdl.core.VdlDownloader

/**
 * Re-enqueues tasks that were mid-flight when the process died.
 * Scheduled once per initialize() with a CONNECTED constraint.
 */
internal class ResumeWorker internal constructor(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        VdlDownloader.bridge.recoverOrphanTasks()
        Result.success()
    } catch (t: Throwable) {
        VdlDownloader.bridge.logLine("[VDL][WORKER] resume failed decision=retry err=${t.message}")
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
