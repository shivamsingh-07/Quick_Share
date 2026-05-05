package com.quickshare.tv.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC-5869 HKDF with HMAC-SHA256.
 *
 * Used for:
 *  - UKEY2 next-protocol key derivation (D2DClientKey/D2DServerKey + their HMAC keys).
 *  - QR-code token derivation on the sender side: see [QrCodeKey] for the
 *    exact `info` strings required by stock Quick Share.
 */
object Hkdf {
    private const val HASH_LEN = 32 // SHA-256
    private const val ALG = "HmacSHA256"

    /**
     * HKDF-Extract: PRK = HMAC(salt, IKM).
     *
     * Per RFC-5869 §2.2, an *empty* salt is equivalent to a string of HashLen
     * zero bytes. We do that substitution here so callers (e.g. the Quick
     * Share QR-code derivation, which spec-mandates `salt=""`) don't have to
     * remember the rule — and so we don't blow up on JCE providers that
     * reject empty `SecretKeySpec` keys with `IllegalArgumentException`.
     */
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        val mac = Mac.getInstance(ALG)
        mac.init(SecretKeySpec(effectiveSalt, ALG))
        return mac.doFinal(ikm)
    }

    /** HKDF-Expand. */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length <= 255 * HASH_LEN) { "length too large for HKDF-Expand" }
        val mac = Mac.getInstance(ALG)
        mac.init(SecretKeySpec(prk, ALG))

        val n = (length + HASH_LEN - 1) / HASH_LEN
        val out = ByteArray(length)
        var prev = ByteArray(0)
        var written = 0
        for (i in 1..n) {
            mac.reset()
            mac.update(prev)
            mac.update(info)
            mac.update(byteArrayOf(i.toByte()))
            prev = mac.doFinal()
            val take = minOf(HASH_LEN, length - written)
            System.arraycopy(prev, 0, out, written, take)
            written += take
        }
        return out
    }

    /** Convenience: HKDF(salt, ikm, info, length). */
    fun derive(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, ikm), info, length)
}
