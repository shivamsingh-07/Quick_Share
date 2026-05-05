package com.quickshare.tv.network.mdns

import android.content.Context
import com.quickshare.tv.domain.model.LocalEndpoint
import com.quickshare.tv.util.Base64Url
import com.quickshare.tv.network.ServiceNameCodec
import com.quickshare.tv.network.WifiAddrPicker
import com.quickshare.tv.system.MulticastLockHolder
import com.quickshare.tv.util.Log
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Advertises a Quick Share-compatible mDNS service via JmDNS.
 *
 * Why JmDNS instead of [android.net.nsd.NsdManager]:
 *  - Several TV SoCs (older Mediatek/Amlogic) ship a buggy mDNSResponder that
 *    returns ERROR_RETRY_LATER under load and silently drops TXT records.
 *  - JmDNS owns its own multicast socket so we control retries, timeouts,
 *    and TXT decoding end-to-end — and the implementation is identical
 *    across every Android version we support.
 *
 * Wire format (see `IMPLEMENTATION.md` and
 * [NearDrop PROTOCOL.md](https://github.com/grishka/NearDrop/blob/master/PROTOCOL.md)):
 *   Service type  : `_FC9F5ED42C8A._tcp.local.`   (JmDNS adds the `.local.`)
 *   Service name  : base64url(10-byte PCP struct from [ServiceNameCodec])
 *                     = PCP(0x23) + 4-byte endpointId + SHA256("NearbySharing")[:3] + 0x00 0x00
 *   TXT records   : `n` = base64url(EndpointInfo blob)
 *
 * We still hold a [MulticastLockHolder] for the lifetime of the advertisement
 * so the WiFi chipset doesn't drop multicast packets during deep idle.
 */
class MdnsAdvertiser(private val context: Context) {

    private val multicast = MulticastLockHolder(context, "MdnsAdv")
    private var jmdns: JmDNS? = null
    private var registered: ServiceInfo? = null

    /**
     * Register the service. Suspends until JmDNS confirms registration.
     * Up to [MAX_RETRIES] retries on transient failures, with back-off.
     */
    suspend fun start(port: Int, local: LocalEndpoint): String {
        multicast.acquire()
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt < MAX_RETRIES) {
            attempt++
            try {
                return registerOnce(port, local)
            } catch (t: Throwable) {
                lastError = t
                Log.w(SCOPE, "register attempt $attempt failed: ${t.message}")
                delay(BACKOFF_MS * attempt)
            }
        }
        multicast.release()
        throw IllegalStateException("mDNS register failed after $MAX_RETRIES attempts", lastError)
    }

    fun stop() {
        registered?.let { si ->
            runCatching { jmdns?.unregisterService(si) }
                .onFailure { Log.w(SCOPE, "unregisterService threw", it) }
        }
        registered = null
        runCatching { jmdns?.close() }
            .onFailure { Log.w(SCOPE, "JmDNS.close threw", it) }
        jmdns = null
        multicast.release()
    }

    /**
     * Force JmDNS to re-announce the registered service. Equivalent to
     * mdns-sd's `register_resend()` used by `rqs_lib::hdl::mdns` when its
     * BLE listener sees a nearby Quick Share peer scanning.
     *
     * Why this matters: Android sometimes only sees an mDNS service if
     * it was registered *after* Android started its discovery query.
     * Re-running [JmDNS.registerService] on the same descriptor causes
     * JmDNS to send a fresh PTR/SRV/TXT announcement, so a phone whose
     * scanner started late still sees us. JmDNS deduplicates the
     * registration internally so this is safe to call repeatedly.
     */
    fun reRegister() {
        val mdns = jmdns ?: return
        val service = registered ?: return
        runCatching {
            mdns.unregisterService(service)
            mdns.registerService(service)
            Log.i(SCOPE, "Re-announced ${service.qualifiedName} (BLE wake-up)")
        }.onFailure { Log.w(SCOPE, "reRegister failed", it) }
    }

    private suspend fun registerOnce(port: Int, local: LocalEndpoint): String =
        withContext(Dispatchers.IO) {
            val bindAddr = WifiAddrPicker.pickPreferredIPv4()
                ?: throw IllegalStateException("No usable IPv4 interface found for mDNS bind")

            val instanceName = ServiceNameCodec.encodeBase64(local.endpointId)
            val nProperty    = Base64Url.encode(local.endpointInfo)

            val mdns = JmDNS.create(bindAddr, "Quick-Share-TV")
            // JmDNS service type MUST be RFC-2782 form ending in ".local.".
            // The previous NSD implementation accepted "_FC9F5ED42C8A._tcp."
            // which silently registered nothing on some Android TV builds.
            val serviceInfo = ServiceInfo.create(
                /* type = */ SERVICE_TYPE,
                /* name = */ instanceName,
                /* port = */ port,
                /* weight = */ 0,
                /* priority = */ 0,
                /* props = */ mapOf("n" to nProperty),
            )
            mdns.registerService(serviceInfo)
            jmdns = mdns
            registered = serviceInfo
            Log.i(SCOPE, "Advertising '$instanceName' as $SERVICE_TYPE on $bindAddr:$port")
            instanceName
        }

    companion object {
        const val SERVICE_TYPE = "_FC9F5ED42C8A._tcp.local."
        private const val SCOPE = "MdnsAdv"
        private const val MAX_RETRIES = 3
        private const val BACKOFF_MS = 500L
    }
}
