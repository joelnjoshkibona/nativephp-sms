package com.blutrixx.plugins.nativephp_sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log

/**
 * Sends a (multipart) text. The message's row in sms.sqlite is written first (queued, with its part count), and while this
 * app is the default SMS app it is mirrored into the system store's outbox. Each part gets its own sent and delivered
 * callback (SmsResultReceiver), which count onto the one row, so the message has one status: queued, sent, delivered or
 * failed. If the app is not the default, the text is still sent (SEND_SMS) but the system store is not written.
 */
object SmsSender {
    private const val TAG = "NativephpSms.Sender"

    data class Result(
        val clientRef: String,
        val stored: Boolean,
        val rowId: Long?,
        val threadId: Long?,
        val parts: Int,
        val messageId: String?,
        val storeThreadId: String?,
        val duplicate: Boolean,
    )

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
        val manager = smsManager(appContext, subscriptionId)
        val parts = manager.divideMessage(body).ifEmpty { arrayListOf(body) }
        val now = System.currentTimeMillis()

        // a retried call with the same client_ref answers with the first one's message instead of sending twice
        SmsDb.get(appContext).rawQuery("SELECT id, thread_id, system_id, parts_total FROM messages WHERE client_ref = ?", arrayOf(clientRef)).use { c ->
            if (c.moveToFirst()) {
                val systemId = if (c.isNull(2)) null else c.getLong(2)
                return Result(clientRef, systemId != null, systemId, null, c.getInt(3), c.getString(0), c.getString(1), duplicate = true)
            }
        }

        val isDefault = SmsStore.isDefaultSmsApp(appContext)
        val inserted = if (isDefault) SmsStore.insertOutbox(appContext, to, body, subscriptionId) else SmsStore.Inserted(null, null)
        val rowId = inserted.id ?: -1L
        val slot = SimSlots.slotForSubscriptionId(appContext, subscriptionId)
        val saved = SmsDb.write(appContext) { db ->
            MessageStore.insertOutgoing(
                db,
                MessageStore.Outgoing(to, body, now, subscriptionId, slot, parts.size, clientRef, inserted.id, inserted.threadId),
                now,
            )
        }

        fun callback(action: String, part: Int): PendingIntent = PendingIntent.getBroadcast(
            appContext,
            "$clientRef|$action|$part".hashCode(),
            Intent(appContext, SmsResultReceiver::class.java)
                .setAction(action)
                .putExtra("client_ref", clientRef)
                .putExtra("message_id", saved.messageId)
                .putExtra("part", part)
                .putExtra("parts", parts.size)
                .putExtra("to", to)
                .putExtra("row_id", rowId)
                .putExtra("sub_id", subscriptionId),
            // Mutable on API 31+: the system must be able to add the delivery report's pdu/format extras.
            // The intent is explicit (component set), which Android 14 requires for mutable PendingIntents.
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        )

        try {
            if (parts.size > 1) {
                val sent = ArrayList(parts.indices.map { callback(SmsResultReceiver.ACTION_SMS_SENT, it) })
                val delivered = ArrayList(parts.indices.map { callback(SmsResultReceiver.ACTION_SMS_DELIVERED, it) })
                manager.sendMultipartTextMessage(to, null, parts, sent, delivered)
            } else {
                manager.sendTextMessage(to, null, body, callback(SmsResultReceiver.ACTION_SMS_SENT, 0), callback(SmsResultReceiver.ACTION_SMS_DELIVERED, 0))
            }
        } catch (e: Exception) {
            Log.e(TAG, "send failed for $clientRef", e)
            if (rowId >= 0) SmsStore.updateOutgoing(appContext, rowId, Telephony.Sms.MESSAGE_TYPE_FAILED, Telephony.Sms.STATUS_FAILED)
            SmsDb.write(appContext) { db ->
                db.execSQL("UPDATE messages SET status = 'failed', parts_failed = parts_total, updated_at = ? WHERE id = ?", arrayOf<Any>(System.currentTimeMillis(), saved.messageId))
            }
            EventJournal.append(
                appContext, "send_failed",
                org.json.JSONObject().put("client_ref", clientRef).put("message_id", saved.messageId).put("error", e.message ?: e.javaClass.simpleName)
            )
            throw e
        }

        EventJournal.append(
            appContext, "send_queued",
            org.json.JSONObject()
                .put("client_ref", clientRef).put("message_id", saved.messageId).put("to", to).put("parts", parts.size)
                .put("stored", rowId >= 0).put("sub_id", subscriptionId)
        )
        return Result(clientRef, rowId >= 0, if (rowId >= 0) rowId else null, inserted.threadId, parts.size, saved.messageId, saved.threadId, duplicate = false)
    }
}
