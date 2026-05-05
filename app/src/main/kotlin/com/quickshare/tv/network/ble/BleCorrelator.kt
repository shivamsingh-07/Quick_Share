package com.quickshare.tv.network.ble

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * Time-windowed cache of recent FE2C BLE beacons. Lets the send pipeline
 * corroborate mDNS resolutions against "yes, *something* nearby is
 * actively trying to share **right now**" before connecting.
 *
 * # Why we need this on top of mDNS
 *
 *  - Stock Quick Share keeps its mDNS service registered even when the
 *    user has dismissed the share sheet. The picker can resolve a peer
 *    that is no longer interested, time out at "Awaiting confirmation",
 *    and frustrate the user. A fresh BLE beacon from that peer in the
 *    last few seconds is a strong signal that the share sheet *is*
 *    open right now.
 *  - Conversely, an mDNS row with no matching BLE beacon in the recent
 *    window is more likely to be a stale advert from a sleeping device.
 *
 * # Why a separate class
 *
 *  - Send pipelines (picker + QR) want to consume BLE events without
 *    duplicating buffer/window logic.
 *  - Receive pipelines already use BLE for the wake-up nudge; if we
 *    later want to surface "someone tried to share" on the receiver UI,
 *    the same correlator drops in.
 *
 * The correlator is intentionally MAC-and-fingerprint based, not
 * service-data-byte interpreted: stock Android Quick Share's payload
 * format is undocumented, but the *presence* of a fresh FE2C beacon is
 * already a useful signal independent of payload semantics.
 */
class BleCorrelator(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    /**
     * One observed beacon. We keep the most recent emission per
     * (deviceAddress, fingerprintHex) pair, since the same broadcaster
     * can rotate either field across the session.
     */
    data class Beacon(
        val deviceAddress: String?,
        val fingerprintHex: String?,
        val rssiDbm: Int,
        val seenAtMs: Long,
    )

    /**
     * Snapshot returned by [snapshot]. Lightweight to share with
     * loggers / UI without exposing the underlying mutable map.
     *
     * [newestSeenAtMs] is in [SystemClock.elapsedRealtime] units —
     * the same clock used by [nowMs] — so age can be computed as
     * `nowMs() - newestSeenAtMs`. Do not subtract [System.currentTimeMillis]
     * from it; the two clocks are not the same epoch.
     */
    data class Snapshot(
        val beacons: List<Beacon>,
        val newestSeenAtMs: Long?,
    ) {
        val isEmpty: Boolean get() = beacons.isEmpty()
    }

    // Composite key keeps two distinct broadcasters separate even if one
    // randomises its MAC and the other doesn't expose service-data; the
    // window prune in [snapshot] keeps memory bounded.
    private val recent = ConcurrentHashMap<String, Beacon>()

    /**
     * Record a fresh FE2C beacon. Safe to call from BLE callback threads;
     * the underlying map is concurrent.
     */
    fun record(event: BleListener.Event) {
        if (event !is BleListener.Event.NearbySharing) return
        val now = nowMs()
        val key = correlatorKey(event.deviceAddress, event.fingerprintHex)
        recent[key] = Beacon(
            deviceAddress = event.deviceAddress,
            fingerprintHex = event.fingerprintHex,
            rssiDbm = event.rssi,
            seenAtMs = now,
        )
        if (recent.size > MAX_TRACKED) prune(now)
    }

    /**
     * Return the BLE beacons seen within the last [windowMs] ms.
     * Pruning happens lazily on read to avoid scheduled-cleanup
     * coroutines.
     */
    fun snapshot(): Snapshot {
        val now = nowMs()
        prune(now)
        val live = recent.values.toList()
        if (live.isEmpty()) return Snapshot(emptyList(), null)
        return Snapshot(
            beacons = live.sortedByDescending { it.seenAtMs },
            newestSeenAtMs = live.maxOf { it.seenAtMs },
        )
    }

    /**
     * `true` when at least one BLE beacon arrived inside the
     * configured window. Convenience for log lines that just want a
     * yes/no signal.
     */
    fun hasRecentEvidence(): Boolean = snapshot().beacons.isNotEmpty()

    /** Drop everything (e.g. on session teardown). */
    fun clear() {
        recent.clear()
    }

    private fun prune(now: Long) {
        val cutoff = now - windowMs
        val it = recent.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.seenAtMs < cutoff) it.remove()
        }
    }

    private fun correlatorKey(addr: String?, fp: String?): String =
        "${addr ?: "?"}|${fp ?: "?"}"

    /**
     * Format the snapshot for a one-line diagnostic so callers don't
     * each reinvent the prefix. Returns "no recent beacons" when empty.
     */
    fun describe(snapshot: Snapshot = snapshot()): String {
        if (snapshot.isEmpty) return "no recent BLE beacons"
        val freshest = snapshot.beacons.first()
        val ageMs = (nowMs() - freshest.seenAtMs).coerceAtLeast(0L)
        val fp = freshest.fingerprintHex?.let { " fp=$it" } ?: ""
        return "${snapshot.beacons.size} BLE beacon(s) in last ${windowMs / 1000}s, " +
            "freshest ${ageMs}ms ago from ${freshest.deviceAddress ?: "?"}" +
            "$fp rssi=${freshest.rssiDbm}dBm"
    }

    companion object {
        private const val DEFAULT_WINDOW_MS = 30_000L
        private const val MAX_TRACKED = 32
    }
}
