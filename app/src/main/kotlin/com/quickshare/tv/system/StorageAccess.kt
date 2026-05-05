package com.quickshare.tv.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.quickshare.tv.system.storage.FileSinkFactory
import com.quickshare.tv.system.storage.MediaStoreDownloadsFileSinkFactory
import com.quickshare.tv.util.Log
import java.io.IOException
import java.io.InputStream

/**
 * Storage abstraction. Outbound (sender) reads come from user-picked SAF URIs;
 * inbound (receiver) writes go through a [FileSinkFactory] backed by
 * MediaStore.Downloads.
 *
 * With `minSdk = 30` there is exactly one storage backend on every supported
 * device — no scoped-storage version branching, no permission gymnastics.
 */
object StorageAccess {

    /**
     * Persist long-term read access to a SAF URI so the foreground service can
     * keep reading the file even if the activity that picked it is destroyed.
     * No-op if the URI was returned without [Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION].
     */
    fun persistRead(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { Log.v(SCOPE) { "persistRead skipped for $uri: ${it.message}" } }
    }

    fun queryNameAndSize(context: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        val resolver = context.contentResolver
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (ni >= 0) name = c.getString(ni) ?: name
                    if (si >= 0) size = c.getLong(si)
                }
            }
        }.onFailure { Log.v(SCOPE) { "query failed for $uri: ${it.message}" } }
        runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length >= 0L) size = afd.length
            }
        }.onFailure { Log.v(SCOPE) { "length probe skipped for $uri: ${it.message}" } }
        return name to size
    }

    /**
     * Resolve the **actual** byte length of the content under [uri]. Falls
     * back through three strategies in increasing cost order:
     *
     *  1. the cached `(name, size)` from [queryNameAndSize] when it
     *     already gave us a non-negative value (covers the modern SAF
     *     happy path: stock photo / media providers, Drive, OneDrive
     *     when offline-pinned),
     *  2. a fresh [AssetFileDescriptor] probe (catches providers that
     *     change their mind between picker and intro time, e.g. cloud
     *     providers that materialise the file lazily on first open),
     *  3. a one-shot byte count of the input stream (catches providers
     *     whose AFD reports `UNKNOWN_LENGTH` even though the bytes are
     *     readable end-to-end — Drive offline-only, FUSE-backed mounts).
     *
     * The byte-count fallback is gated on [byteCountFallback] and capped
     * at [MAX_FALLBACK_COUNT_BYTES] so a stuck pipe can't wedge the send
     * pipeline. Callers that don't want to pay the IO cost (e.g. simple
     * file picks where the cached size is trustworthy) leave the flag
     * `false` and accept the cached value verbatim.
     *
     * Returns `-1` only when every strategy refuses to commit a number;
     * callers translate that to the "size unknown" sentinel that the
     * Introduction frame already understands.
     */
    fun resolveActualSize(
        context: Context,
        uri: Uri,
        cachedSize: Long,
        byteCountFallback: Boolean = true,
    ): Long {
        if (cachedSize >= 0L) return cachedSize
        val resolver = context.contentResolver
        val viaAfd = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length >= 0L) afd.length else null
            }
        }.getOrNull()
        if (viaAfd != null) {
            Log.v(SCOPE) { "resolveActualSize($uri) via AFD = ${viaAfd}B" }
            return viaAfd
        }
        if (!byteCountFallback) return -1L

        val measured = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                val buf = ByteArray(BYTE_COUNT_BUFFER)
                var total = 0L
                while (true) {
                    val n = stream.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total >= MAX_FALLBACK_COUNT_BYTES) {
                        Log.w(
                            SCOPE,
                            "resolveActualSize($uri) byte-count cap " +
                                "(${MAX_FALLBACK_COUNT_BYTES}B) hit; reporting size unknown",
                        )
                        return@use null
                    }
                }
                total
            }
        }.getOrElse { t ->
            Log.w(SCOPE, "resolveActualSize($uri) byte-count failed: ${t.message}")
            null
        }
        if (measured != null) {
            Log.i(SCOPE, "resolveActualSize($uri) measured ${measured}B via byte-count fallback")
            return measured
        }
        return -1L
    }

    /** Open a streaming reader for SAF URIs (or anything ContentResolver knows about). */
    fun openInputStream(context: Context, uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri)
            ?: throw IOException("ContentResolver returned null for $uri")

    /**
     * Build a [FileSinkFactory] for inbound transfers. Always
     * [MediaStoreDownloadsFileSinkFactory] — files land under
     * `Download/Quick Share/<name>` and are visible to the system Files app,
     * Photos (for media), USB MTP, DLNA, etc. with no extra wiring.
     */
    fun fileSinkFactory(context: Context): FileSinkFactory =
        MediaStoreDownloadsFileSinkFactory(context.applicationContext)

    private const val SCOPE = "StorageAccess"

    /** Read buffer used by [resolveActualSize]'s byte-count fallback. */
    private const val BYTE_COUNT_BUFFER = 64 * 1024

    /**
     * Hard cap on how many bytes we'll count for a single URI before
     * giving up on the fallback. Two GiB matches the practical Quick
     * Share single-payload ceiling and keeps a runaway provider from
     * pinning the IO dispatcher.
     */
    private const val MAX_FALLBACK_COUNT_BYTES: Long = 2L * 1024 * 1024 * 1024
}
