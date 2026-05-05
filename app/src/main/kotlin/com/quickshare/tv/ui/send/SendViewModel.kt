package com.quickshare.tv.ui.send

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickshare.tv.QuickShareApp
import com.quickshare.tv.R
import com.quickshare.tv.data.repository.SendRepository
import com.quickshare.tv.domain.model.DeviceKind
import com.quickshare.tv.domain.model.DiscoveredDevice
import com.quickshare.tv.domain.model.SendEvent
import com.quickshare.tv.domain.usecase.StartSendUseCase
import com.quickshare.tv.domain.usecase.StopSendUseCase
import com.quickshare.tv.system.BluetoothMonitor
import com.quickshare.tv.ui.TransferFailureMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class SendViewModel(
    private val repository: SendRepository = QuickShareApp.instance.sendRepository,
    private val start: StartSendUseCase = StartSendUseCase(repository),
    private val stop: StopSendUseCase = StopSendUseCase(repository),
) : ViewModel() {

    /**
     * Two-mode send flow. PICK_DEVICE is the default once URIs land —
     * the screen renders the picker (mDNS list with a "Use QR"
     * affordance). The user can flip to QR explicitly via
     * [switchToQrMode] (the picker's "Use QR" button), or pick a row
     * which jumps straight to TRANSFERRING via [selectDevice].
     */
    enum class Mode { PICK_DEVICE, QR }

    data class Ui(
        val mode: Mode = Mode.PICK_DEVICE,
        /** Live device list during PICK_DEVICE; empty in QR mode. */
        val devices: List<DiscoveredDevice> = emptyList(),
        val qrUrl: String? = null,
        /**
         * Pre-rendered 1024px PNG for the active QR. Null until the
         * sender pipeline emits `QrReady`; the screen scales it inside
         * the responsive TV frame.
         */
        val qrPngBytes: ByteArray? = null,
        @StringRes val statusRes: Int = R.string.send_status_pick_files,
        /** Display name of the peer once `SendEvent.Connecting` arrives. */
        val peerName: String? = null,
        val peerKind: DeviceKind = DeviceKind.UNKNOWN,
        val authString: String? = null,
        val pin: String? = null,
        val progress: Map<Long, Pair<Long, Long>> = emptyMap(),
        val done: Boolean = false,
        @StringRes val errorMessageRes: Int? = null,
        /** Bluetooth readiness snapshot; the VM passes the live repo state through unchanged. */
        val bluetooth: BluetoothMonitor.State = BluetoothMonitor.State.READY,
    )

    private val _ui = MutableStateFlow(Ui(bluetooth = repository.bluetooth.value))
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    /** Owns the [SendEvent] subscription started by [startWith]. */
    private var collectJob: Job? = null

    /**
     * Owns the picker-state subscription (devices). Lives
     * across mode switches because both the picker and the QR fallback
     * read from the same VM `Ui`; we just clear the device list on
     * [switchToQrMode] / [selectDevice].
     */
    private var pickerSubscriptionJob: Job? = null

    /**
     * Owns the bluetooth-state subscription. Started in `init` so the
     * UI reflects the current adapter state even before the user picks
     * any files (the picker prompt copy already references it), and
     * survives mode swaps + reset cycles for the lifetime of the VM.
     */
    private var bluetoothSubscriptionJob: Job? = null

    init {
        bluetoothSubscriptionJob = viewModelScope.launch {
            repository.bluetooth.collect { state ->
                _ui.value = _ui.value.copy(bluetooth = state)
            }
        }
    }

    /**
     * Kick off the device-picker pipeline. Idempotent — re-invoked
     * when the screen recomposes with the same URIs, no-op while a
     * previous send is still in flight. The repo itself dedupes
     * back-to-back calls with the same payload.
     */
    fun startWith(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (collectJob?.isActive == true) return

        _ui.value = Ui(
            mode = Mode.PICK_DEVICE,
            statusRes = R.string.send_picker_scanning,
            bluetooth = repository.bluetooth.value,
        )

        // Subscribe to the picker side-channel once. It comes from a
        // StateFlow so we can stay subscribed
        // across mode swaps without missing updates. Cancelled in
        // [reset]/[onCleared].
        pickerSubscriptionJob?.cancel()
        pickerSubscriptionJob = viewModelScope.launch {
            repository.devices.collect { devices ->
                _ui.value = _ui.value.copy(devices = devices)
            }
        }

        collectJob = viewModelScope.launch {
            try {
                start(context, uris).collect { e ->
                    _ui.value = reduce(_ui.value, e)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(errorMessageRes = TransferFailureMessages.send(t))
            }
        }
    }

    /**
     * User picked a row in the device picker. Hands off to the repo,
     * clears the picker hint copy, and switches the screen to the
     * connecting/transferring phase via the next `Connecting` event.
     */
    fun selectDevice(deviceId: String) {
        // Optimistically clear the picker so the screen doesn't blink
        // back to "Scanning..." between the click and the first
        // Connecting event landing.
        _ui.value = _ui.value.copy(
            devices = emptyList(),
            statusRes = R.string.send_status_connecting,
        )
        repository.selectDevice(deviceId)
    }

    /**
     * User tapped "Use QR" in the picker. Tells the repo to swap
     * pipelines; the next event the UI will see is a `QrReady` and
     * the screen flips to the QR card.
     */
    fun switchToQrMode() {
        _ui.value = _ui.value.copy(
            mode = Mode.QR,
            devices = emptyList(),
            statusRes = R.string.send_status_generating_qr,
        )
        repository.useQrInstead()
    }

    /** Cancel discovery/handshake/streaming and stop the foreground service. */
    fun stopSending() {
        collectJob?.cancel()
        collectJob = null
        pickerSubscriptionJob?.cancel()
        pickerSubscriptionJob = null
        stop()
    }

    /**
     * Drop any prior session state so the next [startWith] starts fresh.
     * Called by the screen when the URI list is cleared (e.g. after the
     * user backs out of a completed transfer) so a stale "done" or
     * "failed" state from a previous run can't leak into the new one.
     */
    fun reset() {
        stopSending()
        // Preserve the live bluetooth snapshot across resets so the
        // picker doesn't briefly flash "READY" between sessions on a
        // device that's actually missing the adapter / has it off.
        _ui.value = Ui(bluetooth = repository.bluetooth.value)
    }

    override fun onCleared() {
        stopSending()
        bluetoothSubscriptionJob?.cancel()
        bluetoothSubscriptionJob = null
        super.onCleared()
    }

    private fun reduce(s: Ui, e: SendEvent): Ui = when (e) {
        SendEvent.Idle             -> s
        // QR phase shows a single static hint (`R.string.send_qr_hint`); the
        // VM intentionally doesn't push a parallel status copy here so the
        // screen can't render two competing one-liners under the QR.
        is SendEvent.QrReady       -> s.copy(
            qrUrl = e.url,
            qrPngBytes = e.pngBytes,
            mode = Mode.QR,
        )
        is SendEvent.Connecting    -> s.copy(peerName = e.peer.displayName,
                                              peerKind = e.peer.deviceType.toDeviceKind(),
                                              statusRes = R.string.send_status_connecting)
        is SendEvent.Handshaked    -> s.copy(authString = e.authString, pin = e.pin,
                                              statusRes = R.string.send_status_verifying)
        SendEvent.Awaiting         -> s.copy(statusRes = R.string.send_status_awaiting)
        // Progress events overwrite the prior "Awaiting…" / "Verifying…"
        // status with the active "Sending…" copy. Without this the screen
        // would freeze on whichever string was set just before the first
        // chunk arrived (typically "Waiting for confirmation…",
        // which becomes stale the instant the receiver accepts).
        is SendEvent.Progress      -> s.copy(
            progress = s.progress + (e.payloadId to (e.sent to e.total)),
            statusRes = R.string.send_status_sending,
        )
        SendEvent.Done             -> s.copy(done = true, statusRes = R.string.send_status_done)
        is SendEvent.Failed        -> s.copy(errorMessageRes = TransferFailureMessages.send(e.cause))
    }

    private companion object {
        fun String.toDeviceKind(): DeviceKind = when (uppercase()) {
            "PHONE" -> DeviceKind.PHONE
            "TABLET" -> DeviceKind.TABLET
            "LAPTOP" -> DeviceKind.LAPTOP
            "TV" -> DeviceKind.TV
            else -> DeviceKind.UNKNOWN
        }
    }
}
