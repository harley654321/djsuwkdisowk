package io.vdl.cloudkit.internal

import io.vdl.cloudkit.CloudKitKind
import io.vdl.cloudkit.CloudKitResult

import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * UPNShare extractor (uns.bio "vidstack" family).
 *
 * Upstream reference: recloudstream/cloudstream `extractors/VidStack.kt`
 * (GPL-3.0, https://github.com/recloudstream/cloudstream — master @ 2026-10;
 * GDMirrorbot routes "UpnShare" servers here).
 *
 * Live protocol capture 2026-10-10 (animeav1.uns.bio/#kjkatd, from the
 * user's real page):
 *
 *   1. the video id lives in the URL FRAGMENT after '#'
 *      (animeav1.uns.bio/#kjkatd -> kjkatd);
 *   2. GET {base}/api/v1/video?id={id} -> HEX-encoded ciphertext (5.6KB);
 *   3. AES-128-CBC decrypt, static key "kiemtienmua911ca", IV pair
 *      {"1234567890oiuytr", "0123456789abcdef"} — first that decrypts
 *      cleanly wins (live capture: the first IV);
 *   4. plaintext JSON {"source": ".../master.m3u8", ...} (live capture:
 *      https://94.131.217.183/v4/.../kjkatd/master.m3u8).
 *
 * Upstream builds the subtitle set too; this port only resolves the
 * playable source — subtitle plumbing belongs to the player, not the
 * download library.
 */
internal class UpnshareExtractor(private val http: CloudHttp) : CloudKitExtractor {

    override fun matches(url: String): Boolean = KNOWN_DOMAINS.any { url.contains(it) }

    /** Never throws; null on API change, dead link or decrypt failure. */
    override suspend fun resolve(url: String): CloudKitResult? {
        val base = baseUrl(url) ?: return null
        val id = url.substringAfterLast('#').substringBefore('/').ifEmpty { return null }

        val response = try {
            http.get("$base/api/v1/video?id=$id")
        } catch (t: IOException) {
            return null
        }
        val decrypted = IVS.firstNotNullOfOrNull { iv ->
            decryptAesCbc(response.body.trim(), KEY, iv)
        } ?: return null
        val source = SOURCE_REGEX.find(decrypted)?.groupValues?.get(1)
            ?.let(CloudJson::unescape)
            ?: return null

        return CloudKitResult(
            url = source,
            kind = if (source.contains(".m3u8")) CloudKitKind.HLS else CloudKitKind.DIRECT,
            referer = null, // live CDN serves the master without referer (IP-host CDN)
            extractor = "Upnshare",
        )
    }

    internal fun baseUrl(url: String): String? {
        val scheme = Regex("^(https?)://").find(url)?.value ?: return null
        val host = url.removePrefix(scheme).substringBefore('/').ifEmpty { return null }
        return "$scheme$host"
    }

    internal companion object {
        // Upstream Server1uns + the domain captured in the user's real
        // page (2026-10-09). Matching is substring-based like the rest
        // of the registry, so any uns.bio subdomain claims this extractor.
        internal val KNOWN_DOMAINS: List<String> = listOf(
            "uns.bio", "vidstack.io",
        )

        // Upstream constants; they are protocol-fixed, not secrets.
        private const val KEY = "kiemtienmua911ca"
        private val IVS = listOf("1234567890oiuytr", "0123456789abcdef")
        private val SOURCE_REGEX = Regex(""""source"\s*:\s*"((?:[^"\\]|\\.)*)"""")

        /** AES-128-CBC with PKCS#7 (JDK alias PKCS5) padding; null on failure. */
        internal fun decryptAesCbc(hexInput: String, key: String, iv: String): String? {
            val cipherBytes = try {
                hexToBytes(hexInput)
            } catch (t: NumberFormatException) {
                return null
            } catch (t: IllegalStateException) {
                return null // odd-length body: not a hex payload
            }
            if (cipherBytes.isEmpty() || cipherBytes.size % 16 != 0) return null
            return try {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
                    IvParameterSpec(iv.toByteArray(Charsets.UTF_8)),
                )
                String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
            } catch (t: GeneralSecurityException) {
                null
            } catch (t: IllegalArgumentException) {
                null
            }
        }

        internal fun hexToBytes(hex: String): ByteArray {
            check(hex.length % 2 == 0) { "hex string must have even length" }
            return ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }
}
