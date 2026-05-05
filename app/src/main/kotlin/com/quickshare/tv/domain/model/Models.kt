package com.quickshare.tv.domain.model

/** Stable identity for an active transfer (one per peer connection). */
@JvmInline value class TransferId(val value: String)

/** Description of a file the sender is offering, or that we're receiving. */
data class FileMeta(
    val payloadId: Long,
    val name: String,
    val size: Long,
    val mimeType: String? = null,
)

/** What we render in a discovery list / what gets advertised. */
data class Peer(
    val displayName: String,
    val deviceType: String,
    val host: String,
    val port: Int,
)

/**
 * Coarse classification of a peer for picker-row icon selection. We
 * collapse the four Quick Share `EndpointInfo.DeviceType` values
 * (UNKNOWN, PHONE, TABLET, LAPTOP) plus BLE-only entries into four
 * named buckets the UI cares about. Peers we can't classify (or
 * BLE-only entries with no mDNS hint) render with the [UNKNOWN] icon.
 */
enum class DeviceKind { PHONE, TABLET, LAPTOP, TV, UNKNOWN }

/**
 * One row in the device-picker list (Send screen, "Nearby Devices").
 *
 * Every row in the picker is connectable by construction: the discovery
 * stack only emits a peer once it has both an mDNS-resolved host:port
 * AND a usable visible device name (anonymous "hidden" peers are held
 * back until they refresh with a name; see
 * [com.quickshare.tv.network.mdns.MdnsDiscoverer.observe]).
 *
 * [id] is the mDNS service-instance name — globally unique per session,
 * stable across re-resolves of the same service.
 */
data class DiscoveredDevice(
    val id: String,
    val displayName: String,
    val kind: DeviceKind,
)

/**
 * Local-side endpoint identity carried in the plaintext ConnectionRequest
 * OfflineFrame that *must* precede UKEY2 (per Nearby Connections spec).
 *
 *  - [endpointId]   : 4 ASCII-alphanumeric bytes (`rand::distr::Alphanumeric`),
 *                     used both as the mDNS service-name endpoint ID AND as
 *                     the UTF-8 string `ConnectionRequest.endpoint_id`.
 *  - [endpointName] : human display name, e.g. "Living Room TV".
 *  - [endpointInfo] : the EndpointInfo blob (`[type<<1][16 random][nameLen][name]`)
 *                     — same bytes published as mDNS TXT record `n`.
 */
data class LocalEndpoint(
    val endpointId: ByteArray,
    val endpointName: String,
    val endpointInfo: ByteArray,
) {
    /**
     * The endpoint id as a 4-character ASCII string, suitable for the
     * `ConnectionRequest.endpoint_id` protobuf field. We assume the bytes are
     * already ASCII alphanumerics (see `ServiceNameCodec.newEndpointId`); any
     * stray non-ASCII byte will be replaced by U+FFFD on decode.
     */
    fun endpointIdString(): String = String(endpointId, Charsets.US_ASCII)

    override fun equals(other: Any?): Boolean = other is LocalEndpoint &&
        endpointId.contentEquals(other.endpointId) &&
        endpointName == other.endpointName &&
        endpointInfo.contentEquals(other.endpointInfo)

    override fun hashCode(): Int =
        (endpointId.contentHashCode() * 31 + endpointName.hashCode()) * 31 +
            endpointInfo.contentHashCode()
}

/** Identity of the *peer* we just completed a ConnectionRequest exchange with. */
data class PeerIdentity(
    val endpointId: String,
    val endpointName: String,
    val endpointInfo: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is PeerIdentity &&
        endpointId == other.endpointId &&
        endpointName == other.endpointName &&
        endpointInfo.contentEquals(other.endpointInfo)
    override fun hashCode(): Int =
        (endpointId.hashCode() * 31 + endpointName.hashCode()) * 31 +
            endpointInfo.contentHashCode()
}

sealed interface ReceiveEvent {
    object Listening : ReceiveEvent
    data class Connected(val remote: String) : ReceiveEvent
    /**
     * Sender peer announced after the plaintext ConnectionRequest is decoded
     * but before the secure channel finishes. [peerKind] is best-effort: we
     * pull it from the sender's EndpointInfo when present and fall back to
     * [DeviceKind.UNKNOWN] for stock peers that omit a useful type byte.
     */
    data class PeerIntroduced(
        val peerName: String,
        val peerKind: DeviceKind = DeviceKind.UNKNOWN,
    ) : ReceiveEvent
    data class Handshaked(val authString: String, val pin: String) : ReceiveEvent
    /** When [needsPrompt] is false (auto-accept), UI skips Accept/Reject. */
    data class IntroductionReceived(
        val files: List<FileMeta>,
        val sender: String,
        val needsPrompt: Boolean = true,
    ) : ReceiveEvent
    data class Progress(val payloadId: Long, val received: Long, val total: Long) : ReceiveEvent
    data class FileSaved(val payloadId: Long, val path: String) : ReceiveEvent
    data class Done(val saved: List<String>) : ReceiveEvent
    data class Failed(val cause: Throwable) : ReceiveEvent
}

sealed interface SendEvent {
    object Idle : SendEvent
    data class QrReady(val url: String, val pngBytes: ByteArray) : SendEvent
    data class Connecting(val peer: Peer) : SendEvent
    data class Handshaked(val authString: String, val pin: String) : SendEvent
    object Awaiting : SendEvent  // waiting for receiver's accept/reject
    data class Progress(val payloadId: Long, val sent: Long, val total: Long) : SendEvent
    object Done : SendEvent
    data class Failed(val cause: Throwable) : SendEvent
}
