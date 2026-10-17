package com.blutrixx.plugins.nativephp_sms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Optional always-on foreground service that keeps the app process alive.
 *
 * Some OEM power managers (Transsion's Griffin on Infinix/Tecno/itel) drop SMS_DELIVER for a
 * third-party app whose process was killed by swiping it from recents, so the text is lost
 * before any of our code runs. A running process is not subject to that block. The service
 * does no work; it only holds a silent, minimum-importance notification.
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "NativephpSms.KeepAlive"
        const val CHANNEL_ID = "nativephp_sms_service"
        private const val NOTIFICATION_ID = 0x5355

        @Volatile var running = false
            private set

        /** Starts the service when the setting is on. Safe to call from anywhere; never throws. */
        fun startIfEnabled(context: Context) {
            if (!SmsPrefs.isKeepAliveEnabled(context)) return
            try {
                ContextCompat.startForegroundService(context.applicationContext, Intent(context.applicationContext, KeepAliveService::class.java))
            } catch (e: Exception) {
                // API 31+ refuses foreground-service starts from the background in most cases
                Log.w(TAG, "could not start keep-alive service", e)
            }
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(Intent(context.applicationContext, KeepAliveService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!SmsPrefs.isKeepAliveEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return try {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            running = true
            START_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun buildNotification() = run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Background service", NotificationManager.IMPORTANCE_MIN).apply {
                        description = "Keeps message delivery working when the app is closed"
                        setShowBadge(false)
                    }
                )
            }
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(this, NOTIFICATION_ID, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val icon = applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.sym_action_chat
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle("Ready for new messages")
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { if (contentIntent != null) setContentIntent(contentIntent) }
            .build()
    }
}
