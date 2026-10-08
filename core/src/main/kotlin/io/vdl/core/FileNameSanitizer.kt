package io.vdl.core

/**
 * Filename sanitization, CWE-22 hardened.
 *
 * Pipeline (verified by unit tests):
 * 1. unify separators, then normalize the path with a stack: `..` pops the
 *    previous kept segment (clamped at root) so traversal never escapes.
 * 2. replace hostile characters: `:`, shell metachars (*?"<>|) and
 *    control chars (<32, DEL) become `_`; a RUN of consecutive hostile
 *    characters collapses into a single `_` (literal user underscores
 *    are never touched).
 * 3. collapse whitespace runs, trim, and strip dots from both ends.
 * 4. cap at 120 chars preserving a short extension suffix.
 * 5. never empty: falls back to "download.bin".
 */
internal object FileNameSanitizer {

    private const val MAX_LEN = 120
    private const val FALLBACK = "download.bin"

    internal fun sanitize(raw: String?): String {
        if (raw.isNullOrBlank()) return FALLBACK

        // 1. traversal-safe stack normalization
        val stack = ArrayDeque<String>()
        raw.replace('\\', '/').split('/').forEach { seg ->
            when {
                seg.isEmpty() || seg == "." -> Unit
                seg == ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(seg)
            }
        }
        var name = stack.joinToString(" ")

        // 2. hostile characters; runs collapse to a single underscore so
        // "a*?b" -> "a_b", while literal user underscores survive intact.
        val sb = StringBuilder(name.length)
        var prevReplaced = false
        for (c in name) {
            val hostile = c == ':' || c == '*' || c == '?' || c == '"' ||
                c == '<' || c == '>' || c == '|' || c.code < 32 || c.code == 127
            if (hostile) {
                if (!prevReplaced) sb.append('_')
                prevReplaced = true
            } else {
                sb.append(c)
                prevReplaced = false
            }
        }
        name = sb.toString()

        // 3. whitespace + dots
        name = name.replace(Regex("\\s+"), " ").trim().trim('.')

        // 4. length cap keeping a short suffix
        if (name.length > MAX_LEN) {
            val lastDot = name.lastIndexOf('.')
            val suffix = if (lastDot > 0 && name.length - lastDot in 1..10) name.substring(lastDot) else ""
            val stem = if (suffix.isEmpty()) name else name.dropLast(suffix.length)
            name = stem.take((MAX_LEN - suffix.length).coerceAtLeast(0)) + suffix
        }
        return name.ifBlank { FALLBACK }
    }
}
