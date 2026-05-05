package com.quickshare.tv.network.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [BleCorrelator]. Uses an injectable clock so pruning and
 * freshness math do not depend on [android.os.SystemClock] behaviour in unit
 * tests.
 */
class BleCorrelatorTest {

    private class MutableClock(var value: Long)

    @Test
    fun `snapshot is empty until first NearbySharing record`() {
        val clock = MutableClock(1_000L)
        val c = BleCorrelator(windowMs = 10_000L, nowMs = { clock.value })

        assertTrue(c.snapshot().isEmpty)
        assertFalse(c.hasRecentEvidence())

        c.record(BleListener.Event.Unavailable(BleListener.Event.Reason.BLUETOOTH_OFF))
        assertTrue(c.snapshot().isEmpty)

        c.record(BleListener.Event.NearbySharing("aa:bb", -40, "deadbeef"))
        assertFalse(c.snapshot().isEmpty)
        assertTrue(c.hasRecentEvidence())
        assertEquals(1, c.snapshot().beacons.size)
        assertEquals("aa:bb", c.snapshot().beacons.first().deviceAddress)
        assertEquals("deadbeef", c.snapshot().beacons.first().fingerprintHex)
    }

    @Test
    fun `clear removes all beacons`() {
        val clock = MutableClock(5_000L)
        val c = BleCorrelator(windowMs = 30_000L, nowMs = { clock.value })
        c.record(BleListener.Event.NearbySharing("11:22", -50, null))
        assertTrue(c.hasRecentEvidence())
        c.clear()
        assertFalse(c.hasRecentEvidence())
    }

    @Test
    fun `beacons older than window are pruned on snapshot`() {
        val clock = MutableClock(100L)
        val window = 1_000L
        val c = BleCorrelator(windowMs = window, nowMs = { clock.value })

        c.record(BleListener.Event.NearbySharing("aa", -40, "aa"))
        assertEquals(1, c.snapshot().beacons.size)

        // Elapsed time inside window — still present.
        clock.value = 100 + window
        assertEquals(1, c.snapshot().beacons.size)

        // One ms past end of window — pruned (seenAt 100 < cutoff 101).
        clock.value = 100 + window + 1
        assertTrue(c.snapshot().isEmpty)
        assertFalse(c.hasRecentEvidence())
    }

    @Test
    fun `distinct address or fingerprint creates separate entries`() {
        val clock = MutableClock(0L)
        val c = BleCorrelator(windowMs = 60_000L, nowMs = { clock.value })

        c.record(BleListener.Event.NearbySharing("a", -40, "fp1"))
        c.record(BleListener.Event.NearbySharing("b", -41, "fp1"))
        assertEquals(2, c.snapshot().beacons.size)

        // Same composite key updates in place (latest RSSI wins).
        c.record(BleListener.Event.NearbySharing("a", -99, "fp1"))
        assertEquals(2, c.snapshot().beacons.size)
        assertEquals(-99, c.snapshot().beacons.find { it.deviceAddress == "a" }!!.rssiDbm)
    }

    @Test
    fun `describe matches snapshot content when non-empty`() {
        val clock = MutableClock(10_000L)
        val c = BleCorrelator(windowMs = 30_000L, nowMs = { clock.value })
        c.record(BleListener.Event.NearbySharing("cc:dd", -55, "abc"))
        clock.value = 10_500L
        val desc = c.describe()
        assertTrue(desc.contains("1 BLE beacon"))
        assertTrue(desc.contains("cc:dd"))
        assertTrue(desc.contains("fp=abc"))
        assertTrue(desc.contains("500ms ago"))
    }
}
