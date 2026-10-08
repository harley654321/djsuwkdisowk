package io.vdl.core.internal.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.vdl.core.VdlDownloader

/**
 * Foreground service of type dataSync (Android 15+ budget: 6h/24h).
 *
 * The queue lives in the engine singleton (application scope); this service
 * only satisfies the foreground requirements and forwards notification
 * actions. onTimeout (API 35+) pauses everything and stops orderly.
 */
internal class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        log("service onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_ALL -> {
                log("action PAUSE_ALL")
                VdlDownloader.bridge.pauseAll()
                return START_STICKY
            }
            ACTION_CANCEL_ALL -> {
                log("action CANCEL_ALL")
                VdlDownloader.bridge.cancelAll()
                return START_STICKY
            }
            ACTION_STOP -> {
                log("action STOP")
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startForegroundCompat()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = VdlDownloader.bridge.progressNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.ID_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NotificationHelper.ID_PROGRESS, notification)
        }
        log("startForeground type=dataSync")
    }

    private fun stopForegroundCompat() {
        VdlDownloader.bridge.cancelNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    /** Android 15+: the 6h/24h dataSync budget ran out. Pause and exit cleanly. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        log("onTimeout startId=$startId fgsType=$fgsType decision=pauseAll-stop")
        VdlDownloader.bridge.onDataSyncTimeout()
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        log("service onDestroy")
        super.onDestroy()
    }

    private fun log(message: String) {
        // The bridge may not be initialized (rare direct start); never crash.
        VdlDownloader.bridge.logLine("[VDL][SERVICE] $message")
    }

    internal companion object {
        internal const val ACTION_PAUSE_ALL = "io.vdl.core.action.PAUSE_ALL"
        internal const val ACTION_CANCEL_ALL = "io.vdl.core.action.CANCEL_ALL"
        internal const val ACTION_STOP = "io.vdl.core.action.STOP"

        internal fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        internal fun stop(context: Context) {
            val intent = Intent(context, DownloadService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
