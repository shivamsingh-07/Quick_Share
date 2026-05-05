package com.quickshare.tv.network.ble

import android.os.ParcelUuid

/**
 * Quick Share BLE 16-bit service UUID `0xFE2C` in the Bluetooth SIG base.
 * Shared by both the advertiser (wake-up beacon) and the passive listener.
 */
internal val QS_SERVICE_UUID: ParcelUuid =
    ParcelUuid.fromString("0000fe2c-0000-1000-8000-00805f9b34fb")
