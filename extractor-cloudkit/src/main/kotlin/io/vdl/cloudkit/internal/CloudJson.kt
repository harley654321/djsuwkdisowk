package io.vdl.cloudkit.internal

/**
 * Shared JSON string unescape for extracted payloads. Hosts deliver URL
 * values inside JSON with \/ and \uXXXX escapes (live evidence X1:
 * byse decrypted playback uses \u0026 for query separators; voe uses
 * \/ inside source URLs). Regex captures stop at the closing quote, so
 * the captured value must be unescaped before use.
 */
internal object CloudJson {

    internal fun unescape(input: String): String {
        val out = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '\\' && i + 1 < input.length) {
                when (input[i + 1]) {
                    '/' -> out.append('/')
                    '\\' -> out.append('\\')
                    '"' -> out.append('"')
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'b' -> out.append('\b')
                    'f' -> out.append('')
                    'u' -> if (i + 5 < input.length) {
                        out.append(input.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 4
                    } else {
                        out.append(c)
                    }
                    else -> out.append(input[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
