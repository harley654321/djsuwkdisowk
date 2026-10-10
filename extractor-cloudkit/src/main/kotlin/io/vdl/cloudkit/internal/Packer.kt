package io.vdl.cloudkit.internal

/**
 * P.A.C.K.E.R. JavaScript unpacker.
 *
 * Ported from recloudstream/cloudstream `utils/JsUnpacker.kt` (GPL-3.0,
 * https://github.com/recloudstream/cloudstream — commit master @ 2026-10,
 * originally from cylonu87/JsUnpacker). Only the dead anti-ad companion
 * from upstream was dropped; the unpack algorithm is byte-faithful.
 *
 * Live evidence 2026-10-10: unpacks the REAL mixdrop embed page captured
 * from mxdrop.top/e/gjn98o4lbqk06z and recovers
 * MDCore.wurl = "//30xplewoo.mxcontent.net/v2/gjn98o4lbqk06z.mp4?..."
 */
internal object Packer {

    /** True when [script] contains an eval(p,a,c,k,e,d) packed payload. */
    internal fun isPacked(script: String): Boolean {
        return Regex("""eval\(function\(p,a,c,k,e,[rd]""").containsMatchIn(script)
    }

    /**
     * Unpacks a packed script. Returns null when the payload does not
     * match the packer shape or the symbol table is inconsistent —
     * never throws.
     */
    internal fun unpack(script: String): String? {
        return try {
        val match = Regex(
            """(?s)\}\s*\('(.*)',\s*(.*?),\s*(\d+),\s*'(.*?)'\.split\('\|'\)""",
        ).find(script) ?: return null
        if (match.groupValues.size != 5) return null

        val payload = match.groupValues[1].replace("\\'", "'")
        val radix = match.groupValues[2].toIntOrNull() ?: 36
        val count = match.groupValues[3].toIntOrNull() ?: 0
        val symtab = match.groupValues[4].split("|")
        if (symtab.size != count) return null

        val unbase = Unbase(radix)
        val decoded = StringBuilder(payload)
        var replaceOffset = 0
        Regex("""\b[a-zA-Z0-9_]+\b""").findAll(payload).forEach { wordMatch ->
            val word = wordMatch.value
            val index = unbase.unbase(word)
            val value = if (index in symtab.indices) symtab[index] else null
            if (!value.isNullOrEmpty()) {
                decoded.setRange(
                    wordMatch.range.first + replaceOffset,
                    wordMatch.range.last + 1 + replaceOffset,
                    value,
                )
                replaceOffset += value.length - word.length
            }
        }
        decoded.toString()
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Extracts the packed <script> body from an HTML page (upstream
     * getPacked/getAndUnpack behavior) and unpacks it. Returns null when
     * the page carries no packed script or the unpack degrades.
     */
    internal fun getAndUnpack(html: String): String? {
        val regex = Regex("""eval\(function\(p,a,c,k,e,[rd][\s\S]*?(?=</script>)""")
        val packed = regex.find(html)?.value ?: return null
        return unpack(packed)
    }

    /** Base-N decoder for packer symbols (upstream Unbase, faithful). */
    private class Unbase(private val radix: Int) {
        private val alphabet62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private val alphabet95 =
            " !\"#\$%&\\'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\\\]^_`abcdefghijklmnopqrstuvwxyz{|}~"
        private var alphabet: String? = null
        private val dictionary = HashMap<String, Int>(95)

        init {
            if (radix > 36) {
                alphabet = when {
                    radix < 62 -> alphabet62.substring(0, radix)
                    radix in 63..94 -> alphabet95.substring(0, radix)
                    radix == 62 -> alphabet62
                    radix == 95 -> alphabet95
                    else -> alphabet62
                }
                alphabet?.forEachIndexed { i, c -> dictionary[c.toString()] = i }
            }
        }

        fun unbase(str: String): Int {
            alphabet ?: return str.toIntOrNull(radix) ?: 0
            var ret = 0
            val tmp = str.reversed()
            tmp.forEachIndexed { i, c ->
                val digit = dictionary[c.toString()] ?: return@forEachIndexed
                ret += (radix.toDouble().pow(i.toDouble()) * digit).toInt()
            }
            return ret
        }

        private fun Double.pow(e: Double): Double = Math.pow(this, e)
    }
}
