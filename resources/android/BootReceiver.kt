package com.blutrixx.plugins.nativephp_sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * Restarts the keep-alive service after a reboot or an app update, when the setting is on, and writes a `boot` journal
 * line either way. When the app was the default SMS app and no longer is (another SMS app was chosen, on purpose or not),
 * it posts a notification that leads back to the fix. On Transsion phones this receiver never runs while the app is
 * "Prohibited" in Phone Master's auto-start management, so a missing line after a reboot is itself the sign.
 * It also deletes spam older than 30 days (the app does the same at start-up).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "boot_completed"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "package_replaced"
            else -> return
        }
        val keepAlive = KeepAliveService.startIfEnabled(context)
        val pending = goAsync()
        Background.executor.execute {
            try {
                val purged = VaultOps.purgeSpam(context)
                if (purged > 0) EventJournal.append(context, "spam_purged", JSONObject().put("count", purged))
            } catch (e: Exception) {
                android.util.Log.w("SmsBoot", "spam purge failed", e)
            } finally {
                pending.finish()
            }
        }
        SmsPrefs.setLastBoot(context, reason, keepAlive)
        val isDefault = SmsStore.isDefaultSmsApp(context)
        EventJournal.append(context, "boot", JSONObject().put("reason", reason).put("keep_alive", keepAlive).put("is_default", isDefault))
        if (isDefault) {
            SmsPrefs.markDefault(context)
        } else if (SmsPrefs.wasDefault(context)) {
            // it was the default SMS app and is not any more: texts now go to another app, so say so at once
            SmsPrefs.setDefaultLost(context, System.currentTimeMillis())
            val notified = SmsNotifier.notifyDefaultLost(context)
            EventJournal.append(context, "default_sms_lost", JSONObject().put("reason", reason).put("notified", notified))
        }
    }
}
