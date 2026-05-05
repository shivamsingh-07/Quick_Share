package com.quickshare.tv.protocol

import com.quickshare.tv.crypto.PinCode
import com.quickshare.tv.crypto.QrHandshake
import com.quickshare.tv.domain.model.DeviceKind
import com.quickshare.tv.domain.model.FileMeta
import com.quickshare.tv.domain.model.LocalEndpoint
import com.quickshare.tv.domain.model.PeerIdentity
import com.quickshare.tv.domain.model.ReceiveEvent
import com.quickshare.tv.network.EndpointInfo
import com.quickshare.tv.proto.offline.PayloadHeader
import com.quickshare.tv.proto.offline.V1Frame
import com.quickshare.tv.proto.share.Frame
import com.quickshare.tv.proto.share.V1Frame as ShareV1Frame
import com.quickshare.tv.protocol.payload.PayloadAssembler
import com.quickshare.tv.system.storage.FileSinkFactory
import com.quickshare.tv.util.Log
import com.quickshare.tv.util.isUsefulDeviceName
import com.quickshare.tv.util.toHex
import java.io.EOFException
import java.net.Socket
import java.net.SocketException
import java.security.PrivateKey
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Server-role state machine — reactive, mirrors `rqs_lib::inbound`.
 *
 * Receiver flow (`D2DSession.handshakeAsServer` already exchanged plaintext
 * ConnectionRequest, UKEY2, plaintext ConnectionResponse before we get here):
 *
 *   1) Send PairedKeyEncryption (random stubs; optional `qr_code_handshake_data`
 *      when auto-accept and a persisted Send-flow QR private key) immediately.
 *   2) On inbound PairedKeyEncryption → send PairedKeyResult{UNABLE}.
 *   3) On inbound PairedKeyResult     → no-op.
 *   4) On inbound Introduction        → surface to UI; ask for accept/reject;
 *                                        send Response{ACCEPT|REJECT}.
 *   5) On inbound FILE chunks         → write via PayloadAssembler.
 *      Files complete only on the empty-body LAST_CHUNK terminator. When the
 *      in-flight set is empty AND we've seen the introduction, we're done.
 *   6) On inbound Disconnection       → bail out cleanly.
 */
class ReceiverSession(
    private val sinkFactory: FileSinkFactory,
    private val local: LocalEndpoint,
    private val acceptDecision: suspend (List<FileMeta>) -> Boolean,
    private val autoAcceptIncoming: Boolean = false,
    private val requestRejectedMessage: String = "Request Rejected",
    /** When non-null (persisted Send-flow QR key), signs auth string so the phone skips device confirm. */
    private val qrHandshakePrivateKey: PrivateKey? = null,
) {
    private val _events = MutableSharedFlow<ReceiveEvent>(replay = 1, extraBufferCapacity = 32)
    val events: Flow<ReceiveEvent> = _events.asSharedFlow()

    suspend fun run(socket: Socket) {
        var session: D2DSession? = null
        val savedPaths = mutableListOf<String>()
        val assembler = PayloadAssembler(sinkFactory)
        val startedAtMs = System.currentTimeMillis()
        var totalBytesSaved = 0L
        try {
            _events.emit(ReceiveEvent.Connected(socket.remoteSocketAddress.toString()))

            session = D2DSession.handshakeAsServer(socket, local).also { it.start() }
            val sessionRef = session!!
            val peerDisplayName = sessionRef.peer.resolvedDisplayName()
            val peerKind = sessionRef.peer.resolvedKind()
            _events.emit(ReceiveEvent.PeerIntroduced(peerDisplayName, peerKind))
            _events.emit(
                ReceiveEvent.Handshaked(
                    authString = sessionRef.authString.toHex(8),
                    pin = PinCode.fromAuthString(sessionRef.authString),
                )
            )

            // Step 1: send our PairedKeyEncryption (random stubs + optional QR proof).
            val qrSig = qrHandshakePrivateKey?.let {
                QrHandshake.signAuthStringToP1363(it, sessionRef.authString)
            }
            sessionRef.sendBytesPayload(
                id = nextPayloadId(),
                bytes = ShareFrames.pairedKeyEncryption(rng, qrHandshakeSignature = qrSig).toByteArray(),
            )

            // Per-payload BYTES buffer; Quick Share's two-frame split means we
            // see at least one data chunk + an empty-body LAST_CHUNK per
            // logical Sharing frame.
            val byteBuffers = HashMap<Long, ByteArray>()
            val expectedFiles = HashSet<Long>()
            var pairedKeyResultSent = false
            var introductionReceived = false
            var transferAccepted = false

            try {
                coroutineScope {
                sessionRef.incoming.collect { frame ->
                    when (frame.v1.type) {
                        V1Frame.FrameType.PAYLOAD_TRANSFER -> {
                            val pt = frame.v1.payloadTransfer
                            when (pt.payloadHeader.type) {
                                PayloadHeader.PayloadType.BYTES -> {
                                    val id = pt.payloadHeader.id
                                    val chunkBody = pt.payloadChunk.body.toByteArray()
                                    val merged = byteBuffers[id]?.let { it + chunkBody } ?: chunkBody
                                    if (!Frames.isLastChunk(pt.payloadChunk)) {
                                        byteBuffers[id] = merged
                                        return@collect
                                    }
                                    byteBuffers.remove(id)
                                    handleInnerFrame(
                                        bytes = merged,
                                        session = sessionRef,
                                        assembler = assembler,
                                        expectedFiles = expectedFiles,
                                        introductionAlreadyReceived = { introductionReceived },
                                        onIntroduction = { introductionReceived = true },
                                        onAccepted = { transferAccepted = true },
                                        decisionScope = this,
                                        peerDisplayName = peerDisplayName,
                                        sendPairedKeyResultOnce = {
                                            if (!pairedKeyResultSent) {
                                                pairedKeyResultSent = true
                                                sessionRef.sendBytesPayload(
                                                    id = nextPayloadId(),
                                                    bytes = ShareFrames.pairedKeyResult().toByteArray(),
                                                )
                                            }
                                        },
                                    )
                                }
                                PayloadHeader.PayloadType.FILE -> {
                                    val (progress, completed) = assembler.apply(pt)
                                    progress?.let {
                                        _events.emit(ReceiveEvent.Progress(it.payloadId, it.received, it.total))
                                    }
                                    when (completed) {
                                        is PayloadAssembler.Completed.FileSaved -> {
                                            _events.emit(ReceiveEvent.FileSaved(completed.payloadId, completed.path))
                                            savedPaths += completed.path
                                            totalBytesSaved += progress?.received ?: 0L
                                            expectedFiles.remove(completed.payloadId)
                                            if (introductionReceived && expectedFiles.isEmpty()) {
                                                Log.i(
                                                    SCOPE,
                                                    "Transfer complete — ${savedPaths.size} file(s), " +
                                                        "${totalBytesSaved}B in " +
                                                        "${System.currentTimeMillis() - startedAtMs}ms",
                                                )
                                                _events.emit(ReceiveEvent.Done(savedPaths.toList()))
                                                runCatching { sessionRef.sendDisconnection() }
                                                throw CompleteSentinel
                                            }
                                        }
                                        else -> Unit
                                    }
                                }
                                else -> Log.d(SCOPE) { "Ignoring payload type ${pt.payloadHeader.type}" }
                            }
                        }
                        V1Frame.FrameType.DISCONNECTION -> {
                            val completed = introductionReceived && transferAccepted && expectedFiles.isEmpty()
                            Log.i(
                                SCOPE,
                                "Peer disconnected — transfer ${if (completed) "completed" else "cancelled"} " +
                                    "with ${savedPaths.size} file(s) " +
                                    "in ${System.currentTimeMillis() - startedAtMs}ms",
                            )
                            if (completed) {
                                _events.emit(ReceiveEvent.Done(savedPaths.toList()))
                            } else {
                                _events.emit(ReceiveEvent.Failed(IllegalStateException("Cancelled by sender")))
                            }
                            throw CompleteSentinel
                        }
                        else -> Log.d(SCOPE) { "Ignoring offline frame ${frame.v1.type}" }
                    }
                }
                }
            } catch (_: CompleteSentinel) {
                // Normal end-of-transfer signal (see throw sites above).
            }
        } catch (e: CancellationException) {
            // External cancellation (screen left, repo stopped). Don't try to
            // emit through a Cancelling scope — it just rethrows. Best-effort
            // disconnect under NonCancellable so the peer learns we're gone.
            withContext(NonCancellable) {
                runCatching { session?.sendDisconnection() }
            }
            throw e
        } catch (t: Throwable) {
            if (session == null && t.isPreHandshakeDisconnect()) {
                Log.i(SCOPE, "Peer disconnected before Quick Share handshake: ${t.javaClass.simpleName}")
                _events.tryEmit(ReceiveEvent.Listening)
                return
            }
            Log.e(SCOPE, "Receive failed", t)
            _events.tryEmit(ReceiveEvent.Failed(t))
            withContext(NonCancellable) {
                runCatching { session?.sendDisconnection() }
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { assembler.close() }
                runCatching { session?.close() }
            }
        }
    }

    /** Decode one Quick Share inner Frame from a BYTES payload and react. */
    private suspend fun handleInnerFrame(
        bytes: ByteArray,
        session: D2DSession,
        assembler: PayloadAssembler,
        expectedFiles: MutableSet<Long>,
        introductionAlreadyReceived: () -> Boolean,
        onIntroduction: () -> Unit,
        onAccepted: () -> Unit,
        decisionScope: CoroutineScope,
        peerDisplayName: String,
        sendPairedKeyResultOnce: suspend () -> Unit,
    ) {
        val frame = runCatching { Frame.parseFrom(bytes) }.getOrNull() ?: return
        val v1 = frame.v1
        when (v1.type) {
            ShareV1Frame.FrameType.PAIRED_KEY_ENCRYPTION -> sendPairedKeyResultOnce()
            ShareV1Frame.FrameType.PAIRED_KEY_RESULT -> Unit  // no-op, see step 3

            ShareV1Frame.FrameType.INTRODUCTION -> {
                if (introductionAlreadyReceived()) return
                val files = v1.introduction.fileMetadataList.map {
                    FileMeta(
                        payloadId = it.payloadId,
                        name = it.name,
                        size = it.size,
                        mimeType = it.mimeType.takeIf { s -> s.isNotBlank() },
                    )
                }
                // Pre-register so the assembler can pick the right MediaStore
                // collection / mime type when the first FILE chunk arrives.
                files.forEach { meta ->
                    expectedFiles.add(meta.payloadId)
                    assembler.registerExpectedFile(meta.payloadId, meta.name, meta.mimeType)
                }
                onIntroduction()
                _events.emit(
                    ReceiveEvent.IntroductionReceived(
                        files,
                        peerDisplayName,
                        needsPrompt = !autoAcceptIncoming,
                    ),
                )

                decisionScope.launch {
                    val accept =
                        if (autoAcceptIncoming) true
                        else acceptDecision(files)
                    session.sendBytesPayload(
                        id = nextPayloadId(),
                        bytes = ShareFrames.response(accept).toByteArray(),
                    )
                    if (!accept) {
                        _events.emit(ReceiveEvent.Failed(IllegalStateException(requestRejectedMessage)))
                        runCatching { session.sendDisconnection() }
                        runCatching { session.close() }
                        return@launch
                    }
                    onAccepted()
                    // No file payloads expected (text/wifi only) → finish now.
                    if (expectedFiles.isEmpty()) {
                        _events.emit(ReceiveEvent.Done(emptyList()))
                        runCatching { session.sendDisconnection() }
                        runCatching { session.close() }
                    }
                }
            }

            ShareV1Frame.FrameType.CANCEL -> {
                _events.emit(ReceiveEvent.Failed(IllegalStateException("Cancelled by sender")))
                throw CompleteSentinel
            }
            else -> Log.d(SCOPE) { "Unhandled inner frame ${v1.type}" }
        }
    }

    private fun nextPayloadId(): Long {
        var v: Long
        do { v = rng.nextLong() and Long.MAX_VALUE } while (v == 0L)
        return v
    }
    private val rng = SecureRandom()

    /** Sentinel used to break out of the inbound Flow.collect cleanly. */
    private object CompleteSentinel : CancellationException("transfer complete")

    /**
     * rqs_lib resolves inbound peer names from ConnectionRequest.endpoint_info:
     * byte 17 is the UTF-8 name length and bytes 18..N are the sender's
     * display name. The protobuf endpoint_name is only a fallback because it
     * can be absent or less user-friendly on stock Quick Share peers.
     */
    private fun PeerIdentity.resolvedDisplayName(): String {
        val infoName = EndpointInfo.decode(endpointInfo)
            ?.deviceName
            ?.trim()
            ?.takeIf { it.isUsefulDeviceName() }
        val protobufName = endpointName.trim().takeIf { it.isUsefulDeviceName() }
        return infoName ?: protobufName ?: "Sender"
    }

    /**
     * Translate the sender's EndpointInfo `deviceType` byte into our coarse
     * [DeviceKind]. Most Android phones publish [EndpointInfo.DeviceType.PHONE];
     * iOS / desktop senders may omit a useful type byte, in which case we
     * surface [DeviceKind.UNKNOWN] and let the UI render a generic label.
     */
    private fun PeerIdentity.resolvedKind(): DeviceKind =
        when (EndpointInfo.decode(endpointInfo)?.deviceType) {
            EndpointInfo.DeviceType.PHONE   -> DeviceKind.PHONE
            EndpointInfo.DeviceType.TABLET  -> DeviceKind.TABLET
            EndpointInfo.DeviceType.LAPTOP  -> DeviceKind.LAPTOP
            EndpointInfo.DeviceType.UNKNOWN -> DeviceKind.UNKNOWN
            null                             -> DeviceKind.UNKNOWN
        }


    private fun Throwable.isPreHandshakeDisconnect(): Boolean =
        this is EOFException || this is SocketException

    companion object { private const val SCOPE = "Receiver" }
}
