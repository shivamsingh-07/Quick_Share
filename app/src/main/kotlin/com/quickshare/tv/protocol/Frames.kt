package com.quickshare.tv.protocol

import com.google.protobuf.ByteString
import com.quickshare.tv.proto.offline.ConnectionRequestFrame
import com.quickshare.tv.proto.offline.ConnectionResponseFrame
import com.quickshare.tv.proto.offline.DisconnectionFrame
import com.quickshare.tv.proto.offline.KeepAliveFrame
import com.quickshare.tv.proto.offline.OfflineFrame
import com.quickshare.tv.proto.offline.OsInfo
import com.quickshare.tv.proto.offline.PayloadChunk
import com.quickshare.tv.proto.offline.PayloadHeader
import com.quickshare.tv.proto.offline.PayloadTransferFrame
import com.quickshare.tv.proto.offline.V1Frame

/**
 * Pure-function builders for OfflineFrame variants.
 *
 * The chunking helpers come in **pairs**: a "data" frame that carries body
 * bytes with `flags=0`, and a "terminator" frame that carries an empty body
 * with `flags=LAST_CHUNK`. Stock Quick Share / `rqs_lib` finalize a payload
 * only on the empty-body terminator. A single self-terminating frame works
 * against tolerant peers but deadlocks the strict ones.
 */
object Frames {
    private const val FLAG_LAST_CHUNK = 0x1

    fun connectionRequest(
        endpointId: String,
        endpointName: String,
        endpointInfo: ByteArray,
        nonce: Int = 0,
    ): OfflineFrame =
        offline(
            V1Frame.FrameType.CONNECTION_REQUEST,
            connection_request = ConnectionRequestFrame.newBuilder()
                .setEndpointId(endpointId)
                .setEndpointName(endpointName)
                .setEndpointInfo(ByteString.copyFrom(endpointInfo))
                .setNonce(nonce)
                // Quick Share advertises WIFI_LAN as the only supported medium.
                // We don't negotiate bandwidth upgrades, just like `rqs_lib`.
                .addMediums(ConnectionRequestFrame.Medium.WIFI_LAN)
                .build()
        )

    /**
     * Disconnection control frame. The proto field exists to participate in
     * the safe-disconnect protocol; our peers (and `rqs_lib`) treat any
     * Disconnection frame as a clean shutdown signal regardless of the flag.
     */
    fun disconnection(): OfflineFrame =
        offline(
            V1Frame.FrameType.DISCONNECTION,
            disconnection = DisconnectionFrame.newBuilder()
                .setRequestSafeToDisconnect(false)
                .build()
        )

    fun connectionResponse(accept: Boolean): OfflineFrame =
        offline(
            V1Frame.FrameType.CONNECTION_RESPONSE,
            connection_response = ConnectionResponseFrame.newBuilder()
                .setResponse(if (accept) ConnectionResponseFrame.ResponseStatus.ACCEPT
                             else        ConnectionResponseFrame.ResponseStatus.REJECT)
                // ANDROID is honest. `rqs_lib` sends LINUX (=100) but stock
                // peers also accept ANDROID without complaint.
                .setOsInfo(OsInfo.newBuilder().setType(OsInfo.OsType.ANDROID))
                .build()
        )

    fun keepAlive(ack: Boolean): OfflineFrame =
        offline(
            V1Frame.FrameType.KEEP_ALIVE,
            keep_alive = KeepAliveFrame.newBuilder().setAck(ack).build()
        )

    // ────────────────────────── BYTES payload ──────────────────────────────
    //
    // Quick Share BYTES carriers are usually sub-chunk-sized so the data part
    // contains the full body. The receiver MUST also see the empty-body
    // terminator before it'll decode the buffer as an inner Sharing Frame.

    fun bytesPayloadDataChunk(payloadId: Long, body: ByteArray): OfflineFrame =
        payloadTransfer(
            type = PayloadHeader.PayloadType.BYTES,
            id = payloadId,
            totalSize = body.size.toLong(),
            offset = 0L,
            body = body,
            lastChunk = false,
        )

    fun bytesPayloadTerminator(payloadId: Long, totalSize: Long): OfflineFrame =
        payloadTransfer(
            type = PayloadHeader.PayloadType.BYTES,
            id = payloadId,
            totalSize = totalSize,
            offset = totalSize,
            body = EMPTY_BODY,
            lastChunk = true,
        )

    // ────────────────────────── FILE payload ───────────────────────────────
    //
    // File transfers chunk at 512 KiB. After the final non-empty data chunk,
    // send `fileChunkTerminator(...)` separately — never piggyback the
    // LAST_CHUNK flag on the final data chunk.

    fun fileChunkData(
        payloadId: Long,
        fileName: String,
        totalSize: Long,
        offset: Long,
        body: ByteArray,
    ): OfflineFrame =
        payloadTransfer(
            type = PayloadHeader.PayloadType.FILE,
            id = payloadId,
            totalSize = totalSize,
            fileName = fileName,
            offset = offset,
            body = body,
            lastChunk = false,
        )

    fun fileChunkTerminator(
        payloadId: Long,
        fileName: String,
        totalSize: Long,
    ): OfflineFrame =
        payloadTransfer(
            type = PayloadHeader.PayloadType.FILE,
            id = payloadId,
            totalSize = totalSize,
            fileName = fileName,
            offset = totalSize,
            body = EMPTY_BODY,
            lastChunk = true,
        )

    fun isLastChunk(c: PayloadChunk): Boolean = (c.flags and FLAG_LAST_CHUNK) != 0

    // -------------------------------------------------------------------------

    private val EMPTY_BODY: ByteArray = ByteArray(0)

    private fun payloadTransfer(
        type: PayloadHeader.PayloadType,
        id: Long,
        totalSize: Long,
        offset: Long,
        body: ByteArray,
        lastChunk: Boolean,
        fileName: String? = null,
    ): OfflineFrame {
        val header = PayloadHeader.newBuilder()
            .setId(id)
            .setType(type)
            .setTotalSize(totalSize)
            .apply { fileName?.let(::setFileName) }
        val chunk = PayloadChunk.newBuilder()
            .setOffset(offset)
            .setBody(ByteString.copyFrom(body))
            .setFlags(if (lastChunk) FLAG_LAST_CHUNK else 0)
        return offline(
            V1Frame.FrameType.PAYLOAD_TRANSFER,
            payload_transfer = PayloadTransferFrame.newBuilder()
                .setPacketType(PayloadTransferFrame.PacketType.DATA)
                .setPayloadHeader(header)
                .setPayloadChunk(chunk)
                .build(),
        )
    }

    private fun offline(
        type: V1Frame.FrameType,
        connection_request: ConnectionRequestFrame? = null,
        connection_response: ConnectionResponseFrame? = null,
        payload_transfer: PayloadTransferFrame? = null,
        keep_alive: KeepAliveFrame? = null,
        disconnection: DisconnectionFrame? = null,
    ): OfflineFrame {
        val v1 = V1Frame.newBuilder().setType(type)
        connection_request?.let  { v1.connectionRequest  = it }
        connection_response?.let { v1.connectionResponse = it }
        payload_transfer?.let    { v1.payloadTransfer    = it }
        keep_alive?.let          { v1.keepAlive          = it }
        disconnection?.let       { v1.disconnection      = it }
        return OfflineFrame.newBuilder()
            .setVersion(OfflineFrame.Version.V1)
            .setV1(v1)
            .build()
    }
}
