package com.blutrixx.plugins.nativephp_sms

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Answers paired browsers while the app's process runs (the keep-alive service keeps it running): it holds the relay's
 * WebSocket (Pusher protocol, this phone's private channel), opens each frame with that browser's session key, answers
 * `hello`, `pull` and `fetch` from sms.sqlite, and pushes `changes` unasked when change_log moves. Anything it missed
 * while offline waits on the relay and is fetched when it reconnects.
 *
 * All work runs on one thread, so frames are handled in order.
 */
object SyncAgent {
    private const val TAG = "NativephpSms.Sync"
    private const val WATCH_MS = 3_000L

    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sms-sync").apply { isDaemon = true } }
    private val client by lazy { OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build() }

    @Volatile private var app: Context? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var wanted = false
    private var backoff = 2L
    private var watch: ScheduledFuture<*>? = null
    private var retry: ScheduledFuture<*>? = null

    /** What the settings screen shows. */
    fun state(): String = when {
        !wanted -> "off"
        connected -> "connected"
        else -> "connecting"
    }

    /** Starts (or keeps) the connection when this phone has a paired browser. Safe to call often. */
    fun start(context: Context) {
        val ctx = context.applicationContext
        worker.execute {
            app = ctx
            if (RelayLink.identity(ctx) == null || RelayLink.browsers(ctx).isEmpty()) {
                stopNow()
                return@execute
            }
            wanted = true
            if (socket == null) connect(ctx)
        }
    }

    fun stop() = worker.execute { stopNow() }

    /** After pairing or unpairing: reconnect with what is stored now. */
    fun restart(context: Context) {
        worker.execute { stopNow() }
        start(context)
    }

    private fun stopNow() {
        wanted = false
        connected = false
        watch?.cancel(false); watch = null
        retry?.cancel(false); retry = null
        socket?.close(1000, "stop"); socket = null
    }

    // -- the WebSocket ------------------------------------------------------------------------------------------------

    private fun connect(ctx: Context) {
        val me = RelayLink.identity(ctx) ?: return
        val ws = me.url.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://").trimEnd('/')
        val request = Request.Builder().url("$ws/app/${me.appKey}?protocol=7&client=sms-messenger-android&version=1&flash=false").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                worker.execute { if (socket === webSocket) onEvent(ctx, webSocket, text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                worker.execute { if (socket === webSocket) dropped(ctx, t.javaClass.simpleName) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                worker.execute { if (socket === webSocket) dropped(ctx, "closed $code") }
            }
        })
    }

    private fun dropped(ctx: Context, why: String) {
        Log.w(TAG, "relay connection dropped: $why")
        connected = false
        socket = null
        watch?.cancel(false); watch = null
        if (!wanted) return
        val wait = backoff
        backoff = (backoff * 2).coerceAtMost(60)
        retry = worker.schedule({ if (wanted && socket == null) connect(ctx) }, wait, TimeUnit.SECONDS)
    }

    private fun onEvent(ctx: Context, ws: WebSocket, text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        val data = msg.opt("data").let { d -> if (d is String) runCatching { JSONObject(d) }.getOrNull() ?: JSONObject() else d as? JSONObject ?: JSONObject() }
        when (msg.optString("event")) {
            "pusher:connection_established" -> subscribe(ctx, ws, data.getString("socket_id"))
            "pusher:ping" -> ws.send("""{"event":"pusher:pong","data":{}}""")
            "pusher_internal:subscription_succeeded" -> {
                connected = true
                backoff = 2
                EventJournal.append(ctx, "relay_connected", JSONObject().put("browsers", RelayLink.browsers(ctx).size))
                drain(ctx)
                watch = worker.scheduleWithFixedDelay({ pushChanges(ctx) }, WATCH_MS, WATCH_MS, TimeUnit.MILLISECONDS)
            }
            "pusher:error" -> Log.w(TAG, "relay error ${data.optInt("code")}")
            "message" -> {
                val blob = data.optString("blob")
                if (blob.isNotEmpty()) receive(ctx, listOf(Triple(data.getLong("id"), data.getString("from"), blob))) else drain(ctx)
            }
            "revoked" -> revoked(ctx, data.optString("id"))
        }
    }

    private fun subscribe(ctx: Context, ws: WebSocket, socketId: String) {
        val me = RelayLink.identity(ctx) ?: return
        val channel = "private-relay.device.${me.deviceId}"
        try {
            val auth = RelayHttp.current.call(me.url, "POST", "/relay/broadcasting/auth", JSONObject().put("socket_id", socketId).put("channel_name", channel), me.token)
            ws.send(JSONObject().put("event", "pusher:subscribe").put("data", JSONObject().put("channel", channel).put("auth", auth.getString("auth"))).toString())
        } catch (e: RelayHttp.Failed) {
            if (e.status == 401) revoked(ctx, me.deviceId) else ws.close(1000, "auth failed")
        } catch (e: Exception) {
            ws.close(1000, "auth failed")
        }
    }

    /** Fetches everything waiting on the relay for this phone and handles it. */
    private fun drain(ctx: Context) {
        val me = RelayLink.identity(ctx) ?: return
        try {
            var after = 0L
            while (true) {
                val waiting = RelayHttp.current.call(me.url, "GET", "/relay/messages?after=$after&limit=100", null, me.token).getJSONArray("messages")
                if (waiting.length() == 0) break
                val batch = (0 until waiting.length()).map { i -> waiting.getJSONObject(i).let { Triple(it.getLong("id"), it.optString("from"), it.getString("blob")) } }
                receive(ctx, batch)
                after = batch.last().first
                if (waiting.length() < 100) break
            }
        } catch (e: RelayHttp.Failed) {
            if (e.status == 401) revoked(ctx, me.deviceId)
        } catch (e: Exception) {
            Log.w(TAG, "drain failed: ${e.javaClass.simpleName}")
        }
    }

    private fun receive(ctx: Context, batch: List<Triple<Long, String, String>>) {
        val me = RelayLink.identity(ctx) ?: return
        // a typed-code pairing reads its own frames (RelayLink.pairByCode): left on the relay for it
        val mine = batch.filterNot { RelayLink.isPairFrame(it.third) }
        for ((_, from, blob) in mine) {
            for ((to, reply) in handle(ctx, from, blob)) send(ctx, me, to, reply)
        }
        // a send or a read changed sms.sqlite: the browsers hear of it now rather than at the next watch
        if (mine.isNotEmpty()) pushChanges(ctx)
        if (mine.isNotEmpty()) runCatching { RelayHttp.current.call(me.url, "POST", "/relay/messages/ack", JSONObject().put("ids", JSONArray(mine.map { it.first })), me.token) }
    }

    /** After a typed-code pairing is confirmed: say hello, so the browser knows the digits matched and pulls. */
    fun greet(context: Context, id: String) {
        val ctx = context.applicationContext
        worker.execute {
            val me = RelayLink.identity(ctx) ?: return@execute
            val b = RelayLink.browser(ctx, id) ?: return@execute
            val hello = JSONObject().put("v", 1).put("t", "hello").put("name", RelayLink.deviceName()).put("seq", SyncData.seq(ctx))
            send(ctx, me, id, SyncCrypto.current.seal(b.tx, hello.toString(), SyncCrypto.ad(me.deviceId, id)))
        }
    }

    private fun send(ctx: Context, me: RelayLink.Identity, to: String, blob: String) {
        try {
            RelayHttp.current.call(me.url, "POST", "/relay/messages", JSONObject().put("to", to).put("blob", blob), me.token)
        } catch (e: RelayHttp.Failed) {
            if (e.code == "not_peer") RelayLink.forget(ctx, to)
            Log.w(TAG, "send failed: ${e.code}")
        } catch (e: Exception) {
            Log.w(TAG, "send failed: ${e.javaClass.simpleName}")
        }
    }

    private fun revoked(ctx: Context, id: String) {
        val me = RelayLink.identity(ctx)
        if (me == null || id == me.deviceId) {
            RelayLink.forgetIdentity(ctx)
            EventJournal.append(ctx, "relay_revoked", JSONObject().put("who", "phone"))
            stopNow()
        } else {
            RelayLink.forget(ctx, id)
            EventJournal.append(ctx, "relay_revoked", JSONObject().put("who", "browser"))
            if (RelayLink.browsers(ctx).isEmpty()) stopNow()
        }
    }

    /** Sends each browser what changed since it was last brought up to date. */
    private fun pushChanges(ctx: Context) {
        if (!connected) return
        val me = RelayLink.identity(ctx) ?: return
        val now = SyncData.seq(ctx)
        for (b in RelayLink.browsers(ctx)) {
            if (b.pushSeq == 0L || b.pushSeq >= now) continue // never synced (it pulls first), or up to date
            val frame = SyncData.changes(ctx, b.pushSeq)
            if (frame.optBoolean("reset")) continue // too far behind for a push: it pulls
            send(ctx, me, b.id, SyncCrypto.current.seal(b.tx, frame.toString(), SyncCrypto.ad(me.deviceId, b.id)))
            RelayLink.setPushSeq(ctx, b.id, frame.getLong("seq"))
        }
    }

    // -- frames -------------------------------------------------------------------------------------------------------

    /**
     * Opens one frame from a browser and answers it: the (browser id, sealed reply) pairs to send. A frame that does
     * not open (another browser, a changed blob, an unknown sender) is dropped without an answer.
     */
    fun handle(ctx: Context, from: String, blob: String, name: String = RelayLink.deviceName()): List<Pair<String, String>> {
        val me = RelayLink.identity(ctx) ?: return emptyList()
        val browser = RelayLink.browser(ctx, from) ?: return emptyList()
        val crypto = SyncCrypto.current
        val text = crypto.open(browser.rx, blob, SyncCrypto.ad(from, me.deviceId)) ?: return emptyList()
        val frame = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        RelayLink.touch(ctx, from)

        val reply: JSONObject = try {
            when (frame.optString("t")) {
                "hello" -> JSONObject().put("v", 1).put("t", "hello").put("name", name).put("seq", SyncData.seq(ctx))
                "pull" -> {
                    val after = frame.optLong("after", 0)
                    val snapshotSeq = if (frame.has("seq")) frame.getLong("seq") else null
                    SyncData.changes(ctx, after, frame.optInt("offset", 0), snapshotSeq).also { out ->
                        // once the browser has everything up to here, later changes are pushed to it unasked
                        if (!out.optBoolean("more")) RelayLink.setPushSeq(ctx, from, out.getLong("seq"))
                    }
                }
                "send" -> sendFromBrowser(ctx, from, frame)
                "read" -> {
                    StoreWrites.saveThread(ctx, frame.getString("thread"), mapOf("read" to true))
                    JSONObject().put("v", 1).put("t", "ok")
                }
                "fetch" -> SyncData.page(ctx, frame.getString("thread"), if (frame.has("before") && !frame.isNull("before")) frame.getLong("before") else null, frame.optInt("limit", SyncData.MESSAGES_PAGE))
                else -> JSONObject().put("v", 1).put("t", "error").put("code", "unknown").put("message", "This phone does not know that request.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "frame failed: ${e.javaClass.simpleName}")
            JSONObject().put("v", 1).put("t", "error").put("code", "failed").put("message", "The phone could not answer.")
        }
        if (frame.has("id")) reply.put("id", frame.get("id"))
        return listOf(from to crypto.seal(browser.tx, reply.toString(), SyncCrypto.ad(me.deviceId, from)))
    }

    /** The longest text a browser may send (the app's own composer has no lower limit; this keeps a frame small). */
    const val MAX_BODY = 5_000

    /**
     * `send {to, body, client_ref, thread?, sub_id?}`: sent exactly as the app sends (SmsSender: the system store when
     * default, a private number's sealed copy, the status callbacks); its states reach the browser as changes. The SIM is
     * the one asked for, else the conversation's last, else the phone's default. A client_ref already used answers with
     * the first message and sends nothing (a browser that retries after a lost answer does not send twice).
     */
    private fun sendFromBrowser(ctx: Context, from: String, frame: JSONObject): JSONObject {
        val to = frame.optString("to").trim()
        val body = frame.optString("body")
        val ref = frame.optString("client_ref")
        if (to.isEmpty() || body.isBlank() || body.length > MAX_BODY || ref.length !in 8..64) {
            return JSONObject().put("v", 1).put("t", "error").put("code", "invalid").put("message", "The phone could not send that.")
        }
        val thread = frame.optString("thread").takeIf { it.isNotEmpty() && it != "null" }
        val sub = when {
            frame.has("sub_id") && !frame.isNull("sub_id") -> frame.getInt("sub_id")
            thread != null -> SmsDb.get(ctx).rawQuery("SELECT sub_id FROM messages WHERE thread_id = ? AND sub_id IS NOT NULL ORDER BY ts DESC LIMIT 1", arrayOf(thread)).use { if (it.moveToFirst()) it.getInt(0) else -1 }
            else -> -1
        }
        val r = SmsSender.send(ctx, to, body, ref, sub)
        EventJournal.append(ctx, "send_from_browser", JSONObject().put("browser", from.takeLast(4)).put("parts", r.parts).put("duplicate", r.duplicate))
        return JSONObject().put("v", 1).put("t", "sent").put("client_ref", ref).put("message_id", r.messageId ?: JSONObject.NULL)
            .put("thread", r.storeThreadId ?: JSONObject.NULL).put("duplicate", r.duplicate)
    }
}
