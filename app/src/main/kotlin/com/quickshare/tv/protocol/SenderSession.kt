package com.quickshare.tv.protocol

import com.quickshare.tv.crypto.PinCode
import com.quickshare.tv.crypto.QrHandshake
import com.quickshare.tv.domain.model.FileMeta
import com.quickshare.tv.domain.model.LocalEndpoint
import com.quickshare.tv.domain.model.SendEvent
import com.quickshare.tv.proto.offline.PayloadHeader
import com.quickshare.tv.proto.offline.V1Frame
import com.quickshare.tv.proto.share.ConnectionResponseFrame as ShareConnectionResponseFrame
import com.quickshare.tv.proto.share.Frame
import com.quickshare.tv.proto.share.V1Frame as ShareV1Frame
import com.quickshare.tv.util.Log
import com.quickshare.tv.util.toHex
import java.io.InputStream
import java.net.Socket
import java.security.PrivateKey
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Client-role state machine — reactive, exactly mirroring `rqs_lib::outbound`.
 *
 * Sender flow (post-handshake; the plaintext ConnectionRequest + UKEY2 +
 * plaintext ConnectionResponse roundtrip are handled inside `D2DSession`):
 *
 *   1) Immediately send PairedKeyEncryption (random stubs; optional
 *      `qr_code_handshake_data` when auto-accept + QR key) as the first
 *      encrypted frame.
 *   2) On inbound PairedKeyEncryption  → send PairedKeyResult{UNABLE}.
 *   3) On inbound PairedKeyResult      → send Introduction (file list).
 *   4) On inbound Response{ACCEPT}     → stream files via the two-frame
 *      chunk + empty-LAST_CHUNK pattern, one file at a time.
 *   5) On inbound Response{REJECT}     → emit Failed, send Disconnection.
 *   6) On inbound Disconnection        → bail out cleanly.
 *
 * The previous strict request/response ordering with `CompletableDeferred`
 * gates added latency and could deadlock against tolerant peers that batch
 * the paired-key frames. Fire-and-forget matches Quick Share exactly.
 */
class SenderSession(
    private val files: List<FileMeta>,
    private val openFile: (FileMeta) -> Pair<InputStream, Long>,
    private val chunkSize: Int = DEFAULT_CHUNK,
    /**
     * When non-null, signs the UKEY2 auth string and embeds the proof
     * in PairedKeyEncryption so the receiver phone can skip its
     * confirmation dialog (it verifies the signature against the QR
     * public key it scanned). Always set on the QR send path; always
     * `null` on the device-picker send path.
     */
    private val qrHandshakePrivateKey: PrivateKey? = null,
) {
    private val _events = MutableSharedFlow<SendEvent>(replay = 1, extraBufferCapacity = 32)
    val events: Flow<SendEvent> = _events.asSharedFlow()

    private val responseGate = CompletableDeferred<Boolean>()
    private val disconnected = CompletableDeferred<Unit>()

    suspend fun run(socket: Socket, local: LocalEndpoint) = coroutineScope {
        var session: D2DSession? = null
        val startedAtMs = System.currentTimeMillis()
        try {
            session = D2DSession.handshakeAsClient(socket, local).also { it.start() }
            val sessionRef = session!!
            _events.emit(
                SendEvent.Handshaked(
                    authString = sessionRef.authString.toHex(8),
                    pin = PinCode.fromAuthString(sessionRef.authString),
                )
            )

            // Side coroutine: drain inbound BYTES payloads and react. Must be
            // launched *before* we send our PairedKeyEncryption so we don't
            // race the peer's reply.
            val collectorJob = launch { collectInboundFrames(sessionRef) }

            // Step 1: fire our PairedKeyEncryption (random stubs + optional QR proof).
            val qrSig = qrHandshakePrivateKey?.let {
                QrHandshake.signAuthStringToP1363(it, sessionRef.authString)
            }
            sessionRef.sendBytesPayload(
                id = nextPayloadId(),
                bytes = ShareFrames.pairedKeyEncryption(rng, qrHandshakeSignature = qrSig).toByteArray(),
            )
            _events.emit(SendEvent.Awaiting)

            // Steps 2–4 happen reactively in the collector. We block here on
            // the receiver's accept/reject decision.
            val accepted = responseGate.await()
            if (!accepted) {
                _events.emit(SendEvent.Failed(IllegalStateException("rejected by receiver")))
                runCatching { sessionRef.sendDisconnection() }
                collectorJob.cancel()
                return@coroutineScope
            }

            // Step 4: stream file chunks with the two-frame terminator.
            for (meta in files) {
                val (stream, total) = openFile(meta)
                stream.use { s ->
                    var sent = 0L
                    val buf = ByteArray(chunkSize)
                    while (true) {
                        val n = s.read(buf)
                        if (n <= 0) break
                        val nextSent = sent + n
                        sessionRef.send(Frames.fileChunkData(
                            payloadId = meta.payloadId,
                            fileName  = meta.name,
                            totalSize = maxOf(total, nextSent),
                            offset    = sent,
                            body      = if (n == buf.size) buf else buf.copyOf(n),
                        ))
                        sent = nextSent
                        _events.emit(SendEvent.Progress(meta.payloadId, sent, maxOf(total, sent)))
                    }
                    // Emit zero-progress for empty files so the UI can render
                    // an "empty file" affordance instead of being stuck at -1.
                    if (sent == 0L) _events.emit(SendEvent.Progress(meta.payloadId, 0L, total))

                    // Empty-body LAST_CHUNK terminator. Required by every
                    // Quick Share peer to mark the file complete.
                    sessionRef.send(Frames.fileChunkTerminator(
                        payloadId = meta.payloadId,
                        fileName  = meta.name,
                        totalSize = sent,
                    ))
                }
            }

            // Step 5: WAIT for the receiver to acknowledge the transfer.
            //
            // Per `rqs_lib::inbound::decrypt_and_process_secure_message`
            // (around the empty-body LAST_CHUNK branch), once the receiver
            // has written every file in the introduction it sets state to
            // `Finished` and **sends its own Disconnection** back to the
            // sender, then closes its socket.
            //
            // We must NOT initiate Disconnection ourselves on the success
            // path: doing so makes us close the TCP connection 1-2 ms after
            // the LAST_CHUNK arrives at the phone — before the phone has
            // flushed file bytes to disk and finalized the transfer. Stock
            // Quick Share treats that as an interrupted send and discards
            // the file. (Outbound `disconnection()` is reserved for
            // user-cancel and error paths in `rqs_lib::outbound`.)
            //
            // [PEER_DISCONNECT_TIMEOUT_MS] gives the receiver a generous
            // window to flush large files (≥ a few hundred MB) before we
            // fall through and close anyway.
            withTimeoutOrNull(PEER_DISCONNECT_TIMEOUT_MS) { disconnected.await() }
                ?: Log.w(SCOPE, "Peer never sent Disconnection within ${PEER_DISCONNECT_TIMEOUT_MS}ms; closing anyway")
            collectorJob.cancel()
            val totalBytes = files.sumOf { it.size.coerceAtLeast(0L) }
            val durationMs = System.currentTimeMillis() - startedAtMs
            Log.i(
                SCOPE,
                "Transfer complete — ${files.size} file(s), ${totalBytes}B in ${durationMs}ms",
            )
            _events.emit(SendEvent.Done)
        } catch (e: CancellationException) {
            // Cooperative cancellation. Emit nothing — the consumer is gone
            // and a suspend through emit() in a Cancelling scope just rethrows.
            // Best-effort disconnect under NonCancellable so the peer learns.
            withContext(NonCancellable) {
                runCatching { session?.sendDisconnection() }
            }
            throw e
        } catch (t: Throwable) {
            Log.e(SCOPE, "Send failed", t)
            _events.tryEmit(SendEvent.Failed(t))
            withContext(NonCancellable) {
                runCatching { session?.sendDisconnection() }
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { session?.close() }
            }
        }
    }

    private suspend fun collectInboundFrames(session: D2DSession) {
        // Buffers BYTES payloads across multiple chunks (data + terminator).
        // Quick Share inner Sharing frames usually fit in one chunk, but the
        // two-frame split means we always see at least two PayloadTransfer
        // messages per logical BYTES payload.
        val byteBuffers = HashMap<Long, ByteArray>()
        var pairedKeyResultSent = false
        var introSent = false

        session.incoming.collect { offline ->
            when (offline.v1.type) {
                V1Frame.FrameType.DISCONNECTION -> {
                    Log.i(SCOPE, "Peer disconnected")
                    if (!responseGate.isCompleted) responseGate.complete(false)
                    if (!disconnected.isCompleted) disconnected.complete(Unit)
                }
                V1Frame.FrameType.PAYLOAD_TRANSFER -> {
                    val pt = offline.v1.payloadTransfer
                    if (pt.payloadHeader.type != PayloadHeader.PayloadType.BYTES) return@collect

                    val id = pt.payloadHeader.id
                    val chunkBody = pt.payloadChunk.body.toByteArray()
                    val merged = byteBuffers[id]?.let { it + chunkBody } ?: chunkBody
                    if (!Frames.isLastChunk(pt.payloadChunk)) {
                        byteBuffers[id] = merged
                        return@collect
                    }
                    byteBuffers.remove(id)

                    val frame = runCatching { Frame.parseFrom(merged) }.getOrNull() ?: return@collect
                    when (frame.v1.type) {
                        ShareV1Frame.FrameType.PAIRED_KEY_ENCRYPTION -> {
                            // Step 2: reply with PairedKeyResult{UNABLE} once.
                            if (!pairedKeyResultSent) {
                                pairedKeyResultSent = true
                                session.sendBytesPayload(
                                    id = nextPayloadId(),
                                    bytes = ShareFrames.pairedKeyResult().toByteArray(),
                                )
                            }
                        }
                        ShareV1Frame.FrameType.PAIRED_KEY_RESULT -> {
                            // Step 3: send Introduction once.
                            if (!introSent) {
                                introSent = true
                                val intro = ShareFrames.introduction(
                                    files.map {
                                        ShareFrames.FileEntry(it.name, it.payloadId, it.size, it.mimeType)
                                    }
                                )
                                session.sendBytesPayload(nextPayloadId(), intro.toByteArray())
                            }
                        }
                        ShareV1Frame.FrameType.RESPONSE -> {
                            val status = frame.v1.connectionResponse.status
                            Log.i(SCOPE, "Receiver response: $status")
                            val ok = status == ShareConnectionResponseFrame.Status.ACCEPT
                            if (!ok) session.expectPeerClose()
                            if (!responseGate.isCompleted) responseGate.complete(ok)
                        }
                        ShareV1Frame.FrameType.CANCEL -> {
                            Log.i(SCOPE, "Receiver cancelled before file transfer")
                            session.expectPeerClose()
                            if (!responseGate.isCompleted) responseGate.complete(false)
                        }
                        else -> Log.v(SCOPE) { "← inner ${frame.v1.type}" }
                    }
                }
                else -> Log.d(SCOPE) { "ignoring inbound ${offline.v1.type}" }
            }
        }
    }

    private fun nextPayloadId(): Long {
        // SecureRandom rather than a counter so payload IDs don't overlap
        // across concurrent BYTES + FILE flows. Keep IDs positive for
        // Android Quick Share/rqs peers and reject 0 so protobuf defaults
        // are never mistaken for "unset".
        var v: Long
        do { v = rng.nextLong() and Long.MAX_VALUE } while (v == 0L)
        return v
    }
    private val rng = SecureRandom()

    companion object {
        private const val SCOPE = "Sender"
        const val DEFAULT_CHUNK = 512 * 1024  // 512 KiB — matches stock Quick Share

        /**
         * Upper bound on how long we wait for the receiver's Disconnection
         * frame after we've sent the last LAST_CHUNK terminator. The phone
         * needs this window to flush bytes to MediaStore (which on slow
         * sdcards can take a couple of seconds for hundred-MB files) before
         * it sends Disconnection back to acknowledge completion.
         *
         * Closing earlier than this caused the bug where the TV reported
         * "Sent" but stock Quick Share showed "file not received": we yanked
         * the socket while the phone was still inside its `write_all_at`
         * call.
         */
        private const val PEER_DISCONNECT_TIMEOUT_MS = 30_000L
    }
}
