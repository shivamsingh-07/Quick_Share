package com.quickshare.tv.util

import android.util.Log
import com.quickshare.tv.BuildConfig

/**
 * Tag-prefixed logger used app-wide.
 *
 * # Severity contract
 *
 *  - [v] / [d] are **debug-only diagnostic logs**. Both are gated on
 *    [BuildConfig.DEBUG] (or [BuildConfig.VERBOSE_PROTOCOL_LOG] for QA
 *    release builds) and accept a lambda so the message string is
 *    never even constructed in shipping APKs. Use freely for tracing —
 *    no perf or log-volume cost in release.
 *  - [i] is for **lifecycle events** the field cares about: discovery
 *    started, peer connected, transfer completed, settings flipped.
 *    Always emitted — keep these terse and non-spammy.
 *  - [w] is for **recoverable problems**: a retry, a dropped packet, a
 *    permission we can work around. Always emitted; optional [Throwable].
 *  - [e] is for **terminal failures** that abort the current operation
 *    (transfer aborted, handshake mismatch). Always emitted; optional
 *    [Throwable].
 *
 * # Privacy
 *
 * Every log call must be safe to share in a bug report. IP addresses,
 * device names, payload sizes, and protocol metadata are fine; payload
 * bodies, cryptographic key material, file contents, and SAF URI paths
 * containing personal directories are not.
 */
object Log {
    @PublishedApi internal const val TAG = "Quick_Share"

    /**
     * Single gate for [v] / [d]. `true` in dev/debug builds and in any
     * release build that opted into the QA verbose protocol log.
     */
    @PublishedApi internal val DEBUG: Boolean =
        BuildConfig.DEBUG || BuildConfig.VERBOSE_PROTOCOL_LOG

    inline fun v(scope: String, msg: () -> String) {
        if (DEBUG) Log.v(TAG, "[$scope] ${msg()}")
    }
    inline fun d(scope: String, msg: () -> String) {
        if (DEBUG) Log.d(TAG, "[$scope] ${msg()}")
    }
    fun i(scope: String, msg: String) = Log.i(TAG, "[$scope] $msg")
    fun w(scope: String, msg: String, t: Throwable? = null) = Log.w(TAG, "[$scope] $msg", t)
    fun e(scope: String, msg: String, t: Throwable? = null) = Log.e(TAG, "[$scope] $msg", t)
}
