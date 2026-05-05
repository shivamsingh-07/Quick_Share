package com.quickshare.tv.crypto

import com.quickshare.tv.util.Base64Url
import java.math.BigInteger
import java.security.KeyPair
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey

/**
 * Sender-side QR-code key material for Quick Share's "tap-QR" pairing flow.
 *
 * ### What goes on the wire
 *
 * The QR encodes a URL of the form `https://quickshare.google/qrcode#key=XXX`,
 * where `XXX` is a URL-safe base64 (no padding) of **35 bytes**:
 *
 * ```
 *   bytes 0..1 : 0x00 0x00         (envelope version)
 *   byte 2     : 0x02 or 0x03       (SEC1 compressed-point Y-parity prefix)
 *   bytes 3..34: X coordinate (32 bytes, big-endian, **leading 0x00 stripped**)
 * ```
 *
 * Stock Quick Share / GMS rejects keys whose X has a leading 0x00 sign byte,
 * which Java's `BigInteger.toByteArray()` happily emits when X's high bit is
 * set. NearDrop's `generateQrCodeKey` documents this verbatim
 * (`// Android really hates it (it breaks the endpoint info)`); we mirror the
 * fix by always taking the *last* 32 bytes of the unsigned magnitude.
 *
 * ### What both sides derive from `key`
 *
 * | name               | length | HKDF inputs                                             |
 * | ------------------ | ------ | ------------------------------------------------------- |
 * | `advertisingToken` | 16 B   | `IKM = key`, `salt = ""`, `info = "advertisingContext"` |
 * | `nameEncryptionKey`| 16 B   | `IKM = key`, `salt = ""`, `info = "encryptionKey"`      |
 *
 * `salt = ""` triggers the RFC-5869 §2.2 substitution (HashLen zero bytes);
 * see [Hkdf.extract] for that behaviour.
 *
 * ### Roles
 *
 *  - The TV **sender** generates this and paints the URL into a QR code.
 *  - The phone **receiver** scans, decodes the URL, derives the same
 *    [advertisingToken], and starts mDNS-advertising with TLV-1 carrying
 *    that 16-byte token. See [QrPeerMatcher] for the sender-side filter
 *    that picks the right peer out of the mDNS noise. Display names are
 *    read from the regular mDNS `n` TXT record — we deliberately do not
 *    decrypt the AES-GCM hidden-name blob some peers put in TLV-1.
 *
 * ### Reference
 *
 * NearDrop (`PROTOCOL.md` §"QR codes"): the canonical reverse-engineered spec.
 * `NearbyConnectionManager.swift::generateQrCodeKey` is the exact code we
 * mirror here.
 */
class QrCodeKey private constructor(
    /** Raw 35-byte key material — what we encode into the QR and feed to HKDF. */
    val keyBytes: ByteArray,
    /** URL-safe base64 (no padding) of [keyBytes]; goes after `#key=`. */
    val urlSafeBase64: String,
    /** 16 bytes; matched against TLV-1 of receivers' EndpointInfo. */
    val advertisingToken: ByteArray,
    /** 16 bytes; decrypts hidden QR TLV-1 records carrying the receiver name. */
    val nameEncryptionKey: ByteArray,
    /**
     * The EC P-256 keypair whose public X is encoded in [keyBytes]. The
     * private half is used to sign the UKEY2 auth string for
     * `qr_code_handshake_data` so the phone skips its confirmation dialog
     * after scanning our QR.
     */
    val keyPair: KeyPair,
) {
    /** Convenience: full deep-link URL ready for QR rendering. */
    val url: String get() = "$URL_PREFIX$urlSafeBase64"

    val privateKey: PrivateKey get() = keyPair.private

    companion object {
        const val URL_PREFIX = "https://quickshare.google/qrcode#key="

        /** Per the NearDrop spec. */
        private val INFO_ADVERTISING = "advertisingContext".toByteArray(Charsets.US_ASCII)
        private val INFO_ENCRYPTION  = "encryptionKey".toByteArray(Charsets.US_ASCII)
        private val EMPTY_SALT       = ByteArray(0)
        private const val TOKEN_LEN  = 16

        /** Generate a fresh ephemeral keypair + derived tokens. */
        fun generate(): QrCodeKey {
            val pair = EcKeys.generateP256()
            return fromKeyPair(pair)
        }

        /**
         * Build everything from an externally-supplied [pair] (handy for
         * deterministic unit tests).
         */
        fun fromKeyPair(pair: KeyPair): QrCodeKey {
            val pub = pair.public as ECPublicKey
            val keyBytes = encodePublicKey(pub)
            val token = Hkdf.derive(EMPTY_SALT, keyBytes, INFO_ADVERTISING, TOKEN_LEN)
            val nameKey = Hkdf.derive(EMPTY_SALT, keyBytes, INFO_ENCRYPTION, TOKEN_LEN)
            return QrCodeKey(
                keyBytes         = keyBytes,
                urlSafeBase64    = Base64Url.encode(keyBytes),
                advertisingToken = token,
                nameEncryptionKey = nameKey,
                keyPair          = pair,
            )
        }

        /**
         * Encode a P-256 public key as `[0x00, 0x00, 0x02|0x03, X(32)]`.
         *
         * Y-parity → the third byte: `0x02` if `Y` is even, `0x03` if odd
         * (standard SEC1 compressed-point convention).
         */
        internal fun encodePublicKey(pub: ECPublicKey): ByteArray {
            val w = pub.w
            val parity: Byte = if (w.affineY.testBit(0)) 0x03 else 0x02
            val x32 = unsigned32(w.affineX)
            val out = ByteArray(35)
            out[0] = 0x00
            out[1] = 0x00
            out[2] = parity
            System.arraycopy(x32, 0, out, 3, 32)
            return out
        }

        /**
         * Big-endian, unsigned magnitude, **exactly 32 bytes**.
         *
         *  - Strips a leading 0x00 sign byte that `BigInteger.toByteArray()`
         *    inserts when the high bit of `X` is set (Quick Share rejects it).
         *  - Left-pads with zeros for the (very rare) X values that fit in
         *    fewer than 32 bytes — same wire layout as a stock-emitted QR.
         */
        private fun unsigned32(v: BigInteger): ByteArray {
            val raw = v.toByteArray()
            return when {
                raw.size == 32 -> raw
                raw.size > 32  -> raw.copyOfRange(raw.size - 32, raw.size)
                else           -> ByteArray(32).also {
                    System.arraycopy(raw, 0, it, 32 - raw.size, raw.size)
                }
            }
        }
    }
}
