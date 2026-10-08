package io.vdl.core.internal.storage

import io.vdl.core.Destination

/**
 * Pure publication plan derived from a Destination: which Android
 * collection to target, whether MediaStore IS_PENDING gives an atomic
 * publish, the MIME type and the legacy base dir for pre-Q devices.
 * All decisions here are JVM-testable; the executors in
 * [AndroidStoragePublisher] stay thin Android glue.
 */
internal class StoragePlan internal constructor(
    /** MediaStore collection uri name, or "saf", or "file". */
    internal val collection: String,
    /** MediaStore relative-path base (null for saf/file). */
    internal val relativeBase: String?,
    /** Legacy (API < 29) public dir constant name, null = not legacy-supported. */
    internal val legacyDir: String?,
    /** True when MediaStore IS_PENDING gives an atomic publish (API 29+). */
    internal val atomicPending: Boolean,
    internal val mime: String
) {
    internal companion object {
        internal val DOWNLOADS = "downloads"
        internal val VIDEO = "video"
        internal val AUDIO = "audio"
        internal val SAF = "saf"
        internal val FILE = "file"
    }
}

internal object StoragePlanner {

    /** Decides the publication plan for [dest]; [sdkInt] defaults to device. */
    internal fun plan(
        dest: Destination,
        fileName: String,
        contentType: String?,
        sdkInt: Int
    ): StoragePlan {
        val mime = mimeFor(fileName, contentType)
        return when (dest) {
            is Destination.PublicDownloads -> StoragePlan(
                collection = StoragePlan.DOWNLOADS,
                relativeBase = relPath("Download", dest.subfolder),
                legacyDir = "Download",
                atomicPending = sdkInt >= SdkConsts.SDK_Q,
                mime = mime
            )
            is Destination.Gallery -> StoragePlan(
                collection = if (mime.startsWith("audio/")) StoragePlan.AUDIO else StoragePlan.VIDEO,
                relativeBase = relPath(
                    if (mime.startsWith("audio/")) "Music" else "Movies",
                    dest.subfolder
                ),
                legacyDir = if (mime.startsWith("audio/")) "Music" else "Movies",
                atomicPending = sdkInt >= SdkConsts.SDK_Q,
                mime = mime
            )
            is Destination.SafTree -> StoragePlan(
                // SAF writes are NOT transactional: the document becomes
                // visible when the stream closes; failure cleanup = delete.
                collection = StoragePlan.SAF,
                relativeBase = null,
                legacyDir = null,
                atomicPending = false,
                mime = mime
            )
            is Destination.AppPrivate -> StoragePlan(
                collection = StoragePlan.FILE,
                relativeBase = null,
                legacyDir = null,
                atomicPending = false,
                mime = mime
            )
        }
    }

    /**
     * Content type for MediaStore / SAF: server-provided wins, else the
     * file extension decides, else generic binary.
     */
    internal fun mimeFor(fileName: String, contentType: String?): String {
        contentType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }?.let { return it }
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "ts" -> "video/mp2t"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "m4s" -> "video/iso.segment"
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "opus" -> "audio/opus"
            "flac" -> "audio/flac"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }
    }

    private fun relPath(base: String, subfolder: String?): String =
        if (subfolder.isNullOrBlank()) base
        else base + "/" + io.vdl.core.FileNameSanitizer.sanitize(subfolder)
}

internal object SdkConsts {
    /** API 29 (Q): MediaStore IS_PENDING + scoped storage. */
    internal const val SDK_Q = 29
}
