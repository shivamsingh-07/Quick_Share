package com.quickshare.tv.crypto

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class QrHandshakeTest {

    @Test
    fun `ecdsaDerSignatureToIeeeP256 inverts JCA output and signAuthStringToP1363 is 64 bytes`() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val pair = kpg.generateKeyPair()
        val msg = "UKEY2 v1 auth test".toByteArray()
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(pair.private)
        sig.update(msg)
        val der = sig.sign()
        val p1363 = QrHandshake.ecdsaDerSignatureToIeeeP256(der)
        assertEquals(64, p1363.size)

        val back = QrHandshake.signAuthStringToP1363(pair.private, msg)
        assertEquals(64, back.size)
        // ECDSA signatures use random k — two invocations are unrelated bytes.
    }

    @Test
    fun `minimal DER ECDSA integers convert to 32 plus 32 bytes`() {
        val der = byteArrayOf(
            0x30, 0x06,
            0x02, 0x01, 0x01, // r = 1
            0x02, 0x01, 0x01, // s = 1
        )
        val out = QrHandshake.ecdsaDerSignatureToIeeeP256(der)
        assertEquals(64, out.size)
        assertEquals(1, out[31].toInt() and 0xff)
        assertEquals(1, out[63].toInt() and 0xff)
    }
}
