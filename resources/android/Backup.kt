package com.blutrixx.plugins.nativephp_sms

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The backup file (`.smsbackup`) and the readable export.
 *
 * A backup is one JSON document: a readable header (format, version, when, the app version) and the vault's wrapped
 * keys (its public key, Argon2id settings, and the private key wrapped under the passphrase and under the recovery key:
 * none of it opens without one of them), then one sealed box to the vault's public key holding, gzipped:
 *  - the vault's rows, still sealed as they are stored;
 *  - the rules (blocked and private numbers), taken out of this phone's Keystore encryption;
 *  - the spam senders, the app's settings, and each conversation's preferences (folder, pinned, muted, colour,
 *    wallpaper) by its participants' key.
 * Sealing needs only the public key, so a backup is made while the vault is locked. Opening it needs the passphrase or
 * the recovery key. Normal texts are not in it: they are in the phone's own SMS store.
 *
 * The readable export is a zip of messages.json and messages.txt: the normal conversations of sms.sqlite, never the vault.
 */
object Backup {
    const val FORMAT = "sms-messenger-backup"
    const val VERSION = 1
    /** Settings that describe this install, not the person's choices: never carried to another one. */
    private val LOCAL_SETTINGS = listOf("import.", "backup.", "restore.")
    const val PENDING_PREFS = "restore.thread-prefs"

    class NoVault : IllegalStateException("Set up the vault first: a backup is sealed to it.")
    class NotABackup : IllegalArgumentException("This is not an SMS Messenger backup.")
    class WrongKey : IllegalArgumentException("The passphrase or recovery key does not open this backup.")
    class VaultExists : IllegalStateException("A vault is already set up here. Wipe it first to restore another.")

    data class Header(val createdAt: Long, val appVersion: String)

    // -- making one ------------------------------------------------------------------------------------------------------

    fun build(context: Context, appVersion: String = "", now: Long = System.currentTimeMillis()): ByteArray {
        val meta = VaultStore.exportMeta(context) ?: throw NoVault()
        val payload = JSONObject()
            .put("vault_rows", VaultStore.rawRows(context))
            .put("rules", JSONArray(Blocklist.rules(context).map { JSONObject().put("kind", it.kind).put("action", it.action).put("notify", it.notify ?: JSONObject.NULL).put("pattern", it.pattern) }))
            .put("spam_senders", rows(context, "SELECT address, reported_at FROM spam_senders") { c -> JSONObject().put("address", c.getString(0)).put("reported_at", c.getLong(1)) })
            .put("settings", rows(context, "SELECT key, value FROM settings") { c -> if (LOCAL_SETTINGS.any { c.getString(0).startsWith(it) }) null else JSONObject().put("key", c.getString(0)).put("value", if (c.isNull(1)) JSONObject.NULL else c.getString(1)) })
            .put("threads", rows(context, "SELECT participants_key, folder, pinned, muted, color, wallpaper FROM threads WHERE folder != 'inbox' OR pinned = 1 OR muted = 1 OR color IS NOT NULL OR wallpaper IS NOT NULL") { c ->
                JSONObject().put("key", c.getString(0)).put("folder", c.getString(1)).put("pinned", c.getInt(2) == 1).put("muted", c.getInt(3) == 1)
                    .put("color", if (c.isNull(4)) JSONObject.NULL else c.getString(4)).put("wallpaper", if (c.isNull(5)) JSONObject.NULL else c.getString(5))
            })
        val sealed = VaultStore.crypto.seal(meta.publicKey, gzip(payload.toString().toByteArray(Charsets.UTF_8)))
        val doc = JSONObject()
            .put("format", FORMAT).put("version", VERSION).put("created_at", now).put("app_version", appVersion)
            .put("vault", meta.toJson())
            .put("sealed", Base64.encodeToString(sealed, Base64.NO_WRAP))
        return doc.toString().toByteArray(Charsets.UTF_8)
    }

    fun fileName(now: Long = System.currentTimeMillis()) = "sms-messenger-${SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(now))}.smsbackup"

    fun readableName(now: Long = System.currentTimeMillis()) = "sms-messenger-texts-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))}.zip"

    // -- reading one -----------------------------------------------------------------------------------------------------

    fun header(bytes: ByteArray): Header {
        val doc = parse(bytes)
        return Header(doc.getLong("created_at"), doc.optString("app_version"))
    }

    data class Restored(val vaultRows: Int, val rules: Int, val settings: Int, val conversations: Int)

    /**
     * Restores a backup into an app with no vault: the vault (same keys: the same passphrase and recovery key open it),
     * the rules (encrypted again under this phone's Keystore), the spam senders and settings. Conversation preferences
     * apply to conversations already here; the rest wait until the import of the phone's texts has made them.
     * Costs one Argon2id. Leaves the vault locked.
     */
    fun restore(context: Context, bytes: ByteArray, typed: String): Restored {
        if (VaultStore.exists(context)) throw VaultExists()
        val doc = parse(bytes)
        val meta = VaultStore.Meta.fromJson(doc.getJSONObject("vault"))
        val sk = VaultStore.unwrap(meta, typed.trim()) ?: throw WrongKey()
        val opened = VaultStore.crypto.sealOpen(meta.publicKey, sk, Base64.decode(doc.getString("sealed"), Base64.NO_WRAP))
        sk.fill(0)
        opened ?: throw WrongKey()
        val payload = JSONObject(String(gunzip(opened), Charsets.UTF_8))

        val vaultRows = payload.getJSONArray("vault_rows")
        VaultStore.importAll(context, meta, vaultRows)
        val rules = payload.getJSONArray("rules")
        val spam = payload.getJSONArray("spam_senders")
        val settings = payload.getJSONArray("settings")
        val threads = payload.getJSONArray("threads")
        SmsDb.write(context) { db ->
            db.delete("blocklist", null, null)
            for (i in 0 until rules.length()) {
                val r = rules.getJSONObject(i)
                Blocklist.add(db, r.getString("kind"), r.getString("action"), if (r.isNull("notify")) null else r.getString("notify"), r.getString("pattern"))
            }
            for (i in 0 until spam.length()) {
                val r = spam.getJSONObject(i)
                db.insertWithOnConflict("spam_senders", null, ContentValues().apply { put("address", r.getString("address")); put("reported_at", r.getLong("reported_at")) }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            for (i in 0 until settings.length()) {
                val r = settings.getJSONObject(i)
                SmsDb.setSetting(db, r.getString("key"), if (r.isNull("value")) null else r.getString("value"))
            }
            SmsDb.setSetting(db, PENDING_PREFS, threads.toString())
            applyThreadPrefs(db)
        }
        VaultStore.sealQuarantine(context)
        return Restored(vaultRows.length(), rules.length(), settings.length(), threads.length())
    }

    /**
     * A restored backup's conversation preferences, applied to the conversations that exist now; what is left waits for
     * the next call (the import calls this when it is done). Writes through the thread rows, so change_log follows.
     */
    fun applyThreadPrefs(db: SQLiteDatabase) {
        val waiting = SmsDb.setting(db, PENDING_PREFS)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return
        val left = JSONArray()
        val now = System.currentTimeMillis()
        for (i in 0 until waiting.length()) {
            val p = waiting.getJSONObject(i)
            val n = db.update("threads", ContentValues().apply {
                put("folder", p.getString("folder")); put("pinned", if (p.getBoolean("pinned")) 1 else 0); put("muted", if (p.getBoolean("muted")) 1 else 0)
                if (p.isNull("color")) putNull("color") else put("color", p.getString("color"))
                if (p.isNull("wallpaper")) putNull("wallpaper") else put("wallpaper", p.getString("wallpaper"))
                put("updated_at", now)
            }, "participants_key = ?", arrayOf(p.getString("key")))
            if (n == 0) left.put(p)
        }
        SmsDb.setSetting(db, PENDING_PREFS, if (left.length() == 0) null else left.toString())
    }

    // -- the readable export ---------------------------------------------------------------------------------------------

    /** messages.json and messages.txt in a zip, written to `out`. Answers how many messages. The vault is never read. */
    fun writeReadable(context: Context, out: OutputStream): Int {
        val db = SmsDb.get(context)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val conversations = JSONArray()
        val text = StringBuilder()
        var count = 0
        db.rawQuery("SELECT id, addresses, folder FROM threads ORDER BY COALESCE(last_message_at, 0) DESC", null).use { t ->
            while (t.moveToNext()) {
                val addresses = JSONArray(t.getString(1))
                val first = addresses.optString(0, "")
                val name = ContactSync.nameFor(context, first)
                val msgs = JSONArray()
                text.append("== ").append(name?.let { "$it ($first)" } ?: (0 until addresses.length()).joinToString(", ") { addresses.getString(it) })
                    .append(if (t.getString(2) != "inbox") " [${t.getString(2)}]" else "").append(" ==\n")
                db.rawQuery("SELECT direction, address, body, ts, status FROM messages WHERE thread_id = ? ORDER BY ts", arrayOf(t.getString(0))).use { m ->
                    while (m.moveToNext()) {
                        val out1 = m.getString(0) == "out"
                        msgs.put(JSONObject().put("direction", m.getString(0)).put("address", m.getString(1)).put("body", m.getString(2)).put("ts", m.getLong(3)).put("status", m.getString(4)))
                        text.append(fmt.format(Date(m.getLong(3)))).append("  ").append(if (out1) "You" else (name ?: m.getString(1))).append(": ").append(m.getString(2)).append('\n')
                        count++
                    }
                }
                text.append('\n')
                conversations.put(JSONObject().put("addresses", addresses).put("name", name ?: JSONObject.NULL).put("folder", t.getString(2)).put("messages", msgs))
            }
        }
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("messages.json"))
            zip.write(JSONObject().put("exported_at", System.currentTimeMillis()).put("conversations", conversations).toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("messages.txt"))
            zip.write(text.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return count
    }

    // -- helpers ---------------------------------------------------------------------------------------------------------

    private fun parse(bytes: ByteArray): JSONObject {
        val doc = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: throw NotABackup()
        if (doc.optString("format") != FORMAT || doc.optInt("version") != VERSION || !doc.has("vault") || !doc.has("sealed")) throw NotABackup()
        return doc
    }

    private fun rows(context: Context, sql: String, map: (android.database.Cursor) -> JSONObject?): JSONArray =
        SmsDb.get(context).rawQuery(sql, null).use { c -> JSONArray().apply { while (c.moveToNext()) map(c)?.let { put(it) } } }

    private fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    private fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
}
