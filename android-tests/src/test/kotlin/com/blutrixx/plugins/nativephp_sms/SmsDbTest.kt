package com.blutrixx.plugins.nativephp_sms

import android.content.ContentValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsDbTest : DbTestBase() {
    private fun thread(id: String, key: String) = ContentValues().apply {
        put("id", id); put("participants_key", key); put("created_at", 1L); put("updated_at", 1L)
    }

    private fun message(id: String, threadId: String) = ContentValues().apply {
        put("id", id); put("thread_id", threadId); put("direction", "in"); put("address", "+255700000001")
        put("body", "x"); put("ts", 1L); put("status", "received"); put("created_at", 1L); put("updated_at", 1L)
    }

    @Test fun anEmptyFileIsMigratedToTheLatestVersionWithEveryTable() {
        assertEquals(SmsDb.MIGRATIONS.size, SmsDb.version(context))
        val tables = SmsDb.get(context).rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' AND name NOT LIKE 'messages_fts%' ORDER BY name", null)
            .use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
        assertEquals(
            listOf("attachments", "blocklist", "change_log", "contact_cache", "events", "messages", "paired_devices", "scheduled", "settings", "spam_senders", "templates", "threads"),
            tables,
        )
        assertTrue(SmsDb.file(context).path.endsWith("persisted_data/database/sms.sqlite"))
    }

    @Test fun everyChangeToThreadsMessagesAndAttachmentsWritesChangeLogInOrder() {
        SmsDb.write(context) { db ->
            db.insertOrThrow("threads", null, thread("T1", "255700000001"))
            db.insertOrThrow("messages", null, message("M1", "T1"))
            db.insertOrThrow("attachments", null, ContentValues().apply { put("id", "A1"); put("message_id", "M1"); put("mime", "image/jpeg"); put("created_at", 1L) })
            db.update("messages", ContentValues().apply { put("read", 1) }, "id = ?", arrayOf("M1"))
            db.delete("threads", "id = ?", arrayOf("T1")) // cascades to the message and the attachment
        }
        val rows = SmsDb.get(context).rawQuery("SELECT seq, entity, entity_id, op FROM change_log ORDER BY seq", null).use { c ->
            generateSequence { if (c.moveToNext()) listOf(c.getLong(0), c.getString(1), c.getString(2), c.getString(3)) else null }.toList()
        }
        assertEquals(
            listOf(
                listOf("thread", "T1", "upsert"), listOf("message", "M1", "upsert"), listOf("attachment", "A1", "upsert"),
                listOf("message", "M1", "upsert"), listOf("attachment", "A1", "delete"), listOf("message", "M1", "delete"), listOf("thread", "T1", "delete"),
            ),
            rows.map { it.drop(1) },
        )
        val seqs = rows.map { it[0] as Long }
        assertEquals(seqs.sorted(), seqs)
        assertEquals(seqs.size, seqs.toSet().size)
    }

    @Test fun otherTablesNeverWriteChangeLog() {
        SmsDb.write(context) { db ->
            db.insertOrThrow("templates", null, ContentValues().apply { put("id", "X"); put("title", "t"); put("body", "b"); put("created_at", 1L); put("updated_at", 1L) })
            SmsDb.setSetting(db, "k", "v")
            db.insertOrThrow("spam_senders", null, ContentValues().apply { put("address", "a"); put("reported_at", 1L) })
        }
        assertEquals(0L, count("SELECT COUNT(*) FROM change_log"))
    }

    @Test fun aSeqIsNeverReusedAfterADelete() {
        SmsDb.write(context) { it.insertOrThrow("threads", null, thread("T1", "1")) }
        SmsDb.write(context) { it.delete("change_log", null, null) }
        SmsDb.write(context) { it.insertOrThrow("threads", null, thread("T2", "2")) }
        assertEquals(2L, count("SELECT seq FROM change_log"))
    }

    /**
     * The schema the app's Laravel reads, written to database/sms-schema.sql for its tests' fixture databases. A change to
     * MIGRATIONS fails here until the file is written again: UPDATE_SCHEMA=1 ./gradlew test
     */
    @Test fun theSchemaFileMatchesTheMigrations() {
        val schema = SmsDb.get(context).rawQuery(
            // the search index's own tables (messages_fts_content, _segments …) are made by its CREATE VIRTUAL TABLE
            "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' AND name NOT LIKE 'messages_fts_%' ORDER BY CASE type WHEN 'table' THEN 0 WHEN 'index' THEN 1 ELSE 2 END, name",
            null,
        ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) + ";" else null }.joinToString("\n\n") }
        val text = "-- sms.sqlite, schema version ${SmsDb.MIGRATIONS.size}. Written by SmsDbTest from SmsDb.MIGRATIONS; do not edit.\n\n$schema\n"
        val file = File("../database/sms-schema.sql")
        if (System.getenv("UPDATE_SCHEMA") == "1") {
            file.parentFile?.mkdirs()
            file.writeText(text)
        }
        assertEquals("database/sms-schema.sql is out of date: UPDATE_SCHEMA=1 ./gradlew test", text, file.takeIf { it.exists() }?.readText())
    }
}
