package com.blutrixx.plugins.nativephp_sms

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 1.3: the vault's keys, hiding and restoring, blocking, private numbers, sending from the vault, spam, and the gate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultTest : DbTestBase() {
    private val pass = "quiet harbor 42"

    @Before fun allow() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        SmsNotifier.activeThread = null
    }

    private fun receive(from: String, body: String, ts: Long = System.currentTimeMillis()) =
        ReceivePipeline.receive(context, ReceivePipeline.Delivered(from, body, ts, ts - 500, 1, 0, 1))

    // -- keys ---------------------------------------------------------------------------------------------------------

    @Test fun setupAnswersARecoveryKeyOnceAndEitherItOrThePassphraseOpens() {
        val recovery = VaultStore.setup(context, pass)
        assertTrue(Regex("^[A-Z2-7]{4}(-[A-Z2-7]{1,4}){6}$").matches(recovery))
        assertTrue(VaultStore.isOpen())
        assertThrows(VaultStore.VaultExists::class.java) { VaultStore.setup(context, "again") }
        VaultStore.lock()
        assertFalse(VaultStore.unlock(context, "quiet harbor 43"))
        assertTrue(VaultStore.unlock(context, pass))
        VaultStore.lock()
        assertTrue(VaultStore.unlock(context, recovery.lowercase().replace("-", " ")))
    }

    @Test fun everyUnlockCostsOneArgon2idWithOrWithoutAVault() {
        assertFalse(VaultStore.unlock(context, "anything"))
        assertEquals(1, TestCrypto.pwhashCalls)
        VaultStore.setup(context, pass)
        VaultStore.lock()
        TestCrypto.pwhashCalls = 0
        VaultStore.unlock(context, "wrong")
        VaultStore.unlock(context, pass)
        assertEquals(2, TestCrypto.pwhashCalls)
    }

    @Test fun aNewPassphraseOpensItAndTheOldOneNoLonger() {
        VaultStore.setup(context, pass)
        VaultStore.changePassphrase(context, "new words here")
        VaultStore.lock()
        assertFalse(VaultStore.unlock(context, pass))
        assertTrue(VaultStore.unlock(context, "new words here"))
    }

    @Test fun itLocksAfterFiveIdleMinutes() {
        var now = 1_000_000L
        VaultStore.clock = { now }
        try {
            VaultStore.setup(context, pass)
            now += VaultStore.IDLE_MS - 1
            assertTrue(VaultStore.isOpen())
            now += VaultStore.IDLE_MS + 1
            assertFalse(VaultStore.isOpen())
            assertThrows(VaultStore.VaultLocked::class.java) { VaultStore.rows(context) }
        } finally {
            VaultStore.clock = { System.currentTimeMillis() }
        }
    }

    // -- hide, restore, block, private -----------------------------------------------------------------------------

    @Test fun hiddenMessagesLeaveTheConversationAndComeBackOnRestore() {
        VaultStore.setup(context, pass)
        val a = receive("+255700000001", "keep this one", ts = 1_000)
        val b = receive("+255700000001", "hide this one", ts = 2_000)
        assertEquals(1, VaultOps.hideMessages(context, listOf(b.messageId!!)))
        assertEquals(1L, count("SELECT COUNT(*) FROM messages"))
        assertEquals(a.messageId, SmsDb.get(context).rawQuery("SELECT last_message_id FROM threads", null).use { it.moveToFirst(); it.getString(0) })
        val conv = VaultOps.conversations(context).single()
        assertEquals(listOf("hidden", 1), listOf(conv.tag, conv.count))
        val id = VaultOps.messages(context, conv.address).single().getString("id")
        assertEquals(1, VaultOps.restore(context, listOf(id)))
        assertEquals(listOf("keep this one", "hide this one"), SmsDb.get(context).rawQuery("SELECT body FROM messages ORDER BY ts", null).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() })
        assertEquals(0, VaultStore.count(context))
    }

    @Test fun blockMovesTheConversationInWhenAskedAndTheirNextTextsAreSealed() {
        VaultStore.setup(context, pass)
        val r = receive("+255711000432", "why are you ignoring me")
        VaultOps.block(context, "+255711000432", r.threadId)
        assertEquals(0L, count("SELECT COUNT(*) FROM threads"))
        val next = receive("+255711000432", "answer your phone")
        assertEquals(ReceivePipeline.Route.Blocked, next.route)
        assertEquals(listOf("blocked", 2), VaultOps.conversations(context).single().let { listOf(it.tag, it.count) })
        VaultOps.wipe(context)
        assertEquals(listOf(0, false), listOf(Blocklist.rules(context).size, VaultStore.exists(context)))
        assertEquals(ReceivePipeline.Route.Normal, receive("+255711000432", "after the wipe").route)
        assertThrows(VaultOps.NoVault::class.java) { VaultOps.block(context, "+255700000002", null) }
    }

    @Test fun keepPrivateThenMoveOut() {
        VaultStore.setup(context, pass)
        val r = receive("+255700000005", "our secret")
        VaultOps.keepPrivate(context, r.threadId!!, "silent")
        assertTrue(VaultOps.isPrivate(context, "0700000005"))
        val c = VaultOps.conversations(context).single()
        assertEquals(listOf("private", "silent"), listOf(c.tag, c.notify))
        assertEquals(ReceivePipeline.Route.Private("silent"), receive("+255700000005", "again").route)
        assertEquals(2, VaultOps.moveOut(context, "+255700000005"))
        assertFalse(VaultOps.isPrivate(context, "+255700000005"))
        assertEquals(2L, count("SELECT COUNT(*) FROM messages"))
    }

    @Test fun rulesNeedTheOpenVaultAndAPrivateRulesRemovalMovesItOut() {
        VaultStore.setup(context, pass)
        val id = VaultOps.addRule(context, "prefix", "+25568")
        assertEquals(listOf("prefix"), VaultOps.rules(context).map { it.kind })
        assertThrows(StoreWrites.Invalid::class.java) { VaultOps.addRule(context, "wildcard", "**") }
        VaultStore.lock()
        assertThrows(VaultStore.VaultLocked::class.java) { VaultOps.rules(context) }
        VaultStore.unlock(context, pass)
        VaultOps.removeRule(context, id)
        assertEquals(0, VaultOps.rules(context).size)
    }

    // -- sending ---------------------------------------------------------------------------------------------------

    @Test fun aTextFromTheVaultIsKeptOnlyThereWithItsState() {
        VaultStore.setup(context, pass)
        SmsSender.sendFromVault(context, "+255700000005", "from the vault only", "ref-v", -1)
        assertEquals(0L, count("SELECT COUNT(*) FROM messages"))
        assertEquals("sent", VaultStore.countPart(context, "ref-v", "sent", true))
        assertEquals("delivered", VaultStore.countPart(context, "ref-v", "delivered", true))
        assertEquals("delivered", VaultOps.messages(context, "+255700000005").single().getString("status"))
    }

    @Test fun theNormalComposerToAPrivateNumberSendsNormallyWithASealedCopy() {
        VaultStore.setup(context, pass)
        val r = receive("+255700000007", "hi")
        VaultOps.keepPrivate(context, r.threadId!!)
        val sent = SmsSender.send(context, "+255700000007", "from the normal screen", "ref-n", -1)
        assertEquals(1L, count("SELECT COUNT(*) FROM messages WHERE body = 'from the normal screen'"))
        assertNotNull(sent.messageId)
        val copy = VaultOps.messages(context, "+255700000007").last()
        assertEquals(listOf("out", "from the normal screen", "queued"), listOf(copy.getString("direction"), copy.getString("body"), copy.getString("status")))
        SmsDb.write(context) { MessageStore.countPart(it, sent.messageId!!, "sent", true) }
        assertEquals("sent", VaultStore.countPart(context, "ref-n", "sent", true))
        // moving out puts back what only the vault had, and never a second copy of the text sent normally
        VaultOps.moveOut(context, "+255700000007")
        assertEquals(1L, count("SELECT COUNT(*) FROM messages WHERE body = 'from the normal screen'"))
        assertEquals(1L, count("SELECT COUNT(*) FROM messages WHERE body = 'hi'"))
        assertEquals(0, VaultStore.count(context))
    }

    // -- spam ------------------------------------------------------------------------------------------------------

    @Test fun reportSpamSendsTheirNextTextsToSpamSilentlyAndNotSpamUndoesIt() {
        val r = receive("+255689004117", "you won")
        VaultOps.reportSpam(context, r.threadId!!)
        assertEquals(1L, count("SELECT COUNT(*) FROM threads WHERE folder = 'spam' AND muted = 1"))
        assertEquals(ReceivePipeline.Route.Spam, receive("+255689004117", "claim now").route)
        VaultOps.notSpam(context, r.threadId!!)
        assertEquals(ReceivePipeline.Route.Normal, receive("+255689004117", "really").route)
    }

    @Test fun spamOlderThanThirtyDaysIsPurged() {
        val now = 10_000_000_000L
        val old = receive("+255689000001", "old spam", ts = now - VaultOps.SPAM_KEEP_MS - 1)
        val fresh = receive("+255689000002", "new spam", ts = now - 1_000)
        VaultOps.reportSpam(context, old.threadId!!)
        VaultOps.reportSpam(context, fresh.threadId!!)
        assertEquals(1, VaultOps.purgeSpam(context, now))
        assertEquals(1L, count("SELECT COUNT(*) FROM threads"))
    }

    // -- the gate --------------------------------------------------------------------------------------------------

    /** 1.3's gate: a vault text is never in the system store, sms.sqlite, search, the notification text or change_log. */
    @Test fun aVaultTextIsNowhereOutsideTheVault() {
        val written = mutableListOf<String>()
        SmsStore.onWrite = { _, body -> written += body }
        VaultStore.setup(context, pass)
        // private (generic notification), blocked, hidden, and a text sent from the vault
        val seed = receive("+255700000011", "private seed SEEDWORD")
        VaultOps.keepPrivate(context, seed.threadId!!, "generic")
        receive("+255700000011", "private incoming PRIVWORD")
        VaultOps.block(context, "+255700000012", null)
        receive("+255700000012", "blocked incoming BLOCKWORD")
        val h = receive("+255700000013", "hidden later HIDEWORD")
        VaultOps.hideMessages(context, listOf(h.messageId!!))
        SmsSender.sendFromVault(context, "+255700000011", "vault reply REPLYWORD", "ref-g", -1)

        val words = listOf("SEEDWORD", "PRIVWORD", "BLOCKWORD", "HIDEWORD", "REPLYWORD")
        val all = everythingInSmsDb()
        for (w in words) {
            assertFalse("$w in sms.sqlite", all.contains(w))
            assertTrue("$w found by search", MessageSearch.search(context, w.lowercase()).isEmpty())
        }
        // the system store saw the seed and the hidden text before they went in, and nothing after (deleted there too)
        assertFalse(written.any { it.contains("PRIVWORD") || it.contains("BLOCKWORD") || it.contains("REPLYWORD") })
        // change_log: the moved messages are recorded as deletes only
        assertEquals(0L, count("SELECT COUNT(*) FROM change_log WHERE op = 'upsert' AND entity_id IN (SELECT entity_id FROM change_log WHERE op = 'delete') AND seq > (SELECT MAX(seq) FROM change_log WHERE op = 'delete')"))
        val shown = context.getSystemService(NotificationManager::class.java).activeNotifications.map { n ->
            listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT).joinToString("|") { n.notification.extras.getCharSequence(it)?.toString() ?: "" }
        }
        assertTrue(shown.none { s -> words.any { s.contains(it) } || s.contains("700000011") })
        assertEquals(5, VaultOps.conversations(context).sumOf { it.count })
    }

    @Test fun theRecoveryKeyFormatReadsBackAndForgivesHandCopies() {
        val bytes = ByteArray(16) { (it * 17).toByte() }
        val key = RecoveryKey.format(bytes)
        assertTrue(RecoveryKey.parse(key)!!.contentEquals(bytes))
        assertTrue(RecoveryKey.parse(key.lowercase().replace("-", " "))!!.contentEquals(bytes))
        assertNull(RecoveryKey.parse("not a key"))
    }
}
