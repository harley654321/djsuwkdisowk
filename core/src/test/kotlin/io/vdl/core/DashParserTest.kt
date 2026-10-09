package io.vdl.core

import io.vdl.core.internal.dash.DashParser
import io.vdl.core.internal.dash.DashPlan
import io.vdl.core.internal.dash.DashSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DASH parser/selector/plan unit tests: real MPD XML strings (the shapes
 * actual manifests use), typed failures for out-of-scope features.
 */
class DashParserTest {

    private val parser = DashParser(testLog())

    private val staticMpd = """
        <?xml version="1.0" encoding="UTF-8"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static"
             mediaPresentationDuration="PT7.5S" profiles="urn:mpeg:dash:profile:isoff-live:2011">
          <Period id="p0">
            <AdaptationSet mimeType="video/mp4" lang="und">
              <SegmentTemplate timescale="90000" duration="180000" startNumber="1"
                               initialization="${'$'}RepresentationID${'$'}/init.mp4"
                               media="${'$'}RepresentationID${'$'}/seg_${'$'}Number${'$'}.m4s"/>
              <Representation id="video/1080" bandwidth="3000000" height="1080" codecs="avc1.640028"/>
              <Representation id="video/720" bandwidth="1400000" height="720" codecs="avc1.64001f"/>
              <Representation id="video/360" bandwidth="500000" height="360" codecs="avc1.64001e"/>
            </AdaptationSet>
            <AdaptationSet mimeType="audio/mp4" lang="en">
              <SegmentTemplate timescale="44100" duration="176400" startNumber="1"
                               initialization="audio-${'$'}RepresentationID${'$'}/init.mp4"
                               media="audio-${'$'}RepresentationID${'$'}/seg_${'$'}Number${'$'}.m4s"/>
              <Representation id="en" bandwidth="128000" codecs="mp4a.40.2"/>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    @Test
    fun parsesStaticMpdWithFixedDurationAndTwoSets() {
        val mpd = parser.parse(staticMpd)
        assertFalse(mpd.isDynamic)
        assertFalse(mpd.protected)
        assertEquals(1, mpd.periods.size)
        assertEquals(2, mpd.periods[0].adaptationSets.size)

        val video = mpd.periods[0].adaptationSets[0]
        assertEquals(3, video.representations.size)
        val r720 = video.representations.first { it.id == "video/720" }
        assertEquals(90000L, r720.timescale)
        assertEquals(180000L, r720.fixedDuration)
        assertEquals(1L, r720.startNumber)
        assertTrue(r720.timeline.isEmpty())

        val audio = mpd.periods[0].adaptationSets[1]
        assertEquals(1, audio.representations.size)
        assertEquals("en", audio.lang)
    }

    @Test
    fun parsesIsoDurations() {
        assertEquals(7.5, parser.parseIsoDuration("PT7.5S")!!, 1e-9)
        assertEquals(90.0, parser.parseIsoDuration("PT1M30S")!!, 1e-9)
        assertEquals(3661.5, parser.parseIsoDuration("PT1H1M1.5S")!!, 1e-9)
        assertEquals(90061.0, parser.parseIsoDuration("P1DT1H1M1S")!!, 1e-9)
        assertNull(parser.parseIsoDuration("P"))
        assertNull(parser.parseIsoDuration("garbage"))
    }

    @Test
    fun detectsContentProtectionAsRefusalFlag() {
        val drm = staticMpd.replace(
            "<AdaptationSet mimeType=\"video/mp4\" lang=\"und\">",
            "<AdaptationSet mimeType=\"video/mp4\" lang=\"und\"><ContentProtection schemeIdUri=\"urn:mpeg:dash:mp4protection:2011\" value=\"cenc\"/>"
        )
        val mpd = parser.parse(drm)
        assertTrue(mpd.protected)
    }

    @Test
    fun parsesSegmentTimelineWithRepeats() {
        // regex-based swap (immune to trimIndent-relative spacing); the
        // video SegmentTemplate is the first self-closing one
        val timelineTpl = """<SegmentTemplate timescale="90000" startNumber="5" """ +
            """initialization="${'$'}RepresentationID${'$'}/init.mp4" """ +
            """media="${'$'}RepresentationID${'$'}/seg_${'$'}Time${'$'}.m4s">""" +
            """<SegmentTimeline><S t="0" d="180000" r="1"/><S t="360000" d="180000"/></SegmentTimeline>""" +
            """</SegmentTemplate>"""
        val timelineMpd = staticMpd.replaceFirst(
            Regex("""<SegmentTemplate[^>]*?/>"""),
            java.util.regex.Matcher.quoteReplacement(timelineTpl)
        )
        val mpd = parser.parse(timelineMpd)
        val rep = mpd.periods[0].adaptationSets[0].representations.first()
        assertTrue(rep.usesTimeline)
        assertEquals(3, rep.timeline.size)
        assertEquals(0L to 180000L, rep.timeline[0])
        assertEquals(180000L to 180000L, rep.timeline[1])
        assertEquals(360000L to 180000L, rep.timeline[2])
        assertEquals(5L, rep.startNumber)
    }

    @Test
    fun openRepeatMinusOneExpandsToPeriodDuration() {
        // real-world Akamai shape: a single S with r="-1" (repeat until the
        // end) — before the fix this produced an EMPTY timeline and the
        // downloader failed with "neither timeline, list, nor fixed duration".
        val tpl = """<SegmentTemplate timescale="12288" startNumber="1" """ +
            """initialization="video/init.mp4" media="video/seg_${'$'}Time${'$'}.m4s">""" +
            """<SegmentTimeline><S t="0" d="61440" r="-1"/></SegmentTimeline>""" +
            """</SegmentTemplate>"""
        val mpd = parser.parse(staticMpd.replaceFirst(
            Regex("""<SegmentTemplate[^>]*?/>"""), java.util.regex.Matcher.quoteReplacement(tpl)))
        val rep = mpd.periods[0].adaptationSets[0].representations.first()
        assertTrue(rep.usesTimeline)
        // period = 7.5s * 12288 = 92160 units; ceil(92160 / 61440) = 2
        assertEquals(2, rep.timeline.size)
        assertEquals(0L to 61440L, rep.timeline[0])
        assertEquals(61440L to 61440L, rep.timeline[1])
        // $Time$ URLs use each segment's timeline start (unit 0 = init)
        val units = DashPlan.build(
            mpd, mpd.periods[0], rep, "https://cdn.example/v.mpd"
        )
        assertEquals(3, units.size)
        assertTrue(units[1].url.endsWith("seg_0.m4s"))
        assertTrue(units[2].url.endsWith("seg_61440.m4s"))
    }

    @Test
    fun openRepeatWithoutDurationFallsBackToSingleSegment() {
        val tpl = """<SegmentTemplate timescale="1000" media="v_${'$'}Time${'$'}.m4s">""" +
            """<SegmentTimeline><S t="0" d="4000" r="-1"/></SegmentTimeline>""" +
            """</SegmentTemplate>"""
        val mpd = parser.parse(staticMpd.replaceFirst(
            Regex("""<SegmentTemplate[^>]*?/>"""), java.util.regex.Matcher.quoteReplacement(tpl))
            .replace(Regex("mediaPresentationDuration=\"[^\"]*\""), ""))
        val rep = mpd.periods[0].adaptationSets[0].representations.first()
        assertEquals(1, rep.timeline.size)
    }

    @Test
    fun audioSetWithoutMimeIsDetectedViaRepCodecs() {
        // real 1c-style MPD: AdaptationSet carries no mimeType; the audio
        // identity lives in each Representation codecs="mp4a...".
        val stripped = staticMpd.replace(Regex("""mimeType="audio/mp4" """), "")
        val sel = DashSelector.select(parser.parse(stripped), null, null)
        assertNotNull(sel.audio)
        assertEquals("en", sel.audio!!.id)
    }

    @Test
    fun parsesSegmentList() {
        // swap the first (video) SegmentTemplate for a SegmentList
        val listXml = """<SegmentList><Initialization sourceURL="v-init.mp4"/>""" +
            """<SegmentURL media="v1.m4s"/><SegmentURL media="v2.m4s"/></SegmentList>"""
        val listMpd = staticMpd.replaceFirst(
            Regex("""<SegmentTemplate[^>]*?/>"""),
            java.util.regex.Matcher.quoteReplacement(listXml)
        )
        val mpd = parser.parse(listMpd)
        val rep = mpd.periods[0].adaptationSets[0].representations.first()
        assertTrue(rep.usesSegmentList)
        assertEquals(listOf("v1.m4s", "v2.m4s"), rep.listUrls)
        assertEquals("v-init.mp4", rep.listInitUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun selectorThrowsWhenVideoSetWasSkippedForMissingAddressing() {
        // without SegmentTemplate the whole video AdaptationSet is skipped
        // (evidence-logged in the parser); selection then fails typed.
        val bad = staticMpd.replace(Regex("""<SegmentTemplate[^>]*?/>"""), "")
        val mpd = parser.parse(bad)
        assertEquals(0, mpd.periods[0].adaptationSets.size) // both sets skipped
        DashSelector.select(mpd) // throws: no video set
    }

    @Test
    fun planResolvesBaseUrlChains() {
        val withBase = staticMpd.replace(
            "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\"",
            "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" BaseURL=\"https://cdn.example/streams/\""
        )
        val mpd = parser.parse(withBase)
        val sel = DashSelector.select(mpd, maxHeight = 720)
        val units = DashPlan.build(mpd, mpd.periods.first(), sel.video, "https://mirror.example/manifest.mpd")
        assertEquals("https://cdn.example/streams/video/720/init.mp4", units[0].url)
    }

    @Test
    fun planRejectsUnknownTemplateToken() {
        val weird = staticMpd.replace(
            """media="${'$'}RepresentationID${'$'}/seg_${'$'}Number${'$'}.m4s"/>""",
            """media="${'$'}RepresentationID${'$'}/seg_${'$'}SubNumber${'$'}.m4s"/>"""
        )
        val mpd = parser.parse(weird)
        val sel = DashSelector.select(mpd, maxHeight = 720)
        try {
            DashPlan.build(mpd, mpd.periods.first(), sel.video, "https://x/master.mpd")
            throw IllegalStateException("expected failure")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("${'$'}SubNumber${'$'}"))
        }
    }
}
