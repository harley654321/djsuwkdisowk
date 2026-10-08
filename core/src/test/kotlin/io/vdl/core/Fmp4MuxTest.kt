package io.vdl.core

import io.vdl.core.internal.mp4.Fmp4Fragments
import io.vdl.core.internal.mp4.Fmp4Muxer
import io.vdl.core.internal.mp4.Mp4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Muxer evidence: two single-track fMP4 streams in, ONE merged fMP4 out.
 * Structure verified with our own ISO 14496-12 reader; payloads verified
 * byte for byte; every surgical rewrite asserted by position.
 */
class Fmp4MuxTest {

    private val sink = PrintSink()
    private lateinit var tmp: File

    @org.junit.Before
    fun setUp() {
        tmp = Files.createTempDirectory("vdl-mux").toFile()
    }

    @org.junit.After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun videoStream(): ByteArray =
        TestFmp4Fixtures.initStream(1, 1000, "vide", audio = false) +
            TestFmp4Fixtures.segment(1, 0, 0L, randomBytes(1000, seed = 1)) +
            TestFmp4Fixtures.segment(1, 1, 1000L, randomBytes(1100, seed = 2))

    private fun audioStream(): ByteArray =
        TestFmp4Fixtures.initStream(1, 44_100, "soun", audio = true) +
            TestFmp4Fixtures.segment(1, 0, 0L, randomBytes(500, seed = 3)) +
            TestFmp4Fixtures.segment(1, 1, 960L, randomBytes(550, seed = 4))

    private fun write(name: String, data: ByteArray): File =
        File(tmp, name).apply { writeBytes(data) }

    private fun mux(video: ByteArray, audio: ByteArray?): ByteArray {
        val out = File(tmp, "muxed.mp4")
        val log = testLog(sink)
        val src = write("v.mp4", video)
        val a = audio?.let { write("a.mp4", it) }
        Fmp4Muxer.muxFiles(src, a, out, log)
        return out.readBytes()
    }

    /** Parsed moof info from the muxed output. */
    private data class MoofInfo(val seq: Long, val trackId: Long, val decodeTime: Long, val mdat: ByteArray)

    private fun parseTop(data: ByteArray): List<Mp4.Box> = Mp4.readBoxes(data)

    private fun moofInfos(data: ByteArray): List<MoofInfo> {
        val out = ArrayList<MoofInfo>()
        var i = 0
        val boxes = parseTop(data)
        while (i < boxes.size) {
            if (boxes[i].type == "moof") {
                val children = Mp4.readBoxes(boxes[i].body)
                val mfhd = children.first { it.type == "mfhd" }
                val traf = children.first { it.type == "traf" }
                val trafChildren = Mp4.readBoxes(traf.body)
                val tfhd = trafChildren.first { it.type == "tfhd" }
                val tfdt = trafChildren.first { it.type == "tfdt" }
                val seq = Mp4.readU32(mfhd.body, 4)
                val trackId = Mp4.readU32(tfhd.body, 4)
                val decodeTime = Mp4.readU32(tfdt.body, 4)
                val mdat = boxes[i + 1].body
                out.add(MoofInfo(seq, trackId, decodeTime, mdat))
            }
            i++
        }
        return out
    }

    @Test
    fun mergesTracksInterleavesByDecodeTimeAndKeepsPayloads() {
        val v0 = randomBytes(1000, seed = 1)
        val a0 = randomBytes(500, seed = 3)
        val a1 = randomBytes(550, seed = 4)
        val v1 = randomBytes(1100, seed = 2)
        val result = mux(videoStream(), audioStream())

        val top = parseTop(result).map { it.type }
        assertEquals(listOf("ftyp", "moov", "moof", "mdat", "moof", "mdat", "moof", "mdat", "moof", "mdat"), top)

        // moov: mvhd + 2 traks + mvex with both trex
        val moov = parseTop(result)[1]
        val moovChildren = Mp4.readBoxes(moov.body).map { it.type }
        assertEquals(listOf("mvhd", "trak", "trak", "mvex"), moovChildren)
        val trakV = Mp4.readBoxes(Mp4.readBoxes(moov.body)[1].body)
        val trakA = Mp4.readBoxes(Mp4.readBoxes(moov.body)[2].body)
        assertEquals(1L, Mp4.readU32(trakV.first { it.type == "tkhd" }.body, 12))
        // audio track_ID collision 1->2 (video kept 1)
        assertEquals(2L, Mp4.readU32(trakA.first { it.type == "tkhd" }.body, 12))
        val trexes = Mp4.readBoxes(Mp4.readBoxes(moov.body)[3].body)
        assertEquals(listOf("trex", "trex"), trexes.map { it.type })
        assertEquals(1L, Mp4.readU32(trexes[0].body, 4))
        assertEquals(2L, Mp4.readU32(trexes[1].body, 4))

        // interleave by tfdt: v(0), a(0), a(960), v(1000)
        val infos = moofInfos(result)
        assertEquals(4, infos.size)
        assertEquals(listOf(1L, 2L, 2L, 1L), infos.map { it.trackId })
        assertEquals(listOf(0L, 0L, 960L, 1000L), infos.map { it.decodeTime })
        assertEquals(listOf(0L, 0L, 1L, 1L), infos.map { it.seq })
        // payloads byte for byte, in interleaved order
        assertArrayEquals(v0, infos[0].mdat)
        assertArrayEquals(a0, infos[1].mdat)
        assertArrayEquals(a1, infos[2].mdat)
        assertArrayEquals(v1, infos[3].mdat)

        // evidence logs
        assertTrue(sink.lines.any { it.contains("track remap") && it.contains("old=1 new=2") })
        assertTrue(sink.lines.any { it.contains("interleave plan=v0,a0,a1,v1") })
        assertTrue(sink.lines.any { it.contains("mux done") })
    }

    @Test
    fun noCollisionKeepsOriginalTrackIds() {
        val video = TestFmp4Fixtures.initStream(1, 1000, "vide", audio = false) +
            TestFmp4Fixtures.segment(1, 0, 0L, randomBytes(100, seed = 5))
        val audio = TestFmp4Fixtures.initStream(5, 44_100, "soun", audio = true) +
            TestFmp4Fixtures.segment(5, 0, 0L, randomBytes(60, seed = 6))
        val result = mux(video, audio)
        val moovChildren = Mp4.readBoxes(parseTop(result)[1].body)
        assertEquals(1L, Mp4.readU32(Mp4.readBoxes(moovChildren[1].body).first { it.type == "tkhd" }.body, 12))
        assertEquals(5L, Mp4.readU32(Mp4.readBoxes(moovChildren[2].body).first { it.type == "tkhd" }.body, 12))
        val infos = moofInfos(result)
        assertEquals(listOf(1L, 5L), infos.map { it.trackId })
        assertFalse(sink.lines.any { it.contains("track remap") })
    }

    @Test
    fun baseDataOffsetRewrittenToNewMoofPosition() {
        // audio fragment carries tfhd.base_data_offset (flag 0x01)
        val video = TestFmp4Fixtures.initStream(1, 1000, "vide", audio = false) +
            TestFmp4Fixtures.segment(1, 0, 0L, randomBytes(100, seed = 7))
        val audio = TestFmp4Fixtures.initStream(3, 44_100, "soun", audio = true) +
            TestFmp4Fixtures.segment(3, 0, 0L, randomBytes(60, seed = 8), useBaseDataOffset = true)
        val result = mux(video, audio)

        // locate the audio moof (trackId 3) and verify its tfhd base_data_offset
        var off = 0
        var found = false
        val boxes = parseTop(result)
        for (i in boxes.indices) {
            if (boxes[i].type != "moof") continue
            val traf = Mp4.readBoxes(boxes[i].body).first { it.type == "traf" }
            val tfhd = Mp4.readBoxes(traf.body).first { it.type == "tfhd" }
            val trackId = Mp4.readU32(tfhd.body, 4)
            if (trackId == 3L) {
                assertEquals("base-data-offset flag", 0x01, Mp4.flags(tfhd.body) and 0x01)
                // absolute position of THIS moof:
                var moofAbs = 0
                for (j in 0 until i) moofAbs += boxes[j].size()
                assertEquals(moofAbs.toLong(), Mp4.readU64(tfhd.body, 8))
                found = true
            }
        }
        assertTrue("audio moof with trackId 3 not found", found)
        assertTrue(sink.lines.any { it.contains("rewrittenBases=1") })
    }

    @Test
    fun stypAndSidxAreDroppedWithLoggedDecision() {
        val video = TestFmp4Fixtures.initStream(1, 1000, "vide", audio = false) +
            TestFmp4Fixtures.segment(1, 0, 0L, randomBytes(100, seed = 9), withStyp = true, withSidx = true) +
            TestFmp4Fixtures.segment(1, 1, 500L, randomBytes(100, seed = 10), withStyp = true)
        val result = mux(video, null)
        val top = parseTop(result).map { it.type }
        assertFalse("styp must be dropped", top.contains("styp"))
        assertFalse("sidx must be dropped", top.contains("sidx"))
        assertEquals(2, moofInfos(result).size)
        assertTrue(sink.lines.any { it.contains("dropped box type=styp") })
        assertTrue(sink.lines.any { it.contains("dropped box type=sidx") })
    }

    @Test
    fun singleTrackPassthroughWithoutAudio() {
        val video = videoStream()
        val result = mux(video, null)
        val moovChildren = Mp4.readBoxes(parseTop(result)[1].body).map { it.type }
        assertEquals(listOf("mvhd", "trak", "mvex"), moovChildren)
        assertEquals(2, moofInfos(result).size)
        assertArrayEquals(randomBytes(1100, seed = 2), moofInfos(result)[1].mdat)
    }

    @Test
    fun missingFragmentsFailsFast() {
        val initOnly = TestFmp4Fixtures.initStream(1, 1000, "vide", audio = false)
        try {
            mux(initOnly, null)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("no moof"))
            assertTrue(sink.lines.any { it.contains("no fragments indexed") && it.contains("decision=fatal") })
        }
    }
}
