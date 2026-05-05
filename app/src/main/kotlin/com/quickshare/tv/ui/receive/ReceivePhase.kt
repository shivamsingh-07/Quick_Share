package com.quickshare.tv.ui.receive

// =====================================================================
//  Receive phase model
// =====================================================================

internal enum class ReceivePhase { LISTENING, CONNECTING, PROMPT, TRANSFERRING, SUCCESS, FAILED }

internal fun derivePhase(ui: ReceiveViewModel.Ui): ReceivePhase = when {
    ui.errorMessageRes != null -> ReceivePhase.FAILED
    ui.done -> ReceivePhase.SUCCESS
    ui.showPrompt -> ReceivePhase.PROMPT
    ui.progressByPayload.isNotEmpty() || ui.saved.isNotEmpty() -> ReceivePhase.TRANSFERRING
    ui.pendingFiles.isNotEmpty() -> ReceivePhase.TRANSFERRING
    ui.peerName != null || ui.authString != null || ui.pin != null -> ReceivePhase.CONNECTING
    else -> ReceivePhase.LISTENING
}
