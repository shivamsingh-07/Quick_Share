package com.quickshare.tv.system.storage

import java.io.File
import java.io.RandomAccessFile

/**
 * Test-only [FileSinkFactory] that writes to a directory on disk. We can't use
 * the production [MediaStoreDownloadsFileSinkFactory] from JVM unit tests
 * because MediaStore needs a real Context + ContentResolver; the production
 * implementation is exercised by instrumented tests only.
 *
 * This factory mirrors the production sink's lifecycle (write/commit/abort)
 * and supports random-access writes via [RandomAccessFile] so tests can
 * exercise odd offset patterns the wire protocol could legally produce.
 */
class TempDirFileSinkFactory(private val dir: File) : FileSinkFactory {
    init { dir.mkdirs() }

    override fun create(name: String, total: Long, mime: String?): FileSink =
        TempDirFileSink(File(dir, sanitize(name)), total)

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)
}

private class TempDirFileSink(
    private val target: File,
    total: Long,
) : FileSink {
    private val raf: RandomAccessFile = RandomAccessFile(target, "rw").apply {
        if (total > 0) setLength(total)
    }
    private var done = false

    override fun write(offset: Long, body: ByteArray) {
        if (body.isEmpty()) return
        raf.seek(offset)
        raf.write(body)
    }

    override fun commit(): String {
        if (done) error("commit() called twice")
        done = true
        raf.close()
        return target.absolutePath
    }

    override fun abort() {
        if (done) return
        done = true
        runCatching { raf.close() }
        runCatching { target.delete() }
    }

    override fun close() { runCatching { raf.close() } }
}
