package com.quickshare.tv.crypto

import com.google.protobuf.ByteString
import com.quickshare.tv.proto.ukey.Ukey2ClientFinished
import com.quickshare.tv.proto.ukey.Ukey2ClientInit
import com.quickshare.tv.proto.ukey.Ukey2HandshakeCipher
import com.quickshare.tv.proto.ukey.Ukey2Message
import com.quickshare.tv.proto.ukey.Ukey2ServerInit
import com.quickshare.tv.util.Log
import com.quickshare.tv.util.constantTimeEquals
import com.quickshare.tv.util.toHex
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyPair
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * UKEY2 v1 handshake — Nearby Connections wire-compatible derivation.
 *
 * Protocol (P256_SHA512 cipher):
 *   ClientInit  C→S : random_c (32B), commitments[ {P256_SHA512, SHA-512(M3)} ],
 *                     next_protocol="AES_256_CBC-HMAC_SHA256"
 *   ServerInit  S→C : random_s (32B), chosen_cipher=P256_SHA512, server_public_key
 *   ClientFin   C→S : client_public_key   ← server verifies SHA-512(M3) == commitment
 *
 * Key derivation (the bit that has to match Android byte-for-byte):
 *   raw_shared  = ECDH(client_priv, server_pub).raw_secret_bytes()   // 32B
 *   shared      = SHA-256(raw_shared)                                // ← Quick Share-specific
 *
 *   authString  = HKDF-SHA256(ikm=shared, salt="UKEY2 v1 auth", info=clientInit||serverInit, L=32)
 *   nextSecret  = HKDF-SHA256(ikm=shared, salt="UKEY2 v1 next", info=clientInit||serverInit, L=32)
 *
 *   d2dClient   = HKDF-SHA256(ikm=nextSecret, salt=SHA-256("D2D"),           info="client",  L=32)
 *   d2dServer   = HKDF-SHA256(ikm=nextSecret, salt=SHA-256("D2D"),           info="server",  L=32)
 *
 *   encClient   = HKDF-SHA256(ikm=d2dClient,  salt=SHA-256("SecureMessage"), info="ENC:2",   L=32)
 *   sigClient   = HKDF-SHA256(ikm=d2dClient,  salt=SHA-256("SecureMessage"), info="SIG:1",   L=32)
 *   encServer   = HKDF-SHA256(ikm=d2dServer,  salt=SHA-256("SecureMessage"), info="ENC:2",   L=32)
 *   sigServer   = HKDF-SHA256(ikm=d2dServer,  salt=SHA-256("SecureMessage"), info="SIG:1",   L=32)
 *
 * Wire framing per Ukey2Message: 4-byte big-endian length + serialized Ukey2Message.
 */
class Ukey2 private constructor(
    private val role: Role,
    private val random: SecureRandom = SecureRandom()
) {
    enum class Role { CLIENT, SERVER }

    /** Output of a successful handshake. */
    data class Session(
        /** Per-direction SecureMessage AES-256-CBC keys. */
        val encClientKey: ByteArray,   // CLIENT→SERVER traffic encryption key
        val encServerKey: ByteArray,   // SERVER→CLIENT traffic encryption key
        /** Per-direction SecureMessage HMAC-SHA256 keys. */
        val sigClientKey: ByteArray,   // CLIENT→SERVER traffic auth key
        val sigServerKey: ByteArray,   // SERVER→CLIENT traffic auth key
        /** 32-byte UKEY2 auth-string used for PIN derivation / out-of-band verify. */
        val authString: ByteArray,
        val nextProtocol: String,
    )

    companion object {
        private const val SCOPE = "UKEY2"
        private const val NEXT_PROTOCOL = "AES_256_CBC-HMAC_SHA256"

        // ---- Server side ---------------------------------------------------

        fun server() = Ukey2(Role.SERVER)

        // ---- Client side ---------------------------------------------------

        fun client() = Ukey2(Role.CLIENT)
    }

    /**
     * CLIENT: drive the handshake to completion.
     *
     * @param input  socket InputStream  wrapped as DataInputStream
     * @param output socket OutputStream wrapped as DataOutputStream
     */
    fun runClient(input: DataInputStream, output: DataOutputStream): Session {
        require(role == Role.CLIENT)
        val keyPair = EcKeys.generateP256()
        val randomC = ByteArray(32).also(random::nextBytes)

        // Build the ClientFinished we *will* send, then commit to its hash now.
        val clientFinishedMsg = buildClientFinishedMessage(keyPair)
        val commitment = sha512(clientFinishedMsg)

        // 1) Send ClientInit.
        val clientInit = Ukey2ClientInit.newBuilder()
            .setVersion(1)
            .setRandom(ByteString.copyFrom(randomC))
            .setNextProtocol(NEXT_PROTOCOL)
            .addCipherCommitments(
                Ukey2ClientInit.CipherCommitment.newBuilder()
                    .setHandshakeCipher(Ukey2HandshakeCipher.P256_SHA512)
                    .setCommitment(ByteString.copyFrom(commitment))
            )
            .build()
        val clientInitMsg = wrap(Ukey2Message.Type.CLIENT_INIT, clientInit.toByteArray())
        writeFramed(output, clientInitMsg)
        Log.v(SCOPE) { "→ ClientInit (${clientInitMsg.size}B)" }

        // 2) Read ServerInit.
        val serverInitMsg = readFramed(input)
        val serverInitWrapper = Ukey2Message.parseFrom(serverInitMsg)
        require(serverInitWrapper.messageType == Ukey2Message.Type.SERVER_INIT) {
            "Expected SERVER_INIT, got ${serverInitWrapper.messageType}"
        }
        val serverInit = Ukey2ServerInit.parseFrom(serverInitWrapper.messageData)
        require(serverInit.handshakeCipher == Ukey2HandshakeCipher.P256_SHA512) {
            "Server picked unsupported cipher ${serverInit.handshakeCipher}"
        }
        val randomS = serverInit.random.toByteArray()
        val peerPub = EcKeys.parseGenericPublicKey(serverInit.publicKey.toByteArray())
        Log.v(SCOPE) { "← ServerInit randomS=${randomS.toHex(8)}" }

        // 3) Send ClientFinished (already pre-built so the commitment is exact).
        writeFramed(output, clientFinishedMsg)
        Log.v(SCOPE) { "→ ClientFinished (${clientFinishedMsg.size}B)" }

        // 4) Derive session.
        return derive(
            keyPair = keyPair,
            peerPub = peerPub,
            clientInitMsg = clientInitMsg,
            serverInitMsg = serverInitMsg,
        )
    }

    /**
     * SERVER: drive the handshake to completion.
     */
    fun runServer(input: DataInputStream, output: DataOutputStream): Session {
        require(role == Role.SERVER)
        val keyPair = EcKeys.generateP256()
        val randomS = ByteArray(32).also(random::nextBytes)

        // 1) Read ClientInit.
        val clientInitMsg = readFramed(input)
        val clientInitWrapper = Ukey2Message.parseFrom(clientInitMsg)
        require(clientInitWrapper.messageType == Ukey2Message.Type.CLIENT_INIT) {
            "Expected CLIENT_INIT"
        }
        val clientInit = Ukey2ClientInit.parseFrom(clientInitWrapper.messageData)
        val commitment = clientInit.cipherCommitmentsList
            .firstOrNull { it.handshakeCipher == Ukey2HandshakeCipher.P256_SHA512 }
            ?.commitment?.toByteArray()
            ?: error("No P256_SHA512 commitment in ClientInit")
        val randomC = clientInit.random.toByteArray()
        Log.v(SCOPE) { "← ClientInit randomC=${randomC.toHex(8)}" }

        // 2) Send ServerInit.
        val serverInit = Ukey2ServerInit.newBuilder()
            .setVersion(1)
            .setRandom(ByteString.copyFrom(randomS))
            .setHandshakeCipher(Ukey2HandshakeCipher.P256_SHA512)
            .setPublicKey(ByteString.copyFrom(EcKeys.serializeGenericPublicKey(keyPair.public)))
            .build()
        val serverInitMsg = wrap(Ukey2Message.Type.SERVER_INIT, serverInit.toByteArray())
        writeFramed(output, serverInitMsg)
        Log.v(SCOPE) { "→ ServerInit (${serverInitMsg.size}B)" }

        // 3) Read ClientFinished and verify the commitment.
        val clientFinishedMsg = readFramed(input)
        val verify = sha512(clientFinishedMsg)
        check(verify.constantTimeEquals(commitment)) {
            "ClientFinished hash mismatch: client cheated on commitment"
        }
        val cfWrapper = Ukey2Message.parseFrom(clientFinishedMsg)
        val cf = Ukey2ClientFinished.parseFrom(cfWrapper.messageData)
        val peerPub = EcKeys.parseGenericPublicKey(cf.publicKey.toByteArray())

        return derive(
            keyPair = keyPair,
            peerPub = peerPub,
            clientInitMsg = clientInitMsg,
            serverInitMsg = serverInitMsg,
        )
    }

    // -------------------------------------------------------------------------
    // helpers

    private fun derive(
        keyPair: KeyPair,
        peerPub: java.security.PublicKey,
        clientInitMsg: ByteArray,
        serverInitMsg: ByteArray,
    ): Session {
        // Quick Share / `rqs_lib` hashes the raw ECDH secret with SHA-256 BEFORE
        // feeding it as the IKM to HKDF. Skipping this step makes our keys
        // diverge from every real peer (self-tests still pass because both
        // sides are wrong in the same way). See `inbound.rs::finalize_key_exchange`.
        val rawShared = EcKeys.ecdh(keyPair.private, peerPub)
        val shared    = sha256(rawShared)
        val info = clientInitMsg + serverInitMsg
        val saltAuth = "UKEY2 v1 auth".toByteArray(Charsets.UTF_8)
        val saltNext = "UKEY2 v1 next".toByteArray(Charsets.UTF_8)

        val authString = Hkdf.derive(saltAuth, shared, info, 32)
        val nextSecret = Hkdf.derive(saltNext, shared, info, 32)

        val d2dSalt = sha256("D2D".toByteArray(Charsets.UTF_8))
        val d2dClient = Hkdf.derive(d2dSalt, nextSecret, "client".toByteArray(Charsets.UTF_8), 32)
        val d2dServer = Hkdf.derive(d2dSalt, nextSecret, "server".toByteArray(Charsets.UTF_8), 32)

        val smSalt = sha256("SecureMessage".toByteArray(Charsets.UTF_8))
        val encClient = Hkdf.derive(smSalt, d2dClient, "ENC:2".toByteArray(Charsets.UTF_8), 32)
        val sigClient = Hkdf.derive(smSalt, d2dClient, "SIG:1".toByteArray(Charsets.UTF_8), 32)
        val encServer = Hkdf.derive(smSalt, d2dServer, "ENC:2".toByteArray(Charsets.UTF_8), 32)
        val sigServer = Hkdf.derive(smSalt, d2dServer, "SIG:1".toByteArray(Charsets.UTF_8), 32)

        Log.i(SCOPE, "Handshake done role=$role auth=${authString.toHex(8)}")
        return Session(
            encClientKey = encClient,
            encServerKey = encServer,
            sigClientKey = sigClient,
            sigServerKey = sigServer,
            authString   = authString,
            nextProtocol = NEXT_PROTOCOL,
        )
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun buildClientFinishedMessage(keyPair: KeyPair): ByteArray {
        val cf = Ukey2ClientFinished.newBuilder()
            .setPublicKey(ByteString.copyFrom(EcKeys.serializeGenericPublicKey(keyPair.public)))
            .build()
        return wrap(Ukey2Message.Type.CLIENT_FINISHED, cf.toByteArray())
    }

    private fun wrap(type: Ukey2Message.Type, body: ByteArray): ByteArray =
        Ukey2Message.newBuilder()
            .setMessageType(type)
            .setMessageData(ByteString.copyFrom(body))
            .build()
            .toByteArray()

    private fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        for (p in parts) md.update(p)
        return md.digest()
    }

    private fun writeFramed(out: DataOutputStream, payload: ByteArray) {
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    private fun readFramed(input: DataInputStream): ByteArray {
        val len = input.readInt()
        require(len in 1..1_000_000) { "Unreasonable frame length: $len" }
        val buf = ByteArray(len)
        input.readFully(buf)
        return buf
    }
}
