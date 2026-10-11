package com.blutrixx.plugins.nativephp_sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * The default-SMS-app receive path (android.provider.Telephony.SMS_DELIVER). The system
 * delivers this ONLY to the app holding the SMS role, and a default app is responsible for
 * writing the message to the provider itself.
 *
 * Order matters and is the design rule under test in the Phase 0 spike:
 *   1. blocklist check (native, no PHP)      -> blocked: quarantine, no store write, no notification
 *   2. system SMS store write (native)        -> the message now survives whatever happens next
 *   3. notification (native)
 *   4. journal entry (native)
 *   5. optional ephemeral PHP hand-off        -> enrichment only; failure here loses nothing
 */
class SmsDeliverReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NativephpSms.Deliver"
        private const val QUARANTINE_FILE = "sms_blocked_quarantine.jsonl"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val receivedAtNative = System.currentTimeMillis()
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) {
            Log.w(TAG, "SMS_DELIVER with no messages in intent")
            return
        }

        val address = messages[0].originatingAddress ?: "unknown"
        val body = messages.joinToString(separator = "") { it.messageBody ?: "" }
        val smscTimestamp = messages[0].timestampMillis
        val subscriptionId = intent.getIntExtra(
            "subscription",
            intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1)
        )
        val appContext = context.applicationContext

        val pending = goAsync()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish() { if (finished.compareAndSet(false, true)) pending.finish() }
        Background.executor.execute {
            var handedToEphemeral = false
            try {
                if (SmsPrefs.isBlocked(appContext, address)) {
                    quarantine(appContext, address, body, receivedAtNative)
                    EventJournal.append(
                        appContext, "sms_blocked",
                        JSONObject().put("from", address).put("body_len", body.length)
                    )
                    return@execute
                }

                // 2. system store -- before anything that can fail or be slow
                val inserted = SmsStore.insertInbox(appContext, address, body, receivedAtNative, smscTimestamp, subscriptionId)
                val stored = inserted.id != null

                // 3. notification
                val notified = SmsNotifier.notifyIncoming(appContext, inserted.threadId, address, body)

                // 4. journal (the spike's evidence trail)
                val payload = JSONObject()
                    .put("from", address)
                    .put("body_len", body.length)
                    .put("parts", messages.size)
                    .put("stored", stored)
                    .put("row_id", inserted.id ?: JSONObject.NULL)
                    .put("thread_id", inserted.threadId ?: JSONObject.NULL)
                    .put("notified", notified)
                    .put("sub_id", subscriptionId)
                    .put("slot", SimSlots.slotForSubscriptionId(appContext, subscriptionId) ?: JSONObject.NULL)
                    .put("smsc_ts", smscTimestamp)
                    .put("native_received_at", receivedAtNative)
                    .put("native_done_ms", System.currentTimeMillis() - receivedAtNative)
                EventJournal.append(appContext, "sms_received", payload)
                Log.i(TAG, "received from=$address stored=$stored notified=$notified")

                // 5. optional PHP hand-off; finish the broadcast when PHP is done (or immediately when off)
                EphemeralDispatch.dispatch(appContext, "sms:received", payload) { finish() }
                handedToEphemeral = true
            } catch (e: Throwable) {
                Log.e(TAG, "receive path failed", e)
                EventJournal.append(appContext, "sms_receive_error", JSONObject().put("error", e.message ?: e.javaClass.simpleName))
            } finally {
                if (!handedToEphemeral) finish()
            }
        }
    }

    /**
     * Spike-only quarantine: plaintext. Phase 1 replaces this with an X25519 sealed box to
     * the vault public key, which needs no passcode and no PHP at receive time.
     */
    private fun quarantine(context: Context, address: String, body: String, at: Long) {
        try {
            val line = JSONObject().put("from", address).put("body", body).put("at", at)
            File(context.filesDir, QUARANTINE_FILE).appendText(line.toString() + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "quarantine write failed", e)
        }
    }
}
