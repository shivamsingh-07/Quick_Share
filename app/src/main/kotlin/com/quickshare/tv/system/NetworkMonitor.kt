package com.quickshare.tv.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.quickshare.tv.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Reactive view of the device's primary network state.
 *
 * Wraps [ConnectivityManager.registerNetworkCallback] so callers don't have to
 * deal with NetworkRequest construction or callback un-registration boilerplate.
 *
 * What's exposed via [observe] is the current `Status` after every callback;
 * downstream code typically reacts to two transitions:
 *  - WiFi disappears  → tear down advertising/sockets, surface "no network"
 *  - WiFi reappears   → re-advertise, optionally re-discover
 */
class NetworkMonitor(context: Context) {

    sealed interface Status {
        object Disconnected : Status
        data class Connected(
            val isWifi: Boolean,
            val isMetered: Boolean,
            val network: Network,
        ) : Status
    }

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun observe(): Flow<Status> = callbackFlow {
        // Send an initial snapshot so collectors don't sit in the dark until
        // the first transition.
        trySend(snapshot())

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(snapshot()) }
            override fun onLost(network: Network)      { trySend(snapshot()) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(snapshot())
            }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                trySend(snapshot())
            }
        }

        runCatching { cm.registerNetworkCallback(request, callback) }
            .onFailure { Log.w(SCOPE, "registerNetworkCallback failed", it) }

        awaitClose {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()

    private fun snapshot(): Status {
        val active = cm.activeNetwork ?: return Status.Disconnected
        val caps = cm.getNetworkCapabilities(active) ?: return Status.Disconnected
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return Status.Disconnected
        }
        return Status.Connected(
            isWifi    = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            network   = active,
        )
    }

    companion object { private const val SCOPE = "NetworkMonitor" }
}
