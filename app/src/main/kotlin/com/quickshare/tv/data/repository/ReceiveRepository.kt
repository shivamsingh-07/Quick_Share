package com.quickshare.tv.data.repository

import android.content.Context
import com.quickshare.tv.R
import com.quickshare.tv.data.service.TransferServiceController
import com.quickshare.tv.domain.model.LocalEndpoint
import com.quickshare.tv.domain.model.ReceiveEvent
import com.quickshare.tv.network.EndpointInfo
import com.quickshare.tv.network.ServiceNameCodec
import com.quickshare.tv.network.ble.BleListener
import com.quickshare.tv.network.mdns.MdnsAdvertiser
import com.quickshare.tv.network.tcp.TcpServer
import com.quickshare.tv.protocol.ReceiverSession
import com.quickshare.tv.system.NetworkMonitor
import com.quickshare.tv.system.StorageAccess
import com.quickshare.tv.system.WakeLockHolder
import com.quickshare.tv.util.Log
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Wires up mDNS + TCP + ReceiverSession for the "Receive" flow.
 *
 * When the app moves to the background ([onApplicationBackgrounded]), we stop
 * idle listening immediately; if a transfer is in progress we finish it, then
 * tear down on the next idle boundary.
 *
 * Compat surfaces:
 *  - [TransferServiceController] during each inbound session only (FGS reduces
 *    background kill risk; no separate “transfer done” notifications).
 *  - WakeLock held only while a transfer is *active* — released when the
 *    session ends.
 *  - WiFi-loss detection through [NetworkMonitor]; on network drop we tear
 *    down advertising, on network return we re-advertise.
 *
 * Event-flow lifecycle: the SharedFlow's replay cache is reset every time
 * [start] is called so a fresh ViewModel doesn't see stale Done/Failed events
 * from a prior session.
 */
class ReceiveRepository(
    private val appContext: Context,
    private val settings: SettingsRepository,
) {

    private val errorHandler = CoroutineExceptionHandler { _, t ->
        if (t is CancellationException) return@CoroutineExceptionHandler
        Log.w(SCOPE, "uncaught in receive scope", t)
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + errorHandler)
    private var server: TcpServer? = null
    private var advertiser: MdnsAdvertiser? = null
    private var pendingDecision: CompletableDeferred<Boolean>? = null
    private var localEndpoint: LocalEndpoint? = null

    private val wakeLock = WakeLockHolder(appContext, "Receive")
    private val networkMonitor = NetworkMonitor(appContext)
    private var networkJob: Job? = null
    private var bleListenerJob: Job? = null

    /** True after an inbound socket completes the Quick Share handshake. */
    private val inboundSessionActive = AtomicBoolean(false)

    /**
     * When the user backgrounds the app during an active receive, we finish the
     * session then call [stop].
     */
    private val stopReceiveAfterInboundSession = AtomicBoolean(false)

    /**
     * Set when [onApplicationBackgrounded] stopped an **idle** listener. Used so
     * [ReceiveViewModel.restartPipelineIfRunning] can rebuild only in that case —
     * not while a transfer is active, and not on every `ON_START` replay.
     */
    private val restartReceiveAfterForeground = AtomicBoolean(false)

    private val _events = MutableSharedFlow<ReceiveEvent>(replay = 1, extraBufferCapacity = 64)
    fun events(): Flow<ReceiveEvent> = _events.asSharedFlow()

    /**
     * Process went to background (no activity in foreground). Idle listening is
     * torn down immediately; an active inbound transfer runs to completion first.
     */
    fun onApplicationBackgrounded() {
        if (!isListening()) return
        if (!inboundSessionActive.get()) {
            Log.i(SCOPE, "App backgrounded — stopping idle receive listener")
            restartReceiveAfterForeground.set(true)
            stop()
        } else {
            Log.i(SCOPE, "App backgrounded mid-transfer — listener will stop after session ends")
            stopReceiveAfterInboundSession.set(true)
        }
    }

    /** True while a real inbound Quick Share session is active. */
    fun isInboundSessionActive(): Boolean = inboundSessionActive.get()

    private fun isListening(): Boolean =
        server != null || advertiser != null || bleListenerJob != null || networkJob != null

    /**
     * If we stopped the idle listener in [onApplicationBackgrounded], returns true once
     * so the UI can call [start] again after foregrounding.
     */
    fun consumeRestartAfterForeground(): Boolean = restartReceiveAfterForeground.getAndSet(false)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun start(): Flow<ReceiveEvent> {
        stop()
        _events.resetReplayCache()

        val srv = TcpServer().also { server = it }
        val port = srv.bind(0)

        val endpoint = buildLocalEndpoint()
        localEndpoint = endpoint

        val adv = MdnsAdvertiser(appContext)
        adv.start(port, endpoint)
        advertiser = adv

        _events.tryEmit(ReceiveEvent.Listening)
        restartReceiveAfterForeground.set(false)

        networkJob = scope.launch {
            networkMonitor.observe().collect { status ->
                when (status) {
                    NetworkMonitor.Status.Disconnected -> {
                        Log.w(SCOPE, "Network lost — pausing advertising")
                        runCatching { advertiser?.stop() }
                        advertiser = null
                    }
                    is NetworkMonitor.Status.Connected -> {
                        if (advertiser == null && server != null) {
                            Log.i(SCOPE, "Network back — re-advertising")
                            val a = MdnsAdvertiser(appContext)
                            runCatching { a.start(port, endpoint) }
                                .onSuccess { advertiser = a }
                                .onFailure { Log.w(SCOPE, "re-advertise failed", it) }
                        }
                    }
                }
            }
        }

        // BLE wake-up. Mirrors `rqs_lib::hdl::ble`: passive scan for
        // Quick Share's FE2C beacon. When we hear it we re-announce the
        // mDNS service so a phone whose discovery started *after* our
        // mDNS register still finds us. Permissions / hardware missing
        // here is non-fatal — discovery just falls back to the JmDNS
        // 60-second tick.
        bleListenerJob = scope.launch {
            // `BleListener` logs its own Started / Unavailable transitions
            // — we only need to react to NearbySharing (the wake-up nudge).
            BleListener(appContext).observe().collect { event ->
                if (event is BleListener.Event.NearbySharing) {
                    Log.i(
                        SCOPE,
                        "Nearby sender (${event.deviceAddress ?: "?"}, " +
                            "rssi=${event.rssi}dBm) — re-announcing mDNS",
                    )
                    runCatching { advertiser?.reRegister() }
                        .onFailure { Log.w(SCOPE, "mDNS re-register failed", it) }
                }
            }
        }

        scope.launch {
            srv.accepted().collect { socket ->
                val autoAccept = settings.autoAcceptFlow.first()
                val pkcs8 = settings.qrHandshakePrivateKeyPkcs8Flow.first()
                val qrHandshakeKey = if (autoAccept && pkcs8 != null && pkcs8.isNotEmpty()) {
                    runCatching {
                        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
                    }.getOrElse { t ->
                        Log.w(SCOPE, "Bad stored QR handshake key — cannot sign PairedKeyEncryption", t)
                        null
                    }
                } else {
                    null
                }
                Log.i(
                    SCOPE,
                    "Inbound connection from ${socket.remoteSocketAddress} " +
                        "(qrSign=${qrHandshakeKey != null}, autoAccept=$autoAccept)",
                )

                val session = ReceiverSession(
                    sinkFactory = StorageAccess.fileSinkFactory(appContext),
                    local = endpoint,
                    acceptDecision = { _ ->
                        val gate = CompletableDeferred<Boolean>()
                        pendingDecision = gate
                        gate.await()
                    },
                    autoAcceptIncoming = autoAccept,
                    requestRejectedMessage = appContext.getString(R.string.receive_request_rejected),
                    qrHandshakePrivateKey = qrHandshakeKey,
                )
                val transferStarted = AtomicBoolean(false)
                val eventsJob = launch {
                    session.events.collect { event ->
                        if (event is ReceiveEvent.Handshaked && transferStarted.compareAndSet(false, true)) {
                            wakeLock.acquire()
                            inboundSessionActive.set(true)
                            TransferServiceController.start(
                                context = appContext,
                                title = appContext.getString(R.string.fgs_notification_title),
                                text = appContext.getString(R.string.fgs_notification_receiving),
                            )
                        }
                        _events.emit(event)
                    }
                }
                var sessionCancelled = false
                try {
                    session.run(socket)
                } catch (e: CancellationException) {
                    sessionCancelled = true
                    throw e
                } catch (t: Throwable) {
                    Log.w(SCOPE, "Receiver session escaped its own catch", t)
                } finally {
                    eventsJob.cancel()
                    pendingDecision?.cancel()
                    pendingDecision = null
                    val hadActiveTransfer = transferStarted.get()
                    if (hadActiveTransfer) {
                        wakeLock.release()
                        inboundSessionActive.set(false)
                        TransferServiceController.stop(appContext)
                    }
                    if (hadActiveTransfer && !sessionCancelled && stopReceiveAfterInboundSession.compareAndSet(true, false)) {
                        Log.i(SCOPE, "Inbound session finished after background — stopping listener")
                        stop()
                    }
                }
            }
        }
        return _events.asSharedFlow()
    }

    fun respondToOffer(accept: Boolean) {
        Log.i(SCOPE, "User ${if (accept) "accepted" else "rejected"} incoming transfer")
        pendingDecision?.complete(accept)
        pendingDecision = null
    }

    fun stop() {
        scope.coroutineContext.cancelChildren()
        networkJob = null
        bleListenerJob = null
        runCatching { advertiser?.stop() }
        runCatching { server?.close() }
        advertiser = null
        server = null
        localEndpoint = null
        pendingDecision?.cancel()
        pendingDecision = null
        wakeLock.release()
        stopReceiveAfterInboundSession.set(false)
        TransferServiceController.stop(appContext)
    }

    private suspend fun buildLocalEndpoint(): LocalEndpoint {
        val endpointId = ServiceNameCodec.newEndpointId()
        // SettingsRepository.deviceNameFlow already resolves the system
        // device name and falls back to Build.MODEL internally; we don't
        // need a second fallback here.
        val displayName = settings.deviceNameFlow.first()
        val info = EndpointInfo(displayName, EndpointInfo.DeviceType.LAPTOP)
        return LocalEndpoint(
            endpointId   = endpointId,
            endpointName = info.deviceName,
            endpointInfo = info.encode(),
        )
    }

    companion object { private const val SCOPE = "ReceiveRepo" }
}
