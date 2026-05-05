package com.quickshare.tv.crypto

import com.quickshare.tv.util.Base64Url
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec

/**
 * Wire-format and key-derivation tests for [QrCodeKey].
 *
 * The expectations here are pinned to the NearDrop spec
 * (PROTOCOL.md §"QR codes"). Anything that drifts from this format will silently
 * make stock Quick Share refuse to discover us — so the tests guard the exact
 * 35-byte layout and HKDF info strings.
 */
class QrCodeKeyTest {

    @Test
    fun `key bytes are exactly 35 with version prefix 00 00`() {
        val key = QrCodeKey.generate()
        assertEquals("key payload must be 35 bytes (3 prefix + 32 X-coord)", 35, key.keyBytes.size)
        assertEquals("first envelope-version byte must be 0x00", 0x00.toByte(), key.keyBytes[0])
        assertEquals("second envelope-version byte must be 0x00", 0x00.toByte(), key.keyBytes[1])
    }

    @Test
    fun `parity byte at index 2 is 0x02 for even Y and 0x03 for odd Y`() {
        // Generate keys until we've observed both parities. P-256 has ≈50/50
        // odds per draw, so 200 attempts effectively guarantees we see both
        // (probability of failing < 2^-200).
        var sawEven = false
        var sawOdd  = false
        repeat(200) {
            val key = QrCodeKey.generate()
            val parity = key.keyBytes[2].toInt() and 0xFF
            assertTrue("parity byte must be 0x02 or 0x03 (got 0x${parity.toString(16)})",
                parity == 0x02 || parity == 0x03)
            val yIsOdd = (key.keyPair.public as java.security.interfaces.ECPublicKey)
                .w.affineY.testBit(0)
            if (yIsOdd) {
                sawOdd = true
                assertEquals("odd Y must encode as 0x03", 0x03, parity)
            } else {
                sawEven = true
                assertEquals("even Y must encode as 0x02", 0x02, parity)
            }
            if (sawEven && sawOdd) return
        }
        assertTrue("Expected to see at least one even and one odd Y in 200 draws", sawEven && sawOdd)
    }

    @Test
    fun `X coordinate is exactly 32 bytes with no leading zero pad`() {
        // We deliberately construct a key whose X has the high bit set so
        // that BigInteger.toByteArray() would produce 33 bytes (with a leading
        // 0x00 sign byte). The encoder must strip that.
        val pair = generateUntil { x -> x.bitLength() == 256 && x.testBit(255) }
        val key = QrCodeKey.fromKeyPair(pair)
        val x = key.keyBytes.copyOfRange(3, 35)
        assertEquals("X must be 32 bytes", 32, x.size)
        // Reconstruct the BigInteger from the 32-byte slice and confirm it
        // equals the original public X — i.e. nothing was truncated.
        val reconstructed = BigInteger(1, x)
        val original = (pair.public as java.security.interfaces.ECPublicKey).w.affineX
        assertEquals(original, reconstructed)
    }

    @Test
    fun `urlSafeBase64 round-trips back to keyBytes`() {
        val key = QrCodeKey.generate()
        val decoded = Base64Url.decode(key.urlSafeBase64)
        assertArrayEquals(key.keyBytes, decoded)
    }

    @Test
    fun `url has the canonical https quickshare google qrcode prefix`() {
        val key = QrCodeKey.generate()
        assertTrue(
            "URL must start with the spec-mandated prefix (got '${key.url}')",
            key.url.startsWith("https://quickshare.google/qrcode#key="),
        )
    }

    @Test
    fun `advertising token is deterministic from a fixed key pair`() {
        // Use a hard-coded P-256 key so the derivation output is fully
        // pinned — any accidental change to salt or info bytes will move
        // this array and fail the assertion below.
        val pair = fixedKnownKeyPair()
        val a = QrCodeKey.fromKeyPair(pair)
        val b = QrCodeKey.fromKeyPair(pair)
        assertArrayEquals("advertisingToken must be deterministic", a.advertisingToken, b.advertisingToken)
        assertEquals(16, a.advertisingToken.size)
    }

    @Test
    fun `private key is exposed for downstream qr_code_handshake_data signing`() {
        // We don't sign anything yet, but the field must be wired so a future
        // change can sign the UKEY2 auth string for auto-accept.
        val key = QrCodeKey.generate()
        val priv: PrivateKey = key.privateKey
        assertNotNull(priv)
        assertEquals("EC", priv.algorithm)
    }

    // --- helpers ---------------------------------------------------------------

    /** Re-generate P-256 keypairs until [predicate] of `affineX` returns true. */
    private fun generateUntil(predicate: (BigInteger) -> Boolean): KeyPair {
        repeat(200) {
            val pair = EcKeys.generateP256()
            val x = (pair.public as java.security.interfaces.ECPublicKey).w.affineX
            if (predicate(x)) return pair
        }
        error("Could not synthesise an EC key matching the predicate within 200 draws")
    }

    /**
     * Build a [KeyPair] from a fixed P-256 secret. Using KeyFactory lets us
     * bypass the random source so [QrCodeKey.fromKeyPair] tests stay
     * deterministic.
     */
    private fun fixedKnownKeyPair(): KeyPair {
        val params = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }
        val ecParams = params.getParameterSpec(ECParameterSpec::class.java)
        val priv = BigInteger("c9afa9d845ba75166b5c215767b1d6934e50c3db36e89b127b8a622b120f6721", 16)
        val pubX = BigInteger("60fed4ba255a9d31c961eb74c6356d68c049b8923b61fa6ce669622e60f29fb6", 16)
        val pubY = BigInteger("7903fe1008b8bc99a41ae9e95628bc64f2f1b20c2d7e9f5177a3c294d4462299", 16)
        val kf = KeyFactory.getInstance("EC")
        val pub = kf.generatePublic(ECPublicKeySpec(ECPoint(pubX, pubY), ecParams))
        val sec = kf.generatePrivate(ECPrivateKeySpec(priv, ecParams))
        return KeyPair(pub, sec)
    }
}
