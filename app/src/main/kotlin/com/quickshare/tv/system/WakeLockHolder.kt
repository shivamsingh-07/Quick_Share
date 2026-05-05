package com.quickshare.tv.system

import android.content.Context
import android.os.PowerManager
import com.quickshare.tv.util.Log

/**
 * Partial wake lock used during *active* transfers only.
 *
 * Doze and App Standby will tear down idle TCP sockets after a few minutes
 * of inactivity. Holding a partial wake lock while bytes are flowing gives
 * the kernel enough scheduling priority to keep the connection alive. We
 * always release it the moment the transfer finishes (success or failure)
 * so we don't kill TV battery / heat budget.
 *
 * Backed by [PowerManager.WakeLock] which has a built-in safety timeout —
 * we set ours to 30 minutes so a stuck transfer can't pin the device awake
 * forever.
 */
class WakeLockHolder(context: Context, private val tag: String) {
    private val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wl: PowerManager.WakeLock? = null

    fun acquire(timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        if (wl?.isHeld == true) return
        runCatching {
            val l = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Quick_Share:$tag")
            l.setReferenceCounted(false)
            l.acquire(timeoutMs)
            wl = l
            Log.d(SCOPE) { "Acquired wake lock for $tag (timeout=${timeoutMs}ms)" }
        }.onFailure { Log.w(SCOPE, "WakeLock.acquire failed for $tag", it) }
    }

    fun release() {
        runCatching {
            wl?.takeIf { it.isHeld }?.release()
            Log.d(SCOPE) { "Released wake lock for $tag" }
        }.onFailure { Log.w(SCOPE, "WakeLock.release failed for $tag", it) }
        wl = null
    }

    companion object {
        private const val SCOPE = "WakeLock"
        private const val DEFAULT_TIMEOUT_MS = 30L * 60_000  // 30 min
    }
}
