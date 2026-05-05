package com.quickshare.tv.util

import java.util.Base64 as JBase64

/**
 * URL-safe Base64 (no padding, no line wrap) — the encoding everyone in the
 * Quick Share / NearDrop ecosystem expects on the wire.
 *
 * Uses JDK's `java.util.Base64` (available unconditionally at our `minSdk=30`)
 * rather than `android.util.Base64`. The big practical win: JVM unit tests
 * don't need Robolectric or `unitTests.returnDefaultValues=false` to exercise
 * paths that touch this codec.
 */
object Base64Url {
    private val ENCODER = JBase64.getUrlEncoder().withoutPadding()
    private val DECODER = JBase64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = ENCODER.encodeToString(bytes)
    fun decode(s: String): ByteArray = DECODER.decode(s)
}
