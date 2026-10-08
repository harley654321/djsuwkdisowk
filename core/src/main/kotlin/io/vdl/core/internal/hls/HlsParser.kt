package io.vdl.core.internal.hls

import io.vdl.core.internal.logging.VdlLog

/** Hard parse failure: the playlist text is unusable. */
internal class HlsParseException internal constructor(
    message: String,
    internal val playlistUrl: String
) : RuntimeException(message)

/**
 * RFC 8216 m3u8 parser. Master and media playlists, AES-128 keys,
 * EXT-X-MAP (fMP4), BYTERANGE segments, live/VOD detection.
 *
 * Unknown tags are ignored (spec MUST), malformed known constructs are
 * logged WARN and skipped; structural breakage (no EXTM3U, EXTINF without
 * a following URI, master variant without BANDWIDTH/URI) fails fast with
 * [HlsParseException] so the queue can mark the task Fatal with a reason.
 */
internal class HlsParser internal constructor(private val log: VdlLog? = null) {

    internal fun parse(text: String, playlistUrl: String): HlsPlaylist {
        val t0 = System.nanoTime()
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "#EXTM3U" }
            .toList()
        if (!text.startsWith("#EXTM3U")) {
            log?.e(TAG) { "parse fail uri=$playlistUrl reason=missing-EXTM3U" }
            throw HlsParseException("playlist does not start with #EXTM3U", playlistUrl)
        }
        val isMaster = lines.any { it.startsWith(MASTER_TAG) }
        val result = try {
            if (isMaster) parseMaster(lines, playlistUrl) else parseMedia(lines, playlistUrl)
        } catch (t: HlsParseException) {
            log?.e(TAG, t) { "parse fail uri=$playlistUrl reason=${t.message}" }
            throw t
        }
        val dtMs = (System.nanoTime() - t0) / 1_000_000L
        log?.i(TAG) {
            val shape = if (result is MasterPlaylist) {
                "master variants=${result.variants.size} renditions=${result.media.size}"
            } else {
                val mp = result as MediaPlaylist
                "media segments=${mp.segments.size} live=${mp.isLive} key=${mp.segments.firstOrNull()?.key?.method ?: "none"}"
            }
            "parse ok uri=$playlistUrl $shape dt=${dtMs}ms"
        }
        return result
    }

    // ------------------------------------------------------------------ master

    private fun parseMaster(lines: List<String>, url: String): MasterPlaylist {
        val variants = ArrayList<HlsVariant>(8)
        val renditions = ArrayList<MediaRendition>(4)
        var pendingVariantAttrs: Map<String, String>? = null
        var lineNo = 0
        for (raw in lines) {
            lineNo++
            when {
                raw.startsWith(MASTER_TAG) -> {
                    if (pendingVariantAttrs != null) {
                        warn(url, lineNo, "STREAM-INF without URI; variant dropped")
                    }
                    pendingVariantAttrs = HlsAttrs.parse(raw.removePrefix(MASTER_TAG))
                }
                raw.startsWith(MEDIA_TAG) ->
                    parseRendition(HlsAttrs.parse(raw.removePrefix(MEDIA_TAG)), url, lineNo)
                        ?.let { renditions.add(it) }
                raw.startsWith("#") -> Unit // comment or unknown tag: ignore
                pendingVariantAttrs != null -> {
                    val attrs = pendingVariantAttrs
                    pendingVariantAttrs = null
                    val bw = attrs["BANDWIDTH"]?.toLongOrNull()
                    if (bw == null) {
                        throw HlsParseException("EXT-X-STREAM-INF without BANDWIDTH", url)
                    }
                    val (w, h) = parseResolution(attrs["RESOLUTION"])
                    variants.add(
                        HlsVariant(
                            uri = HlsUrl.resolve(url, raw),
                            bandwidth = bw,
                            averageBandwidth = attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull(),
                            codecs = attrs["CODECS"],
                            resolutionWidth = w,
                            resolutionHeight = h,
                            frameRate = attrs["FRAME-RATE"]?.toDoubleOrNull(),
                            audioGroup = attrs["AUDIO"]
                        )
                    )
                }
                else -> warn(url, lineNo, "bare URI line outside STREAM-INF ignored: $raw")
            }
        }
        if (pendingVariantAttrs != null) {
            throw HlsParseException("EXT-X-STREAM-INF without following URI", url)
        }
        if (variants.isEmpty()) {
            throw HlsParseException("master playlist without variants", url)
        }
        return MasterPlaylist(url, variants, renditions)
    }

    private fun parseRendition(attrs: Map<String, String>, url: String, lineNo: Int): MediaRendition? {
        val type = attrs["TYPE"]?.uppercase()
        if (type == null) {
            warn(url, lineNo, "EXT-X-MEDIA without TYPE; rendition dropped")
            return null
        }
        return MediaRendition(
            type = type,
            group = attrs["GROUP-ID"],
            uri = attrs["URI"]?.let { HlsUrl.resolve(url, it) },
            language = attrs["LANGUAGE"],
            name = attrs["NAME"],
            isDefault = attrs["DEFAULT"]?.equals("YES", ignoreCase = true) == true,
            channels = attrs["CHANNELS"]
        )
    }

    // ------------------------------------------------------------------- media

    private fun parseMedia(lines: List<String>, url: String): MediaPlaylist {
        var targetDuration = -1.0
        var mediaSequence = 0L
        var endList = false
        var init: HlsInit? = null
        val segments = ArrayList<HlsSegment>(512)

        var key: HlsKey? = null
        var pendingDuration: Double? = null
        var pendingRange: Pair<Long, Long?>? = null // (length, offset)

        for (raw in lines) {
            when {
                raw.startsWith(TARGET_DURATION_TAG) ->
                    targetDuration = raw.removePrefix(TARGET_DURATION_TAG).trim().toDoubleOrNull() ?: -1.0
                raw.startsWith(MEDIA_SEQUENCE_TAG) ->
                    mediaSequence = raw.removePrefix(MEDIA_SEQUENCE_TAG).trim().toLongOrNull() ?: 0L
                raw == ENDLIST_TAG -> endList = true
                raw.startsWith(KEY_TAG) -> key = parseKey(raw.removePrefix(KEY_TAG), url)
                raw.startsWith(MAP_TAG) -> {
                    val attrs = HlsAttrs.parse(raw.removePrefix(MAP_TAG))
                    val range = attrs["BYTERANGE"]?.let { parseByterange(it, url) }
                    val uri = attrs["URI"]
                        ?: throw HlsParseException("EXT-X-MAP without URI", url)
                    init = HlsInit(HlsUrl.resolve(url, uri), range?.second, range?.first)
                }
                raw.startsWith(BYTERANGE_TAG) ->
                    pendingRange = parseByterange(raw.removePrefix(BYTERANGE_TAG), url)
                raw.startsWith(INF_TAG) ->
                    pendingDuration = raw.removePrefix(INF_TAG)
                        .substringBefore(',').trim().toDoubleOrNull()
                raw.startsWith("#") -> Unit // unknown tag or comment: ignore
                else -> {
                    // a URI line: consume pending EXTINF (+optional BYTERANGE)
                    val duration = pendingDuration
                        ?: throw HlsParseException("segment URI without EXTINF: $raw", url)
                    pendingDuration = null
                    val (length, offset) = pendingRange ?: Pair(null, null)
                    pendingRange = null
                    segments.add(
                        HlsSegment(
                            uri = HlsUrl.resolve(url, raw),
                            durationSec = duration,
                            byterangeOffset = offset,
                            byterangeLength = length,
                            key = key
                        )
                    )
                }
            }
        }
        if (pendingDuration != null) {
            throw HlsParseException("EXTINF without following URI (truncated playlist)", url)
        }
        if (targetDuration < 0) {
            throw HlsParseException("media playlist without EXT-X-TARGETDURATION", url)
        }
        if (segments.isEmpty()) {
            throw HlsParseException("media playlist without segments", url)
        }
        return MediaPlaylist(url, targetDuration, mediaSequence, !endList, init, segments)
    }

    private fun parseKey(attrs: String, url: String): HlsKey? {
        val map = HlsAttrs.parse(attrs)
        val method = map["METHOD"]?.uppercase() ?: return null
        if (method == "NONE") return null
        val keyUri = map["URI"]?.let { HlsUrl.resolve(url, it) }
        if (keyUri == null) {
            throw HlsParseException("EXT-X-KEY method=$method without URI", url)
        }
        return HlsKey(method, keyUri, map["IV"]?.removePrefix("0x")?.removePrefix("0X"))
    }

    /** `n[@o]` -> (length, offset). Offset null means "previous end". */
    private fun parseByterange(spec: String, url: String): Pair<Long, Long?> {
        val length = spec.substringBefore('@').trim().toLongOrNull()
            ?: throw HlsParseException("bad BYTERANGE spec: $spec", url)
        val offset = spec.substringAfter('@', "").trim().toLongOrNull()
        return Pair(length, offset)
    }

    private fun parseResolution(value: String?): Pair<Int?, Int?> {
        if (value == null) return Pair(null, null)
        val parts = value.split('x')
        if (parts.size != 2) return Pair(null, null)
        return Pair(parts[0].trim().toIntOrNull(), parts[1].trim().toIntOrNull())
    }

    private fun warn(url: String, lineNo: Int, message: String) {
        log?.w(TAG) { "parse warn uri=$url line=$lineNo $message" }
    }

    private companion object {
        internal const val TAG = "[VDL][HLS][parser]"
        internal const val MASTER_TAG = "#EXT-X-STREAM-INF:"
        internal const val MEDIA_TAG = "#EXT-X-MEDIA:"
        internal const val TARGET_DURATION_TAG = "#EXT-X-TARGETDURATION:"
        internal const val MEDIA_SEQUENCE_TAG = "#EXT-X-MEDIA-SEQUENCE:"
        internal const val ENDLIST_TAG = "#EXT-X-ENDLIST"
        internal const val KEY_TAG = "#EXT-X-KEY:"
        internal const val MAP_TAG = "#EXT-X-MAP:"
        internal const val BYTERANGE_TAG = "#EXT-X-BYTERANGE:"
        internal const val INF_TAG = "#EXTINF:"
    }
}
