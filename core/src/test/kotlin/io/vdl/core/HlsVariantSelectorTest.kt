package io.vdl.core

import io.vdl.core.internal.hls.HlsParser
import io.vdl.core.internal.hls.HlsVariantSelector
import io.vdl.core.internal.hls.MasterPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HlsVariantSelectorTest {

    private val parser = HlsParser()
    private val masterUrl = "https://cdn.example.com/v/master.m3u8"

    private fun master(vararg lines: String): MasterPlaylist {
        val text = buildString {
            append("#EXTM3U\n")
            lines.forEach { append(it).append('\n') }
        }
        return parser.parse(text, masterUrl) as MasterPlaylist
    }

    private val audioGroup = listOf(
        """#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",DEFAULT=YES,URI="audio/en.m3u8"""",
        """#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Spanish",LANGUAGE="es",DEFAULT=NO,URI="audio/es.m3u8""""
    )

    @Test
    fun picksHighestResolutionWithinCap() {
        val p = master(
            "#EXT-X-STREAM-INF:BANDWIDTH=600000,RESOLUTION=854x480",
            "v480.m3u8",
            "#EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=1280x720",
            "v720.m3u8",
            "#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080",
            "v1080.m3u8"
        )
        val sel = HlsVariantSelector.select(p, maxHeight = 720)
        assertEquals("https://cdn.example.com/v/v720.m3u8", sel.video.uri)
        assertNull(sel.audio)
    }

    @Test
    fun capTooLowFallsBackToSmallest() {
        val p = master(
            "#EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=1280x720",
            "v720.m3u8",
            "#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080",
            "v1080.m3u8"
        )
        val sel = HlsVariantSelector.select(p, maxHeight = 360)
        assertEquals("https://cdn.example.com/v/v720.m3u8", sel.video.uri)
    }

    @Test
    fun averageBandwidthBreaksResolutionTie() {
        val p = master(
            "#EXT-X-STREAM-INF:BANDWIDTH=3000000,AVERAGE-BANDWIDTH=2900000,RESOLUTION=1920x1080",
            "a.m3u8",
            "#EXT-X-STREAM-INF:BANDWIDTH=2900000,AVERAGE-BANDWIDTH=2800000,RESOLUTION=1920x1080",
            "b.m3u8"
        )
        val sel = HlsVariantSelector.select(p)
        assertEquals("https://cdn.example.com/v/a.m3u8", sel.video.uri)
    }

    @Test
    fun defaultAudioRenditionChosen() {
        val p = master(*(audioGroup + listOf(
            "#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080,AUDIO=\"aud\"",
            "v1080.m3u8"
        )).toTypedArray())
        val sel = HlsVariantSelector.select(p)
        assertNotNull(sel.audio)
        assertEquals("English", sel.audio!!.name)
        assertEquals("https://cdn.example.com/v/audio/en.m3u8", sel.audio!!.uri)
    }

    @Test
    fun muxedAudioYieldsNullWhenGroupHasNoUris() {
        val p = master(
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"embedded\"",
            "#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080,AUDIO=\"aud\"",
            "v1080.m3u8"
        )
        val sel = HlsVariantSelector.select(p)
        assertNull(sel.audio)
    }
}
