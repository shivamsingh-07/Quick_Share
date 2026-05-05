package com.quickshare.tv.domain.usecase

import android.content.Context
import android.net.Uri
import com.quickshare.tv.data.repository.ReceiveRepository
import com.quickshare.tv.data.repository.SendRepository
import com.quickshare.tv.domain.model.ReceiveEvent
import com.quickshare.tv.domain.model.SendEvent
import kotlinx.coroutines.flow.Flow

/**
 * The use case classes are intentionally one-method facades. They give the UI
 * layer something to inject without leaking repository details.
 */
class StartReceiveUseCase(private val repo: ReceiveRepository) {
    suspend operator fun invoke(): Flow<ReceiveEvent> = repo.start()
}

class StopReceiveUseCase(private val repo: ReceiveRepository) {
    operator fun invoke() = repo.stop()
}

class AcceptReceiveUseCase(private val repo: ReceiveRepository) {
    operator fun invoke(accept: Boolean) = repo.respondToOffer(accept)
}

/**
 * Start the Send pipeline in the **device-picker** mode (the default
 * flow once files are picked: mDNS + BLE scan, render the list, wait
 * for a tap or a fallback to QR). The repo exposes the discovered
 * device list separately via [SendRepository.devices]; the
 * [SendEvent] flow returned here only carries lifecycle events
 * (Connecting, Handshaked, Progress, ...) just like the QR path used to.
 */
class StartSendUseCase(private val repo: SendRepository) {
    suspend operator fun invoke(context: Context, uris: List<Uri>): Flow<SendEvent> =
        repo.startDevicePicker(context, uris)
}

class StopSendUseCase(private val repo: SendRepository) {
    operator fun invoke() = repo.stop()
}
