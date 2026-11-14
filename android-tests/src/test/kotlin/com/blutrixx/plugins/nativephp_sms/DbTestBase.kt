package com.blutrixx.plugins.nativephp_sms

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import java.io.File
import javax.crypto.KeyGenerator

/** Each test starts on an empty sms.sqlite and vault.sqlite, a plain AES key for the blocklist and a readable test sealer. */
abstract class DbTestBase {
    protected lateinit var context: Context

    /** Robolectric runs no libsodium: "sealed:" + the bytes reversed, so a test can open what it sealed. */
    object TestSealer : VaultStore.Sealer {
        override fun seal(publicKey: ByteArray, message: ByteArray): ByteArray = "sealed:".toByteArray() + message.reversedArray()
        fun open(sealed: ByteArray): String = String(sealed.copyOfRange(7, sealed.size).reversedArray(), Charsets.UTF_8)
    }

    @Before fun openFresh() {
        context = ApplicationProvider.getApplicationContext()
        SmsDb.closeForTests()
        VaultStore.closeForTests()
        SmsDb.file(context).parentFile?.listFiles()?.forEach { it.delete() }
        VaultStore.file(context).parentFile?.listFiles()?.forEach { it.delete() }
        File(context.filesDir, VaultStore.QUARANTINE_FILE).delete()
        EventJournal.resetForTests()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        Blocklist.cipher = object : Blocklist.PatternCipher {
            override fun encrypt(plain: String) = Blocklist.aesEncrypt(key, plain)
            override fun decrypt(stored: String) = Blocklist.aesDecrypt(key, stored)
        }
        VaultStore.sealer = TestSealer
    }

    @After fun close() {
        SmsDb.closeForTests()
        VaultStore.closeForTests()
    }

    protected fun count(sql: String, vararg args: String): Long =
        SmsDb.get(context).rawQuery(sql, args).use { it.moveToFirst(); it.getLong(0) }

    /** Every text column of every table in sms.sqlite, joined: to prove something is NOT anywhere in it. */
    protected fun everythingInSmsDb(): String {
        val db = SmsDb.get(context)
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%'", null)
            .use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
        return tables.joinToString("\n") { t ->
            db.rawQuery("SELECT * FROM $t", null).use { c ->
                buildString { while (c.moveToNext()) for (i in 0 until c.columnCount) if (c.getType(i) == android.database.Cursor.FIELD_TYPE_STRING) append(c.getString(i)).append('|') }
            }
        }
    }
}
