package com.quickshare.tv.system

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.quickshare.tv.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

/**
 * Reactive observer for whether Bluetooth is ready for discovery work.
 *
 * The send-side picker piggybacks on the [BleAdvertiser][com.quickshare.tv.network.ble.BleAdvertiser]
 * wake-up beacon to nudge nearby Android phones into publishing their
 * mDNS name. Without Bluetooth, the picker is essentially blind on
 * stock Android Quick Share builds, so the UI surfaces a single
 * not-ready state and pushes the user toward the QR fallback.
 *
 * Two coarse buckets — every state the UI can usefully act on:
 *
 *  - [State.READY]        Adapter is on, BLE hardware is present, and we
 *                         have the runtime permissions we need to use it.
 *                         Picker scan can run.
 *  - [State.NOT_READY]    Anything else: adapter off, no BLE hardware,
 *                         missing runtime permission, or no adapter.
 *                         Discovery is unavailable, but QR can still work.
 */
object BluetoothMonitor {

    enum class State {
        READY,
        NOT_READY,
    }

    /** Synchronous snapshot — useful for one-off gating outside flow scope. */
    fun current(context: Context): State = computeState(context)

    /**
     * Cold flow that emits [State] on collect and re-emits whenever the
     * system broadcasts an [BluetoothAdapter.ACTION_STATE_CHANGED]
     * (i.e. user toggled Bluetooth from Quick Settings, an OS update
     * cycled the radio, etc).
     *
     * Permission / hardware presence checks are re-evaluated on every
     * recompute so a recently-granted runtime permission flips us into
     * [State.READY] without the caller having to restart anything.
     *
     * `distinctUntilChanged()` gates redundant re-emits so a burst of
     * `STATE_TURNING_ON` → `STATE_ON` doesn't double-render the UI.
     */
    fun observe(context: Context): Flow<State> = callbackFlow<State> {
        // Initial snapshot. Without this the collector would sit blank
        // until the user actually toggled the adapter — wrong default
        // for a cold flow that's expected to drive UI state.
        trySend(computeState(context))

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                val newState = computeState(context)
                Log.d(SCOPE) { "ACTION_STATE_CHANGED → $newState" }
                trySend(newState)
            }
        }

        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (SdkCompat.atLeastTiramisu) {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        awaitClose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
        .flowOn(Dispatchers.IO)
        .distinctUntilChanged()

    /**
     * Recompute whether Bluetooth can currently run the BLE discovery helpers.
     * The app intentionally does not distinguish "off" from "unavailable" in
     * UX; both states use the QR fallback.
     */
    private fun computeState(context: Context): State {
        val pm = context.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return State.NOT_READY
        }
        if (!hasBluetoothPermissions(context)) {
            return State.NOT_READY
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return State.NOT_READY
        return if (adapter.isEnabled) State.READY else State.NOT_READY
    }

    /**
     * The set of runtime perms our BLE stack needs on this OS. On
     * pre-S the legacy install-time `BLUETOOTH` / `BLUETOOTH_ADMIN`
     * permissions are auto-granted, so the predicate is vacuously true
     * there — same shape we use in [com.quickshare.tv.system.Permissions.bleRuntime].
     */
    private fun hasBluetoothPermissions(context: Context): Boolean {
        if (!SdkCompat.atLeastS) return true
        val needed = arrayOf(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        return needed.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private const val SCOPE = "BluetoothMon"
}
