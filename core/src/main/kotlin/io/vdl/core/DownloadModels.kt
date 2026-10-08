package io.vdl.core

/** Task priority. Higher priority tasks are dispatched first. */
public enum class Priority {
    HIGH, NORMAL, LOW
}

/** Snapshot of live progress for a running download. */
public data class Progress(
    public val bytesDownloaded: Long,
    public val bytesTotal: Long,
    public val speedBps: Long,
    public val etaMs: Long,
    public val activeThreads: Int
)

/** Immutable, reactive state of a single download task. */
public sealed interface DownloadState {
    /** Enqueued, waiting to be dispatched (queue full, no network, wifi-only waiting, etc). */
    public data object Queued : DownloadState

    /** Actively transferring bytes. */
    public data class Downloading(public val progress: Progress) : DownloadState

    /** Paused by the user or by the system (e.g. dataSync timeout). Persisted and resumable. */
    public data object Paused : DownloadState

    /** Finished and published to the requested destination. */
    public data class Completed(
        public val uri: String?,
        public val filePath: String?
    ) : DownloadState

    /** Terminally failed after exhausting retries or hitting an unrecoverable error. */
    public data class Failed(public val error: DownloadError) : DownloadState

    /** Cancelled by the user. */
    public data object Cancelled : DownloadState
}

/** Typed, sealed errors. Never parse strings to know what failed. */
public sealed interface DownloadError {
    public data class Network(public val message: String, public val cause: String?) : DownloadError
    public data class Http(public val code: Int, public val message: String?) : DownloadError
    public data class Storage(public val message: String, public val cause: String?) : DownloadError
    public data class InvalidRequest(public val message: String) : DownloadError
    public data object FileAlreadyExists : DownloadError
    public data object InsufficientSpace : DownloadError
}

/** Point-in-time view of a task, exposed via [VdlDownloader.observeAll]. */
public data class TaskSnapshot(
    public val id: String,
    public val url: String,
    public val fileName: String,
    public val state: DownloadState,
    public val bytesDownloaded: Long,
    public val bytesTotal: Long,
    public val priority: Priority
)

