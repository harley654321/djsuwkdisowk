package io.vdl.core.internal.dash

import io.vdl.core.internal.logging.VdlLog
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * MPD parser: DOM via javax.xml (API 1, JVM-testable, no android.* deps).
 * Namespace-tolerant: matches unprefixed AND prefixed elements
 * (dash:AdaptationSet) by comparing the local name after ':'.
 *
 * Every scope decision (unsupported feature, missing attribute) is
 * returned typed or logged - the parser never invents URLs.
 */
internal class DashParser internal constructor(private val log: VdlLog? = null) {

    internal fun parse(xml: String): DashMpd {
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(xml.byteInputStream())

        val root = doc.documentElement ?: throw IllegalArgumentException("empty MPD")
        val type = attr(root, "type") ?: "static"
        val protected = doc.getElementsByTagName("*").let { all ->
            (0 until all.length).any { i -> localName(all.item(i)) == "ContentProtection" }
        }
        val mpdBase = attr(root, "BaseURL")?.takeIf { it.isNotBlank() }
        val duration = attr(root, "mediaPresentationDuration")?.let { parseIsoDuration(it) }

        val periods = children(root, "Period").map { p ->
            val pBase = attr(p, "BaseURL")?.takeIf { it.isNotBlank() }
            // period duration (ISO 8601) or MPD-level duration: needed to
            // expand open-ended SegmentTimeline repeats (r="-1").
            val pEndSec = attr(p, "duration")?.let { parseIsoDuration(it) } ?: duration
            DashPeriod(
                id = attr(p, "id"),
                baseUrl = pBase,
                adaptationSets = children(p, "AdaptationSet").mapNotNull { asEl ->
                    val reps = children(asEl, "Representation").mapNotNull { repEl -> representation(repEl, asEl, pEndSec) }
                    if (reps.isEmpty()) {
                        log?.w(TAG) { "adaptationSet without parseable representations id=${attr(asEl, "id") ?: "-"} decision=skip" }
                        null
                    } else {
                        DashAdaptationSet(
                            mimeType = attr(asEl, "mimeType"),
                            lang = attr(asEl, "lang") ?: attr(asEl, "language"),
                            representations = reps
                        )
                    }
                }
            )
        }
        if (periods.isEmpty()) throw IllegalArgumentException("MPD has no Period")

        log?.i(TAG) {
            "mpd parsed type=$type periods=${periods.size} sets=${periods.sumOf { it.adaptationSets.size }} " +
                "protected=$protected durationSec=${duration ?: "unknown"}"
        }
        if (protected) log?.w(TAG) { "ContentProtection present decision=fatal-downstream" }
        return DashMpd(
            isDynamic = type.equals("dynamic", ignoreCase = true),
            baseUrl = mpdBase,
            presentationDurationSec = duration,
            periods = periods,
            protected = protected
        )
    }

    // -------------------------------------------------- representation

    private fun representation(repEl: Element, asEl: Element, endSec: Double?): DashRepresentation? {
        val id = attr(repEl, "id") ?: run {
            log?.w(TAG) { "representation without id decision=skip" }
            return null
        }
        // SegmentTemplate may sit on AdaptationSet or Representation
        val tpl = child(repEl, "SegmentTemplate") ?: child(asEl, "SegmentTemplate")
        val list = child(repEl, "SegmentList") ?: child(asEl, "SegmentList")

        return when {
            tpl != null -> fromTemplate(repEl, asEl, id, tpl, endSec)
            list != null -> fromSegmentList(repEl, id, list)
            else -> {
                log?.e(TAG) { "representation id=$id has no SegmentTemplate/SegmentList decision=skip" }
                null
            }
        }
    }

    private fun fromTemplate(
        repEl: Element, asEl: Element, id: String, tpl: Element, endSec: Double?
    ): DashRepresentation {
        // timescale is a SegmentTemplate attribute; 1 is the spec default
        val timescale = attr(tpl, "timescale")?.toLongOrNull() ?: 1L
        val media = attr(tpl, "media")
        val init = attr(tpl, "initialization")
        if (media == null) throw IllegalArgumentException("SegmentTemplate without media for $id")
        if (init == null) {
            // legal for TS-less profiles but unsupported for us: no init
            // means non-fMP4; the queue engine refuses TS in DASH anyway.
            log?.w(TAG) { "SegmentTemplate without initialization id=$id" }
        }
        val startNumber = attr(tpl, "startNumber")?.toLongOrNull() ?: 1L
        val fixed = attr(tpl, "duration")?.toLongOrNull()
        val tl = child(tpl, "SegmentTimeline")
        val timeline = if (tl != null) parseTimeline(tl, timescale, endSec) else emptyList()
        return DashRepresentation(
            id = id,
            bandwidth = attr(repEl, "bandwidth")?.toLongOrNull()
                ?: attr(asEl, "bandwidth")?.toLongOrNull() ?: 0L,
            height = attr(repEl, "height")?.toIntOrNull(),
            codecs = attr(repEl, "codecs") ?: attr(asEl, "codecs"),
            timescale = timescale,
            initTemplate = init,
            mediaTemplate = media,
            startNumber = startNumber,
            fixedDuration = fixed,
            timeline = timeline,
            listUrls = emptyList(),
            listInitUrl = null
        )
    }

    private fun fromSegmentList(repEl: Element, id: String, list: Element): DashRepresentation {
        val urls = children(list, "SegmentURL").mapNotNull { attr(it, "media") }
        val init = child(list, "Initialization")?.let { attr(it, "sourceURL") }
        return DashRepresentation(
            id = id,
            bandwidth = attr(repEl, "bandwidth")?.toLongOrNull() ?: 0L,
            height = attr(repEl, "height")?.toIntOrNull(),
            codecs = attr(repEl, "codecs"),
            timescale = 1L,
            initTemplate = init,
            mediaTemplate = null,
            startNumber = 1L,
            fixedDuration = null,
            timeline = emptyList(),
            listUrls = urls,
            listInitUrl = init
        )
    }

    /** S entries: t (first only), d (required), r (repeat; r=-1 = until period end). */
    private fun parseTimeline(tl: Element, timescale: Long, endSec: Double?): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        var cursor = 0L
        for (s in children(tl, "S")) {
            val d = attr(s, "d")?.toLongOrNull() ?: run {
                log?.w(TAG) { "timeline S without d decision=skip-entry" }
                continue
            }
            val start = attr(s, "t")?.toLongOrNull() ?: cursor
            val repeat = attr(s, "r")?.toLongOrNull() ?: 0L
            val count = when {
                repeat >= 0 -> (repeat + 1).toInt()
                // ISO/IEC 23009-1: r="-1" repeats until the period/MPD end.
                repeat == -1L -> {
                    val endUnits = endSec?.let { (it * timescale).toLong() }
                    val n = endUnits?.let { (((it - start) + d - 1) / d).toInt() }?.coerceAtLeast(1)
                        ?: run {
                            log?.w(TAG) { "timeline r=-1 without period duration decision=single-segment" }
                            1
                        }
                    if (n > MAX_TIMELINE_UNITS) {
                        log?.w(TAG) { "timeline r=-1 entries=$n capped decision=first-$MAX_TIMELINE_UNITS" }
                        MAX_TIMELINE_UNITS
                    } else {
                        log?.i(TAG) { "timeline r=-1 open-repeat expanded endUnits=${endUnits ?: "-"} entries=$n" }
                        n
                    }
                }
                else -> {
                    log?.w(TAG) { "timeline S r=$repeat invalid decision=single-segment" }
                    1
                }
            }
            for (i in 0 until count) {
                out.add((start + i * d) to d)
            }
            cursor = start + count * d
        }
        return out
    }



    // ------------------------------------------------------ xml helpers

    private fun children(el: Element, local: String): List<Element> =
        (0 until el.childNodes.length).mapNotNull { i ->
            val n = el.childNodes.item(i)
            if (n is Element && localName(n) == local) n else null
        }

    private fun child(el: Element, local: String): Element? = children(el, local).firstOrNull()

    private fun localName(n: org.w3c.dom.Node): String = n.nodeName.substringAfterLast(':')

    private fun attr(el: Element, name: String): String? =
        el.getAttribute(name)?.takeIf { it.isNotEmpty() }

    /** ISO 8601 duration, MPD form: P#Y#M#DT#H#M#S (any part optional). */
    internal fun parseIsoDuration(raw: String): Double? {
        val m = Regex(
            "^P(?!$)(?:(\\d+(?:\\.\\d+)?)Y)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)D)?" +
                "(?:T(?!$)(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?$"
        ).find(raw.trim()) ?: return null
        val (y, mo, d, h, mi, s) = m.destructured
        fun g(v: String?): Double = v?.toDoubleOrNull() ?: 0.0
        return g(y) * 31536000 + g(mo) * 2592000 + g(d) * 86400 +
            g(h) * 3600 + g(mi) * 60 + g(s)
    }

    private companion object {
        internal const val TAG = "[VDL][DASH][parser]"

        /** Hostile-MPD guard: an open-ended repeat can never allocate unbounded units. */
        private const val MAX_TIMELINE_UNITS = 50_000
    }
}
