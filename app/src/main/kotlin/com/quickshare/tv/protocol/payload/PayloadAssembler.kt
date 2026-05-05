package com.quickshare.tv.protocol.payload

import com.quickshare.tv.proto.offline.PayloadHeader
import com.quickshare.tv.proto.offline.PayloadTransferFrame
import com.quickshare.tv.protocol.Frames
import com.quickshare.tv.system.storage.FileSink
import com.quickshare.tv.system.storage.FileSinkFactory
import com.quickshare.tv.util.Log
import java.io.Closeable

/**
 * Reassembles incoming PayloadTransfer frames into completed payloads.
 *
 * Two backends:
 *  - In-memory ByteArray for BYTES (Nearby Share uses these for the inner Frame
 *    metadata: PairedKey, Introduction, Response).
 *  - Streaming [FileSink] for FILE — chunks are forwarded directly to the sink
 *    (MediaStore.Downloads in production). We never buffer the whole file in
 *    memory and never see the bytes after they're written.
 *
 * Quick Share completion semantics: a payload finishes only when an empty-body
 * `LAST_CHUNK` terminator arrives, *not* when bytes_transferred reaches
 * total_size. A non-empty chunk that happens to fill the file leaves the
 * payload in flight until the terminator arrives. Senders that piggyback the
 * LAST_CHUNK flag on the final data chunk also work because the chunk is
 * empty by the time we see it (rare in practice).
 *
 * Per-payload mime/name overrides come from the IntroductionFrame via
 * [registerExpectedFile]; the inner PayloadHeader rarely carries either.
 */
class PayloadAssembler(private val sinkFactory: FileSinkFactory) : Closeable {

    sealed interface Completed {
        val payloadId: Long
        data class Bytes(override val payloadId: Long, val body: ByteArray) : Completed
        data class FileSaved(override val payloadId: Long, val name: String, val path: String) : Completed
    }

    data class Progress(val payloadId: Long, val received: Long, val total: Long)

    private data class Expected(val name: String, val mime: String?)
    private data class FileSlot(val name: String, val total: Long, val sink: FileSink)

    private val byteSinks = mutableMapOf<Long, ByteArrayBuf>()
    private val fileSinks = mutableMapOf<Long, FileSlot>()
    private val expected  = mutableMapOf<Long, Expected>()

    /**
     * Pre-register a file we expect to receive (from IntroductionFrame). The
     * sink will be opened lazily on the first FILE chunk so an unaccepted
     * transfer never touches user storage.
     */
    @Synchronized
    fun registerExpectedFile(payloadId: Long, name: String, mime: String?) {
        expected[payloadId] = Expected(name, mime)
    }

    /** Apply one frame; return either Completed (when LAST_CHUNK) or null. */
    @Synchronized
    fun apply(transfer: PayloadTransferFrame): Pair<Progress?, Completed?> {
        val header = transfer.payloadHeader
        val chunk  = transfer.payloadChunk
        val id     = header.id
        val total  = header.totalSize
        val isLast = Frames.isLastChunk(chunk)

        return when (header.type) {
            PayloadHeader.PayloadType.BYTES -> {
                val buf = byteSinks.getOrPut(id) { ByteArrayBuf(total) }
                buf.write(chunk.offset, chunk.body.toByteArray())
                val progress = Progress(id, buf.size, total)
                if (isLast) {
                    byteSinks.remove(id)
                    progress to Completed.Bytes(id, buf.toByteArray())
                } else {
                    progress to null
                }
            }

            PayloadHeader.PayloadType.FILE -> {
                val slot = fileSinks.getOrPut(id) {
                    val pre = expected[id]
                    val name = pre?.name?.takeIf { it.isNotBlank() }
                        ?: header.fileName.takeIf { it.isNotBlank() }
                        ?: "file_$id"
                    val mime = pre?.mime
                    val sink = sinkFactory.create(name, total, mime)
                    Log.d(SCOPE) { "Opened sink for payload=$id name='$name' size=${total}B mime=$mime" }
                    FileSlot(name, total, sink)
                }
                val body = chunk.body.toByteArray()
                slot.sink.write(chunk.offset, body)
                val received = chunk.offset + body.size
                val progress = Progress(id, received, slot.total)
                // Match Quick Share semantics: only an empty-body LAST_CHUNK
                // finalizes a file. A self-terminating final chunk is also
                // accepted (callers shouldn't rely on it).
                val isComplete = isLast && (body.isEmpty() || received >= slot.total)
                if (isComplete) {
                    val publishedPath = slot.sink.commit()
                    fileSinks.remove(id)
                    expected.remove(id)
                    progress to Completed.FileSaved(id, slot.name, publishedPath)
                } else {
                    progress to null
                }
            }

            else -> null to null
        }
    }

    /**
     * Cancel every in-flight FILE sink (deletes the partial entry from
     * MediaStore / unlinks the file). Called when the transfer is aborted or
     * the receiver shuts down. Safe to call multiple times.
     */
    @Synchronized
    override fun close() {
        for (slot in fileSinks.values) runCatching { slot.sink.abort() }
        fileSinks.clear()
        byteSinks.clear()
        expected.clear()
    }

    /** Append-only growable buffer with absolute-offset writes. */
    private class ByteArrayBuf(totalSize: Long) {
        private var arr: ByteArray = ByteArray(totalSize.coerceIn(0, MAX_PREALLOC).toInt())
        var size: Long = 0L; private set

        fun write(offset: Long, data: ByteArray) {
            val needed = offset + data.size
            if (needed > arr.size) arr = arr.copyOf(needed.toInt().coerceAtLeast(arr.size * 2))
            System.arraycopy(data, 0, arr, offset.toInt(), data.size)
            if (needed > size) size = needed
        }

        fun toByteArray(): ByteArray = arr.copyOf(size.toInt())
        companion object { const val MAX_PREALLOC = 1L shl 20 }
    }

    companion object { private const val SCOPE = "PayloadAsm" }
}
