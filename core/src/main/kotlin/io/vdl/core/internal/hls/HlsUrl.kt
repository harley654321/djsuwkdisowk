package io.vdl.core.internal.hls

/**
 * Minimal RFC 3986 section 5 relative-reference resolver for playlist URIs.
 * Pure Kotlin: no android.net.Uri so the parser stays JVM-testable.
 *
 * Covers everything real playlists use: absolute URIs, protocol-relative
 * (//host/path), same-directory refs (seg1.ts), subdirectories (a/b/seg.ts),
 * parent walking (../../seg.ts), dot segments, and suffix joins
 * (master.m3u8 + ../media/seg.ts -> .../media/seg.ts).
 */
internal object HlsUrl {

    fun resolve(baseUrl: String?, reference: String): String {
        if (reference.isBlank()) return reference
        if (hasScheme(reference)) return reference
        if (reference.startsWith("//")) {
            // protocol-relative: inherit the base scheme
            val scheme = baseUrl?.substringBefore("://")?.takeIf { hasScheme(it + "://x") } ?: "https"
            return "$scheme:$reference"
        }
        if (baseUrl.isNullOrBlank()) return reference
        if (baseUrl.substringBefore('?').substringBefore('#').endsWith("/")) {
            return mergeDirectory(baseUrl, reference)
        }
        // base is a file-like URL (…/master.m3u8): resolve against its directory
        val directory = baseUrl.substringBeforeLast('/') + "/"
        return mergeDirectory(directory, reference)
    }

    private fun mergeDirectory(directory: String, reference: String): String {
        val ref = if (reference.startsWith("/")) {
            // server-absolute path: replace path, keep scheme+authority
            val schemeEnd = directory.indexOf("://")
            if (schemeEnd > 0) {
                val scheme = directory.substring(0, schemeEnd)
                val authorityEnd = directory.indexOf('/', schemeEnd + 3)
                val authority = if (authorityEnd > 0) directory.substring(0, authorityEnd) else directory
                return authority + normalize(reference)
            }
            reference
        } else {
            normalize(directory + reference)
        }
        return ref
    }

    /** Collapses ./ and ../ segments and duplicate slashes in the path. */
    private fun normalize(url: String): String {
        val hashIdx = url.indexOf('#')
        val fragment = if (hashIdx >= 0) url.substring(hashIdx) else ""
        val rest = if (hashIdx >= 0) url.substring(0, hashIdx) else url
        val queryIdx = rest.indexOf('?')
        val query = if (queryIdx >= 0) rest.substring(queryIdx) else ""
        val path = if (queryIdx >= 0) rest.substring(0, queryIdx) else rest

        val schemeEnd = path.indexOf("://")
        val prefix = if (schemeEnd > 0) path.substring(0, schemeEnd + 3) else ""
        val body = if (schemeEnd > 0) path.substring(schemeEnd + 3) else path
        // authority ends at the first '/' after host[:port]
        val slash = body.indexOf('/')
        val authority = if (slash >= 0) body.substring(0, slash) else body
        val rawPath = if (slash >= 0) body.substring(slash) else "/"

        val out = ArrayDeque<String>()
        for (seg in rawPath.split('/')) {
            when (seg) {
                "", "." -> Unit // skip empty (from //) and current-dir
                ".." -> if (out.isNotEmpty() && out.last() != "..") out.removeLast()
                else -> out.addLast(seg)
            }
        }
        val normPath = out.joinToString("/").let { if (it.isEmpty()) "" else it }
        return "$prefix$authority/$normPath$query$fragment".let {
            // avoid leaving a trailing bare host slash off when rawPath was "/"
            if (rawPath == "/" && normPath.isEmpty()) "$prefix$authority/" else it
        }
    }

    private fun hasScheme(url: String): Boolean {
        val idx = url.indexOf("://")
        if (idx <= 0) return false
        val scheme = url.substring(0, idx)
        return scheme.all { it.isLetterOrDigit() || it in "+-." } && scheme.first().isLetter()
    }
}
