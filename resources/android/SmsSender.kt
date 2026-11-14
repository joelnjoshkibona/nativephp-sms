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

    /** The phone's radio: splitting a text into parts and sending them. Tests set a stand-in (Robolectric has no SMS). */
    interface Radio {
        fun divide(context: Context, subscriptionId: Int, body: String): ArrayList<String>
        fun send(context: Context, subscriptionId: Int, to: String, parts: ArrayList<String>, sent: ArrayList<PendingIntent>, delivered: ArrayList<PendingIntent>)
    }

    @Volatile internal var radio: Radio = object : Radio {
        override fun divide(context: Context, subscriptionId: Int, body: String) = smsManager(context, subscriptionId).divideMessage(body)
        override fun send(context: Context, subscriptionId: Int, to: String, parts: ArrayList<String>, sent: ArrayList<PendingIntent>, delivered: ArrayList<PendingIntent>) {
            val manager = smsManager(context, subscriptionId)
            if (parts.size > 1) manager.sendMultipartTextMessage(to, null, parts, sent, delivered)
            else manager.sendTextMessage(to, null, parts[0], sent[0], delivered[0])
        }
    }

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
        val parts = radio.divide(appContext, subscriptionId, body).ifEmpty { arrayListOf(body) }
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

        // a private number from the normal composer: an ordinary text, and a sealed copy in its vault conversation (the
        // copy's state follows the same callbacks by client_ref). Nothing here says so: the vault stays unseen.
        if (VaultStore.hasKey(appContext) && VaultOps.isPrivate(appContext, to)) {
            VaultStore.put(
                appContext,
                org.json.JSONObject().put("why", "private").put("direction", "out").put("address", to).put("body", body).put("sub_id", subscriptionId),
                now, status = "queued", clientRef = clientRef, parts = parts.size,
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
            radio.send(
                appContext, subscriptionId, to, parts,
                ArrayList(parts.indices.map { callback(SmsResultReceiver.ACTION_SMS_SENT, it) }),
                ArrayList(parts.indices.map { callback(SmsResultReceiver.ACTION_SMS_DELIVERED, it) }),
            )
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

    /**
     * A text written inside the vault: sent by SMS, sealed into the vault as an outgoing row, and nowhere else (not
     * sms.sqlite, not the phone's SMS store, not change_log). Its state follows the callbacks by client_ref.
     */
    fun sendFromVault(context: Context, to: String, body: String, clientRef: String, subscriptionId: Int) {
        val appContext = context.applicationContext
        val parts = radio.divide(appContext, subscriptionId, body).ifEmpty { arrayListOf(body) }
        val now = System.currentTimeMillis()
        check(VaultStore.put(
            appContext,
            org.json.JSONObject().put("why", "private").put("direction", "out").put("address", to).put("body", body).put("sub_id", subscriptionId),
            now, status = "queued", clientRef = clientRef, parts = parts.size,
        )) { "no vault to keep it in" }
        fun callback(action: String, part: Int): PendingIntent = PendingIntent.getBroadcast(
            appContext,
            "$clientRef|$action|$part".hashCode(),
            Intent(appContext, SmsResultReceiver::class.java)
                .setAction(action)
                .putExtra("client_ref", clientRef)
                .putExtra("vault", true)
                .putExtra("part", part)
                .putExtra("parts", parts.size),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        )
        try {
            radio.send(
                appContext, subscriptionId, to, parts,
                ArrayList(parts.indices.map { callback(SmsResultReceiver.ACTION_SMS_SENT, it) }),
                ArrayList(parts.indices.map { callback(SmsResultReceiver.ACTION_SMS_DELIVERED, it) }),
            )
        } catch (e: Exception) {
            repeat(parts.size) { VaultStore.countPart(appContext, clientRef, "sent", false) }
            EventJournal.append(appContext, "vault_status", org.json.JSONObject().put("status", "failed"))
            throw e
        }
        EventJournal.append(appContext, "vault_status", org.json.JSONObject().put("status", "queued").put("parts", parts.size))
    }
}
