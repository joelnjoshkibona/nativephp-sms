package com.blutrixx.plugins.nativephp_sms

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * A tapped message notification: records which conversation to open (SmsPrefs, read by the app with Sms.TakePendingOpen)
 * and brings the app to the front. A transparent trampoline like ComposeActivity, with no screen of its own.
 */
class OpenThreadActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
        finish()
    }

    private fun handle(incoming: Intent?) {
        try {
            incoming?.getStringExtra(SmsNotifier.EXTRA_THREAD)?.let { SmsPrefs.setPendingOpen(applicationContext, it) }
            packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                // CLEAR_TOP: bring the running app forward (this activity sits above it, so SINGLE_TOP alone made a second
                // MainActivity, which started PHP again under the running queue worker and crashed it: SIGSEGV in
                // native_worker_artisan). The app reads what was asked on its return to the front (pending.ts).
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(launch)
            }
        } catch (e: Exception) {
            Log.e("NativephpSms.Open", "failed to open the conversation", e)
        }
    }
}
