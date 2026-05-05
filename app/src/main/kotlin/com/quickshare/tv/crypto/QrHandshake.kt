package com.quickshare.tv.crypto

import java.math.BigInteger
import java.security.PrivateKey
import java.security.Signature

/**
 * Signs the UKEY2 auth string with the QR ephemeral EC key so the peer can treat
 * this as proof-of-pairing (`PairedKeyEncryptionFrame.qr_code_handshake_data`).
 *
 * Wire format: **IEEE P1363** fixed-length **R‖S** (32 + 32 bytes for P-256),
 * not ASN.1 DER — stock Quick Share expects raw coordinates.
 */
object QrHandshake {

    fun signAuthStringToP1363(privateKey: PrivateKey, authString: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(authString)
        return ecdsaDerSignatureToIeeeP256(sig.sign())
    }

    /**
     * Parses a JCA ECDSA signature (ASN.1 SEQUENCE of two INTEGERs) into 64-byte
     * R‖S for secp256r1 scalars.
     */
    internal fun ecdsaDerSignatureToIeeeP256(der: ByteArray): ByteArray {
        var p = 0
        require(der[p++] == 0x30.toByte()) { "expected ECDSA SEQUENCE" }
        val seqLen = readDerLength(der, p)
        p += seqLen.bytesConsumed
        val seqEnd = p + seqLen.value
        require(der[p++] == 0x02.toByte()) { "expected R INTEGER" }
        val rLen = readDerLength(der, p)
        p += rLen.bytesConsumed
        val rDer = der.copyOfRange(p, p + rLen.value)
        p += rLen.value
        require(der[p++] == 0x02.toByte()) { "expected S INTEGER" }
        val sLen = readDerLength(der, p)
        p += sLen.bytesConsumed
        val sDer = der.copyOfRange(p, p + sLen.value)
        p += sLen.value
        require(p == seqEnd) { "trailing ASN.1 bytes in ECDSA signature" }
        val r = unsignedScalarTo32(rDer)
        val s = unsignedScalarTo32(sDer)
        return r + s
    }

    private data class DerLen(val value: Int, val bytesConsumed: Int)

    private fun readDerLength(buf: ByteArray, offset: Int): DerLen {
        val first = buf[offset].toInt() and 0xff
        return when {
            first < 0x80 -> DerLen(first, 1)
            first == 0x81 -> DerLen(buf[offset + 1].toInt() and 0xff, 2)
            first == 0x82 -> DerLen(
                ((buf[offset + 1].toInt() and 0xff) shl 8) or (buf[offset + 2].toInt() and 0xff),
                3,
            )
            else -> error("unsupported DER length encoding 0x${first.toString(16)}")
        }
    }

    /** Unsigned big-endian magnitude padded/truncated to exactly 32 bytes. */
    private fun unsignedScalarTo32(unsignedDerInteger: ByteArray): ByteArray {
        val bi = BigInteger(1, unsignedDerInteger)
        val raw = bi.toByteArray()
        val out = ByteArray(32)
        val copyLen = minOf(32, raw.size)
        System.arraycopy(raw, raw.size - copyLen, out, 32 - copyLen, copyLen)
        return out
    }
}
