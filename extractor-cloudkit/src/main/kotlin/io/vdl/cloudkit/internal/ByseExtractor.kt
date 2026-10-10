package io.vdl.cloudkit.internal

import io.vdl.cloudkit.CloudKitKind
import io.vdl.cloudkit.CloudKitResult

import java.io.IOException
import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Byse extractor (byse.sx rotating-domain family, SPA backend).
 *
 * Upstream reference: recloudstream/cloudstream `extractors/ByseSX.kt`
 * (GPL-3.0, https://github.com/recloudstream/cloudstream — master @ 2026-10).
 *
 * Live protocol capture 2026-10-10 (byselapuix.com / bysesukior.com,
 * links from the user's real pages; the "filemoon" button on VerAnimes
 * actually points here — the SPA title is "Byse Frontend"):
 *
 *   1. GET {base}/api/videos/{code} -> JSON metadata that embeds a
 *      "playback" envelope {algorithm, iv, payload, key_parts[], version};
 *   2. key_parts selection BY VERSION: indices [version, 31 - version]
 *      1-based, concatenated after base64url decode (upstream's two-part
 *      concat predates this scheme and fails on today's version 19 —
 *      reverse-engineered from the live bundle videoPagesBundle-*.js:
 *      `for (n = 1..20) map[n] = [n, 31 - n]`, valid pair only when both
 *      indices fall inside the array);
 *   3. AES-256-GCM (128-bit tag) decrypt of payload -> JSON
 *      {"sources":[{"url","mime_type",...}]};
 *   4. sources[0].url is a master.m3u8 (proven live: 1080p master,
 *      MEDIA_CHECK 200 without referer).
 *
 * The upstream details -> embed_frame_url -> /embed/playback hop is no
 * longer required: the playback envelope now travels inside the
 * single /api/videos/{code} response (live-proven both via the embed
 * domain n1mwq.org and directly on byselapuix.com).
 */
internal class ByseExtractor(private val http: CloudHttp) : CloudKitExtractor {

    override fun matches(url: String): Boolean = KNOWN_DOMAINS.any { url.contains(it) }

    /** Never throws; null on API change, dead link or decrypt failure. */
    override suspend fun resolve(url: String): CloudKitResult? {
        val base = baseUrl(url) ?: return null
        val code = codeOf(url) ?: return null

        val response = try {
            http.get("$base/api/videos/$code")
        } catch (t: IOException) {
            return null
        }
        val playback = PlaybackEnvelope.parse(response.body) ?: return null
        val key = selectKey(playback) ?: return null
        val plain = decrypt(playback, key) ?: return null
        val source = SourceJson.firstUrl(plain) ?: return null

        return CloudKitResult(
            url = source,
            kind = if (source.contains(".m3u8")) CloudKitKind.HLS else CloudKitKind.DIRECT,
            referer = null, // live MEDIA_CHECK: CDN serves the master without referer
            extractor = "Byse",
        )
    }

    internal data class PlaybackEnvelope(
        val iv: String,
        val payload: String,
        val keyParts: List<String>,
        val version: Int,
    ) {
        internal companion object {
            /**
             * Regex parse of the metadata JSON: only the playback envelope
             * matters, so a tolerant field scan beats a full JSON tree for
             * this module's zero-dependency budget.
             */
            internal fun parse(body: String): PlaybackEnvelope? {
                val algorithm = ALGORITHM_REGEX.find(body)?.groupValues?.get(1) ?: return null
                if (algorithm != "AES-256-GCM") return null // unknown scheme: typed degradation
                val iv = FIELD_REGEX.find(body)?.groupValues?.get(1) ?: return null
                val payload = PAYLOAD_REGEX.find(body)?.groupValues?.get(1) ?: return null
                val parts = KEY_PARTS_REGEX.find(body)?.groupValues?.get(1)
                    ?.split(',')?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() }
                    ?: return null
                if (parts.isEmpty()) return null
                val version = VERSION_REGEX.find(body)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return null
                return PlaybackEnvelope(iv, payload, parts, version)
            }

            private val ALGORITHM_REGEX = Regex(""""algorithm"\s*:\s*"([^"]+)"""")
            private val FIELD_REGEX = Regex(""""iv"\s*:\s*"([^"]+)"""")
            private val PAYLOAD_REGEX = Regex(""""payload"\s*:\s*"([^"]+)"""")
            private val KEY_PARTS_REGEX = Regex(""""key_parts"\s*:\s*\[(.*?)]""")
            private val VERSION_REGEX = Regex(""""version"\s*:\s*"?(\d+)"?""")
        }
    }

    /**
     * Live scheme (version <= 20): 1-based indices [version, 31 - version];
     * out-of-range pair falls back to the FULL concatenated parts (defensive:
     * a future version bump must degrade, not throw). Concatenated key must
     * be exactly 32 bytes for AES-256; anything else is a protocol change.
     */
    internal fun selectKey(playback: PlaybackEnvelope): ByteArray? {
        val selected = listOf(playback.version, 31 - playback.version)
            .filter { it in 1..playback.keyParts.size }
            .map { b64UrlDecode(playback.keyParts[it - 1]) }
        val key = (if (selected.size == 2) selected else playback.keyParts.map { b64UrlDecode(it) })
            .reduce { acc, bytes -> acc + bytes }
        return if (key.size == 32) key else null
    }

    /** AES-256-GCM decrypt with the standard 128-bit tag; null on any failure. */
    internal fun decrypt(playback: PlaybackEnvelope, key: ByteArray): String? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, b64UrlDecode(playback.iv)),
            )
            String(cipher.doFinal(b64UrlDecode(playback.payload)), Charsets.UTF_8)
        } catch (t: GeneralSecurityException) {
            null
        } catch (t: IllegalArgumentException) {
            null
        }
    }

    /** URL code = last non-empty path segment (/e/ and /d/ embeds alike). */
    internal fun codeOf(url: String): String? {
        val code = url.substringBefore('#').trimEnd('/').substringAfterLast('/')
        return code.ifEmpty { null }
    }

    internal fun baseUrl(url: String): String? {
        val scheme = Regex("^(https?)://").find(url)?.value ?: return null
        val host = url.removePrefix(scheme).substringBefore('/').ifEmpty { return null }
        return "$scheme$host"
    }

    internal companion object {
        // Upstream ByseSX subclasses + the two domains captured in the
        // user's real pages (2026-10-09/10).
        internal val KNOWN_DOMAINS: List<String> = listOf(
            "byse.sx", "bysezejataos.com", "bysebuho.com", "bysevepoin.com",
            "byseqekaho.com", "byselapuix.com", "bysesukior.com",
        )

        internal fun b64UrlDecode(value: String): ByteArray {
            val fixed = value.replace('-', '+').replace('_', '/')
            val pad = (4 - fixed.length % 4) % 4
            return Base64.getDecoder().decode(fixed + "=".repeat(pad))
        }
    }
}

/**
 * Decrypted playback JSON scanner: sources[0].url (the live capture carries
 * "url", not jwplayer's "file", so JwPlayerParser does not apply).
 */
internal object SourceJson {

    private val URL_REGEX = Regex(""""url"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val MIME_REGEX = Regex(""""mime_type"\s*:\s*"([^"]+)"""")

    internal fun firstUrl(decryptedJson: String): String? {
        val match = URL_REGEX.find(decryptedJson) ?: return null
        val raw = match.groupValues[1]
        // Live evidence: URLs arrive with \/ and \u0026 escapes.
        val unescaped = CloudJson.unescape(raw)
        return unescaped.ifEmpty { null }
    }

    internal fun firstMime(decryptedJson: String): String? =
        MIME_REGEX.find(decryptedJson)?.groupValues?.get(1)
}
