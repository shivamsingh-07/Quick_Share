package com.quickshare.tv.crypto

import kotlin.math.abs

/**
 * Nearby Connections "verification code" — the 4-digit PIN both peers display
 * after the UKEY2 handshake so the user can confirm they're talking to the
 * intended device out-of-band.
 *
 * Algorithm (matches NearDrop and the Nearby Connections reference):
 *
 * ```
 *   hash = 0
 *   multiplier = 1
 *   for byte b in authString:
 *       hash       = (hash + b * multiplier) mod 9973
 *       multiplier = (multiplier * 31)       mod 9973
 *   pin = sprintf("%04d", abs(hash))
 * ```
 *
 * 9973 is the largest prime below 10000, chosen so the output fits in 4 decimal
 * digits without modular bias on either end. Both peers MUST derive the same
 * authString from the UKEY2 transcript and so must produce the same PIN; if
 * they don't, an active MITM is in flight and the user should refuse.
 */
object PinCode {

    /** Returns a 4-digit, zero-padded decimal PIN. */
    fun fromAuthString(authString: ByteArray): String {
        var hash = 0
        var multiplier = 1
        for (byte in authString) {
            // Treat bytes as signed (matches the NearDrop reference exactly).
            hash = ((hash + byte.toInt() * multiplier) % MOD)
            multiplier = (multiplier * 31) % MOD
        }
        return "%04d".format(abs(hash))
    }

    private const val MOD = 9973
}
