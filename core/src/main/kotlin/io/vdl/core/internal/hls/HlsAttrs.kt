package io.vdl.core.internal.hls

/**
 * HLS attribute-list parser (RFC 8216 section 4.2). Attribute values may be
 * quoted strings containing commas, decimal integers, hexadecimal, or
 * dotted decimals, e.g.:
 *   `BANDWIDTH=1280000,CODECS="avc1.42e00a,mp4a.40.2",AUDIO="aud-hi"`
 * Unknown attributes are preserved; the caller picks what it needs.
 */
internal object HlsAttrs {

    fun parse(attrs: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        val n = attrs.length
        while (i < n) {
            // key up to '='
            val eq = attrs.indexOf('=', i)
            if (eq < 0) break
            val key = attrs.substring(i, eq).trim()
            i = eq + 1
            if (i >= n) {
                out[key] = ""
                break
            }
            val value: String
            if (attrs[i] == '"') {
                // quoted: scan to the closing quote; no escape sequences in
                // the HLS grammar.
                val close = attrs.indexOf('"', i + 1)
                if (close < 0) {
                    out[key] = attrs.substring(i + 1)
                    break
                }
                value = attrs.substring(i + 1, close)
                i = close + 1
            } else {
                val comma = attrs.indexOf(',', i)
                value = if (comma < 0) attrs.substring(i) else attrs.substring(i, comma)
                i = if (comma < 0) n else comma + 1
            }
            out[key] = value.trim()
            // for quoted values, skip the separating comma
            if (i < n && attrs[i] == ',') i++
            while (i < n && attrs[i] == ' ') i++
        }
        return out
    }
}
