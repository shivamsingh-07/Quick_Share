package com.quickshare.tv.protocol

import com.quickshare.tv.domain.model.LocalEndpoint
import com.quickshare.tv.proto.offline.V1Frame
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end test for the spec-compliant Quick Share handshake order:
 *
 *   plaintext ConnectionRequest → UKEY2 → plaintext ConnectionResponse → encrypted
 *
 * Drives both sides over a real loopback TCP socket so the buffered-stream
 * plumbing in [D2DSession] is exercised exactly as it would be at runtime.
 */
class D2DSessionTest {

    private lateinit var server: ServerSocket
    private var port: Int = 0

    @Before fun setUp() {
        server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress("127.0.0.1", 0))
        }
        port = server.localPort
    }

    @After fun tearDown() {
        runCatching { server.close() }
    }

    @Test
    fun `handshake exchanges ConnectionRequest and matches keys end-to-end`() = runBlocking {
        val clientLocal = LocalEndpoint(
            endpointId   = "abCD".toByteArray(Charsets.US_ASCII),
            endpointName = "Phone",
            endpointInfo = byteArrayOf(0x02, 0x10, 0x20, 0x30),
        )
        val serverLocal = LocalEndpoint(
            endpointId   = "WxYz".toByteArray(Charsets.US_ASCII),
            endpointName = "Living Room TV",
            endpointInfo = byteArrayOf(0x06, 0x40, 0x50, 0x60),
        )

        val serverDeferred = async(Dispatchers.IO) {
            val accepted: Socket = server.accept().apply { tcpNoDelay = true }
            D2DSession.handshakeAsServer(accepted, serverLocal)
        }
        val clientDeferred = async(Dispatchers.IO) {
            val s = Socket().apply { tcpNoDelay = true }
            s.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            D2DSession.handshakeAsClient(s, clientLocal)
        }

        val sSession = withTimeout(10_000) { serverDeferred.await() }
        val cSession = withTimeout(10_000) { clientDeferred.await() }

        try {
            // Server learned the client's identity from the plaintext ConnectionRequest.
            assertEquals(clientLocal.endpointName, sSession.peer.endpointName)
            assertEquals(clientLocal.endpointIdString(), sSession.peer.endpointId)
            assertArrayEquals(clientLocal.endpointInfo, sSession.peer.endpointInfo)

            // The shared UKEY2 auth-string must match on both sides.
            assertArrayEquals(sSession.authString, cSession.authString)
            assertNotEquals(0, sSession.authString.size)

            // A round-trip BYTES payload survives the encrypted channel.
            // Quick Share splits BYTES into a data frame + an empty-body
            // LAST_CHUNK terminator, so the receiver sees two
            // PayloadTransfer frames per logical send. We collect both in a
            // single subscription because `D2DSession.incoming` is a
            // `consumeAsFlow()` and can be collected only once.
            sSession.start(); cSession.start()
            cSession.sendBytesPayload(id = 42L, bytes = byteArrayOf(1, 2, 3, 4))
            val frames = withTimeout(5_000) {
                sSession.incoming
                    .filter { it.v1.type == V1Frame.FrameType.PAYLOAD_TRANSFER }
                    .take(2)
                    .toList()
            }
            val data = frames[0].v1.payloadTransfer
            val term = frames[1].v1.payloadTransfer
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), data.payloadChunk.body.toByteArray())
            assertFalse(Frames.isLastChunk(data.payloadChunk))
            assertEquals(42L, term.payloadHeader.id)
            assertEquals(0, term.payloadChunk.body.size())
            assertTrue(Frames.isLastChunk(term.payloadChunk))
        } finally {
            sSession.close()
            cSession.close()
        }
    }

    @Test
    fun `sender Disconnection frame is delivered to receiver`() = runBlocking {
        val clientLocal = LocalEndpoint(
            endpointId = "abcd".toByteArray(Charsets.US_ASCII),
            endpointName = "Phone",
            endpointInfo = byteArrayOf(0x02),
        )
        val serverLocal = LocalEndpoint(
            endpointId = "wxyz".toByteArray(Charsets.US_ASCII),
            endpointName = "TV",
            endpointInfo = byteArrayOf(0x06),
        )

        val serverDeferred = async(Dispatchers.IO) {
            D2DSession.handshakeAsServer(server.accept(), serverLocal)
        }
        val clientDeferred = async(Dispatchers.IO) {
            val s = Socket().apply { tcpNoDelay = true }
            s.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            D2DSession.handshakeAsClient(s, clientLocal)
        }

        val sSession = withTimeout(10_000) { serverDeferred.await() }
        val cSession = withTimeout(10_000) { clientDeferred.await() }

        try {
            sSession.start(); cSession.start()
            cSession.sendDisconnection()
            val received = withTimeout(5_000) {
                sSession.incoming.first { it.v1.type == V1Frame.FrameType.DISCONNECTION }
            }
            // The DisconnectionFrame has no `reason` field in the canonical
            // proto. Just validate the type lands at V1Frame field 7.
            assertEquals(V1Frame.FrameType.DISCONNECTION, received.v1.type)
        } finally {
            sSession.close()
            cSession.close()
        }
    }
}
