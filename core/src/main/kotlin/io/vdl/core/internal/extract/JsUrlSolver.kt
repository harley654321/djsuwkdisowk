package io.vdl.core.internal.extract

import io.vdl.core.SourceKind
import io.vdl.core.internal.logging.VdlLog
import java.util.Base64

/**
 * Recovers media URLs that never appear contiguously in the page because
 * the site obfuscates them in JavaScript. Three techniques, in order of
 * cost, ALL evidence-backed and NONE executing raw page scripts:
 *
 *  - "atob":         atob('<base64>') literals — decoded in Kotlin, no JS.
 *  - "fromCharCode": String.fromCharCode(0x68, 0x74, ...) arrays — pure Kotlin.
 *  - "js-concat":    var x = 'a' + 'b' + ...; expressions — evaluated in
 *                    the REAL QuickJS engine, but only after the expression
 *                    passes a strict character whitelist (strings, +,
 *                    spaces). Never arbitrary site code.
 *
 * Every candidate is validated (http/https, sane length) before it leaves.
 */
internal class JsUrlSolver internal constructor(
    private val log: VdlLog,
    private val js: JsEngine
) {

    internal data class Hit(val url: String, val kind: SourceKind, val technique: String)

    private val atobPattern = Regex("""atob\(\s*['"]([A-Za-z0-9+/=]{16,512})['"]\s*\)""")
    private val charCodePattern =
        Regex("""String\.fromCharCode\(\s*((?:0x[0-9A-Fa-f]{1,4}|\d{1,5})(?:\s*,\s*(?:0x[0-9A-Fa-f]{1,4}|\d{1,5})){1,511})\s*\)""")
    // string-concat only: quoted strings joined by +, nothing else allowed
    private val concatPattern =
        Regex("""['"]([A-Za-z0-9/=._~%:?&=#\-]{2,64})['"](\s*\+\s*['"][^'"]{2,128}['"]){1,31}""")

    internal suspend fun solve(html: String, baseUrl: String): List<Hit> {
        val hits = ArrayList<Hit>()
        var t1 = 0
        var t2 = 0
        var t3 = 0

        atobPattern.findAll(html).forEach { m ->
            decodeBase64(m.groupValues[1])?.let { u ->
                accept(u, baseUrl, "atob")?.let { hits.add(it); t1++ }
            }
        }
        charCodePattern.findAll(html).forEach { m ->
            codesToString(m.groupValues[1])?.let { u ->
                accept(u, baseUrl, "fromCharCode")?.let { hits.add(it); t2++ }
            }
        }
        concatPattern.findAll(html).forEach { m ->
            val expr = buildConcatExpr(m.value)
            if (expr != null) {
                val u = js.evaluateString(expr)
                if (u != null) {
                    accept(u, baseUrl, "js-concat")?.let { hits.add(it); t3++ }
                }
            }
        }
        log.i(TAG) { "solve atob=$t1 fromCharCode=$t2 jsConcat=$t3 unique=${hits.size}" }
        return hits
    }

    /** Validates + classifies; null when not an acceptable http(s) URL. */
    private fun accept(raw: String, baseUrl: String, technique: String): Hit? {
        val url = raw.trim()
        val resolved = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") || url.startsWith("https://") -> url
            else -> return null
        }
        if (resolved.length > 2048 || resolved.any { it.isWhitespace() }) return null
        val kind = UrlKind.fromUrl(resolved) ?: return null
        log.i(TAG) { "solve hit technique=$technique kind=$kind url=$resolved" }
        return Hit(resolved, kind, technique)
    }

    /** Base64 -> UTF-8 string; no URL -> null. */
    private fun decodeBase64(b64: String): String? = try {
        val decoded = Base64.getDecoder().decode(b64)
        String(decoded, Charsets.UTF_8)
    } catch (t: Throwable) {
        log.e(TAG, t) { "atob decode failed decision=null-result" }
        null
    }

    /** "0x68, 0x74" -> "ht"; rejects > 2048 resulting chars. */
    private fun codesToString(list: String): String? {
        val sb = StringBuilder()
        for (token in list.split(',')) {
            val t = token.trim()
            val v = if (t.startsWith("0x") || t.startsWith("0X")) {
                t.substring(2).toIntOrNull(16)
            } else {
                t.toIntOrNull()
            } ?: return null
            // media URLs are ASCII; refuse anything needing surrogates
            if (v < 1 || v > 0x7F) return null
            sb.append(v.toChar())
            if (sb.length > 2048) return null
        }
        return sb.toString()
    }

    /**
     * Rebuilds the matched concat expression as a pure parenthesized
     * expression, re-verifying every character against the whitelist.
     * The regex only matched quotes/+; this keeps the invariant that only
     * whitelisted bytes ever reach QuickJS.
     */
    private fun buildConcatExpr(matched: String): String? {
        val expr = matched.filter { it != ' ' }
        val ok = expr.all { c ->
            c == '\'' || c == '"' || c == '+' ||
                c.isLetterOrDigit() || "=/&%+.:?-_~#,;".contains(c)
        }
        if (!ok) {
            log.w(TAG) { "concat expr rejected len=${expr.length} decision=skip" }
            return null
        }
        return "($matched)"
    }

    internal companion object {
        internal const val TAG = "[VDL][EXTRACT][SOLVE]"
    }
}
