package com.quickshare.tv.network.ble

import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BleFastInitPayloadTest {

    @Test
    fun `dynamic payload preserves Android wake-up prefix and random tail`() {
        val payload = BleFastInitPayload.build(
            rng = FixedRandom(),
        )

        assertEquals(24, payload.size)
        assertArrayEquals(
            byteArrayOf(
                0xFC.toByte(), 0x12, 0x8E.toByte(), 0x01,
                0x42, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00,
            ),
            payload.copyOfRange(0, 14),
        )
        assertArrayEquals(
            byteArrayOf(
                0xA0.toByte(), 0xA1.toByte(), 0xA2.toByte(),
                0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte(),
                0xA6.toByte(), 0xA7.toByte(), 0xA8.toByte(),
                0xA9.toByte(),
            ),
            payload.copyOfRange(14, 24),
        )
    }

    private class FixedRandom : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            for (i in bytes.indices) {
                bytes[i] = (0xA0 + i).toByte()
            }
        }
    }
}
