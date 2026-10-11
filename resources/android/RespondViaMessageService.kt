package com.blutrixx.plugins.nativephp_sms

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.util.Log

/**
 * Required by the SMS role: "decline a call with a message" (android.intent.action.RESPOND_VIA_MESSAGE).
 * Only the system can bind to it (SEND_RESPOND_VIA_MESSAGE permission in the manifest).
 */
class RespondViaMessageService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val data: Uri? = intent?.data
        if (intent?.action != "android.intent.action.RESPOND_VIA_MESSAGE" || text.isNullOrBlank() || data == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val recipients = Uri.decode(data.schemeSpecificPart.substringBefore('?'))
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val appContext = applicationContext

        Background.executor.execute {
            try {
                recipients.forEach { to ->
                    SmsSender.send(appContext, to, text, java.util.UUID.randomUUID().toString(), -1)
                }
            } catch (e: Throwable) {
                Log.e("NativephpSms.Respond", "respond-via-message send failed", e)
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }
}
