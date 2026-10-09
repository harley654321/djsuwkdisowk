package io.vdl.core.internal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import io.vdl.core.internal.logging.VdlLog

/**
 * Single low-importance ongoing notification for the download service.
 * Updates are throttled to 500ms (monotonic clock) to avoid battery drain.
 */
internal class NotificationHelper internal constructor(
    private val context: Context,
    private val log: VdlLog
) {

    private val nm = context.applicationContext.getSystemService(NotificationManager::class.java)
    private var lastUpdateMs: Long = 0L

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Ongoing download progress"
            nm?.createNotificationChannel(channel)
        }
    }

    internal fun canNotify(): Boolean = runCatching {
        nm?.areNotificationsEnabled() == true
    }.getOrDefault(false)

    internal fun progress(fileName: String, done: Long, total: Long, actions: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUpdateMs < UPDATE_INTERVAL_MS) return
        lastUpdateMs = now
        val builder = baseBuilder()
            .setContentTitle(fileName)
            .setProgress(10_000, scaled(done, total), total <= 0)
            .setContentText("${mb(done)} / ${mb(total)}")
        if (actions) {
            builder.addAction(0, "Pause all", serviceIntent(DownloadService.ACTION_PAUSE_ALL))
            builder.addAction(0, "Cancel all", serviceIntent(DownloadService.ACTION_CANCEL_ALL))
        }
        notify(ID_PROGRESS, builder.build())
    }

    internal fun complete(fileName: String, filePath: String?, taskId: String? = null) {
        val builder = baseBuilder()
            .setContentTitle(fileName)
            .setContentText(if (filePath != null) "Saved: $filePath" else "Download complete")
            .setOngoing(false)
            .setAutoCancel(true)
        // per-task id when known: several completions coexist in the tray
        notify(taskId?.let { stableId(ID_COMPLETE_BASE, it) } ?: ID_COMPLETE, builder.build())
    }

    /**
     * Terminal failure notification with a Retry action. The Retry
     * PendingIntent targets the service (which may be STOPPED at this
     * point: the queue is idle after the failure) so it uses
     * getForegroundService and the RETRY branch calls startForeground.
     */
    internal fun failed(fileName: String, reason: String, taskId: String) {
        val builder = baseBuilder()
            .setContentTitle(fileName)
            .setContentText("Failed: $reason")
            .setOngoing(false)
            .setAutoCancel(true)
            .addAction(0, "Retry", serviceIntent(DownloadService.ACTION_RETRY, taskId))
        notify(stableId(ID_FAILED_BASE, taskId), builder.build())
    }

    /** Immediate notification for startForeground(); never throttled. */
    internal fun foregroundNotification(fileName: String?, done: Long, total: Long): Notification {
        val b = baseBuilder().setContentTitle(fileName ?: "Downloads")
        if (fileName != null && total > 0) {
            b.setProgress(10_000, scaled(done, total), false)
                .setContentText("${mb(done)} / ${mb(total)}")
        } else {
            b.setContentText("Preparing downloads...")
        }
        return b.build()
    }

    internal fun cancel() {
        nm?.cancel(ID_PROGRESS)
    }

    private fun baseBuilder(): Notification.Builder {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context.applicationContext, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context.applicationContext)
        }
        return b.setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
    }

    private fun serviceIntent(action: String, taskId: String? = null): PendingIntent {
        val intent = Intent(context.applicationContext, DownloadService::class.java).setAction(action)
        if (taskId != null) intent.putExtra(DownloadService.EXTRA_TASK_ID, taskId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val requestCode = (action + (taskId ?: "")).hashCode()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context.applicationContext, requestCode, intent, flags)
        } else {
            PendingIntent.getService(context.applicationContext, requestCode, intent, flags)
        }
    }

    /** Stable per-task notification id in [base, base+RANGE). */
    private fun stableId(base: Int, taskId: String): Int =
        (base + kotlin.math.abs(taskId.hashCode()) % ID_RANGE).also {
            log.d(TAG) { "notif id=$it task=$taskId base=$base" }
        }

    private fun notify(id: Int, notification: Notification) {
        if (!canNotify()) {
            log.d(TAG) { "notifications disabled, skip id=$id" }
            return
        }
        try {
            nm?.notify(id, notification)
        } catch (se: SecurityException) {
            log.w(TAG) { "notify denied id=$id decision=ignore" }
        }
    }

    private fun scaled(done: Long, total: Long): Int =
        if (total > 0) ((done.toDouble() / total) * 10_000).toInt().coerceIn(0, 10_000) else 0

    private fun mb(bytes: Long): String =
        if (bytes >= 0) String.format("%.1f MB", bytes / 1_048_576.0) else "?"

    internal companion object {
        internal const val TAG = "[VDL][NOTIF]"
        internal const val CHANNEL_ID = "vdl_downloads"
        internal const val ID_PROGRESS = 4711
        internal const val ID_COMPLETE = 4712
        internal const val ID_COMPLETE_BASE = 5000
        internal const val ID_FAILED_BASE = 14000
        private const val ID_RANGE = 8000
        private const val UPDATE_INTERVAL_MS = 500L
    }
}
