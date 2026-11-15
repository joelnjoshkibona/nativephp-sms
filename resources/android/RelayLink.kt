package com.blutrixx.plugins.nativephp_sms

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.util.Base64
import org.json.JSONObject

/**
 * This phone's link to the relay and the browsers paired through it.
 *
 * The phone has one relay identity (its device id and token, and an X25519 keypair) kept in settings, the token and
 * the private key wrapped under this phone's Keystore key like the blocklist's patterns. Each paired browser is a row
 * of paired_devices with its public key and the two session keys (also wrapped). The QR code's secret is used once,
 * to make the session keys, and never stored.
 */
object RelayLink {
    class BadCode : IllegalArgumentException("This is not a pairing code from SMS Messenger on the web.")
    class KeyMismatch : IllegalStateException("The server answered with a different browser than the code shows.")

    data class Identity(val url: String, val appKey: String, val deviceId: String, val token: String, val publicKey: ByteArray, val privateKey: ByteArray)

    data class Browser(val id: String, val name: String, val publicKey: String, val rx: ByteArray, val tx: ByteArray, val pushSeq: Long, val pairedAt: Long, val lastSeen: Long?)

    private const val URL = "relay.url"
    private const val APP_KEY = "relay.app_key"
    private const val DEVICE = "relay.device"
    private const val TOKEN = "relay.token"
    private const val PK = "relay.pk"
    private const val SK = "relay.sk"

    private fun wrap(bytes: ByteArray) = Blocklist.cipher.encrypt(Base64.encodeToString(bytes, Base64.NO_WRAP))
    private fun unwrap(stored: String) = Base64.decode(Blocklist.cipher.decrypt(stored), Base64.NO_WRAP)

    fun identity(context: Context): Identity? {
        val db = SmsDb.get(context)
        val url = SmsDb.setting(db, URL) ?: return null
        val device = SmsDb.setting(db, DEVICE) ?: return null
        return runCatching {
            Identity(
                url, SmsDb.setting(db, APP_KEY) ?: "", device,
                Blocklist.cipher.decrypt(SmsDb.setting(db, TOKEN)!!),
                Base64.decode(SmsDb.setting(db, PK)!!, Base64.NO_WRAP),
                unwrap(SmsDb.setting(db, SK)!!),
            )
        }.getOrNull() // the Keystore key is gone (a restore to a new phone): pair again
    }

    fun browsers(context: Context): List<Browser> =
        SmsDb.get(context).rawQuery("SELECT id, name, public_key, rx_key, tx_key, push_seq, paired_at, last_seen FROM paired_devices WHERE rx_key IS NOT NULL ORDER BY paired_at", null).use { c ->
            val out = ArrayList<Browser>()
            while (c.moveToNext()) {
                runCatching {
                    Browser(c.getString(0), c.getString(1), c.getString(2), unwrap(c.getString(3)), unwrap(c.getString(4)), c.getLong(5), c.getLong(6), if (c.isNull(7)) null else c.getLong(7))
                }.getOrNull()?.let { out.add(it) }
            }
            out
        }

    fun browser(context: Context, id: String): Browser? = browsers(context).firstOrNull { it.id == id }

    /**
     * Pairs with the browser whose QR code was scanned: claims the code (reusing this phone's identity when it has
     * one), checks the server's answer against the browser key in the code, and keeps the session keys.
     */
    fun pair(context: Context, code: String, name: String = deviceName(), now: Long = System.currentTimeMillis()): Browser {
        val qr = parse(code) ?: throw BadCode()
        val crypto = SyncCrypto.current
        val http = RelayHttp.current
        val old = identity(context)?.takeIf { it.url == qr.url }
        val (pk, sk) = old?.let { it.publicKey to it.privateKey } ?: crypto.keypair()

        val answer = http.call(
            qr.url, "POST", "/relay/pairings/claim",
            JSONObject().put("token", qr.token).put("name", name).put("public_key", Base64.encodeToString(pk, Base64.NO_WRAP)),
            old?.token,
        )
        val peer = answer.getJSONObject("peer")
        val webKey = Base64.decode(peer.getString("public_key"), Base64.NO_WRAP)
        if (peer.getString("id") != qr.web || !webKey.contentEquals(decode(qr.pk))) throw KeyMismatch()

        val (rx, tx) = crypto.sessionKeys(pk, sk, webKey, decode(qr.secret))
        val deviceId = answer.getJSONObject("device").getString("id")
        val token = answer.optString("token").takeIf { it.isNotEmpty() && it != "null" } ?: old?.token
            ?: throw IllegalStateException("The relay did not give this phone a token.")

        SmsDb.write(context) { db ->
            SmsDb.setSetting(db, URL, qr.url)
            SmsDb.setSetting(db, APP_KEY, qr.key)
            SmsDb.setSetting(db, DEVICE, deviceId)
            SmsDb.setSetting(db, TOKEN, Blocklist.cipher.encrypt(token))
            SmsDb.setSetting(db, PK, Base64.encodeToString(pk, Base64.NO_WRAP))
            SmsDb.setSetting(db, SK, wrap(sk))
            db.insertWithOnConflict("paired_devices", null, ContentValues().apply {
                put("id", qr.web); put("name", peer.optString("name", "Browser")); put("public_key", peer.getString("public_key"))
                put("paired_at", now); put("web_access_enabled", 1); put("rx_key", wrap(rx)); put("tx_key", wrap(tx)); put("push_seq", 0)
            }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        }
        EventJournal.append(context, "relay_paired", JSONObject().put("browser", qr.web.takeLast(4)).put("new_identity", old == null))
        return browser(context, qr.web)!!
    }

    /** Removes a browser here and asks the relay to revoke it; the browser is told and wipes itself. */
    fun unpair(context: Context, id: String) {
        identity(context)?.let { me ->
            runCatching { RelayHttp.current.call(me.url, "POST", "/relay/devices/$id/revoke", null, me.token) }
        }
        forget(context, id)
    }

    /** Drops a browser here only (it was revoked elsewhere). With no browser left, the relay identity goes too. */
    fun forget(context: Context, id: String) {
        SmsDb.write(context) { db ->
            db.delete("paired_devices", "id = ?", arrayOf(id))
        }
        EventJournal.append(context, "relay_unpaired", JSONObject().put("browser", id.takeLast(4)))
        if (browsers(context).isEmpty()) forgetIdentity(context)
    }

    /** This phone itself was revoked, or has no browsers left. */
    fun forgetIdentity(context: Context) {
        SmsDb.write(context) { db ->
            listOf(URL, APP_KEY, DEVICE, TOKEN, PK, SK).forEach { SmsDb.setSetting(db, it, null) }
            db.delete("paired_devices", null, null)
        }
    }

    fun setPushSeq(context: Context, id: String, seq: Long) = SmsDb.write(context) { db ->
        db.update("paired_devices", ContentValues().apply { put("push_seq", seq) }, "id = ?", arrayOf(id))
    }

    fun touch(context: Context, id: String, now: Long = System.currentTimeMillis()) = SmsDb.write(context) { db ->
        db.update("paired_devices", ContentValues().apply { put("last_seen", now) }, "id = ?", arrayOf(id))
    }

    // -- the QR code ---------------------------------------------------------------------------------------------------

    data class Code(val url: String, val key: String, val token: String, val secret: String, val web: String, val pk: String)

    /** `smsrelay:1?u=…&k=…&t=…&s=…&w=…&p=…` (FRONTEND/src/sync/protocol.ts). */
    fun parse(text: String): Code? {
        val prefix = "smsrelay:1?"
        if (!text.startsWith(prefix)) return null
        val q = text.removePrefix(prefix).split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i) to java.net.URLDecoder.decode(part.substring(i + 1), "UTF-8")
        }.toMap()
        val code = Code(q["u"] ?: return null, q["k"] ?: return null, q["t"] ?: return null, q["s"] ?: return null, q["w"] ?: return null, q["p"] ?: return null)
        if (!code.url.startsWith("https://") && !code.url.startsWith("http://")) return null
        return code
    }

    /** Either base64 alphabet, padded or not. */
    internal fun decode(text: String): ByteArray {
        val std = text.replace('-', '+').replace('_', '/').trimEnd('=')
        return Base64.decode(std + "=".repeat((4 - std.length % 4) % 4), Base64.DEFAULT)
    }

    fun deviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL).filterNotNull().joinToString(" ").trim().ifEmpty { "Phone" }
            .replaceFirstChar { it.uppercase() }.take(80)
}
