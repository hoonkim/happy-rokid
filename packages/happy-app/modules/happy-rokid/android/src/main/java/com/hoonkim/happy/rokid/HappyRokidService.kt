package com.hoonkim.happy.rokid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps Happy's React Native sync runtime and the CXR-L binder connection alive
 * while the phone is locked. The service does not hold a wake lock: it wakes
 * only for normal network and device callbacks.
 */
class HappyRokidService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Rokid session relay",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps Happy session updates connected to Rokid Glasses"
                setShowBadge(false)
            },
        )
    }

    private fun createNotification(): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Happy · Rokid Glasses")
            .setContentText("Happy 세션을 안경으로 중계하고 있습니다")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "happy_rokid_relay"
        private const val NOTIFICATION_ID = 5918

        fun start(context: Context) {
            context.startForegroundService(Intent(context, HappyRokidService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HappyRokidService::class.java))
        }
    }
}
