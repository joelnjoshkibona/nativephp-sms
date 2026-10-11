package com.blutrixx.plugins.nativephp_sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

/**
 * WAP_PUSH_DELIVER. Declared because the system will not offer the SMS role to an app that
 * lacks it. MMS is deferred (Phase 4): this only records that one arrived and does nothing
 * else, so an MMS received while this app is default is NOT stored or shown.
 */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.w("NativephpSms.Mms", "MMS/WAP push received but MMS is not implemented yet (action=${intent.action})")
        EventJournal.append(context, "mms_ignored", JSONObject().put("action", intent.action ?: ""))
    }
}
