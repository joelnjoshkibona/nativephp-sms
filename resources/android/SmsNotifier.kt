package com.blutrixx.plugins.nativephp_sms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Plain native notification for an incoming message. Phase 1 adds quick reply and grouping. */
object SmsNotifier {
    private const val TAG = "NativephpSms.Notifier"
    const val CHANNEL_ID = "nativephp_sms_messages"

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Incoming text messages"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    /** Returns true when a notification was actually posted. */
    fun notifyIncoming(context: Context, threadId: Long?, address: String, body: String): Boolean {
        val appContext = context.applicationContext
        return try {
            val compat = NotificationManagerCompat.from(appContext)
            if (!compat.areNotificationsEnabled()) {
                Log.w(TAG, "notifications are disabled for this app")
                return false
            }
            ensureChannel(appContext)

            val launch = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val contentIntent = launch?.let {
                PendingIntent.getActivity(
                    appContext,
                    (threadId ?: address.hashCode().toLong()).toInt(),
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val icon = appContext.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.sym_action_chat

            val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(address)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
            if (contentIntent != null) builder.setContentIntent(contentIntent)

            compat.notify((threadId ?: address.hashCode().toLong()).toInt(), builder.build())
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "notify rejected (POST_NOTIFICATIONS not granted?)", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "notify failed", e)
            false
        }
    }
}
