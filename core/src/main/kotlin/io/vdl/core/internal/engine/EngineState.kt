package io.vdl.core.internal.engine

import io.vdl.core.Progress
import java.io.File

/**
 * Live state the engine owns during a run. The queue reads it only after the
 * engine coroutine was joined, so the join provides the happens-before edge;
 * [Volatile] covers reads from the progress path.
 *
 * [chunks] is always REPLACED (never mutated) so sharing the reference is safe.
 */
internal class EngineState internal constructor() {
    @Volatile internal var bytesTotal: Long = 0L
    @Volatile internal var etag: String? = null
    @Volatile internal var acceptRanges: Boolean = false
    @Volatile internal var contentType: String? = null
    @Volatile internal var chunks: List<ChunkProgress> = emptyList()
}

internal sealed interface EngineOutcome {
    internal data class Success(internal val bytesTotal: Long) : EngineOutcome
    internal data class Retryable(internal val message: String, internal val httpCode: Int? = null) : EngineOutcome
    internal data class Fatal(internal val message: String, internal val httpCode: Int? = null) : EngineOutcome
}

/** Seam so the queue can be unit-tested with a scripted fake engine. */
internal fun interface DownloadEngine {
    internal suspend fun execute(
        task: io.vdl.core.internal.db.DownloadTaskEntity,
        partFile: File,
        state: EngineState,
        onProgress: (Progress) -> Unit
    ): EngineOutcome
}
