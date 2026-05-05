package com.quickshare.tv.network

import com.quickshare.tv.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Tuple of an IPv4 address and the interface that owns it. */
private data class WifiPick(val address: Inet4Address, val iface: NetworkInterface)

/**
 * Picks an InetAddress JmDNS should bind its multicast socket to.
 *
 * Why we don't rely on `JmDNS.create()` (no-arg) on Android:
 *  - It iterates [NetworkInterface] under the hood and frequently picks the
 *    wrong interface — VPN tun0, the cellular rmnet, or even a bridge that
 *    can't see the WiFi LAN. Once chosen, JmDNS will silently never see the
 *    real Quick Share peers.
 *  - We want a deterministic, IPv4, multicast-capable, non-loopback address,
 *    matching the interface the user's phone is actually on.
 */
object WifiAddrPicker {
    private const val SCOPE = "WifiAddrPicker"

    /**
     * @return The first IPv4 address attached to a non-loopback, non-virtual,
     *         multicast-capable, "up" interface — or null if no such interface
     *         exists (Wi-Fi off, ethernet-only TVs, airplane mode, etc.).
     */
    fun pickPreferredIPv4(): InetAddress? = pickInternal()?.also {
        Log.d(SCOPE) { "Picked ${it.address.hostAddress} on ${it.iface.name}" }
    }?.address

    /**
     * The [NetworkInterface] that owns the address [pickPreferredIPv4]
     * returned. Used for scoping IPv6 link-local peer addresses (`fe80::...`)
     * before passing them to `Socket.connect`, which on Android requires the
     * scope ID or fails with `EINVAL`.
     *
     * Returns null in the same conditions [pickPreferredIPv4] returns null.
     */
    fun wifiInterface(): NetworkInterface? = pickInternal()?.iface

    /** Internal: returns both the IPv4 + its owning interface, picked once. */
    private fun pickInternal(): WifiPick? {
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return null
        for (iface in ifaces) {
            try {
                if (!iface.isUp) continue
                if (iface.isLoopback) continue
                if (iface.isVirtual) continue
                if (!iface.supportsMulticast()) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address &&
                        !addr.isLoopbackAddress &&
                        !addr.isLinkLocalAddress &&
                        !addr.isAnyLocalAddress
                    ) {
                        return WifiPick(addr, iface)
                    }
                }
            } catch (t: Throwable) {
                Log.v(SCOPE) { "Skipped iface ${iface.name}: ${t.message}" }
            }
        }
        return null
    }

    /**
     * Snapshot of every InetAddress bound to any local interface. Used by the
     * mDNS discoverer to suppress self-discovery: when our own advertiser
     * answers our own multicast query, the resolved host equals one of these.
     *
     * Returns an empty set if the network stack is in a transient state
     * (interfaces being torn down). Callers must treat that as "couldn't
     * decide" rather than "no local addresses".
     */
    fun localAddresses(): Set<InetAddress> {
        val out = HashSet<InetAddress>()
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return out
        for (iface in ifaces) {
            try {
                for (addr in iface.inetAddresses) out += addr
            } catch (_: Throwable) {
                // Interface mutated mid-iteration; ignore and keep what we have.
            }
        }
        return out
    }
}
