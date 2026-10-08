package io.vdl.core.internal.dash

import io.vdl.core.internal.logging.VdlLog

/**
 * Audio+video representation selection for a parsed MPD.
 *
 * Policy (mirrors the HLS selector, evidence-logged):
 * 1. Video = first AdaptationSet with a video mime (or height/bandwidth
 *    and codecs that are not audio); Audio = first audio set.
 * 2. Among video representations drop those above [maxHeight]; among the
 *    rest prefer the highest height, ties by highest bandwidth.
 * 3. If the cap filters everything out, fail-safe to the LOWEST
 *    representation instead of failing the download.
 * 4. Audio: highest bandwidth (rep-level, language-agnostic v1).
 *
 * v1 scope decisions, logged: first Period only; a single video
 * AdaptationSet (no layered codecs / trick modes).
 */
internal object DashSelector {

    internal fun select(
        mpd: DashMpd,
        maxHeight: Int? = null,
        log: VdlLog? = null
    ): DashSelection {
        if (mpd.isDynamic) {
            log?.w(TAG) { "dynamic MPD decision=download-current-window" }
        }
        if (mpd.periods.size > 1) {
            log?.w(TAG) { "periods=${mpd.periods.size} decision=first-period-only" }
        }
        val period = mpd.periods.first()

        val videoSet = period.adaptationSets.firstOrNull { it.mimeType?.startsWith("video/") == true }
            ?: period.adaptationSets.firstOrNull { asx ->
                asx.mimeType == null && asx.representations.any { it.height != null }
            }
            ?: throw IllegalArgumentException("no video AdaptationSet in MPD")
        val audioSet = period.adaptationSets.firstOrNull { it.mimeType?.startsWith("audio/") == true }

        val reps = videoSet.representations
        val allowed = maxHeight?.let { h -> reps.filter { (it.height ?: 0) in 1 until h + 1 } } ?: reps
        val video = if (allowed.isNotEmpty()) {
            allowed.sortedWith(
                compareByDescending<DashRepresentation> { it.height ?: -1 }
                    .thenByDescending { it.bandwidth }
            ).first()
        } else {
            // fail-safe: the cap filtered everything -> LOWEST rep, not a failure
            reps.sortedWith(
                compareBy<DashRepresentation> { it.height ?: Int.MAX_VALUE }
                    .thenBy { it.bandwidth }
            ).first()
        }
        if (allowed.isEmpty() && maxHeight != null) {
            log?.w(TAG) { "maxHeight=$maxHeight filtered all video reps decision=failsafe-lowest rep=${video.id} h=${video.height ?: "?"}" }
        }

        val audio = audioSet?.representations?.maxByOrNull { it.bandwidth }

        log?.i(TAG) {
            "selected video=${video.id} h=${video.height ?: "?"} bw=${video.bandwidth} " +
                "audio=${audio?.id ?: "muxed"} audioLang=${audioSet?.lang ?: "-"} " +
                "reps=${reps.size} maxHeight=${maxHeight ?: "none"}"
        }
        return DashSelection(
            video = video,
            videoLang = videoSet.lang,
            videoMimeType = videoSet.mimeType,
            audio = audio,
            audioLang = audioSet?.lang
        )
    }

    private const val TAG = "[VDL][DASH][selector]"
}
