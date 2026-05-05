package com.quickshare.tv.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object HmacSha256 {
    private const val ALG = "HmacSHA256"

    fun mac(key: ByteArray, data: ByteArray): ByteArray {
        val m = Mac.getInstance(ALG)
        m.init(SecretKeySpec(key, ALG))
        return m.doFinal(data)
    }

}
