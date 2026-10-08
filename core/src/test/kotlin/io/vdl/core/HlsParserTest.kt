package io.vdl.core

import io.vdl.core.internal.hls.HlsParseException
import io.vdl.core.internal.hls.HlsParser
import io.vdl.core.internal.hls.HlsPlaylist
import io.vdl.core.internal.hls.HlsUrl
import io.vdl.core.internal.hls.MasterPlaylist
import io.vdl.core.internal.hls.MediaPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HlsParserTest {

    private val parser = HlsParser()
    private val masterUrl = "https://cdn.example.com/v/master.m3u8"

    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,CHANNELS="2",URI="audio/en.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Español, latino",LANGUAGE="es",DEFAULT=NO,AUTOSELECT=YES,CHANNELS="2",URI="audio/es.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=2500000,AVERAGE-BANDWIDTH=2400000,CODECS="avc1.64001f,mp4a.40.2",RESOLUTION=1920x1080,FRAME-RATE=30.000,AUDIO="aud"
        v1080.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=1280x720,AUDIO="aud"
        v720.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=600000,RESOLUTION=854x480
        v480.m3u8
    """.trimIndent()

    @Test
    fun parsesMasterVariantsAndRenditions() {
        val p = parser.parse(master, masterUrl) as MasterPlaylist
        assertEquals(3, p.variants.size)
        assertEquals(2, p.media.size)

        val top = p.variants.first()
        assertEquals("https://cdn.example.com/v/v1080.m3u8", top.uri)
        assertEquals(2500000L, top.bandwidth)
        assertEquals(2400000L, top.averageBandwidth)
        assertEquals("avc1.64001f,mp4a.40.2", top.codecs)
        assertEquals(1920, top.resolutionWidth)
        assertEquals(1080, top.resolutionHeight)
        assertEquals(30.0, top.frameRate!!, 0.001)
        assertEquals("aud", top.audioGroup)

        val es = p.media[1]
        assertEquals("Español, latino", es.name) // comma inside quoted value
        assertEquals("https://cdn.example.com/v/audio/es.m3u8", es.uri)
        assertFalse(es.isDefault)
        assertTrue(p.media[0].isDefault)
        assertEquals("2", p.media[0].channels)
    }

    @Test
    fun variantWithoutBandwidthFails() {
        val bad = """
            #EXTM3U
            #EXT-X-STREAM-INF:RESOLUTION=1280x720
            v720.m3u8
        """.trimIndent()
        try {
            parser.parse(bad, masterUrl)
            fail("expected HlsParseException")
        } catch (e: HlsParseException) {
            assertEquals("EXT-X-STREAM-INF without BANDWIDTH", e.message)
        }
    }

    @Test
    fun masterWithNoVariantsFails() {
        try {
            parser.parse("#EXTM3U\n#EXT-X-INDEPENDENT-SEGMENTS\n", masterUrl)
            fail("expected HlsParseException")
        } catch (e: HlsParseException) {
            // no variants and no segments: invalid either way, with a reason
            assertTrue(e.message!!, e.message!!.contains("without"))
        }
    }

    @Test
    fun missingExtm3uFails() {
        try {
            parser.parse("#EXT-X-STREAM-INF:BANDWIDTH=1\nx.m3u8\n", masterUrl)
            fail("expected HlsParseException")
        } catch (e: HlsParseException) {
            assertTrue(e.message!!.contains("EXTM3U"))
        }
    }

    @Test
    fun parsesTsMediaPlaylistWithAes128() {
        val mediaUrl = "https://cdn.example.com/v/720/media.m3u8"
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:100
            #EXT-X-KEY:METHOD=AES-128,URI="../keys/key.bin",IV=0x9c7db8772967aa1c94e8cfad19d2ee2c
            #EXTINF:5.76,
            seg100.ts
            #EXTINF:6.00,
            seg101.ts
            #EXT-X-DISCONTINUITY
            #EXT-X-KEY:METHOD=AES-128,URI="../keys/key2.bin"
            #EXTINF:5.50,
            seg102.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val p = parser.parse(text, mediaUrl) as MediaPlaylist
        assertFalse(p.isLive)
        assertEquals(6.0, p.targetDurationSec, 0.001)
        assertEquals(100L, p.mediaSequence)
        assertEquals(3, p.segments.size)
        assertEquals(17.26, p.totalDurationSec, 0.001)

        val first = p.segments[0]
        assertEquals("https://cdn.example.com/v/720/seg100.ts", first.uri)
        assertEquals(5.76, first.durationSec, 0.001)
        assertNotNull(first.key)
        assertEquals("AES-128", first.key!!.method)
        assertEquals("https://cdn.example.com/v/keys/key.bin", first.key!!.uri)
        assertEquals("9c7db8772967aa1c94e8cfad19d2ee2c", first.key!!.ivHex)

        // second key (no IV) applies to the segment after it
        val third = p.segments[2]
        assertNull(third.key!!.ivHex)
        assertEquals("https://cdn.example.com/v/keys/key2.bin", third.key!!.uri)
        // KEY NONE resets
    }

    @Test
    fun keyNoneClearsEncryption() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-KEY:METHOD=AES-128,URI="k.bin"
            #EXTINF:4.0,
            a.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4.0,
            b.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val p = parser.parse(text, "https://h.example.com/m.m3u8") as MediaPlaylist
        assertNotNull(p.segments[0].key)
        assertNull(p.segments[1].key)
    }

    @Test
    fun parsesFmp4WithMapAndByterange() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-MAP:URI="init.mp4",BYTERANGE="712@0"
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x1234567890abcdef1234567890abcdef
            #EXTINF:3.96,
            #EXT-X-BYTERANGE:400000@712
            media.1.m4s
            #EXTINF:4.0,
            #EXT-X-BYTERANGE:450000
            media.2.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        val url = "https://cdn.example.com/live/idx.mfm8"
        val p = parser.parse(text, url) as MediaPlaylist
        assertEquals("https://cdn.example.com/live/init.mp4", p.initSegment!!.uri)
        assertEquals(0L, p.initSegment!!.byterangeOffset)
        assertEquals(712L, p.initSegment!!.byterangeLength)

        val s1 = p.segments[0]
        assertEquals(400000L, s1.byterangeLength)
        assertEquals(712L, s1.byterangeOffset)
        val s2 = p.segments[1]
        assertEquals(450000L, s2.byterangeLength)
        assertNull(s2.byterangeOffset) // contiguous: offset derived from previous end
    }

    @Test
    fun livePlaylistHasNoEndlist() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:9.0,
            live0.ts
        """.trimIndent()
        val p = parser.parse(text, "https://h/x.m3u8") as MediaPlaylist
        assertTrue(p.isLive)
    }

    @Test
    fun extinfWithoutUriFails() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXTINF:4.0,
        """.trimIndent()
        try {
            parser.parse(text, "https://h/x.m3u8")
            fail("expected HlsParseException for truncated playlist")
        } catch (e: HlsParseException) {
            assertTrue(e.message!!.contains("EXTINF without following URI"))
        }
    }

    @Test
    fun unknownTagsIgnoredTolerantly() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-SOMETHING-NEW:whatever,"quoted"
            #EXT-X-TARGETDURATION:2
            #EXTINF:2.0,
            s.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val p = parser.parse(text, "https://h/x.m3u8") as MediaPlaylist
        assertEquals(1, p.segments.size)
    }

    @Test
    fun absoluteSegmentUrlsPassThrough() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:2
            #EXTINF:2.0,
            https://other.cdn.example.com/abs.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val p = parser.parse(text, "https://h/x.m3u8") as MediaPlaylist
        assertEquals("https://other.cdn.example.com/abs.ts", p.segments[0].uri)
    }

    @Test
    fun mediaPlaylistMissingTargetDurationFails() {
        val text = "#EXTM3U\n#EXTINF:2.0,\ns.ts\n"
        try {
            parser.parse(text, "https://h/x.m3u8")
            fail("expected HlsParseException")
        } catch (e: HlsParseException) {
            assertTrue(e.message!!.contains("TARGETDURATION"))
        }
    }

    @Test
    fun urlResolverCases() {
        // deep relative walk
        assertEquals(
            "https://cdn.example.com/media/s1.ts",
            HlsUrl.resolve("https://cdn.example.com/a/b/idx.m3u8", "../../media/s1.ts")
        )
        // same directory, playlist is a "directory-like" url
        assertEquals(
            "https://cdn.example.com/a/s1.ts",
            HlsUrl.resolve("https://cdn.example.com/a/", "s1.ts")
        )
        // server-absolute path
        assertEquals(
            "https://cdn.example.com/root/s1.ts",
            HlsUrl.resolve("https://cdn.example.com/a/idx.m3u8", "/root/s1.ts")
        )
        // protocol-relative
        assertEquals(
            "https://mirror.example.com/s1.ts",
            HlsUrl.resolve("https://cdn.example.com/a/idx.m3u8", "//mirror.example.com/s1.ts")
        )
        // dot segment
        assertEquals(
            "https://cdn.example.com/a/s1.ts",
            HlsUrl.resolve("https://cdn.example.com/a/idx.m3u8", "./s1.ts")
        )
    }

    @Test
    fun attrParserHandlesQuotedCommasAndHex() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-KEY:METHOD=AES-128,URI="https://k.example.com/a,b.bin",IV=0xABCDEF00000000000000000000000001
            #EXTINF:4.0,
            s.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val p = parser.parse(text, "https://h/x.m3u8") as MediaPlaylist
        assertEquals("https://k.example.com/a,b.bin", p.segments[0].key!!.uri)
        assertEquals("ABCDEF00000000000000000000000001", p.segments[0].key!!.ivHex)
    }

    @Test
    fun parseIsTypedPlaylistInterface() {
        val p: HlsPlaylist = parser.parse(master, masterUrl)
        assertTrue(p is MasterPlaylist)
        assertTrue(p.uri == masterUrl)
    }
}
