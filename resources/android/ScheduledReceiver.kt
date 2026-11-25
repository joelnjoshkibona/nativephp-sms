package com.blutrixx.plugins.nativephp_sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** A scheduled text's alarm (ScheduledSender): sends it on the plugin's background thread, with no web view. */
class ScheduledReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScheduledSender.ACTION_DUE) return
        val id = intent.getStringExtra("id") ?: return
        val app = context.applicationContext
        val pending = goAsync()
        Background.executor.execute {
            try {
                ScheduledSender.sendNow(app, id, onlyIfDue = true)
            } finally {
                pending.finish()
            }
        }
    }
}
