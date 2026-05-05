package com.quickshare.tv.ui

import com.quickshare.tv.R
import com.quickshare.tv.domain.model.DeviceKind

/**
 * Human-readable byte-count string. Returns `"-"` for negative sizes
 * (e.g. unknown size before transfer begins).
 */
fun humanReadableFileSize(bytes: Long): String {
    if (bytes < 0L) return "-"
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}

/**
 * Drawable resource for a picker-row device icon. TABLET shares the
 * PHONE glyph; LAPTOP and TV both use the screen-with-stand glyph.
 * UNKNOWN falls back to the generic Quick Share icon.
 */
fun DeviceKind.iconRes(): Int = when (this) {
    DeviceKind.PHONE   -> R.drawable.ic_phone
    DeviceKind.TABLET  -> R.drawable.ic_phone
    DeviceKind.LAPTOP  -> R.drawable.ic_tv
    DeviceKind.TV      -> R.drawable.ic_tv
    DeviceKind.UNKNOWN -> R.drawable.ic_quick_share
}
