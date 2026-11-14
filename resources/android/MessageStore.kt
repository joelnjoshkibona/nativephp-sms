package com.blutrixx.plugins.nativephp_sms

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray

/**
 * Threads and messages in sms.sqlite. Every write here runs inside SmsDb.write; change_log follows by trigger.
 * A thread is one set of participants (participants_key: their comparison keys, sorted), so "+255712345678" and
 * "0712345678" land in the same conversation.
 */
object MessageStore {
    data class Incoming(
        val address: String,
        val body: String,
        val ts: Long,
        val subId: Int?,
        val simSlot: Int?,
        val systemId: Long?,
        val systemThreadId: Long?,
        val folder: String = "inbox",
        val read: Boolean = false,
        /** When the other phone's network sent it (the SMSC timestamp); null when unknown. */
        val smscAt: Long? = null,
    )

    data class Outgoing(
        val address: String,
        val body: String,
        val ts: Long,
        val subId: Int?,
        val simSlot: Int?,
        val partsTotal: Int,
        val clientRef: String,
        val systemId: Long?,
        val systemThreadId: Long?,
    )

    data class Saved(val messageId: String, val threadId: String, val created: Boolean)

    fun participantsKey(addresses: Collection<String>): String =
        addresses.map { SmsPrefs.normalize(it).ifEmpty { it.trim().lowercase() } }.toSortedSet().joinToString(",")

    /** The thread for these participants, made if it does not exist. A spam message moves its thread to Spam. */
    fun threadFor(db: SQLiteDatabase, addresses: List<String>, systemThreadId: Long?, folder: String, now: Long): String {
        val key = participantsKey(addresses)
        db.rawQuery("SELECT id, folder, system_thread_id FROM threads WHERE participants_key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) {
                val id = c.getString(0)
                val values = ContentValues()
                if (folder == "spam" && c.getString(1) != "spam") values.put("folder", "spam")
                if (systemThreadId != null && c.isNull(2)) values.put("system_thread_id", systemThreadId)
                if (values.size() > 0) {
                    values.put("updated_at", now)
                    db.update("threads", values, "id = ?", arrayOf(id))
                }
                return id
            }
        }
        val id = Ulid.next(now)
        db.insertOrThrow("threads", null, ContentValues().apply {
            put("id", id)
            put("participants_key", key)
            put("addresses", JSONArray(addresses).toString())
            if (systemThreadId != null) put("system_thread_id", systemThreadId)
            put("folder", folder)
            put("created_at", now)
            put("updated_at", now)
        })
        return id
    }

    fun insertIncoming(db: SQLiteDatabase, m: Incoming, now: Long = System.currentTimeMillis()): Saved {
        existingBySystemId(db, m.systemId)?.let { return it }
        val threadId = threadFor(db, listOf(m.address), m.systemThreadId, m.folder, now)
        val id = Ulid.next(now)
        db.insertOrThrow("messages", null, ContentValues().apply {
            put("id", id)
            put("thread_id", threadId)
            if (m.systemId != null) put("system_id", m.systemId)
            put("direction", "in")
            put("address", m.address)
            put("body", m.body)
            put("ts", m.ts)
            put("status", "received")
            if (m.subId != null && m.subId >= 0) put("sub_id", m.subId)
            if (m.simSlot != null) put("sim_slot", m.simSlot)
            put("read", if (m.read) 1 else 0)
            if (m.smscAt != null && m.smscAt > 0) put("smsc_at", m.smscAt)
            put("created_at", now)
            put("updated_at", now)
        })
        touchThread(db, threadId, id, m.ts, unreadDelta = if (m.read) 0 else 1, now = now)
        return Saved(id, threadId, true)
    }

    /** A send's row, written before the radio is asked; an existing client_ref answers with its row (a retried call). */
    fun insertOutgoing(db: SQLiteDatabase, m: Outgoing, now: Long = System.currentTimeMillis()): Saved {
        db.rawQuery("SELECT id, thread_id FROM messages WHERE client_ref = ?", arrayOf(m.clientRef)).use { c ->
            if (c.moveToFirst()) return Saved(c.getString(0), c.getString(1), false)
        }
        val threadId = threadFor(db, listOf(m.address), m.systemThreadId, "inbox", now)
        val id = Ulid.next(now)
        db.insertOrThrow("messages", null, ContentValues().apply {
            put("id", id)
            put("thread_id", threadId)
            if (m.systemId != null) put("system_id", m.systemId)
            put("direction", "out")
            put("address", m.address)
            put("body", m.body)
            put("ts", m.ts)
            put("status", "queued")
            if (m.subId != null && m.subId >= 0) put("sub_id", m.subId)
            if (m.simSlot != null) put("sim_slot", m.simSlot)
            put("parts_total", m.partsTotal.coerceAtLeast(1))
            put("client_ref", m.clientRef)
            put("read", 1)
            put("created_at", now)
            put("updated_at", now)
        })
        touchThread(db, threadId, id, m.ts, unreadDelta = 0, now = now)
        return Saved(id, threadId, true)
    }

    /** A sent text from the system store's history (the import), with the status it ended in. */
    fun insertImportedOutgoing(
        db: SQLiteDatabase, address: String, body: String, ts: Long, subId: Int?, systemId: Long, systemThreadId: Long?, status: String,
        now: Long = System.currentTimeMillis(),
    ): Saved {
        existingBySystemId(db, systemId)?.let { return it }
        val threadId = threadFor(db, listOf(address), systemThreadId, "inbox", now)
        val id = Ulid.next(now)
        db.insertOrThrow("messages", null, ContentValues().apply {
            put("id", id)
            put("thread_id", threadId)
            put("system_id", systemId)
            put("direction", "out")
            put("address", address)
            put("body", body)
            put("ts", ts)
            put("status", status)
            if (status == "sent" || status == "delivered") put("sent_at", ts)
            if (subId != null && subId >= 0) put("sub_id", subId)
            put("parts_total", 1)
            put("read", 1)
            put("created_at", now)
            put("updated_at", now)
        })
        touchThread(db, threadId, id, ts, unreadDelta = 0, now = now)
        return Saved(id, threadId, true)
    }

    private fun existingBySystemId(db: SQLiteDatabase, systemId: Long?): Saved? {
        if (systemId == null) return null
        return db.rawQuery("SELECT id, thread_id FROM messages WHERE system_id = ?", arrayOf(systemId.toString())).use { c ->
            if (c.moveToFirst()) Saved(c.getString(0), c.getString(1), false) else null
        }
    }

    /** The thread's newest message moves only forward, so an imported old message never hides a newer one. */
    private fun touchThread(db: SQLiteDatabase, threadId: String, messageId: String, ts: Long, unreadDelta: Int, now: Long) {
        db.execSQL(
            "UPDATE threads SET " +
                "last_message_id = CASE WHEN last_message_at IS NULL OR ? >= last_message_at THEN ? ELSE last_message_id END, " +
                "last_message_at = MAX(COALESCE(last_message_at, 0), ?), unread = unread + ?, updated_at = ? WHERE id = ?",
            arrayOf<Any>(ts, messageId, ts, unreadDelta, now, threadId),
        )
    }

    // -- one status per message, from the per-part callbacks ------------------------------------------------------------

    data class StatusChange(val messageId: String, val clientRef: String?, val systemId: Long?, val from: String, val to: String, val partsTotal: Int)

    /**
     * Counts one part's result on its message and works out the message's status:
     * failed as soon as a part fails; sent once every part is sent; delivered once every part is delivered.
     * Answers the change when the message's status moved, else null (so the journal gets one line per change, not per part).
     */
    fun countPart(db: SQLiteDatabase, messageId: String, event: String, ok: Boolean, now: Long = System.currentTimeMillis()): StatusChange? {
        var status: String; var total: Int; var sent: Int; var failed: Int; var delivered: Int; val clientRef: String?; val systemId: Long?
        db.rawQuery(
            "SELECT status, parts_total, parts_sent, parts_failed, parts_delivered, client_ref, system_id FROM messages WHERE id = ?",
            arrayOf(messageId),
        ).use { c ->
            if (!c.moveToFirst()) return null
            status = c.getString(0); total = c.getInt(1); sent = c.getInt(2); failed = c.getInt(3); delivered = c.getInt(4)
            clientRef = if (c.isNull(5)) null else c.getString(5)
            systemId = if (c.isNull(6)) null else c.getLong(6)
        }
        when {
            event == "sent" && ok -> sent += 1
            event == "sent" -> failed += 1
            event == "delivered" && ok -> delivered += 1
            else -> failed += 1 // a delivery report that says it failed
        }
        val next = when {
            failed > 0 -> "failed"
            delivered >= total -> "delivered"
            sent >= total && status != "delivered" -> "sent"
            else -> status
        }
        db.update("messages", ContentValues().apply {
            put("parts_sent", sent.coerceAtMost(total)); put("parts_failed", failed.coerceAtMost(total)); put("parts_delivered", delivered.coerceAtMost(total))
            put("status", next)
            if (next != status) {
                put("updated_at", now)
                // the moment it became sent, or delivered (a delivery with no sent callback before it is also when it went)
                if (next == "sent") put("sent_at", now)
                if (next == "delivered") put("delivered_at", now)
            }
        }, "id = ?", arrayOf(messageId))
        if (next == "delivered" && next != status) db.execSQL("UPDATE messages SET sent_at = COALESCE(sent_at, ?) WHERE id = ?", arrayOf<Any>(now, messageId))
        return if (next != status) StatusChange(messageId, clientRef, systemId, status, next, total) else null
    }
}
