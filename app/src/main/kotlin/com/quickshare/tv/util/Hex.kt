package com.quickshare.tv.util

private val HEX = "0123456789abcdef".toCharArray()

fun ByteArray.toHex(maxLen: Int = size): String {
    val n = minOf(size, maxLen)
    val out = CharArray(n * 2)
    for (i in 0 until n) {
        val b = this[i].toInt() and 0xff
        out[i * 2]     = HEX[b ushr 4]
        out[i * 2 + 1] = HEX[b and 0x0f]
    }
    val s = String(out)
    return if (n < size) "$s...(${size}B)" else s
}

fun ByteArray.constantTimeEquals(other: ByteArray): Boolean {
    if (size != other.size) return false
    var diff = 0
    for (i in indices) diff = diff or (this[i].toInt() xor other[i].toInt())
    return diff == 0
}
