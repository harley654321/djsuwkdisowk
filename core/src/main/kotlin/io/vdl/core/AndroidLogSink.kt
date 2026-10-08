package io.vdl.core

import android.util.Log
import io.vdl.core.Logger

/**
 * Default Android sink: every line goes to logcat under the single parent
 * tag VDL so consumers filter with `logcat VDL:V *:S`. The structured tag
 * travels inside the message: "[VDL][QUEUE][dispatch] ...".
 */
internal class AndroidLogSink : Logger {
    override fun log(level: Logger.Level, tag: String, message: String, error: Throwable?) {
        val line = "$tag $message"
        when (level) {
            Logger.Level.DEBUG -> Log.d("VDL", line, error)
            Logger.Level.INFO -> Log.i("VDL", line, error)
            Logger.Level.WARN -> Log.w("VDL", line, error)
            Logger.Level.ERROR -> Log.e("VDL", line, error)
        }
    }
}
