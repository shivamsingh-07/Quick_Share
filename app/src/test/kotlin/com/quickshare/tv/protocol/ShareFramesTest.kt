package com.quickshare.tv.protocol

import com.quickshare.tv.proto.share.FileMetadata
import com.quickshare.tv.proto.share.Frame
import com.quickshare.tv.proto.share.V1Frame
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the wire shape of the share-side frames built by [ShareFrames] and
 * the MIME / extension → [FileMetadata.Type] inference used in
 * `Introduction` frames. A regression here would silently downgrade the
 * receiver's icon / preview UX, which we want a unit test to catch
 * before it ships.
 */
class ShareFramesTest {

    // ──────────────────────────────────────────────────────────────────
    //  guessFileType: specific MIME wins
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `specific image MIME maps to IMAGE`() {
        assertEquals(FileMetadata.Type.IMAGE, ShareFrames.guessFileType("p.jpg", "image/jpeg"))
        assertEquals(FileMetadata.Type.IMAGE, ShareFrames.guessFileType(null, "image/png"))
        assertEquals(FileMetadata.Type.IMAGE, ShareFrames.guessFileType("p", "IMAGE/HEIC"))
    }

    @Test
    fun `specific video MIME maps to VIDEO`() {
        assertEquals(FileMetadata.Type.VIDEO, ShareFrames.guessFileType("clip.mp4", "video/mp4"))
        assertEquals(FileMetadata.Type.VIDEO, ShareFrames.guessFileType(null, "video/webm"))
    }

    @Test
    fun `specific audio MIME maps to AUDIO`() {
        assertEquals(FileMetadata.Type.AUDIO, ShareFrames.guessFileType("song.mp3", "audio/mpeg"))
        assertEquals(FileMetadata.Type.AUDIO, ShareFrames.guessFileType(null, "audio/flac"))
    }

    @Test
    fun `APK MIME variants both map to APP`() {
        assertEquals(
            FileMetadata.Type.APP,
            ShareFrames.guessFileType("app.apk", "application/vnd.android.package-archive"),
        )
        assertEquals(
            FileMetadata.Type.APP,
            ShareFrames.guessFileType("app.apk", "application/x-android-package"),
        )
    }

    // ──────────────────────────────────────────────────────────────────
    //  guessFileType: extension fallback when MIME is generic / missing
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `octet-stream MIME falls back to extension`() {
        assertEquals(
            FileMetadata.Type.IMAGE,
            ShareFrames.guessFileType("vacation.JPG", "application/octet-stream"),
        )
        assertEquals(
            FileMetadata.Type.VIDEO,
            ShareFrames.guessFileType("clip.mkv", "application/octet-stream"),
        )
        assertEquals(
            FileMetadata.Type.AUDIO,
            ShareFrames.guessFileType("track.flac", "application/octet-stream"),
        )
        assertEquals(
            FileMetadata.Type.APP,
            ShareFrames.guessFileType("instagram.apk", "application/octet-stream"),
        )
    }

    @Test
    fun `null or blank MIME falls back to extension`() {
        assertEquals(FileMetadata.Type.IMAGE, ShareFrames.guessFileType("photo.png", null))
        assertEquals(FileMetadata.Type.VIDEO, ShareFrames.guessFileType("clip.mp4", ""))
        assertEquals(FileMetadata.Type.AUDIO, ShareFrames.guessFileType("song.m4a", "  "))
    }

    @Test
    fun `multi-dot filename uses the last extension only`() {
        // archive.tar.gz → "gz" (not in the table) → UNKNOWN; the
        // assertion is really about *which* extension we extract.
        assertEquals(
            FileMetadata.Type.UNKNOWN,
            ShareFrames.guessFileType("archive.tar.gz", null),
        )
        // photo.backup.jpg → "jpg" → IMAGE.
        assertEquals(
            FileMetadata.Type.IMAGE,
            ShareFrames.guessFileType("photo.backup.jpg", null),
        )
    }

    @Test
    fun `unknown extension and unknown MIME stays UNKNOWN`() {
        assertEquals(FileMetadata.Type.UNKNOWN, ShareFrames.guessFileType("notes.xyz", null))
        assertEquals(FileMetadata.Type.UNKNOWN, ShareFrames.guessFileType(null, null))
        assertEquals(FileMetadata.Type.UNKNOWN, ShareFrames.guessFileType("noext", null))
        assertEquals(FileMetadata.Type.UNKNOWN, ShareFrames.guessFileType("trailing.", null))
    }

    @Test
    fun `specific MIME outranks misleading extension`() {
        // SAF reports a real MIME — use it even when the filename ends
        // with a dotted-extension that would map elsewhere.
        assertEquals(
            FileMetadata.Type.AUDIO,
            ShareFrames.guessFileType("backup.jpg", "audio/mpeg"),
        )
    }

    // ──────────────────────────────────────────────────────────────────
    //  Introduction frame: end-to-end FileMetadata fields
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `introduction sets type from MIME or extension per file`() {
        val files = listOf(
            ShareFrames.FileEntry(
                name = "vacation.jpg",
                payloadId = 100L,
                size = 12_345L,
                mime = "image/jpeg",
            ),
            ShareFrames.FileEntry(
                name = "drive.dat",
                payloadId = 200L,
                size = 999L,
                mime = "application/octet-stream", // generic — should still resolve via... nothing
            ),
            ShareFrames.FileEntry(
                name = "song.m4a",
                payloadId = 300L,
                size = 1_000L,
                mime = "application/octet-stream", // generic — extension wins
            ),
            ShareFrames.FileEntry(
                name = "app.apk",
                payloadId = 400L,
                size = 500_000L,
                mime = null, // null — extension wins
            ),
        )
        val frame = ShareFrames.introduction(files)
        val parsed = Frame.parseFrom(frame.toByteArray())
        assertEquals(V1Frame.FrameType.INTRODUCTION, parsed.v1.type)

        val list = parsed.v1.introduction.fileMetadataList
        assertEquals(4, list.size)

        assertEquals("vacation.jpg", list[0].name)
        assertEquals(FileMetadata.Type.IMAGE, list[0].type)
        assertEquals("image/jpeg", list[0].mimeType)
        assertEquals(12_345L, list[0].size)
        assertEquals(100L, list[0].payloadId)

        // generic MIME + unknown extension → UNKNOWN.
        assertEquals(FileMetadata.Type.UNKNOWN, list[1].type)

        // generic MIME + known audio extension → AUDIO.
        assertEquals(FileMetadata.Type.AUDIO, list[2].type)
        assertEquals("application/octet-stream", list[2].mimeType)

        // null MIME + apk extension → APP, and we DO NOT emit a mime field
        // on the wire (the receiver decides what to label it).
        assertEquals(FileMetadata.Type.APP, list[3].type)
        assertEquals("", list[3].mimeType)
    }
}
