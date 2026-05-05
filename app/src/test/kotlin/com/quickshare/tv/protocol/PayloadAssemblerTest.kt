package com.quickshare.tv.protocol

import com.google.protobuf.ByteString
import com.quickshare.tv.proto.offline.PayloadChunk
import com.quickshare.tv.proto.offline.PayloadHeader
import com.quickshare.tv.proto.offline.PayloadTransferFrame
import com.quickshare.tv.protocol.payload.PayloadAssembler
import com.quickshare.tv.system.storage.TempDirFileSinkFactory
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Quick Share completion semantics:
 *  - BYTES finishes when the empty-body LAST_CHUNK terminator arrives,
 *    *after* all data chunks have been buffered.
 *  - FILE finishes the same way; reaching `total_size` is not enough.
 *
 * Tests use [TempDirFileSinkFactory] because the production
 * [com.quickshare.tv.system.storage.MediaStoreDownloadsFileSinkFactory] needs
 * a real Context + ContentResolver — it's covered by instrumented tests
 * separately, not by JVM unit tests.
 */
class PayloadAssemblerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `bytes payload completes only on empty-body LAST_CHUNK`() {
        val asm = PayloadAssembler(TempDirFileSinkFactory(tmp.newFolder("out")))
        val full = ByteArray(1024) { it.toByte() }

        // Two-frame split: data chunk (offset=0, flags=0, body=full) +
        // terminator (offset=full.size, flags=LAST, body=[]).
        val (_, completedAfterData) = asm.apply(chunk(
            id = 1L, type = PayloadHeader.PayloadType.BYTES,
            total = full.size.toLong(), offset = 0L, body = full, last = false,
        ))
        assertNull(completedAfterData)

        val (_, completedAfterTerm) = asm.apply(chunk(
            id = 1L, type = PayloadHeader.PayloadType.BYTES,
            total = full.size.toLong(), offset = full.size.toLong(),
            body = ByteArray(0), last = true,
        ))
        val bytes = (completedAfterTerm as PayloadAssembler.Completed.Bytes).body
        assertArrayEquals(full, bytes)
    }

    @Test
    fun `file payload completes only after empty-body LAST_CHUNK terminator`() {
        val outDir = tmp.newFolder("out")
        val asm = PayloadAssembler(TempDirFileSinkFactory(outDir))
        val full = ByteArray(8192) { (it * 31).toByte() }

        // Stream 1500-byte data chunks. None of them carry the LAST flag.
        val chunkSize = 1500
        var offset = 0
        while (offset < full.size) {
            val end = minOf(offset + chunkSize, full.size)
            val (_, completed) = asm.apply(chunk(
                id = 7L, type = PayloadHeader.PayloadType.FILE,
                total = full.size.toLong(), offset = offset.toLong(),
                body = full.copyOfRange(offset, end), last = false,
                fileName = "blob.bin",
            ))
            // Even the chunk that fills the file must NOT mark it complete.
            assertNull(completed)
            offset = end
        }

        // Now the empty-body LAST_CHUNK terminator finalises the file.
        val (_, completed) = asm.apply(chunk(
            id = 7L, type = PayloadHeader.PayloadType.FILE,
            total = full.size.toLong(), offset = full.size.toLong(),
            body = ByteArray(0), last = true, fileName = "blob.bin",
        ))
        val saved = (completed as PayloadAssembler.Completed.FileSaved)
        val onDisk = File(saved.path)
        assertEquals(full.size.toLong(), onDisk.length())
        assertArrayEquals(full, onDisk.readBytes())
    }

    @Test
    fun `registerExpectedFile preserves the introduction filename`() {
        val outDir = tmp.newFolder("out")
        val asm = PayloadAssembler(TempDirFileSinkFactory(outDir))

        // Simulate the introduction phase: name + mime are known before any
        // chunk arrives. The PayloadHeader.fileName (empty here) must NOT win.
        asm.registerExpectedFile(payloadId = 42L, name = "vacation.jpg", mime = "image/jpeg")

        val (_, completed) = asm.apply(chunk(
            id = 42L, type = PayloadHeader.PayloadType.FILE,
            total = 0L, offset = 0L, body = ByteArray(0), last = true,
            fileName = "",
        ))
        val saved = completed as PayloadAssembler.Completed.FileSaved
        assertEquals("vacation.jpg", File(saved.path).name)
    }

    private fun chunk(
        id: Long, type: PayloadHeader.PayloadType, total: Long, offset: Long,
        body: ByteArray, last: Boolean, fileName: String = "",
    ): PayloadTransferFrame =
        PayloadTransferFrame.newBuilder()
            .setPacketType(PayloadTransferFrame.PacketType.DATA)
            .setPayloadHeader(
                PayloadHeader.newBuilder()
                    .setId(id).setType(type).setTotalSize(total).setFileName(fileName)
            )
            .setPayloadChunk(
                PayloadChunk.newBuilder()
                    .setOffset(offset).setBody(ByteString.copyFrom(body))
                    .setFlags(if (last) 1 else 0)
            )
            .build()
}
