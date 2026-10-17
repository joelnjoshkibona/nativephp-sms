package com.blutrixx.plugins.nativephp_sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the keep-alive service after a reboot or an app update, when the setting is on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> KeepAliveService.startIfEnabled(context)
        }
    }
}
