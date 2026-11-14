package com.blutrixx.plugins.nativephp_sms

import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Base64
import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box
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
 * sealed and the file deleted as soon as a key is set. Vault setup (passcode, recovery key) is milestone 1.3.
 */
object VaultStore {
    private const val TAG = "NativephpSms.Vault"
    const val FILE = "vault.sqlite"
    internal const val QUARANTINE_FILE = "sms_blocked_quarantine.jsonl"

    /** Seals bytes to a public key. libsodium on a phone; tests set another (libsodium-android needs its native library). */
    interface Sealer {
        fun seal(publicKey: ByteArray, message: ByteArray): ByteArray
    }

    @Volatile internal var sealer: Sealer = SodiumSealer
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
    fun put(context: Context, record: JSONObject, ts: Long): Boolean {
        val key = publicKey(context)
        if (key == null) {
            quarantine(context, record.put("at", ts))
            return false
        }
        db(context).insertOrThrow("vault_messages", null, ContentValues().apply {
            put("id", Ulid.next(ts))
            put("ts", ts)
            put("ciphertext", sealer.seal(key, record.toString().toByteArray(Charsets.UTF_8)))
        })
        return true
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
                        put("ciphertext", sealer.seal(key, record.toString().toByteArray(Charsets.UTF_8)))
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
        }
    }

    private object SodiumSealer : Sealer {
        private val sodium by lazy { LazySodiumAndroid(SodiumAndroid()) }

        override fun seal(publicKey: ByteArray, message: ByteArray): ByteArray {
            val out = ByteArray(message.size + Box.SEALBYTES)
            check((sodium as Box.Native).cryptoBoxSeal(out, message, message.size.toLong(), publicKey)) { "crypto_box_seal failed" }
            return out
        }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, FILE, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            // vault_meta's other columns (the private key wrapped by the passcode and by the recovery key, Argon2id
            // settings) arrive with vault setup in 1.3, as migration 2.
            db.execSQL("CREATE TABLE vault_meta (id INTEGER PRIMARY KEY CHECK (id = 1), public_key TEXT NOT NULL, test_key INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE vault_messages (id TEXT PRIMARY KEY, ts INTEGER NOT NULL, nonce BLOB, ciphertext BLOB NOT NULL)")
            db.execSQL("CREATE INDEX vault_messages_ts ON vault_messages (ts)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }
}
