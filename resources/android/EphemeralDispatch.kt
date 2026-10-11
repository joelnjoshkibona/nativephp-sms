package com.blutrixx.plugins.nativephp_sms

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.nativephp.mobile.bridge.PHPBridge
import org.json.JSONObject

/**
 * Optional, best-effort hand-off from a headless receiver to PHP.
 *
 * Every nativeEphemeralBoot()/nativeEphemeralArtisan() call must run on the SAME OS
 * thread for the life of the process (nativephp-telephony-gateway found this the
 * hard way: a second thread skips the TSRM binding and the process SIGSEGVs). So all
 * calls go through this one HandlerThread. Do not register this plugin in an app
 * that also registers nativephp-telephony-gateway: two HandlerThreads would both
 * drive the one ephemeral runtime.
 *
 * The native side never waits on PHP for correctness: the system SMS store write
 * and the notification have already happened before dispatch() is called.
 */
object EphemeralDispatch {
    private const val TAG = "NativephpSms.Ephemeral"
    private const val WATCHDOG_MS = 8_000L
    private val handlerThread = HandlerThread("nativephp-sms-ephemeral").apply { start() }
    private val handler = Handler(handlerThread.looper)

    /**
     * Runs `sms:<name> --payload=<base64 json>` in the ephemeral runtime, then calls
     * onDone() on the ephemeral thread (always, success or failure). When ephemeral
     * dispatch is disabled, onDone() runs immediately on the caller's thread.
     */
    fun dispatch(context: Context, command: String, payload: JSONObject, onDone: () -> Unit) {
        if (!SmsPrefs.isEphemeralEnabled(context)) {
            onDone()
            return
        }
        val appContext = context.applicationContext
        // onDone must run exactly once and must not wait on PHP forever: a held broadcast ANRs after
        // ~10s (foreground) / ~60s (background), and a hung runtime would also block every queued job.
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val finishOnce = { if (done.compareAndSet(false, true)) onDone() }
        Handler(Looper.getMainLooper()).postDelayed({
            if (!done.get()) {
                Log.w(TAG, "$command still running after ${WATCHDOG_MS}ms; releasing the broadcast")
                EventJournal.append(appContext, "ephemeral_timeout", JSONObject().put("command", command))
                finishOnce()
            }
        }, WATCHDOG_MS)
        handler.post {
            val startedAt = System.currentTimeMillis()
            try {
                val bridge = PHPBridge(appContext)
                val bootstrapPath = "${bridge.getLaravelPath()}/bootstrap/android/ephemeral.php"
                bridge.nativeEphemeralBoot(bootstrapPath)

                // Base64 has no spaces, quotes or backslashes, so it survives Runtime::artisan()'s
                // str_getcsv() argument splitting (JSON does not -- confirmed by telephony-gateway).
                val encoded = Base64.encodeToString(payload.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val result = bridge.nativeEphemeralArtisan("$command --payload=$encoded")
                val elapsed = System.currentTimeMillis() - startedAt
                Log.i(TAG, "$command ok in ${elapsed}ms: $result")
                EventJournal.append(appContext, "ephemeral_ok", JSONObject().put("command", command).put("ms", elapsed))
            } catch (e: Throwable) {
                Log.e(TAG, "$command failed", e)
                EventJournal.append(
                    appContext,
                    "ephemeral_failed",
                    JSONObject().put("command", command).put("error", e.message ?: e.javaClass.simpleName)
                )
            } finally {
                finishOnce()
            }
        }
    }
}
