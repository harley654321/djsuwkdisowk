package io.vdl.core.internal.engine

import io.vdl.core.internal.logging.VdlLog
import java.io.File

/**
 * Unit-level resume ledger: a sidecar file next to the target output that
 * records, for each already-written unit, its index and its byte range in
 * the output. A download that is interrupted mid-track can then skip the
 * units that are already on disk and continue from the first missing one.
 *
 * Format: one line per completed unit, "<index> <offset> <length>".
 *
 * Invariants enforced by [load]:
 * - lines must be strictly increasing by index
 * - offsets must chain: entry[n].offset == entry[n-1].offset + entry[n-1].length
 * - the output file must actually be at least offset+length bytes long
 * Any violation discards the ledger (logged, never hidden) so the downloader
 * falls back to a full restart.
 *
 * Writes are atomic: the whole file is rewritten to a temp file and renamed,
 * so a crash mid-write can never leave a half-line ledger behind.
 */
internal class UnitLedger(
    private val file: File,
    private val log: VdlLog,
    private val tag: String
) {
    /** One completed unit: [offset] + [length] mark its byte range in the output. */
    data class Entry(val index: Int, val offset: Long, val length: Long)

    companion object {
        /** Sidecar path for a given output file: "<out>.vdl-units". */
        fun forOut(out: File): File = File(out.parentFile, out.name + ".vdl-units")
    }

    /** Completed units in order; empty when absent, corrupt or inconsistent. */
    fun load(outLength: Long): List<Entry> {
        if (!file.exists()) return emptyList()
        val entries = try {
            file.readLines().mapNotNull { line ->
                val p = line.split(' ')
                if (p.size != 3) null
                else Entry(p[0].toInt(), p[1].toLong(), p[2].toLong())
            }
        } catch (t: Throwable) {
            log.w(tag) { "ledger read failed out=${outRef()} err=${t.message} decision=discard" }
            return emptyList()
        }
        var expectedOffset = 0L
        var prevIndex = -1
        for (e in entries) {
            if (e.index <= prevIndex || e.offset != expectedOffset || e.length <= 0L) {
                log.w(tag) { "ledger inconsistent out=${outRef()} entry=${e.index} decision=discard" }
                return emptyList()
            }
            if (e.offset + e.length > outLength) {
                log.w(tag) { "ledger beyond file out=${outRef()} entry=${e.index} decision=discard" }
                return emptyList()
            }
            expectedOffset = e.offset + e.length
            prevIndex = e.index
        }
        return entries
    }

    /** Persist the full entry list atomically (small file, few units). */
    fun save(entries: List<Entry>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writeText(entries.joinToString("") { "${it.index} ${it.offset} ${it.length}\n" })
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) {
                    log.e(tag) { "ledger rename failed out=${outRef()} decision=continue-without-resume" }
                }
            }
        } catch (t: Throwable) {
            tmp.delete()
            log.e(tag) { "ledger write failed out=${outRef()} err=${t.message} decision=continue-without-resume" }
        }
    }

    /** Remove the ledger (download finished successfully: nothing to resume). */
    fun clear() {
        if (file.exists() && !file.delete()) {
            log.e(tag) { "ledger delete failed out=${outRef()} decision=retry-on-next-success" }
        }
    }

    private fun outRef(): String = file.name.removeSuffix(".vdl-units")
}
