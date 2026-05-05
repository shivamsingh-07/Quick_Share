package com.quickshare.tv.system.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.quickshare.tv.util.Log
import java.io.Closeable
import java.io.OutputStream

/**
 * Output destination for one inbound payload. The single production
 * implementation streams bytes to user-visible storage via
 * [MediaStore.Downloads]; tests provide their own in-memory / temp-dir stubs.
 *
 * Lifecycle (must be honoured by callers):
 *   1) factory creates the sink
 *   2) write(offset, body) called sequentially with monotonically increasing
 *      offsets that match `bytesWritten` — Quick Share guarantees this and the
 *      receiver crashes the transfer if it doesn't (see ReceiverSession).
 *   3) commit() — publish to the user. Returns a human-readable path/URI.
 *      OR abort() — discard. Either MUST be called exactly once.
 *   4) close() — releases OS handles. Safe to call multiple times.
 */
interface FileSink : Closeable {

    /**
     * Append [body] at [offset]. Implementations MAY require offset to equal
     * the current write cursor; the receiver always sends sequential chunks.
     * Empty bodies are allowed (used for the LAST_CHUNK terminator).
     */
    fun write(offset: Long, body: ByteArray)

    /**
     * Mark the file as complete and visible. Returns a user-displayable path
     * (`Download/Quick Share/<file>` for the production MediaStore-backed
     * implementation). Must NOT be called after [abort].
     */
    fun commit(): String

    /**
     * Discard any partial bytes written so far (delete the entry / unlink the
     * file). Idempotent. Always safe to call from a `catch` block.
     */
    fun abort()
}

interface FileSinkFactory {
    /**
     * Open a sink for a payload of [total] bytes. [mime] is best-effort metadata
     * used by MediaStore for indexing (Photos / Files apps use it to decide
     * which collection to surface the file in). Pass null for unknown.
     */
    fun create(name: String, total: Long, mime: String?): FileSink
}

/**
 * MediaStore.Downloads-backed factory. Files materialise under
 * `Download/Quick Share/<name>` and are visible to every other app that can
 * read MediaStore.Downloads (system Files app, Photos for media, etc.).
 *
 * Pending entries: we insert with `IS_PENDING=1`, write, then flip to 0 in
 * [FileSink.commit]. If the process dies mid-transfer the entry is left
 * pending and MediaStore hides it from other apps; the OS eventually GC's it
 * after a week, but a polite future cleanup pass is tracked in
 * FUTURE_ENHANCEMENTS.
 *
 * MediaStore.Downloads has been part of the platform since API 29; our
 * `minSdk = 30` gives us a single uniform code path with no version branching.
 */
class MediaStoreDownloadsFileSinkFactory(private val context: Context) : FileSinkFactory {
    override fun create(name: String, total: Long, mime: String?): FileSink =
        MediaStoreFileSink(context, name, total, mime ?: "application/octet-stream")
}

private class MediaStoreFileSink(
    context: Context,
    name: String,
    total: Long,
    mime: String,
) : FileSink {
    private val resolver = context.contentResolver
    private val uri: Uri
    private val out: OutputStream
    private var bytesWritten: Long = 0L
    private var done = false  // commit() OR abort() set this

    init {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "$RELATIVE_PARENT/$SUBDIR")
            put(MediaStore.Downloads.IS_PENDING, 1)
            if (total > 0) put(MediaStore.Downloads.SIZE, total)
        }
        uri = resolver.insert(collection, values)
            ?: error("MediaStore.insert returned null for '$name' (mime=$mime)")
        out = resolver.openOutputStream(uri)
            ?: error("ContentResolver.openOutputStream returned null for $uri")
        Log.d(SCOPE) { "Opened MediaStore sink for '$name' total=${total}B at $uri" }
    }

    override fun write(offset: Long, body: ByteArray) {
        // MediaStore openOutputStream is sequential — no seek. We rely on the
        // Quick Share invariant that file chunks arrive in order.
        check(offset == bytesWritten) {
            "MediaStoreFileSink got non-sequential offset: $offset (expected $bytesWritten)"
        }
        if (body.isNotEmpty()) {
            out.write(body)
            bytesWritten += body.size
        }
    }

    override fun commit(): String {
        if (done) error("commit() called twice on MediaStoreFileSink")
        done = true
        out.flush()
        out.close()
        val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        resolver.update(uri, values, null, null)
        // MediaStore may de-duplicate the DISPLAY_NAME (e.g. append " (1)") so
        // we read it back rather than echoing whatever we asked for.
        val displayName = readDisplayName() ?: "(unknown)"
        return "$RELATIVE_PARENT/$SUBDIR/$displayName"
    }

    override fun abort() {
        if (done) return
        done = true
        runCatching { out.close() }
        runCatching { resolver.delete(uri, null, null) }
            .onFailure { Log.w(SCOPE, "Failed to delete pending MediaStore entry $uri", it) }
    }

    override fun close() {
        runCatching { out.close() }
    }

    private fun readDisplayName(): String? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    companion object {
        private const val SCOPE = "MediaStoreSink"

        // System-defined parent ("Download") + our app subdir. Android renders
        // the parent as "Downloads" in most file browsers; the on-disk path
        // uses the Environment.DIRECTORY_DOWNLOADS spelling.
        private val RELATIVE_PARENT: String = Environment.DIRECTORY_DOWNLOADS
        private const val SUBDIR: String = "Quick Share"
    }
}
