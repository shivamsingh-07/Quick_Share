package com.quickshare.tv.util

/**
 * Returns `true` when the string is a plausible human-visible device name:
 * at least 2 non-whitespace characters and at least one letter or digit.
 *
 * Used to filter out empty, whitespace-only, or single-char device names
 * that some peers publish in their mDNS TXT record or ConnectionRequest.
 * The trim happens before the length check so callers don't have to pre-strip.
 */
fun String.isUsefulDeviceName(): Boolean =
    trim().let { it.length >= 2 && it.any(Char::isLetterOrDigit) }
