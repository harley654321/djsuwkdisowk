package io.vdl.core.internal.hls

/**
 * Parsed HLS (m3u8) playlist models. Pure data; behavior lives in
 * [HlsParser] (parsing) and [HlsVariantSelector] (selection).
 */
internal sealed interface HlsPlaylist {
    /** Absolute URL the playlist text was fetched from (resolution base). */
    val uri: String
}

/**
 * A master playlist: variant streams + renditions.
 * If a file contains neither, [HlsParser] fails with [HlsParseException].
 */
internal data class MasterPlaylist(
    override val uri: String,
    val variants: List<HlsVariant>,
    val media: List<MediaRendition>
) : HlsPlaylist {
    val isMaster: Boolean get() = variants.isNotEmpty()
}

/** One EXT-X-STREAM-INF variant. */
internal data class HlsVariant(
    val uri: String,
    /** BANDWIDTH (required by spec), bits per second. */
    val bandwidth: Long,
    /** AVERAGE-BANDWIDTH when present. */
    val averageBandwidth: Long?,
    val codecs: String?,
    val resolutionWidth: Int?,
    val resolutionHeight: Int?,
    /** FRAME-RATE in fps when present. */
    val frameRate: Double?,
    /** AUDIO rendition-group name, if the variant declares one. */
    val audioGroup: String?
)

/** One EXT-X-MEDIA rendition (audio/subtitles track). */
internal data class MediaRendition(
    /** AUDIO | VIDEO | SUBTITLES | CLOSED-CAPTIONS. */
    val type: String,
    val group: String?,
    /** Rendition playlist URL; null for embedded/in-stream renditions. */
    val uri: String?,
    val language: String?,
    val name: String?,
    /** DEFAULT=YES rendition of its group. */
    val isDefault: Boolean,
    /** CHANNELS attribute (e.g. "2", "6") for AUDIO renditions. */
    val channels: String?
)

/** A media playlist: ordered segments plus playlist-level metadata. */
internal data class MediaPlaylist(
    override val uri: String,
    val targetDurationSec: Double,
    val mediaSequence: Long,
    /** True when EXT-X-ENDLIST is absent (live stream). */
    val isLive: Boolean,
    val initSegment: HlsInit?,
    val segments: List<HlsSegment>
) : HlsPlaylist {
    val totalDurationSec: Double get() = segments.sumOf { it.durationSec }
}

/** EXT-X-MAP initialization segment (fMP4 streams). */
internal data class HlsInit(
    val uri: String,
    val byterangeOffset: Long?,
    val byterangeLength: Long?
)

/** One media segment (EXTINF + URI, optional BYTERANGE, sticky KEY). */
internal data class HlsSegment(
    val uri: String,
    val durationSec: Double,
    /**
     * EXT-X-BYTERANGE offset. Offset/length both null: whole resource.
     * Length set with null offset: contiguous after the previous segment
     * (RFC 8216 4.3.2.2) — the downloader derives the byte position.
     */
    val byterangeOffset: Long?,
    val byterangeLength: Long?,
    /** Active EXT-X-KEY at this segment; null means unencrypted. */
    val key: HlsKey?
)

/**
 * Segment encryption key. The engine downloads KEYURI and decrypts the
 * segment (AES-128, 16-byte blocks). An absent IV is derived at download
 * time from the segment media sequence number (RFC 8216 4.3.2.4).
 */
internal data class HlsKey(
    /** NONE | AES-128 | SAMPLE-AES. */
    val method: String,
    val uri: String?,
    /** HEX-INTEGER IV when the tag declares one. */
    val ivHex: String?
)
