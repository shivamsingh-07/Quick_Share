package com.quickshare.tv.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wire-format tests for the EndpointInfo blob (mDNS TXT record `n`).
 *
 * Layout that must be preserved (matches `rqs_lib::utils::gen_mdns_endpoint_info`):
 *
 * ```
 *   byte 0       : (device_type << 1) & 0b111
 *   bytes 1..16  : 16 random salt bytes
 *   byte 17      : nameLen (UTF-8 byte count)
 *   bytes 18..N  : device-name UTF-8 bytes
 * ```
 */
class EndpointInfoCodecTest {

    @Test
    fun `roundtrip preserves device name + type`() {
        val original = EndpointInfo("Living Room TV", EndpointInfo.DeviceType.LAPTOP)
        val bytes = original.encode()
        val decoded = EndpointInfo.decode(bytes)!!
        assertEquals(original.deviceName, decoded.deviceName)
        assertEquals(original.deviceType, decoded.deviceType)
    }

    @Test
    fun `byte 0 encodes device_type shifted left by one`() {
        val phone   = EndpointInfo("p",  EndpointInfo.DeviceType.PHONE).encode()
        val tablet  = EndpointInfo("t",  EndpointInfo.DeviceType.TABLET).encode()
        val laptop  = EndpointInfo("l",  EndpointInfo.DeviceType.LAPTOP).encode()
        val unknown = EndpointInfo("u",  EndpointInfo.DeviceType.UNKNOWN).encode()
        assertEquals(0x02, phone[0].toInt() and 0xFF)   // 1 << 1
        assertEquals(0x04, tablet[0].toInt() and 0xFF)  // 2 << 1
        assertEquals(0x06, laptop[0].toInt() and 0xFF)  // 3 << 1
        assertEquals(0x00, unknown[0].toInt() and 0xFF) // 0 << 1
    }

    @Test
    fun `byte 17 carries name length and bytes 18+ carry the name`() {
        val name = "Living Room TV"
        val bytes = EndpointInfo(name, EndpointInfo.DeviceType.LAPTOP).encode()
        val nameLen = bytes[17].toInt() and 0xFF
        assertEquals(name.toByteArray(Charsets.UTF_8).size, nameLen)
        val decoded = String(bytes, 18, nameLen, Charsets.UTF_8)
        assertEquals(name, decoded)
    }

    @Test
    fun `salt occupies bytes 1 through 16 and is not all zero`() {
        val a = EndpointInfo("dev", EndpointInfo.DeviceType.LAPTOP).encode()
        val b = EndpointInfo("dev", EndpointInfo.DeviceType.LAPTOP).encode()
        val saltA = a.copyOfRange(1, 17)
        val saltB = b.copyOfRange(1, 17)
        // Two encodes of the same struct must produce different salts —
        // otherwise a downstream device-id derivation would collide.
        assertNotEquals(saltA.toList(), saltB.toList())
    }

    @Test
    fun `decode returns null for blobs shorter than the hidden endpoint prefix`() {
        assertNull(EndpointInfo.decode(ByteArray(0)))
        assertNull(EndpointInfo.decode(ByteArray(16)))
    }

    @Test
    fun `decode accepts hidden endpoint prefix without public name`() {
        val blob = ByteArray(17).apply {
            this[0] = hiddenFlags(EndpointInfo.DeviceType.PHONE)
            // bytes 1..16 are metadata/salt and are not interpreted by the decoder.
        }

        val decoded = EndpointInfo.decode(blob)!!
        assertEquals("", decoded.deviceName)
        assertEquals(EndpointInfo.DeviceType.PHONE, decoded.deviceType)
        assertNull(decoded.qrCodeData)
    }

    @Test
    fun `decode reads TLV records directly after hidden endpoint prefix`() {
        // Hidden QR endpoints omit the public-name length byte. That means
        // byte 17 is TLV type=1, byte 18 is TLV length, and the value starts
        // at byte 19. The codec must extract the value into qrCodeData
        // regardless of its semantic (16-byte advertising token, AES-GCM
        // hidden-name blob, etc. — the matcher decides what to do with it).
        val tlvValue = ByteArray(32) { (0x30 + it).toByte() }
        val blob = ByteArray(17 + 2 + tlvValue.size).apply {
            this[0] = hiddenFlags(EndpointInfo.DeviceType.PHONE)
            this[17] = 0x01
            this[18] = tlvValue.size.toByte()
            System.arraycopy(tlvValue, 0, this, 19, tlvValue.size)
        }

        val decoded = EndpointInfo.decode(blob)!!
        assertEquals("", decoded.deviceName)
        assertEquals(EndpointInfo.DeviceType.PHONE, decoded.deviceType)
        assertEquals(tlvValue.toList(), decoded.qrCodeData?.toList())
    }

    @Test
    fun `decode hidden endpoint skips unknown TLV before QR TLV`() {
        val token = ByteArray(16) { (0x70 + it).toByte() }
        val blob = ByteArray(17 + 3 + 2 + token.size).apply {
            this[0] = hiddenFlags(EndpointInfo.DeviceType.TABLET)
            var off = 17
            this[off++] = 0x02
            this[off++] = 0x01
            this[off++] = 0x01
            this[off++] = 0x01
            this[off++] = token.size.toByte()
            System.arraycopy(token, 0, this, off, token.size)
        }

        val decoded = EndpointInfo.decode(blob)!!
        assertEquals("", decoded.deviceName)
        assertEquals(EndpointInfo.DeviceType.TABLET, decoded.deviceType)
        assertEquals(token.toList(), decoded.qrCodeData?.toList())
    }

    @Test
    fun `decode captures TLV-1 QR-code data after the device name`() {
        // Construct a synthetic blob: prefix + name + a single TLV record of
        // type=1 carrying a 16-byte advertising-token-shaped value. This
        // mirrors what a phone in "visible" mode broadcasts after scanning
        // our QR code.
        val name = "Pixel 9".toByteArray(Charsets.UTF_8)
        val token = ByteArray(16) { it.toByte() }  // 0x00..0x0F
        val blob = ByteArray(18 + name.size + 2 + token.size).apply {
            this[0] = (3 shl 1).toByte()  // LAPTOP, just to vary it
            // bytes 1..16 left as zero salt — the decoder doesn't care
            this[17] = name.size.toByte()
            System.arraycopy(name, 0, this, 18, name.size)
            this[18 + name.size]     = 0x01  // TLV type
            this[18 + name.size + 1] = token.size.toByte()
            System.arraycopy(token, 0, this, 18 + name.size + 2, token.size)
        }

        val decoded = EndpointInfo.decode(blob)!!
        assertEquals("Pixel 9", decoded.deviceName)
        assertEquals(EndpointInfo.DeviceType.LAPTOP, decoded.deviceType)
        assertEquals(token.toList(), decoded.qrCodeData?.toList())
    }

    @Test
    fun `decode tolerates a truncated TLV value by stopping cleanly`() {
        val name = "x".toByteArray(Charsets.UTF_8)
        // Declare type=1, length=16 but only 4 bytes follow — must NOT throw,
        // and qrCodeData stays null because we never saw a complete record.
        val blob = ByteArray(18 + name.size + 2 + 4).apply {
            this[17] = name.size.toByte()
            System.arraycopy(name, 0, this, 18, name.size)
            this[18 + name.size]     = 0x01
            this[18 + name.size + 1] = 0x10  // claims 16 bytes
            // ...but only 4 bytes of value follow.
        }
        val decoded = EndpointInfo.decode(blob)!!
        assertEquals("x", decoded.deviceName)
        assertNull(decoded.qrCodeData)
    }

    @Test
    fun `decode skips unknown TLV types but still picks up TLV-1 after them`() {
        // type=2 (vendor) length=1 value=1, then type=1 (QR) length=16.
        val name = "n".toByteArray(Charsets.UTF_8)
        val token = ByteArray(16) { (0xA0 + it).toByte() }
        val blob = ByteArray(18 + name.size + 3 + 2 + token.size).apply {
            this[17] = name.size.toByte()
            System.arraycopy(name, 0, this, 18, name.size)
            var off = 18 + name.size
            this[off++] = 0x02
            this[off++] = 0x01
            this[off++] = 0x01  // vendor=Samsung
            this[off++] = 0x01
            this[off++] = token.size.toByte()
            System.arraycopy(token, 0, this, off, token.size)
        }
        val decoded = EndpointInfo.decode(blob)!!
        assertEquals(token.toList(), decoded.qrCodeData?.toList())
    }

    @Test
    fun `equals treats structurally-equal qrCodeData as equal`() {
        // Sanity check that the manual equals/hashCode override on the data
        // class actually compares ByteArray content (the default would use
        // reference equality and break DiscoveredPeer deduping).
        val a = EndpointInfo("dev", EndpointInfo.DeviceType.LAPTOP, ByteArray(16) { 7 })
        val b = EndpointInfo("dev", EndpointInfo.DeviceType.LAPTOP, ByteArray(16) { 7 })
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    private fun hiddenFlags(type: EndpointInfo.DeviceType): Byte =
        ((1 shl 4) or (type.code shl 1)).toByte()
}
