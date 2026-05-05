package com.quickshare.tv

import android.app.Application
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.quickshare.tv.data.repository.PreferencesSettingsRepository
import com.quickshare.tv.data.repository.ReceiveRepository
import com.quickshare.tv.data.repository.SendRepository
import com.quickshare.tv.data.repository.SettingsRepository
import com.quickshare.tv.util.Log

/**
 * Process-wide singletons. We deliberately avoid a DI framework — for a project
 * this size manual injection is clearer and lighter.
 */
class QuickShareApp : Application() {

    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var receiveRepository: ReceiveRepository
        private set
    lateinit var sendRepository: SendRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsRepository = PreferencesSettingsRepository(this)
        receiveRepository = ReceiveRepository(this, settingsRepository)
        sendRepository = SendRepository(this, settingsRepository)

        // Single startup banner so logcat tail captures the running build
        // and the device class without us having to chase it down later
        // from a bug report.
        Log.i(
            "App",
            "Quick Share ${BuildConfig.VERSION_NAME} (vc=${BuildConfig.VERSION_CODE}) " +
                "starting on ${Build.MANUFACTURER} ${Build.MODEL} " +
                "Android ${Build.VERSION.RELEASE} (sdk=${Build.VERSION.SDK_INT})",
        )

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    receiveRepository.onApplicationBackgrounded()
                    sendRepository.onApplicationBackgrounded()
                }

                override fun onStart(owner: LifecycleOwner) {
                    // Resume any picker discovery that was paused (not
                    // torn down) by `onApplicationBackgrounded`. This
                    // is what survives a brief jump out to the system
                    // Bluetooth-enable dialog: prepared payload kept,
                    // discovery restarts the moment we're back.
                    sendRepository.onApplicationForegrounded()
                }
            },
        )
    }

    companion object {
        @Volatile lateinit var instance: QuickShareApp; private set
    }
}
