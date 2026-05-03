package com.axonwatch.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.axonwatch.R
import com.axonwatch.service.BluetoothScanService
import com.axonwatch.ui.MainActivity

class NotificationHelper(private val context: Context) {

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Bluetooth Scanning",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows scanning status and recent match count"
                setShowBadge(false)
            }
        )
    }

    fun buildScanningNotification(matchCount: Int): Notification {
        val openPending = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPending = PendingIntent.getService(
            context, 1,
            Intent(context, BluetoothScanService::class.java)
                .setAction(BluetoothScanService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val matchText = if (matchCount == 1) "1 unique match" else "$matchCount unique matches"
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bluetooth_searching)
            .setContentTitle("AxonWatch scanning")
            .setContentText("$matchText in the last 5 min")
            .setContentIntent(openPending)
            .addAction(R.drawable.ic_stop, "Stop", stopPending)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun updateNotification(matchCount: Int) {
        manager.notify(NOTIFICATION_ID, buildScanningNotification(matchCount))
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "axonwatch_scan"
    }
}
