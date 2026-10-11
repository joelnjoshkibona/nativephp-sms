package com.blutrixx.plugins.nativephp_sms

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import org.json.JSONObject

/**
 * Required by the SMS role: handles ACTION_SENDTO for sms:/smsto:/mms:/mmsto: links (Contacts,
 * browser, other apps). It is a transparent trampoline: it records who/what to compose in
 * SmsPrefs, journals it, and brings the NativePHP app to the front; the web UI then calls
 * Sms.TakePendingCompose to open the composer. No UI of its own.
 */
class ComposeActivity : Activity() {

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
            val data = incoming?.data
            val parsed = parse(data)
            val to = parsed.first
            val body = incoming?.getStringExtra("sms_body")
                ?: incoming?.getStringExtra(Intent.EXTRA_TEXT)
                ?: parsed.second
            SmsPrefs.setPendingCompose(applicationContext, to, body)
            EventJournal.append(
                applicationContext, "compose_requested",
                JSONObject().put("to", to).put("body_len", body.length).put("action", incoming?.action ?: "")
            )

            packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(launch)
            }
        } catch (e: Exception) {
            Log.e("NativephpSms.Compose", "failed to handle compose intent", e)
        }
    }

    /** sms:+255712345678?body=hi is an opaque URI; getQueryParameter() throws on it, so parse by hand. */
    private fun parse(data: Uri?): Pair<String, String> {
        val ssp = data?.schemeSpecificPart ?: return Pair("", "")
        val number = Uri.decode(ssp.substringBefore('?')).trim()
        val query = ssp.substringAfter('?', "")
        val body = if (query.isEmpty()) "" else (Uri.parse("x://h?$query").getQueryParameter("body") ?: "")
        return Pair(number, body)
    }
}
