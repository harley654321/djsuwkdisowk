package io.vdl.core.internal.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.vdl.core.Destination
import io.vdl.core.DownloadRequest
import io.vdl.core.Priority
import io.vdl.core.RetryPolicy
import java.util.UUID

/**
 * Persistent download task. UNIQUE(url, fileName) is the anti-duplicate
 * guarantee; the queue also checks in memory before hitting the DB.
 */
@Entity(
    tableName = "tasks",
    indices = [Index(value = ["url", "fileName"], unique = true)]
)
internal data class DownloadTaskEntity(
    @PrimaryKey val id: String,
    val url: String,
    val fileName: String,
    @androidx.room.ColumnInfo(defaultValue = "DIRECT") val kind: String = "DIRECT",
    val maxHeight: Int? = null,
    val destinationType: String,
    val destinationSubfolder: String?,
    val headersEnc: String,
    val priority: String,
    val state: String,
    val bytesTotal: Long,
    val bytesDownloaded: Long,
    val etag: String?,
    val contentType: String?,
    val acceptRanges: Boolean,
    val chunksEnc: String,
    val attempt: Int,
    val wifiOnly: Boolean,
    val showNotification: Boolean,
    val threads: Int,
    val chunkSizeBytes: Long,
    val retryBaseDelayMs: Long,
    val retryMaxAttempts: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val lastError: String?,
    val resultUri: String?,
    val resultPath: String?
) {
    internal fun withState(state: TaskState, now: Long, error: String? = lastError): DownloadTaskEntity =
        copy(state = state.name, updatedAt = now, lastError = error)

    internal fun priorityEnum(): Priority =
        runCatching { Priority.valueOf(priority) }.getOrDefault(Priority.NORMAL)

    internal fun destination(): Destination =
        Destination.fromStrings(destinationType, destinationSubfolder)

    internal fun retryPolicy(): RetryPolicy =
        RetryPolicy.fromStrings(retryBaseDelayMs, retryMaxAttempts)

    internal companion object {
        internal fun fromRequest(request: DownloadRequest, now: Long, existingId: String? = null): DownloadTaskEntity {
            val dest = request.destination
            val (type, sub) = when (dest) {
                is Destination.PublicDownloads -> "PUBLIC_DOWNLOADS" to dest.subfolder
                is Destination.AppPrivate -> "APP_PRIVATE" to dest.subfolder
                is Destination.Gallery -> "GALLERY" to dest.subfolder
                // the tree uri travels in the subfolder column
                is Destination.SafTree -> "SAF" to dest.treeUri
            }
            return DownloadTaskEntity(
                id = existingId ?: UUID.randomUUID().toString(),
                url = request.url,
                fileName = request.fileName,
                kind = request.kind.name,
                maxHeight = request.maxHeight,
                destinationType = type,
                destinationSubfolder = sub,
                headersEnc = io.vdl.core.DownloadRequestBuilder.encodeHeaders(request.headers),
                priority = request.priority.name,
                state = TaskState.PENDING.name,
                bytesTotal = 0L,
                bytesDownloaded = 0L,
                etag = null,
                contentType = null,
                acceptRanges = false,
                chunksEnc = "",
                attempt = 0,
                wifiOnly = request.wifiOnly,
                showNotification = request.showNotification,
                threads = request.threads,
                chunkSizeBytes = request.chunkSizeBytes,
                retryBaseDelayMs = request.retryPolicy.baseDelayMs,
                retryMaxAttempts = request.retryPolicy.maxAttempts,
                createdAt = now,
                updatedAt = now,
                lastError = null,
                resultUri = null,
                resultPath = null
            )
        }
    }
}
