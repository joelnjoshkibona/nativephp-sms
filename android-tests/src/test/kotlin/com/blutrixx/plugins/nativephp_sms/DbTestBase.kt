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

    /**
     * Robolectric runs no libsodium. A stand-in with the same rules: a sealed box opens only with the private key of its
     * public key, a wrapped key only with its wrapping key; Argon2id is SHA-256 here, and counted.
     */
    object TestCrypto : VaultCrypto {
        private val rnd = java.security.SecureRandom()
        var pwhashCalls = 0
        private fun sha(vararg parts: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").apply { parts.forEach { update(it) } }.digest()
        override fun keypair(): Pair<ByteArray, ByteArray> { val sk = random(32); return sha(sk) to sk }
        override fun seal(publicKey: ByteArray, message: ByteArray) = "sealed:".toByteArray() + publicKey + message.reversedArray()
        override fun sealOpen(publicKey: ByteArray, secretKey: ByteArray, sealed: ByteArray): ByteArray? {
            if (!sha(secretKey).contentEquals(publicKey) || !sealed.copyOfRange(7, 39).contentEquals(publicKey)) return null
            return sealed.copyOfRange(39, sealed.size).reversedArray()
        }
        override fun pwhash(passphrase: ByteArray, salt: ByteArray, ops: Long, mem: Long): ByteArray { pwhashCalls++; return sha(passphrase, salt) }
        override fun secretbox(key: ByteArray, message: ByteArray): ByteArray {
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES")) }
            return c.iv + c.doFinal(message)
        }
        override fun secretboxOpen(key: ByteArray, boxed: ByteArray): ByteArray? = runCatching {
            javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, boxed, 0, 12)) }.doFinal(boxed, 12, boxed.size - 12)
        }.getOrNull()
        override fun hash(input: ByteArray) = sha(input)
        override fun random(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
        /** What a stand-in box holds, without the key (to check what was sealed). */
        fun peek(sealed: ByteArray): String = String(sealed.copyOfRange(39, sealed.size).reversedArray(), Charsets.UTF_8)
    }

    /** A radio that splits like Android (153 per part when split) and keeps what it was asked to send. */
    object TestRadio : SmsSender.Radio {
        val sent = mutableListOf<Pair<String, List<String>>>()
        override fun divide(context: Context, subscriptionId: Int, body: String) =
            ArrayList(if (body.length <= 160) listOf(body) else body.chunked(153))
        override fun send(context: Context, subscriptionId: Int, to: String, parts: ArrayList<String>, sent: ArrayList<android.app.PendingIntent>, delivered: ArrayList<android.app.PendingIntent>) {
            this.sent += to to parts
        }
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
        VaultStore.crypto = TestCrypto
        TestCrypto.pwhashCalls = 0
        SmsStore.onWrite = null
        SmsSender.radio = TestRadio
        TestRadio.sent.clear()
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
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' AND name NOT LIKE 'messages_fts%'", null)
            .use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
        return tables.joinToString("\n") { t ->
            db.rawQuery("SELECT * FROM $t", null).use { c ->
                buildString { while (c.moveToNext()) for (i in 0 until c.columnCount) if (c.getType(i) == android.database.Cursor.FIELD_TYPE_STRING) append(c.getString(i)).append('|') }
            }
        }
    }
}
