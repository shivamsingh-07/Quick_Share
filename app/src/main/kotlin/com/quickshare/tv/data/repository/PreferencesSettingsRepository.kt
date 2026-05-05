package com.quickshare.tv.data.repository

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "quickshare_settings",
)

/**
 * [SettingsRepository] over Jetpack DataStore Preferences.
 *
 * **Device name** is *not* stored in DataStore — it's sourced from the
 * Android system setting [Settings.Global.DEVICE_NAME] (the value the
 * user edits under "Device Preferences → About → Device name" on
 * Android TV builds). [Build.MODEL] is the final fallback for the
 * pathological case where the system hasn't published a device name
 * yet. The flow re-emits whenever the system value changes (a
 * [ContentObserver] watches the setting URI), so when the user comes
 * back from the system Settings activity after renaming the TV, every
 * subscriber sees the new value without having to re-launch the app.
 *
 * Everything else (auto-accept, QR handshake key, dark-theme
 * preference) is owned by Quick Share and persisted via DataStore.
 */
class PreferencesSettingsRepository(
    private val context: Context,
) : SettingsRepository {

    private val dataStore get() = context.applicationContext.settingsDataStore
    private val resolver: ContentResolver get() = context.applicationContext.contentResolver

    override val deviceNameFlow: Flow<String> = callbackFlow {
        val uri = Settings.Global.getUriFor(Settings.Global.DEVICE_NAME)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(readSystemDeviceName(resolver))
            }
        }
        resolver.registerContentObserver(uri, /* notifyForDescendants = */ false, observer)
        // Prime the flow with the current value so subscribers don't
        // have to wait for the first system change to see anything.
        trySend(readSystemDeviceName(resolver))
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.distinctUntilChanged()

    override val autoAcceptFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_AUTO_ACCEPT] ?: false
    }

    override val qrHandshakePrivateKeyPkcs8Flow: Flow<ByteArray?> = dataStore.data.map { prefs ->
        prefs[KEY_QR_HANDSHAKE_PKCS8]?.let { Base64.decode(it, Base64.DEFAULT) }
    }

    override val useDarkThemeFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_USE_DARK_THEME] ?: true
    }

    override suspend fun setAutoAccept(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTO_ACCEPT] = enabled }
    }

    override suspend fun setQrHandshakePrivateKeyPkcs8(pkcs8: ByteArray?) {
        dataStore.edit { prefs ->
            if (pkcs8 == null || pkcs8.isEmpty()) prefs.remove(KEY_QR_HANDSHAKE_PKCS8)
            else prefs[KEY_QR_HANDSHAKE_PKCS8] = Base64.encodeToString(pkcs8, Base64.NO_WRAP)
        }
    }

    override suspend fun setUseDarkTheme(dark: Boolean) {
        dataStore.edit { it[KEY_USE_DARK_THEME] = dark }
    }

    companion object {
        private val KEY_AUTO_ACCEPT = booleanPreferencesKey("auto_accept")
        private val KEY_QR_HANDSHAKE_PKCS8 = stringPreferencesKey("qr_handshake_pkcs8")
        private val KEY_USE_DARK_THEME = booleanPreferencesKey("use_dark_theme")

        /** [com.quickshare.tv.network.EndpointInfo.encode] caps the UTF-8 name at 255 bytes. */
        private const val MAX_DEVICE_NAME_UTF8_BYTES = 255

        /**
         * Resolve the user-visible device name. Order of preference:
         *  1. The Android system setting [Settings.Global.DEVICE_NAME]
         *     — what the user edits in the TV's system settings.
         *  2. [Build.MODEL] — the immutable hardware/OEM model name,
         *     used only when the system hasn't published a device name.
         *
         * The result is clamped to [MAX_DEVICE_NAME_UTF8_BYTES] UTF-8
         * bytes so it survives `EndpointInfo` encoding.
         */
        private fun readSystemDeviceName(resolver: ContentResolver): String {
            val systemName = Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            return clampDeviceNameUtf8(systemName ?: Build.MODEL)
        }

        private fun clampDeviceNameUtf8(name: String): String {
            val bytes = name.toByteArray(Charsets.UTF_8)
            if (bytes.size <= MAX_DEVICE_NAME_UTF8_BYTES) return name
            var end = name.length
            while (end > 0) {
                val sub = name.substring(0, end)
                if (sub.toByteArray(Charsets.UTF_8).size <= MAX_DEVICE_NAME_UTF8_BYTES) return sub
                end--
            }
            return ""
        }
    }
}
