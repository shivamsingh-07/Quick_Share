package com.quickshare.tv.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.quickshare.tv.ui.home.HomeScreen
import com.quickshare.tv.ui.receive.ReceiveScreen
import com.quickshare.tv.ui.send.SendScreen
import com.quickshare.tv.ui.settings.SettingsScreen

/**
 * Tiny finite-state navigation. We avoid Navigation-Compose because the graph is
 * trivially three nodes and we want zero-cost focus restoration on TV.
 */
@Composable
fun QuickShareNav(
    pickedUris: List<Uri>,
    onPickFiles: () -> Unit,
    onClearPicked: () -> Unit,
    onExitApp: () -> Unit,
) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }

    BackHandler(enabled = screen == Screen.HOME) {
        onExitApp()
    }

    BackHandler(enabled = screen != Screen.HOME) {
        when (screen) {
            Screen.SETTINGS,
            Screen.RECEIVE -> screen = Screen.HOME
            Screen.SEND -> {
                onClearPicked()
                screen = Screen.HOME
            }
            Screen.HOME -> Unit
        }
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            // Each tile simply navigates to its page. The Send page itself owns
            // the "Select Files" CTA, so Home stays a pure destination switcher.
            onReceive = { screen = Screen.RECEIVE },
            onSend = {
                onClearPicked()
                screen = Screen.SEND
            },
            onSettings = { screen = Screen.SETTINGS },
        )
        Screen.SETTINGS -> SettingsScreen()
        Screen.RECEIVE -> ReceiveScreen(
            onExit = { screen = Screen.HOME },
        )
        Screen.SEND -> SendScreen(
            uris = pickedUris,
            onPickFiles = onPickFiles,
            onExit = {
                // Drops the picked URIs and returns to Home. Invoked by
                // SendScreen itself only on the second press of the
                // double-Back-to-cancel gesture (after `stopSending()`).
                // For phases that don't intercept Back (PICK_FILES /
                // SUCCESS / FAILED) the parent BackHandler above has
                // already handled the navigation directly.
                onClearPicked()
                screen = Screen.HOME
            },
        )
    }
}

private enum class Screen { HOME, SETTINGS, RECEIVE, SEND }
