package com.gopro.unloader.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.gopro.unloader.R

/**
 * Foreground service that keeps the app alive during long downloads/transcodes.
 * Started by MainActivity when an offload begins and stopped when it finishes.
 */
class GoProForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "gopro_offload"
        const val NOTIFICATION_ID = 1

        fun buildNotification(context: Context, message: String): Notification {
            val manager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "GoPro Offload",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("GoPro Unloader")
                .setContentText(message)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val message = intent?.getStringExtra("message") ?: "Running…"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(this, message),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(this, message))
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
