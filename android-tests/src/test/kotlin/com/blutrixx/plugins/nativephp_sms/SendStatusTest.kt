package com.blutrixx.plugins.nativephp_sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SendStatusTest : DbTestBase() {
    private fun queued(parts: Int, ref: String = "ref-1"): String = SmsDb.write(context) { db ->
        MessageStore.insertOutgoing(db, MessageStore.Outgoing("+255700000001", "x".repeat(parts * 153), 1L, 1, 0, parts, ref, null, null)).messageId
    }

    private fun part(id: String, event: String, ok: Boolean = true) = SmsDb.write(context) { MessageStore.countPart(it, id, event, ok) }

    private fun status(id: String) = SmsDb.get(context).rawQuery("SELECT status FROM messages WHERE id = ?", arrayOf(id)).use { it.moveToFirst(); it.getString(0) }

    @Test fun aThreePartTextHasOneStatusChangePerStepNotPerPart() {
        val id = queued(3)
        assertEquals("queued", status(id))
        val changes = listOf(
            part(id, "sent"), part(id, "sent"), part(id, "sent"),
            part(id, "delivered"), part(id, "delivered"), part(id, "delivered"),
        ).mapNotNull { it?.let { c -> "${c.from}>${c.to}" } }
        assertEquals(listOf("queued>sent", "sent>delivered"), changes)
        assertEquals("delivered", status(id))
    }

    @Test fun oneFailedPartFailsTheMessageAndLaterOkPartsNeverUndoIt() {
        val id = queued(3)
        assertNull(part(id, "sent"))
        assertEquals("queued>failed", part(id, "sent", ok = false)?.let { "${it.from}>${it.to}" })
        assertNull(part(id, "sent"))
        assertNull(part(id, "delivered"))
        assertEquals("failed", status(id))
    }

    @Test fun aFailedDeliveryReportFailsADeliveredLookingMessage() {
        val id = queued(1)
        part(id, "sent")
        assertEquals("sent>failed", part(id, "delivered", ok = false)?.let { "${it.from}>${it.to}" })
    }

    @Test fun aRetriedSendWithTheSameClientRefIsTheSameMessage() {
        val a = queued(1, "same")
        val b = queued(1, "same")
        assertEquals(a, b)
        assertEquals(1L, count("SELECT COUNT(*) FROM messages"))
    }

    private fun times(id: String) = SmsDb.get(context).rawQuery("SELECT sent_at, delivered_at FROM messages WHERE id = ?", arrayOf(id)).use {
        it.moveToFirst(); listOf(if (it.isNull(0)) null else it.getLong(0), if (it.isNull(1)) null else it.getLong(1))
    }

    @Test fun theMomentsItWasSentAndDeliveredAreKept() {
        val id = queued(2)
        SmsDb.write(context) { MessageStore.countPart(it, id, "sent", true, now = 1_000) }
        assertEquals(listOf(null, null), times(id)) // one part of two: not sent yet
        SmsDb.write(context) { MessageStore.countPart(it, id, "sent", true, now = 1_500) }
        SmsDb.write(context) { MessageStore.countPart(it, id, "delivered", true, now = 4_000) }
        SmsDb.write(context) { MessageStore.countPart(it, id, "delivered", true, now = 4_200) }
        assertEquals(listOf(1_500L, 4_200L), times(id))
    }

    @Test fun aReceivedTextKeepsWhenTheOtherPhoneSentIt() {
        val r = ReceivePipeline.receive(context, ReceivePipeline.Delivered("+255700000001", "hi", receivedAt = 9_000, smscTs = 8_400, subId = 1, simSlot = 0, parts = 1))
        assertEquals(8_400L, count("SELECT smsc_at FROM messages WHERE id = ?", r.messageId!!))
    }
}
