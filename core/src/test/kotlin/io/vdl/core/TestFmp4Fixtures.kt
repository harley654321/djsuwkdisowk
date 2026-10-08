package io.vdl.core

import io.vdl.core.internal.mp4.Mp4

/**
 * Byte-exact minimal fMP4 fixtures (ISO 14496-12 layouts) for the muxer
 * tests: ftyp, moov(mvhd, trak(tkhd, mdia(mdhd, hdlr)), mvex(trex)),
 * segments = styp + moof(mfhd, traf(tfhd, tfdt, trun)) + mdat.
 *
 * Body layouts (all FullBox: version 1B + flags 3B first):
 *  mvhd v0 (96B): ctime4 mtime4 timescale@12 dur@16 rate@20 vol@22 matrix@26 nextTrkId@92
 *  tkhd v0 (84B): ctime4 mtime4 trackID@12 dur@20 layer@30 vol@36 matrix@40 w@80 h@84->(82,84)... width/height last 8 bytes
 *  tfdt v0 (8B):  time@4   tfhd: trackID@4 (+base_data_offset@8 if flag 0x01)
 */
internal object TestFmp4Fixtures {

    private fun full(version: Int, flags: Int) = Mp4.fullBoxHeader(version, flags)

    internal fun ftyp(major: String = "iso5"): ByteArray {
        val body = ByteArray(12)
        System.arraycopy(major.toByteArray(Charsets.US_ASCII), 0, body, 0, 4)
        return Mp4.build("ftyp", body)
    }

    internal fun mvhd(timescale: Long = 1000): ByteArray {
        // v0 body = 100B: ver/flags4 ctime4 mtime4 timescale@12 dur@16 rate@20
        // volume@24 res2@26 res8@28 matrix(36)@36 predefined(24)@72 nextTrkId@96
        val b = ByteArray(100)
        System.arraycopy(full(0, 0), 0, b, 0, 4)
        Mp4.writeU32(b, 12, timescale)
        Mp4.writeU32(b, 16, 0L)          // duration: fragmented -> 0
        Mp4.writeU32(b, 20, 0x00010000)  // rate = 1.0
        b[24] = 0x01.toByte()            // volume
        Mp4.writeU32(b, 36, 0x00010000)  // unity matrix: entries 0, 4, 8
        Mp4.writeU32(b, 52, 0x00010000)
        Mp4.writeU32(b, 68, 0x40000000)
        Mp4.writeU32(b, 96, 0xFFFFFFFFL) // next_track_ID
        return Mp4.build("mvhd", b)
    }

    internal fun tkhd(trackId: Long, audio: Boolean): ByteArray {
        val b = ByteArray(84)
        System.arraycopy(full(0, 0), 0, b, 0, 4)
        Mp4.writeU32(b, 12, trackId)     // ctime(4) mtime(4) -> track_ID @12
        Mp4.writeU32(b, 20, 0L)          // duration
        // layer(2)@32? layout: res(8)@24 layer@32? compute: after dur@20: res(8)@24 layer(2)@32 alt(2)@34 vol(2)@36 res(2)@38 matrix@40
        if (audio) { b[36] = 0x01.toByte() }
        Mp4.writeU32(b, 40, 0x00010000)
        Mp4.writeU32(b, 56, 0x00010000)
        Mp4.writeU32(b, 72, 0x40000000)
        // width/height: last 8 bytes of the 84-byte body
        Mp4.writeU32(b, 76, 0L)
        Mp4.writeU32(b, 80, 0L)
        return Mp4.build("tkhd", b)
    }

    internal fun trak(trackId: Long, timescale: Long, handler: String, audio: Boolean): ByteArray {
        val mdhd = ByteArray(20)
        System.arraycopy(full(0, 0), 0, mdhd, 0, 4)
        Mp4.writeU32(mdhd, 12, timescale)
        val hdlr = ByteArray(21)
        System.arraycopy(full(0, 0), 0, hdlr, 0, 4)
        System.arraycopy(handler.toByteArray(Charsets.US_ASCII), 0, hdlr, 8, 4)
        val mdia = Mp4.build("mdia", Mp4.build("mdhd", mdhd) + Mp4.build("hdlr", hdlr))
        return Mp4.build("trak", tkhd(trackId, audio) + mdia)
    }

    internal fun trex(trackId: Long, defaultDuration: Long = 1024): ByteArray {
        val b = ByteArray(24)
        System.arraycopy(full(0, 0), 0, b, 0, 4)
        Mp4.writeU32(b, 4, trackId)
        Mp4.writeU32(b, 8, 1)
        Mp4.writeU32(b, 12, defaultDuration)
        return Mp4.build("trex", b)
    }

    internal fun initStream(trackId: Long, timescale: Long, handler: String, audio: Boolean): ByteArray {
        val moov = Mp4.build(
            "moov",
            mvhd(timescale) + trak(trackId, timescale, handler, audio) +
                Mp4.build("mvex", trex(trackId))
        )
        return ftyp() + moov
    }

    /** One media segment: [styp] + [sidx] + moof(mfhd, traf(tfhd, tfdt, trun)) + mdat. */
    internal fun segment(
        trackId: Long,
        sequence: Long,
        decodeTime: Long,
        payload: ByteArray,
        withStyp: Boolean = true,
        withSidx: Boolean = false,
        useBaseDataOffset: Boolean = false
    ): ByteArray {
        val styp = if (withStyp) {
            val body = ByteArray(12)
            System.arraycopy("msdh".toByteArray(Charsets.US_ASCII), 0, body, 0, 4)
            Mp4.build("styp", body)
        } else ByteArray(0)
        val sidx = if (withSidx) Mp4.build("sidx", ByteArray(24)) else ByteArray(0)

        val mfhd = Mp4.build("mfhd", full(0, 0) + u32(sequence))
        val tfhdFlags = if (useBaseDataOffset) 0x020001 else 0x020000 // default-base-is-moof
        val tfhdBody = full(0, tfhdFlags) + u32(trackId) +
            (if (useBaseDataOffset) u64(0L) else ByteArray(0))
        val tfhd = Mp4.build("tfhd", tfhdBody)
        val tfdt = Mp4.build("tfdt", full(0, 0) + u32(decodeTime))
        // trun: 1 sample, data_offset present (relative to base = moof start)
        val trun = Mp4.build("trun", full(0, 0x000001) + u32(1) + u32(36))
        val traf = Mp4.build("traf", tfhd + tfdt + trun)
        val moof = Mp4.build("moof", mfhd + traf)
        return styp + sidx + moof + Mp4.build("mdat", payload)
    }

    internal fun u32(v: Long): ByteArray {
        val b = ByteArray(4)
        Mp4.writeU32(b, 0, v)
        return b
    }

    internal fun u64(v: Long): ByteArray {
        val b = ByteArray(8)
        Mp4.writeU64(b, 0, v)
        return b
    }
}
