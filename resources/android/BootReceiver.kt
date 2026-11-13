package com.blutrixx.plugins.nativephp_sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * Restarts the keep-alive service after a reboot or an app update, when the setting is on, and writes a `boot` journal
 * line either way. On Transsion phones this receiver never runs while the app is "Prohibited" in Phone Master's
 * auto-start management, so a missing line after a reboot is itself the sign.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "boot_completed"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "package_replaced"
            else -> return
        }
        val keepAlive = KeepAliveService.startIfEnabled(context)
        EventJournal.append(context, "boot", JSONObject().put("reason", reason).put("keep_alive", keepAlive))
    }
}
