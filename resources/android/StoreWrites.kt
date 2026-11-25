package com.blutrixx.plugins.nativephp_sms

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.provider.Telephony
import android.util.Log

/**
 * The writes the app's Laravel asks for (it opens sms.sqlite read-only): threads, messages, templates, scheduled texts
 * and the app's settings. Each validates what it is given and throws [Invalid] with a message the screen can show.
 * While this app is the default SMS app, a deleted thread or message is deleted from the system store too, and a thread
 * marked read is marked read there.
 */
object StoreWrites {
    class Invalid(message: String) : IllegalArgumentException(message)
    class NotFound(message: String) : IllegalArgumentException(message)

    private val FOLDERS = setOf("inbox", "spam", "archive")
    private val SCHEDULED_STATUSES = setOf("waiting", "cancelled")
    private val SETTING_KEY = Regex("^app\\.[a-z0-9_.-]{1,80}$")

    private fun exists(db: SQLiteDatabase, table: String, id: String) =
        db.rawQuery("SELECT 1 FROM $table WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }

    // -- threads ---------------------------------------------------------------------------------------------------------

    /** pinned, muted (booleans), folder (inbox, spam, archive), color, wallpaper (null clears), read (true marks it all read). */
    fun saveThread(context: Context, id: String, changes: Map<String, Any?>, now: Long = System.currentTimeMillis()) {
        var systemThreadId: Long? = null
        SmsDb.write(context) { db ->
            if (!exists(db, "threads", id)) throw NotFound("No such conversation.")
            val values = ContentValues()
            changes["pinned"]?.let { values.put("pinned", if (bool(it, "pinned")) 1 else 0) }
            changes["muted"]?.let { values.put("muted", if (bool(it, "muted")) 1 else 0) }
            changes["folder"]?.let {
                if (it !in FOLDERS) throw Invalid("folder must be inbox, spam or archive.")
                values.put("folder", it as String)
            }
            for (key in listOf("color", "wallpaper")) if (changes.containsKey(key)) {
                val v = changes[key]
                if (v == null || v == "") values.putNull(key) else values.put(key, (v as? String)?.take(200) ?: throw Invalid("$key must be text."))
            }
            if (changes["read"]?.let { bool(it, "read") } == true) {
                values.put("unread", 0)
                db.execSQL("UPDATE messages SET read = 1, updated_at = ? WHERE thread_id = ? AND read = 0", arrayOf<Any>(now, id))
                systemThreadId = db.rawQuery("SELECT system_thread_id FROM threads WHERE id = ?", arrayOf(id)).use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                }
            }
            if (values.size() == 0) return@write
            values.put("updated_at", now)
            db.update("threads", values, "id = ?", arrayOf(id))
        }
        systemThreadId?.let { SmsStore.markThreadRead(context, it) }
        if (changes["read"]?.let { bool(it, "read") } == true || changes["muted"]?.let { bool(it, "muted") } == true) SmsNotifier.cancelThread(context, id)
    }

    fun deleteThread(context: Context, id: String) {
        val systemThreadId = SmsDb.write(context) { db ->
            val sys = db.rawQuery("SELECT system_thread_id FROM threads WHERE id = ?", arrayOf(id)).use { c ->
                if (!c.moveToFirst()) throw NotFound("No such conversation.")
                if (c.isNull(0)) null else c.getLong(0)
            }
            db.delete("threads", "id = ?", arrayOf(id)) // its messages and attachments follow (ON DELETE CASCADE)
            sys
        }
        SmsNotifier.cancelThread(context, id)
        if (systemThreadId != null && SmsStore.isDefaultSmsApp(context)) {
            try {
                context.contentResolver.delete(ContentUris.withAppendedId(Telephony.Threads.CONTENT_URI, systemThreadId), null, null)
            } catch (e: Exception) {
                Log.w("NativephpSms.Writes", "system thread delete failed", e)
            }
        }
    }

    // -- messages --------------------------------------------------------------------------------------------------------

    fun deleteMessage(context: Context, id: String, now: Long = System.currentTimeMillis()) {
        val systemId = SmsDb.write(context) { db ->
            val (threadId, sys) = db.rawQuery("SELECT thread_id, system_id FROM messages WHERE id = ?", arrayOf(id)).use { c ->
                if (!c.moveToFirst()) throw NotFound("No such message.")
                c.getString(0) to (if (c.isNull(1)) null else c.getLong(1))
            }
            db.delete("messages", "id = ?", arrayOf(id))
            // the thread's newest message and unread count, without the deleted one
            db.execSQL(
                "UPDATE threads SET " +
                    "last_message_id = (SELECT id FROM messages WHERE thread_id = threads.id ORDER BY ts DESC, id DESC LIMIT 1), " +
                    "last_message_at = (SELECT MAX(ts) FROM messages WHERE thread_id = threads.id), " +
                    "unread = (SELECT COUNT(*) FROM messages WHERE thread_id = threads.id AND direction = 'in' AND read = 0), " +
                    "updated_at = ? WHERE id = ?",
                arrayOf<Any>(now, threadId),
            )
            sys
        }
        if (systemId != null && SmsStore.isDefaultSmsApp(context)) {
            try {
                context.contentResolver.delete(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, systemId), null, null)
            } catch (e: Exception) {
                Log.w("NativephpSms.Writes", "system message delete failed", e)
            }
        }
    }

    // -- templates -------------------------------------------------------------------------------------------------------

    /** Creates (no id) or edits a saved reply; returns its id. A shortcut is unique. */
    fun saveTemplate(context: Context, id: String?, title: String?, body: String?, shortcut: String?, now: Long = System.currentTimeMillis()): String =
        SmsDb.write(context) { db ->
            val t = title?.trim().orEmpty()
            val b = body.orEmpty()
            if (t.isEmpty()) throw Invalid("A template needs a title.")
            if (b.isBlank()) throw Invalid("A template needs words.")
            if (t.length > 120) throw Invalid("The title is too long (120 characters at most).")
            if (b.length > 2000) throw Invalid("The words are too long (2,000 characters at most).")
            val s = shortcut?.trim()?.lowercase()?.ifEmpty { null }
            if (s != null) {
                if (!Regex("^[a-z0-9_-]{1,20}$").matches(s)) throw Invalid("A shortcut is up to 20 letters, digits, - or _.")
                val taken = db.rawQuery("SELECT id FROM templates WHERE shortcut = ? AND id != ?", arrayOf(s, id ?: "")).use { it.moveToFirst() }
                if (taken) throw Invalid("Another template already has that shortcut.")
            }
            val values = ContentValues().apply {
                put("title", t); put("body", b)
                if (s == null) putNull("shortcut") else put("shortcut", s)
                put("updated_at", now)
            }
            if (id == null) {
                val newId = Ulid.next(now)
                db.insertOrThrow("templates", null, values.apply { put("id", newId); put("created_at", now) })
                newId
            } else {
                if (db.update("templates", values, "id = ?", arrayOf(id)) == 0) throw NotFound("No such template.")
                id
            }
        }

    fun deleteTemplate(context: Context, id: String) = SmsDb.write(context) { db ->
        if (db.delete("templates", "id = ?", arrayOf(id)) == 0) throw NotFound("No such template.")
    }

    // -- scheduled -------------------------------------------------------------------------------------------------------

    /**
     * Creates (no id) or edits a text to send later: to a thread (thread_id) or a number (to). Only a waiting one can be
     * edited; status "cancelled" cancels it. A waiting text is armed to go out at its time (ScheduledSender).
     */
    fun saveScheduled(
        context: Context, id: String?, threadId: String?, to: String?, body: String?, sendAt: Long?, subId: Int?, status: String?,
        now: Long = System.currentTimeMillis(),
    ): String = SmsDb.write(context) { db -> saveScheduledRow(db, id, threadId, to, body, sendAt, subId, status, now) }
        .also { ScheduledSender.sync(context, it) }

    private fun saveScheduledRow(
        db: android.database.sqlite.SQLiteDatabase, id: String?, threadId: String?, to: String?, body: String?, sendAt: Long?, subId: Int?, status: String?, now: Long,
    ): String {
        if (status != null && status !in SCHEDULED_STATUSES) throw Invalid("status can only be waiting or cancelled.")
        if (id != null) {
            val current = db.rawQuery("SELECT status FROM scheduled WHERE id = ?", arrayOf(id)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: throw NotFound("No such scheduled text.")
            if (current != "waiting") throw Invalid("Only a text still waiting can be changed.")
        }
        val values = ContentValues().apply { put("updated_at", now) }
        if (id == null || body != null) {
            if (body.isNullOrBlank()) throw Invalid("A scheduled text needs words.")
            values.put("body", body)
        }
        if (id == null || sendAt != null) {
            if (sendAt == null || (status != "cancelled" && sendAt <= now)) throw Invalid("Choose a time in the future.")
            values.put("send_at", sendAt)
        }
        if (subId != null) values.put("sub_id", subId)
        if (status != null) values.put("status", status)
        if (id == null) {
            val thread = when {
                threadId != null -> threadId.also { if (!exists(db, "threads", it)) throw NotFound("No such conversation.") }
                !to.isNullOrBlank() -> MessageStore.threadFor(db, listOf(to.trim()), null, "inbox", now)
                else -> throw Invalid("Say who it goes to (a conversation or a number).")
            }
            val newId = Ulid.next(now)
            db.insertOrThrow("scheduled", null, values.apply { put("id", newId); put("thread_id", thread); put("created_at", now) })
            return newId
        }
        db.update("scheduled", values, "id = ?", arrayOf(id))
        return id
    }

    fun deleteScheduled(context: Context, id: String) {
        SmsDb.write(context) { db ->
            if (db.delete("scheduled", "id = ?", arrayOf(id)) == 0) throw NotFound("No such scheduled text.")
        }
        ScheduledSender.disarm(context, id)
    }

    // -- settings --------------------------------------------------------------------------------------------------------

    /** The app's own settings, under "app." (the plugin's own keys, such as the import cursor, are not writable). Null removes. */
    fun setSetting(context: Context, key: String, value: String?) {
        if (!SETTING_KEY.matches(key)) throw Invalid("A setting key starts with app. (letters, digits, . _ -).")
        if (value != null && value.length > 10_000) throw Invalid("That value is too long.")
        SmsDb.setSetting(context, key, value)
    }

    private fun bool(v: Any, name: String): Boolean = when (v) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        "true", "1" -> true
        "false", "0" -> false
        else -> throw Invalid("$name must be true or false.")
    }
}
