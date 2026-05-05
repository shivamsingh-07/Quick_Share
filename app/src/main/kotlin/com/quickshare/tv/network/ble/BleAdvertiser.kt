package com.quickshare.tv.network.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.quickshare.tv.system.SdkCompat
import com.quickshare.tv.util.Log
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

/**
 * Quick Share BLE wake-up advertiser — Android port of `rqs_lib::hdl::blea`.
 *
 * # What it does
 *
 * Broadcasts a non-connectable BLE advertisement carrying Quick Share's
 * 16-bit service UUID `0xFE2C` plus a Fast Init service-data payload that
 * Android's Quick Share stack listens for. The production default is a
 * one-shot dynamic 24-byte payload because local testing showed it wakes stock
 * Android phones faster without the mDNS churn caused by repeated rotation.
 * When a nearby Android phone is in "Visible to everyone" mode, hearing this
 * beacon causes its Quick Share scanner to wake up and (importantly)
 * (re-)publish its Wi-Fi LAN mDNS record with the user's *real* device name in
 * the `n` TXT property.
 *
 * Without this beacon, modern stock Quick Share often only publishes a
 * "hidden" mDNS record (17-byte EndpointInfo with no visible name) until
 * a peer actively requests discovery via BLE. That's why our previous
 * mDNS-only picker showed `Phone` / `Tablet` instead of `Pixel 9` /
 * `Galaxy S24`.
 *
 * # What it does NOT do
 *
 *  - It does not transfer files. mDNS + TCP still own that pipeline.
 *  - It does not identify the TV. The service-data is an opaque wake-up token;
 *    we don't put a device name in it.
 *
 * # Lifecycle
 *
 * The flow stays open as long as the collector is alive. While open we:
 *  - hold a single [BluetoothLeAdvertiser.startAdvertising] registration,
 *  - watch [BluetoothAdapter.ACTION_STATE_CHANGED] and re-bind whenever
 *    the user toggles Bluetooth off/on (without forcing the caller to
 *    restart the picker), and
 *  - on the underlying advertise callback firing `onStartFailure(...)`,
 *    we surface a state event but keep the flow open so the caller can
 *    decide to retry on the next picker session.
 *
 * Cancel the collector to stop advertising and unregister callbacks.
 *
 * # Hardware caveats (TV)
 *
 *  - On chipsets where [BluetoothAdapter.isMultipleAdvertisementSupported]
 *    is `false`, [BluetoothAdapter.bluetoothLeAdvertiser] is `null` (the
 *    Android framework gates the advertiser API on the multi-adv chip
 *    bit). We surface this as
 *    [AdvertiseState.Unavailable.Reason.UNSUPPORTED_BY_HARDWARE] so the
 *    rest of discovery can proceed mDNS-only.
 *  - Some Mediatek/Amlogic TV firmwares accept `startAdvertising` then
 *    deliver `onStartFailure(2)` ("ADVERTISE_FAILED_DATA_TOO_LARGE") even
 *    for compact service-data payloads — we log it; discovery can still
 *    continue mDNS-only.
 */
class BleAdvertiser(private val context: Context) {

    private val random = SecureRandom()

    sealed interface AdvertiseState {
        /** Advertisement registered with the chipset. */
        data class Started(
            val adapterName: String?,
            val adapterAddress: String?,
        ) : AdvertiseState

        /** Cannot advertise on this device / right now. */
        data class Unavailable(val reason: Reason, val detail: String? = null) : AdvertiseState

        enum class Reason {
            NO_BLE_HARDWARE,
            BLUETOOTH_OFF,
            MISSING_PERMISSION,
            UNSUPPORTED_BY_HARDWARE,
            START_FAILED,
        }
    }

    /**
     * Cold flow: starts advertising on collect, stops on cancel.
     * Re-emits [AdvertiseState] whenever the underlying adapter state
     * flips (Bluetooth toggled off/on while we're active).
     */
    @SuppressLint("MissingPermission")
    fun observe(): Flow<AdvertiseState> = callbackFlow<AdvertiseState> {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.d(SCOPE) { "no FEATURE_BLUETOOTH_LE — skipping advertiser" }
            trySend(AdvertiseState.Unavailable(AdvertiseState.Reason.NO_BLE_HARDWARE))
            close()
            return@callbackFlow
        }
        if (!hasAdvertisePermission()) {
            Log.w(SCOPE, "BLUETOOTH_ADVERTISE not granted — discovery will rely on mDNS only")
            trySend(AdvertiseState.Unavailable(AdvertiseState.Reason.MISSING_PERMISSION))
            close()
            return@callbackFlow
        }

        // Single shared callback so adapter on/off cycles can stop the
        // previous registration before starting a new one.
        var currentCallback: AdvertiseCallback? = null
        val flowClosed = AtomicBoolean(false)

        fun stopCurrent() {
            val cb = currentCallback ?: return
            currentCallback = null
            val advertiser = adapter()?.bluetoothLeAdvertiser ?: return
            runCatching { advertiser.stopAdvertising(cb) }
                .onFailure { Log.w(SCOPE, "stopAdvertising threw", it) }
        }

        fun startNow() {
            if (flowClosed.get()) return
            stopCurrent()

            val adapter = adapter()
            if (adapter == null || !adapter.isEnabled) {
                Log.d(SCOPE) { "Bluetooth is OFF — advertiser inactive" }
                trySend(AdvertiseState.Unavailable(AdvertiseState.Reason.BLUETOOTH_OFF))
                return
            }

            val advertiser = adapter.bluetoothLeAdvertiser
            if (advertiser == null) {
                // Android framework returns null whenever the chipset
                // doesn't expose multi-advertisement, even when the rest
                // of BLE works. Surface that explicitly so callers can
                // see "this TV won't wake Android Quick Share" once and
                // move on with mDNS-only discovery.
                val reason = if (!adapter.isMultipleAdvertisementSupported) {
                    "isMultipleAdvertisementSupported=false"
                } else {
                    "bluetoothLeAdvertiser=null with multi-adv supported (driver bug?)"
                }
                Log.w(SCOPE, "Cannot advertise — $reason (mDNS-only fallback)")
                trySend(
                    AdvertiseState.Unavailable(
                        AdvertiseState.Reason.UNSUPPORTED_BY_HARDWARE,
                        reason,
                    ),
                )
                return
            }

            fun startWithPayload() {
                if (flowClosed.get()) return
                val payload = BleFastInitPayload.build(random)
                val data = advertiseData(payload)

                val callback = object : AdvertiseCallback() {
                    override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                        if (flowClosed.get()) return
                        if (currentCallback !== this) return
                        // Log shape mirrors rqs_lib::hdl::blea so cross-impl
                        // bug reports compare apples to apples.
                        Log.i(
                            SCOPE,
                            "Advertising FE2C ${BleFastInitPayload.LABEL} beacon (${payload.size} bytes) on " +
                                "${adapter.name ?: "unknown"} (${adapter.address ?: "??"})",
                        )
                        trySend(
                            AdvertiseState.Started(
                                adapterName = adapter.name,
                                adapterAddress = adapter.address,
                            ),
                        )
                    }

                    override fun onStartFailure(errorCode: Int) {
                        if (flowClosed.get()) return
                        if (currentCallback !== this) return
                        if (currentCallback === this) currentCallback = null
                        val text = describeAdvertiseFailure(errorCode)
                        Log.w(SCOPE, "startAdvertising failed: code=$errorCode ($text)")
                        trySend(
                            AdvertiseState.Unavailable(
                                AdvertiseState.Reason.START_FAILED,
                                "code=$errorCode $text",
                            ),
                        )
                    }
                }
                currentCallback = callback

                runCatching { advertiser.startAdvertising(SETTINGS, data, callback) }
                    .onFailure { t ->
                        if (flowClosed.get()) return@onFailure
                        if (currentCallback === callback) currentCallback = null
                        Log.w(SCOPE, "startAdvertising threw — chipset rejected request", t)
                        trySend(
                            AdvertiseState.Unavailable(
                                AdvertiseState.Reason.START_FAILED,
                                t.message,
                            ),
                        )
                    }
            }

            startWithPayload()
        }

        // React to user toggling Bluetooth: when it comes back online,
        // we silently rebind so the picker doesn't have to be reopened.
        val stateReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                val newState = intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.STATE_OFF,
                )
                when (newState) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.d(SCOPE) { "Bluetooth turned ON — restarting advertiser" }
                        startNow()
                    }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        Log.d(SCOPE) { "Bluetooth turned OFF — pausing advertiser" }
                        stopCurrent()
                        trySend(AdvertiseState.Unavailable(AdvertiseState.Reason.BLUETOOTH_OFF))
                    }
                }
            }
        }

        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (SdkCompat.atLeastTiramisu) {
            ContextCompat.registerReceiver(
                context,
                stateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(stateReceiver, filter)
        }

        startNow()

        awaitClose {
            flowClosed.set(true)
            runCatching { context.unregisterReceiver(stateReceiver) }
                .onFailure { /* receiver may have been removed already */ }
            stopCurrent()
            Log.d(SCOPE) { "Advertiser stopped" }
        }
    }.flowOn(Dispatchers.IO)

    private fun adapter(): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private fun hasAdvertisePermission(): Boolean =
        !SdkCompat.atLeastS ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            ) == PackageManager.PERMISSION_GRANTED

    private fun describeAdvertiseFailure(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
        else -> "unknown"
    }

    companion object {
        private const val SCOPE = "BleAdvertiser"

        private val SETTINGS: AdvertiseSettings = AdvertiseSettings.Builder()
            // LOW_LATENCY matches rqs's `Type::Broadcast` in air-time.
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            // Non-connectable mirrors `bluer::adv::Type::Broadcast`.
            .setConnectable(false)
            // Run forever — caller controls lifetime by cancelling the flow.
            .setTimeout(0)
            .build()

        private fun advertiseData(serviceData: ByteArray): AdvertiseData = AdvertiseData.Builder()
            // Service-data only, no name / TX power → keeps us under the
            // 31-byte legacy advertise PDU even on chipsets without
            // extended advertising.
            .addServiceData(QS_SERVICE_UUID, serviceData)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
    }
}
