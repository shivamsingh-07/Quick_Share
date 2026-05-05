package com.quickshare.tv.network.mdns

import android.content.Context
import com.quickshare.tv.util.Base64Url
import com.quickshare.tv.network.EndpointInfo
import com.quickshare.tv.network.ServiceNameCodec
import com.quickshare.tv.network.WifiAddrPicker
import com.quickshare.tv.system.MulticastLockHolder
import com.quickshare.tv.util.Log
import com.quickshare.tv.util.isUsefulDeviceName
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Discovers Quick Share peers via mDNS (JmDNS) and resolves each into a
 * [DiscoveredPeer].
 *
 * **Why we don't rely on JmDNS's `serviceResolved` callback alone.** JmDNS
 * fires `serviceResolved` every time *any* record (PTR/SRV/A/AAAA/TXT)
 * arrives for a service, often before the TXT record is even on the wire.
 * On real-world LANs the TXT response can also be lost outright (multicast
 * UDP, no retransmit). The Rust `mdns_sd` crate handles this by actively
 * issuing fresh queries via `info.get_property("n")` after the partial
 * resolve; JmDNS doesn't have an equivalent, so we drive the lookup
 * ourselves with [JmDNS.getServiceInfo] + timeout + retry.
 *
 * Per discovered service name we spawn **exactly one** resolve coroutine
 * (deduped via [ConcurrentHashMap.putIfAbsent]). It re-queries every
 * [RESOLVE_RETRY_DELAY_MS] until the TXT 'n' record materialises, the
 * service is removed, or the parent flow is cancelled — there is no
 * upper attempt cap; lifetime is owned by the caller (picker / QR
 * timeout / `stop()`).
 */
class MdnsDiscoverer(private val context: Context) {

    data class DiscoveredPeer(
        val endpointInfo: EndpointInfo,
        val host: InetAddress,
        val port: Int,
        val instanceName: String,
        /** The 4-byte endpoint ID extracted from the service instance name. */
        val endpointId: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = other is DiscoveredPeer &&
            host == other.host && port == other.port && instanceName == other.instanceName
        override fun hashCode(): Int =
            host.hashCode() * 31 * 31 + port * 31 + instanceName.hashCode()
    }

    sealed interface DiscoveryEvent {
        data class Found(val peer: DiscoveredPeer) : DiscoveryEvent
        data class Lost(val instanceName: String) : DiscoveryEvent
    }

    /**
     * Browse for Quick Share peers and emit each as it fully resolves.
     *
     * @param requireVisibleName when `true` (default), only peers whose
     * `EndpointInfo.deviceName` is a useful UTF-8 string are emitted. The
     * 17-byte "hidden" record stock Quick Share publishes in `Visible to
     * everyone` mode is **not** emitted as a placeholder; we keep polling
     * until the named refresh lands or the caller cancels.
     *
     * Set to `false` only when the consumer needs the raw TLV records of
     * a hidden peer (legacy QR-decryption flows). The current QR pipeline
     * uses the same `true` filter as the picker — it relies on the BLE
     * wake-up beacon to make the receiver re-publish its visible name.
     */
    fun observe(requireVisibleName: Boolean = true): Flow<DiscoveredPeer> =
        observeEvents(requireVisibleName).transform { event ->
            if (event is DiscoveryEvent.Found) emit(event.peer)
        }

    fun observeEvents(requireVisibleName: Boolean = true): Flow<DiscoveryEvent> = callbackFlow<DiscoveryEvent> {
        val multicast = MulticastLockHolder(context, "MdnsDisc")
        multicast.acquire()

        val bindAddr = WifiAddrPicker.pickPreferredIPv4()
        val browsers = buildList {
            if (bindAddr != null) {
                add("bound" to JmDNS.create(bindAddr, "Quick-Share-TV-disc"))
                // Some Android TV network stacks deliver PTR records to the
                // interface-bound JmDNS socket but never deliver the follow-up
                // SRV/TXT/A responses there. Keep an unbound browser alive as
                // a resolver fallback; duplicate Found events are harmless
                // because SendRepository dedups by service instance name.
                add("default" to JmDNS.create())
            } else {
                add("default" to JmDNS.create())
            }
        }

        val active = ConcurrentHashMap<String, Job>()
        val emitted = ConcurrentHashMap.newKeySet<String>()
        val producer = this  // capture for use inside listener callbacks

        fun activeKey(source: String, name: String) = "$source:$name"
        fun hasAnyResolverFor(name: String): Boolean =
            active.keys.any { it.substringAfter(':') == name }

        fun listenerFor(source: String, mdns: JmDNS) = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                val type = event.type
                val name = event.name
                // Dedup per browser. The bound + default browsers can both
                // resolve the same peer; downstream dedups by instance name.
                val newJob = producer.launch(
                    context = Dispatchers.IO,
                    start = CoroutineStart.LAZY,
                ) { resolveLoop(mdns, source, type, name, requireVisibleName, emitted::add) }
                val prev = active.putIfAbsent(activeKey(source, name), newJob)
                if (prev != null) {
                    newJob.cancel()
                } else {
                    newJob.start()
                }
            }
            override fun serviceRemoved(event: ServiceEvent) {
                active.remove(activeKey(source, event.name))?.cancel()
                if (!hasAnyResolverFor(event.name)) {
                    emitted.removeIf { it == event.name || it.startsWith("${event.name}:") }
                    trySend(DiscoveryEvent.Lost(event.name))
                }
            }
            override fun serviceResolved(event: ServiceEvent) {
                // Fast path: when JmDNS already has SRV/TXT/A, don't wait for
                // the polling loop's next getServiceInfo round. This matters
                // most for QR, where phones often publish a short-lived hidden
                // record immediately after scan.
                val info = event.info ?: return
                producer.launch(context = Dispatchers.IO) {
                    val peer = buildPeer(info, event.name, acceptIPv6 = false) ?: return@launch
                    emitResolvedPeer(
                        peer = peer,
                        source = source,
                        name = event.name,
                        requireVisibleName = requireVisibleName,
                        tryMarkEmitted = emitted::add,
                        origin = "callback",
                    )
                }
            }
        }
        val listeners = browsers.map { (source, mdns) ->
            val listener = listenerFor(source, mdns)
            mdns.addServiceListener(MdnsAdvertiser.SERVICE_TYPE, listener)
            mdns to listener
        }
        Log.i(
            SCOPE,
            "Discovery started (requireVisibleName=$requireVisibleName, " +
                "browsers=${browsers.joinToString { it.first }})",
        )

        awaitClose {
            listeners.forEach { (mdns, listener) ->
                runCatching { mdns.removeServiceListener(MdnsAdvertiser.SERVICE_TYPE, listener) }
            }
            for (job in active.values) job.cancel()
            active.clear()
            browsers.forEach { (_, mdns) -> runCatching { mdns.close() } }
            multicast.release()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Drive a single service to a fully-resolved state. Each iteration:
     *   1) calls [JmDNS.getServiceInfo] which issues a fresh DNS-SD query and
     *      waits up to [RESOLVE_TIMEOUT_MS] for PTR/SRV/A/TXT to come back,
     *   2) tries to parse the result into a [DiscoveredPeer], and
     *   3) on failure, sleeps [RESOLVE_RETRY_DELAY_MS] and tries again.
     *
     * Cancelled by [serviceRemoved] or by the parent flow being closed.
     */
    private suspend fun ProducerScope<DiscoveryEvent>.resolveLoop(
        mdns: JmDNS,
        source: String,
        type: String,
        name: String,
        requireVisibleName: Boolean,
        tryMarkEmitted: (String) -> Boolean,
    ) {
        var attempts = 0
        // No upper bound: the picker / QR caller controls lifetime by
        // cancelling the parent flow (selectDevice, useQrInstead, stop,
        // etc.). Phones routinely take 30-60s to refresh their hidden
        // record into a named one, so we keep polling until either we
        // emit a peer (and return) or the caller cancels.
        while (currentCoroutineContext().isActive) {
            attempts++
            // Synchronous resolve. Sends a query and blocks the coroutine
            // (we're on Dispatchers.IO so JmDNS's listener thread is free).
            //
            // rqs/mdns_sd can publish a very short-lived PTR first and fill
            // SRV/TXT/A in follow-up packets. On some Android TV firmwares
            // JmDNS reports `serviceAdded` for the PTR but `getServiceInfo`
            // returns null unless we explicitly ask it to keep a resolver
            // active for that instance.
            runCatching { mdns.requestServiceInfo(type, name, true) }
            val info = runCatching { mdns.getServiceInfo(type, name, true, RESOLVE_TIMEOUT_MS) }
                .getOrNull()
            if (info != null) {
                // For the first IPV4_PATIENCE_ATTEMPTS we *insist* on an IPv4
                // address — even if [info] already has an AAAA record, we
                // hold off because Quick Share is documented as IPv4-LAN
                // only and connecting to an IPv6 link-local address without
                // a scope ID returns EINVAL on Android. After the patience
                // window, we'll accept a scoped IPv6 address as last resort.
                val acceptIPv6 = attempts >= IPV4_PATIENCE_ATTEMPTS
                val peer = buildPeer(info, name, acceptIPv6 = acceptIPv6)
                if (peer != null) {
                    if (emitResolvedPeer(
                            peer = peer,
                            source = source,
                            name = name,
                            requireVisibleName = requireVisibleName,
                            tryMarkEmitted = tryMarkEmitted,
                            origin = "poll attempt $attempts",
                        )
                    ) {
                        return
                    }
                }
            }
            delay(RESOLVE_RETRY_DELAY_MS)
        }
    }


    private fun ProducerScope<DiscoveryEvent>.emitResolvedPeer(
        peer: DiscoveredPeer,
        source: String,
        name: String,
        requireVisibleName: Boolean,
        tryMarkEmitted: (String) -> Boolean,
        origin: String,
    ): Boolean {
        val hasVisibleName = peer.endpointInfo.deviceName.isUsefulDeviceName()
        val emissionKey = if (requireVisibleName) {
            name
        } else {
            "$name:${peer.endpointInfo.qrCodeData?.contentHashCode() ?: 0}"
        }
        return when {
            hasVisibleName -> {
                if (tryMarkEmitted(emissionKey)) {
                    Log.i(SCOPE, "Resolved '$name' via $source → ${describe(peer)} ($origin)")
                    trySend(DiscoveryEvent.Found(peer))
                }
                true
            }
            !requireVisibleName -> {
                // QR pipeline: needs TLV-1 from the hidden record as scan
                // evidence; the regular picker still holds hidden peers back.
                if (tryMarkEmitted(emissionKey)) {
                    Log.i(SCOPE, "Resolved '$name' via $source [hidden] → ${describe(peer)} ($origin)")
                    trySend(DiscoveryEvent.Found(peer))
                }
                true
            }
            else -> false
        }
    }

    /**
     * Compact one-line summary of a resolved peer for the log. We tag the
     * TLV-1 size so a QR-flow bug report makes it obvious whether the
     * phone published our 16-byte advertising token (only that case
     * matches in [com.quickshare.tv.crypto.QrPeerMatcher]).
     */
    private fun describe(peer: DiscoveredPeer): String {
        val ei = peer.endpointInfo
        val tlv = ei.qrCodeData
        val tlvDesc = when {
            tlv == null    -> "qrTlv=absent"
            tlv.size == 16 -> "qrTlv=token(16B)"
            else           -> "qrTlv=other(${tlv.size}B)"
        }
        return "${peer.host.hostAddress}:${peer.port} '${ei.deviceName}' " +
            "type=${ei.deviceType} $tlvDesc"
    }

    /**
     * Try to construct a peer from a (possibly partial) [ServiceInfo].
     * Returns null when the service isn't a Quick Share peer, doesn't yet
     * have the TXT 'n' record, or has no host address we're willing to
     * connect to ([acceptIPv6] gates whether the IPv6 fallback engages —
     * see [resolveLoop] for the patience strategy).
     */
    private fun buildPeer(
        info: ServiceInfo,
        name: String,
        acceptIPv6: Boolean,
    ): DiscoveredPeer? {
        // 1) Validate the 10-byte PCP service-name struct and pull the endpoint ID.
        val nameDecoded = ServiceNameCodec.decodeBase64(name)
            ?: return null

        // 2) The actual EndpointInfo lives in TXT 'n'. **Two encodings
        // exist in the wild** and we have to handle both:
        //
        //   - **Raw bytes (modern / spec-compliant).** Stock Quick Share
        //     on current Galaxy / Pixel builds, NearDrop, and rqs_lib all
        //     publish the EndpointInfo blob directly as the TXT value
        //     (binary TXT is fine per RFC 6763 §6.5). This is the layout
        //     `IMPLEMENTATION.md` and NearDrop's PROTOCOL.md document.
        //
        //   - **base64url-wrapped (legacy).** Earlier Galaxy builds (we
        //     last saw it on a 2024-era One UI) wrapped the same blob in
        //     base64url before publishing. Decoding raw → 0 valid bytes
        //     in that case.
        //
        // We try **raw first** (matches the spec, fastest path for
        // modern peers) then fall back to base64. Whichever produces a
        // valid `EndpointInfo` wins. If both fail we log diagnostics so
        // the next round of debugging starts with concrete bytes
        // instead of "Gave up resolving".
        val nBytes = readTxtPropertyCI(info, TXT_KEY_ENDPOINT_INFO) ?: return null
        val endpointInfo = decodeEndpointInfoFromTxt(nBytes, name) ?: return null

        // 3) Pick a host address.
        //
        // **Strict IPv4 preference.** Quick Share is documented (NearDrop
        // PROTOCOL.md, rqs_lib `mdns.rs`) as LAN-IPv4 only. We've also seen
        // in production logs that Android's `Socket.connect()` rejects an
        // unscoped IPv6 link-local with `EINVAL`, so even when JmDNS gives
        // us only an AAAA record we'd rather wait for the A record to land
        // (the resolveLoop will keep polling).
        //
        // **TXT 'IPv4' fallback.** Modern Quick Share also publishes the
        // device's IP as an ASCII string under the TXT key `IPv4`
        // (e.g. "192.168.29.224", 14 bytes for that example). We accept
        // it as a primary source whenever JmDNS hasn't surfaced an A
        // record on its own — observed in field tests on Wi-Fi networks
        // where the multicast A response is dropped but the TXT response
        // gets through. Without this fallback the resolveLoop spins for
        // 40 s and gives up even though we have everything we need.
        //
        // **Scoped-IPv6 fallback.** Once the patience window elapses
        // [acceptIPv6] flips to true: we accept an IPv6 address but, if
        // it's link-local, attach the Wi-Fi interface index so connect()
        // can route it. Without this Android returns EINVAL.
        val a4 = info.inet4Addresses.firstOrNull()
        val txt4 = if (a4 == null) parseTxtIPv4(info) else null
        val a6 = if (a4 == null && txt4 == null && acceptIPv6) {
            info.inet6Addresses.firstOrNull()?.let(::scopeIPv6IfNeeded)
        } else null

        val host = a4
            ?: txt4
            ?: a6
            ?: return null
        if (host.isLoopbackAddress) return null

        // 4) Suppress self-discovery. JmDNS happily echoes our own advertised
        //    service back to us; without this filter the Send screen will
        //    cheerfully connect to the same device's Receive socket and
        //    deadlock at the user-acceptance prompt.
        if (host in WifiAddrPicker.localAddresses()) return null

        return DiscoveredPeer(
            endpointInfo = endpointInfo,
            host = host,
            port = info.port,
            instanceName = name,
            endpointId = nameDecoded.endpointId,
        )
    }

    /**
     * If [addr] is a link-local IPv6 (`fe80::...`) without a scope, return a
     * copy with our Wi-Fi interface attached so `Socket.connect()` doesn't
     * fail with `EINVAL`. Non-link-local IPv6 (global, ULA) returns
     * unchanged. Returns null only when we have absolutely no Wi-Fi
     * interface to scope against.
     */
    private fun scopeIPv6IfNeeded(addr: Inet6Address): Inet6Address? {
        // Already scoped (rare with JmDNS, but cheap to short-circuit).
        if (addr.scopeId != 0 || addr.scopedInterface != null) return addr
        if (!addr.isLinkLocalAddress) return addr
        val iface = WifiAddrPicker.wifiInterface() ?: run {
            Log.w(SCOPE, "Can't scope ${addr.hostAddress} — no Wi-Fi interface available")
            return null
        }
        return runCatching {
            Inet6Address.getByAddress(addr.hostAddress, addr.address, iface)
        }.getOrNull()
    }

    /**
     * Try to recover an [EndpointInfo] from the TXT 'n' bytes, accepting
     * either the modern raw layout or the legacy base64url-wrapped
     * layout. Returns null only when *both* attempts fail; in that case
     * we emit a single `D`-level diagnostic with sizes + hex prefix so
     * a follow-up bug report has the data needed to add a third
     * encoding if Quick Share ever invents one.
     */
    private fun decodeEndpointInfoFromTxt(nBytes: ByteArray, name: String): EndpointInfo? {
        val asString = runCatching { String(nBytes, Charsets.US_ASCII) }.getOrNull()
        if (asString != null && asString.isLikelyBase64Url()) {
            val decoded = runCatching { Base64Url.decode(asString) }.getOrNull()
            if (decoded != null) {
                runCatching { EndpointInfo.decode(decoded) }.getOrNull()?.let { ei ->
                    return ei
                }
            }
        }

        runCatching { EndpointInfo.decode(nBytes) }.getOrNull()?.let { ei ->
            return ei
        }
        // Fallback: maybe a legacy peer wrapped it in base64url but the text
        // was unusual enough that [isLikelyBase64Url] didn't classify it.
        if (asString != null) {
            val decoded = runCatching { Base64Url.decode(asString) }.getOrNull()
            if (decoded != null) {
                runCatching { EndpointInfo.decode(decoded) }.getOrNull()?.let { ei ->
                    return ei
                }
            }
        }
        Log.w(
            SCOPE,
            "EndpointInfo for '$name' did NOT decode as raw OR base64url " +
                "(size=${nBytes.size}B head=${nBytes.toHexPrefix(16)})",
        )
        return null
    }

    /**
     * Some peers publish TXT `n` as URL-safe base64 text; others publish the
     * raw binary EndpointInfo. A base64 string is also just bytes, and without
     * this guard our raw parser can accidentally treat ASCII like `zfi1...`
     * as a valid-but-garbage visible endpoint name. Prefer base64 decoding
     * whenever the TXT value has the unmistakable URL-safe alphabet shape.
     */
    private fun String.isLikelyBase64Url(): Boolean =
        isNotEmpty() &&
            all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' } &&
            length % 4 != 1

    private fun ByteArray.toHexPrefix(n: Int): String {
        val take = minOf(n, size)
        val sb = StringBuilder(take * 2)
        for (i in 0 until take) {
            val v = this[i].toInt() and 0xFF
            sb.append(HEX_CHARS[v ushr 4])
            sb.append(HEX_CHARS[v and 0x0F])
        }
        if (take < size) sb.append("...(+").append(size - take).append("B)")
        return sb.toString()
    }

    /**
     * Parse the TXT key `IPv4` (an ASCII string, e.g. `"192.168.29.224"`)
     * into an [InetAddress]. Stock Quick Share publishes this alongside
     * the regular A record so peers can route around dropped multicast A
     * responses — and on real-world Wi-Fi we've seen the A response get
     * lost while the TXT response makes it through.
     *
     * Returns `null` for absent / blank / malformed values; we *want* to
     * keep polling in that case (the next round-trip might give us a
     * usable A record) rather than wedging on a bad string.
     *
     * # JmDNS caveats
     *
     * JmDNS internally folds TXT property keys to lower-case for
     * storage in its `Map<String, byte[]>`, then exposes them via
     * [ServiceInfo.getPropertyNames] *as-stored* (lower-case). The
     * documented [ServiceInfo.getPropertyBytes] lookup is case-
     * sensitive against that lower-case map — so calling
     * `getPropertyBytes("IPv4")` against a record published as
     * `IPv4=192.168.29.224` returns `null` even though the key is
     * present. We work around this by enumerating the property names
     * and matching case-insensitively. (Stock GMS uses mixed case
     * `IPv4`; macOS NearDrop uses lower-case `ipv4`; rqs uses lower-
     * case `ipv4`; we accept all of them.)
     *
     * Note: we don't accept IPv6 over a TXT 'IPv6' key today. Quick Share
     * doesn't publish one, and the link-local-scoping work in
     * [scopeIPv6IfNeeded] would need to be ported here verbatim if it
     * ever does.
     */
    private fun parseTxtIPv4(info: ServiceInfo): InetAddress? {
        val raw = readTxtPropertyCI(info, TXT_KEY_IPV4)
            ?: return null
        val asString = runCatching { String(raw, Charsets.US_ASCII).trim() }.getOrNull()
        if (asString.isNullOrEmpty()) return null
        val resolved = runCatching { InetAddress.getAllByName(asString).firstOrNull() }
            .getOrNull()
            ?: return null
        if (resolved is Inet6Address || resolved.isLoopbackAddress || resolved.isAnyLocalAddress) return null
        return resolved
    }

    /**
     * Case-insensitive TXT-property fetch. Iterates [ServiceInfo.getPropertyNames]
     * and pulls the first key that matches [target] regardless of
     * capitalisation. See [parseTxtIPv4] for the JmDNS storage caveat
     * that motivates this.
     */
    private fun readTxtPropertyCI(info: ServiceInfo, target: String): ByteArray? {
        val names = info.propertyNames ?: return null
        while (names.hasMoreElements()) {
            val key = names.nextElement()?.toString() ?: continue
            if (key.equals(target, ignoreCase = true)) {
                return info.getPropertyBytes(key)
            }
        }
        return null
    }

    companion object {
        private const val SCOPE = "MdnsDisc"

        /**
         * TXT key carrying the EndpointInfo blob — see
         * [decodeEndpointInfoFromTxt] for the two encodings we accept
         * (raw and base64url).
         */
        private const val TXT_KEY_ENDPOINT_INFO = "n"

        /**
         * TXT key carrying the device's IPv4 address as an ASCII string
         * (e.g. `"192.168.29.224"`). Published by stock Quick Share
         * alongside the regular DNS A record as a routing fallback for
         * networks where multicast A responses get filtered.
         */
        private const val TXT_KEY_IPV4 = "IPv4"

        private val HEX_CHARS = "0123456789abcdef".toCharArray()

        // Each `getServiceInfo` issues a fresh query and waits this long for
        // the PTR/SRV/A/TXT response. JmDNS will return whatever ServiceInfo
        // it has (possibly partial) when the timeout fires.
        private const val RESOLVE_TIMEOUT_MS = 1500L

        // How long to sleep between resolve attempts when we got a partial
        // ServiceInfo. The loop runs until it emits a peer or the caller
        // cancels, so this only governs polling cadence.
        private const val RESOLVE_RETRY_DELAY_MS = 500L

        /**
         * Number of resolve attempts we'll wait for an IPv4 A record to
         * arrive before falling back to a (scoped) IPv6 address. With
         * RESOLVE_TIMEOUT_MS=1.5 s + RESOLVE_RETRY_DELAY_MS=0.5 s, this is
         * roughly ~10 s of patience. In real-world dual-stack LANs the A
         * record almost always arrives within the first 2-3 polls; this
         * margin only kicks in on weird IPv4-less networks.
         */
        private const val IPV4_PATIENCE_ATTEMPTS = 5
    }
}
