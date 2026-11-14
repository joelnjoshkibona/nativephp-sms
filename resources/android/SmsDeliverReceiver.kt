package com.blutrixx.plugins.nativephp_sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import org.json.JSONObject

/**
 * The default-SMS-app receive path (android.provider.Telephony.SMS_DELIVER). The system
 * delivers this ONLY to the app holding the SMS role, and a default app is responsible for
 * writing the message to the provider itself.
 *
 * Everything happens natively, without PHP: ReceivePipeline decides the route (private number,
 * blocklist, spam, normal) and stores, seals and notifies; then the journal entry; then the
 * optional ephemeral PHP hand-off, which is enrichment only and never sees a vault text.
 */
class SmsDeliverReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NativephpSms.Deliver"
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
                val slot = SimSlots.slotForSubscriptionId(appContext, subscriptionId)
                val outcome = ReceivePipeline.receive(
                    appContext,
                    ReceivePipeline.Delivered(address, body, receivedAtNative, smscTimestamp, subscriptionId, slot, messages.size),
                )
                val route = outcome.route
                if (route is ReceivePipeline.Route.Private || route == ReceivePipeline.Route.Blocked) {
                    // nothing that names the sender or the words: the journal lives in sms.sqlite
                    EventJournal.append(
                        appContext, "sms_sealed",
                        JSONObject()
                            .put("route", if (route is ReceivePipeline.Route.Private) "private" else "blocked")
                            .put("sealed", outcome.sealed)
                            .put("notified", outcome.notified)
                            .put("native_done_ms", System.currentTimeMillis() - receivedAtNative)
                    )
                    Log.i(TAG, "received into the vault sealed=${outcome.sealed}")
                    return@execute
                }

                val payload = JSONObject()
                    .put("route", if (route == ReceivePipeline.Route.Spam) "spam" else "normal")
                    .put("from", address)
                    .put("body_len", body.length)
                    .put("parts", messages.size)
                    .put("stored", outcome.systemId != null)
                    .put("row_id", outcome.systemId ?: JSONObject.NULL)
                    .put("message_id", outcome.messageId ?: JSONObject.NULL)
                    .put("thread", outcome.threadId ?: JSONObject.NULL)
                    .put("notified", outcome.notified)
                    .put("sub_id", subscriptionId)
                    .put("slot", slot ?: JSONObject.NULL)
                    .put("smsc_ts", smscTimestamp)
                    .put("native_received_at", receivedAtNative)
                    .put("native_done_ms", System.currentTimeMillis() - receivedAtNative)
                EventJournal.append(appContext, "sms_received", payload)
                Log.i(TAG, "received route=${payload.getString("route")} stored=${outcome.systemId != null} notified=${outcome.notified}")

                // optional PHP hand-off; finish the broadcast when PHP is done (or immediately when off)
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
}
