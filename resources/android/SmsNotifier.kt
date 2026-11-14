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
import androidx.core.app.Person
import androidx.core.app.RemoteInput

/**
 * The app's notifications. A text gets one notification per conversation (notifyThread): the unread texts of that
 * conversation, newest last, with Reply (answered from the shade) and Mark as read; tapping it opens the conversation.
 * A muted conversation, or the one open on screen (Sms.SetActiveThread), posts nothing.
 */
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

    const val GROUP = "nativephp_sms_threads"
    const val EXTRA_THREAD = "nativephp_sms_thread"
    const val EXTRA_ADDRESS = "nativephp_sms_address"
    const val REPLY_KEY = "nativephp_sms_reply"

    /** The conversation on screen (the app says so with Sms.SetActiveThread): its texts post no notification. */
    @Volatile var activeThread: String? = null

    fun notificationId(threadId: String): Int = (threadId.hashCode() and 0x3fffffff) + 10_000

    data class Line(val body: String, val ts: Long)

    /** The conversation's unread texts, oldest first, at most six. */
    internal fun unread(context: Context, threadId: String): List<Line> =
        SmsDb.get(context).rawQuery(
            "SELECT body, ts FROM messages WHERE thread_id = ? AND direction = 'in' AND read = 0 ORDER BY ts DESC, id DESC LIMIT 6",
            arrayOf(threadId),
        ).use { c -> buildList { while (c.moveToNext()) add(Line(c.getString(0), c.getLong(1))) } }.reversed()

    internal fun isMuted(context: Context, threadId: String): Boolean =
        SmsDb.get(context).rawQuery("SELECT muted FROM threads WHERE id = ?", arrayOf(threadId)).use { it.moveToFirst() && it.getInt(0) == 1 }

    /** Why a text posted nothing, or null when it was posted. */
    enum class Skip { ACTIVE, MUTED, DISABLED, FAILED }

    /**
     * The conversation's notification for a text just stored: its unread texts with the sender's name (or number), Reply
     * and Mark as read. Returns null when posted, else why not.
     */
    fun notifyThread(context: Context, threadId: String, address: String): Skip? {
        val appContext = context.applicationContext
        if (activeThread == threadId) return Skip.ACTIVE
        if (isMuted(appContext, threadId)) return Skip.MUTED
        return try {
            val compat = NotificationManagerCompat.from(appContext)
            if (!compat.areNotificationsEnabled()) return Skip.DISABLED
            ensureChannel(appContext)
            val id = notificationId(threadId)
            val sender = Person.Builder().setName(ContactSync.nameFor(appContext, address) ?: address).setKey(address).build()
            val me = Person.Builder().setName("You").build()
            val style = NotificationCompat.MessagingStyle(me)
            unread(appContext, threadId).forEach { style.addMessage(it.body, it.ts, sender) }
            val icon = appContext.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.sym_action_chat
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

            val open = Intent(appContext, OpenThreadActivity::class.java).putExtra(EXTRA_THREAD, threadId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val action = { name: String -> Intent(appContext, NotificationActionReceiver::class.java).setAction(name)
                .putExtra(EXTRA_THREAD, threadId).putExtra(EXTRA_ADDRESS, address) }
            val reply = NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_send, "Reply",
                PendingIntent.getBroadcast(appContext, id, action(NotificationActionReceiver.ACTION_REPLY), PendingIntent.FLAG_UPDATE_CURRENT or mutable),
            ).addRemoteInput(RemoteInput.Builder(REPLY_KEY).setLabel("Reply").build())
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).setAllowGeneratedReplies(false).build()
            val markRead = NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_view, "Mark as read",
                PendingIntent.getBroadcast(appContext, id + 1, action(NotificationActionReceiver.ACTION_MARK_READ), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).setShowsUserInterface(false).build()

            val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(icon)
                .setStyle(style)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setGroup(GROUP)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(appContext, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .addAction(reply)
                .addAction(markRead)
            compat.notify(id, builder.build())
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "notify rejected (POST_NOTIFICATIONS not granted?)", e)
            Skip.DISABLED
        } catch (e: Exception) {
            Log.e(TAG, "notify failed", e)
            Skip.FAILED
        }
    }

    fun cancelThread(context: Context, threadId: String) {
        NotificationManagerCompat.from(context.applicationContext).cancel(notificationId(threadId))
    }

    private const val PRIVATE_ID = 7302

    /**
     * A text from a private number: "New message" and nothing else, no name, no number, no words, so the notification
     * shade never shows what the vault holds. Returns true when it was posted.
     */
    fun notifyPrivate(context: Context): Boolean {
        val appContext = context.applicationContext
        return try {
            val compat = NotificationManagerCompat.from(appContext)
            if (!compat.areNotificationsEnabled()) return false
            ensureChannel(appContext)
            val launch = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val icon = appContext.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.sym_action_chat
            val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle("New message")
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setAutoCancel(true)
            launch?.let {
                builder.setContentIntent(PendingIntent.getActivity(appContext, PRIVATE_ID, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            compat.notify(PRIVATE_ID, builder.build())
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "notify rejected (POST_NOTIFICATIONS not granted?)", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "notify failed", e)
            false
        }
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

    private const val ALERTS_CHANNEL_ID = "nativephp_sms_alerts"
    private const val DEFAULT_LOST_ID = 7301

    /** "<app> is no longer your SMS app": opens the app, which shows the fix. Returns true when it was posted. */
    fun notifyDefaultLost(context: Context): Boolean {
        val appContext = context.applicationContext
        return try {
            val compat = NotificationManagerCompat.from(appContext)
            if (!compat.areNotificationsEnabled()) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (manager.getNotificationChannel(ALERTS_CHANNEL_ID) == null) {
                    manager.createNotificationChannel(
                        NotificationChannel(ALERTS_CHANNEL_ID, "Problems", NotificationManager.IMPORTANCE_HIGH).apply {
                            description = "When texts can no longer reach this app"
                        }
                    )
                }
            }
            val name = appContext.applicationInfo.loadLabel(appContext.packageManager).toString()
            val launch = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val icon = appContext.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.sym_action_chat
            val text = "Texts now go to another app. Tap to make $name your SMS app again."
            val builder = NotificationCompat.Builder(appContext, ALERTS_CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle("$name is no longer your SMS app")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
            launch?.let {
                builder.setContentIntent(PendingIntent.getActivity(appContext, DEFAULT_LOST_ID, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            compat.notify(DEFAULT_LOST_ID, builder.build())
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "default-lost notification rejected", e)
            false
        }
    }

    fun cancelDefaultLost(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(DEFAULT_LOST_ID)
    }
}
