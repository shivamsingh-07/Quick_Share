package com.quickshare.tv.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RFC 5869 test vector A.1 (HKDF-SHA256, basic).
 */
class HkdfTest {
    @Test
    fun `RFC 5869 A1`() {
        val ikm  = "0b".repeat(22).hex()
        val salt = "000102030405060708090a0b0c".hex()
        val info = "f0f1f2f3f4f5f6f7f8f9".hex()
        val expected =
            "3cb25f25faacd57a90434f64d0362f2a" +
            "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
            "34007208d5b887185865"
        val out = Hkdf.derive(salt, ikm, info, length = 42)
        assertArrayEquals(expected.hex(), out)
    }

    private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
