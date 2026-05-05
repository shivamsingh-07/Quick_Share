package com.quickshare.tv.crypto

import com.google.protobuf.ByteString
import com.quickshare.tv.proto.d2d.DeviceToDeviceMessage
import com.quickshare.tv.proto.securegcm.GcmMetadata
import com.quickshare.tv.proto.securegcm.Type as GcmType
import com.quickshare.tv.proto.securemessage.EncScheme
import com.quickshare.tv.proto.securemessage.Header
import com.quickshare.tv.proto.securemessage.HeaderAndBody
import com.quickshare.tv.proto.securemessage.SecureMessage
import com.quickshare.tv.proto.securemessage.SigScheme
import com.quickshare.tv.util.Log
import com.quickshare.tv.util.constantTimeEquals
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Encrypted, authenticated, ordered byte channel built on top of the UKEY2 session.
 *
 * Wire shape per message:
 *   4-byte BE length | SecureMessage{
 *      header_and_body = HeaderAndBody{
 *          header = { sig=HMAC_SHA256, enc=AES_256_CBC, iv=<random16> },
 *          body   = AES-256-CBC( DeviceToDeviceMessage{seq, message=<payload>} )
 *      }
 *      signature = HMAC-SHA256(header_and_body)
 *   }
 *
 * Sequence numbers are *independent* per direction (one counter for tx, one for rx),
 * matching the Nearby Connections D2D layer.
 */
class SecureChannel(
    private val session: Ukey2.Session,
    private val role: Ukey2.Role,
) {
    private val txSeq = AtomicInteger(0)
    private var rxSeq = 0

    private val encKeyTx: ByteArray
    private val hmacKeyTx: ByteArray
    private val encKeyRx: ByteArray
    private val hmacKeyRx: ByteArray

    init {
        // CLIENT direction is sender→receiver; SERVER direction is the reply.
        // SecureMessage uses *separate* ENC and SIG keys per direction; the
        // d2dClient/d2dServer intermediates from UKEY2 are not used directly.
        if (role == Ukey2.Role.CLIENT) {
            encKeyTx = session.encClientKey; hmacKeyTx = session.sigClientKey
            encKeyRx = session.encServerKey; hmacKeyRx = session.sigServerKey
        } else {
            encKeyTx = session.encServerKey; hmacKeyTx = session.sigServerKey
            encKeyRx = session.encClientKey; hmacKeyRx = session.sigClientKey
        }
    }

    fun authString(): ByteArray = session.authString

    /** Encrypt + authenticate + write a single application message. */
    fun send(out: DataOutputStream, payload: ByteArray) {
        val seq = txSeq.incrementAndGet()
        val d2d = DeviceToDeviceMessage.newBuilder()
            .setSequenceNumber(seq)
            .setMessage(ByteString.copyFrom(payload))
            .build()
            .toByteArray()

        val iv = AesCbc.randomIv()
        val ciphertext = AesCbc.encrypt(encKeyTx, iv, d2d)

        // `public_metadata` is part of the HMAC-covered Header. Stock Quick
        // Share peers tag every encrypted Nearby Connections frame with
        // GcmMetadata{type=DEVICE_TO_DEVICE_MESSAGE, version=1}. Strict peers
        // refuse SecureMessages without it.
        val header = Header.newBuilder()
            .setSignatureScheme(SigScheme.HMAC_SHA256)
            .setEncryptionScheme(EncScheme.AES_256_CBC)
            .setIv(ByteString.copyFrom(iv))
            .setPublicMetadata(ByteString.copyFrom(D2D_GCM_METADATA))
            .build()
        val hb = HeaderAndBody.newBuilder()
            .setHeader(header)
            .setBody(ByteString.copyFrom(ciphertext))
            .build()
            .toByteArray()
        val sig = HmacSha256.mac(hmacKeyTx, hb)

        val sm = SecureMessage.newBuilder()
            .setHeaderAndBody(ByteString.copyFrom(hb))
            .setSignature(ByteString.copyFrom(sig))
            .build()
            .toByteArray()

        out.writeInt(sm.size)
        out.write(sm)
        out.flush()
        Log.v(SCOPE) { "tx seq=$seq enc=${ciphertext.size}B" }
    }

    /** Read + verify + decrypt → application payload. */
    fun receive(input: DataInputStream): ByteArray {
        val len = input.readInt()
        require(len in 1..16_000_000) { "Unreasonable secure-message length: $len" }
        val raw = ByteArray(len).also(input::readFully)

        val sm = SecureMessage.parseFrom(raw)
        val hbBytes = sm.headerAndBody.toByteArray()
        val sigGot  = sm.signature.toByteArray()
        val sigWant = HmacSha256.mac(hmacKeyRx, hbBytes)
        check(sigGot.constantTimeEquals(sigWant)) { "HMAC mismatch on inbound SecureMessage" }

        val hb = HeaderAndBody.parseFrom(hbBytes)
        require(hb.header.encryptionScheme == EncScheme.AES_256_CBC) {
            "Unsupported enc scheme ${hb.header.encryptionScheme}"
        }
        val iv = hb.header.iv.toByteArray()
        val plaintext = AesCbc.decrypt(encKeyRx, iv, hb.body.toByteArray())

        val d2d = DeviceToDeviceMessage.parseFrom(plaintext)
        val seq = d2d.sequenceNumber
        check(seq == rxSeq + 1) { "Out-of-order seq: got $seq expected ${rxSeq + 1}" }
        rxSeq = seq
        Log.v(SCOPE) { "rx seq=$seq dec=${d2d.message.size()}B" }
        return d2d.message.toByteArray()
    }

    companion object {
        private const val SCOPE = "SecureChannel"

        // Cached: every outbound message uses the same GcmMetadata blob, so we
        // serialize it once and reuse the bytes.
        private val D2D_GCM_METADATA: ByteArray = GcmMetadata.newBuilder()
            .setType(GcmType.DEVICE_TO_DEVICE_MESSAGE)
            .setVersion(1)
            .build()
            .toByteArray()
    }
}
