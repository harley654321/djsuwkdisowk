package io.vdl.core.internal.storage

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import io.vdl.core.Destination
import io.vdl.core.FileNameSanitizer
import io.vdl.core.internal.db.DownloadTaskEntity
import io.vdl.core.internal.logging.VdlLog
import io.vdl.core.internal.queue.PartFileFactory
import io.vdl.core.internal.queue.PublishedResult
import io.vdl.core.internal.queue.SpaceChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Raised when the destination already exists; mapped to DownloadError.FileAlreadyExists. */
internal class DestinationFileExistsException internal constructor(
    internal val fileName: String
) : IOException("destination already exists: $fileName")

internal class AndroidPartFileFactory internal constructor(
    context: Context
) : PartFileFactory {

    private val dir = File(context.applicationContext.cacheDir, "vdl_parts").apply { mkdirs() }

    override fun partFor(id: String, fileName: String): File {
        val f = File(dir, "$id.part")
        if (!f.exists()) f.createNewFile()
        return f
    }

    override fun cleanup(id: String) {
        val f = File(dir, "$id.part")
        if (f.exists() && !f.delete()) {
            // logged by caller context is unavailable here; best effort delete
        }
    }
}

internal class AndroidSpaceChecker internal constructor(
    context: Context
) : SpaceChecker {

    private val cacheDir = context.applicationContext.cacheDir

    override fun availableBytes(): Long = runCatching {
        StatFs(cacheDir.absolutePath).availableBytes
    }.getOrDefault(-1L)
}

/**
 * Publishes the finished part file to its destination.
 *
 * - PublicDownloads API 29+: MediaStore.Downloads + IS_PENDING (atomic)
 * - PublicDownloads API 24-28: legacy public dir (WRITE_EXTERNAL_STORAGE maxSdk 28)
 * - AppPrivate: getExternalFilesDir fallback filesDir, temp file + atomic rename
 */
internal class AndroidStoragePublisher internal constructor(
    private val context: Context,
    private val log: VdlLog
) : io.vdl.core.internal.queue.PartPublisher {

    override suspend fun publish(part: File, task: DownloadTaskEntity): PublishedResult =
        withContext(Dispatchers.IO) {
            when (val dest = task.destination()) {
                is Destination.AppPrivate -> publishPrivate(part, dest, task)
                is Destination.PublicDownloads -> publishPublic(part, dest, task)
            }
        }

    private fun publishPrivate(
        part: File,
        dest: Destination.AppPrivate,
        task: DownloadTaskEntity
    ): PublishedResult {
        val base = File(context.getExternalFilesDir(null) ?: context.filesDir, dest.subfolder ?: "")
        if (!base.exists() && !base.mkdirs()) {
            throw IOException("cannot create dir ${base.absolutePath}")
        }
        val target = File(base, task.fileName)
        if (target.exists()) throw DestinationFileExistsException(task.fileName)
        val tmp = File(base, "${task.fileName}.tmp")
        copy(part, tmp)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("rename failed: ${tmp.absolutePath}")
        }
        log.i(TAG) { "published private file=${target.absolutePath} bytes=${part.length()}" }
        return PublishedResult(null, target.absolutePath)
    }

    private fun publishPublic(
        part: File,
        dest: Destination.PublicDownloads,
        task: DownloadTaskEntity
    ): PublishedResult {
        val sub = dest.subfolder?.let { FileNameSanitizer.sanitize(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, task.fileName)
                put(
                    MediaStore.MediaColumns.MIME_TYPE,
                    task.contentType ?: "application/octet-stream"
                )
                val rel = if (sub.isNullOrBlank()) {
                    Environment.DIRECTORY_DOWNLOADS
                } else {
                    Environment.DIRECTORY_DOWNLOADS + "/" + sub
                }
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert returned null")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    part.inputStream().use { it.copyTo(out, 256 * 1024) }
                } ?: throw IOException("openOutputStream returned null")
                val clear = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, clear, null, null)
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
            log.i(TAG) { "published mediaStore uri=$uri name=${task.fileName}" }
            return PublishedResult(uri.toString(), null)
        }
        // API 24-28 legacy path
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val base = if (sub.isNullOrBlank()) publicDir else File(publicDir, sub)
        if (!base.exists() && !base.mkdirs()) {
            throw IOException("cannot create dir ${base.absolutePath}")
        }
        val target = File(base, task.fileName)
        if (target.exists()) throw DestinationFileExistsException(task.fileName)
        val tmp = File(base, "${task.fileName}.tmp")
        copy(part, tmp)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("rename failed: ${tmp.absolutePath}")
        }
        log.i(TAG) { "published legacy file=${target.absolutePath}" }
        return PublishedResult(null, target.absolutePath)
    }

    private fun copy(src: File, dst: File) {
        dst.outputStream().use { out -> src.inputStream().use { it.copyTo(out, 256 * 1024) } }
    }

    internal companion object {
        internal const val TAG = "[VDL][STORAGE]"
    }
}
