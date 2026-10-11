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
 * SmsManager sentIntent / deliveredIntent callbacks. Internal only (exported=false); the
 * PendingIntents are created by SmsSender. Updates the provider row (when one was written)
 * and journals the outcome. For a multipart message each part reports separately, so every
 * update here is idempotent: any failed part marks the message failed, and a later OK part
 * never flips it back.
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
        Background.executor.execute {
            var handedToEphemeral = false
            try {
                if (rowId >= 0) {
                    when {
                        // OK only advances a row still in the outbox, so a later OK part never undoes a failed one
                        event == "sent" && finalOk -> SmsStore.updateOutgoing(
                            appContext, rowId, Telephony.Sms.MESSAGE_TYPE_SENT, null,
                            intArrayOf(Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED)
                        )
                        event == "sent" -> SmsStore.updateOutgoing(appContext, rowId, Telephony.Sms.MESSAGE_TYPE_FAILED, Telephony.Sms.STATUS_FAILED)
                        event == "delivered" && finalOk -> SmsStore.updateOutgoing(appContext, rowId, null, Telephony.Sms.STATUS_COMPLETE)
                        else -> SmsStore.updateOutgoing(appContext, rowId, null, Telephony.Sms.STATUS_FAILED)
                    }
                }
                val payload = JSONObject()
                    .put("client_ref", clientRef)
                    .put("event", event)
                    .put("ok", finalOk)
                    .put("result_code", code)
                    .put("delivery_status", finalDeliveryStatus ?: JSONObject.NULL)
                    .put("to", to)
                    .put("row_id", if (rowId >= 0) rowId else JSONObject.NULL)
                    .put("sub_id", intent.getIntExtra("sub_id", -1))
                EventJournal.append(appContext, "sms_$event", payload)
                Log.i(TAG, "event=$event client_ref=$clientRef ok=$finalOk resultCode=$code")

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
