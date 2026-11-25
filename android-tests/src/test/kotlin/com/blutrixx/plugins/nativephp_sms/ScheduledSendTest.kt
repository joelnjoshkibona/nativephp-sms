package com.blutrixx.plugins.nativephp_sms

import android.app.AlarmManager
import android.content.Context
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Milestone 1.4: a scheduled text is armed when saved, goes out once when due, and survives a restart. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduledSendTest : DbTestBase() {
    private val to = "+255700000042"
    private val alarms get() = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    private fun one(sql: String, vararg args: String): String? =
        SmsDb.get(context).rawQuery(sql, args).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    private fun journal(): List<String> {
        val a: JSONArray = EventJournal.read(context, 0, 500)
        return (0 until a.length()).map { a.getJSONObject(it).getString("type") }
    }

    private fun schedule(inMs: Long, body: String = "later"): String =
        StoreWrites.saveScheduled(context, null, null, to, body, System.currentTimeMillis() + inMs, null, null)

    @Test fun savingArmsAnAlarmAndCancellingOrDeletingDisarmsIt() {
        val at = System.currentTimeMillis() + 3_600_000
        val id = StoreWrites.saveScheduled(context, null, null, to, "later", at, null, null)
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(at, alarms.scheduledAlarms[0].triggerAtTime)
        assertTrue("scheduled_armed" in journal())

        StoreWrites.saveScheduled(context, id, null, null, null, null, null, "cancelled")
        assertEquals(0, alarms.scheduledAlarms.size)

        val other = schedule(7_200_000)
        assertEquals(1, alarms.scheduledAlarms.size)
        StoreWrites.deleteScheduled(context, other)
        assertEquals(0, alarms.scheduledAlarms.size)
    }

    @Test fun aDueTextGoesOutOnceWithItsIdAsTheSendsReference() {
        val id = schedule(3_600_000)
        val due = System.currentTimeMillis() + 3_600_000
        assertEquals("sent", ScheduledSender.sendNow(context, id, onlyIfDue = true, now = due))
        assertEquals(listOf(to), TestRadio.sent.map { it.first })
        assertEquals("sent", one("SELECT status FROM scheduled WHERE id = ?", id))
        assertEquals(id, one("SELECT m.client_ref FROM scheduled s JOIN messages m ON m.id = s.message_id WHERE s.id = ?", id))
        // a second alarm (or Send now) for the same text finds nothing to send
        assertEquals("skipped", ScheduledSender.sendNow(context, id, onlyIfDue = true, now = due))
        assertEquals(1, TestRadio.sent.size)
        assertTrue("scheduled_sent" in journal())
    }

    @Test fun anEarlyAlarmSendsNothingAndArmsAgain() {
        val id = schedule(3_600_000)
        alarms.scheduledAlarms.clear()
        assertEquals("skipped", ScheduledSender.sendNow(context, id, onlyIfDue = true))
        assertEquals(0, TestRadio.sent.size)
        assertEquals("waiting", one("SELECT status FROM scheduled WHERE id = ?", id))
        assertEquals(1, alarms.scheduledAlarms.size)
    }

    @Test fun sendNowSendsAWaitingTextBeforeItsTime() {
        val id = schedule(3_600_000)
        assertEquals("sent", ScheduledSender.sendNow(context, id))
        assertEquals(1, TestRadio.sent.size)
    }

    @Test fun aRestartArmsTheWaitingSendsTheRecentlyMissedAndFailsTheOld() {
        val now = System.currentTimeMillis()
        val ahead = schedule(3_600_000, "ahead")
        val recent = schedule(60_000, "recent")
        val old = schedule(120_000, "old")
        alarms.scheduledAlarms.clear() // Android forgot them
        // the phone comes back two hours later: "recent" is 2 h late, "old" is moved back to look 2 days late
        SmsDb.write(context) { it.execSQL("UPDATE scheduled SET send_at = ? WHERE id = ?", arrayOf(now - 2 * 86_400_000L, old)) }
        val later = now + 2 * 3_600_000L - 60_000
        val summary = ScheduledSender.restoreAll(context, now = later)
        assertEquals(0, summary.getInt("armed")) // "ahead" was due within the two hours too
        val s2 = listOf(ahead, recent, old).map { one("SELECT status FROM scheduled WHERE id = ?", it) }
        assertEquals(listOf("sent", "sent", "failed"), s2)
        assertEquals(2, TestRadio.sent.size)
        assertEquals(2, summary.getInt("late"))
        assertEquals(1, summary.getInt("missed"))
    }

    @Test fun aRestartBeforeTheTimeOnlyArmsAgain() {
        val id = schedule(3_600_000)
        alarms.scheduledAlarms.clear()
        val summary = ScheduledSender.restoreAll(context)
        assertEquals(1, summary.getInt("armed"))
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(0, TestRadio.sent.size)
        assertEquals("waiting", one("SELECT status FROM scheduled WHERE id = ?", id))
    }

    @Test fun theJournalHoldsNoWordsAndNoNumber() {
        val id = schedule(3_600_000, "secret words")
        ScheduledSender.sendNow(context, id)
        // the scheduled lines (the send's own send_queued line keeps its number in sms.sqlite; the app masks it in its log)
        val a = EventJournal.read(context, 0, 500)
        val mine = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.getString("type").startsWith("scheduled_") }.joinToString()
        assertTrue(mine.isNotEmpty())
        assertTrue(!mine.contains("secret words") && !mine.contains("700000042"))
    }
}
