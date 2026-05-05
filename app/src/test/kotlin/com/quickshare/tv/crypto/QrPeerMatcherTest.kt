package com.quickshare.tv.crypto

import com.quickshare.tv.network.EndpointInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [QrPeerMatcher]. Exact matching remains narrow — only the
 * byte-for-byte equality of TLV-1 with our 16-byte advertising token counts
 * as a strong match. QR mode can still use TLV-1 presence as scan evidence
 * for stock builds that publish longer hidden records after scan.
 */
class QrPeerMatcherTest {

    @Test
    fun `peer carrying our 16-byte advertising token matches`() {
        val key = QrCodeKey.generate()
        val matcher = QrPeerMatcher(key)
        val info = EndpointInfo(
            deviceName = "Pixel 9",
            deviceType = EndpointInfo.DeviceType.PHONE,
            qrCodeData = key.advertisingToken,
        )
        assertTrue(matcher.matches(info))
    }

    @Test
    fun `peer with no TLV-1 does not match`() {
        val key = QrCodeKey.generate()
        val matcher = QrPeerMatcher(key)
        val info = EndpointInfo("Random Phone", EndpointInfo.DeviceType.PHONE, qrCodeData = null)
        assertFalse(matcher.matches(info))
    }

    @Test
    fun `peer carrying someone else's 16-byte token does not match`() {
        val key = QrCodeKey.generate()
        val matcher = QrPeerMatcher(key)
        // Same length as a real token, different bytes.
        val foreign = ByteArray(16) { 0x42 }
        val info = EndpointInfo("Other Phone", EndpointInfo.DeviceType.PHONE, foreign)
        assertFalse(matcher.matches(info))
    }

    @Test
    fun `peer carrying a TLV-1 of any other length does not match`() {
        val key = QrCodeKey.generate()
        val matcher = QrPeerMatcher(key)
        // Hidden peers historically put a 12-byte IV + ciphertext + 16-byte tag
        // here. We treat that as "not our QR scanner" and let the regular mDNS
        // visible-name path resolve it (or hold it back if it never goes
        // visible). Same goes for any other unexpected length.
        val notAToken = ByteArray(41) { it.toByte() }
        val info = EndpointInfo("Hidden Phone", EndpointInfo.DeviceType.PHONE, notAToken)
        assertFalse(matcher.matches(info))
        assertFalse(matcher.hasVerifiedQrEvidence(info))
        assertTrue(matcher.hasQrEvidence(info))
    }

    @Test
    fun `peer carrying encrypted hidden QR name verifies as QR evidence`() {
        val key = QrCodeKey.generate()
        val matcher = QrPeerMatcher(key)
        val nonce = ByteArray(12) { (0x10 + it).toByte() }
        val encrypted = AesGcm.encrypt(
            key = key.nameEncryptionKey,
            nonce = nonce,
            plaintext = "Pixel 9".toByteArray(Charsets.UTF_8),
        )
        val info = EndpointInfo(
            deviceName = "",
            deviceType = EndpointInfo.DeviceType.PHONE,
            qrCodeData = nonce + encrypted,
        )

        assertFalse(matcher.matches(info))
        assertTrue(matcher.hasVerifiedQrEvidence(info))
        assertEquals("Pixel 9", matcher.decryptHiddenName(info))
    }

    @Test
    fun `peer carrying encrypted hidden QR name with one-byte version prefix decrypts`() {
        // Some OEM firmwares prepend a single 0x00 / 0x01 byte ahead of the
        // 12-byte nonce. The canonical-offset path will fail GCM tag check
        // (because the wrong 12 bytes were treated as the nonce); the
        // fallback path retries at offset 1 and recovers the device name.
        val key = QrCodeKey.generate()
        val matcher = QrPeerMatcher(key)
        val nonce = ByteArray(12) { (0x20 + it).toByte() }
        val encrypted = AesGcm.encrypt(
            key = key.nameEncryptionKey,
            nonce = nonce,
            plaintext = "Galaxy S24".toByteArray(Charsets.UTF_8),
        )
        val tlv = byteArrayOf(0x01) + nonce + encrypted
        val info = EndpointInfo(
            deviceName = "",
            deviceType = EndpointInfo.DeviceType.PHONE,
            qrCodeData = tlv,
        )

        assertFalse(matcher.matches(info))
        assertTrue(matcher.hasVerifiedQrEvidence(info))
        assertEquals("Galaxy S24", matcher.decryptHiddenName(info))
    }

    @Test
    fun `unrelated 41 byte TLV does not decrypt and stays unverified`() {
        // Random 41-byte blob that doesn't belong to our QR session — must
        // NOT decrypt and must not be reported as verified evidence.
        val key = QrCodeKey.generate()
        val matcher = QrPeerMatcher(key)
        val random = ByteArray(41) { (0xA0 + it).toByte() }
        val info = EndpointInfo("", EndpointInfo.DeviceType.PHONE, qrCodeData = random)

        assertFalse(matcher.matches(info))
        assertFalse(matcher.hasVerifiedQrEvidence(info))
        assertTrue(matcher.hasQrEvidence(info))
    }

    @Test
    fun `peer carrying a TLV-1 with the right bytes but wrong length does not match`() {
        val key = QrCodeKey.generate()
        val matcher = QrPeerMatcher(key)
        // Truncate the token by one byte — must not match (defensive).
        val truncated = key.advertisingToken.copyOf(15)
        val info = EndpointInfo("Phone", EndpointInfo.DeviceType.PHONE, truncated)
        assertFalse(matcher.matches(info))
    }

    @Test
    fun `matcher is independent of deviceName and deviceType`() {
        val key = QrCodeKey.generate()
        val matcher = QrPeerMatcher(key)
        // Anonymous peer (no public name) but with the right token still matches.
        val info = EndpointInfo("", EndpointInfo.DeviceType.UNKNOWN, key.advertisingToken)
        assertTrue(matcher.matches(info))
    }
}
