package io.vdl.core.internal.mp4

/**
 * ISO 14496-12 toolbox: box reading/writing and primitive codecs.
 * Everything is ByteArray-based and JVM-pure: testable without a device.
 */
internal object Mp4 {

    /** A parsed box: type + raw body (children not parsed recursively). */
    internal class Box internal constructor(
        internal val type: String,
        internal val body: ByteArray
    ) {
        internal fun size(): Int = 8 + body.size
    }

    /** Reads consecutive boxes in [start, end). Sizes 0 (to-end) and 1 (large) honored. */
    internal fun readBoxes(data: ByteArray, start: Int = 0, end: Int = data.size): List<Box> {
        val out = ArrayList<Box>()
        var off = start
        while (off + 8 <= end) {
            var size = readU32(data, off).toInt()
            val type = String(data, off + 4, 4, Charsets.US_ASCII)
            var header = 8
            if (size == 1) {
                if (off + 16 > end) break
                size = readU64(data, off + 8).toInt()
                header = 16
            } else if (size == 0) {
                size = end - off
            }
            if (size < header || off + size > end) break
            out.add(Box(type, data.copyOfRange(off + header, off + size)))
            off += size
        }
        return out
    }

    /** Builds size(4) + type(4) + body. */
    internal fun build(type: String, body: ByteArray): ByteArray {
        val out = ByteArray(8 + body.size)
        writeU32(out, 0, (8L + body.size))
        System.arraycopy(type.toByteArray(Charsets.US_ASCII), 0, out, 4, 4)
        System.arraycopy(body, 0, out, 8, body.size)
        return out
    }

    // ------------------------------------------------------- primitives

    internal fun writeU16(dst: ByteArray, off: Int, v: Int) {
        dst[off] = (v ushr 8).toByte()
        dst[off + 1] = v.toByte()
    }

    internal fun writeU32(dst: ByteArray, off: Int, v: Long) {
        dst[off] = (v ushr 24).toByte()
        dst[off + 1] = (v ushr 16).toByte()
        dst[off + 2] = (v ushr 8).toByte()
        dst[off + 3] = v.toByte()
    }

    internal fun writeU64(dst: ByteArray, off: Int, v: Long) {
        writeU32(dst, off, v ushr 32)
        writeU32(dst, off + 4, v and 0xFFFFFFFFL)
    }

    internal fun readU16(src: ByteArray, off: Int): Int =
        ((src[off].toInt() and 0xFF) shl 8) or (src[off + 1].toInt() and 0xFF)

    internal fun readU32(src: ByteArray, off: Int): Long =
        ((src[off].toLong() and 0xFF) shl 24) or
            ((src[off + 1].toLong() and 0xFF) shl 16) or
            ((src[off + 2].toLong() and 0xFF) shl 8) or
            (src[off + 3].toLong() and 0xFF)

    internal fun readU64(src: ByteArray, off: Int): Long =
        (readU32(src, off) shl 32) or readU32(src, off + 4)

    /** FullBox: version (1 byte) + flags (3 bytes). */
    internal fun version(body: ByteArray): Int = body[0].toInt() and 0xFF

    internal fun flags(body: ByteArray): Int = flags(body, 0)

    internal fun flags(data: ByteArray, off: Int): Int =
        ((data[off + 1].toInt() and 0xFF) shl 16) or
            ((data[off + 2].toInt() and 0xFF) shl 8) or
            (data[off + 3].toInt() and 0xFF)

    internal fun fullBoxHeader(version: Int, flags: Int): ByteArray =
        byteArrayOf(version.toByte(), (flags ushr 16).toByte(), (flags ushr 8).toByte(), flags.toByte())
}
