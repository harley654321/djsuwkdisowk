package io.vdl.core.internal.db

import io.vdl.core.DownloadError

/** Internal persisted state machine. Mirrors [io.vdl.core.DownloadState] minus live progress. */
internal enum class TaskState {
    PENDING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED;

    internal companion object {
        internal fun from(raw: String?): TaskState =
            runCatching { valueOf(raw ?: "") }.getOrDefault(PENDING)
    }
}

/**
 * Compact string codec for [DownloadError] so failures survive a flat DB
 * column without JSON machinery: "TYPE|message|code".
 */
internal object ErrorCodec {

    internal fun encode(error: DownloadError): String = when (error) {
        is DownloadError.Network -> "Network|${error.message}|${error.cause ?: ""}"
        is DownloadError.Http -> "Http|${error.code}|${error.message ?: ""}"
        is DownloadError.Storage -> "Storage|${error.message}|${error.cause ?: ""}"
        is DownloadError.InvalidRequest -> "InvalidRequest|${error.message}|"
        DownloadError.FileAlreadyExists -> "FileAlreadyExists||"
        DownloadError.InsufficientSpace -> "InsufficientSpace||"
    }

    internal fun decode(raw: String?): DownloadError? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split('|', limit = 3)
        val msg = parts.getOrElse(1) { "" }
        val aux = parts.getOrElse(2) { "" }
        return when (parts.firstOrNull()) {
            "Network" -> DownloadError.Network(msg, aux.ifBlank { null })
            "Http" -> msg.toIntOrNull()?.let { DownloadError.Http(it, aux.ifBlank { null }) }
                ?: DownloadError.Network("HTTP failure: $msg", null)
            "Storage" -> DownloadError.Storage(msg, aux.ifBlank { null })
            "InvalidRequest" -> DownloadError.InvalidRequest(msg)
            "FileAlreadyExists" -> DownloadError.FileAlreadyExists
            "InsufficientSpace" -> DownloadError.InsufficientSpace
            else -> null
        }
    }
}
