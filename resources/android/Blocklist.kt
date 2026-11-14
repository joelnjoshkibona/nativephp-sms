package com.blutrixx.plugins.nativephp_sms

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The blocklist and the private numbers (sms.sqlite `blocklist`). Each pattern is encrypted under a key in the Android
 * Keystore, so the file alone does not say whom you block or keep private, and the receiver can still match while the
 * vault is locked (the key needs no passcode).
 */
object Blocklist {
    data class Rule(val id: String, val kind: String, val action: String, val notify: String?, val pattern: String)

    /** Encrypts patterns. The Keystore on a phone; tests set a plain AES key (Robolectric has no Keystore). */
    interface PatternCipher {
        fun encrypt(plain: String): String
        fun decrypt(stored: String): String
    }

    @Volatile internal var cipher: PatternCipher = KeystoreCipher

    fun rules(context: Context): List<Rule> {
        migrateFromPrefs(context)
        return SmsDb.get(context).rawQuery("SELECT id, kind, action, notify, pattern FROM blocklist", null).use { c ->
            val out = ArrayList<Rule>()
            while (c.moveToNext()) {
                // a pattern the key can no longer open (the Keystore was reset) is skipped rather than blocking everything
                val pattern = runCatching { cipher.decrypt(c.getString(4)) }.getOrNull() ?: continue
                out.add(Rule(c.getString(0), c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getString(3), pattern))
            }
            out
        }
    }

    fun size(context: Context): Int {
        migrateFromPrefs(context)
        return SmsDb.get(context).rawQuery("SELECT COUNT(*) FROM blocklist", null).use { it.moveToFirst(); it.getInt(0) }
    }

    fun add(db: SQLiteDatabase, kind: String, action: String, notify: String?, pattern: String, now: Long = System.currentTimeMillis()): String {
        val id = Ulid.next(now)
        db.insertOrThrow("blocklist", null, ContentValues().apply {
            put("id", id); put("kind", kind); put("action", action)
            if (notify != null) put("notify", notify)
            put("pattern", cipher.encrypt(pattern)); put("created_at", now)
        })
        return id
    }

    /** Sms.SetBlocklist: the blocked numbers become exactly these (prefixes, keywords and private numbers are kept). */
    fun replaceNumbers(context: Context, numbers: Collection<String>) {
        migrateFromPrefs(context)
        SmsDb.write(context) { db ->
            db.delete("blocklist", "kind = 'number' AND action = 'block'", null)
            numbers.map { SmsPrefs.normalize(it) }.filter { it.isNotEmpty() }.toSet().forEach { add(db, "number", "block", null, it) }
        }
    }

    /** Before sms.sqlite the blocked numbers were a plain set in SharedPreferences: moved in once, then removed there. */
    @Synchronized private fun migrateFromPrefs(context: Context) {
        if (SmsDb.setting(context, "blocklist.migrated") != null) return
        val old = SmsPrefs.takeLegacyBlocklist(context)
        SmsDb.write(context) { db ->
            old.forEach { add(db, "number", "block", null, it) }
            SmsDb.setSetting(db, "blocklist.migrated", System.currentTimeMillis().toString())
        }
    }

    private object KeystoreCipher : PatternCipher {
        private const val ALIAS = "nativephp_sms_blocklist"

        private fun key(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
            }.generateKey()
        }

        override fun encrypt(plain: String): String = aesEncrypt(key(), plain)
        override fun decrypt(stored: String): String = aesDecrypt(key(), stored)
    }

    /** iv (12 bytes) then the AES-GCM ciphertext, base64. */
    internal fun aesEncrypt(key: SecretKey, plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    internal fun aesDecrypt(key: SecretKey, stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12)) }
        return String(c.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }
}
