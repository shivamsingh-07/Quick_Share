package com.quickshare.tv.crypto

import java.math.BigInteger
import java.security.interfaces.ECPublicKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for [EcKeys.parseGenericPublicKey].
 *
 * The previous implementation used the signed `BigInteger(byte[])` constructor,
 * which silently produced a negative magnitude (and therefore an off-curve
 * ECPoint, then ECDH failure) for any peer that sent a 32-byte coordinate
 * with the high bit set without a leading 0x00 sign byte.
 */
class EcKeysTest {

    @Test
    fun `serialize then parse round-trips a generated key pair`() {
        val pair = EcKeys.generateP256()
        val bytes = EcKeys.serializeGenericPublicKey(pair.public)
        val parsed = EcKeys.parseGenericPublicKey(bytes) as ECPublicKey
        val original = pair.public as ECPublicKey
        assertEquals(original.w.affineX, parsed.w.affineX)
        assertEquals(original.w.affineY, parsed.w.affineY)
    }

    @Test
    fun `ECDH succeeds across many random key pairs`() {
        // Run several round-trips so any peer key with a high-bit-set affine
        // coordinate will be exercised (≈50% probability per coordinate).
        repeat(16) {
            val a = EcKeys.generateP256()
            val b = EcKeys.generateP256()
            val aPubBytes = EcKeys.serializeGenericPublicKey(a.public)
            val bPubBytes = EcKeys.serializeGenericPublicKey(b.public)
            val aPubParsed = EcKeys.parseGenericPublicKey(aPubBytes)
            val bPubParsed = EcKeys.parseGenericPublicKey(bPubBytes)
            val s1 = EcKeys.ecdh(a.private, bPubParsed)
            val s2 = EcKeys.ecdh(b.private, aPubParsed)
            assertArrayEquals("ECDH must agree on every random key pair", s1, s2)
        }
    }

    @Test
    fun `BigInteger constructor variant matters for high-bit coordinates`() {
        // A 32-byte coordinate whose MSB is 0xFF is the canonical example.
        // BigInteger(byte[]) treats it as negative; BigInteger(1, byte[]) does
        // not. The test pins the contract our implementation depends on.
        val coord = ByteArray(32) { 0xFF.toByte() }
        val signed = BigInteger(coord)
        val unsigned = BigInteger(1, coord)
        assertEquals(-1, signed.signum())
        assertEquals(1, unsigned.signum())
    }
}
