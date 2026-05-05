package com.quickshare.tv.system

import android.content.Context
import android.net.wifi.WifiManager
import com.quickshare.tv.util.Log

/**
 * MDNS relies on multicast UDP. On many Android TVs the WiFi chipset filters
 * multicast packets aggressively to save power; the standard remedy is to hold
 * a [WifiManager.MulticastLock] for the duration of advertising/discovery.
 *
 * The lock is reference-counted internally and idempotent — multiple
 * acquire/release pairs nest safely. We keep ours non-reference-counted at the
 * holder level so the contract is "one logical owner per holder instance".
 */
class MulticastLockHolder(context: Context, private val tag: String) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lock: WifiManager.MulticastLock? = null

    fun acquire() {
        if (lock?.isHeld == true) return
        runCatching {
            val l = wifi.createMulticastLock(tag).apply {
                setReferenceCounted(false)
                acquire()
            }
            lock = l
            Log.d(SCOPE) { "Acquired multicast lock for $tag" }
        }.onFailure { Log.w(SCOPE, "MulticastLock.acquire failed for $tag", it) }
    }

    fun release() {
        runCatching {
            lock?.takeIf { it.isHeld }?.release()
            Log.d(SCOPE) { "Released multicast lock for $tag" }
        }.onFailure { Log.w(SCOPE, "MulticastLock.release failed for $tag", it) }
        lock = null
    }

    companion object { private const val SCOPE = "MulticastLock" }
}
