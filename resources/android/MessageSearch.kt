package com.blutrixx.plugins.nativephp_sms

import android.content.Context

/**
 * Search over the words of messages (the FTS4 index, migration 2), newest first. Each word typed must appear, as a word or
 * the start of one ("pick" finds "pickup"), in any order; letters are matched without case. Only sms.sqlite is searched:
 * a vault message is never there.
 */
object MessageSearch {
    data class Hit(val id: String, val threadId: String, val ts: Long)

    /**
     * The words typed, as an FTS4 query: letters and digits only, lower case (FTS4's operators are upper case), each
     * made a prefix, so nothing typed is read as an operator.
     */
    internal fun query(typed: String): String? {
        val words = typed.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.take(8)
        return if (words.isEmpty()) null else words.joinToString(" ") { "$it*" }
    }

    fun search(context: Context, typed: String, limit: Int = 100): List<Hit> {
        val q = query(typed) ?: return emptyList()
        return SmsDb.get(context).rawQuery(
            "SELECT m.id, m.thread_id, m.ts FROM messages_fts f JOIN messages m ON m.rowid = f.docid WHERE messages_fts MATCH ? ORDER BY m.ts DESC LIMIT ?",
            arrayOf(q, limit.coerceIn(1, 500).toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(Hit(c.getString(0), c.getString(1), c.getLong(2))) } }
    }
}
