package com.blutrixx.plugins.nativephp_sms

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * sms.sqlite: the app's own message store. This plugin's Kotlin is its only writer (receivers, sends, the import, and the
 * bridge functions the app's Laravel calls for its writes); Laravel opens the same file read-only as its `sms` connection.
 *
 * The file sits beside Laravel's own database (persisted_data/database/), so PHP finds it as dirname(DB_DATABASE)/sms.sqlite
 * and it survives updates the same way. WAL lets PHP read while Kotlin writes.
 *
 * Migrations are numbered: MIGRATIONS[n-1] takes the file from version n-1 to n. Never edit a shipped one; add the next.
 */
object SmsDb {
    const val FILE = "sms.sqlite"

    @Volatile private var helper: Helper? = null

    fun file(context: Context): File =
        File(context.applicationContext.getDir("storage", Context.MODE_PRIVATE), "persisted_data/database/$FILE")

    fun get(context: Context): SQLiteDatabase {
        helper?.let { return it.writableDatabase }
        synchronized(this) {
            val h = helper ?: Helper(context.applicationContext).also { helper = it }
            return h.writableDatabase
        }
    }

    /** One transaction. SQLite serialises writers on its one write connection, so writes from any thread queue here. */
    fun <T> write(context: Context, block: (SQLiteDatabase) -> T): T {
        val db = get(context)
        db.beginTransactionNonExclusive()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    fun setting(context: Context, key: String): String? =
        get(context).rawQuery("SELECT value FROM settings WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun setSetting(db: SQLiteDatabase, key: String, value: String?) {
        if (value == null) {
            db.delete("settings", "key = ?", arrayOf(key))
        } else {
            db.insertWithOnConflict("settings", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun setSetting(context: Context, key: String, value: String?) = write(context) { setSetting(it, key, value) }

    fun version(context: Context): Int = get(context).version

    /** Tests only: forget the open file so the next get() opens (and migrates) afresh. */
    internal fun closeForTests() {
        synchronized(this) {
            helper?.close()
            helper = null
        }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, file(context).also { it.parentFile?.mkdirs() }.absolutePath, null, MIGRATIONS.size) {
        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
            db.rawQuery("PRAGMA busy_timeout = 5000", null).close()
        }

        override fun onCreate(db: SQLiteDatabase) = migrate(db, 0, MIGRATIONS.size)

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = migrate(db, oldVersion, newVersion)

        private fun migrate(db: SQLiteDatabase, from: Int, to: Int) {
            for (v in from until to) {
                MIGRATIONS[v].split(";\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach { db.execSQL(it) }
            }
        }
    }

    private const val NOW_MS = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"

    /** change_log is written by triggers, so no code path that changes a thread, message or attachment can forget it. */
    private fun changeTriggers(table: String, entity: String) = listOf("INSERT" to "NEW", "UPDATE" to "NEW", "DELETE" to "OLD").joinToString("") { (event, row) ->
        val op = if (event == "DELETE") "delete" else "upsert"
        "CREATE TRIGGER ${table}_${event.lowercase()}_log AFTER $event ON $table BEGIN " +
            "INSERT INTO change_log (entity, entity_id, op, ts) VALUES ('$entity', $row.id, '$op', $NOW_MS); END;\n"
    }

    internal val MIGRATIONS: List<String> = listOf(
        // 1: the plan's phone tables (vault_meta and vault_messages live in vault.sqlite, never here)
        """
        CREATE TABLE threads (
            id TEXT PRIMARY KEY,
            participants_key TEXT NOT NULL UNIQUE,
            addresses TEXT NOT NULL DEFAULT '[]',
            system_thread_id INTEGER,
            folder TEXT NOT NULL DEFAULT 'inbox' CHECK (folder IN ('inbox', 'spam', 'archive')),
            last_message_at INTEGER,
            last_message_id TEXT,
            unread INTEGER NOT NULL DEFAULT 0,
            pinned INTEGER NOT NULL DEFAULT 0,
            muted INTEGER NOT NULL DEFAULT 0,
            color TEXT,
            wallpaper TEXT,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        );
        CREATE INDEX threads_list ON threads (folder, pinned, last_message_at);
        CREATE TABLE messages (
            id TEXT PRIMARY KEY,
            thread_id TEXT NOT NULL REFERENCES threads (id) ON DELETE CASCADE,
            system_id INTEGER UNIQUE,
            direction TEXT NOT NULL CHECK (direction IN ('in', 'out')),
            address TEXT NOT NULL,
            body TEXT NOT NULL DEFAULT '',
            ts INTEGER NOT NULL,
            status TEXT NOT NULL CHECK (status IN ('received', 'queued', 'sent', 'delivered', 'failed')),
            sim_slot INTEGER,
            sub_id INTEGER,
            kind TEXT NOT NULL DEFAULT 'sms' CHECK (kind IN ('sms', 'mms')),
            parts_total INTEGER NOT NULL DEFAULT 1,
            parts_sent INTEGER NOT NULL DEFAULT 0,
            parts_failed INTEGER NOT NULL DEFAULT 0,
            parts_delivered INTEGER NOT NULL DEFAULT 0,
            client_ref TEXT UNIQUE,
            read INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        );
        CREATE INDEX messages_thread ON messages (thread_id, ts);
        CREATE TABLE attachments (
            id TEXT PRIMARY KEY,
            message_id TEXT NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
            mime TEXT NOT NULL,
            size INTEGER NOT NULL DEFAULT 0,
            local_path TEXT,
            thumb_path TEXT,
            created_at INTEGER NOT NULL
        );
        CREATE INDEX attachments_message ON attachments (message_id);
        CREATE TABLE contact_cache (
            number_e164 TEXT PRIMARY KEY,
            name TEXT,
            color_override TEXT,
            avatar_ref TEXT,
            updated_at INTEGER NOT NULL
        );
        CREATE TABLE blocklist (
            id TEXT PRIMARY KEY,
            kind TEXT NOT NULL CHECK (kind IN ('number', 'prefix', 'wildcard', 'keyword')),
            action TEXT NOT NULL DEFAULT 'block' CHECK (action IN ('block', 'private')),
            notify TEXT CHECK (notify IN ('generic', 'silent')),
            pattern TEXT NOT NULL,
            created_at INTEGER NOT NULL
        );
        CREATE TABLE spam_senders (
            address TEXT PRIMARY KEY,
            reported_at INTEGER NOT NULL
        );
        CREATE TABLE scheduled (
            id TEXT PRIMARY KEY,
            thread_id TEXT NOT NULL REFERENCES threads (id) ON DELETE CASCADE,
            body TEXT NOT NULL,
            send_at INTEGER NOT NULL,
            sub_id INTEGER,
            status TEXT NOT NULL DEFAULT 'waiting' CHECK (status IN ('waiting', 'sending', 'sent', 'failed', 'cancelled')),
            message_id TEXT,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        );
        CREATE INDEX scheduled_due ON scheduled (status, send_at);
        CREATE TABLE templates (
            id TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            body TEXT NOT NULL,
            shortcut TEXT UNIQUE,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        );
        CREATE TABLE change_log (
            seq INTEGER PRIMARY KEY AUTOINCREMENT,
            entity TEXT NOT NULL CHECK (entity IN ('thread', 'message', 'attachment')),
            entity_id TEXT NOT NULL,
            op TEXT NOT NULL CHECK (op IN ('upsert', 'delete')),
            ts INTEGER NOT NULL
        );
        CREATE TABLE paired_devices (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            public_key TEXT NOT NULL,
            paired_at INTEGER NOT NULL,
            web_access_enabled INTEGER NOT NULL DEFAULT 1,
            last_seen INTEGER
        );
        CREATE TABLE settings (
            key TEXT PRIMARY KEY,
            value TEXT
        );
        CREATE TABLE events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            type TEXT NOT NULL,
            at INTEGER NOT NULL,
            data TEXT NOT NULL DEFAULT '{}'
        );
        """.trimIndent() + "\n" +
            changeTriggers("threads", "thread") + changeTriggers("messages", "message") + changeTriggers("attachments", "attachment"),
    )
}
