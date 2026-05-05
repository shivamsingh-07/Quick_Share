package com.quickshare.tv.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-CBC with PKCS#7 padding. The IV is *per message* and travels in the
 * SecureMessage header, never reused across messages with the same key.
 */
object AesCbc {
    private const val ALG = "AES/CBC/PKCS5Padding"
    private val rng = SecureRandom()

    fun randomIv(): ByteArray = ByteArray(16).also(rng::nextBytes)

    fun encrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 key must be 32 bytes" }
        require(iv.size == 16) { "AES IV must be 16 bytes" }
        val c = Cipher.getInstance(ALG)
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(plaintext)
    }

    fun decrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 key must be 32 bytes" }
        require(iv.size == 16) { "AES IV must be 16 bytes" }
        val c = Cipher.getInstance(ALG)
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(ciphertext)
    }
}
