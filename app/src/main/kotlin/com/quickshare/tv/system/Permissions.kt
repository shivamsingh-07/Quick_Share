package com.quickshare.tv.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Centralised, version-aware permission declarations and runtime helpers.
 *
 * Surfaces:
 *  - [requiredAtRuntime] — the permissions the app *must* hold to do its job
 *                          on a given API level.
 *  - [bleRuntime]        — Bluetooth-LE permissions; denying is non-fatal
 *                          (falls back to mDNS-only discovery).
 */
object Permissions {

    /** Runtime permissions we always request once on first launch. */
    fun requiredAtRuntime(): Array<String> = buildList {
        // Lets the mandatory FGS notification render on API 33+ (silent/low channel).
        if (SdkCompat.atLeastTiramisu) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    /**
     * Bluetooth-LE permissions for the rqs-style stack:
     *  - `BLUETOOTH_ADVERTISE` — Send-side wake-up beacon.
     *  - `BLUETOOTH_SCAN`      — Receive-side passive listener that
     *    triggers an mDNS re-announce when a phone starts looking.
     *  - `BLUETOOTH_CONNECT`   — read adapter name/address for
     *    diagnostics (logs match rqs's format).
     *
     * Denying any of these does NOT break transfers; the picker
     * collapses to mDNS-only discovery and the receiver simply doesn't
     * get the BLE nudge to re-announce.
     *
     * - **API 31+:** runtime permissions above.
     * - **API ≤ 30 (our minSdk):** the legacy `BLUETOOTH` /
     *   `BLUETOOTH_ADMIN` install-time permissions cover both halves.
     */
    fun bleRuntime(): Array<String> = if (SdkCompat.atLeastS) {
        arrayOf(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        emptyArray()
    }

}

/**
 * Compose / Activity-friendly launcher.
 *
 * On Android-TV the system permission dialog is a full-screen activity that's
 * already D-pad navigable, so we don't need to roll our own UI; we only need
 * to plug the Activity-Result API in.
 *
 * Usage:
 * ```kotlin
 * private val perms = PermissionsRequester(this) { granted ->
 *     // ... toggle UI based on `granted`
 * }
 * override fun onStart() {
 *     super.onStart()
 *     perms.requestIfNeeded(Permissions.requiredAtRuntime())
 * }
 * ```
 */
class PermissionsRequester(
    private val activity: ComponentActivity,
    private val onResult: (allGranted: Boolean, granted: Map<String, Boolean>) -> Unit,
) {
    // NOTE: Do **not** dereference `activity.applicationContext` here. When a
    // PermissionsRequester is created as a property initializer of a
    // ComponentActivity, it runs before the framework has attached a base
    // context, so applicationContext is null and we'd NPE on launch.
    //
    // registerForActivityResult itself is safe to call from this construction
    // window because it only stashes a registry key — it doesn't touch the
    // context.
    private val launcher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            onResult(result.values.all { it }, result)
        }

    /** Request permissions that aren't already granted; no-op otherwise. */
    fun requestIfNeeded(permissions: Array<String>) {
        if (permissions.isEmpty()) {
            onResult(true, emptyMap())
            return
        }
        // Resolved lazily — by the time anyone calls requestIfNeeded() the
        // activity is at least in CREATED, so the base context is attached.
        val context: Context = activity
        val missing = permissions.filterNot {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (missing.isEmpty()) {
            onResult(true, permissions.associateWith { true })
        } else {
            launcher.launch(missing)
        }
    }
}
