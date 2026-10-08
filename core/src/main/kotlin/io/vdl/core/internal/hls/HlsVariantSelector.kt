package io.vdl.core.internal.hls

/**
 * Variant + rendition selection for a parsed master playlist.
 *
 * Policy (deterministic, evidence-logged):
 * 1. Drop variants above [maxHeight] (height-based cap, if set).
 * 2. Among the rest, prefer the highest resolution; ties resolved by
 *    highest AVERAGE-BANDWIDTH (falls back to BANDWIDTH).
 * 3. If the cap filters everything out, fail-safe to the *lowest* variant
 *    instead of failing the download.
 * 4. Audio: from the chosen variant's AUDIO group pick the DEFAULT=YES
 *    rendition, else AUTOSELECT, else the first one that has a URI.
 */
internal object HlsVariantSelector {

    internal data class Selection(
        val video: HlsVariant,
        /** Chosen audio rendition, null when muxed into the video stream. */
        val audio: MediaRendition?
    )

    internal fun select(
        master: MasterPlaylist,
        maxHeight: Int? = null,
        log: io.vdl.core.internal.logging.VdlLog? = null
    ): Selection {
        val byHeight = master.variants.sortedWith(
            compareByDescending<HlsVariant> { it.resolutionHeight ?: -1 }
                .thenByDescending { it.averageBandwidth ?: it.bandwidth }
        )
        val eligible = byHeight.filter { v ->
            maxHeight == null || (v.resolutionHeight ?: 0) <= maxHeight
        }
        // Fail-safe: cap too low for this master -> take the smallest variant.
        val chosen = eligible.firstOrNull() ?: byHeight.last()
        val audio = pickAudio(master, chosen)

        log?.i(TAG) {
            val reason = if (eligible.isEmpty()) "cap-too-low fallback" else "best-of-${eligible.size}"
            "select variant=${describe(chosen)} audio=${audio?.name ?: "muxed"} $reason"
        }
        return Selection(chosen, audio)
    }

    private fun pickAudio(master: MasterPlaylist, variant: HlsVariant): MediaRendition? {
        val group = variant.audioGroup ?: return null
        val renditions = master.media.filter { it.group == group && it.uri != null }
        if (renditions.isEmpty()) return null
        return renditions.firstOrNull { it.isDefault }
            ?: renditions.firstOrNull { it.type == "AUDIO" }
            ?: renditions.first()
    }

    private fun describe(v: HlsVariant): String {
        val res = if (v.resolutionHeight != null) "${v.resolutionWidth}x${v.resolutionHeight}" else "unknown-res"
        return "${res}@${v.averageBandwidth ?: v.bandwidth}bps ${v.uri.substringAfterLast('/')}"
    }

    private const val TAG = "[VDL][HLS][selector]"
}
