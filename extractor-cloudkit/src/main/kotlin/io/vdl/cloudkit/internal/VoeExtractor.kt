package io.vdl.cloudkit.internal

import io.vdl.cloudkit.CloudKitKind
import io.vdl.cloudkit.CloudKitResult
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Minimal HTTP layer for cloudkit extractors. One seam, one place to set
 * the browser-like headers the hosts expect (UA + Referer are checked by
 * voe/filemoon CDNs). Body size is capped: embed pages are < 1 MB; a host
 * returning a giant body is hostile or we hit the wrong URL, and reading
 * it would burn memory in vain.
 */
internal class CloudHttp(private val client: OkHttpClient) {

    internal class Response(val body: String, val code: Int)

    /** GET [url] with optional [referer]; returns the decoded body or throws [IOException]. */
    @Throws(IOException::class)
    internal fun get(url: String, referer: String? = null): Response {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
        referer?.let { builder.header("Referer", it) }
        client.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.byteStream()?.let { stream ->
                stream.readBytesSafely(MAX_BODY_BYTES)
            } ?: ByteArray(0)
            if (!resp.isSuccessful) {
                throw IOException("http ${resp.code} for $url")
            }
            return Response(String(body, Charsets.UTF_8), resp.code)
        }
    }

    private fun java.io.InputStream.readBytesSafely(cap: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(16 * 1024)
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (total < cap) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    /**
     * Minimal JSON string unescape. The decrypted payload carries URL
     * values with \/ and \uXXXX escapes; the extractor regex stops at the
     * closing quote, so the captured value must be unescaped before use
     * (live evidence X1: source arrives as "https:\/\/ugc-cdn...").
     */
    private fun unescapeJson(input: String): String {
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

    internal companion object {
        internal const val DESKTOP_UA: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        internal const val MAX_BODY_BYTES: Int = 1 shl 20
    }
}

/**
 * Voe.sx (+ rotating mirror domains) extractor.
 *
 * Ported from recloudstream/cloudstream `extractors/Voe.kt` (GPL-3.0,
 * https://github.com/recloudstream/cloudstream — commit master @ 2026-10).
 * Mechanism verified live 2026-10-09 against voe.sx/wodlv7os3van (X1):
 *
 *   1. embed URL returns a tiny JS shell that redirects to the rotating
 *      player domain:  window.location.href = 'https://....com/<id>';
 *   2. the player page carries the stream inside
 *      <script type="application/json">["<obfuscated>"]</script>;
 *   3. decryptF7 chain: rot13 -> replace noise tokens with '_' -> drop
 *      '_' -> base64 -> char-shift(-3) -> reverse -> base64 -> JSON
 *      {source: <master.m3u8>, direct_access_url: <mp4>}.
 *
 * Zero dependencies beyond OkHttp: the redirect and the JSON script tag
 * are parsed with anchored regexes (the page is machine-generated), and
 * the decrypted JSON is flat with the two keys we read.
 */
internal class VoeExtractor(private val http: CloudHttp) {

    internal fun matches(url: String): Boolean = KNOWN_DOMAINS.any { url.contains(it) }

    /**
     * Resolves a voe embed URL to the master m3u8 (preferred) or the direct
     * mp4. Returns null when any step degrades (shell without redirect,
     * missing JSON script, undecryptable payload) — never throws.
     */
    internal suspend fun resolve(url: String): CloudKitResult? {
        // Step 1: follow the rotating-domain JS redirect (0-1 hops).
        var playerUrl = url
        var page = try {
            http.get(url)
        } catch (t: IOException) {
            return null
        }
        REDIRECT_REGEX.find(page.body)?.groupValues?.get(1)?.let { target ->
            if (target != url) {
                playerUrl = target
                page = try {
                    http.get(target, referer = url)
                } catch (t: IOException) {
                    return null
                }
            }
        }

        // Step 2: pull the obfuscated payload out of the JSON script tag.
        val encoded = JSON_SCRIPT_REGEX.find(page.body)?.groupValues?.get(1)
            ?: return null

        // Step 3: decryptF7 chain.
        val decrypted = decryptF7(encoded) ?: return null
        val m3u8 = decrypted.source
        val mp4 = decrypted.directAccessUrl

        return when {
            !m3u8.isNullOrEmpty() -> CloudKitResult(
                url = m3u8, kind = CloudKitKind.HLS, referer = playerUrl, extractor = "Voe",
            )
            !mp4.isNullOrEmpty() -> CloudKitResult(
                url = mp4, kind = CloudKitKind.DIRECT, referer = playerUrl, extractor = "Voe",
            )
            else -> null
        }
    }

    internal class Decrypted internal constructor(internal val source: String?, internal val directAccessUrl: String?)

    internal fun decryptF7(payload: String): Decrypted? = try {
        val vRot = rot13(payload)
        val vNoise = NOISE_TOKENS.fold(vRot) { acc, token -> acc.replace(token, "_") }
        val vClean = vNoise.replace("_", "")
        val vB64 = base64Decode(vClean) ?: return null
        // ISO-8859-1 keeps each byte as its own code point: the shift and the
        // reverse below stay byte-exact regardless of UTF-8 multibyte content.
        val vChars = String(vB64, Charsets.ISO_8859_1)
        val vShift = buildString(vChars.length) { vChars.forEach { append((it.code - 3).toChar()) } }
        val vReversed = vShift.reversed()
        val vJsonBytes = base64Decode(vReversed) ?: return null
        val json = String(vJsonBytes, Charsets.UTF_8)
        Decrypted(
            source = JSON_FIELD("source").find(json)?.groupValues?.get(1)?.let(::unescapeJson),
            directAccessUrl = JSON_FIELD("direct_access_url").find(json)?.groupValues?.get(1)
                ?.let(::unescapeJson),
        )
    } catch (t: Throwable) {
        null
    }

    private fun rot13(input: String): String = buildString(input.length) {
        for (c in input) {
            append(
                when (c) {
                    in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
                    in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
                    else -> c
                },
            )
        }
    }

    /** RFC4648 base64 with URL-safe mapping and padding tolerance. */
    private fun base64Decode(input: String): ByteArray? = try {
        val normalized = input
            .replace('-', '+')
            .replace('_', '/')
            .let { it + "=".repeat((-it.length) % 4) }
        java.util.Base64.getDecoder().decode(normalized)
    } catch (t: IllegalArgumentException) {
        null
    }

    /**
     * Minimal JSON string unescape. The decrypted payload carries URL
     * values with \/ and \uXXXX escapes; the extractor regex stops at the
     * closing quote, so the captured value must be unescaped before use
     * (live evidence X1: source arrives as "https:\/\/ugc-cdn...").
     */
    private fun unescapeJson(input: String): String {
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

    internal companion object {
        // Rotating player/embed domains known to serve the same engine
        // (mirror list from recloudstream Voe.kt + live evidence X1).
        internal val KNOWN_DOMAINS: List<String> = listOf(
            "voe.sx", "tubelessceliolymph.com", "simpulumlamerop.com",
            "urochsunloath.com", "nathanfromsubject.com", "yip.su",
            "metagnathtuggers.com", "donaldlineelse.com", "charlestoughrace.com",
        )

        internal val REDIRECT_REGEX: Regex =
            Regex("""window\.location\.href\s*=\s*'([^']+)'""")
        internal val JSON_SCRIPT_REGEX: Regex =
            Regex("""<script[^>]*type="application/json"[^>]*>\s*\["(.*?)"\]\s*</script>""", RegexOption.DOT_MATCHES_ALL)

        private val NOISE_TOKENS: List<String> = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")
        private fun JSON_FIELD(name: String): Regex =
            Regex(""""$name"\s*:\s*"([^"]*)"""")
    }
}
