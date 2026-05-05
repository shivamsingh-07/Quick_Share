package com.quickshare.tv.network.tcp

import com.quickshare.tv.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TcpClient {
    private const val SCOPE = "TcpClient"
    private const val CONNECT_TIMEOUT_MS = 8_000

    suspend fun connect(host: InetAddress, port: Int): Socket = withContext(Dispatchers.IO) {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        Log.d(SCOPE) { "Connected to $host:$port" }
        s
    }
}
