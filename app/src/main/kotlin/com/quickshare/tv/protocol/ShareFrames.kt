package com.quickshare.tv.protocol

import com.google.protobuf.ByteString
import com.quickshare.tv.proto.share.ConnectionResponseFrame
import com.quickshare.tv.proto.share.FileMetadata
import com.quickshare.tv.proto.share.Frame
import com.quickshare.tv.proto.share.IntroductionFrame
import com.quickshare.tv.proto.share.PairedKeyEncryptionFrame
import com.quickshare.tv.proto.share.PairedKeyResultFrame
import com.quickshare.tv.proto.share.V1Frame
import java.security.SecureRandom

/**
 * Builders + parsers for the Nearby Share *application* frames that ride inside
 * BYTES payloads. Kept in a single place so the state machine reads cleanly.
 */
object ShareFrames {

    /**
     * `signed_data` (72 random bytes) and `secret_id_hash` (6 random bytes) are
     * stubs: real Quick Share certificates would put a signature over the
     * UKEY2 auth-string here; we don't have a contact graph, so we send random
     * noise and rely on PIN-based out-of-band trust.
     *
     * All-zero stubs are a fingerprint that some peers reject; CSPRNG output
     * looks indistinguishable from a real signed payload at this layer.
     */
    fun pairedKeyEncryption(
        rng: SecureRandom = SecureRandom(),
        qrHandshakeSignature: ByteArray? = null,
    ): Frame {
        val signed = ByteArray(72).also(rng::nextBytes)
        val hash   = ByteArray(6).also(rng::nextBytes)
        val b = PairedKeyEncryptionFrame.newBuilder()
            .setSignedData(ByteString.copyFrom(signed))
            .setSecretIdHash(ByteString.copyFrom(hash))
        qrHandshakeSignature?.let {
            require(it.size == 64) { "qr handshake signature must be 64-byte IEEE P1363 (P-256)" }
            b.setQrCodeHandshakeData(ByteString.copyFrom(it))
        }
        return wrap(V1Frame.FrameType.PAIRED_KEY_ENCRYPTION) { v1 ->
            v1.pairedKeyEncryption = b.build()
        }
    }

    /**
     * Always emit `UNABLE`. We never claim to have cryptographically verified
     * the peer's paired key — that's the protocol way of saying "fall back to
     * PIN-based trust." `SUCCESS` would be a lie and some peers disconnect on
     * it because we have no cert chain to back it up.
     */
    fun pairedKeyResult(): Frame =
        wrap(V1Frame.FrameType.PAIRED_KEY_RESULT) { v1 ->
            v1.pairedKeyResult = PairedKeyResultFrame.newBuilder()
                .setStatus(PairedKeyResultFrame.Status.UNABLE)
                .build()
        }

    fun introduction(files: List<FileEntry>): Frame =
        wrap(V1Frame.FrameType.INTRODUCTION) { v1 ->
            v1.introduction = IntroductionFrame.newBuilder()
                .addAllFileMetadata(files.map(::toProto))
                .build()
        }

    fun response(accept: Boolean): Frame =
        wrap(V1Frame.FrameType.RESPONSE) { v1 ->
            v1.connectionResponse = ConnectionResponseFrame.newBuilder()
                .setStatus(
                    if (accept) ConnectionResponseFrame.Status.ACCEPT
                    else ConnectionResponseFrame.Status.REJECT
                )
                .build()
        }

    data class FileEntry(
        val name: String,
        val payloadId: Long,
        val size: Long,
        val mime: String? = null,
    )

    private fun toProto(e: FileEntry): FileMetadata {
        val builder = FileMetadata.newBuilder()
            .setName(e.name)
            .setPayloadId(e.payloadId)
            .setSize(e.size)
            .setType(guessFileType(e.name, e.mime))
        e.mime?.let(builder::setMimeType)
        return builder.build()
    }

    /**
     * Resolve the on-wire [FileMetadata.Type] for an outbound file.
     *
     * Strategy:
     *  1. Specific MIME wins (e.g. `image/jpeg`, `audio/aac`,
     *     `application/vnd.android.package-archive`). This matches
     *     `rqs_lib::outbound::guess_file_type`.
     *  2. Generic / missing MIME falls back to the **filename extension**.
     *     SAF on Android-TV often returns the `application/octet-stream`
     *     fallback for cloud / Drive / OneDrive URIs, even when the
     *     filename clearly says `.jpg` or `.mp4`. A pure MIME-only
     *     mapping reports those as `UNKNOWN`, which renders as a
     *     generic file icon on the receiver instead of the right
     *     thumbnail badge.
     *  3. If both signals are uninformative, return `UNKNOWN`.
     *
     * Visible to tests for coverage; not part of the public API surface
     * outside the protocol package.
     */
    @JvmStatic
    internal fun guessFileType(name: String?, mime: String?): FileMetadata.Type {
        mimeOnlyType(mime)?.let { return it }
        return extensionToType(name) ?: FileMetadata.Type.UNKNOWN
    }

    /**
     * MIME-driven mapping. Returns `null` when the MIME is missing or so
     * generic (`application/octet-stream`, `application/binary`) that we
     * should fall through to the filename extension.
     *
     * The APK MIME has two spellings in the wild — the IANA-registered
     * `application/vnd.android.package-archive` is the canonical one;
     * `application/x-android-package` shows up on a handful of older
     * sideloader apps and we accept it for symmetry.
     */
    private fun mimeOnlyType(mime: String?): FileMetadata.Type? {
        if (mime.isNullOrBlank()) return null
        val lower = mime.lowercase()
        if (lower in GENERIC_BINARY_MIMES) return null
        return when {
            lower.startsWith("image/") -> FileMetadata.Type.IMAGE
            lower.startsWith("video/") -> FileMetadata.Type.VIDEO
            lower.startsWith("audio/") -> FileMetadata.Type.AUDIO
            lower == "application/vnd.android.package-archive" ||
                lower == "application/x-android-package" -> FileMetadata.Type.APP
            else -> null
        }
    }

    /**
     * Filename-extension fallback for the cases where MIME under-described
     * the file. Comparison is case-insensitive; only the *last* dot in the
     * filename counts (so `archive.tar.gz` extracts `gz`, not `tar.gz`).
     *
     * Returns `null` for unknown extensions so [guessFileType] can settle
     * on `UNKNOWN`; we deliberately don't try to be clever about archives
     * / documents because the on-wire enum doesn't have a slot for them.
     */
    private fun extensionToType(name: String?): FileMetadata.Type? {
        if (name.isNullOrBlank()) return null
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return null
        val ext = name.substring(dot + 1).lowercase()
        return EXTENSION_TYPE_MAP[ext]
    }

    /**
     * MIMEs we treat as "no information" so the extension fallback runs.
     * `application/octet-stream` is the SAF default when ContentResolver
     * cannot determine the type; `application/binary` is the legacy Drive
     * variant; both come back for the same files where the extension is
     * the only useful hint.
     */
    private val GENERIC_BINARY_MIMES: Set<String> = setOf(
        "application/octet-stream",
        "application/binary",
    )

    /**
     * Curated extension → enum table. Kept narrow on purpose: every entry
     * here either ships natively in Android's MediaStore-recognised
     * formats or is a popular interchange format. A wider table would
     * tempt false positives from ambiguous extensions like `.dat`.
     */
    private val EXTENSION_TYPE_MAP: Map<String, FileMetadata.Type> = mapOf(
        // Images.
        "jpg" to FileMetadata.Type.IMAGE, "jpeg" to FileMetadata.Type.IMAGE,
        "png" to FileMetadata.Type.IMAGE, "gif" to FileMetadata.Type.IMAGE,
        "webp" to FileMetadata.Type.IMAGE, "bmp" to FileMetadata.Type.IMAGE,
        "heic" to FileMetadata.Type.IMAGE, "heif" to FileMetadata.Type.IMAGE,
        "svg" to FileMetadata.Type.IMAGE, "tif" to FileMetadata.Type.IMAGE,
        "tiff" to FileMetadata.Type.IMAGE, "avif" to FileMetadata.Type.IMAGE,
        "ico" to FileMetadata.Type.IMAGE,
        // Video.
        "mp4" to FileMetadata.Type.VIDEO, "m4v" to FileMetadata.Type.VIDEO,
        "mov" to FileMetadata.Type.VIDEO, "mkv" to FileMetadata.Type.VIDEO,
        "avi" to FileMetadata.Type.VIDEO, "webm" to FileMetadata.Type.VIDEO,
        "3gp" to FileMetadata.Type.VIDEO, "3gpp" to FileMetadata.Type.VIDEO,
        "mpeg" to FileMetadata.Type.VIDEO, "mpg" to FileMetadata.Type.VIDEO,
        "flv" to FileMetadata.Type.VIDEO, "wmv" to FileMetadata.Type.VIDEO,
        "ts" to FileMetadata.Type.VIDEO,
        // Audio.
        "mp3" to FileMetadata.Type.AUDIO, "m4a" to FileMetadata.Type.AUDIO,
        "wav" to FileMetadata.Type.AUDIO, "flac" to FileMetadata.Type.AUDIO,
        "ogg" to FileMetadata.Type.AUDIO, "oga" to FileMetadata.Type.AUDIO,
        "aac" to FileMetadata.Type.AUDIO, "opus" to FileMetadata.Type.AUDIO,
        "wma" to FileMetadata.Type.AUDIO, "mid" to FileMetadata.Type.AUDIO,
        "midi" to FileMetadata.Type.AUDIO, "amr" to FileMetadata.Type.AUDIO,
        // Apps.
        "apk" to FileMetadata.Type.APP, "apks" to FileMetadata.Type.APP,
        "xapk" to FileMetadata.Type.APP, "aab" to FileMetadata.Type.APP,
    )

    private inline fun wrap(type: V1Frame.FrameType, build: (V1Frame.Builder) -> Unit): Frame {
        val v1 = V1Frame.newBuilder().setType(type)
        build(v1)
        return Frame.newBuilder()
            .setVersion(Frame.Version.V1)
            .setV1(v1)
            .build()
    }
}
