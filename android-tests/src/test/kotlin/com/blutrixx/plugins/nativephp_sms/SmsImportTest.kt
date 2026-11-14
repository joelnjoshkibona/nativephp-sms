package com.blutrixx.plugins.nativephp_sms

import android.provider.Telephony
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsImportTest : DbTestBase() {
    /** A system store in memory; [failAfter] pages makes the next page throw, as a killed process would stop. */
    private class FakeSource(val rows: MutableList<SmsImport.SystemRow>) : SmsImport.Source {
        var failAfter = Int.MAX_VALUE
        var pages = 0
        override fun count() = rows.size
        override fun olderThan(date: Long, id: Long, limit: Int): List<SmsImport.SystemRow> {
            if (pages++ >= failAfter) throw IllegalStateException("killed")
            return rows.filter { it.date < date || (it.date == date && it.id < id) }
                .sortedWith(compareByDescending<SmsImport.SystemRow> { it.date }.thenByDescending { it.id }).take(limit)
        }
        override fun newerThan(id: Long, limit: Int) = rows.filter { it.id > id }.sortedBy { it.id }.take(limit)
    }

    private lateinit var source: FakeSource

    private fun row(id: Long, from: String, type: Int = Telephony.Sms.MESSAGE_TYPE_INBOX, status: Int = -1, date: Long = 1_700_000_000_000L + id * 1000) =
        SmsImport.SystemRow(id, threadId = 9, address = from, body = "text $id", date = date, type = type, read = id % 2 == 0L, status = status, subId = 1)

    @Before fun history() {
        // 1,203 rows over three numbers, a sent one delivered, a failed one and a draft
        val rows = (1L..1200L).map { row(it, listOf("+255700000001", "0700000002", "MPESA")[(it % 3).toInt()]) }.toMutableList()
        rows += row(1201, "+255700000001", Telephony.Sms.MESSAGE_TYPE_SENT, Telephony.Sms.STATUS_COMPLETE)
        rows += row(1202, "+255700000001", Telephony.Sms.MESSAGE_TYPE_FAILED)
        rows += row(1203, "+255700000001", Telephony.Sms.MESSAGE_TYPE_DRAFT)
        source = FakeSource(rows)
        SmsImport.sourceFor = { source }
    }

    @Test fun everythingButDraftsComesInGroupedByNumberWithStatuses() {
        SmsImport.run(context)
        assertEquals(1202L, count("SELECT COUNT(*) FROM messages"))
        assertEquals(3L, count("SELECT COUNT(*) FROM threads"))
        assertEquals(1L, count("SELECT COUNT(*) FROM messages WHERE system_id = 1201 AND direction = 'out' AND status = 'delivered'"))
        assertEquals(1L, count("SELECT COUNT(*) FROM messages WHERE system_id = 1202 AND status = 'failed'"))
        assertEquals(600L, count("SELECT SUM(unread) FROM threads")) // the odd ids were unread
        val status = SmsImport.status(context)
        assertEquals(listOf("done", 1203), listOf(status["state"], status["done"]))
    }

    @Test fun theNewestComeInFirst() {
        source.failAfter = 1 // stop after the first batch
        runCatching { SmsImport.run(context) }
        // the first batch is rows 1203..704; 1203 is a draft, so 499 came in
        assertEquals(499L, count("SELECT COUNT(*) FROM messages"))
        assertEquals(listOf(704L, 1202L), listOf(count("SELECT MIN(system_id) FROM messages"), count("SELECT MAX(system_id) FROM messages")))
    }

    @Test fun anImportCutShortCarriesOnWithoutCopyingAnythingTwice() {
        source.failAfter = 1
        runCatching { SmsImport.run(context) }
        assertEquals("running", SmsDb.setting(context, "import.state"))
        source.failAfter = Int.MAX_VALUE
        SmsImport.run(context)
        assertEquals(1202L, count("SELECT COUNT(*) FROM messages"))
        assertEquals(1202L, count("SELECT COUNT(DISTINCT system_id) FROM messages"))
        assertEquals("done", SmsImport.status(context)["state"])
    }

    @Test fun startingAnswersWithTheTotalAlreadyCounted() {
        assertEquals(true, SmsImport.start(context))
        assertEquals(1203, SmsImport.status(context)["total"])
        repeat(100) { if (SmsImport.status(context)["running"] == true) Thread.sleep(20) }
        assertEquals("done", SmsImport.status(context)["state"])
    }

    @Test fun aCatchUpCopiesOnlyWhatIsNew() {
        SmsImport.run(context)
        source.rows += row(1300, "+255700000009")
        source.rows += row(1301, "+255700000009")
        assertEquals(2, SmsImport.catchUp(context))
        assertEquals(0, SmsImport.catchUp(context))
        assertEquals(1204L, count("SELECT COUNT(*) FROM messages"))
    }

    @Test fun aThreadShowsItsNewestMessageEvenWhenOlderOnesArriveLater() {
        SmsImport.run(context)
        val newest = SmsDb.get(context).rawQuery("SELECT m.system_id FROM threads t JOIN messages m ON m.id = t.last_message_id WHERE t.participants_key = ?", arrayOf("700000001"))
            .use { it.moveToFirst(); it.getLong(0) }
        assertEquals(1202L, newest)
    }
}
