package io.vdl.core.internal.mp4

import io.vdl.core.internal.logging.VdlLog
import java.io.File
import java.io.RandomAccessFile

/**
 * Indexes the fragment stream of a concatenated fMP4 file
 * (init segment + media segments, as produced by the HLS downloader):
 * every moof paired with its following mdat, with the fields the muxer
 * must know: track id, mfhd sequence, tfdt decode time, and the source
 * positions it may need to rewrite (track id / base_data_offset).
 *
 * The file is WALKED box by box (8/16-byte headers, only moof loaded):
 * multi-hundred-MB streams are never held in RAM.
 *
 * Fragment-addressed top-level boxes are DROPPED on mux with a logged
 * decision: styp (per-segment type info), sidx (its references go stale
 * the moment fragments move) and free/skip/prft.
 */
internal class Fmp4Fragment internal constructor(
    internal val trackId: Long,
    internal val sequence: Long,
    internal val decodeTime: Long,
    internal val moofStart: Long,
    internal val moofEnd: Long,
    internal val mdatStart: Long,
    internal val mdatEnd: Long,
    internal val tfhdTrackIdPos: Long,     // absolute file position
    internal val baseDataOffsetPos: Long?  // absolute; null = default-base-is-moof
)

internal object Fmp4Fragments {

    /** Walks [f] and indexes every moof->mdat pair. */
    internal fun scan(f: File, log: VdlLog? = null): List<Fmp4Fragment> {
        val out = ArrayList<Fmp4Fragment>()
        RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            var off = 0L
            var pending: Partial? = null
            val hdr = ByteArray(16)
            while (off + 8 <= len) {
                raf.seek(off)
                raf.readFully(hdr, 0, 8)
                var size = Mp4.readU32(hdr, 0).toInt()
                var header = 8
                if (size == 1) {
                    raf.readFully(hdr, 8, 8)
                    size = Mp4.readU64(hdr, 8).toInt()
                    header = 16
                } else if (size == 0) {
                    size = (len - off).toInt()
                }
                if (size < header || off + size > len) break
                when (String(hdr, 4, 4, Charsets.US_ASCII)) {
                    "moof" -> {
                        val buf = ByteArray(size)
                        raf.seek(off)
                        raf.readFully(buf)
                        pending = parseMoof(buf, off)
                    }
                    "mdat" -> {
                        val p = pending
                        if (p != null) {
                            out.add(
                                Fmp4Fragment(
                                    trackId = p.trackId,
                                    sequence = p.sequence,
                                    decodeTime = p.decodeTime,
                                    moofStart = p.moofStart,
                                    moofEnd = p.moofEnd,
                                    mdatStart = off,
                                    mdatEnd = off + size,
                                    tfhdTrackIdPos = p.tfhdTrackIdPos,
                                    baseDataOffsetPos = p.baseDataOffsetPos
                                )
                            )
                        }
                        pending = null
                    }
                    "styp", "sidx", "free", "skip", "prft" ->
                        log?.d(TAG) { "dropped box type=${String(hdr, 4, 4, Charsets.US_ASCII)} off=$off size=$size reason=fragment-addressed-or-padding" }
                }
                off += size
            }
        }
        return out
    }

    private class Partial(
        val trackId: Long,
        val sequence: Long,
        val decodeTime: Long,
        val moofStart: Long,
        val moofEnd: Long,
        val tfhdTrackIdPos: Long,
        val baseDataOffsetPos: Long?
    )

    /** Parses a moof buffer starting at file offset [fileOff]. */
    private fun parseMoof(buf: ByteArray, fileOff: Long): Partial? {
        var trackId = -1L
        var sequence = -1L
        var decodeTime = -1L
        var tfhdTrackIdPos = -1L
        var baseDataOffsetPos: Long? = null
        var off = 8
        while (off + 8 <= buf.size) {
            val size = Mp4.readU32(buf, off).toInt()
            if (size < 8 || off + size > buf.size) break
            when (String(buf, off + 4, 4, Charsets.US_ASCII)) {
                "mfhd" -> sequence = Mp4.readU32(buf, off + 12)
                "traf" -> {
                    var t = off + 8
                    while (t + 8 <= off + size) {
                        val tSize = Mp4.readU32(buf, t).toInt()
                        if (tSize < 8 || t + tSize > off + size) break
                        when (String(buf, t + 4, 4, Charsets.US_ASCII)) {
                            "tfhd" -> {
                                trackId = Mp4.readU32(buf, t + 12)
                                tfhdTrackIdPos = t + 12 + fileOff
                                // FullBox flags at t+8..t+11; base_data_offset follows track_ID
                                val fl = Mp4.flags(buf, t + 8)
                                if (fl and 0x01 != 0) baseDataOffsetPos = t + 16 + fileOff
                            }
                            "tfdt" -> {
                                val ver = buf[t + 8].toInt() and 0xFF
                                decodeTime = if (ver == 1) Mp4.readU64(buf, t + 12) else Mp4.readU32(buf, t + 12)
                            }
                        }
                        t += tSize
                    }
                }
            }
            if (sequence >= 0 && trackId >= 0 && decodeTime >= 0) break
            off += size
        }
        if (trackId < 0 || tfhdTrackIdPos < 0) return null
        return Partial(trackId, sequence, decodeTime, fileOff, fileOff + buf.size, tfhdTrackIdPos, baseDataOffsetPos)
    }

    /** Init section: raw ftyp bytes + moov children (mvhd, trak, mvex...). */
    internal fun extractInit(f: File): Pair<ByteArray, List<Mp4.Box>> {
        RandomAccessFile(f, "r").use { raf ->
            // ftyp = everything before the moov, first ftyp box only
            var off = 0L
            val len = raf.length()
            val hdr = ByteArray(8)
            var ftyp: ByteArray? = null
            var moovChildren: List<Mp4.Box> = emptyList()
            while (off + 8 <= len) {
                raf.seek(off)
                raf.readFully(hdr)
                val size = Mp4.readU32(hdr, 0).toInt()
                if (size < 8 || off + size > len) break
                when (String(hdr, 4, 4, Charsets.US_ASCII)) {
                    "ftyp" -> {
                        if (ftyp == null) {
                            val buf = ByteArray(size)
                            raf.seek(off)
                            raf.readFully(buf)
                            val b = Mp4.readBoxes(buf).first()
                            ftyp = Mp4.build("ftyp", b.body)
                        }
                    }
                    "moov" -> {
                        val buf = ByteArray(size)
                        raf.seek(off)
                        raf.readFully(buf)
                        moovChildren = Mp4.readBoxes(buf.copyOfRange(8, size))
                    }
                }
                if (moovChildren.isNotEmpty()) break
                off += size
            }
            return (ftyp ?: ByteArray(0)) to moovChildren
        }
    }

    private const val TAG = "[VDL][MP4][index]"
}
