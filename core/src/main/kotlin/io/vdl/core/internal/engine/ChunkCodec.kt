package io.vdl.core.internal.engine

/**
 * A byte region of the remote file plus how much of it we already have
 * on disk. `end == -1` marks an unknown-length stream.
 */
internal data class ChunkProgress(
    internal val start: Long,
    internal val end: Long,
    internal val downloaded: Long
) {
    internal val length: Long
        get() = if (end < 0) -1L else end - start + 1L

    internal val isComplete: Boolean
        get() = length > 0 && downloaded >= length
}

/** "start-end-downloaded;..." codec for the flat DB column. */
internal object ChunkCodec {

    internal fun encode(chunks: List<ChunkProgress>): String =
        chunks.joinToString(";") { "${it.start}-${it.end}-${it.downloaded}" }

    internal fun decode(raw: String?): List<ChunkProgress> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<ChunkProgress>()
        for (part in raw.split(';')) {
            val f = part.split('-')
            if (f.size != 3) continue
            val start = f[0].toLongOrNull() ?: continue
            val end = f[1].toLongOrNull() ?: continue
            val done = f[2].toLongOrNull() ?: continue
            if (start < 0 || done < 0) continue
            out.add(ChunkProgress(start, end, done.coerceAtMost((end - start + 1).coerceAtLeast(0))))
        }
        // defensive: never let a corrupt row produce overlapping chunks
        val dedup = out.distinctBy { it.start }.sortedBy { it.start }
        val bounded = ArrayList<ChunkProgress>()
        var nextStart = 0L
        for (c in dedup) {
            bounded.add(c.copy(start = c.start.coerceAtLeast(nextStart)))
            nextStart = (c.end + 1).coerceAtLeast(0)
        }
        return bounded
    }
}
