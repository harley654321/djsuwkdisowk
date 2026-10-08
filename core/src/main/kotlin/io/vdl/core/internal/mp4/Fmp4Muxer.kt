package io.vdl.core.internal.mp4

import io.vdl.core.internal.logging.VdlLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Atom-level mux of two concatenated fMP4 streams (video + optional audio,
 * as produced by the HLS downloader) into ONE playable fragmented MP4.
 * No transcode, no sample rebuild: the moov of the video init (mvhd,
 * trak, mvex) is merged with the audio init's trak + trex, moof/mdat
 * pairs are interleaved by tfdt decode time and copied verbatim, with
 * surgical rewrites only:
 *   1. tkhd.track_ID + trex.track_ID of the audio track on a collision
 *   2. tfhd.track_ID of every remapped fragment
 *   3. tfhd.base_data_offset (when present) -> the fragment's new position
 * trun data offsets stay untouched: they are relative to the base, and
 * moof+mdat keep their adjacency. mvex (trex defaults for trun) is
 * preserved from BOTH inits; without it, fragments whose trun omits
 * fields would be unparseable.
 */
internal object Fmp4Muxer {

    internal class Result(
        internal val bytesWritten: Long,
        internal val videoFragments: Int,
        internal val audioFragments: Int,
        internal val videoTrackId: Long,
        internal val audioTrackId: Long?
    )

    /** Streams [video] + [audio] into [out]; returns sizes/ids for logging upstream. */
    internal fun muxFiles(
        video: File,
        audio: File?,
        out: File,
        log: VdlLog,
        clockNanos: () -> Long = { System.nanoTime() }
    ): Result {
        val t0 = clockNanos()
        val (ftyp, videoMoov) = Fmp4Fragments.extractInit(video)
        val mvhd = videoMoov.firstOrNull { it.type == "mvhd" }
            ?: throw IllegalArgumentException("video init has no mvhd")
        val videoTrak = videoMoov.firstOrNull { it.type == "trak" }
            ?: throw IllegalArgumentException("video init has no trak")
        val videoTrackId = trackIdOf(videoTrak, "tkhd", tkhdIdOffset(videoTrak))
        val videoFragments = Fmp4Fragments.scan(video, log)
        if (videoFragments.isEmpty()) {
            log.w(TAG) { "no fragments indexed video=${video.name} decision=fatal reason=stream-without-moof" }
            throw IllegalArgumentException("video stream has no moof fragments")
        }

        var audioTrak: ByteArray? = null
        var audioMvexTrex: ByteArray? = null
        var audioTrackId: Long? = null
        var audioFragments: List<Fmp4Fragment> = emptyList()
        if (audio != null) {
            val (_, audioMoov) = Fmp4Fragments.extractInit(audio)
            val trak = audioMoov.firstOrNull { it.type == "trak" }
                ?: throw IllegalArgumentException("audio init has no trak")
            val srcId = trackIdOf(trak, "tkhd", tkhdIdOffset(trak))
            val newId = if (srcId == videoTrackId) videoTrackId + 1 else srcId
            val remapped = newId != srcId
            audioTrackId = newId
            audioTrak = buildTrak(trak, if (remapped) newId else null)
            audioMvexTrex = audioMoov.firstOrNull { it.type == "mvex" }?.let { mvex ->
                val trex = Mp4.readBoxes(mvex.body).firstOrNull { it.type == "trex" }
                trex?.let {
                    val body = it.body.copyOf()
                    if (remapped) Mp4.writeU32(body, 4, newId) // FullBox: track_ID at payload+4
                    Mp4.build("trex", body)
                }
            }
            if (remapped) log.i(TAG) { "track remap kind=audio old=$srcId new=$newId reason=collision" }
            audioFragments = Fmp4Fragments.scan(audio, log)
            if (audioFragments.isEmpty()) {
                log.w(TAG) { "no fragments indexed audio=${audio.name} decision=fatal reason=stream-without-moof" }
                throw IllegalArgumentException("audio stream has no moof fragments")
            }
        }

        // moov: mvhd + trakV + [trakA] + mvex(video trex..., [audio trex remapped])
        val mvex = buildMvex(videoMoov, audioMvexTrex, log)
        val moov = ByteArrayOutputStream().let { bos ->
            bos.write(Mp4.build("mvhd", mvhd.body))
            bos.write(Mp4.build("trak", videoTrak.body))
            audioTrak?.let { bos.write(it) }
            mvex?.let { bos.write(it) }
            Mp4.build("moov", bos.toByteArray())
        }
        log.i(TAG) {
            "moov built videoTrack=$videoTrackId audioTrack=${audioTrackId ?: "-"} mvex=${mvex != null} bytes=${moov.size}"
        }

        // interleave by tfdt decode time; stable sort keeps video first on ties
        data class Labeled(val frag: Fmp4Fragment, val isVideo: Boolean, val order: Int)
        val all = videoFragments.mapIndexed { i, f -> Labeled(f, true, i) } +
            audioFragments.mapIndexed { i, f -> Labeled(f, false, i) }
        val sorted = all.sortedWith(compareBy({ it.frag.decodeTime }, { it.order }))
        log.i(TAG) {
            "interleave plan=" + sorted.joinToString(",") { (if (it.isVideo) "v" else "a") + it.frag.sequence } + " by=tfdt"
        }

        var written = 0L
        var rewrittenBases = 0
        var remappedIds = 0
        FileOutputStream(out).use { fout ->
            fout.write(ftyp); written += ftyp.size
            fout.write(moov); written += moov.size
            for (item in sorted) {
                val f = item.frag
                val src = if (item.isVideo) video else audio!!
                val moofBytes = ByteArray((f.moofEnd - f.moofStart).toInt())
                RandomAccessFile(src, "r").use { raf ->
                    raf.seek(f.moofStart)
                    raf.readFully(moofBytes)
                }
                // 1) tfhd.track_ID remap (audio fragments of a remapped track)
                if (!item.isVideo && audioTrackId != null && f.trackId != audioTrackId) {
                    Mp4.writeU32(moofBytes, (f.tfhdTrackIdPos - f.moofStart).toInt(), audioTrackId)
                    remappedIds++
                }
                // 2) base_data_offset -> this moof's new absolute position
                f.baseDataOffsetPos?.let { pos ->
                    Mp4.writeU64(moofBytes, (pos - f.moofStart).toInt(), written)
                    rewrittenBases++
                }
                fout.write(moofBytes); written += moofBytes.size
                // 3) mdat: verbatim stream copy from source
                RandomAccessFile(src, "r").use { raf ->
                    raf.seek(f.mdatStart)
                    val buf = ByteArray(64 * 1024)
                    var remaining = f.mdatEnd - f.mdatStart
                    while (remaining > 0) {
                        val n = raf.read(buf, 0, remaining.toInt().coerceAtMost(buf.size))
                        if (n < 0) throw IOException("mdat truncated at ${f.mdatStart}")
                        fout.write(buf, 0, n)
                        written += n
                        remaining -= n
                    }
                }
            }
        }
        log.i(TAG) {
            "mux done bytes=$written videoFrags=${videoFragments.size} audioFrags=${audioFragments.size} " +
                "remappedIds=$remappedIds rewrittenBases=$rewrittenBases dtMs=${(clockNanos() - t0) / 1_000_000} out=${out.name}"
        }
        return Result(written, videoFragments.size, audioFragments.size, videoTrackId, audioTrackId)
    }

    // ------------------------------------------------------------ helpers

    /** tkhd.track_ID: v0 at payload+8, v1 at payload+16. */
    private fun tkhdIdOffset(trak: Mp4.Box): Int {
        val tkhd = Mp4.readBoxes(trak.body).firstOrNull { it.type == "tkhd" }
            ?: throw IllegalArgumentException("trak has no tkhd")
        // FullBox(4) + creation + modification: v0 4+4 -> 12 ; v1 8+8 -> 20
        return if (Mp4.version(tkhd.body) == 1) 20 else 12
    }

    private fun trackIdOf(trak: Mp4.Box, box: String, bodyOffset: Int): Long {
        val b = Mp4.readBoxes(trak.body).first { it.type == box }
        return Mp4.readU32(b.body, bodyOffset)
    }

    /** FULL trak BOX (size+type+body) with tkhd.track_ID rewritten when [newId] != null. */
    private fun buildTrak(trak: Mp4.Box, newId: Long?): ByteArray {
        val bos = ByteArrayOutputStream()
        for (b in Mp4.readBoxes(trak.body)) {
            if (b.type == "tkhd" && newId != null) {
                val body = b.body.copyOf()
                val off = if (Mp4.version(body) == 1) 20 else 12
                Mp4.writeU32(body, off, newId)
                bos.write(Mp4.build("tkhd", body))
            } else {
                bos.write(Mp4.build(b.type, b.body))
            }
        }
        return Mp4.build("trak", bos.toByteArray())
    }

    /** mvex from the video init + the (remapped) audio trex. */
    private fun buildMvex(videoMoov: List<Mp4.Box>, audioTrex: ByteArray?, log: VdlLog): ByteArray? {
        val videoMvex = videoMoov.firstOrNull { it.type == "mvex" }
        val children = ArrayList<ByteArray>()
        if (videoMvex != null) {
            for (b in Mp4.readBoxes(videoMvex.body)) children.add(Mp4.build(b.type, b.body))
        }
        audioTrex?.let { children.add(it) }
        if (children.isEmpty()) {
            log.w(TAG) { "no mvex in either init decision=proceed reason=trun-may-carry-all-fields" }
            return null
        }
        val bos = ByteArrayOutputStream()
        children.forEach { bos.write(it) }
        return Mp4.build("mvex", bos.toByteArray())
    }

    private const val TAG = "[VDL][MP4][muxer]"
}
