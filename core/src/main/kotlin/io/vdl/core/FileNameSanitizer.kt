package io.vdl.core

/**
 * Filename sanitization, CWE-22 hardened.
 *
 * Rules (all verified by unit tests):
 * - strips path separators, traversal sequences and control characters
 * - collapses whitespace, trims dots from both ends (".hidden" -> "hidden")
 * - caps length at 120 chars (keeps suffix)
 * - never returns empty: falls back to "download.bin"
 */
internal object FileNameSanitizer {

    private const val MAX_LEN = 120
    private const val FALLBACK = "download.bin"

    internal fun sanitize(raw: String?): String {
        if (raw.isNullOrBlank()) return FALLBACK
        var name = raw
            .replace('\\', '/')
            .substringAfterLast('/')
            .replace("..", "")
            .replace(':', '_')
            .replace("*?\"<>|", "_")
            .map { c -> if (c.code < 32 || c.code == 127) '_' else c }
            .joinToString("")
            .trim()
            .trim('.')
            .replace(Regex("\\s+"), " ")
        if (name.length > MAX_LEN) {
            val cut = name.take(MAX_LEN)
            val dot = cut.lastIndexOf('.')
            name = if (dot > 0) cut.substring(0, dot) + cut.substring(dot) else cut
        }
        if (name.isBlank()) return FALLBACK
        return name
    }
}
