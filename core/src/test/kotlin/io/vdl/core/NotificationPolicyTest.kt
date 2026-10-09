package io.vdl.core

import io.vdl.core.internal.db.TaskState
import io.vdl.core.internal.notify.NotificationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure policy behavior against REAL entities: which notification to show,
 * terminal dedup, re-arm after retry and human-readable failure reasons.
 */
class NotificationPolicyTest {

    private val policy = NotificationPolicy()

    private fun task(
        id: String,
        state: TaskState,
        show: Boolean = true,
        fileName: String = "video.mp4",
        lastError: String? = null,
        resultPath: String? = null,
        bytesDownloaded: Long = 0L,
        bytesTotal: Long = 1000L
    ): io.vdl.core.internal.db.DownloadTaskEntity =
        makeEntity(url = "https://x.example/$id", fileName = fileName, state = state).copy(
            id = id,
            showNotification = show,
            lastError = lastError,
            resultPath = resultPath,
            bytesDownloaded = bytesDownloaded,
            bytesTotal = bytesTotal
        )

    private fun progress(done: Long, total: Long) = Progress(done, total, 0, 0, 1)

    @Test
    fun runningTaskProducesOneProgressDecision() {
        val d = policy.reduce(listOf(task("a", TaskState.RUNNING)), mapOf("a" to progress(400, 1000)))
        assertEquals(1, d.size)
        val p = d[0] as NotificationPolicy.Decision.Progress
        assertEquals("video.mp4", p.fileName)
        assertEquals(400, p.done)
        assertEquals(1000, p.total)
    }

    @Test
    fun idleQueueCancelsProgressNotification() {
        assertEquals(
            listOf(NotificationPolicy.Decision.CancelProgress),
            policy.reduce(listOf(task("a", TaskState.PENDING)), emptyMap())
        )
    }

    @Test
    fun showNotificationTaskWinsOverSilentOnes() {
        val silent = task("s", TaskState.RUNNING, show = false, fileName = "silent.bin")
        val shown = task("v", TaskState.RUNNING, show = true, fileName = "visible.mp4")
        val d = policy.reduce(listOf(silent, shown), emptyMap())
        assertEquals("visible.mp4", (d[0] as NotificationPolicy.Decision.Progress).fileName)
    }

    @Test
    fun completedFiresExactlyOncePerTask() {
        val t = task("a", TaskState.COMPLETED, resultPath = "/data/movie.mp4")
        val first = policy.reduce(listOf(t), emptyMap())
        // idle queue: the progress notification is cancelled AND the
        // completion is announced -> two decisions by design
        assertEquals(2, first.size)
        assertEquals(NotificationPolicy.Decision.CancelProgress, first[0])
        val c = first[1] as NotificationPolicy.Decision.Completed
        assertEquals("/data/movie.mp4", c.filePath)

        // second emission of the same state: dedup, nothing new
        val second = policy.reduce(listOf(t), emptyMap())
        assertTrue(second.none { it is NotificationPolicy.Decision.Completed })
    }

    @Test
    fun failedFiresOnceWithHumanReasonAndRearmsOnRetry() {
        val failed = task("f", TaskState.FAILED, lastError = "Network|timeout|null")
        val f = policy.reduce(listOf(failed), emptyMap())
            .filterIsInstance<NotificationPolicy.Decision.Failed>().single()
        assertEquals("Network error", f.reason)
        assertTrue(policy.reduce(listOf(failed), emptyMap()).none { it is NotificationPolicy.Decision.Failed })

        // user retries: task leaves the terminal state -> dedup re-arms
        policy.reduce(listOf(task("f", TaskState.PENDING)), emptyMap())
        val failedAgain = task("f", TaskState.FAILED, lastError = "InsufficientSpace||")
        val second = policy.reduce(listOf(failedAgain), emptyMap())
            .filterIsInstance<NotificationPolicy.Decision.Failed>().single()
        assertEquals("Not enough space", second.reason)
    }

    @Test
    fun silentTasksNeverNotifyAnything() {
        val t = task("s", TaskState.FAILED, show = false, lastError = "Network|x|null")
        // idle still cancels the progress notification, but NO per-task
        // decision may exist for a silent task
        val d = policy.reduce(listOf(t), emptyMap())
        assertEquals(listOf(NotificationPolicy.Decision.CancelProgress), d)
    }

    @Test
    fun severalTerminalsInOneBatchAllNotify() {
        val d = policy.reduce(
            listOf(
                task("c1", TaskState.COMPLETED, resultPath = "/p/1"),
                task("c2", TaskState.COMPLETED, resultPath = "/p/2"),
                task("f1", TaskState.FAILED, lastError = "Http|404|")
            ),
            emptyMap()
        )
        // idle queue also cancels the progress notification
        assertEquals(4, d.size)
        assertEquals(1, d.count { it is NotificationPolicy.Decision.CancelProgress })
        assertEquals(2, d.count { it is NotificationPolicy.Decision.Completed })
        assertEquals(1, d.count { it is NotificationPolicy.Decision.Failed })
    }

    @Test
    fun humanReasonMapsEveryPersistedErrorType() {
        assertEquals("Network error", policy.humanReason("Network|timeout|null"))
        assertEquals("Server error 503", policy.humanReason("Http|503|"))
        assertEquals("Storage error", policy.humanReason("Storage|disk|err"))
        assertEquals("Bad page", policy.humanReason("InvalidRequest|Bad page|"))
        assertEquals("File already exists", policy.humanReason("FileAlreadyExists||"))
        assertEquals("Not enough space", policy.humanReason("InsufficientSpace||"))
        assertEquals("Download failed", policy.humanReason(null))
        assertEquals("Download failed", policy.humanReason("garbage"))
    }
}
