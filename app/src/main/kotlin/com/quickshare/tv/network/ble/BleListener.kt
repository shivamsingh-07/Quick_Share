package com.quickshare.tv.network.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.quickshare.tv.system.SdkCompat
import com.quickshare.tv.util.Log
import com.quickshare.tv.util.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

/**
 * Quick Share BLE passive listener — Android port of `rqs_lib::hdl::ble`.
 *
 * # Why
 *
 * When a phone (or any Quick Share-capable peer) is in "Visible to
 * everyone" mode and the user opens its share sheet, Android starts
 * BLE-broadcasting Quick Share's `0xFE2C` service-data beacon to wake
 * up nearby receivers. rqs_lib uses that signal on the *receiver*
 * side to:
 *
 *   1) prove that someone nearby is actively trying to share, and
 *   2) trigger an immediate mDNS re-broadcast of our own receive
 *      service so Android sees us even if our `_FC9F5ED42C8A._tcp`
 *      record was registered before Android started listening.
 *
 * Without (2) we routinely lose the race on Android-TV chipsets where
 * mDNS announces are sent once at register-time and not retried until
 * the next 60 s tick.
 *
 * # API
 *
 * Cold flow that emits:
 *  - [Event.Started] when the chipset accepts the scan request,
 *  - [Event.NearbySharing] each time a fresh FE2C advertisement lands
 *    (max once per [DEBOUNCE_MS] to avoid storming the receiver), and
 *  - [Event.Unavailable] when BLE scanning isn't possible right now
 *    (no permission, BT off, no chip support, callback flood).
 *
 * Cancelling the collector stops scanning and unregisters callbacks.
 *
 * # Hardware caveats
 *
 *  - Android-TV firmwares vary wildly in BLE-scan delivery. Some
 *    deliver service-data scan results, some only deliver after a
 *    foreground-service nudges the BLE stack, and some simply never
 *    deliver scan callbacks for the offloaded filter list. We log
 *    the on-the-wire result counter so the field can tell which class
 *    a given device falls into.
 *  - We post the platform [ScanFilter] for the FE2C UUID so cheap
 *    chipsets can offload filtering to the controller (low duty cycle).
 *    On chipsets that ignore offloaded filters we still re-check the
 *    UUID in the callback — see issue #74 in rquickshare for the
 *    motivating bug.
 */
class BleListener(private val context: Context) {

    sealed interface Event {
        /** Scanner registered with the chipset. */
        data class Started(val adapterAddress: String?) : Event

        /**
         * A nearby peer broadcast Quick Share's FE2C beacon.
         *
         * @param deviceAddress the broadcaster's BLE MAC if the OS exposes
         * one (often a randomized "BLE address" on modern Android, but
         * still stable for the duration of one share session).
         * @param rssi RSSI in dBm — higher (less negative) is closer.
         * @param fingerprintHex compact hex of the FE2C service-data
         * bytes when the broadcaster sent service-data (rqs publishes a
         * fixed 24-byte blob; some third-party clients and stock builds publish
         * dynamic bytes per session). `null` when the beacon only
         * advertised the UUID without service-data; correlation has to
         * fall back to MAC + arrival time in that case.
         */
        data class NearbySharing(
            val deviceAddress: String?,
            val rssi: Int,
            val fingerprintHex: String? = null,
        ) : Event

        /** Cannot scan right now. */
        data class Unavailable(val reason: Reason, val detail: String? = null) : Event

        enum class Reason {
            NO_BLE_HARDWARE,
            BLUETOOTH_OFF,
            MISSING_PERMISSION,
            START_FAILED,
            CALLBACK_FLOOD,
        }
    }

    @SuppressLint("MissingPermission")
    fun observe(): Flow<Event> = callbackFlow<Event> {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.d(SCOPE) { "no FEATURE_BLUETOOTH_LE — skipping listener" }
            trySend(Event.Unavailable(Event.Reason.NO_BLE_HARDWARE))
            close()
            return@callbackFlow
        }
        if (!hasScanPermission()) {
            Log.w(SCOPE, "BLUETOOTH_SCAN not granted — receive will rely on mDNS only")
            trySend(Event.Unavailable(Event.Reason.MISSING_PERMISSION))
            close()
            return@callbackFlow
        }

        var scanner: android.bluetooth.le.BluetoothLeScanner? = null
        var callback: ScanCallback? = null
        var lastEmitMs = 0L
        var resultsSeen = 0

        fun stopCurrent() {
            val s = scanner ?: return
            val cb = callback ?: return
            scanner = null
            callback = null
            runCatching { s.stopScan(cb) }
                .onFailure { Log.w(SCOPE, "stopScan threw", it) }
        }

        fun startNow() {
            stopCurrent()

            val adapter = adapter()
            if (adapter == null || !adapter.isEnabled) {
                Log.d(SCOPE) { "Bluetooth is OFF — listener inactive" }
                trySend(Event.Unavailable(Event.Reason.BLUETOOTH_OFF))
                return
            }
            val s = adapter.bluetoothLeScanner
            if (s == null) {
                Log.w(SCOPE, "bluetoothLeScanner=null — chipset offers no scanner (mDNS-only fallback)")
                trySend(Event.Unavailable(Event.Reason.START_FAILED, "scanner=null"))
                return
            }

            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    result?.let { handle(it) }
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                    results?.forEach(::handle)
                }

                override fun onScanFailed(errorCode: Int) {
                    val text = describeScanFailure(errorCode)
                    Log.w(SCOPE, "scan failed: code=$errorCode ($text)")
                    trySend(
                        Event.Unavailable(
                            Event.Reason.START_FAILED,
                            "code=$errorCode $text",
                        ),
                    )
                }

                /** Filter on FE2C even if the chipset accepted the offload — issue #74. */
                private fun handle(r: ScanResult) {
                    resultsSeen++
                    val record = r.scanRecord ?: return
                    // Either of these fields can carry Quick Share's UUID
                    // depending on whether the broadcaster sent it as
                    // service-data, complete UUID list, or solicitation.
                    val sdBytes = record.serviceData?.get(QS_SERVICE_UUID)
                    val sd = sdBytes != null
                    val complete = record.serviceUuids?.contains(QS_SERVICE_UUID) == true
                    val sol = record.serviceSolicitationUuids?.contains(QS_SERVICE_UUID) == true
                    if (!sd && !complete && !sol) return

                    val now = SystemClock.elapsedRealtime()
                    if (now - lastEmitMs < DEBOUNCE_MS) return
                    lastEmitMs = now

                    val addr = runCatching { r.device?.address }.getOrNull()
                    // Fingerprint = hex of the FE2C service-data prefix.
                    // Different OEMs publish different payloads (rqs is
                    // fixed-static, some FOSS clients randomise 9 bytes per
                    // session, stock Android Quick Share rotates per share)
                    // so the hex prefix is a useful corroboration key for
                    // matching mDNS resolves to "yes, that device is
                    // *currently* trying to share" without trusting the
                    // randomizable BLE MAC alone.
                    val fingerprint = sdBytes?.toHex(FINGERPRINT_HEX_BYTES)
                    Log.i(
                        SCOPE,
                        "Nearby Quick Share peer ($addr) — rssi=${r.rssi}dBm " +
                            "(sd=$sd complete=$complete sol=$sol seen=$resultsSeen" +
                            (fingerprint?.let { " fp=$it" } ?: "") + ")",
                    )
                    trySend(Event.NearbySharing(addr, r.rssi, fingerprint))
                }
            }

            // Hint the chipset to offload filtering to the controller.
            // We *also* re-check in the callback (issue #74).
            val filters = listOf(
                ScanFilter.Builder()
                    .setServiceUuid(QS_SERVICE_UUID)
                    .build(),
            )
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .build()

            runCatching { s.startScan(filters, settings, cb) }
                .onSuccess {
                    scanner = s
                    callback = cb
                    Log.i(SCOPE, "Listening for FE2C beacons on ${adapter.address ?: "??"}")
                    trySend(Event.Started(adapter.address))
                }
                .onFailure { t ->
                    Log.w(SCOPE, "startScan threw", t)
                    trySend(
                        Event.Unavailable(
                            Event.Reason.START_FAILED,
                            t.message,
                        ),
                    )
                }
        }

        val stateReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.d(SCOPE) { "Bluetooth turned ON — restarting listener" }
                        startNow()
                    }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        Log.d(SCOPE) { "Bluetooth turned OFF — pausing listener" }
                        stopCurrent()
                        trySend(Event.Unavailable(Event.Reason.BLUETOOTH_OFF))
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
            runCatching { context.unregisterReceiver(stateReceiver) }
                .onFailure { /* receiver may have been removed already */ }
            stopCurrent()
            Log.d(SCOPE) { "Listener stopped (resultsSeen=$resultsSeen)" }
        }
    }.flowOn(Dispatchers.IO)

    private fun adapter(): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private fun hasScanPermission(): Boolean = if (SdkCompat.atLeastS) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_SCAN,
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        // Pre-12: BLE scan with serviceUuid filter requires location.
        // We declare ACCESS_FINE_LOCATION only at runtime if the OS
        // demands it; on legacy installs it falls back to install-time
        // BLUETOOTH_ADMIN which is auto-granted.
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_ADMIN,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun describeScanFailure(code: Int): String = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "APP_REG_FAILED"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
        else -> "unknown"
    }

    companion object {
        private const val SCOPE = "BleListener"

        /** Min ms between consecutive [Event.NearbySharing] emissions. */
        private const val DEBOUNCE_MS: Long = 30_000L

        /**
         * Number of leading service-data bytes to expose as a fingerprint.
         * 8 bytes is short enough to keep log lines compact (~16 hex
         * chars) and long enough to disambiguate concurrent broadcasters
         * (rqs static prefix differs from those clients' even before the
         * randomized region begins).
         */
        private const val FINGERPRINT_HEX_BYTES = 8

    }
}
