package com.quickshare.tv.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AesCbcTest {
    @Test
    fun `roundtrip encrypts and decrypts`() {
        val key = ByteArray(32) { it.toByte() }
        val iv  = AesCbc.randomIv()
        val pt  = "Hello, Quick Share 0123456789".toByteArray()

        val ct = AesCbc.encrypt(key, iv, pt)
        assertNotEquals(pt.toList(), ct.toList())

        val pt2 = AesCbc.decrypt(key, iv, ct)
        assertArrayEquals(pt, pt2)
    }

    @Test
    fun `different IVs yield different ciphertexts`() {
        val key = ByteArray(32) { 0x42 }
        val pt  = ByteArray(64) { 0x33 }
        val a = AesCbc.encrypt(key, AesCbc.randomIv(), pt)
        val b = AesCbc.encrypt(key, AesCbc.randomIv(), pt)
        assertNotEquals(a.toList(), b.toList())
    }
}
