package com.quickshare.tv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickshare.tv.QuickShareApp
import com.quickshare.tv.data.repository.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUi(
    val deviceName: String = "",
    val autoAccept: Boolean = false,
    val useDarkTheme: Boolean = true,
)

class SettingsViewModel(
    private val settings: SettingsRepository = QuickShareApp.instance.settingsRepository,
) : ViewModel() {

    val ui: StateFlow<SettingsUi> = combine(
        settings.deviceNameFlow,
        settings.autoAcceptFlow,
        settings.useDarkThemeFlow,
    ) { name, auto, dark ->
        SettingsUi(deviceName = name, autoAccept = auto, useDarkTheme = dark)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUi(),
    )

    fun setAutoAccept(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoAccept(enabled) }
    }

    fun setUseDarkTheme(dark: Boolean) {
        viewModelScope.launch { settings.setUseDarkTheme(dark) }
    }
}
