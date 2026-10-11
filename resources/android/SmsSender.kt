package com.blutrixx.plugins.nativephp_sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log

/**
 * Sends a (multipart) text and, while this app is the default SMS app, mirrors it into the
 * system store: outbox row -> sent / failed on the SENT callback -> status complete on the
 * DELIVERED callback. If the app is not the default, the text is still sent (SEND_SMS) but
 * nothing is stored, and the result says so ("stored": false).
 */
object SmsSender {
    private const val TAG = "NativephpSms.Sender"

    data class Result(val clientRef: String, val stored: Boolean, val rowId: Long?, val threadId: Long?, val parts: Int)

    @Suppress("DEPRECATION")
    private fun smsManager(context: Context, subscriptionId: Int): SmsManager {
        if (subscriptionId >= 0) return SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        } else {
            SmsManager.getDefault()
        }
    }

    fun send(context: Context, to: String, body: String, clientRef: String, subscriptionId: Int): Result {
        val appContext = context.applicationContext
        val isDefault = SmsStore.isDefaultSmsApp(appContext)
        val inserted = if (isDefault) SmsStore.insertOutbox(appContext, to, body, subscriptionId) else SmsStore.Inserted(null, null)
        val rowId = inserted.id ?: -1L

        fun callback(action: String): PendingIntent = PendingIntent.getBroadcast(
            appContext,
            (clientRef + action).hashCode(),
            Intent(appContext, SmsResultReceiver::class.java)
                .setAction(action)
                .putExtra("client_ref", clientRef)
                .putExtra("to", to)
                .putExtra("row_id", rowId)
                .putExtra("sub_id", subscriptionId),
            // Mutable on API 31+: the system must be able to add the delivery report's pdu/format extras.
            // The intent is explicit (component set), which Android 14 requires for mutable PendingIntents.
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        )
        val sentIntent = callback(SmsResultReceiver.ACTION_SMS_SENT)
        val deliveredIntent = callback(SmsResultReceiver.ACTION_SMS_DELIVERED)

        var partCount = 1
        try {
            val manager = smsManager(appContext, subscriptionId)
            val parts = manager.divideMessage(body)
            partCount = parts.size
            if (parts.size > 1) {
                val sent = ArrayList<PendingIntent>().apply { repeat(parts.size) { add(sentIntent) } }
                val delivered = ArrayList<PendingIntent>().apply { repeat(parts.size) { add(deliveredIntent) } }
                manager.sendMultipartTextMessage(to, null, parts, sent, delivered)
            } else {
                manager.sendTextMessage(to, null, body, sentIntent, deliveredIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "send failed for $clientRef", e)
            if (rowId >= 0) SmsStore.updateOutgoing(appContext, rowId, Telephony.Sms.MESSAGE_TYPE_FAILED, Telephony.Sms.STATUS_FAILED)
            EventJournal.append(
                appContext, "send_failed",
                org.json.JSONObject().put("client_ref", clientRef).put("error", e.message ?: e.javaClass.simpleName)
            )
            throw e
        }

        EventJournal.append(
            appContext, "send_queued",
            org.json.JSONObject()
                .put("client_ref", clientRef).put("to", to).put("parts", partCount)
                .put("stored", rowId >= 0).put("sub_id", subscriptionId)
        )
        return Result(clientRef, rowId >= 0, if (rowId >= 0) rowId else null, inserted.threadId, partCount)
    }
}
