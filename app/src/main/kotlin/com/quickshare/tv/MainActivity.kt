package com.quickshare.tv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import com.quickshare.tv.system.Permissions
import com.quickshare.tv.system.PermissionsRequester
import com.quickshare.tv.ui.QuickShareNav
import com.quickshare.tv.ui.theme.QuickShareThemeHost
import com.quickshare.tv.util.Log

class MainActivity : ComponentActivity() {

    private val openMultiple = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris?.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        selectedUris.value = uris ?: emptyList()
    }

    private val selectedUris = mutableStateOf<List<Uri>>(emptyList())

    private val perms = PermissionsRequester(this) { allGranted, granted ->
        val grantedCount = granted.count { it.value }
        Log.i(
            "MainActivity",
            "Runtime permissions: $grantedCount/${granted.size} granted (allGranted=$allGranted)",
        )
        if (!allGranted) {
            val denied = granted.filterValues { !it }.keys.joinToString(", ")
            Log.w("MainActivity", "Denied permissions: $denied — discovery will degrade gracefully")
        }
    }

    private fun pickFiles() {
        openMultiple.launch(arrayOf("*/*"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Single launch-time permission request bundling everything the
        // app expects to need:
        //   - POST_NOTIFICATIONS for the FGS channel (API 33+).
        //   - BLUETOOTH_ADVERTISE / _SCAN / _CONNECT for the rqs-style
        //     stack: Send-side FE2C wake-up beacon + Receive-side passive
        //     listener that triggers an mDNS re-announce when a phone
        //     starts scanning (API 31+).
        // Denying any of these doesn't break the app; the picker
        // collapses to mDNS-only discovery, the FGS notification simply
        // doesn't render, and the receiver loses its mDNS wake-up nudge.
        perms.requestIfNeeded(
            Permissions.requiredAtRuntime() + Permissions.bleRuntime()
        )

        setContent {
            QuickShareThemeHost {
                QuickShareNav(
                    pickedUris = selectedUris.value,
                    onPickFiles = ::pickFiles,
                    onClearPicked = { selectedUris.value = emptyList() },
                    onExitApp = { finish() },
                )
            }
        }
    }
}
