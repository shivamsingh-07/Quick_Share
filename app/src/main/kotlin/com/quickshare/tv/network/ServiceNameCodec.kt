package com.quickshare.tv.network

import com.quickshare.tv.util.Base64Url
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The 10-byte instance name used as the **mDNS service name** for Quick Share.
 *
 * Per Nearby Connections / Quick Share wire format (see `IMPLEMENTATION.md`):
 *
 * ```
 *   byte 0      PCP            : 0x23  → "Visible to everyone"
 *   bytes 1..4  endpoint ID    : 4 random bytes, stable for the lifetime of
 *                                 the advertisement
 *   bytes 5..7  service-id-hash: SHA-256("NearbySharing")[0..3]
 *   bytes 8..9  reserved       : 0x00, 0x00
 * ```
 *
 * The whole 10-byte blob is base64-encoded (URL-safe, no padding) and used as
 * the service instance name. Android validates the PCP byte and the 3-byte
 * service-id-hash, so getting any of the first eight bytes wrong breaks
 * discovery silently — the remote device just won't show up as a Quick Share
 * peer.
 *
 * The richer per-device data (device name, type, identity bytes, optional QR
 * token, etc.) lives in the TXT `n` record, encoded by [EndpointInfo].
 */
object ServiceNameCodec {

    /** PCP value for "visible to everyone" — the only mode this app advertises. */
    const val PCP_EVERYONE = 0x23

    /** Pre-computed SHA-256("NearbySharing")[0..3], cached. */
    private val SERVICE_ID_HASH_3: ByteArray by lazy {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("NearbySharing".toByteArray(Charsets.UTF_8))
        md.digest().copyOfRange(0, 3)
    }

    /**
     * Random 4-byte endpoint ID drawn from the ASCII alphanumeric alphabet
     * (`a-z A-Z 0-9`). Both Quick Share and `rqs_lib` derive the endpoint ID
     * this way (`rand::distr::Alphanumeric`) and embed the same 4 bytes in:
     *   - the mDNS service-instance name (positions 1..4), AND
     *   - the `ConnectionRequest.endpoint_id` UTF-8 string field.
     *
     * Using arbitrary bytes here works for self-discovery but causes stock
     * Quick Share to log a warning and may suppress the device entry.
     */
    fun newEndpointId(rng: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(4) { ALPHA_NUMERIC[rng.nextInt(ALPHA_NUMERIC.size)] }

    private val ALPHA_NUMERIC: ByteArray =
        ("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789").toByteArray(Charsets.US_ASCII)

    /** Build the raw 10-byte instance-name blob. */
    fun encode(endpointId: ByteArray, pcp: Int = PCP_EVERYONE): ByteArray {
        require(endpointId.size == 4) { "endpointId must be 4 bytes (got ${endpointId.size})" }
        val out = ByteArray(10)
        out[0] = pcp.toByte()
        System.arraycopy(endpointId, 0, out, 1, 4)
        System.arraycopy(SERVICE_ID_HASH_3, 0, out, 5, 3)
        // out[8] / out[9] left as 0x00.
        return out
    }

    /** Convenience wrapper: returns the URL-safe base64 string used on the wire. */
    fun encodeBase64(endpointId: ByteArray, pcp: Int = PCP_EVERYONE): String =
        Base64Url.encode(encode(endpointId, pcp))

    /** Parse a wire instance-name back into its parts; null on invalid shape. */
    fun decodeBase64(serviceName: String): Decoded? {
        val raw = runCatching { Base64Url.decode(serviceName) }.getOrNull() ?: return null
        if (raw.size != 10) return null
        if (!raw.copyOfRange(5, 8).contentEquals(SERVICE_ID_HASH_3)) return null
        return Decoded(
            pcp = raw[0].toInt() and 0xFF,
            endpointId = raw.copyOfRange(1, 5),
        )
    }

    data class Decoded(val pcp: Int, val endpointId: ByteArray) {
        // Sensible structural equality for tests.
        override fun equals(other: Any?): Boolean = other is Decoded &&
            pcp == other.pcp && endpointId.contentEquals(other.endpointId)
        override fun hashCode(): Int = pcp * 31 + endpointId.contentHashCode()
    }
}
