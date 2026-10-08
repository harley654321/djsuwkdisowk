package io.vdl.core.internal.hls

/**
 * Flattens a [MediaPlaylist] into ordered download units.
 *
 * - EXT-X-MAP becomes unit 0 (fMP4 init section).
 * - AES-128 IVs: explicit HEX from the tag wins; absent IVs are derived
 *   from the segment media sequence number (RFC 8216 4.3.2.4: the
 *   128-bit big-endian sequence, zero-padded to 16 bytes).
 * - BYTERANGE offsets that the playlist omits ("n" without "@o") are the
 *   position right after the previous range *of the same resource*.
 */
internal object HlsPlan {

    /** One downloadable unit in output order. */
    internal data class Unit(
        val index: Int,
        val url: String,
        val byterangeOffset: Long?,
        val byterangeLength: Long?,
        val keyMethod: String?,   // null: plaintext
        val keyUrl: String?,
        val iv: ByteArray          // 16 bytes, always resolved here
    ) {
        override fun equals(other: Any?): Boolean = other is Unit && other.index == index
        override fun hashCode(): Int = index
    }

    internal fun build(media: MediaPlaylist): List<Unit> {
        val out = ArrayList<Unit>(media.segments.size + 1)
        var idx = 0

        // fMP4 init section first; it shares the key of the first segment
        // that follows it (RFC 8216 4.3.2.5) with an IV derived from the
        // playlist media sequence.
        media.initSegment?.let { init ->
            val key = media.segments.firstOrNull()?.key
            out.add(
                Unit(
                    index = idx++,
                    url = init.uri,
                    byterangeOffset = init.byterangeOffset,
                    byterangeLength = init.byterangeLength,
                    keyMethod = key?.method,
                    keyUrl = key?.uri,
                    iv = resolveIv(key, media.mediaSequence)
                )
            )
        }

        // cursor for omitted BYTERANGE offsets, per resource URL
        val rangeCursors = HashMap<String, Long>()
        media.segments.forEachIndexed { i, seg ->
            var offset = seg.byterangeOffset
            if (offset == null && seg.byterangeLength != null) {
                offset = rangeCursors[seg.uri] ?: 0L
            }
            if (seg.byterangeLength != null) {
                rangeCursors[seg.uri] = offset!! + seg.byterangeLength
            }
            out.add(
                Unit(
                    index = idx++,
                    url = seg.uri,
                    byterangeOffset = offset,
                    byterangeLength = seg.byterangeLength,
                    keyMethod = seg.key?.method,
                    keyUrl = seg.key?.uri,
                    iv = resolveIv(seg.key, media.mediaSequence + i)
                )
            )
        }
        return out
    }

    /** Explicit hex IV wins; otherwise derive from the sequence number. */
    private fun resolveIv(key: HlsKey?, sequence: Long): ByteArray {
        key?.ivHex?.let { hex ->
            val clean = hex.replace("0x", "").replace("0X", "")
            if (clean.length == 32) {
                val iv = ByteArray(16)
                for (i in 0 until 16) {
                    iv[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
                return iv
            }
        }
        return sequenceIv(sequence)
    }

    /** 128-bit big-endian sequence number, zero-padded. */
    internal fun sequenceIv(sequence: Long): ByteArray {
        val iv = ByteArray(16)
        for (i in 0 until 8) {
            iv[15 - i] = (sequence ushr (8 * i)).toByte()
        }
        return iv
    }
}
