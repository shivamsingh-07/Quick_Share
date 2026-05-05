package com.quickshare.tv.ui

import androidx.annotation.StringRes
import com.quickshare.tv.R
import java.io.EOFException
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.crypto.AEADBadTagException

/**
 * Maps technical transfer failures to stable, user-facing copy.
 *
 * Protocol and repository layers intentionally keep throwing precise exceptions
 * for logs/debugging. The UI should not surface those raw messages ("rejected
 * by receiver", "ContentResolver returned null", socket class names, etc.).
 * Keep this mapper small and conservative: only special-case messages we own or
 * platform exceptions that clearly imply a user-understandable next step.
 */
internal object TransferFailureMessages {
    @StringRes
    fun send(cause: Throwable): Int = when {
        cause.hasMessage("rejected by receiver") ||
            cause.hasMessage("request declined") ||
            cause.hasMessage("peer rejected the connection") ->
            R.string.transfer_error_declined_by_receiver

        cause.hasMessage("no nearby receiver found") ->
            R.string.transfer_error_no_receiver

        cause.isFileReadFailure() ->
            R.string.transfer_error_file_read

        cause.isSecureHandshakeFailure() ->
            R.string.transfer_error_secure_connection

        cause.isConnectFailure() ->
            R.string.transfer_error_connection_failed

        cause.isConnectionLost() ->
            R.string.transfer_error_connection_lost

        else -> R.string.send_failed_message
    }

    @StringRes
    fun receive(cause: Throwable): Int = when {
        cause.hasMessage("cancelled by sender") ->
            R.string.transfer_error_cancelled_by_sender

        cause.hasMessage("request declined") ->
            R.string.transfer_error_request_declined

        cause.isSecureHandshakeFailure() ->
            R.string.transfer_error_secure_connection

        cause.isReceiveStartupFailure() ->
            R.string.transfer_error_receive_start_failed

        cause.isConnectionLost() ->
            R.string.transfer_error_connection_lost

        else -> R.string.receive_failed_message
    }

    private fun Throwable.causes(): Sequence<Throwable> =
        generateSequence(this) { it.cause }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
        causes().any { it is T }

    private fun Throwable.hasMessage(fragment: String): Boolean =
        causes()
            .mapNotNull(Throwable::message)
            .any { it.contains(fragment, ignoreCase = true) }

    private fun Throwable.isConnectFailure(): Boolean =
        hasCause<ConnectException>() ||
            hasCause<NoRouteToHostException>() ||
            hasCause<SocketTimeoutException>() ||
            hasCause<UnknownHostException>() ||
            hasMessage("connect to") ||
            hasMessage("failed to connect")

    private fun Throwable.isConnectionLost(): Boolean =
        hasCause<EOFException>() ||
            hasCause<SocketException>() ||
            hasMessage("connection reset") ||
            hasMessage("broken pipe") ||
            hasMessage("socket closed")

    private fun Throwable.isFileReadFailure(): Boolean =
        hasCause<FileNotFoundException>() ||
            hasCause<SecurityException>() ||
            hasMessage("contentresolver returned null") ||
            hasMessage("openinputstream")

    private fun Throwable.isSecureHandshakeFailure(): Boolean =
        hasCause<AEADBadTagException>() ||
            hasMessage("ukey2") ||
            hasMessage("secure channel") ||
            hasMessage("auth string") ||
            hasMessage("handshake")

    private fun Throwable.isReceiveStartupFailure(): Boolean =
        hasMessage("mdns register failed") ||
            hasMessage("no usable ipv4") ||
            hasMessage("advertise failed") ||
            hasMessage("start receiving")
}
