package com.quickshare.tv.crypto

import com.quickshare.tv.network.EndpointInfo
import com.quickshare.tv.util.Log
import com.quickshare.tv.util.toHex
import javax.crypto.AEADBadTagException

/**
 * Decides whether a discovered mDNS peer is the receiver that scanned our QR.
 *
 * The strongest check is byte-for-byte equivalence of the peer's TLV-1
 * record with the 16-byte HKDF-derived [QrCodeKey.advertisingToken]:
 *
 *  - The TV (sender) generates an EC P-256 key, encodes the public key in
 *    the QR URL, and derives the 16-byte advertising token from it.
 *  - The phone (receiver) scans the QR, derives the same token, and starts
 *    advertising over mDNS with that token in TLV-1 of its EndpointInfo.
 *  - We browse mDNS and [matches] returns `true` for the first peer whose
 *    TLV-1 is exactly that token.
 *
 * Some stock builds publish a longer TLV-1 record after QR scan (observed:
 * 41 bytes: 12-byte nonce + AES-GCM encrypted receiver name) instead of the
 * raw 16-byte token. [decryptHiddenName] authenticates that record with the
 * QR name-encryption key, giving the send path a verified QR match without
 * accepting unrelated anonymous peers.
 *
 * # Hidden-record layout tolerance
 *
 * The canonical layout (NearDrop / cross-client consensus) is:
 *
 * ```
 *   bytes 0..11  : 12-byte AES-GCM nonce
 *   bytes 12..N-17 : ciphertext (UTF-8 device name)
 *   bytes N-16..N-1 : 16-byte GCM authentication tag
 * ```
 *
 * One-OEM variant prepends a single version byte (`0x00` / `0x01`); we try
 * the canonical offset first and only fall back to offset 1 when the
 * tag check fails for offset 0. This keeps the canonical fast-path
 * unchanged while letting the decryption succeed against firmwares that
 * deviate. Either path emits a one-line diagnostic so a real-world
 * "evidence matched but token did not" report carries the failure cause
 * (bad tag, key mismatch, garbled UTF-8) instead of silently degrading
 * to the receiver-confirmation fallback.
 */
class QrPeerMatcher(private val key: QrCodeKey) {

    /**
     * `true` iff the peer's TLV-1 is the 16-byte advertising token derived
     * from the same QR key the user scanned. Returns `false` for peers
     * carrying no TLV-1 at all, a TLV-1 of any other length (e.g. an
     * encrypted-name blob), or someone else's token.
     */
    fun matches(info: EndpointInfo): Boolean {
        val tlv = info.qrCodeData ?: return false
        return tlv.size == TOKEN_LEN && tlv.contentEquals(key.advertisingToken)
    }

    /**
     * Try to decrypt the hidden TLV-1 record into a device name.
     *
     * Returns the decoded name on success and `null` when no usable
     * plaintext can be recovered. The first failure for a given TLV is
     * logged at WARN with the GCM error message + length so field
     * diagnostics aren't blind; we explicitly do not log the plaintext
     * (could leak the user's device name on noisy bug-report uploads).
     */
    fun decryptHiddenName(info: EndpointInfo): String? {
        val tlv = info.qrCodeData ?: return null
        // Canonical layout is 12-byte nonce + ciphertext + 16-byte tag, so
        // the absolute minimum useful length is 12 + 1 + 16 = 29 bytes.
        // (One-byte plaintext is not unusual when the OEM truncates the
        // device name to a single character before encryption.)
        if (tlv.size < QR_NONCE_LEN + GCM_TAG_LEN + 1) return null

        // Strategy 1: canonical NearDrop layout, offset 0.
        decryptAt(tlv, offset = 0, label = "v0")?.let { return it }

        // Strategy 2: tolerate a single leading version byte (some OEM
        // firmwares prepend 0x00/0x01 ahead of the nonce). Skip the
        // try unless we have at least one extra byte to throw away.
        if (tlv.size >= 1 + QR_NONCE_LEN + GCM_TAG_LEN + 1) {
            decryptAt(tlv, offset = 1, label = "v1")?.let { return it }
        }
        return null
    }

    fun hasVerifiedQrEvidence(info: EndpointInfo): Boolean =
        matches(info) || decryptHiddenName(info) != null

    fun hasQrEvidence(info: EndpointInfo): Boolean =
        info.qrCodeData != null

    /**
     * Try AES-GCM decryption assuming the nonce starts at [offset].
     * Returns the decoded device name on success, `null` otherwise. Logs
     * once per call at the appropriate level so a token-mismatched record
     * surfaces the AEAD reason in the field log.
     */
    private fun decryptAt(tlv: ByteArray, offset: Int, label: String): String? {
        val nonceEnd = offset + QR_NONCE_LEN
        if (tlv.size < nonceEnd + GCM_TAG_LEN + 1) return null
        val nonce = tlv.copyOfRange(offset, nonceEnd)
        val ciphertextAndTag = tlv.copyOfRange(nonceEnd, tlv.size)
        val plaintext = try {
            AesGcm.decrypt(key.nameEncryptionKey, nonce, ciphertextAndTag)
        } catch (t: AEADBadTagException) {
            // Tag mismatch is the *expected* outcome when this offset is
            // wrong (we'll try the other one) OR when the peer's record
            // belongs to someone else's QR session — surface it as INFO so
            // it doesn't read as a real error in production logs.
            Log.d(SCOPE) {
                "AES-GCM tag mismatch decrypting hidden TLV ($label, " +
                    "${tlv.size}B head=${tlv.toHex(8)})"
            }
            return null
        } catch (t: Throwable) {
            Log.w(
                SCOPE,
                "AES-GCM decrypt failed ($label, ${tlv.size}B head=${tlv.toHex(8)}): " +
                    "${t.javaClass.simpleName}: ${t.message}",
            )
            return null
        }
        val name = runCatching { plaintext.toString(Charsets.UTF_8).trim() }.getOrNull()
        if (name.isNullOrEmpty() || !name.any(Char::isLetterOrDigit)) {
            Log.d(SCOPE) {
                "Decrypted hidden TLV ($label) but plaintext failed sanity " +
                    "(empty / non-printable, ${plaintext.size}B)"
            }
            return null
        }
        if (label != "v0") {
            Log.i(SCOPE, "Decrypted hidden TLV via fallback layout '$label' (${tlv.size}B)")
        }
        return name
    }

    companion object {
        private const val SCOPE = "QrPeerMatcher"
        /** Length of the HKDF-derived advertising token in bytes. */
        private const val TOKEN_LEN = 16
        private const val QR_NONCE_LEN = 12
        private const val GCM_TAG_LEN = 16
    }
}
