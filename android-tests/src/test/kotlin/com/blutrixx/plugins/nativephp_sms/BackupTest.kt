package com.blutrixx.plugins.nativephp_sms

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** 2.1: the backup file (sealed, restorable with the passphrase or the recovery key) and the readable export. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupTest : DbTestBase() {
    private val pass = "quiet harbor 42"

    private fun receive(from: String, body: String, ts: Long = System.currentTimeMillis()) =
        ReceivePipeline.receive(context, ReceivePipeline.Delivered(from, body, ts, ts - 500, 1, 0, 1))

    /** A vault with a private conversation, a blocked sender, a spam sender, a setting and a pinned conversation. */
    private fun fill(): String {
        val recovery = VaultStore.setup(context, pass)
        val p = receive("+255700000005", "PRIVATEWORDS here")
        VaultOps.keepPrivate(context, p.threadId!!, "silent")
        VaultOps.block(context, "+255711000432", null)
        receive("+255711000432", "BLOCKEDWORDS here")
        val s = receive("+255689004117", "you won")
        VaultOps.reportSpam(context, s.threadId!!)
        val n = receive("+255700000009", "NORMALWORDS here")
        StoreWrites.saveThread(context, n.threadId!!, mapOf("pinned" to true))
        SmsDb.setSetting(context, "sim.default", "2")
        VaultStore.lock()
        return recovery
    }

    /** As after a reinstall: the vault, the rules, spam, settings and conversations gone. */
    private fun wipeAll() {
        VaultStore.unlock(context, pass)
        VaultOps.wipe(context)
        SmsDb.write(context) { db -> listOf("spam_senders", "settings", "messages", "threads").forEach { db.delete(it, null, null) } }
    }

    @Test fun aBackupMadeWhileLockedRestoresEverythingWithThePassphrase() {
        fill()
        assertFalse(VaultStore.isOpen())
        val bytes = Backup.build(context, "0.2.0", now = 1_000)
        assertEquals(1_000L, Backup.header(bytes).createdAt)
        wipeAll()
        // the import has not run yet: the normal conversation comes back from the phone's store later
        val r = Backup.restore(context, bytes, pass)
        assertEquals(listOf(2, 2), listOf(r.vaultRows, r.rules))
        assertTrue(VaultStore.exists(context))
        assertFalse(VaultStore.isOpen())
        assertTrue(VaultStore.unlock(context, pass))
        assertEquals(setOf("private", "blocked"), VaultOps.conversations(context).map { it.tag }.toSet())
        assertTrue(VaultOps.isPrivate(context, "0700000005"))
        assertEquals(ReceivePipeline.Route.Blocked, receive("+255711000432", "again").route)
        assertEquals(ReceivePipeline.Route.Spam, receive("+255689004117", "claim").route)
        assertEquals("2", SmsDb.setting(context, "sim.default"))
        // the pinned conversation is not here yet: its preference waits, and applies once it exists
        val n = receive("+255700000009", "back again")
        SmsDb.write(context) { Backup.applyThreadPrefs(it) }
        assertEquals(1L, count("SELECT pinned FROM threads WHERE id = '${n.threadId}'"))
        assertEquals(null, SmsDb.setting(context, Backup.PENDING_PREFS))
    }

    @Test fun theRecoveryKeyOpensItAndAWrongPassphraseDoesNot() {
        val recovery = fill()
        val bytes = Backup.build(context)
        wipeAll()
        assertThrows(Backup.WrongKey::class.java) { Backup.restore(context, bytes, "quiet harbor 43") }
        assertFalse(VaultStore.exists(context))
        Backup.restore(context, bytes, recovery.lowercase())
        assertTrue(VaultStore.unlock(context, pass))
    }

    @Test fun itNeedsAVaultAndRefusesToRestoreOverOne() {
        assertThrows(Backup.NoVault::class.java) { Backup.build(context) }
        fill()
        val bytes = Backup.build(context)
        assertThrows(Backup.VaultExists::class.java) { Backup.restore(context, bytes, pass) }
        assertThrows(Backup.NotABackup::class.java) { Backup.header("{\"format\":\"other\"}".toByteArray()) }
    }

    @Test fun nothingReadableInTheFileButTheHeader() {
        fill()
        val text = String(Backup.build(context), Charsets.UTF_8)
        for (w in listOf("PRIVATEWORDS", "BLOCKEDWORDS", "700000005", "711000432", "689004117", "sim.default")) assertFalse(w, text.contains(w))
        val doc = JSONObject(text)
        assertEquals(setOf("format", "version", "created_at", "app_version", "vault", "sealed"), doc.keys().asSequence().toSet())
    }

    @Test fun theReadableExportHasTheNormalTextsAndNeverTheVault() {
        fill()
        val out = ByteArrayOutputStream()
        val n = Backup.writeReadable(context, out)
        val files = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z -> while (true) { val e = z.nextEntry ?: break; files[e.name] = String(z.readBytes(), Charsets.UTF_8) } }
        assertEquals(setOf("messages.json", "messages.txt"), files.keys)
        assertEquals(2, n) // the spam text and the normal one
        assertTrue(files.getValue("messages.txt").contains("NORMALWORDS"))
        for (f in files.values) for (w in listOf("PRIVATEWORDS", "BLOCKEDWORDS")) assertFalse(f.contains(w))
        assertEquals(2, JSONObject(files.getValue("messages.json")).getJSONArray("conversations").length())
    }
}
