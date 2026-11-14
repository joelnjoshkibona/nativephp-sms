package com.blutrixx.plugins.nativephp_sms

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventJournalTest : DbTestBase() {
    private fun types(events: org.json.JSONArray) = (0 until events.length()).map { events.getJSONObject(it).getString("type") }

    @Test fun readingRemovesNothingAndAReaderResumesAfterWhatItAcknowledged() {
        listOf("a", "b", "c").forEach { EventJournal.append(context, it, JSONObject().put("n", it)) }
        val first = EventJournal.read(context, 0, 2)
        assertEquals(listOf("a", "b"), types(first))
        assertEquals(listOf("a", "b"), types(EventJournal.read(context, 0, 2))) // read again: still there

        EventJournal.ack(context, "app", first.getJSONObject(1).getLong("id"))
        SmsDb.closeForTests() // the process dies and comes back
        assertEquals(listOf("c"), types(EventJournal.read(context, EventJournal.acked(context, "app"))))
    }

    @Test fun anAcknowledgementNeverMovesBackwards() {
        EventJournal.ack(context, "app", 10)
        EventJournal.ack(context, "app", 4)
        assertEquals(10L, EventJournal.acked(context, "app"))
    }

    @Test fun onlyEventsEveryReaderKeptArePrunedAndOnlyOnceTheyAreOld() {
        val now = 2_000_000_000_000L
        val old = now - EventJournal.KEEP_MS - 1
        listOf("old1", "old2").forEach { EventJournal.append(context, it, at = old) }
        EventJournal.append(context, "new", at = now)
        EventJournal.ack(context, "drain", 1, now) // drain kept only the first
        EventJournal.ack(context, "app", 3, now)
        assertEquals(listOf("old2", "new"), types(EventJournal.read(context, 0)))
    }

    @Test fun drainReturnsEachEventOnce() {
        EventJournal.append(context, "boot")
        assertEquals(listOf("boot"), types(EventJournal.drain(context)))
        assertEquals(0, EventJournal.drain(context).length())
        EventJournal.append(context, "sms_received")
        assertEquals(listOf("sms_received"), types(EventJournal.drain(context)))
    }

    @Test fun theOldJournalFileIsCopiedInOnceThenDeleted() {
        val legacy = File(context.filesDir, "sms_events.jsonl")
        legacy.writeText("""{"type":"boot","at":5,"data":{"reason":"boot_completed"}}""" + "\nnot json\n" + """{"type":"sms_received","at":6,"data":{}}""" + "\n")
        EventJournal.append(context, "after")
        val events = EventJournal.read(context, 0)
        assertEquals(listOf("boot", "sms_received", "after"), types(events))
        assertEquals("boot_completed", events.getJSONObject(0).getJSONObject("data").getString("reason"))
        assertFalse(legacy.exists())
    }
}
