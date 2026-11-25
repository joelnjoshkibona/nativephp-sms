package com.blutrixx.plugins.nativephp_sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import org.json.JSONObject
import java.util.UUID

/**
 * Reply and Mark as read on a message notification, without opening the app. A reply is sent like any text
 * (SmsSender: its row, one status, the journal) and marks the conversation read, which clears its notification.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_REPLY = "com.blutrixx.plugins.nativephp_sms.REPLY"
        const val ACTION_MARK_READ = "com.blutrixx.plugins.nativephp_sms.MARK_READ"
        private const val TAG = "NativephpSms.Action"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val threadId = intent.getStringExtra(SmsNotifier.EXTRA_THREAD) ?: return
        val address = intent.getStringExtra(SmsNotifier.EXTRA_ADDRESS) ?: ""
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(SmsNotifier.REPLY_KEY)?.toString()?.trim().orEmpty()
        val app = context.applicationContext
        val pending = goAsync()
        Background.executor.execute {
            try {
                handle(app, intent.action, threadId, address, text)
            } finally {
                pending.finish()
            }
        }
    }

    internal fun handle(context: Context, action: String?, threadId: String, address: String, text: String) {
        try {
            when (action) {
                ACTION_REPLY -> {
                    if (text.isNotEmpty() && address.isNotEmpty()) {
                        SmsSender.send(context, address, text, UUID.randomUUID().toString(), SmsSender.lastSubscription(context, threadId))
                        EventJournal.append(context, "notification_reply", JSONObject().put("thread", threadId).put("body_len", text.length))
                    }
                    StoreWrites.saveThread(context, threadId, mapOf("read" to true))
                }
                ACTION_MARK_READ -> StoreWrites.saveThread(context, threadId, mapOf("read" to true))
            }
        } catch (e: Exception) {
            Log.e(TAG, "notification action failed", e)
            EventJournal.append(context, "notification_action_failed", JSONObject().put("action", action ?: "").put("error", e.message ?: e.javaClass.simpleName))
        } finally {
            SmsNotifier.cancelThread(context, threadId)
        }
    }

}
