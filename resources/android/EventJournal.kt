package com.blutrixx.plugins.nativephp_sms

import android.content.ContentValues
import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The journal written by the headless receivers: every receive, send, status and restart step, timestamped whether or not
 * PHP could be reached. A receiver has no Activity, so it cannot raise a NativePHP event into the WebView; the app reads
 * the journal instead.
 *
 * Append-only rows in sms.sqlite (`events`). A reader names itself and acknowledges what it has kept (Sms.ReadEvents, then
 * Sms.AckEvents), so a reader that dies between the two reads the same events again instead of losing them. Rows every
 * reader has acknowledged are pruned after [KEEP_MS].
 */
object EventJournal {
    private const val TAG = "NativephpSms.Journal"
    private const val LEGACY_FILE = "sms_events.jsonl"
    private const val ACK_PREFIX = "journal.ack."
    internal const val KEEP_MS = 14L * 24 * 60 * 60 * 1000
    @Volatile private var legacyChecked = false

    fun append(context: Context, type: String, data: JSONObject = JSONObject(), at: Long = System.currentTimeMillis()) {
        try {
            importLegacy(context)
            SmsDb.get(context).insert("events", null, ContentValues().apply {
                put("type", type)
                put("at", at)
                put("data", data.toString())
            })
        } catch (e: Exception) {
            Log.w(TAG, "append failed for $type", e)
        }
    }

    /** Events after [after] (an event id), oldest first, at most [limit]. */
    fun read(context: Context, after: Long, limit: Int = 200): JSONArray {
        importLegacy(context)
        val out = JSONArray()
        SmsDb.get(context).rawQuery(
            "SELECT id, type, at, data FROM events WHERE id > ? ORDER BY id LIMIT ?",
            arrayOf(after.toString(), limit.coerceIn(1, 1000).toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val data = try { JSONObject(c.getString(3)) } catch (e: Exception) { JSONObject() }
                out.put(JSONObject().put("id", c.getLong(0)).put("type", c.getString(1)).put("at", c.getLong(2)).put("data", data))
            }
        }
        return out
    }

    fun acked(context: Context, reader: String): Long = SmsDb.setting(context, ACK_PREFIX + reader)?.toLongOrNull() ?: 0L

    /** Marks everything up to [upTo] as kept by [reader] (never moves backwards), then prunes what every reader has kept. */
    fun ack(context: Context, reader: String, upTo: Long, now: Long = System.currentTimeMillis()) {
        SmsDb.write(context) { db ->
            val key = ACK_PREFIX + reader
            val current = db.rawQuery("SELECT value FROM settings WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0).toLongOrNull() ?: 0L else 0L }
            if (upTo > current) SmsDb.setSetting(db, key, upTo.toString())
            val keptByAll = db.rawQuery("SELECT MIN(CAST(value AS INTEGER)) FROM settings WHERE key LIKE ?", arrayOf("$ACK_PREFIX%")).use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
            }
            db.delete("events", "id <= ? AND at < ?", arrayOf(keptByAll.toString(), (now - KEEP_MS).toString()))
        }
    }

    /** The old drain: every event the "drain" reader has not had yet, acknowledged as it is returned. */
    fun drain(context: Context): JSONArray {
        val out = JSONArray()
        try {
            var after = acked(context, "drain")
            while (true) {
                val page = read(context, after, 1000)
                for (i in 0 until page.length()) out.put(page.getJSONObject(i))
                if (page.length() < 1000) break
                after = page.getJSONObject(page.length() - 1).getLong("id")
            }
            if (out.length() > 0) ack(context, "drain", out.getJSONObject(out.length() - 1).getLong("id"))
        } catch (e: Exception) {
            Log.w(TAG, "drain failed", e)
        }
        return out
    }

    /** Before sms.sqlite the journal was a JSON-lines file: its undrained lines are copied in once, then it is deleted. */
    private fun importLegacy(context: Context) {
        if (legacyChecked) return
        synchronized(this) {
            if (legacyChecked) return
            val file = File(context.applicationContext.filesDir, LEGACY_FILE)
            if (file.exists()) {
                SmsDb.write(context) { db ->
                    file.readLines().filter { it.isNotBlank() }.forEach { line ->
                        try {
                            val e = JSONObject(line)
                            db.insert("events", null, ContentValues().apply {
                                put("type", e.optString("type", "unknown"))
                                put("at", e.optLong("at", System.currentTimeMillis()))
                                put("data", (e.optJSONObject("data") ?: JSONObject()).toString())
                            })
                        } catch (ex: Exception) {
                            Log.w(TAG, "skipping unreadable journal line", ex)
                        }
                    }
                }
                file.delete()
            }
            legacyChecked = true
        }
    }

    internal fun resetForTests() {
        legacyChecked = false
    }
}
