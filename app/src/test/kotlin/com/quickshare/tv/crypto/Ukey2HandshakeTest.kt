package com.quickshare.tv.crypto

import com.quickshare.tv.proto.offline.OfflineFrame
import com.quickshare.tv.protocol.Frames
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.concurrent.thread

/**
 * End-to-end UKEY2 + SecureChannel test.
 *
 * We don't use PipedInputStream because it carries a "writer thread liveness"
 * check that fails once the handshake threads die. Instead each direction is a
 * blocking queue of byte buffers, which is what real socket reads/writes look
 * like at this granularity anyway.
 */
class Ukey2HandshakeTest {

    /** Tiny BlockingStreamPair: each call to `out.write` becomes a single chunk. */
    private class Pipe {
        private val q = LinkedBlockingQueue<ByteArray>()
        val out = object : java.io.OutputStream() {
            private val buf = ByteArrayOutputStream()
            override fun write(b: Int) { buf.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { buf.write(b, off, len) }
            override fun flush() {
                val bytes = buf.toByteArray()
                if (bytes.isNotEmpty()) {
                    q.put(bytes); buf.reset()
                }
            }
            override fun close() { flush() }
        }
        val ins = object : java.io.InputStream() {
            private var current: ByteArrayInputStream? = null
            private fun ensure(): ByteArrayInputStream? {
                if (current == null || current!!.available() == 0) {
                    val next = q.poll(10, TimeUnit.SECONDS) ?: return null
                    current = ByteArrayInputStream(next)
                }
                return current
            }
            override fun read(): Int = ensure()?.read() ?: -1
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                ensure()?.read(b, off, len) ?: -1
        }
    }

    @Test
    fun `client and server derive matching keys and exchange a frame`() {
        val c2s = Pipe()
        val s2c = Pipe()

        var serverSession: Ukey2.Session? = null
        var clientSession: Ukey2.Session? = null

        val server = thread {
            serverSession = Ukey2.server().runServer(
                input = DataInputStream(c2s.ins),
                output = DataOutputStream(s2c.out),
            )
        }
        val client = thread {
            clientSession = Ukey2.client().runClient(
                input = DataInputStream(s2c.ins),
                output = DataOutputStream(c2s.out),
            )
        }
        client.join(5000); server.join(5000)

        val s = serverSession ?: error("server failed")
        val c = clientSession ?: error("client failed")
        // All four traffic keys plus the auth-string must agree on both sides.
        assertArrayEquals(s.authString, c.authString)
        assertArrayEquals(s.encClientKey, c.encClientKey)
        assertArrayEquals(s.encServerKey, c.encServerKey)
        assertArrayEquals(s.sigClientKey, c.sigClientKey)
        assertArrayEquals(s.sigServerKey, c.sigServerKey)
        // PIN must round-trip — both sides hash the same auth-string.
        assertEquals(
            com.quickshare.tv.crypto.PinCode.fromAuthString(s.authString),
            com.quickshare.tv.crypto.PinCode.fromAuthString(c.authString),
        )

        // Round-trip a single application frame using independent SecureChannels.
        val cChan = SecureChannel(c, Ukey2.Role.CLIENT)
        val sChan = SecureChannel(s, Ukey2.Role.SERVER)

        val frame = Frames.keepAlive(false).toByteArray()
        cChan.send(DataOutputStream(c2s.out), frame)
        val received = sChan.receive(DataInputStream(c2s.ins))
        assertArrayEquals(frame, received)
        assertEquals(
            OfflineFrame.parseFrom(frame).v1.type,
            OfflineFrame.parseFrom(received).v1.type,
        )
    }
}
