package com.quickshare.tv.protocol

import com.quickshare.tv.crypto.SecureChannel
import com.quickshare.tv.crypto.Ukey2
import com.quickshare.tv.domain.model.LocalEndpoint
import com.quickshare.tv.domain.model.PeerIdentity
import com.quickshare.tv.proto.offline.ConnectionResponseFrame
import com.quickshare.tv.proto.offline.OfflineFrame
import com.quickshare.tv.proto.offline.V1Frame
import com.quickshare.tv.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * High-level device-to-device (D2D) session over a TCP socket.
 *
 * Spec-compliant handshake order (Nearby Connections / Quick Share):
 *   1) Client → Server : ConnectionRequest (plaintext, length-prefixed OfflineFrame)
 *   2) UKEY2 handshake (also plaintext, length-prefixed Ukey2Message frames)
 *   3) Both sides exchange a plaintext ConnectionResponse(ACCEPT) OfflineFrame
 *   4) From here on, every message is wrapped in a SecureMessage envelope
 *      (encrypted + HMAC'd inside DeviceToDeviceMessage with sequence numbers).
 *
 * Closing protocol: the upper layer should call [sendDisconnection] before
 * [close] so the peer learns the connection ended cleanly rather than via TCP RST.
 *
 * Threading: all reads happen in a single coroutine inside [start]; all writes
 * are serialized through [sendMutex] so SecureChannel sequence numbers stay
 * monotonic.
 */
class D2DSession private constructor(
    private val socket: Socket,
    private val output: DataOutputStream,
    private val input: DataInputStream,
    private val secure: SecureChannel,
    val peer: PeerIdentity,
) {
    private val sendMutex = Mutex()
    private val incomingChan = Channel<OfflineFrame>(Channel.BUFFERED)
    private val errors = MutableSharedFlow<Throwable>(extraBufferCapacity = 4)

    /**
     * Set to `true` once **we** initiated the teardown (Disconnection sent
     * or [close] called). When the reader subsequently hits an
     * EOF/SocketException, we know it's the peer doing its half-close in
     * response — not a transport failure — and we log accordingly.
     */
    private val shutdownRequested = AtomicBoolean(false)

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    val incoming: Flow<OfflineFrame> = incomingChan.consumeAsFlow()
    val errorsFlow: Flow<Throwable>  = errors.asSharedFlow()
    val authString: ByteArray get() = secure.authString()

    fun start() {
        // Reader-only — we do NOT originate keep-alives. `rqs_lib` and Quick
        // Share both expect ack-only behaviour: the receiver sets `ack=true`
        // when it sees a peer's `ack=false`, never the other way around.
        scope.launch { readerLoop() }
    }

    /**
     * Mark the next EOF / SocketException as an expected peer-initiated close.
     * Upper protocol layers call this once they parse a terminal encrypted
     * frame such as Response{REJECT} or Cancel, because many Quick Share peers
     * close TCP immediately after sending it.
     */
    fun expectPeerClose() {
        shutdownRequested.set(true)
    }

    suspend fun send(frame: OfflineFrame) = sendMutex.withLock {
        withContext(Dispatchers.IO) { secure.send(output, frame.toByteArray()) }
    }

    /**
     * Send a Quick Share inner Frame as a BYTES payload. Quick Share peers
     * finalize a payload only on the empty-body LAST_CHUNK terminator, so we
     * always emit two PayloadTransfer frames: the data carrier with `flags=0`
     * and the terminator with `flags=LAST_CHUNK, body=[]`. Both use the same
     * payload id and both go out under the [sendMutex] (interleaving with
     * other writes would corrupt the stream).
     */
    suspend fun sendBytesPayload(id: Long, bytes: ByteArray) {
        send(Frames.bytesPayloadDataChunk(id, bytes))
        send(Frames.bytesPayloadTerminator(id, bytes.size.toLong()))
    }

    /**
     * Send the encrypted Disconnection control frame. Best-effort; we swallow
     * IOExceptions because the peer may already have shut their half of the
     * socket if they initiated the close.
     *
     * Marking [shutdownRequested] here is what tells the reader loop that any
     * subsequent EOF / SocketException is the *peer's* polite half-close and
     * not a transport panic — so we don't pollute the log with a fake stack
     * trace at the end of every successful transfer.
     */
    suspend fun sendDisconnection() {
        shutdownRequested.set(true)
        runCatching { send(Frames.disconnection()) }
            .onFailure { Log.v(SCOPE) { "sendDisconnection swallowed: ${it.message}" } }
    }

    suspend fun close() {
        shutdownRequested.set(true)
        runCatching { withContext(NonCancellable + Dispatchers.IO) { socket.close() } }
        scope.cancel()
        incomingChan.close()
    }

    private suspend fun readerLoop() {
        try {
            while (scope.isActive && !socket.isClosed) {
                val plain = secure.receive(input)
                val frame = OfflineFrame.parseFrom(plain)
                if (frame.v1.type == V1Frame.FrameType.KEEP_ALIVE) {
                    if (!frame.v1.keepAlive.ack) {
                        send(Frames.keepAlive(ack = true))
                    }
                    continue
                }
                if (frame.v1.type == V1Frame.FrameType.DISCONNECTION) {
                    // Peer politely told us they're done — surface to the
                    // upper layer (which may need to react), but treat the
                    // *next* read failure as a clean shutdown.
                    shutdownRequested.set(true)
                }
                incomingChan.send(frame)
            }
        } catch (t: Throwable) {
            // Differentiate "expected EOF after either side requested
            // teardown" from a genuine transport blow-up. Without this
            // every successful Quick Share transfer ends with a 20-line
            // SocketException stack trace at WARN level, which makes
            // it look like the transfer failed.
            val expected = shutdownRequested.get() &&
                (t is EOFException || t is SocketException)
            if (expected) {
                Log.d(SCOPE) { "reader stopped cleanly: ${t.javaClass.simpleName}: ${t.message}" }
                incomingChan.close()
            } else {
                Log.w(SCOPE, "reader stopped unexpectedly", t)
                errors.tryEmit(t)
                incomingChan.close(t)
            }
        }
    }

    companion object {
        private const val SCOPE = "D2D"

        // ────────────────────────────────────────────────────────────────────
        // Server (UKEY2 server == receiver of files in our typical TV flow)
        // ────────────────────────────────────────────────────────────────────
        suspend fun handshakeAsServer(
            socket: Socket,
            local: LocalEndpoint,
        ): D2DSession = withContext(Dispatchers.IO) {
            // Build the buffered streams ONCE and use the same instances
            // through the handshake AND the encrypted phase. Wrapping
            // socket.getInputStream() twice (once unbuffered for handshake,
            // once buffered later) used to "work" only because UKEY2 was fully
            // drained — fragile.
            val out = DataOutputStream(socket.getOutputStream().buffered())
            val ins = DataInputStream(socket.getInputStream().buffered())

            // 1) Read the peer's plaintext ConnectionRequest before UKEY2.
            val reqBytes = FrameIo.readFrame(ins)
            val req = parseOfflineExpecting(reqBytes, V1Frame.FrameType.CONNECTION_REQUEST)
            val peer = PeerIdentity(
                endpointId   = req.v1.connectionRequest.endpointId,
                endpointName = req.v1.connectionRequest.endpointName,
                endpointInfo = req.v1.connectionRequest.endpointInfo.toByteArray(),
            )
            Log.i(SCOPE, "ConnectionRequest from '${peer.endpointName}' (id=${peer.endpointId})")

            // 2) UKEY2 server handshake on the same streams.
            val session = Ukey2.server().runServer(ins, out)

            // 3) Plaintext ConnectionResponse(ACCEPT) exchange (both sides send,
            //    both sides read). Order doesn't matter — they're sent over
            //    independent half-sockets.
            FrameIo.writeFrame(out, Frames.connectionResponse(accept = true).toByteArray())
            val respBytes = FrameIo.readFrame(ins)
            val resp = parseOfflineExpecting(respBytes, V1Frame.FrameType.CONNECTION_RESPONSE)
            val accepted = resp.v1.connectionResponse.response ==
                ConnectionResponseFrame.ResponseStatus.ACCEPT
            check(accepted) { "Peer rejected the connection at OfflineFrame layer" }

            D2DSession(
                socket = socket,
                output = out,
                input = ins,
                secure = SecureChannel(session, Ukey2.Role.SERVER),
                peer = peer,
            )
        }

        // ────────────────────────────────────────────────────────────────────
        // Client (UKEY2 client == sender of files in our typical TV flow)
        // ────────────────────────────────────────────────────────────────────
        suspend fun handshakeAsClient(
            socket: Socket,
            local: LocalEndpoint,
        ): D2DSession = withContext(Dispatchers.IO) {
            val out = DataOutputStream(socket.getOutputStream().buffered())
            val ins = DataInputStream(socket.getInputStream().buffered())

            // 1) Send our plaintext ConnectionRequest first.
            val req = Frames.connectionRequest(
                endpointId   = local.endpointIdString(),
                endpointName = local.endpointName,
                endpointInfo = local.endpointInfo,
            )
            FrameIo.writeFrame(out, req.toByteArray())
            Log.i(SCOPE, "Sent ConnectionRequest as '${local.endpointName}'")

            // 2) UKEY2 client handshake on the same streams.
            val session = Ukey2.client().runClient(ins, out)

            // 3) Plaintext ConnectionResponse(ACCEPT) exchange.
            FrameIo.writeFrame(out, Frames.connectionResponse(accept = true).toByteArray())
            val respBytes = FrameIo.readFrame(ins)
            val resp = parseOfflineExpecting(respBytes, V1Frame.FrameType.CONNECTION_RESPONSE)
            val accepted = resp.v1.connectionResponse.response ==
                ConnectionResponseFrame.ResponseStatus.ACCEPT
            check(accepted) { "Peer rejected the connection at OfflineFrame layer" }

            D2DSession(
                socket = socket,
                output = out,
                input = ins,
                secure = SecureChannel(session, Ukey2.Role.CLIENT),
                peer = PeerIdentity(
                    endpointId   = "",
                    endpointName = "",
                    endpointInfo = ByteArray(0),
                ),
            )
        }

        private fun parseOfflineExpecting(
            bytes: ByteArray,
            type: V1Frame.FrameType,
        ): OfflineFrame {
            val frame = OfflineFrame.parseFrom(bytes)
            check(frame.v1.type == type) {
                "Expected OfflineFrame type=$type, got ${frame.v1.type}"
            }
            return frame
        }
    }
}
