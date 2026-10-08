package io.vdl.core.internal.dash

/**
 * Parsed subset of an MPEG-DASH MPD (ISO/IEC 23009-1).
 *
 * Scope (clear content, static profiles - the professional minimum):
 * - SegmentTemplate with fixed duration (timescale units) OR
 *   SegmentTimeline (S entries: t/d/r), OR SegmentList (explicit URLs).
 * - $RepresentationID$/$Bandwidth$/$Number$/$Time$ template tokens.
 * - BaseURL resolution (MPD-level and Period-level).
 *
 * Explicit NON-goals, each surfaced as a typed failure with logs, never
 * a silent skip: DRM (ContentProtection), dynamic MPDs, multiple
 * Periods (v1 downloads the first), SegmentBase (single-file byte-range
 * indexing - the DIRECT engine already covers single files).
 */
internal data class DashMpd(
    val isDynamic: Boolean,
    val baseUrl: String?,
    val presentationDurationSec: Double?,
    val periods: List<DashPeriod>,
    /** Any ContentProtection anywhere -> DRM; downloads must refuse. */
    val protected: Boolean
)

internal data class DashPeriod(
    val id: String?,
    val baseUrl: String?,
    val adaptationSets: List<DashAdaptationSet>
)

internal data class DashAdaptationSet(
    val mimeType: String?,
    val lang: String?,
    val representations: List<DashRepresentation>
)

internal data class DashRepresentation(
    val id: String,
    val bandwidth: Long,
    val height: Int?,
    val codecs: String?,
    val timescale: Long,
    val initTemplate: String?,
    val mediaTemplate: String?,
    val startNumber: Long,
    /** SegmentTemplate fixed duration, timescale units (null = timeline/list). */
    val fixedDuration: Long?,
    /** SegmentTimeline entries: (start, duration) in timescale units. */
    val timeline: List<Pair<Long, Long>>,
    /** SegmentList: explicit media URLs (init comes from [listInitUrl]). */
    val listUrls: List<String>,
    val listInitUrl: String?
) {
    val usesTimeline: Boolean get() = timeline.isNotEmpty()
    val usesSegmentList: Boolean get() = listUrls.isNotEmpty()
}

/** Selected audio+video pair for download. */
internal data class DashSelection(
    val video: DashRepresentation,
    val videoLang: String?,
    val videoMimeType: String?,
    val audio: DashRepresentation?,   // null: muxed inside video stream
    val audioLang: String?
)
