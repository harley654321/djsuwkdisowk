package io.vdl.core.internal.dash

import io.vdl.core.internal.hls.HlsUrl
import io.vdl.core.internal.logging.VdlLog

/**
 * Flattens one representation into ordered download units
 * (unit 0 = init when the template has an initialization URL).
 *
 * Template tokens per ISO/IEC 23009-1: $RepresentationID$,
 * $Bandwidth$, $Number$ (startNumber + i), $Time$ (timeline start).
 * $$ escapes; any other token is a typed failure - never a silent
 * broken URL.
 */
internal object DashPlan {

    internal data class Unit(
        val index: Int,
        val url: String
    ) {
        override fun equals(other: Any?): Boolean = other is Unit && other.index == index
        override fun hashCode(): Int = index
    }

    internal fun build(
        mpd: DashMpd,
        period: DashPeriod,
        rep: DashRepresentation,
        mpdUrl: String,
        log: VdlLog? = null
    ): List<Unit> {
        val base = resolveBase(mpdUrl, mpd.baseUrl, period.baseUrl)
        val out = ArrayList<Unit>()
        var idx = 0

        rep.initTemplate?.let { tpl ->
            val url = HlsUrl.resolve(base, substitute(tpl, rep, number = null, time = null))
            out.add(Unit(idx++, url))
        }

        when {
            rep.usesTimeline -> {
                for ((start, _) in rep.timeline) {
                    val url = HlsUrl.resolve(base, substitute(rep.mediaTemplate!!, rep, number = rep.startNumber + out.size - 1, time = start))
                    out.add(Unit(idx++, url))
                }
            }
            rep.usesSegmentList -> {
                for (u in rep.listUrls) out.add(Unit(idx++, HlsUrl.resolve(base, u)))
            }
            rep.fixedDuration != null -> {
                val count = fixedSegmentCount(mpd, rep) ?: run {
                    throw IllegalArgumentException(
                        "cannot derive segment count for ${rep.id}: fixed duration without mediaPresentationDuration"
                    )
                }
                for (i in 0 until count) {
                    val url = HlsUrl.resolve(base, substitute(rep.mediaTemplate!!, rep, number = rep.startNumber + i, time = i * rep.fixedDuration))
                    out.add(Unit(idx++, url))
                }
            }
            else -> throw IllegalArgumentException(
                "representation ${rep.id} has neither timeline, list, nor fixed duration"
            )
        }
        log?.i("[VDL][DASH][plan]") { "plan built rep=${rep.id} units=${out.size} (init=${if (rep.initTemplate != null) 1 else 0})" }
        return out
    }

    /** ceil(presentationDuration * timescale / fixedDuration). */
    private fun fixedSegmentCount(mpd: DashMpd, rep: DashRepresentation): Int? {
        val dur = mpd.presentationDurationSec ?: return null
        val segs = (dur * rep.timescale) / rep.fixedDuration!!
        val rem = (dur * rep.timescale) % rep.fixedDuration
        return (segs.toLong() + if (rem > 0) 1L else 0L).toInt().coerceAtLeast(1)
    }

    private fun substitute(template: String, rep: DashRepresentation, number: Long?, time: Long?): String {
        var out = template
            .replace("$$", "\u0000") // protect escaped $
            .replace("\$RepresentationID$", rep.id)
            .replace("\$Bandwidth$", rep.bandwidth.toString())
        if (number != null) out = out.replace("\$Number\$", number.toString())
        if (time != null) out = out.replace("\$Time\$", time.toString())
        val unknown = Regex("\\$[^$]*\\$").find(out)
        require(unknown == null) { "unsupported template token ${unknown?.value} in $template" }
        return out.replace("\u0000", "$")
    }

    /** MPD BaseURL (relative) resolved against the manifest URL, then Period's. */
    private fun resolveBase(mpdUrl: String, mpdBase: String?, periodBase: String?): String {
        var base = mpdUrl
        mpdBase?.let { base = HlsUrl.resolve(base, it) }
        periodBase?.let { base = HlsUrl.resolve(base, it) }
        return base
    }
}
