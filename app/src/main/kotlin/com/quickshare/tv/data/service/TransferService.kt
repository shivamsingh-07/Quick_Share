package com.quickshare.tv.data.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.quickshare.tv.MainActivity
import com.quickshare.tv.R
import com.quickshare.tv.util.Log

/**
 * Foreground service **only while bytes are moving** (inbound or outbound).
 * Reduces the chance the OS kills the process mid-transfer. Android requires a
 * notification for any FGS — this uses a **low-importance, silent** channel
 * (no completion/toast notifications; those stay disabled).
 */
class TransferService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val title = intent?.getStringExtra(EXTRA_TITLE)
            ?: getString(R.string.fgs_notification_title)
        val text = intent?.getStringExtra(EXTRA_TEXT)
            ?: getString(R.string.fgs_notification_text_default)

        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotif(title, text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        Log.i(SCOPE, "Foreground active: $title")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(SCOPE, "Service destroyed")
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.fgs_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.fgs_channel_description)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
    }

    private fun buildNotif(title: String, text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_quickshare)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    companion object {
        private const val SCOPE = "TransferService"
        const val CHANNEL_ID = "quickshare.fgs.transfer"
        const val NOTIF_ID = 0xC5

        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
    }
}

/**
 * Starts/stops [TransferService]. Call only when a transfer session is active.
 */
object TransferServiceController {

    fun start(context: Context, title: String, text: String) {
        val intent = Intent(context, TransferService::class.java).apply {
            putExtra(TransferService.EXTRA_TITLE, title)
            putExtra(TransferService.EXTRA_TEXT, text)
        }
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { Log.w("TransferSvcCtrl", "startForegroundService failed", it) }
    }

    fun update(context: Context, title: String, text: String) = start(context, title, text)

    fun stop(context: Context) {
        runCatching { context.stopService(Intent(context, TransferService::class.java)) }
            .onFailure { Log.w("TransferSvcCtrl", "stopService failed", it) }
    }
}
