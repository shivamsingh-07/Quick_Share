package com.quickshare.tv.data.repository

import android.content.Context
import android.net.Uri
import com.quickshare.tv.R
import com.quickshare.tv.crypto.QrCodeKey
import com.quickshare.tv.data.service.TransferServiceController
import com.quickshare.tv.crypto.QrPeerMatcher
import com.quickshare.tv.domain.model.DeviceKind
import com.quickshare.tv.domain.model.DiscoveredDevice
import com.quickshare.tv.domain.model.FileMeta
import com.quickshare.tv.domain.model.LocalEndpoint
import com.quickshare.tv.domain.model.Peer
import com.quickshare.tv.domain.model.SendEvent
import com.quickshare.tv.network.EndpointInfo
import com.quickshare.tv.network.ServiceNameCodec
import com.quickshare.tv.network.ble.BleAdvertiser
import com.quickshare.tv.network.ble.BleCorrelator
import com.quickshare.tv.network.ble.BleListener
import com.quickshare.tv.network.mdns.MdnsDiscoverer
import com.quickshare.tv.network.tcp.TcpClient
import com.quickshare.tv.protocol.SenderSession
import com.quickshare.tv.qr.QrPngRenderer
import com.quickshare.tv.system.BluetoothMonitor
import com.quickshare.tv.system.StorageAccess
import com.quickshare.tv.system.WakeLockHolder
import com.quickshare.tv.util.Log
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * "Send from TV" flow.
 *
 * Two complementary entry points:
 *
 *  - [startDevicePicker] — the **default** (post-file-pick) flow. Spins up
 *    mDNS discovery while broadcasting the same FE2C BLE wake-up beacon
 *    rqs_lib uses. Discovered receivers come strictly from mDNS and are
 *    exposed via [devices] so the UI can render a "Nearby Devices" picker.
 *    The user then taps a device → [selectDevice] → TCP+UKEY2+SenderSession.
 *
 *  - [useQrInstead] — fallback path the picker triggers via its "Use QR"
 *    button. Cancels the picker scan, generates the QR (with QrCodeKey),
 *    runs the strict QR-token-matched discovery, and connects to whichever
 *    peer scans our code. Identical to the original v0.1 behaviour, just
 *    triggered explicitly instead of being the default.
 *
 * In both flows the *post-connect* pipeline is the same — same UKEY2,
 * same `SenderSession`, same `_events` SharedFlow, same wake-lock and
 * foreground-service handling — so the UI sees identical lifecycle
 * events regardless of how the peer was chosen.
 *
 * # Lifecycle
 *
 * - [startDevicePicker] cancels any prior session before starting.
 * - [selectDevice] / [useQrInstead] cancel the active picker scan.
 * - [stop] cancels everything, releases the wake lock, drops the FGS.
 * - [onApplicationBackgrounded]: cancels the pipeline unless we're
 *   actively streaming bytes — in that case we let `SenderSession`
 *   finish so a backgrounded transfer doesn't truncate.
 *
 * While [SenderSession.run] streams payload bytes,
 * [TransferServiceController] runs a foreground service so the OS
 * doesn't reap us during the transfer (low-importance / silent
 * notification; no separate "transfer done" toast).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SendRepository(
    private val appContext: Context,
    private val settings: SettingsRepository,
) {

    private val errorHandler = CoroutineExceptionHandler { _, t ->
        if (t is CancellationException) return@CoroutineExceptionHandler
        Log.w(SCOPE, "uncaught in send scope", t)
    }

    /**
     * Per-session scope. Owns the picker job and every transient
     * coroutine spawned by [startDevicePicker] / [useQrInstead] /
     * [runConnectAndSend]. [stop] tears every child of this scope
     * down — that's the contract callers rely on.
     *
     * Crucially, this is NOT the same scope as [observerScope] below:
     * see that field's docs for why bluetooth observation lives in
     * its own scope.
     */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + errorHandler)

    /**
     * Long-lived scope for cross-session observers (currently just
     * [bluetooth]). Lives for the full lifetime of the repository
     * because [stop] calls `scope.cancelChildren()`, which would
     * otherwise unregister the BroadcastReceiver inside
     * `BluetoothMonitor.observe` and silently freeze the StateFlow
     * at its last value. Without this split, every fresh
     * [startDevicePicker] (which begins by calling [stop]) would
     * leave the picker UI blind to subsequent Bluetooth toggles.
     */
    private val observerScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + errorHandler)
    private val wakeLock = WakeLockHolder(appContext, "Send")
    private val rng = SecureRandom()

    /**
     * Time-windowed cache of FE2C BLE beacons heard during the active
     * picker / QR session. Used purely as a corroboration signal — when
     * an mDNS row resolves but no BLE beacon arrived in the last few
     * seconds, the row is more likely to be a stale advert from a
     * sleeping device. We log the correlation but don't currently use
     * it to filter the picker (would risk hiding peers on TV chipsets
     * that simply can't deliver BLE scan callbacks).
     *
     * Cleared on [stop] / new session start.
     */
    private val bleCorrelator = BleCorrelator()

    /** True only during [SenderSession.run] (payload streaming). */
    private val sendStreamingActive = AtomicBoolean(false)

    private val _events = MutableSharedFlow<SendEvent>(replay = 1, extraBufferCapacity = 32)
    fun events(): Flow<SendEvent> = _events.asSharedFlow()

    /**
     * Live picker state. Reset to empty whenever a new picker starts
     * ([startDevicePicker]) and whenever discovery is torn down
     * ([selectDevice], [useQrInstead], [stop]). Sorted alphabetically
     * by display name so the UI doesn't need to re-sort per-update.
     */
    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    /**
     * Live Bluetooth readiness. The picker job reads it to decide whether to
     * spin up the BLE wake-up advertiser, and the UI uses the same
     * ready/not-ready snapshot for its empty state.
     *
     * Backed by a [MutableStateFlow] (rather than a simple `stateIn`) so that
     * [onApplicationForegrounded] can force a fresh re-evaluation when the
     * app returns from the system permission dialog. Without this,
     * `ACTION_STATE_CHANGED` never fires when permissions are *granted* while
     * BT is already on, leaving the StateFlow permanently stuck at NOT_READY
     * for the rest of the session.
     */
    private val _bluetooth = MutableStateFlow(BluetoothMonitor.current(appContext))
    val bluetooth: StateFlow<BluetoothMonitor.State> = _bluetooth.asStateFlow()

    init {
        // Forward ACTION_STATE_CHANGED updates into _bluetooth. Runs on
        // observerScope (not scope) so stop()'s cancelChildren() doesn't
        // unregister the BroadcastReceiver and freeze the StateFlow.
        observerScope.launch {
            BluetoothMonitor.observe(appContext).collect { state ->
                _bluetooth.value = state
            }
        }
    }

    /**
     * Snapshot of every payload we've prepared for the *current*
     * session — the file metas (sent in the IntroductionFrame) and the
     * URI map used to open input streams during streaming. Populated
     * by [startDevicePicker] and consumed by [selectDevice] /
     * [useQrInstead]. Cleared on [stop].
     */
    private data class PreparedPayload(
        val files: List<FileMeta>,
        val uriByPayload: Map<Long, Uri>,
        val context: Context,
    )
    private var prepared: PreparedPayload? = null

    /**
     * Every mDNS peer currently active during the picker scan,
     * keyed by the same `id` we expose to the UI as
     * [DiscoveredDevice.id]. Lets [selectDevice] turn a UI tap into a
     * `(host, port)` without re-running discovery.
     *
     * Purged on every fresh [startDevicePicker] / [stop].
     */
    private val mdnsPeerIndex = mutableMapOf<String, MdnsDiscoverer.DiscoveredPeer>()
    private val mdnsPeerIndexMutex = Mutex()

    /** Picker rows currently visible in the Send device scan area. */
    private val pickerDeviceIndex = mutableMapOf<String, DiscoveredDevice>()

    private var pickerJob: Job? = null

    /**
     * Set in [onApplicationBackgrounded] when we paused (rather than
     * tore down) an active picker because the user briefly left the
     * app — typically to flip Bluetooth on from the system overlay.
     * Consumed by [onApplicationForegrounded] to re-launch discovery
     * for the still-prepared payload without losing the picked files.
     */
    private val backgroundedWhilePicking = AtomicBoolean(false)

    /**
     * Process moved to background.
     *
     *  - Streaming → let `SenderSession` finish (truncating an in-flight
     *    transfer would corrupt the receiver's file).
     *  - Picking with a prepared payload → just **pause** discovery
     *    (cancel `pickerJob`, drop the multicast lock + BLE advertiser)
     *    but keep `prepared` and the device list intact. This is what
     *    rescues the "user opens BT enable dialog mid-picker" flow:
     *    on return, [onApplicationForegrounded] re-launches discovery
     *    against the same prepared payload. Without this, the dialog
     *    wiping `prepared` made "Use QR" silently no-op.
     *  - Idle (no prepared payload) → full [stop] as before.
     */
    fun onApplicationBackgrounded() {
        if (sendStreamingActive.get()) {
            Log.i(SCOPE, "App backgrounded mid-transfer — letting SenderSession finish")
            return
        }
        if (prepared != null) {
            Log.i(SCOPE, "App backgrounded with prepared payload — pausing discovery")
            pickerJob?.cancel()
            pickerJob = null
            backgroundedWhilePicking.set(true)
            // Intentionally NOT clearing `_devices` / `_events` so the
            // UI keeps its state across the brief background trip.
            return
        }
        Log.d(SCOPE) { "App backgrounded while idle — full stop" }
        stop()
    }

    /**
     * Process moved to foreground. If we paused a picker session in
     * [onApplicationBackgrounded], rebuild discovery against the same
     * prepared payload so the user lands back on a live picker rather
     * than a frozen one.
     *
     * No-op when there's nothing to resume (cold start, idle teardown,
     * or a transfer was actively streaming and survived the trip).
     */
    fun onApplicationForegrounded() {
        // Always re-evaluate BT state on foreground — catches the first-launch
        // case where BLE runtime permissions were just granted in the system
        // dialog. Permission grants never fire ACTION_STATE_CHANGED, so the
        // BroadcastReceiver-based observer can't detect them on its own.
        _bluetooth.value = BluetoothMonitor.current(appContext)

        if (!backgroundedWhilePicking.compareAndSet(true, false)) return
        if (prepared == null) return
        if (pickerJob?.isActive == true) return
        Log.i(SCOPE, "App foregrounded — resuming picker discovery")
        pickerJob = scope.launch { runPickerDiscovery() }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Device-picker entry point  (default flow)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Default Send entry. Persists URI read perms, builds file metas,
     * and starts mDNS discovery + the rqs-style BLE wake-up advertisement.
     * The UI watches [devices] and
     * triggers either [selectDevice] or [useQrInstead].
     *
     * Calling this with the same URI list while a picker is already
     * running re-uses the existing session — we DO NOT cancel and
     * re-prepare on every recomposition.
     */
    suspend fun startDevicePicker(context: Context, uris: List<Uri>): Flow<SendEvent> {
        // Idempotency guard: a recompose during the picker (or while
        // we're connecting) shouldn't tear the in-progress flow down.
        if (prepared != null && pickerJob?.isActive == true) {
            return _events.asSharedFlow()
        }
        stop()
        _events.resetReplayCache()
        _devices.value = emptyList()
        mdnsPeerIndexMutex.withLock {
            mdnsPeerIndex.clear()
            pickerDeviceIndex.clear()
        }

        uris.forEach { StorageAccess.persistRead(context, it) }
        val payloadByUri = uris.associateWith { nextPayloadId() }
        val files = uris.map { uri ->
            val (name, size) = StorageAccess.queryNameAndSize(context, uri)
            FileMeta(
                payloadId = payloadByUri.getValue(uri),
                name = name,
                size = size,
                mimeType = context.contentResolver.getType(uri) ?: DEFAULT_MIME_TYPE,
            )
        }
        prepared = PreparedPayload(
            files = files,
            uriByPayload = payloadByUri.entries.associate { (uri, id) -> id to uri },
            context = context,
        )

        pickerJob = scope.launch { runPickerDiscovery() }

        return _events.asSharedFlow()
    }

    /**
     * Body of [pickerJob]: spin up mDNS discovery and the BLE wake-up
     * advertiser. Extracted so [onApplicationForegrounded] can re-launch
     * the same discovery pipeline against an already-prepared payload
     * without going through the full [startDevicePicker] file-prep
     * dance again (which would clobber `_events` and `_devices`).
     */
    private suspend fun runPickerDiscovery() = coroutineScope {
        bleCorrelator.clear()
        // mDNS branch — same DiscoveredPeer source the QR matcher
        // uses, but we map every peer through `register`/index it
        // by instance name and surface to the UI without filtering.
        val mdns = MdnsDiscoverer(appContext).observeEvents()
        val mdnsCollect = launch {
            mdns.collect { event ->
                when (event) {
                    is MdnsDiscoverer.DiscoveryEvent.Found -> {
                        logBleCorrelationFor(event.peer, label = "picker")
                        onMdnsPeer(event.peer)
                    }
                    is MdnsDiscoverer.DiscoveryEvent.Lost -> onMdnsPeerLost(event.instanceName)
                }
            }
        }

        // BLE advertiser branch — mirrors rqs_lib::hdl::blea: broadcast
        // a FE2C beacon so Android Quick Share receivers wake/refresh
        // their Wi-Fi LAN mDNS service. Does not replace mDNS and does
        // not transfer bytes; it only nudges discovery.
        //
        // Gated on `bluetooth` so we don't spin up an advertiser the
        // OS will instantly reject when the adapter is off / missing.
        // The flow re-runs whenever `bluetooth` flips back to READY,
        // so the user toggling Bluetooth on from system settings
        // mid-picker resumes the wake-up beacon without a restart.
        val bleAdvertiseCollect = launch {
            // `collectLatest` (not `collect`) so that a fresh
            // bluetooth state cancels any in-flight advertiser
            // collect block before starting the next one. Without
            // this, the inner `BleAdvertiser.observe().collect`
            // would block the outer collect forever and we'd never
            // notice the user toggling Bluetooth back on.
            // `BleAdvertiser` logs its own Started/Unavailable transitions
            // at INFO/WARN — we only need to react here, not echo them.
            bluetooth.collectLatest { state ->
                if (state != BluetoothMonitor.State.READY) {
                    Log.d(SCOPE) { "Skipping BLE wake-up beacon — bluetooth=$state" }
                    return@collectLatest
                }
                BleAdvertiser(appContext).observe().collect { /* logged inside */ }
            }
        }

        // BLE listener branch — passive scanner that watches for FE2C
        // beacons from peers actively trying to share. We feed each
        // event into [bleCorrelator] so resolved mDNS rows can be
        // tagged "BLE-corroborated" (active sharer right now) vs
        // "BLE-silent" (likely-stale mDNS advert). Failures here are
        // silent — the listener already logs its own permission /
        // hardware reasons and discovery still works mDNS-only.
        val bleListenerCollect = launch {
            bluetooth.collectLatest { state ->
                if (state != BluetoothMonitor.State.READY) return@collectLatest
                BleListener(appContext).observe().collect { event ->
                    if (event is BleListener.Event.NearbySharing) bleCorrelator.record(event)
                }
            }
        }

        // The picker job stays alive until cancelled by selectDevice,
        // useQrInstead, stop, or onApplicationBackgrounded. No timeout
        // — the user owns the "give up" decision via the Back gesture
        // or "Use QR".
        try {
            awaitJobs(mdnsCollect, bleAdvertiseCollect, bleListenerCollect)
        } catch (_: CancellationException) {
            // expected on selectDevice / useQrInstead / stop / pause
        }
    }

    /**
     * Emit a single one-line diagnostic correlating a freshly resolved
     * mDNS peer against the recent BLE-beacon window. We never block on
     * the correlator (it's an in-memory snapshot) and never fail the
     * pipeline if there's no BLE evidence — many TV chipsets simply
     * never deliver BLE scan callbacks, so absence is not proof of
     * staleness. The log line gives us a fingerprint to match against
     * stock Quick Share's behaviour without changing pipeline flow.
     */
    private fun logBleCorrelationFor(
        peer: MdnsDiscoverer.DiscoveredPeer,
        label: String,
    ) {
        val snap = bleCorrelator.snapshot()
        if (snap.isEmpty) {
            Log.d(SCOPE) {
                "[$label] mDNS resolved '${peer.endpointInfo.deviceName}' " +
                    "(${peer.host.hostAddress}) — ${bleCorrelator.describe(snap)}"
            }
        } else {
            Log.i(
                SCOPE,
                "[$label] mDNS resolved '${peer.endpointInfo.deviceName}' " +
                    "(${peer.host.hostAddress}) — corroborated by " +
                    bleCorrelator.describe(snap),
            )
        }
    }

    /**
     * Pick a device from the picker list and run the rest of the send
     * pipeline against its mDNS-resolved endpoint.
     *
     * No-op if the device id is unknown — guarded because picker state
     * can race with the click (e.g. the row's underlying mDNS service
     * was retracted between render and tap).
     */
    fun selectDevice(deviceId: String) {
        val payload = prepared ?: run {
            Log.w(SCOPE, "selectDevice($deviceId) without a prepared payload — ignoring")
            return
        }
        scope.launch {
            val peer = mdnsPeerIndexMutex.withLock { mdnsPeerIndex[deviceId] }
            if (peer == null) {
                Log.w(SCOPE, "selectDevice($deviceId): unknown id (picker race?)")
                return@launch
            }
            Log.i(
                SCOPE,
                "User picked '${peer.endpointInfo.deviceName}' " +
                    "(${peer.host.hostAddress}:${peer.port}, ${payload.files.size} file(s))",
            )
            // Tearing down the picker BEFORE we connect prevents
            // double-emission of the same peer + frees the multicast
            // lock + stops the BLE wake-up advertisement, which reduces on-air
            // contention while UKEY2 is bringing up the TLS-ish channel.
            pickerJob?.cancel()
            pickerJob = null
            _devices.value = emptyList()
            mdnsPeerIndexMutex.withLock {
                mdnsPeerIndex.clear()
                pickerDeviceIndex.clear()
            }

            // mDNS picker → no QR signing key. The receiver phone will
            // show its own confirmation dialog because there's no signed
            // QR proof in our PairedKeyEncryption frame. This is what we
            // want: the user picked us from a list of nearby devices,
            // they have not authenticated us, so the phone *must* prompt.
            runConnectAndSend(payload, peer, qrHandshakeKey = null)
        }
    }

    /**
     * Switch from picker → QR flow. Cancels the active mDNS+BLE scan,
     * generates a fresh QR key, broadcasts the QR PNG to the UI,
     * and runs the strict QR-matched mDNS pipeline so the receiver
     * that scans our code wins automatically.
     */
    fun useQrInstead() {
        val payload = prepared ?: run {
            Log.w(SCOPE, "useQrInstead() without a prepared payload — ignoring")
            return
        }
        Log.i(SCOPE, "User switched to QR pairing (${payload.files.size} file(s))")
        pickerJob?.cancel()
        pickerJob = null
        _devices.value = emptyList()
        scope.launch {
            mdnsPeerIndexMutex.withLock {
                mdnsPeerIndex.clear()
                pickerDeviceIndex.clear()
            }
        }

        scope.launch { runQrPipeline(payload) }
    }

    // ─────────────────────────────────────────────────────────────────
    //  QR-only entry point  (kept for tests and as the QR pipeline)
    // ─────────────────────────────────────────────────────────────────

    fun stop() {
        scope.coroutineContext.cancelChildren()
        wakeLock.release()
        TransferServiceController.stop(appContext)
        prepared = null
        pickerJob = null
        backgroundedWhilePicking.set(false)
        _devices.value = emptyList()
        bleCorrelator.clear()
        scope.launch {
            mdnsPeerIndexMutex.withLock {
                mdnsPeerIndex.clear()
                pickerDeviceIndex.clear()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Picker-side merge logic
    // ─────────────────────────────────────────────────────────────────

    /**
     * Fold a freshly-resolved mDNS peer into the picker list. Stable
     * id is the mDNS instance name — re-resolves of the same service
     * just refresh the row in place.
     */
    private suspend fun onMdnsPeer(peer: MdnsDiscoverer.DiscoveredPeer) {
        val device = peer.toDiscoveredDevice()
        mdnsPeerIndexMutex.withLock {
            val staleKeys = mdnsPeerIndex
                .filter { (instanceName, existing) ->
                    instanceName != peer.instanceName && existing.isSameVisibleDeviceAs(peer)
                }
                .keys
                .toList()
            staleKeys.forEach { instanceName ->
                mdnsPeerIndex.remove(instanceName)
                pickerDeviceIndex.remove(instanceName)
            }
            mdnsPeerIndex[peer.instanceName] = peer
            pickerDeviceIndex[peer.instanceName] = device
        }
        refreshDevices()
    }

    private suspend fun onMdnsPeerLost(instanceName: String) {
        mdnsPeerIndexMutex.withLock {
            mdnsPeerIndex.remove(instanceName)
            pickerDeviceIndex.remove(instanceName) ?: return@withLock
        }
        refreshDevices()
    }

    /**
     * Recompute [_devices] from the current [mdnsPeerIndex] snapshot
     * whenever mDNS resolves or refreshes a peer.
     */
    private suspend fun refreshDevices() {
        val devices = mdnsPeerIndexMutex.withLock { pickerDeviceIndex.values.toList() }
        _devices.value = devices
            .dedupAndSort()
    }

    /**
     * Map a fully-resolved mDNS peer to a picker row.
     *
     * `MdnsDiscoverer.observe(requireVisibleName=true)` already filters
     * out anonymous (17-byte hidden) records, so by the time we get
     * here the peer is guaranteed to carry a usable visible name. No
     * cosmetic "Phone" / "Tablet" placeholder is needed.
     */
    private fun MdnsDiscoverer.DiscoveredPeer.toDiscoveredDevice(): DiscoveredDevice =
        DiscoveredDevice(
            id = instanceName,
            displayName = endpointInfo.deviceName.trim(),
            kind = endpointInfo.deviceType.toDeviceKind(),
        )

    private fun EndpointInfo.DeviceType.toDeviceKind(): DeviceKind = when (this) {
        EndpointInfo.DeviceType.PHONE   -> DeviceKind.PHONE
        EndpointInfo.DeviceType.TABLET  -> DeviceKind.TABLET
        EndpointInfo.DeviceType.LAPTOP  -> DeviceKind.LAPTOP
        EndpointInfo.DeviceType.UNKNOWN -> DeviceKind.UNKNOWN
    }

    /**
     * Stock Android Quick Share can re-publish the same visible device under a
     * fresh mDNS instance name after each BLE wake-up. JmDNS may delay the old
     * serviceRemoved event, so coalesce by visible identity before the UI sees
     * two rows for one phone.
     */
    private fun MdnsDiscoverer.DiscoveredPeer.isSameVisibleDeviceAs(
        other: MdnsDiscoverer.DiscoveredPeer,
    ): Boolean =
        host == other.host &&
            endpointInfo.deviceType == other.endpointInfo.deviceType &&
            endpointInfo.deviceName.trim().equals(
                other.endpointInfo.deviceName.trim(),
                ignoreCase = true,
            )

    /** Dedup by UI id and sort case-insensitively. */
    private fun List<DiscoveredDevice>.dedupAndSort(): List<DiscoveredDevice> =
        distinctBy { it.id }.sortedBy { it.displayName.lowercase() }

    // ─────────────────────────────────────────────────────────────────
    //  Pipelines  (post-pick / QR)
    // ─────────────────────────────────────────────────────────────────

    private suspend fun runQrPipeline(payload: PreparedPayload) {
        Log.d(SCOPE) { "QR pipeline starting (${payload.files.size} file(s))" }
        // QR-key generation, base64 encoding, and PNG rendering
        // are all CPU-only crypto / encoding paths — but they're also the
        // exact spot where a JCE provider hiccup (P-256 unavailable, BC
        // gone missing, etc.) or a bad allocation in the QR renderer
        // would surface. Without this guard the throw lands in
        // [errorHandler], which only logs, leaving the UI stuck on the
        // blank "Generating QR..." card forever because no `QrReady` /
        // `Failed` event ever reaches the VM.
        val qrKey: QrCodeKey
        val pngBytes: ByteArray
        try {
            qrKey = QrCodeKey.generate()
            runCatching { settings.setQrHandshakePrivateKeyPkcs8(qrKey.keyPair.private.encoded) }
                .onFailure { Log.w(SCOPE, "Could not persist QR key for receive-side handshake", it) }
            pngBytes = QrPngRenderer.render(context = appContext, url = qrKey.url)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(SCOPE, "QR generation failed", t)
            _events.tryEmit(SendEvent.Failed(t))
            return
        }
        val matcher = QrPeerMatcher(qrKey)
        _events.tryEmit(SendEvent.QrReady(qrKey.url, pngBytes))
        Log.i(SCOPE, "QR ready — awaiting receiver scan")

        val peer = try {
            pickQrPeer(matcher)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(SCOPE, "QR discovery failed", t)
            _events.tryEmit(SendEvent.Failed(t))
            return
        }
        if (peer == null) {
            _events.tryEmit(SendEvent.Failed(IllegalStateException("No nearby receiver found")))
            return
        }
        val peerWithQrName = matcher.decryptHiddenName(peer.endpointInfo)?.let { hiddenName ->
            peer.copy(endpointInfo = peer.endpointInfo.copy(deviceName = hiddenName))
        } ?: peer
        // QR flow → always sign. The user already authenticated us by
        // scanning the QR with their phone, so the receiver should skip
        // its confirmation dialog. This is independent of the
        // receiver-side "auto accept" setting (which only affects
        // *incoming* transfers to this TV).
        runConnectAndSend(payload, peerWithQrName, qrHandshakeKey = qrKey.privateKey)
    }

    /**
     * Connect to a chosen mDNS peer, run UKEY2, and stream the
     * prepared payload via [SenderSession].
     *
     * [qrHandshakeKey] decides whether the receiver phone has to
     * confirm the transfer:
     *   - `null` (picker path) → no signed proof in PairedKeyEncryption,
     *     so the phone shows its standard confirmation dialog.
     *   - non-null (QR path)   → we sign the UKEY2 auth string with the
     *     QR EC key, the phone verifies it against the public key it
     *     scanned, and skips the confirmation prompt.
     *
     * The receiver-side "auto accept" setting in our own settings page
     * is intentionally NOT consulted here — that toggle only governs
     * how *we* handle incoming files when this TV is the receiver.
     */
    private suspend fun runConnectAndSend(
        payload: PreparedPayload,
        peer: MdnsDiscoverer.DiscoveredPeer,
        qrHandshakeKey: java.security.PrivateKey?,
    ) {
        val context = payload.context
        val files = resolveActualSizes(context, payload)
        val uriByPayload = payload.uriByPayload

        var collectJob: Job? = null
        try {
            // Picker filters anonymous records; QR mode requires a
            // token-matched record from a real scan. QR records can still
            // be anonymous, so keep a display-name fallback for the
            // connecting/status UI only.
            val displayName = peer.endpointInfo.deviceName.trim().ifEmpty {
                peer.endpointInfo.deviceType.name.lowercase().replaceFirstChar(Char::uppercase)
            }
            val domainPeer = Peer(
                displayName,
                peer.endpointInfo.deviceType.name,
                peer.host.hostAddress ?: "",
                peer.port,
            )
            _events.emit(SendEvent.Connecting(domainPeer))

            val socket = connectWithRetry(peer.host, peer.port)
            wakeLock.acquire()
            val totalBytes = files.sumOf { it.size.coerceAtLeast(0L) }
            Log.i(
                SCOPE,
                "Sending ${files.size} file(s) (${totalBytes}B total) to '$displayName' " +
                    "via ${qrHandshakeKey?.let { "QR" } ?: "picker"}",
            )

            val local = buildLocalEndpoint()
            val session = SenderSession(
                files = files,
                openFile = { meta ->
                    val uri = uriByPayload[meta.payloadId]
                        ?: error("No Uri mapped to payloadId=${meta.payloadId}")
                    val stream: InputStream = StorageAccess.openInputStream(context, uri)
                    stream to meta.size
                },
                qrHandshakePrivateKey = qrHandshakeKey,
            )
            collectJob = scope.launch { session.events.collect(_events::emit) }
            sendStreamingActive.set(true)
            try {
                TransferServiceController.start(
                    context = appContext,
                    title = appContext.getString(R.string.fgs_notification_title),
                    text = appContext.getString(R.string.fgs_notification_sending),
                )
                session.run(socket, local)
            } finally {
                sendStreamingActive.set(false)
                TransferServiceController.stop(appContext)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(SCOPE, "Send pipeline failed", t)
            _events.tryEmit(SendEvent.Failed(t))
        } finally {
            collectJob?.cancel()
            wakeLock.release()
        }
    }

    /**
     * QR-pairing peer picker.
     *
     *  - mDNS browse with `requireVisibleName = false` — the picker
     *    used to filter anonymous (17-byte hidden) records out, but on
     *    the QR path the act of scanning the QR is itself the user's
     *    consent: any device that token-matches IS the right device,
     *    even if it never publishes a visible name.
     *  - BLE FE2C wake-up advertiser — wakes the phone's mDNS responder
     *    so it publishes the matching TLV-1 token in its EndpointInfo.
     *  - BLE listener — feeds [bleCorrelator] so the "QR token matched"
     *    log line can record whether the same device was actively
     *    broadcasting FE2C right when we resolved it. Helpful for
     *    distinguishing "stock Quick Share is alive" from
     *    "mDNS row is stale and the share sheet was dismissed".
     *
     * Selection rule: prefer a peer whose TLV-1 either equals our 16-byte
     * advertising token or decrypts with our QR name-encryption key. As a
     * last compatibility fallback, any peer with TLV-1 QR evidence can
     * proceed too; if it is not verified, the receiver should reject our
     * QR signature and fall back to its normal confirmation dialog.
     *
     * There is intentionally no generic "first available peer" fallback
     * here: nearby anonymous mDNS peers can exist before anyone scans the
     * QR, and accepting one would start a transfer to the wrong device.
     *
     * # Latency note
     *
     * Discovery and BLE-side jobs are launched against the long-lived
     * [scope] (NOT a `coroutineScope { ... }` around this function), so
     * once we've identified a winning peer we can return immediately
     * while the JmDNS/multicast teardown completes asynchronously in
     * the background. Earlier code wrapped both in `coroutineScope`
     * which forced the caller to wait for JmDNS.close() — visible in
     * the field as a multi-second pause between the
     * `MdnsDisc Resolved …` log line and the `TcpClient Connected …`
     * log line.
     */
    private suspend fun pickQrPeer(matcher: QrPeerMatcher): MdnsDiscoverer.DiscoveredPeer? {
        bleCorrelator.clear()
        val discoverer = MdnsDiscoverer(appContext)
        val discovery = discoverer.observe(requireVisibleName = false)

        val picked = CompletableDeferred<MdnsDiscoverer.DiscoveredPeer?>()

        // Wake-up beacon — keeps the phone's mDNS responder alive long
        // enough to re-publish the matching TLV-1 record after scan.
        val ble = scope.launch {
            BleAdvertiser(appContext).observe().collect { /* logged inside */ }
        }
        // Passive scanner — feeds the correlator so the resolution log
        // can include "BLE corroborated" / "BLE silent" diagnostics.
        // Failures (no permission, BT off, no chip support) are logged
        // by BleListener itself and never propagate up here.
        val bleListener = scope.launch {
            BleListener(appContext).observe().collect { event ->
                if (event is BleListener.Event.NearbySharing) bleCorrelator.record(event)
            }
        }

        // Discovery collector lives on `scope` so its eventual JmDNS
        // teardown (multicast lock release, listener removal,
        // `JmDNS.close`) does NOT block us returning the picked peer.
        // Earlier code paid the entire teardown cost on the caller's
        // critical path, which surfaced as a multi-second gap before
        // TCP connect.
        val collector = scope.launch {
            try {
                discovery.collect { peer ->
                    if (matcher.hasVerifiedQrEvidence(peer.endpointInfo) ||
                        matcher.hasQrEvidence(peer.endpointInfo)
                    ) {
                        if (picked.complete(peer)) {
                            // Throwing CancellationException tears the
                            // upstream flow + JmDNS down asynchronously
                            // without us awaiting it.
                            throw CancellationException("QR peer selected")
                        }
                    }
                }
                // Flow finished without selecting (e.g. teardown). Make
                // sure the parent coroutine doesn't hang.
                picked.complete(null)
            } catch (e: CancellationException) {
                if (!picked.isCompleted) picked.complete(null)
                throw e
            } catch (t: Throwable) {
                if (!picked.isCompleted) picked.completeExceptionally(t)
            }
        }

        return try {
            val peer = picked.await()
            peer?.let { logQrSelection(matcher, it) }
            peer
        } finally {
            // Kick off cleanup and return — both jobs run against
            // [scope] so cancellation completes asynchronously.
            ble.cancel()
            bleListener.cancel()
            collector.cancel()
        }
    }

    /**
     * Log the per-strategy outcome for a winning QR peer + the BLE
     * correlation snapshot at the moment of selection. Keeping all the
     * formatting in one place makes it easier to grep
     * "QR token matched", "QR hidden record decrypted", and the
     * fallback case in field reports.
     */
    private fun logQrSelection(matcher: QrPeerMatcher, peer: MdnsDiscoverer.DiscoveredPeer) {
        val ei = peer.endpointInfo
        val bleSnap = bleCorrelator.snapshot()
        val bleDesc = bleCorrelator.describe(bleSnap)
        val hiddenName = matcher.decryptHiddenName(ei)
        when {
            matcher.matches(ei) -> Log.i(
                SCOPE,
                "QR token matched '${ei.deviceName}' at ${peer.host.hostAddress}:${peer.port} " +
                    "($bleDesc)",
            )
            hiddenName != null -> Log.i(
                SCOPE,
                "QR hidden record decrypted '$hiddenName' at " +
                    "${peer.host.hostAddress}:${peer.port} ($bleDesc)",
            )
            else -> Log.w(
                SCOPE,
                "QR evidence matched but exact token did not for " +
                    "'${ei.deviceName}' at ${peer.host.hostAddress}:${peer.port} " +
                    "(tlv=${ei.qrCodeData?.size ?: 0}B); " +
                    "continuing with receiver confirmation fallback ($bleDesc)",
            )
        }
    }

    /**
     * `TcpClient.connect` with bounded exponential backoff.
     *
     * Why: we've seen one-shot connect failures right after the picker
     * resolves a peer — typically `ECONNRESET` or `ECONNREFUSED` because
     * the phone's TCP listener wasn't quite up yet, or `ENETUNREACH`
     * during a Wi-Fi roam between mDNS resolve and connect. A short
     * retry loop turns those transient failures into a working transfer
     * without bothering the user.
     *
     * `CancellationException` is rethrown immediately so the user
     * cancelling the picker / closing the screen aborts the loop
     * instantly; everything else goes through the backoff schedule.
     *
     * Total worst-case wall time: 500 + 1000 + 2000 = 3.5 s of waiting
     * plus 3 × `TcpClient.CONNECT_TIMEOUT_MS` (8 s each) of probing.
     */
    private suspend fun connectWithRetry(host: java.net.InetAddress, port: Int): java.net.Socket {
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt < TCP_CONNECT_MAX_ATTEMPTS) {
            attempt++
            try {
                val socket = TcpClient.connect(host, port)
                if (attempt > 1) {
                    Log.i(SCOPE, "Connected to $host:$port on attempt $attempt")
                }
                return socket
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = t
                if (attempt == TCP_CONNECT_MAX_ATTEMPTS) break
                val delayMs = TCP_CONNECT_BASE_DELAY_MS shl (attempt - 1)
                Log.w(
                    SCOPE,
                    "TCP connect to $host:$port failed " +
                        "(attempt $attempt/$TCP_CONNECT_MAX_ATTEMPTS): " +
                        "${t.javaClass.simpleName}: ${t.message} — retrying in ${delayMs}ms",
                )
                delay(delayMs)
            }
        }
        throw lastError ?: IllegalStateException("connect to $host:$port failed")
    }

    /**
     * Re-probe the actual byte length of every prepared file just before
     * we send the Introduction frame. The picker-time
     * [StorageAccess.queryNameAndSize] already calls [OpenableColumns.SIZE]
     * + AssetFileDescriptor; this second pass catches the rare cloud /
     * FUSE provider that materialises the file lazily on first open
     * (`AFD.length` flips from `UNKNOWN_LENGTH` to the real count
     * between picker and send), and falls back to a one-shot byte count
     * when even AFD refuses.
     *
     * Sending an accurate size in the Introduction matters because:
     *  - rqs/NearDrop receivers preallocate based on the introduction
     *    size; a too-small value causes them to reject extra bytes,
     *  - stock Android Quick Share shows the introduction size in its
     *    confirmation dialog ("Receive 1 file (12.4 MB)?"); a `-1`
     *    renders as "unknown" which dampens user trust.
     *
     * The byte-count fallback is bounded ([StorageAccess.MAX_FALLBACK_COUNT_BYTES]
     * = 2 GiB) so a runaway provider can't pin the IO dispatcher; a
     * file larger than the cap reports `-1` and falls back to the same
     * "unknown size" introduction we used to send unconditionally.
     */
    private fun resolveActualSizes(
        context: Context,
        payload: PreparedPayload,
    ): List<FileMeta> = payload.files.map { meta ->
        val uri = payload.uriByPayload[meta.payloadId] ?: return@map meta
        val resolved = StorageAccess.resolveActualSize(
            context = context,
            uri = uri,
            cachedSize = meta.size,
            byteCountFallback = true,
        )
        if (resolved == meta.size) meta else {
            if (resolved < 0L) {
                Log.w(SCOPE, "Could not resolve actual size for '${meta.name}'; sending size=-1 in intro")
                meta
            } else {
                if (meta.size != resolved) {
                    Log.i(
                        SCOPE,
                        "Refined size for '${meta.name}': cached=${meta.size}B → actual=${resolved}B",
                    )
                }
                meta.copy(size = resolved)
            }
        }
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

    private fun nextPayloadId(): Long {
        var v: Long
        do { v = rng.nextLong() and Long.MAX_VALUE } while (v == 0L)
        return v
    }

    /**
     * Await all child jobs sequentially, in order. Each branch (mDNS
     * collect, BLE advertise, BLE listen) must complete before the
     * picker coroutine is considered done; cancellation of the outer
     * scope propagates to all of them simultaneously so they all stop
     * together regardless of join order.
     */
    private suspend fun awaitJobs(vararg jobs: Job) {
        jobs.forEach { it.join() }
    }

    companion object {
        private const val SCOPE = "SendRepo"
        private const val DEFAULT_MIME_TYPE = "application/octet-stream"

        /** Initial backoff before the second TCP-connect attempt. */
        private const val TCP_CONNECT_BASE_DELAY_MS = 500L

        /** Total TCP-connect attempts before propagating the failure. */
        private const val TCP_CONNECT_MAX_ATTEMPTS = 3
    }
}
