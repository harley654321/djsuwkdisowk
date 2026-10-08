package io.vdl.core.internal.hls

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128 segment decryption (RFC 8216: AES-128 CTR mode, 16-byte blocks).
 * JVM/Android: javax.crypto, no external dependency, API 21+.
 */
internal object Aes128 {

    /** AES-128/CTR/NoPadding, returns plaintext of identical length. */
    internal fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(key.size == 16) { "AES-128 key must be 16 bytes, got ${key.size}" }
        require(iv.size == 16) { "IV must be 16 bytes, got ${iv.size}" }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** Test/fallback path: encrypt with the same scheme. */
    internal fun encrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(key.size == 16 && iv.size == 16)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }
}
