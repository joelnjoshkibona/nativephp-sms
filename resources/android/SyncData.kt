package com.blutrixx.plugins.nativephp_sms

import android.content.Context
import android.database.Cursor
import org.json.JSONArray
import org.json.JSONObject

/**
 * What a paired browser is sent, read from sms.sqlite (the protocol is in FRONTEND/src/sync/protocol.ts):
 *  - `changes` after a change_log cursor: the current rows of what changed, and the ids of what was deleted;
 *  - from cursor 0 (or a cursor the log can no longer answer): every conversation with its last message, a page at a
 *    time, all carrying the cursor of the first page so nothing that changes meanwhile is missed;
 *  - `page`: a conversation's older messages, newest first.
 * Vault texts are never in sms.sqlite, so never here.
 */
object SyncData {
    const val SNAPSHOT_PAGE = 300
    const val CHANGES_PAGE = 200
    const val MESSAGES_PAGE = 50

    private const val THREAD_COLS = "id, addresses, folder, last_message_at, unread, pinned, muted, color"
    private const val MESSAGE_COLS = "id, thread_id, direction, address, body, ts, status, kind, sim_slot, read"

    fun seq(context: Context): Long =
        SmsDb.get(context).rawQuery("SELECT COALESCE(MAX(seq), 0) FROM change_log", null).use { it.moveToFirst(); it.getLong(0) }

    /** The answer to `pull {after, offset?, seq?}`. */
    fun changes(context: Context, after: Long, offset: Int = 0, snapshotSeq: Long? = null): JSONObject {
        val db = SmsDb.get(context)
        val oldest = db.rawQuery("SELECT MIN(seq) FROM change_log", null).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
        val reset = after > 0 && oldest != null && after < oldest - 1
        if (after == 0L || reset) return snapshot(context, offset, snapshotSeq ?: seq(context), reset)

        val changed = ArrayList<Triple<String, String, Long>>()
        db.rawQuery(
            "SELECT entity, entity_id, MAX(seq) AS s FROM change_log WHERE seq > ? AND entity IN ('thread', 'message') GROUP BY entity, entity_id ORDER BY s LIMIT ${CHANGES_PAGE + 1}",
            arrayOf(after.toString()),
        ).use { c -> while (c.moveToNext()) changed.add(Triple(c.getString(0), c.getString(1), c.getLong(2))) }
        val more = changed.size > CHANGES_PAGE
        val page = changed.take(CHANGES_PAGE)
        val upTo = if (more) page.last().third else seq(context)

        val threadIds = page.filter { it.first == "thread" }.map { it.second }
        val messageIds = page.filter { it.first == "message" }.map { it.second }
        val threads = rows(context, "SELECT $THREAD_COLS FROM threads WHERE id IN (${marks(threadIds)})", threadIds, ::thread)
        val messages = rows(context, "SELECT $MESSAGE_COLS FROM messages WHERE id IN (${marks(messageIds)})", messageIds, ::message)
        val foundThreads = (0 until threads.length()).map { threads.getJSONObject(it).getString("id") }.toSet()
        val foundMessages = (0 until messages.length()).map { messages.getJSONObject(it).getString("id") }.toSet()

        return JSONObject().put("v", 1).put("t", "changes").put("after", after).put("seq", upTo)
            .put("threads", threads).put("messages", messages)
            .put("deleted", JSONObject().put("threads", JSONArray(threadIds - foundThreads)).put("messages", JSONArray(messageIds - foundMessages)))
            .put("names", names(context, threads, messages))
            .put("more", more)
    }

    private fun snapshot(context: Context, offset: Int, seq: Long, reset: Boolean): JSONObject {
        val threads = rows(context, "SELECT $THREAD_COLS FROM threads ORDER BY COALESCE(last_message_at, 0) DESC, id LIMIT ${SNAPSHOT_PAGE + 1} OFFSET $offset", emptyList(), ::thread)
        val more = threads.length() > SNAPSHOT_PAGE
        if (more) threads.remove(SNAPSHOT_PAGE)
        val ids = (0 until threads.length()).map { threads.getJSONObject(it).getString("id") }
        // each conversation's last message, for the list
        val messages = rows(
            context,
            "SELECT $MESSAGE_COLS FROM messages m WHERE m.thread_id IN (${marks(ids)}) AND m.id = (SELECT id FROM messages WHERE thread_id = m.thread_id ORDER BY ts DESC, id DESC LIMIT 1)",
            ids,
            ::message,
        )
        return JSONObject().put("v", 1).put("t", "changes").put("after", 0).put("seq", seq)
            .put("threads", threads).put("messages", messages)
            .put("deleted", JSONObject().put("threads", JSONArray()).put("messages", JSONArray()))
            .put("names", names(context, threads, messages))
            .put("more", more).put("offset", offset + ids.size)
            .apply { if (reset) put("reset", true) }
    }

    /** The answer to `fetch {thread, before?, limit?}`: messages older than `before`, newest first. */
    fun page(context: Context, thread: String, before: Long?, limit: Int = MESSAGES_PAGE): JSONObject {
        val n = limit.coerceIn(1, 200)
        val args = if (before != null) listOf(thread, before.toString()) else listOf(thread)
        val messages = rows(
            context,
            "SELECT $MESSAGE_COLS FROM messages WHERE thread_id = ?${if (before != null) " AND ts < ?" else ""} ORDER BY ts DESC, id DESC LIMIT ${n + 1}",
            args,
            ::message,
        )
        val more = messages.length() > n
        if (more) messages.remove(n)
        return JSONObject().put("v", 1).put("t", "page").put("thread", thread).put("messages", messages).put("more", more)
            .put("names", names(context, JSONArray(), messages))
    }

    // -- rows ----------------------------------------------------------------------------------------------------------

    private fun thread(c: Cursor) = JSONObject()
        .put("id", c.getString(0)).put("addresses", JSONArray(c.getString(1))).put("folder", c.getString(2))
        .put("last_message_at", if (c.isNull(3)) JSONObject.NULL else c.getLong(3)).put("unread", c.getInt(4))
        .put("pinned", c.getInt(5) == 1).put("muted", c.getInt(6) == 1).put("color", if (c.isNull(7)) JSONObject.NULL else c.getString(7))

    private fun message(c: Cursor) = JSONObject()
        .put("id", c.getString(0)).put("thread_id", c.getString(1)).put("direction", c.getString(2)).put("address", c.getString(3))
        .put("body", c.getString(4)).put("ts", c.getLong(5)).put("status", c.getString(6)).put("kind", c.getString(7))
        .put("sim_slot", if (c.isNull(8)) JSONObject.NULL else c.getInt(8)).put("read", c.getInt(9) == 1)

    private fun rows(context: Context, sql: String, args: List<String>, map: (Cursor) -> JSONObject): JSONArray {
        if (sql.contains("IN ()")) return JSONArray()
        return SmsDb.get(context).rawQuery(sql, args.toTypedArray()).use { c -> JSONArray().apply { while (c.moveToNext()) put(map(c)) } }
    }

    private fun marks(ids: List<String>) = ids.joinToString(",") { "?" }

    private fun names(context: Context, threads: JSONArray, messages: JSONArray): JSONObject {
        val addresses = LinkedHashSet<String>()
        for (i in 0 until threads.length()) threads.getJSONObject(i).getJSONArray("addresses").let { a -> for (j in 0 until a.length()) addresses.add(a.getString(j)) }
        for (i in 0 until messages.length()) addresses.add(messages.getJSONObject(i).getString("address"))
        val out = JSONObject()
        addresses.forEach { a -> ContactSync.nameFor(context, a)?.let { out.put(a, it) } }
        return out
    }
}
