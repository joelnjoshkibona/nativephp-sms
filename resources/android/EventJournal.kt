package com.blutrixx.plugins.nativephp_sms

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Append-only JSON-lines journal written by the headless receivers. A receiver
 * has no Activity, so it cannot raise a NativePHP event into the WebView; the UI
 * drains this journal instead (Sms.DrainEvents). It is also the spike's evidence
 * trail: every receive/send/status step is timestamped here whether or not PHP
 * could be reached.
 */
object EventJournal {
    private const val TAG = "NativephpSms.Journal"
    private const val FILE = "sms_events.jsonl"
    private const val MAX_BYTES = 512L * 1024L
    private val lock = Any()

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    fun append(context: Context, type: String, data: JSONObject = JSONObject()) {
        synchronized(lock) {
            try {
                val f = file(context)
                if (f.length() > MAX_BYTES) {
                    // keep the newest half rather than dropping undrained events
                    val lines = f.readLines()
                    f.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
                }
                val line = JSONObject()
                    .put("type", type)
                    .put("at", System.currentTimeMillis())
                    .put("data", data)
                f.appendText(line.toString() + "\n")
            } catch (e: Exception) {
                Log.w(TAG, "append failed for $type", e)
            }
        }
    }

    /** Returns every journalled event, oldest first, and empties the journal. */
    fun drain(context: Context): JSONArray {
        synchronized(lock) {
            val out = JSONArray()
            try {
                val f = file(context)
                if (!f.exists()) return out
                f.readLines().forEach { line ->
                    if (line.isNotBlank()) {
                        try {
                            out.put(JSONObject(line))
                        } catch (e: Exception) {
                            Log.w(TAG, "skipping unreadable journal line", e)
                        }
                    }
                }
                f.delete()
            } catch (e: Exception) {
                Log.w(TAG, "drain failed", e)
            }
            return out
        }
    }
}
