package com.blutrixx.plugins.nativephp_sms

import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Base64
import android.util.Log
import com.goterl.lazysodium.interfaces.Box
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * vault.sqlite: blocked texts and texts from private numbers, each sealed to the vault's public key with a libsodium sealed
 * box (X25519 + XSalsa20-Poly1305), so the receiver can seal while the vault is locked and PHP is not running, and only
 * the passcode or the recovery key can open them.
 *
 * Nothing about a vault message (sender, words, thread) is written to sms.sqlite, the system SMS store, change_log, the
 * journal or a notification's text. Only the receipt time is in the clear (`ts`), to order them.
 *
 * Until a vault exists there is no key to seal to, so a match waits in the quarantine file (as before the vault) and is
 * sealed and the file deleted as soon as a key is set.
 *
 * The private key is stored only wrapped: under a key from the passphrase (Argon2id) and under the recovery key (shown
 * once at setup). Unlocking puts it in memory (the session below) until the app locks the vault, or 5 minutes without
 * use; nothing opened is ever written to disk. Besides the sealed record, a row keeps only its time and, for a text sent
 * from the vault or copied there, its state (sending, sent, delivered, failed), its parts and a random client_ref.
 */
object VaultStore {
    private const val TAG = "NativephpSms.Vault"
    const val FILE = "vault.sqlite"
    internal const val QUARANTINE_FILE = "sms_blocked_quarantine.jsonl"

    /** libsodium on a phone; tests set a stand-in (libsodium-android needs its native library). */
    @Volatile internal var crypto: VaultCrypto = SodiumCrypto
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }
    const val IDLE_MS = 5 * 60_000L
    @Volatile private var helper: Helper? = null

    fun file(context: Context): File = context.applicationContext.getDatabasePath(FILE)

    private fun db(context: Context): SQLiteDatabase {
        helper?.let { return it.writableDatabase }
        synchronized(this) {
            val h = helper ?: Helper(context.applicationContext).also { helper = it }
            return h.writableDatabase
        }
    }

    fun publicKey(context: Context): ByteArray? =
        db(context).rawQuery("SELECT public_key FROM vault_meta WHERE id = 1", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) Base64.decode(c.getString(0), Base64.NO_WRAP) else null
        }

    fun hasKey(context: Context): Boolean = publicKey(context) != null

    // -- the vault's keys: setup, unlock, lock --------------------------------------------------------------------------

    private class Session(val secretKey: ByteArray, var usedAt: Long)
    @Volatile private var session: Session? = null

    /** A vault the person set up (not the debug test key): its private key is kept wrapped. */
    fun exists(context: Context): Boolean =
        db(context).rawQuery("SELECT sk_by_pass FROM vault_meta WHERE id = 1", null).use { it.moveToFirst() && !it.isNull(0) }

    /** Open, and used within the last 5 minutes (each use counts). */
    fun isOpen(): Boolean {
        val s = session ?: return false
        if (clock() - s.usedAt > IDLE_MS) {
            lock()
            return false
        }
        return true
    }

    fun lock() {
        session?.secretKey?.fill(0)
        session = null
    }

    /** The open vault's private key, or VaultLocked. */
    internal fun secretKey(): ByteArray {
        if (!isOpen()) throw VaultLocked()
        return session!!.also { it.usedAt = clock() }.secretKey
    }

    class VaultLocked : IllegalStateException("The vault is locked.")
    class VaultExists : IllegalStateException("A vault is already set up.")

    /**
     * Makes the vault: a keypair, the private key wrapped under the passphrase and under a new recovery key. Answers the
     * recovery key, the only time it is ever shown, and leaves the vault open. A debug test key is replaced; what was
     * sealed to it can no longer be opened.
     */
    @Synchronized fun setup(context: Context, passphrase: String): String {
        if (exists(context)) throw VaultExists()
        val c = crypto
        val (pk, sk) = c.keypair()
        val salt = c.random(VaultCrypto.SALT_BYTES)
        val passKey = c.pwhash(passphrase.toByteArray(Charsets.UTF_8), salt, VaultCrypto.OPS, VaultCrypto.MEM)
        val recovery = c.random(RECOVERY_BYTES)
        val b64 = { b: ByteArray -> Base64.encodeToString(b, Base64.NO_WRAP) }
        db(context).insertWithOnConflict("vault_meta", null, ContentValues().apply {
            put("id", 1)
            put("public_key", b64(pk))
            put("test_key", 0)
            put("created_at", clock())
            put("kdf_salt", b64(salt))
            put("kdf_ops", VaultCrypto.OPS)
            put("kdf_mem", VaultCrypto.MEM)
            put("sk_by_pass", b64(c.secretbox(passKey, sk)))
            put("sk_by_recovery", b64(c.secretbox(c.hash(recovery), sk)))
        }, SQLiteDatabase.CONFLICT_REPLACE)
        passKey.fill(0)
        session = Session(sk, clock())
        sealQuarantine(context)
        return RecoveryKey.format(recovery)
    }

    /**
     * Tries what was typed as the passphrase, then as the recovery key. Always runs one Argon2id at the vault's cost,
     * also when there is no vault, so a search and an unlock take the same time. True when the vault is now open.
     */
    @Synchronized fun unlock(context: Context, typed: String): Boolean {
        val c = crypto
        val meta = meta(context)
        val salt = meta?.salt ?: DUMMY_SALT
        val passKey = c.pwhash(typed.toByteArray(Charsets.UTF_8), salt, meta?.ops ?: VaultCrypto.OPS, meta?.mem ?: VaultCrypto.MEM)
        if (meta == null) return false
        val sk = c.secretboxOpen(passKey, meta.skByPass)
            ?: RecoveryKey.parse(typed)?.let { c.secretboxOpen(c.hash(it), meta.skByRecovery) }
        passKey.fill(0)
        if (sk == null) return false
        session = Session(sk, clock())
        return true
    }

    /** A new passphrase for the open vault (the recovery key stays). */
    @Synchronized fun changePassphrase(context: Context, passphrase: String) {
        val sk = secretKey()
        val c = crypto
        val salt = c.random(VaultCrypto.SALT_BYTES)
        val passKey = c.pwhash(passphrase.toByteArray(Charsets.UTF_8), salt, VaultCrypto.OPS, VaultCrypto.MEM)
        val b64 = { b: ByteArray -> Base64.encodeToString(b, Base64.NO_WRAP) }
        db(context).update("vault_meta", ContentValues().apply {
            put("kdf_salt", b64(salt)); put("kdf_ops", VaultCrypto.OPS); put("kdf_mem", VaultCrypto.MEM)
            put("sk_by_pass", b64(c.secretbox(passKey, sk)))
        }, "id = 1", null)
        passKey.fill(0)
    }

    /** Erases every vault message and the keys (VaultOps.wipe also erases the rules). Needs it open. */
    @Synchronized fun wipe(context: Context) {
        secretKey()
        val db = db(context)
        db.delete("vault_messages", null, null)
        db.delete("vault_meta", null, null)
        lock()
    }

    internal class Meta(val publicKey: ByteArray, val salt: ByteArray, val ops: Long, val mem: Long, val skByPass: ByteArray, val skByRecovery: ByteArray) {
        fun toJson(): JSONObject {
            val b64 = { b: ByteArray -> Base64.encodeToString(b, Base64.NO_WRAP) }
            return JSONObject().put("public_key", b64(publicKey)).put("kdf_salt", b64(salt)).put("kdf_ops", ops).put("kdf_mem", mem)
                .put("sk_by_pass", b64(skByPass)).put("sk_by_recovery", b64(skByRecovery))
        }

        companion object {
            fun fromJson(j: JSONObject): Meta {
                val d = { k: String -> Base64.decode(j.getString(k), Base64.NO_WRAP) }
                return Meta(d("public_key"), d("kdf_salt"), j.getLong("kdf_ops"), j.getLong("kdf_mem"), d("sk_by_pass"), d("sk_by_recovery"))
            }
        }
    }

    // -- for a backup (Backup.kt): the wrapped keys and the sealed rows as they are --------------------------------------

    /** The set-up vault's wrapped keys and settings (safe to copy: nothing here opens without the passphrase or key). */
    internal fun exportMeta(context: Context): Meta? = meta(context)

    /** The private key from wrapped keys: what was typed as the passphrase, then as the recovery key. */
    internal fun unwrap(meta: Meta, typed: String): ByteArray? {
        val c = crypto
        val passKey = c.pwhash(typed.toByteArray(Charsets.UTF_8), meta.salt, meta.ops, meta.mem)
        val sk = c.secretboxOpen(passKey, meta.skByPass) ?: RecoveryKey.parse(typed)?.let { c.secretboxOpen(c.hash(it), meta.skByRecovery) }
        passKey.fill(0)
        return sk
    }

    /** Every row as stored: still sealed, with its readable state. */
    internal fun rawRows(context: Context): JSONArray =
        db(context).rawQuery("SELECT id, ts, ciphertext, status, client_ref, parts_total, parts_sent, parts_delivered, parts_failed FROM vault_messages ORDER BY ts", null).use { c ->
            JSONArray().apply {
                while (c.moveToNext()) put(JSONObject().put("id", c.getString(0)).put("ts", c.getLong(1))
                    .put("ciphertext", Base64.encodeToString(c.getBlob(2), Base64.NO_WRAP))
                    .put("status", if (c.isNull(3)) JSONObject.NULL else c.getString(3)).put("client_ref", if (c.isNull(4)) JSONObject.NULL else c.getString(4))
                    .put("parts", JSONArray(listOf(c.getInt(5), c.getInt(6), c.getInt(7), c.getInt(8)))))
            }
        }

    /** A restored vault: its wrapped keys and its sealed rows, replacing whatever was here (a debug test key). */
    @Synchronized internal fun importAll(context: Context, meta: Meta, rows: JSONArray) {
        val db = db(context)
        db.beginTransaction()
        try {
            db.delete("vault_messages", null, null)
            db.delete("vault_meta", null, null)
            db.insertOrThrow("vault_meta", null, ContentValues().apply {
                val m = meta.toJson()
                put("id", 1); put("public_key", m.getString("public_key")); put("test_key", 0); put("created_at", clock())
                put("kdf_salt", m.getString("kdf_salt")); put("kdf_ops", meta.ops); put("kdf_mem", meta.mem)
                put("sk_by_pass", m.getString("sk_by_pass")); put("sk_by_recovery", m.getString("sk_by_recovery"))
            })
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                val parts = r.getJSONArray("parts")
                db.insertOrThrow("vault_messages", null, ContentValues().apply {
                    put("id", r.getString("id")); put("ts", r.getLong("ts"))
                    put("ciphertext", Base64.decode(r.getString("ciphertext"), Base64.NO_WRAP))
                    if (!r.isNull("status")) put("status", r.getString("status"))
                    if (!r.isNull("client_ref")) put("client_ref", r.getString("client_ref"))
                    put("parts_total", parts.getInt(0)); put("parts_sent", parts.getInt(1)); put("parts_delivered", parts.getInt(2)); put("parts_failed", parts.getInt(3))
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        lock()
    }

    private fun meta(context: Context): Meta? =
        db(context).rawQuery("SELECT public_key, kdf_salt, kdf_ops, kdf_mem, sk_by_pass, sk_by_recovery FROM vault_meta WHERE id = 1 AND sk_by_pass IS NOT NULL", null).use { c ->
            if (!c.moveToFirst()) return null
            val d = { i: Int -> Base64.decode(c.getString(i), Base64.NO_WRAP) }
            Meta(d(0), d(1), c.getLong(2), c.getLong(3), d(4), d(5))
        }

    private const val RECOVERY_BYTES = 16
    private val DUMMY_SALT = ByteArray(VaultCrypto.SALT_BYTES) { 7 }

    // -- the rows ---------------------------------------------------------------------------------------------------------

    data class Row(val id: String, val ts: Long, val record: JSONObject, val status: String?, val clientRef: String?)

    /** Every row opened, newest first. Rows sealed to another key (an old debug test key) are skipped. */
    fun rows(context: Context): List<Row> {
        val sk = secretKey()
        val pk = publicKey(context) ?: return emptyList()
        return db(context).rawQuery("SELECT id, ts, ciphertext, status, client_ref FROM vault_messages ORDER BY ts DESC, id DESC", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val plain = crypto.sealOpen(pk, sk, c.getBlob(2)) ?: continue
                    val record = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull() ?: continue
                    add(Row(c.getString(0), c.getLong(1), record, if (c.isNull(3)) null else c.getString(3), if (c.isNull(4)) null else c.getString(4)))
                }
            }
        }
    }

    fun delete(context: Context, ids: Collection<String>) {
        secretKey()
        val db = db(context)
        ids.forEach { db.delete("vault_messages", "id = ?", arrayOf(it)) }
    }

    /**
     * One part's result on a vault text (sent from the vault, or the vault's copy of a text to a private number), by its
     * client_ref: failed as soon as a part fails, sent when every part is, delivered when every part is. Returns the new
     * state when it moved. Needs no key: only the readable state changes.
     */
    fun countPart(context: Context, clientRef: String, event: String, ok: Boolean): String? {
        val db = db(context)
        db.rawQuery("SELECT id, status, parts_total, parts_sent, parts_delivered, parts_failed FROM vault_messages WHERE client_ref = ?", arrayOf(clientRef)).use { c ->
            if (!c.moveToFirst()) return null
            val id = c.getString(0); val status = c.getString(1) ?: "queued"; val total = c.getInt(2).coerceAtLeast(1)
            var sent = c.getInt(3); var delivered = c.getInt(4); var failed = c.getInt(5)
            when {
                event == "sent" && ok -> sent++
                event == "delivered" && ok -> delivered++
                else -> failed++
            }
            val next = when {
                failed > 0 -> "failed"
                delivered >= total -> "delivered"
                sent >= total && status != "delivered" -> "sent"
                else -> status
            }
            db.update("vault_messages", ContentValues().apply {
                put("parts_sent", sent); put("parts_delivered", delivered); put("parts_failed", failed); put("status", next)
            }, "id = ?", arrayOf(id))
            return if (next != status) next else null
        }
    }

    /** Sets the public key everything is sealed to (vault setup in 1.3; the debug-only test key before that). */
    fun setPublicKey(context: Context, key: ByteArray, test: Boolean) {
        require(key.size == Box.PUBLICKEYBYTES) { "a vault public key is ${Box.PUBLICKEYBYTES} bytes" }
        db(context).insertWithOnConflict("vault_meta", null, ContentValues().apply {
            put("id", 1)
            put("public_key", Base64.encodeToString(key, Base64.NO_WRAP))
            put("test_key", if (test) 1 else 0)
            put("created_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        sealQuarantine(context)
    }

    fun count(context: Context): Int = db(context).rawQuery("SELECT COUNT(*) FROM vault_messages", null).use { it.moveToFirst(); it.getInt(0) }

    /**
     * Seals one message into the vault. [record] carries everything (sender, direction, words, why); only [ts] stays
     * readable. Without a key it goes to the quarantine file instead. Returns true when sealed, false when quarantined.
     */
    fun put(context: Context, record: JSONObject, ts: Long, status: String? = null, clientRef: String? = null, parts: Int = 1): Boolean {
        val key = publicKey(context)
        if (key == null) {
            quarantine(context, record.put("at", ts))
            return false
        }
        db(context).insertOrThrow("vault_messages", null, ContentValues().apply {
            put("id", Ulid.next(ts))
            put("ts", ts)
            put("ciphertext", crypto.seal(key, record.toString().toByteArray(Charsets.UTF_8)))
            if (status != null) put("status", status)
            if (clientRef != null) put("client_ref", clientRef)
            put("parts_total", parts.coerceAtLeast(1))
        })
        return true
    }

    internal fun dropQuarantine(context: Context) {
        File(context.applicationContext.filesDir, QUARANTINE_FILE).delete()
    }

    private fun quarantine(context: Context, record: JSONObject) {
        File(context.applicationContext.filesDir, QUARANTINE_FILE).appendText(record.toString() + "\n")
    }

    /** What waited in the quarantine file is sealed into the vault and the file deleted, once a key exists. */
    @Synchronized fun sealQuarantine(context: Context) {
        val file = File(context.applicationContext.filesDir, QUARANTINE_FILE)
        if (!file.exists()) return
        val key = publicKey(context) ?: return
        val db = db(context)
        db.beginTransaction()
        try {
            file.readLines().filter { it.isNotBlank() }.forEach { line ->
                try {
                    val record = JSONObject(line)
                    val ts = record.optLong("at", System.currentTimeMillis())
                    db.insertOrThrow("vault_messages", null, ContentValues().apply {
                        put("id", Ulid.next(ts)); put("ts", ts)
                        put("ciphertext", crypto.seal(key, record.toString().toByteArray(Charsets.UTF_8)))
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "skipping an unreadable quarantine line", e)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        file.delete()
    }

    fun isDebuggable(context: Context): Boolean = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    internal fun closeForTests() {
        synchronized(this) {
            helper?.close()
            helper = null
            lock()
        }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, FILE, null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            // vault_meta's other columns (the private key wrapped by the passcode and by the recovery key, Argon2id
            // settings) arrive with vault setup in 1.3, as migration 2.
            db.execSQL("CREATE TABLE vault_meta (id INTEGER PRIMARY KEY CHECK (id = 1), public_key TEXT NOT NULL, test_key INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE vault_messages (id TEXT PRIMARY KEY, ts INTEGER NOT NULL, nonce BLOB, ciphertext BLOB NOT NULL)")
            db.execSQL("CREATE INDEX vault_messages_ts ON vault_messages (ts)")
            migrate2(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) migrate2(db)
        }

        override fun onOpen(db: SQLiteDatabase) {
            super.onOpen(db)
        }

        companion object {
            /** 2: the wrapped private key and its Argon2id settings; a readable state for texts sent from the vault. */
            fun migrate2(db: SQLiteDatabase) {
                listOf(
                    "ALTER TABLE vault_meta ADD COLUMN kdf_salt TEXT",
                    "ALTER TABLE vault_meta ADD COLUMN kdf_ops INTEGER",
                    "ALTER TABLE vault_meta ADD COLUMN kdf_mem INTEGER",
                    "ALTER TABLE vault_meta ADD COLUMN sk_by_pass TEXT",
                    "ALTER TABLE vault_meta ADD COLUMN sk_by_recovery TEXT",
                    "ALTER TABLE vault_messages ADD COLUMN status TEXT",
                    "ALTER TABLE vault_messages ADD COLUMN client_ref TEXT",
                    "ALTER TABLE vault_messages ADD COLUMN parts_total INTEGER NOT NULL DEFAULT 1",
                    "ALTER TABLE vault_messages ADD COLUMN parts_sent INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE vault_messages ADD COLUMN parts_delivered INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE vault_messages ADD COLUMN parts_failed INTEGER NOT NULL DEFAULT 0",
                    "CREATE INDEX vault_messages_ref ON vault_messages (client_ref)",
                ).forEach { db.execSQL(it) }
            }
        }
    }
}
