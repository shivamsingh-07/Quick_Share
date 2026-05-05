package com.quickshare.tv.network.ble

import java.security.SecureRandom

/**
 * Service-data payloads for Quick Share's FE2C "Fast Init" wake-up beacon.
 *
 * The bytes are intentionally treated as opaque: Android Quick Share's BLE
 * payload is undocumented, and these shapes come from observed compatible
 * implementations rather than a public spec.
 */
object BleFastInitPayload {

    const val LABEL = "dynamic"

    fun build(rng: SecureRandom = SecureRandom()): ByteArray {
        val random = ByteArray(RANDOM_BYTES).also(rng::nextBytes)
        return ByteArray(PREFIX.size + random.size).also { out ->
            PREFIX.copyInto(out)
            random.copyInto(out, destinationOffset = PREFIX.size)
        }
    }

    private const val RANDOM_BYTES = 10

    // Observed prefix bytes from NearDrop and compatible implementations.
    // The exact semantics of each byte are undocumented; offsets are 0-based
    // within the FE2C service-data field:
    //
    //   [0..1]  0xFC 0x12  — version / type marker (observed in all tested payloads)
    //   [2..3]  0x8E 0x01  — sub-type / flags (observed constant)
    //   [4]     0x42       — capability byte (observed constant)
    //   [5..13] 0x00 x9    — reserved / unknown (zeroed)
    //   [14..23]           — 10 random bytes (this implementation; randomized per-advertisement)
    private val PREFIX = byteArrayOf(
        0xFC.toByte(), 0x12, 0x8E.toByte(), 0x01,
        0x42, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00,
    )
}
