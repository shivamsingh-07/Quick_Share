package com.quickshare.tv.protocol

import com.quickshare.tv.proto.offline.ConnectionRequestFrame
import com.quickshare.tv.proto.offline.OfflineFrame
import com.quickshare.tv.proto.offline.OsInfo
import com.quickshare.tv.proto.offline.PayloadHeader
import com.quickshare.tv.proto.offline.V1Frame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke tests for the OfflineFrame builders. These pin the exact wire shape
 * produced by `Frames.kt` so we'd notice immediately if a Quick Share-required
 * field stops being set.
 */
class FramesTest {

    @Test
    fun `connectionRequest carries distinct endpointId, endpointName, and WIFI_LAN medium`() {
        val req = Frames.connectionRequest(
            endpointId   = "aBcD",
            endpointName = "Living Room TV",
            endpointInfo = byteArrayOf(0x06, 0x10, 0x20),
        )
        val parsed = OfflineFrame.parseFrom(req.toByteArray())
        assertEquals(V1Frame.FrameType.CONNECTION_REQUEST, parsed.v1.type)
        val cr = parsed.v1.connectionRequest
        assertEquals("aBcD", cr.endpointId)
        assertEquals("Living Room TV", cr.endpointName)
        assertNotEquals(cr.endpointId, cr.endpointName)
        assertArrayEquals(byteArrayOf(0x06, 0x10, 0x20), cr.endpointInfo.toByteArray())
        assertEquals(listOf(ConnectionRequestFrame.Medium.WIFI_LAN), cr.mediumsList)
    }

    @Test
    fun `connectionResponse populates response status and ANDROID os_info`() {
        val accept = OfflineFrame.parseFrom(Frames.connectionResponse(true).toByteArray())
        val reject = OfflineFrame.parseFrom(Frames.connectionResponse(false).toByteArray())
        assertEquals(
            com.quickshare.tv.proto.offline.ConnectionResponseFrame.ResponseStatus.ACCEPT,
            accept.v1.connectionResponse.response,
        )
        assertEquals(
            com.quickshare.tv.proto.offline.ConnectionResponseFrame.ResponseStatus.REJECT,
            reject.v1.connectionResponse.response,
        )
        assertEquals(OsInfo.OsType.ANDROID, accept.v1.connectionResponse.osInfo.type)
    }

    @Test
    fun `disconnection frame routes through V1Frame field 7`() {
        val frame = Frames.disconnection()
        val parsed = OfflineFrame.parseFrom(frame.toByteArray())
        assertEquals(V1Frame.FrameType.DISCONNECTION, parsed.v1.type)
        assertTrue(parsed.v1.hasDisconnection())
    }

    @Test
    fun `bytes payload data chunk has flags=0 and full body, terminator has flags=LAST and empty body`() {
        val body = byteArrayOf(1, 2, 3, 4, 5)
        val data = OfflineFrame.parseFrom(Frames.bytesPayloadDataChunk(42L, body).toByteArray())
        val term = OfflineFrame.parseFrom(Frames.bytesPayloadTerminator(42L, body.size.toLong()).toByteArray())

        val dataPt = data.v1.payloadTransfer
        val termPt = term.v1.payloadTransfer
        assertEquals(PayloadHeader.PayloadType.BYTES, dataPt.payloadHeader.type)
        assertEquals(42L, dataPt.payloadHeader.id)
        assertEquals(body.size.toLong(), dataPt.payloadHeader.totalSize)
        assertArrayEquals(body, dataPt.payloadChunk.body.toByteArray())
        assertFalse(Frames.isLastChunk(dataPt.payloadChunk))

        assertEquals(42L, termPt.payloadHeader.id)
        assertEquals(0, termPt.payloadChunk.body.size())
        assertEquals(body.size.toLong(), termPt.payloadChunk.offset)
        assertTrue(Frames.isLastChunk(termPt.payloadChunk))
    }

    @Test
    fun `file chunk data has flags=0, terminator has empty body and flags=LAST`() {
        val data = OfflineFrame.parseFrom(
            Frames.fileChunkData(7L, "f.bin", totalSize = 1000L, offset = 0L, body = ByteArray(800))
                .toByteArray()
        )
        val term = OfflineFrame.parseFrom(
            Frames.fileChunkTerminator(7L, "f.bin", totalSize = 1000L).toByteArray()
        )
        val dataPt = data.v1.payloadTransfer
        val termPt = term.v1.payloadTransfer
        assertEquals(PayloadHeader.PayloadType.FILE, dataPt.payloadHeader.type)
        assertEquals("f.bin", dataPt.payloadHeader.fileName)
        assertEquals(800, dataPt.payloadChunk.body.size())
        assertFalse(Frames.isLastChunk(dataPt.payloadChunk))

        assertEquals(0, termPt.payloadChunk.body.size())
        assertEquals(1000L, termPt.payloadChunk.offset)
        assertTrue(Frames.isLastChunk(termPt.payloadChunk))
    }
}
