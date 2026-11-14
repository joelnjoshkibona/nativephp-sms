package com.blutrixx.plugins.nativephp_sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoreWritesTest : DbTestBase() {
    private lateinit var thread: String
    private val messages = mutableListOf<String>()

    @Before fun conversation() {
        messages.clear()
        SmsDb.write(context) { db ->
            (1..3).forEach { i ->
                val saved = MessageStore.insertIncoming(db, MessageStore.Incoming("+255700000001", "m$i", 1000L * i, 1, 0, null, null))
                thread = saved.threadId
                messages += saved.messageId
            }
        }
    }

    private fun one(sql: String, vararg args: String): String? =
        SmsDb.get(context).rawQuery(sql, args).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    @Test fun aThreadIsPinnedMutedArchivedAndMarkedRead() {
        StoreWrites.saveThread(context, thread, mapOf("pinned" to true, "muted" to 1, "folder" to "archive", "read" to true, "color" to "#aa3355"))
        assertEquals("1|1|archive|0|#aa3355", one("SELECT pinned || '|' || muted || '|' || folder || '|' || unread || '|' || color FROM threads WHERE id = ?", thread))
        assertEquals("0", one("SELECT COUNT(*) FROM messages WHERE read = 0"))
        StoreWrites.saveThread(context, thread, mapOf("color" to null))
        assertNull(one("SELECT color FROM threads WHERE id = ?", thread))
    }

    @Test fun aBadValueOrAMissingThreadIsRefusedWithAReason() {
        assertEquals("folder must be inbox, spam or archive.", assertThrows(StoreWrites.Invalid::class.java) { StoreWrites.saveThread(context, thread, mapOf("folder" to "trash")) }.message)
        assertThrows(StoreWrites.NotFound::class.java) { StoreWrites.saveThread(context, "nope", mapOf("pinned" to true)) }
    }

    @Test fun deletingTheNewestMessageMovesTheThreadBackAndRecountsUnread() {
        StoreWrites.deleteMessage(context, messages.last())
        assertEquals("${messages[1]}|2000|2", one("SELECT last_message_id || '|' || last_message_at || '|' || unread FROM threads WHERE id = ?", thread))
        assertEquals("message:delete", one("SELECT entity || ':' || op FROM change_log WHERE entity_id = ? ORDER BY seq DESC LIMIT 1", messages.last()))
    }

    @Test fun deletingAThreadDeletesItsMessagesAndLogsEach() {
        StoreWrites.deleteThread(context, thread)
        assertEquals("0", one("SELECT COUNT(*) FROM messages"))
        assertEquals("4", one("SELECT COUNT(*) FROM change_log WHERE op = 'delete'"))
    }

    @Test fun templatesKeepAUniqueShortcut() {
        val id = StoreWrites.saveTemplate(context, null, "On my way", "On my way, 10 minutes", "omw")
        assertEquals("Another template already has that shortcut.", assertThrows(StoreWrites.Invalid::class.java) { StoreWrites.saveTemplate(context, null, "Other", "x", "OMW") }.message)
        StoreWrites.saveTemplate(context, id, "On my way", "Five minutes", "omw") // its own shortcut is fine
        assertEquals("Five minutes", one("SELECT body FROM templates WHERE id = ?", id))
        StoreWrites.deleteTemplate(context, id)
        assertThrows(StoreWrites.NotFound::class.java) { StoreWrites.deleteTemplate(context, id) }
    }

    @Test fun aScheduledTextGoesToAThreadOrANumberAndOnlyAWaitingOneChanges() {
        val now = 10_000L
        val a = StoreWrites.saveScheduled(context, null, thread, null, "later", now + 60_000, null, null, now)
        val b = StoreWrites.saveScheduled(context, null, null, "+255700000009", "to someone new", now + 60_000, 2, null, now)
        assertEquals("2", one("SELECT COUNT(*) FROM threads"))
        assertEquals("Choose a time in the future.", assertThrows(StoreWrites.Invalid::class.java) { StoreWrites.saveScheduled(context, null, thread, null, "x", now - 1, null, null, now) }.message)
        StoreWrites.saveScheduled(context, a, null, null, null, null, null, "cancelled", now)
        assertEquals("Only a text still waiting can be changed.", assertThrows(StoreWrites.Invalid::class.java) { StoreWrites.saveScheduled(context, a, null, null, "y", null, null, null, now) }.message)
        assertEquals("waiting|2", one("SELECT status || '|' || sub_id FROM scheduled WHERE id = ?", b))
    }

    @Test fun onlyTheAppsOwnSettingsCanBeWritten() {
        StoreWrites.setSetting(context, "app.theme", "dark")
        assertEquals("dark", SmsDb.setting(context, "app.theme"))
        assertThrows(StoreWrites.Invalid::class.java) { StoreWrites.setSetting(context, "import.state", "done") }
        StoreWrites.setSetting(context, "app.theme", null)
        assertNull(SmsDb.setting(context, "app.theme"))
    }
}
