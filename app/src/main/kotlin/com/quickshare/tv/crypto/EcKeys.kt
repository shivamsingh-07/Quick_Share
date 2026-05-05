package com.quickshare.tv.crypto

import com.quickshare.tv.proto.ukey.EcP256PublicKey
import com.quickshare.tv.proto.ukey.GenericPublicKey
import com.quickshare.tv.proto.ukey.PublicKeyType
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * EC P-256 helpers plus the GenericPublicKey envelope serialization that UKEY2
 * uses inside ServerInit / ClientFinished.
 */
object EcKeys {
    private const val CURVE = "secp256r1"

    fun generateP256(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(CURVE))
        return kpg.generateKeyPair()
    }

    /** ECDH P-256 → 32-byte shared secret. */
    fun ecdh(myPriv: PrivateKey, peerPub: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(myPriv)
        ka.doPhase(peerPub, true)
        return ka.generateSecret()
    }

    /** Serialize an EC public key to the Ukey2 GenericPublicKey envelope. */
    fun serializeGenericPublicKey(pub: PublicKey): ByteArray {
        val ec = pub as ECPublicKey
        val w = ec.w
        return GenericPublicKey.newBuilder()
            .setType(PublicKeyType.EC_P256)
            .setEcP256PublicKey(
                EcP256PublicKey.newBuilder()
                    .setX(com.google.protobuf.ByteString.copyFrom(twosComplement(w.affineX)))
                    .setY(com.google.protobuf.ByteString.copyFrom(twosComplement(w.affineY)))
                    .build()
            )
            .build()
            .toByteArray()
    }

    /** Inverse of [serializeGenericPublicKey]. */
    fun parseGenericPublicKey(bytes: ByteArray): PublicKey {
        val gpk = GenericPublicKey.parseFrom(bytes)
        require(gpk.type == PublicKeyType.EC_P256) { "Unsupported pubkey type ${gpk.type}" }
        // BigInteger(1, ...) forces an *unsigned* magnitude interpretation. The
        // wire format may or may not include a leading 0x00 sign byte: spec-
        // compliant encoders add one when the high bit of the coordinate is
        // set, but trimmed-encoding peers exist in the wild. Either way the
        // value we want is the unsigned magnitude — using BigInteger(byte[])
        // would treat a 32-byte coordinate with the high bit set as negative,
        // produce an off-curve ECPoint, and silently fail ECDH.
        val x = BigInteger(1, gpk.ecP256PublicKey.x.toByteArray())
        val y = BigInteger(1, gpk.ecP256PublicKey.y.toByteArray())
        val params = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec(CURVE))
        }
        val ecParams = params.getParameterSpec(ECParameterSpec::class.java)
        val spec = ECPublicKeySpec(ECPoint(x, y), ecParams)
        return KeyFactory.getInstance("EC").generatePublic(spec)
    }

    /**
     * Big-endian, two's-complement encoding. `BigInteger.toByteArray()` already
     * sign-extends with a leading 0x00 when the magnitude's high bit is set, so
     * positive-coordinate values produce 33 bytes for a 32-byte EC coordinate.
     * That matches the UKEY2 EcP256PublicKey field comment exactly.
     */
    private fun twosComplement(v: BigInteger): ByteArray = v.toByteArray()
}
