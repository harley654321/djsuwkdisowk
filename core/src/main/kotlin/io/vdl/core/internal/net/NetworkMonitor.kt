package io.vdl.core.internal.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.vdl.core.internal.db.DownloadTaskEntity
import io.vdl.core.internal.logging.VdlLog
import io.vdl.core.internal.queue.NetworkGate

/**
 * ConnectivityManager-backed network gate: always requires a usable network,
 * plus unmetered for wifiOnly tasks. registerDefaultNetworkCallback is API 24+
 * which matches our minSdk.
 */
internal class NetworkMonitor internal constructor(
    context: Context,
    private val log: VdlLog
) : NetworkGate {

    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    internal fun isOnline(): Boolean {
        val network = cm?.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    internal fun isUnmetered(): Boolean {
        val network = cm?.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    override fun canRunNow(task: DownloadTaskEntity): Boolean {
        if (!isOnline()) return false
        if (task.wifiOnly && !isUnmetered()) return false
        return true
    }

    internal fun register(onChange: () -> Unit) {
        try {
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onChange()
                override fun onLost(network: Network) = onChange()
                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) = onChange()
            })
            log.i(TAG) { "network callback registered" }
        } catch (t: Throwable) {
            log.e(TAG, t) { "network callback registration failed decision=continue-poll-only" }
        }
    }

    internal companion object {
        internal const val TAG = "[VDL][NET]"
    }
}
