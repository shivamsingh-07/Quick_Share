package com.quickshare.tv.data.repository

import kotlinx.coroutines.flow.Flow

/**
 * User-tunable Quick Share behaviour. Backed by [androidx.datastore.core.DataStore]
 * (see [PreferencesSettingsRepository]).
 *
 * **Device name** is intentionally **read-only** from this interface's
 * perspective: the value is owned by the Android system (the user
 * edits it under Settings → Device Preferences → About → Device name),
 * not by Quick Share. `deviceNameFlow` mirrors that system value
 * reactively, with `Build.MODEL` as a final fallback when the OS
 * hasn't been given a name yet.
 */
interface SettingsRepository {
    val deviceNameFlow: Flow<String>
    val autoAcceptFlow: Flow<Boolean>
    /**
     * PKCS#8 encoding of the last Send-flow QR EC private key (Base64 in storage).
     * Used to populate `qr_code_handshake_data` when receiving with auto-accept.
     */
    val qrHandshakePrivateKeyPkcs8Flow: Flow<ByteArray?>
    /** `true` = dark theme, `false` = light. */
    val useDarkThemeFlow: Flow<Boolean>

    suspend fun setAutoAccept(enabled: Boolean)
    suspend fun setQrHandshakePrivateKeyPkcs8(pkcs8: ByteArray?)
    suspend fun setUseDarkTheme(dark: Boolean)
}
