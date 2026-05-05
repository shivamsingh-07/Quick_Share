package com.quickshare.tv.ui.receive

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickshare.tv.QuickShareApp
import com.quickshare.tv.data.repository.ReceiveRepository
import com.quickshare.tv.data.repository.SettingsRepository
import com.quickshare.tv.domain.model.DeviceKind
import com.quickshare.tv.domain.model.FileMeta
import com.quickshare.tv.domain.model.ReceiveEvent
import com.quickshare.tv.domain.usecase.AcceptReceiveUseCase
import com.quickshare.tv.domain.usecase.StartReceiveUseCase
import com.quickshare.tv.domain.usecase.StopReceiveUseCase
import com.quickshare.tv.ui.TransferFailureMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReceiveViewModel(
    private val start: StartReceiveUseCase = StartReceiveUseCase(QuickShareApp.instance.receiveRepository),
    private val stop: StopReceiveUseCase = StopReceiveUseCase(QuickShareApp.instance.receiveRepository),
    private val accept: AcceptReceiveUseCase = AcceptReceiveUseCase(QuickShareApp.instance.receiveRepository),
    private val receiveRepo: ReceiveRepository = QuickShareApp.instance.receiveRepository,
    settingsRepo: SettingsRepository = QuickShareApp.instance.settingsRepository,
) : ViewModel() {

    /** Device name sourced from settings, used by the Receive screen as the
     *  listening-state headline ("Receiving on <name>"). */
    val deviceName: StateFlow<String> = settingsRepo.deviceNameFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    data class Ui(
        val peerName: String? = null,
        /**
         * Coarse classification for the connected sender. Drives the
         * "<DeviceType> <DeviceName>" pill rendered during the
         * Transferring phase; defaults to [DeviceKind.UNKNOWN] until we
         * see a [ReceiveEvent.PeerIntroduced] event.
         */
        val peerKind: DeviceKind = DeviceKind.UNKNOWN,
        val authString: String? = null,
        val pin: String? = null,
        val pendingFiles: List<FileMeta> = emptyList(),
        val showPrompt: Boolean = false,
        val progressByPayload: Map<Long, Pair<Long, Long>> = emptyMap(),
        val saved: List<String> = emptyList(),
        val savedPayloadIds: Set<Long> = emptySet(),
        @StringRes val errorMessageRes: Int? = null,
        val done: Boolean = false,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    // Tracked so we can cancel + restart cleanly when the screen leaves and
    // re-enters composition. Without this the Receive flow leaks across
    // navigations and the Send screen ends up self-connecting to it.
    private var collectJob: Job? = null

    /** Async [StopReceiveUseCase] work — JmDNS teardown can block; join before next [start]. */
    private var repoStopJob: Job? = null

    /**
     * Start (or restart) the Receive pipeline. Idempotent: a second call while
     * a session is still active is a no-op so cheap recompositions don't
     * thrash the network stack.
     */
    fun startReceiving() {
        if (collectJob?.isActive == true) return
        // Reset UI so a re-entered screen doesn't show stale Done / error from
        // the previous session.
        _ui.value = Ui()
        collectJob = viewModelScope.launch {
            repoStopJob?.join()
            repoStopJob = null
            try {
                start().collect { event ->
                    _ui.value = reduce(_ui.value, event)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // JmDNS can transiently fail on TV chipsets; surface the
                // failure to the UI rather than crashing the activity.
                _ui.value = _ui.value.copy(errorMessageRes = TransferFailureMessages.receive(t))
            }
        }
    }

    /** Tear down advertising / TCP server / wake-lock. Idempotent. */
    fun stopReceiving() {
        collectJob?.cancel()
        collectJob = null
        repoStopJob = viewModelScope.launch(Dispatchers.IO) {
            stop()
        }
    }

    /**
     * After [ProcessLifecycleOwner] signals foreground, rebuild the listener **only**
     * if [ReceiveRepository.onApplicationBackgrounded] stopped an idle pipeline.
     * Never runs while an inbound transfer is active — otherwise returning from
     * Home would call [stopReceiving] and kill the session mid-transfer.
     */
    fun restartPipelineIfRunning() {
        if (collectJob?.isActive != true) return
        if (receiveRepo.isInboundSessionActive()) return
        if (!receiveRepo.consumeRestartAfterForeground()) return
        viewModelScope.launch {
            stopReceiving()
            startReceiving()
        }
    }

    /** After success/failure, return to the idle “ready to receive” UI and restart listening. */
    fun clearCompletedAndRestart() {
        viewModelScope.launch {
            stopReceiving()
            startReceiving()
        }
    }

    fun decide(accept: Boolean) {
        this.accept.invoke(accept)
        _ui.value = _ui.value.copy(showPrompt = false)
    }

    override fun onCleared() {
        stopReceiving()
        super.onCleared()
    }

    private fun reduce(s: Ui, e: ReceiveEvent): Ui = when (e) {
        ReceiveEvent.Listening                      -> s
        is ReceiveEvent.Connected                   -> s
        is ReceiveEvent.PeerIntroduced              -> s.copy(peerName = e.peerName, peerKind = e.peerKind)
        is ReceiveEvent.Handshaked                  -> s.copy(authString = e.authString, pin = e.pin)
        is ReceiveEvent.IntroductionReceived        ->
            s.copy(
                peerName = e.sender.takeIf { it.isNotBlank() } ?: s.peerName,
                pendingFiles = e.files,
                showPrompt = e.needsPrompt && e.files.isNotEmpty(),
            )
        is ReceiveEvent.Progress                    -> s.copy(progressByPayload = s.progressByPayload + (e.payloadId to (e.received to e.total)))
        is ReceiveEvent.FileSaved                   -> s.copy(
            saved = s.saved + e.path,
            savedPayloadIds = s.savedPayloadIds + e.payloadId,
        )
        is ReceiveEvent.Done                        -> s.copy(done = true)
        is ReceiveEvent.Failed                      -> s.copy(errorMessageRes = TransferFailureMessages.receive(e.cause))
    }
}
