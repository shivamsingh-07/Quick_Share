package com.quickshare.tv.ui.send

import android.net.Uri

// =====================================================================
//  Send phase model
// =====================================================================

internal enum class SendPhase {
    PICK_FILES,
    /** mDNS+BLE picker — default once URIs are picked, before any peer is chosen. */
    PICK_DEVICE,
    /** QR card — only entered if the user clicks "Use QR" in the picker. */
    AWAITING_SCAN,
    TRANSFERRING,
    SUCCESS,
    FAILED,
}

internal fun derivePhase(uris: List<Uri>, ui: SendViewModel.Ui): SendPhase = when {
    ui.errorMessageRes != null                                -> SendPhase.FAILED
    ui.done                                                   -> SendPhase.SUCCESS
    uris.isEmpty()                                            -> SendPhase.PICK_FILES
    ui.peerName != null || ui.progress.isNotEmpty()           -> SendPhase.TRANSFERRING
    ui.mode == SendViewModel.Mode.QR                          -> SendPhase.AWAITING_SCAN
    else                                                      -> SendPhase.PICK_DEVICE
}
