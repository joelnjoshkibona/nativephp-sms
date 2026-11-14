package com.blutrixx.plugins.nativephp_sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import org.json.JSONObject

/**
 * SmsManager sentIntent / deliveredIntent callbacks, one pair per part. Internal only (exported=false); the PendingIntents
 * are created by SmsSender. Each part's result is counted on the message's row in sms.sqlite (MessageStore.countPart), and
 * only a change of the message's status is acted on: the system store row follows, one `sms_status` journal line is
 * written and the PHP hand-off runs. A part that fails fails the message; a later OK part never flips it back.
 */
class SmsResultReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SMS_SENT = "com.blutrixx.plugins.nativephp_sms.SMS_SENT"
        const val ACTION_SMS_DELIVERED = "com.blutrixx.plugins.nativephp_sms.SMS_DELIVERED"
        private const val TAG = "NativephpSms.Result"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val event = when (intent.action) {
            ACTION_SMS_SENT -> "sent"
            ACTION_SMS_DELIVERED -> "delivered"
            else -> return
        }
        val clientRef = intent.getStringExtra("client_ref") ?: "unknown"
        val to = intent.getStringExtra("to") ?: ""
        val rowId = intent.getLongExtra("row_id", -1L)
        val code = resultCode // read now: only guaranteed valid until pending.finish()
        var ok = code == Activity.RESULT_OK
        // A delivery report always arrives with RESULT_OK; the real status is in the SMS-STATUS-REPORT pdu.
        var deliveryStatus: Int? = null
        if (event == "delivered") {
            val pdu = intent.getByteArrayExtra("pdu")
            if (pdu != null) {
                try {
                    val st: Int = SmsMessage.createFromPdu(pdu, intent.getStringExtra("format")).status
                    deliveryStatus = st
                    ok = ok && st < 64 // 0-31 complete, 32-63 still trying, 64+ failed
                } catch (e: Exception) {
                    Log.w(TAG, "could not parse delivery pdu", e)
                }
            }
        }
        val finalOk = ok
        val finalDeliveryStatus = deliveryStatus
        val appContext = context.applicationContext

        val pending = goAsync()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish() { if (finished.compareAndSet(false, true)) pending.finish() }
        val messageId = intent.getStringExtra("message_id")
        val part = intent.getIntExtra("part", 0)
        val fromVault = intent.getBooleanExtra("vault", false)
        Background.executor.execute {
            var handedToEphemeral = false
            try {
                // the vault's copy (a private number from the normal composer) or a text sent from the vault: its readable
                // state only; a vault text's journal line names nobody and never reaches PHP
                val vaultMoved = VaultStore.countPart(appContext, clientRef, event, finalOk)
                if (fromVault) {
                    if (vaultMoved != null) EventJournal.append(appContext, "vault_status", JSONObject().put("status", vaultMoved))
                    return@execute
                }
                val change = messageId?.let { id -> SmsDb.write(appContext) { db -> MessageStore.countPart(db, id, event, finalOk) } }
                if (messageId != null && change == null) {
                    Log.i(TAG, "part $part of $clientRef: $event ok=$finalOk (message status unchanged)")
                    return@execute
                }
                val status = change?.to ?: if (finalOk) event else "failed"
                if (rowId >= 0) {
                    when (status) {
                        "sent" -> SmsStore.updateOutgoing(
                            appContext, rowId, Telephony.Sms.MESSAGE_TYPE_SENT, null,
                            intArrayOf(Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED)
                        )
                        "delivered" -> SmsStore.updateOutgoing(appContext, rowId, null, Telephony.Sms.STATUS_COMPLETE)
                        "failed" -> SmsStore.updateOutgoing(appContext, rowId, if (event == "sent") Telephony.Sms.MESSAGE_TYPE_FAILED else null, Telephony.Sms.STATUS_FAILED)
                    }
                }
                val payload = JSONObject()
                    .put("client_ref", clientRef)
                    .put("message_id", messageId ?: JSONObject.NULL)
                    .put("status", status)
                    .put("event", event)
                    .put("part", part)
                    .put("parts", change?.partsTotal ?: intent.getIntExtra("parts", 1))
                    .put("result_code", code)
                    .put("delivery_status", finalDeliveryStatus ?: JSONObject.NULL)
                    .put("to", to)
                    .put("row_id", if (rowId >= 0) rowId else JSONObject.NULL)
                    .put("sub_id", intent.getIntExtra("sub_id", -1))
                EventJournal.append(appContext, "sms_status", payload)
                Log.i(TAG, "message $clientRef is now $status")

                EphemeralDispatch.dispatch(appContext, "sms:status", payload) { finish() }
                handedToEphemeral = true
            } catch (e: Throwable) {
                Log.e(TAG, "result handling failed", e)
            } finally {
                if (!handedToEphemeral) finish()
            }
        }
    }
}
