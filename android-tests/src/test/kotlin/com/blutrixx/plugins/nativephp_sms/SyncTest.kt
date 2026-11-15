package com.blutrixx.plugins.nativephp_sms

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
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
import org.robolectric.annotation.Config
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 2.4: pairing by QR code, and answering a browser's hello, pull and fetch from sms.sqlite. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncTest : DbTestBase() {

    /** Robolectric has no libsodium: a stand-in where both sides' keys match and a frame opens only with its key and ad. */
    object TestSync : SyncCrypto {
        fun sha(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").apply { parts.forEach { update(it) } }.digest()
        override fun keypair(seed: ByteArray?): Pair<ByteArray, ByteArray> { val sk = seed ?: ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }; return sha(sk) to sk }
        override fun sessionKeys(publicKey: ByteArray, privateKey: ByteArray, webPublicKey: ByteArray, secret: ByteArray) =
            sha("w2p".toByteArray(), webPublicKey, publicKey, secret) to sha("p2w".toByteArray(), publicKey, webPublicKey, secret)
        override fun seal(key: ByteArray, plaintext: String, ad: String, nonce: ByteArray?): String {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")); updateAAD(ad.toByteArray()) }
            return Base64.encodeToString(c.iv + c.doFinal(plaintext.toByteArray()), Base64.NO_WRAP)
        }
        override fun hash(label: String, vararg parts: ByteArray) = sha(label.toByteArray(), *parts)
        override fun random(n: Int) = ByteArray(n).also { java.security.SecureRandom().nextBytes(it) }
        override fun open(key: ByteArray, blob: String, ad: String): String? = runCatching {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw, 0, 12)); updateAAD(ad.toByteArray()) }
            String(c.doFinal(raw, 12, raw.size - 12))
        }.getOrNull()
    }

    /** The relay: answers a claim with this browser, and remembers calls. With `browserNonce`, plays the browser's part of a typed-code pairing. */
    class FakeRelay(var webKey: ByteArray) : RelayHttp {
        val calls = mutableListOf<Triple<String, String, String?>>()
        var phoneToken = "phone-token-1"
        var browserNonce: ByteArray? = null
        val fromPhone = mutableListOf<JSONObject>()
        override fun call(url: String, method: String, path: String, body: JSONObject?, token: String?): JSONObject {
            calls += Triple(path, body?.toString() ?: "", token)
            if (method == "POST" && path == "/relay/messages") {
                body!!.getString("blob").takeIf { it.startsWith("pair:") }?.let { fromPhone += JSONObject(it.removePrefix("pair:")) }
                return JSONObject().put("id", 1)
            }
            if (method == "GET" && path.startsWith("/relay/messages")) {
                val n = browserNonce
                val list = JSONArray()
                if (n != null && fromPhone.any { it.getString("t") == "commit" }) {
                    list.put(JSONObject().put("id", 7).put("from", WEB).put("blob", "pair:" + JSONObject().put("t", "nonce").put("n", Base64.encodeToString(n, Base64.NO_WRAP))))
                }
                return JSONObject().put("messages", list)
            }
            return when (path) {
                "/relay/pairings/claim" -> JSONObject()
                    .put("device", JSONObject().put("id", PHONE))
                    .put("token", if (token != null) JSONObject.NULL else phoneToken)
                    .put("key", "appkey-from-server")
                    .put("peer", JSONObject().put("id", WEB).put("name", "Firefox on Linux").put("public_key", Base64.encodeToString(webKey, Base64.NO_WRAP)))
                else -> JSONObject()
            }
        }
    }

    companion object {
        const val PHONE = "01PHONE0000000000000000000"
        const val WEB = "01WEB00000000000000000000A"
    }

    private val webSk = ByteArray(32) { 7 }
    private val webPk = TestSync.sha(webSk)
    private val secret = ByteArray(32) { 9 }
    private lateinit var relay: FakeRelay

    @Before fun stand() {
        SyncCrypto.current = TestSync
        relay = FakeRelay(webPk)
        RelayHttp.current = relay
    }

    @After fun back() {
        SyncCrypto.current = SodiumSync
        RelayHttp.current = UrlRelayHttp
    }

    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun code(pk: ByteArray = webPk) =
        "smsrelay:1?u=https%3A%2F%2Frelay.test&k=appkey&t=pair-token&s=${b64url(secret)}&w=$WEB&p=${b64url(pk)}"

    /** The browser's keys for this pairing, worked out as the browser would. */
    private fun webKeys(): Pair<ByteArray, ByteArray> {
        val phonePk = Base64.decode(SmsDb.setting(context, "relay.pk")!!, Base64.NO_WRAP)
        val (phoneRx, phoneTx) = TestSync.sessionKeys(phonePk, ByteArray(0), webPk, secret)
        return phoneTx to phoneRx // the browser's rx is the phone's tx
    }

    private fun ask(frame: JSONObject): JSONObject {
        val (_, webTx) = webKeys()
        val replies = SyncAgent.handle(context, WEB, TestSync.seal(webTx, frame.toString(), SyncCrypto.ad(WEB, PHONE)), "Test phone")
        assertEquals(1, replies.size)
        assertEquals(WEB, replies[0].first)
        val (webRx, _) = webKeys()
        return JSONObject(TestSync.open(webRx, replies[0].second, SyncCrypto.ad(PHONE, WEB))!!)
    }

    private fun receive(from: String, body: String, ts: Long) =
        ReceivePipeline.receive(context, ReceivePipeline.Delivered(from, body, ts, ts - 500, 1, 0, 1))

    // -- pairing ------------------------------------------------------------------------------------------------------

    @Test fun pairingKeepsTheKeysWrappedAndNeverTheSecret() {
        val b = RelayLink.pair(context, code(), "Test phone")

        assertEquals(WEB, b.id)
        assertEquals("Firefox on Linux", b.name)
        val me = RelayLink.identity(context)!!
        assertEquals(PHONE, me.deviceId)
        assertEquals("phone-token-1", me.token)
        assertEquals("https://relay.test", me.url)
        assertEquals("appkey", me.appKey)

        val stored = JSONArray().apply {
            SmsDb.get(context).rawQuery("SELECT key, value FROM settings UNION ALL SELECT id, rx_key || tx_key FROM paired_devices", null).use { c ->
                while (c.moveToNext()) put(c.getString(0) + "=" + c.getString(1))
            }
        }.toString()
        assertFalse(stored.contains("phone-token-1"))
        assertFalse(stored.contains(b64url(secret)))
        assertFalse(stored.contains(Base64.encodeToString(secret, Base64.NO_WRAP)))
        assertFalse(stored.contains(Base64.encodeToString(me.privateKey, Base64.NO_WRAP)))
    }

    @Test fun aSecondBrowserReusesThisPhonesIdentity() {
        RelayLink.pair(context, code(), "Test phone")
        val firstKey = SmsDb.setting(context, "relay.pk")
        RelayLink.pair(context, code(), "Test phone")

        assertEquals("phone-token-1", relay.calls.last().third)
        assertEquals(firstKey, SmsDb.setting(context, "relay.pk"))
        assertEquals("phone-token-1", RelayLink.identity(context)!!.token)
    }

    @Test fun aServerAnsweringWithAnotherBrowserIsRefused() {
        relay.webKey = TestSync.sha(ByteArray(32) { 1 })
        assertThrows(RelayLink.KeyMismatch::class.java) { RelayLink.pair(context, code(), "Test phone") }
        assertNull(RelayLink.identity(context))
        assertThrows(RelayLink.BadCode::class.java) { RelayLink.pair(context, "https://example.test", "Test phone") }
    }

    @Test fun removingTheLastBrowserForgetsTheRelay() {
        RelayLink.pair(context, code(), "Test phone")
        RelayLink.unpair(context, WEB)

        assertEquals("/relay/devices/$WEB/revoke", relay.calls.last().first)
        assertTrue(RelayLink.browsers(context).isEmpty())
        assertNull(RelayLink.identity(context))
    }

    // -- frames -------------------------------------------------------------------------------------------------------

    @Test fun helloPullAndFetchAnswerFromTheStore() {
        val t0 = 1_800_000_000_000L
        receive("+255700000001", "first", t0)
        receive("+255700000001", "second", t0 + 1000)
        receive("+255700000002", "other", t0 + 2000)
        RelayLink.pair(context, code(), "Test phone")

        val hello = ask(JSONObject().put("v", 1).put("t", "hello").put("name", "Firefox").put("id", "h1"))
        assertEquals("hello", hello.getString("t"))
        assertEquals("h1", hello.getString("id"))
        assertEquals("Test phone", hello.getString("name"))
        assertEquals(SyncData.seq(context), hello.getLong("seq"))

        // Everything: each conversation with its last message.
        val all = ask(JSONObject().put("v", 1).put("t", "pull").put("after", 0))
        assertEquals(2, all.getJSONArray("threads").length())
        val lasts = (0 until all.getJSONArray("messages").length()).map { all.getJSONArray("messages").getJSONObject(it).getString("body") }.toSet()
        assertEquals(setOf("second", "other"), lasts)
        assertFalse(all.getBoolean("more"))
        assertEquals(SyncData.seq(context), RelayLink.browser(context, WEB)!!.pushSeq)

        // A conversation's messages, newest first, a page at a time.
        val thread = (0 until 2).map { all.getJSONArray("threads").getJSONObject(it) }.first { it.getJSONArray("addresses").getString(0).endsWith("001") }.getString("id")
        val page = ask(JSONObject().put("v", 1).put("t", "fetch").put("thread", thread).put("limit", 1))
        assertEquals("second", page.getJSONArray("messages").getJSONObject(0).getString("body"))
        assertTrue(page.getBoolean("more"))
        val older = ask(JSONObject().put("v", 1).put("t", "fetch").put("thread", thread).put("limit", 1).put("before", t0 + 1000))
        assertEquals("first", older.getJSONArray("messages").getJSONObject(0).getString("body"))
        assertFalse(older.getBoolean("more"))
    }

    @Test fun pullAfterACursorSendsWhatChangedAndWhatWentAway() {
        val t0 = 1_800_000_000_000L
        receive("+255700000001", "first", t0)
        receive("+255700000002", "to delete", t0 + 10)
        RelayLink.pair(context, code(), "Test phone")
        val cursor = ask(JSONObject().put("v", 1).put("t", "pull").put("after", 0)).getLong("seq")
        val gone = SmsDb.get(context).rawQuery("SELECT thread_id FROM messages WHERE body = 'to delete'", null).use { it.moveToFirst(); it.getString(0) }

        receive("+255700000001", "new one", t0 + 5000)
        StoreWrites.deleteThread(context, gone)

        val changes = ask(JSONObject().put("v", 1).put("t", "pull").put("after", cursor))
        assertEquals(cursor, changes.getLong("after"))
        assertTrue(changes.getLong("seq") > cursor)
        val bodies = (0 until changes.getJSONArray("messages").length()).map { changes.getJSONArray("messages").getJSONObject(it).getString("body") }
        assertEquals(listOf("new one"), bodies)
        assertTrue(changes.getJSONObject("deleted").getJSONArray("threads").toString().contains(gone))
        assertFalse(changes.has("reset"))
    }

    @Test fun aCursorTheLogCannotAnswerStartsAgain() {
        receive("+255700000001", "first", 1_800_000_000_000L)
        RelayLink.pair(context, code(), "Test phone")
        receive("+255700000001", "second", 1_800_000_001_000L)
        SmsDb.write(context) { it.execSQL("DELETE FROM change_log WHERE seq < (SELECT MAX(seq) FROM change_log)") }

        val answer = ask(JSONObject().put("v", 1).put("t", "pull").put("after", 1))
        assertTrue(answer.getBoolean("reset"))
        assertEquals(1, answer.getJSONArray("threads").length())
    }

    @Test fun aFrameThatDoesNotOpenIsDropped() {
        RelayLink.pair(context, code(), "Test phone")
        val (_, webTx) = webKeys()
        val frame = JSONObject().put("v", 1).put("t", "hello").toString()

        // wrong direction, wrong key, unknown sender
        assertTrue(SyncAgent.handle(context, WEB, TestSync.seal(webTx, frame, SyncCrypto.ad(PHONE, WEB))).isEmpty())
        assertTrue(SyncAgent.handle(context, WEB, TestSync.seal(ByteArray(32), frame, SyncCrypto.ad(WEB, PHONE))).isEmpty())
        assertTrue(SyncAgent.handle(context, "01OTHER0000000000000000000", TestSync.seal(webTx, frame, SyncCrypto.ad("01OTHER0000000000000000000", PHONE))).isEmpty())
        assertNotNull(RelayLink.browser(context, WEB))
    }

    // -- a typed code -------------------------------------------------------------------------------------------------

    @Test fun aTypedCodePairsAfterTheDigitsAreConfirmed() {
        val na = ByteArray(16) { 3 }
        relay.browserNonce = na

        val pending = RelayLink.pairByCode(context, "https://relay.test", "k7q2 9xmf", "Test phone", pollMs = 1)

        // the browser's side: the commitment it got opens to the nonce revealed after its own
        val commit = Base64.decode(relay.fromPhone.first { it.getString("t") == "commit" }.getString("c"), Base64.NO_WRAP)
        val nb = Base64.decode(relay.fromPhone.first { it.getString("t") == "reveal" }.getString("n"), Base64.NO_WRAP)
        val phonePk = Base64.decode(SmsDb.setting(context, "relay.pk")!!, Base64.NO_WRAP)
        assertTrue(PairCode.commitment(TestSync, phonePk, webPk, nb).contentEquals(commit))
        assertEquals(PairCode.digits(TestSync, phonePk, webPk, na, nb), pending.digits)
        assertEquals(6, pending.digits.length)
        assertEquals("appkey-from-server", RelayLink.identity(context)!!.appKey)
        // the claim sent the normalised code, and the commitment went before the browser's nonce was read
        assertTrue(relay.calls.first().second.contains("\"token\":\"K7Q29XMF\""))

        // pending: not answered until confirmed
        assertTrue(RelayLink.browsers(context).isEmpty())
        assertTrue(RelayLink.confirm(context, WEB, true))
        val b = RelayLink.browser(context, WEB)!!

        // the keys are the ones the browser works out from the nonces and the code
        val secret = PairCode.secret(TestSync, na, nb, "K7Q2-9XMF")
        assertTrue(TestSync.sessionKeys(phonePk, ByteArray(0), webPk, secret).first.contentEquals(b.rx))
    }

    @Test fun digitsThatDoNotMatchRemoveTheBrowser() {
        relay.browserNonce = ByteArray(16) { 3 }
        RelayLink.pairByCode(context, "https://relay.test", "K7Q2-9XMF", "Test phone", pollMs = 1)

        assertFalse(RelayLink.confirm(context, WEB, false))
        assertEquals("/relay/devices/$WEB/revoke", relay.calls.last().first)
        assertNull(RelayLink.identity(context))
    }

    @Test fun aBrowserThatNeverAnswersLeavesNothingBehind() {
        assertThrows(RelayLink.NoAnswer::class.java) { RelayLink.pairByCode(context, "https://relay.test", "K7Q2-9XMF", "Test phone", waitMs = 20, pollMs = 5) }
        assertNull(RelayLink.identity(context))
        assertThrows(RelayLink.BadCode::class.java) { RelayLink.pairByCode(context, "https://relay.test", "K7Q2", "Test phone") }
    }

    // -- sending and reading from the browser (2.5) --------------------------------------------------------------------

    @Test fun aBrowserSendsATextOnceEvenWhenItAsksTwice() {
        receive("+255700000001", "hello", 1_800_000_000_000L)
        RelayLink.pair(context, code(), "Test phone")
        val thread = SmsDb.get(context).rawQuery("SELECT id FROM threads", null).use { it.moveToFirst(); it.getString(0) }
        val frame = JSONObject().put("v", 1).put("t", "send").put("to", "+255700000001").put("body", "from the browser").put("client_ref", "web-ref-0001").put("thread", thread).put("id", "s1")

        val answer = ask(frame)
        assertEquals("sent", answer.getString("t"))
        assertEquals("s1", answer.getString("id"))
        assertEquals(thread, answer.getString("thread"))
        assertFalse(answer.getBoolean("duplicate"))
        assertEquals(listOf("+255700000001" to listOf("from the browser")), DbTestBase.TestRadio.sent)
        val row = SmsDb.get(context).rawQuery("SELECT direction, status, client_ref FROM messages WHERE body = 'from the browser'", null).use { it.moveToFirst(); listOf(it.getString(0), it.getString(1), it.getString(2)) }
        assertEquals(listOf("out", "queued", "web-ref-0001"), row)

        // it comes back to the browser with its reference
        val page = ask(JSONObject().put("v", 1).put("t", "fetch").put("thread", thread))
        assertTrue(page.getJSONArray("messages").toString().contains("web-ref-0001"))

        // the answer was lost and the browser asks again: nothing more goes out
        assertTrue(ask(frame).getBoolean("duplicate"))
        assertEquals(1, DbTestBase.TestRadio.sent.size)
    }

    @Test fun aBrowserCannotSendNothingOrTooMuch() {
        RelayLink.pair(context, code(), "Test phone")
        for (body in listOf("", "   ", "x".repeat(SyncAgent.MAX_BODY + 1))) {
            val answer = ask(JSONObject().put("v", 1).put("t", "send").put("to", "+255700000001").put("body", body).put("client_ref", "web-ref-0002"))
            assertEquals("error", answer.getString("t"))
        }
        assertTrue(DbTestBase.TestRadio.sent.isEmpty())
    }

    @Test fun openingAConversationInTheBrowserMarksItRead() {
        receive("+255700000001", "unread one", 1_800_000_000_000L)
        RelayLink.pair(context, code(), "Test phone")
        val thread = SmsDb.get(context).rawQuery("SELECT id, unread FROM threads", null).use { it.moveToFirst(); assertEquals(1, it.getInt(1)); it.getString(0) }

        assertEquals("ok", ask(JSONObject().put("v", 1).put("t", "read").put("thread", thread)).getString("t"))
        assertEquals(0, SmsDb.get(context).rawQuery("SELECT unread FROM threads", null).use { it.moveToFirst(); it.getInt(0) })
    }
}
