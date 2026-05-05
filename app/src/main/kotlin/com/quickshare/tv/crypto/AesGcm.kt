package com.quickshare.tv.crypto

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128-GCM helpers used by the QR hidden-name TLV path.
 *
 * Keys are 16 bytes, nonces are 12 bytes, and tag length is 128 bits.
 * Only [decrypt] is called in production; [encrypt] is kept for
 * round-trip tests and future symmetry.
 */
object AesGcm {
    private const val ALG = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray): ByteArray {
        require(key.size == 16) { "AES-GCM QR key must be 16 bytes" }
        require(nonce.size == 12) { "AES-GCM nonce must be 12 bytes" }
        val cipher = Cipher.getInstance(ALG)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return cipher.doFinal(ciphertextAndTag)
    }

    fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == 16) { "AES-GCM QR key must be 16 bytes" }
        require(nonce.size == 12) { "AES-GCM nonce must be 12 bytes" }
        val cipher = Cipher.getInstance(ALG)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return cipher.doFinal(plaintext)
    }
}
