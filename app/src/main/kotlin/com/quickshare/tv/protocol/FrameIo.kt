package com.quickshare.tv.protocol

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * The Nearby Connections wire format prepends a 4-byte big-endian length to every
 * message — both pre-handshake (UKEY2) *and* post-handshake (SecureMessage). This
 * helper centralizes the framing so we don't sprinkle DataInput/Output calls
 * everywhere.
 */
object FrameIo {
    // Matches `rqs_lib`'s SANE_FRAME_LENGTH so a maliciously oversized
    // plaintext frame (pre-handshake or stray) drops the connection cleanly
    // before we allocate hundreds of MiB.
    private const val MAX = 5 * 1024 * 1024 // 5 MiB hard limit per frame

    fun writeFrame(out: DataOutputStream, body: ByteArray) {
        require(body.size <= MAX) { "frame too large: ${body.size}" }
        out.writeInt(body.size)
        out.write(body)
        out.flush()
    }

    fun readFrame(input: DataInputStream): ByteArray {
        val len = input.readInt()
        require(len in 1..MAX) { "Unreasonable frame length: $len" }
        val buf = ByteArray(len)
        input.readFully(buf)
        return buf
    }
}
