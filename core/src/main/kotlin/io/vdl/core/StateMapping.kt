package io.vdl.core

import io.vdl.core.internal.db.DownloadTaskEntity
import io.vdl.core.internal.db.ErrorCodec
import io.vdl.core.internal.db.TaskState

/** Maps a persisted entity (+ live progress) to the public state machine. */
internal fun mapState(entity: DownloadTaskEntity?, progress: Progress?): DownloadState {
    if (entity == null) return DownloadState.Queued
    return when (TaskState.from(entity.state)) {
        TaskState.PENDING -> DownloadState.Queued
        TaskState.RUNNING -> progress?.let { DownloadState.Downloading(it) }
            ?: DownloadState.Downloading(
                Progress(
                    bytesDownloaded = entity.bytesDownloaded,
                    bytesTotal = if (entity.bytesTotal > 0) entity.bytesTotal else -1L,
                    speedBps = 0L,
                    etaMs = -1L,
                    activeThreads = 0
                )
            )
        TaskState.PAUSED -> DownloadState.Paused
        TaskState.COMPLETED -> DownloadState.Completed(entity.resultUri, entity.resultPath)
        TaskState.FAILED -> DownloadState.Failed(
            ErrorCodec.decode(entity.lastError)
                ?: DownloadError.Network("unknown failure", null)
        )
        TaskState.CANCELLED -> DownloadState.Cancelled
    }
}

internal fun DownloadTaskEntity.toSnapshot(progress: Progress?): TaskSnapshot = TaskSnapshot(
    id = id,
    url = url,
    fileName = fileName,
    state = mapState(this, progress),
    bytesDownloaded = progress?.bytesDownloaded ?: bytesDownloaded,
    bytesTotal = if (progress != null && progress.bytesTotal > 0) progress.bytesTotal else bytesTotal,
    priority = priorityEnum()
)
