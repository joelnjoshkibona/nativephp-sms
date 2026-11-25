package com.blutrixx.plugins.nativephp_sms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Scheduled texts go out on time (milestone 1.4), with the app closed and after a restart.
 *
 * Each waiting text has one Android alarm (exact when the phone allows it, else as close as Android lets it). When it
 * fires, [ScheduledReceiver] calls [sendNow]: the row moves from waiting to sending in one write, so a second alarm or a
 * "Send now" at the same moment finds nothing to send; the text goes through [SmsSender] with the scheduled id as its
 * client_ref (a send with a used client_ref sends nothing), then the row is sent (with its message_id) or failed.
 * Android forgets alarms on a restart or an update: [restoreAll] arms the waiting ones again and sends at once the ones
 * whose time passed meanwhile, if it passed less than a day ago; an older one is failed ("missed"), never sent days late.
 * The journal holds ids, counts and times only: never words or numbers.
 */
object ScheduledSender {
    const val ACTION_DUE = "com.blutrixx.plugins.nativephp_sms.SCHEDULED_DUE"
    const val LATE_WINDOW_MS = 24 * 60 * 60_000L
    /** An alarm that comes this early (another app's broadcast, a clock change) sends nothing and is armed again. */
    private const val EARLY_MS = 60_000L
    private const val TAG = "NativephpSms.Scheduled"

    private fun manager(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Android 12+ lets the person switch exact alarms off ("Alarms & reminders"); Android 14+ starts with it off. */
    fun canExact(ctx: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager(ctx).canScheduleExactAlarms()

    internal fun pending(ctx: Context, id: String): PendingIntent {
        val intent = Intent(ctx, ScheduledReceiver::class.java)
            .setAction(ACTION_DUE)
            .setData(Uri.parse("sms-scheduled://due/${Uri.encode(id)}"))
            .putExtra("id", id)
        return PendingIntent.getBroadcast(ctx, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun arm(ctx: Context, id: String, at: Long) {
        val pi = pending(ctx, id)
        val am = manager(ctx)
        try {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            Log.w(TAG, "exact alarm refused, using an inexact one: ${e.message}")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun disarm(ctx: Context, id: String) {
        val pi = pending(ctx, id)
        manager(ctx).cancel(pi)
        pi.cancel()
    }

    /** After a save: a waiting text is armed for its time, any other (cancelled, gone) disarmed. */
    fun sync(ctx: Context, id: String) {
        val due = SmsDb.get(ctx).rawQuery("SELECT send_at FROM scheduled WHERE id = ? AND status = 'waiting'", arrayOf(id))
            .use { if (it.moveToFirst()) it.getLong(0) else null }
        if (due == null) disarm(ctx, id) else arm(ctx, id, due)
        if (due != null) EventJournal.append(ctx, "scheduled_armed", JSONObject().put("id", id).put("send_at", due).put("exact", canExact(ctx)))
    }

    private class Due(val threadId: String, val body: String, val subId: Int?, val addresses: List<String>, val sendAt: Long)

    /**
     * Sends one waiting text now: "sent", "failed", or "skipped" (not waiting any more: sent, cancelled, or already
     * being sent). [onlyIfDue] (an alarm) refuses one whose time is more than a minute ahead and arms it again.
     */
    fun sendNow(ctx: Context, id: String, onlyIfDue: Boolean = false, late: Boolean = false, now: Long = System.currentTimeMillis()): String {
        val app = ctx.applicationContext
        val due = SmsDb.write(app) { db ->
            val row = db.rawQuery(
                "SELECT s.thread_id, s.body, s.sub_id, t.addresses, s.send_at FROM scheduled s JOIN threads t ON t.id = s.thread_id WHERE s.id = ? AND s.status = 'waiting'",
                arrayOf(id),
            ).use { c ->
                if (!c.moveToFirst()) null
                else Due(c.getString(0), c.getString(1), if (c.isNull(2)) null else c.getInt(2), addresses(c.getString(3)), c.getLong(4))
            } ?: return@write null
            if (onlyIfDue && row.sendAt - now > EARLY_MS) {
                arm(app, id, row.sendAt)
                return@write null
            }
            db.update("scheduled", ContentValues().apply { put("status", "sending"); put("updated_at", now) }, "id = ? AND status = 'waiting'", arrayOf(id))
            row
        } ?: return "skipped"

        var messageId: String? = null
        var error: String? = null
        if (due.addresses.isEmpty()) error = "no number"
        val sim = due.subId ?: SmsSender.lastSubscription(app, due.threadId)
        due.addresses.forEachIndexed { i, to ->
            if (error != null && i > 0) return@forEachIndexed
            try {
                val r = SmsSender.send(app, to, due.body, if (i == 0) id else "$id-$i", sim)
                if (i == 0) messageId = r.messageId
            } catch (e: Exception) {
                Log.w(TAG, "scheduled send failed", e)
                error = (e as? SecurityException)?.let { "SEND_SMS is not granted" } ?: (e.message ?: e.javaClass.simpleName)
            }
        }
        val status = if (error == null) "sent" else "failed"
        SmsDb.write(app) { db ->
            db.update("scheduled", ContentValues().apply {
                put("status", status); put("updated_at", System.currentTimeMillis())
                messageId?.let { put("message_id", it) }
            }, "id = ?", arrayOf(id))
        }
        val line = JSONObject().put("id", id).put("send_at", due.sendAt).put("late", late)
        if (error == null) EventJournal.append(app, "scheduled_sent", line.put("parts_to", due.addresses.size))
        else EventJournal.append(app, "scheduled_failed", line.put("error", error))
        return status
    }

    /** After a restart or an update (and when exact alarms are allowed again): arm the waiting texts, send the overdue. */
    fun restoreAll(ctx: Context, now: Long = System.currentTimeMillis()): JSONObject {
        val app = ctx.applicationContext
        val waiting = SmsDb.get(app).rawQuery("SELECT id, send_at FROM scheduled WHERE status = 'waiting' ORDER BY send_at", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getLong(1)) } }
        var armed = 0; var late = 0; var missed = 0
        for ((id, at) in waiting) {
            when {
                at > now -> { arm(app, id, at); armed++ }
                now - at <= LATE_WINDOW_MS -> { if (sendNow(app, id, late = true, now = now) != "skipped") late++ }
                else -> {
                    SmsDb.write(app) { db ->
                        db.update("scheduled", ContentValues().apply { put("status", "failed"); put("updated_at", now) }, "id = ? AND status = 'waiting'", arrayOf(id))
                    }
                    EventJournal.append(app, "scheduled_failed", JSONObject().put("id", id).put("send_at", at).put("error", "missed: the phone was off for more than a day"))
                    missed++
                }
            }
        }
        val summary = JSONObject().put("armed", armed).put("late", late).put("missed", missed).put("exact", canExact(app))
        if (waiting.isNotEmpty()) EventJournal.append(app, "scheduled_restored", summary)
        return summary
    }

    private fun addresses(json: String?): List<String> = try {
        val a = JSONArray(json ?: "[]")
        (0 until a.length()).map { a.getString(it).trim() }.filter { it.isNotEmpty() }
    } catch (e: Exception) {
        emptyList()
    }
}
