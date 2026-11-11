package com.telehost.app

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
import androidx.core.app.ServiceCompat

class BotHostService : Service() {

    companion object {
        const val CHANNEL_ID = "telehost_service_channel"
        const val NOTIFICATION_ID = 1001

        fun startServiceSafe(context: Context) {
            try {
                val intent = Intent(context, BotHostService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

        fun stopServiceSafe(context: Context) {
            try {
                context.stopService(Intent(context, BotHostService::class.java))
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("TeleHost Bot Service")
                .setContentText("Хостинг Telegram ботов активен")
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "TeleHost Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Фоновый сервис хостинга Telegram ботов"
                }
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }
}
