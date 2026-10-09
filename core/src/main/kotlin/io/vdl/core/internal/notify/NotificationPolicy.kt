package io.vdl.core.internal.notify

import io.vdl.core.DownloadError
import io.vdl.core.Progress
import io.vdl.core.internal.db.DownloadTaskEntity
import io.vdl.core.internal.db.ErrorCodec
import io.vdl.core.internal.db.TaskState

/**
 * Pure notification policy: turns the task list + live progress map into
 * the set of notification decisions the engine must execute. All the
 * decision LOGIC lives here (JVM-testable); the Android rendering stays
 * in NotificationHelper.
 *
 * Rules:
 *  - One ongoing progress notification for the first RUNNING task that
 *    wants notifications (fallback: the first RUNNING task).
 *  - No RUNNING tasks -> cancel the progress notification.
 *  - COMPLETED/FAILED with showNotification fire EXACTLY ONCE per task
 *    (terminal dedup). If the task later leaves the terminal state
 *    (retry: FAILED -> PENDING), the dedup re-arms so a SECOND failure
 *    notifies again.
 *  - showNotification=false tasks never produce any decision.
 *
 * Single-caller by design: the engine's notification loop coroutine is
 * the only owner of an instance; no synchronization is needed.
 */
internal class NotificationPolicy internal constructor() {

    internal sealed interface Decision {
        data class Progress(val fileName: String, val done: Long, val total: Long) : Decision
        data class Completed(val id: String, val fileName: String, val filePath: String?) : Decision
        data class Failed(val id: String, val fileName: String, val reason: String) : Decision
        data object CancelProgress : Decision
    }

    private val notifiedTerminal = HashSet<String>()

    internal fun reduce(
        tasks: List<DownloadTaskEntity>,
        progress: Map<String, Progress>
    ): List<Decision> {
        val decisions = ArrayList<Decision>()

        // re-arm: a terminal task that moved back to active (retry) may
        // notify again on its next terminal state
        for (t in tasks) {
            if (t.id !in notifiedTerminal) continue
            val st = TaskState.from(t.state)
            if (st != TaskState.COMPLETED && st != TaskState.FAILED && st != TaskState.CANCELLED) {
                notifiedTerminal.remove(t.id)
            }
        }

        val active = tasks.filter { it.state == TaskState.RUNNING.name }
        if (active.isEmpty()) {
            decisions.add(Decision.CancelProgress)
        } else {
            val shown = active.firstOrNull { it.showNotification } ?: active.first()
            if (shown.showNotification) {
                val p = progress[shown.id]
                decisions.add(
                    Decision.Progress(
                        fileName = shown.fileName,
                        done = p?.bytesDownloaded ?: shown.bytesDownloaded,
                        total = p?.bytesTotal ?: shown.bytesTotal
                    )
                )
            }
        }

        for (t in tasks) {
            if (!t.showNotification) continue
            when (TaskState.from(t.state)) {
                TaskState.COMPLETED ->
                    if (notifiedTerminal.add(t.id)) {
                        decisions.add(Decision.Completed(t.id, t.fileName, t.resultPath))
                    }
                TaskState.FAILED ->
                    if (notifiedTerminal.add(t.id)) {
                        decisions.add(Decision.Failed(t.id, t.fileName, humanReason(t.lastError)))
                    }
                else -> Unit
            }
        }
        return decisions
    }

    /** Short, user-readable failure reason from the persisted error. */
    internal fun humanReason(rawError: String?): String {
        if (rawError.isNullOrBlank()) return "Download failed"
        return when (val e = ErrorCodec.decode(rawError)) {
            null -> "Download failed"
            is DownloadError.Network -> "Network error"
            is DownloadError.Http -> "Server error ${e.code}"
            is DownloadError.Storage -> "Storage error"
            is DownloadError.InvalidRequest -> e.message.take(80)
            DownloadError.FileAlreadyExists -> "File already exists"
            DownloadError.InsufficientSpace -> "Not enough space"
        }
    }

    internal companion object {
        internal const val TAG = "[VDL][NOTIFY][POLICY]"
    }
}
