package com.quickshare.tv.network.tcp

import com.quickshare.tv.util.Log
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lightweight TCP server: binds an ephemeral port on every reachable interface,
 * exposes [accepted] as a cold Flow so the upper layers can decide their concurrency.
 */
class TcpServer : Closeable {
    private var serverSocket: ServerSocket? = null

    suspend fun bind(port: Int = 0): Int = withContext(Dispatchers.IO) {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        serverSocket = s
        Log.i(SCOPE, "Listening on ${s.inetAddress}:${s.localPort}")
        s.localPort
    }

    /**
     * Returns a cold [Flow] of accepted [Socket] connections.
     *
     * **Lifetime contract:** cancelling or completing this flow calls
     * [close] on the server socket, making this a one-shot consumer.
     * Call [bind] again on a fresh [TcpServer] if you need to re-listen.
     */
    fun accepted(): Flow<Socket> = callbackFlow {
        val s = serverSocket ?: error("Call bind() first")
        val acceptJob = launch(Dispatchers.IO) {
            while (isActive && !s.isClosed) {
                runCatching {
                    val c = s.accept()
                    c.tcpNoDelay = true
                    Log.d(SCOPE) { "Accepted ${c.remoteSocketAddress}" }
                    trySend(c)
                }.onFailure { e ->
                    if (!s.isClosed) Log.w(SCOPE, "accept() failed", e)
                }
            }
        }
        awaitClose {
            acceptJob.cancel()
            this@TcpServer.close()
        }
    }

    override fun close() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    companion object { private const val SCOPE = "TcpServer" }
}
