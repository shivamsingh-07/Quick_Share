package com.quickshare.tv.network

import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * The blob carried in the mDNS TXT record `n` for Quick Share. The wire layout
 * is fixed by Google's reference and matches `rqs_lib::utils::gen_mdns_endpoint_info`
 * + NearDrop's `PROTOCOL.md` §"MDNS service":
 *
 * ```
 *   byte 0       : Version(3 bits) | Visibility(1 bit) | Device type(3 bits) | Reserved(1 bit)
 *                    bit 4    = hidden/non-public when set
 *                    bits 1..3 = device type (Unknown=0, Phone=1, Tablet=2, Laptop=3)
 *                    bit 0    = reserved
 *   bytes 1..16  : 16 random salt bytes (CSPRNG)
 *   byte 17      : device-name length in UTF-8 bytes (≤ 0xFF), only when
 *                  the peer is visible / includes a public name
 *   bytes 18..N  : device-name UTF-8 bytes, only when a name is present
 *   bytes N+1..M : optional TLV records, each laid out as:
 *                    1 byte  type
 *                    1 byte  length (number of value bytes that follow)
 *                    L bytes value
 *                  Known types:
 *                    1 = QR-code data (16-byte advertising token for
 *                        peers that scanned a QR; some Hidden-mode peers
 *                        also publish a longer AES-GCM blob here, which
 *                        we ignore on purpose — see [QrPeerMatcher])
 *                    2 = vendor ID (1 byte: 0=none, 1=Samsung, ...)
 * ```
 *
 * The whole blob is base64url-encoded (no padding) by callers when published
 * over mDNS. *DO NOT* put visibility / PCP info in here — that lives only in
 * the service-instance name (see [ServiceNameCodec]).
 *
 * The TV only ever decodes the QR TLV (it's the receiver's job to advertise
 * one when reacting to a scanned QR URL); we never populate it on encode,
 * since stock Quick Share doesn't show a QR for inbound transfers.
 */
data class EndpointInfo(
    val deviceName: String,
    val deviceType: DeviceType,
    /**
     * Raw bytes of TLV-1 ("QR code data") if present. Decoders should hand
     * this to `QrPeerMatcher` to decide whether the peer is the one that
     * scanned our QR — peers that scanned us put the 16-byte advertising
     * token here verbatim. Other lengths (e.g. the AES-GCM hidden-name
     * blobs some Hidden-mode peers publish) are ignored by our matcher
     * by design; we rely on the regular mDNS `n` TXT record for the
     * display name.
     */
    val qrCodeData: ByteArray? = null,
) {
    /**
     * Mirrors the upstream Quick Share enum exactly. We deliberately *do not*
     * add a `TV` value because real Quick Share peers will map any unknown
     * value back to `UNKNOWN`. We advertise as [LAPTOP] from Android TV.
     */
    enum class DeviceType(val code: Int) {
        UNKNOWN(0),
        PHONE(1),
        TABLET(2),
        LAPTOP(3),
    }

    fun encode(rng: SecureRandom = SecureRandom()): ByteArray {
        val nameBytes = deviceName.toByteArray(Charsets.UTF_8)
        require(nameBytes.size <= 0xFF) { "Device name too long (${nameBytes.size} bytes, max 255)" }
        val salt = ByteArray(16).also(rng::nextBytes)

        val out = ByteArrayOutputStream(1 + 16 + 1 + nameBytes.size)
        out.write((deviceType.code shl 1) and 0b111)
        out.write(salt)
        out.write(nameBytes.size)
        out.write(nameBytes)
        return out.toByteArray()
    }

    /**
     * Custom equality so two [EndpointInfo]s with structurally equal
     * [qrCodeData] (same bytes) compare equal — the default `data class`
     * `equals()` uses reference equality on `ByteArray`, which would break
     * `DiscoveredPeer` deduping and tests.
     */
    override fun equals(other: Any?): Boolean = other is EndpointInfo &&
        deviceName == other.deviceName &&
        deviceType == other.deviceType &&
        (qrCodeData?.contentEquals(other.qrCodeData) ?: (other.qrCodeData == null))

    override fun hashCode(): Int {
        var h = deviceName.hashCode()
        h = h * 31 + deviceType.hashCode()
        h = h * 31 + (qrCodeData?.contentHashCode() ?: 0)
        return h
    }

    companion object {
        /** TLV record type for "QR code data" — see class kdoc. */
        private const val TLV_TYPE_QR = 1

        fun decode(bytes: ByteArray): EndpointInfo? {
            // Smallest valid blob is a hidden / non-public endpoint:
            // 1 bitfield byte + 16 metadata bytes. Visible endpoints add
            // byte 17 as the public-name length, then the name bytes.
            //
            // This matches NearDrop's PROTOCOL.md: "User-visible device name
            // in UTF-8, prefixed with 1-byte length. Only present if the
            // visibility bit in the first byte is 0." In practice, modern
            // stock Quick Share can publish a 17-byte endpoint info in TXT
            // `n` while still carrying reachability via SRV/TXT IPv4. Treat
            // that as a valid endpoint with an empty display name so the
            // device picker can show a type-based fallback ("Phone").
            if (bytes.size < PREFIX_LEN) return null
            val flags = bytes[0].toInt() and 0xFF
            val visible = ((flags shr 4) and 1) == 0
            val typeCode = (flags shr 1) and 0b111
            val type = DeviceType.values().firstOrNull { it.code == typeCode } ?: DeviceType.UNKNOWN
            if (bytes.size == PREFIX_LEN) return EndpointInfo("", type, qrCodeData = null)

            val name: String
            val tlvStart: Int
            if (visible) {
                val nameLen = bytes[17].toInt() and 0xFF
                val nameEnd = 18 + nameLen
                if (bytes.size < nameEnd) return null
                name = String(bytes, 18, nameLen, Charsets.UTF_8)
                tlvStart = nameEnd
            } else {
                // Hidden endpoints omit the public name length entirely.
                // Any bytes after the 17-byte prefix are TLV records, with
                // TLV-1 carrying the QR advertising token or encrypted name.
                name = ""
                tlvStart = PREFIX_LEN
            }

            // Parse trailing TLV records. Stop on the first malformed record
            // (matches NearDrop's NearbyConnectionManager.swift parser, which
            // also bails silently on truncated TLVs). We only care about
            // type=1; unknown types are skipped over.
            var qrData: ByteArray? = null
            var off = tlvStart
            while (bytes.size - off >= 2) {
                val tlvType = bytes[off].toInt() and 0xFF
                val tlvLen  = bytes[off + 1].toInt() and 0xFF
                off += 2
                if (bytes.size - off < tlvLen) break // truncated value
                if (tlvType == TLV_TYPE_QR) {
                    qrData = bytes.copyOfRange(off, off + tlvLen)
                }
                off += tlvLen
            }
            return EndpointInfo(name, type, qrData)
        }

        private const val PREFIX_LEN = 17
    }
}

