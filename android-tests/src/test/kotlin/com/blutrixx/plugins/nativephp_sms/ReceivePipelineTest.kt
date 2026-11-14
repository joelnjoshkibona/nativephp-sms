package com.blutrixx.plugins.nativephp_sms

import android.Manifest
import android.app.Application
import android.content.ContentValues
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReceivePipelineTest : DbTestBase() {
    private val secret = "the secret words 4471"
    private val number = "+255712000111"

    private fun delivered(address: String = number, body: String = secret) =
        ReceivePipeline.Delivered(address, body, receivedAt = 1_800_000_000_000L, smscTs = 1_799_999_999_000L, subId = 1, simSlot = 0, parts = 1)

    private fun rule(kind: String, action: String, pattern: String, notify: String? = null) =
        SmsDb.write(context) { Blocklist.add(it, kind, action, notify, pattern) }

    private fun notifications(): ShadowNotificationManager =
        shadowOf(context.getSystemService(android.app.NotificationManager::class.java))

    @Before fun allowNotifications() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    // -- decide(): the order --------------------------------------------------------------------------------------------

    @Test fun aPrivateNumberWinsOverTheBlocklistAndSpam() {
        val rules = listOf(
            Blocklist.Rule("1", "number", "block", null, "0712000111"),
            Blocklist.Rule("2", "number", "private", "silent", "+255 712 000 111"),
        )
        assertEquals(ReceivePipeline.Route.Private("silent"), ReceivePipeline.decide(number, "hi", rules, setOf("712000111")))
    }

    @Test fun theBlocklistWinsOverSpam() {
        val rules = listOf(Blocklist.Rule("1", "prefix", "block", null, "+25571"))
        assertEquals(ReceivePipeline.Route.Blocked, ReceivePipeline.decide(number, "hi", rules, setOf("712000111")))
    }

    @Test fun spamThenNormal() {
        assertEquals(ReceivePipeline.Route.Spam, ReceivePipeline.decide(number, "hi", emptyList(), setOf("712000111")))
        assertEquals(ReceivePipeline.Route.Normal, ReceivePipeline.decide(number, "hi", emptyList(), emptySet()))
    }

    @Test fun eachKindOfRuleMatchesAsItSays() {
        fun m(kind: String, pattern: String, address: String = number, body: String = "") =
            ReceivePipeline.matches(Blocklist.Rule("x", kind, "block", null, pattern), address, body)
        assertTrue(m("number", "0712 000 111"))
        assertFalse(m("number", "0712000112"))
        assertTrue(m("prefix", "+255 71"))
        assertFalse(m("prefix", "+25568"))
        assertTrue(m("wildcard", "+2557?2*111"))
        assertFalse(m("wildcard", "+2557?2*112"))
        assertFalse(m("wildcard", "*")) // a bare * would block everyone
        assertTrue(m("wildcard", "*bet*", address = "SportyBet"))
        assertTrue(m("keyword", "WIN a prize", body = "You win A PRIZE today"))
        assertFalse(m("keyword", "prize", body = "nothing here"))
        assertTrue(m("number", "MPESA", address = "mpesa"))
    }

    // -- receive(): what each route writes ------------------------------------------------------------------------------

    @Test fun normalIsStoredWithItsThreadAndNotified() {
        val out = ReceivePipeline.receive(context, delivered())
        assertEquals(ReceivePipeline.Route.Normal, out.route)
        assertTrue(out.notified)
        assertEquals(1, notifications().size())
        assertEquals(1L, count("SELECT COUNT(*) FROM messages WHERE id = ? AND body = ? AND status = 'received' AND read = 0", out.messageId!!, secret))
        assertEquals(1L, count("SELECT COUNT(*) FROM threads WHERE id = ? AND folder = 'inbox' AND unread = 1 AND last_message_id = ?", out.threadId!!, out.messageId!!))
        // the new thread, the message, then the thread again with its last message
        val log = SmsDb.get(context).rawQuery("SELECT entity || ':' || op FROM change_log ORDER BY seq", null).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
        assertEquals(listOf("thread:upsert", "message:upsert", "thread:upsert"), log)
    }

    @Test fun aSecondTextFromTheSameNumberWrittenDifferentlyJoinsTheThread() {
        val a = ReceivePipeline.receive(context, delivered("+255712000111"))
        val b = ReceivePipeline.receive(context, delivered("0712000111"))
        assertEquals(a.threadId, b.threadId)
        assertEquals(2L, count("SELECT unread FROM threads WHERE id = ?", a.threadId!!))
    }

    @Test fun spamIsStoredInTheSpamFolderWithoutANotification() {
        SmsDb.write(context) { it.insertOrThrow("spam_senders", null, ContentValues().apply { put("address", SmsPrefs.normalize(number)); put("reported_at", 1L) }) }
        val out = ReceivePipeline.receive(context, delivered())
        assertEquals(ReceivePipeline.Route.Spam, out.route)
        assertFalse(out.notified)
        assertEquals(0, notifications().size())
        assertEquals(1L, count("SELECT COUNT(*) FROM threads WHERE id = ? AND folder = 'spam'", out.threadId!!))
    }

    @Test fun aBlockedTextIsSealedAndNowhereElse() {
        VaultStore.setPublicKey(context, ByteArray(32) { 7 }, test = true)
        rule("number", "block", number)
        val out = ReceivePipeline.receive(context, delivered())
        assertEquals(ReceivePipeline.Route.Blocked, out.route)
        assertTrue(out.sealed)
        assertFalse(out.notified)
        assertEquals(0, notifications().size())
        assertVaultHolds(secret, "blocked")
        assertNotInSmsDb()
    }

    @Test fun aPrivateTextIsSealedWithANotificationThatSaysNothing() {
        VaultStore.setPublicKey(context, ByteArray(32) { 7 }, test = true)
        rule("number", "private", number, notify = "generic")
        val out = ReceivePipeline.receive(context, delivered())
        assertEquals(ReceivePipeline.Route.Private("generic"), out.route)
        assertTrue(out.sealed && out.notified)
        val shown = notifications().allNotifications.single()
        val words = listOf(shown.extras.getCharSequence("android.title"), shown.extras.getCharSequence("android.text"), shown.tickerText).joinToString("|")
        assertEquals("New message|null|null", words)
        assertVaultHolds(secret, "private")
        assertNotInSmsDb()
    }

    @Test fun aSilentPrivateNumberPostsNothing() {
        VaultStore.setPublicKey(context, ByteArray(32) { 7 }, test = true)
        rule("number", "private", number, notify = "silent")
        val out = ReceivePipeline.receive(context, delivered())
        assertFalse(out.notified)
        assertEquals(0, notifications().size())
    }

    @Test fun withoutAVaultKeyABlockedTextWaitsInQuarantineAndIsSealedWhenAKeyArrives() {
        rule("number", "block", number)
        val out = ReceivePipeline.receive(context, delivered())
        assertFalse(out.sealed)
        val quarantine = File(context.filesDir, VaultStore.QUARANTINE_FILE)
        assertTrue(quarantine.exists())
        assertNotInSmsDb()

        VaultStore.setPublicKey(context, ByteArray(32) { 7 }, test = true)
        assertFalse(quarantine.exists())
        assertVaultHolds(secret, "blocked")
    }

    @Test fun theOldPlainBlocklistIsMovedInOnce() {
        context.getSharedPreferences("nativephp_sms", 0).edit().putStringSet("blocklist", setOf("712000111")).commit()
        assertEquals(1, Blocklist.size(context))
        assertEquals(ReceivePipeline.Route.Blocked, ReceivePipeline.decide(number, "x", Blocklist.rules(context), emptySet()))
        assertNull(context.getSharedPreferences("nativephp_sms", 0).getStringSet("blocklist", null))
        assertFalse(everythingInSmsDb().contains("712000111")) // stored encrypted
    }

    private fun assertVaultHolds(body: String, why: String) {
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(VaultStore.file(context).path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
        val sealed = db.rawQuery("SELECT ciphertext FROM vault_messages", null).use { c -> c.moveToFirst(); c.getBlob(0) }
        db.close()
        val record = JSONObject(TestCrypto.peek(sealed))
        assertEquals(listOf(body, why, number), listOf(record.getString("body"), record.getString("why"), record.getString("from")))
    }

    private fun assertNotInSmsDb() {
        val all = everythingInSmsDb()
        assertFalse("the words are in sms.sqlite", all.contains(secret))
        assertFalse("the number is in sms.sqlite", all.contains("712000111"))
        assertEquals(0L, count("SELECT COUNT(*) FROM messages"))
        assertEquals(0L, count("SELECT COUNT(*) FROM change_log"))
    }
}
