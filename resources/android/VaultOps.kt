package com.blutrixx.plugins.nativephp_sms

import android.content.ContentValues
import android.content.Context
import org.json.JSONObject

/**
 * What the person does with the vault: hide messages or a conversation, block a number, keep a number private, restore,
 * move out, delete forever, the rules, and the conversations as the open vault shows them.
 *
 * A vault record is the sealed JSON of one text: why it is here (hidden, blocked, private), direction, address, body, ts,
 * and what else the text had (status, SIM, parts, its times, the conversation it came from). Moving a text in deletes it
 * from sms.sqlite (change_log records the delete by trigger, so a paired browser drops it) and from the phone's SMS store.
 */
object VaultOps {
    class NoVault : IllegalStateException("Set up the vault first.")

    private fun requireVault(context: Context) {
        if (!VaultStore.hasKey(context)) throw NoVault()
    }

    /** One sms.sqlite message as a vault record. */
    private fun recordOf(context: Context, messageId: String, why: String): Pair<JSONObject, Long>? =
        SmsDb.get(context).rawQuery(
            "SELECT direction, address, body, ts, status, sub_id, sim_slot, parts_total, thread_id, sent_at, delivered_at, smsc_at, read FROM messages WHERE id = ?",
            arrayOf(messageId),
        ).use { c ->
            if (!c.moveToFirst()) return null
            val opt = { i: Int -> if (c.isNull(i)) JSONObject.NULL else c.getLong(i) }
            JSONObject()
                .put("why", why).put("direction", c.getString(0)).put("address", c.getString(1)).put("body", c.getString(2))
                .put("ts", c.getLong(3)).put("status", c.getString(4)).put("sub_id", opt(5)).put("sim_slot", opt(6))
                .put("parts", c.getInt(7)).put("thread", c.getString(8)).put("sent_at", opt(9)).put("delivered_at", opt(10))
                .put("smsc_at", opt(11)).put("read", c.getInt(12) == 1) to c.getLong(3)
        }

    /** Hide to vault: these messages are sealed and leave the conversation and the phone's SMS store. Answers how many. */
    fun hideMessages(context: Context, ids: Collection<String>, why: String = "hidden"): Int {
        requireVault(context)
        var n = 0
        for (id in ids) {
            val (record, ts) = recordOf(context, id, why) ?: continue
            if (!VaultStore.put(context, record, ts)) continue
            StoreWrites.deleteMessage(context, id)
            // its conversation's notification may still show the words: it goes too
            SmsNotifier.cancelThread(context, record.getString("thread"))
            n++
        }
        return n
    }

    private fun messageIds(context: Context, threadId: String): List<String> =
        SmsDb.get(context).rawQuery("SELECT id FROM messages WHERE thread_id = ?", arrayOf(threadId)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private fun addressOf(context: Context, threadId: String): String =
        SmsDb.get(context).rawQuery("SELECT addresses FROM threads WHERE id = ?", arrayOf(threadId)).use { c ->
            if (!c.moveToFirst()) throw StoreWrites.NotFound("No such conversation.")
            org.json.JSONArray(c.getString(0)).optString(0, "")
        }

    /** Hide conversation: every message sealed, then the conversation goes. */
    fun hideThread(context: Context, threadId: String, why: String = "hidden"): Int {
        requireVault(context)
        val n = hideMessages(context, messageIds(context, threadId), why)
        runCatching { StoreWrites.deleteThread(context, threadId) }
        return n
    }

    /** Block: a number rule (once), and when `threadId` is given its messages move into the vault. */
    fun block(context: Context, number: String, threadId: String?): Int {
        requireVault(context)
        val key = SmsPrefs.normalize(number)
        val existing = Blocklist.rules(context).any { it.kind == "number" && it.action == "block" && SmsPrefs.normalize(it.pattern) == key }
        if (!existing) SmsDb.write(context) { Blocklist.add(it, "number", "block", null, number) }
        return threadId?.let { hideThread(context, it, "blocked") } ?: 0
    }

    /**
     * Keep in vault: the number becomes private (its block rule, if any, goes) and its conversation moves in. From then on
     * its texts land only in the vault, with a generic or no notification.
     */
    fun keepPrivate(context: Context, threadId: String, notify: String = "generic"): Int {
        requireVault(context)
        val address = addressOf(context, threadId)
        setPrivate(context, address, notify)
        return hideThread(context, threadId, "private")
    }

    private fun setPrivate(context: Context, address: String, notify: String) {
        val key = SmsPrefs.normalize(address)
        val same = Blocklist.rules(context).filter { it.kind == "number" && SmsPrefs.normalize(it.pattern) == key }
        SmsDb.write(context) { db ->
            same.forEach { db.delete("blocklist", "id = ?", arrayOf(it.id)) }
            Blocklist.add(db, "number", "private", if (notify == "silent") "silent" else "generic", address)
        }
    }

    /** Make silent / Notify as "New message", for a private number. */
    fun setPrivateNotify(context: Context, address: String, notify: String) = setPrivate(context, address, notify)

    /** A private number's comparison keys (contacts leave them out of suggestions). Needs no key. */
    fun privateKeys(context: Context): List<String> =
        Blocklist.rules(context).filter { it.kind == "number" && it.action == "private" }.map { SmsPrefs.normalize(it.pattern) }.distinct()

    fun isPrivate(context: Context, address: String): Boolean {
        val key = SmsPrefs.normalize(address)
        return key.isNotEmpty() && privateKeys(context).contains(key)
    }

    // -- the open vault ---------------------------------------------------------------------------------------------------

    private fun addressOf(record: JSONObject) = record.optString("address").ifEmpty { record.optString("from").ifEmpty { record.optString("to") } }

    data class Conversation(val address: String, val name: String?, val tag: String, val notify: String?, val lastAt: Long, val lastBody: String, val lastOut: Boolean, val count: Int)

    /** The vault's conversations, one per number, newest first: Private (a private rule), Blocked sender (a block rule), else Hidden messages. */
    fun conversations(context: Context): List<Conversation> {
        val rules = Blocklist.rules(context)
        return VaultStore.rows(context).groupBy { SmsPrefs.normalize(addressOf(it.record)).ifEmpty { addressOf(it.record) } }.map { (key, rows) ->
            val newest = rows.first()
            val address = addressOf(newest.record)
            val private = rules.firstOrNull { it.action == "private" && it.kind == "number" && SmsPrefs.normalize(it.pattern) == key }
            val blocked = rules.any { it.action == "block" && ReceivePipeline.matches(it, address, "") }
            Conversation(
                address, ContactSync.nameFor(context, address),
                when { private != null -> "private"; blocked -> "blocked"; else -> "hidden" },
                private?.notify ?: if (private != null) "generic" else null,
                newest.ts, newest.record.optString("body"), newest.record.optString("direction") == "out", rows.size,
            )
        }.sortedByDescending { it.lastAt }
    }

    /** One conversation's messages, oldest first, with each one's state. */
    fun messages(context: Context, address: String): List<JSONObject> {
        val key = SmsPrefs.normalize(address)
        return VaultStore.rows(context).filter { SmsPrefs.normalize(addressOf(it.record)) == key }.sortedBy { it.ts }.map { row ->
            JSONObject(row.record.toString()).put("id", row.id).put("ts", row.ts).put("status", row.status ?: row.record.optString("status", "received"))
        }
    }

    /**
     * Restore: back into a normal conversation (made again if it went) and the phone's SMS store, with the original time.
     * A blocked sender stays blocked. Answers how many.
     */
    fun restore(context: Context, ids: Collection<String>): Int {
        val wanted = ids.toSet()
        val rows = VaultStore.rows(context).filter { it.id in wanted }
        val now = System.currentTimeMillis()
        for (row in rows) {
            // the vault's copy of a text sent from the normal composer: the original is still in its conversation
            if (row.clientRef != null && hasClientRef(context, row.clientRef)) continue
            val r = row.record
            val address = addressOf(r)
            val body = r.optString("body")
            val sub = r.optInt("sub_id", -1).takeIf { it >= 0 }
            if (r.optString("direction") == "out") {
                val sys = SmsStore.insertSent(context, address, body, row.ts, sub ?: -1)
                val status = (row.status ?: r.optString("status", "sent")).let { if (it == "queued" || it == "sending") "failed" else it }
                SmsDb.write(context) { MessageStore.insertImportedOutgoing(it, address, body, row.ts, sub, sys.id, sys.threadId, status, now) }
            } else {
                val sys = SmsStore.insertInbox(context, address, body, row.ts, r.optLong("smsc_at", row.ts), sub ?: -1, read = true)
                SmsDb.write(context) {
                    MessageStore.insertIncoming(it, MessageStore.Incoming(address, body, row.ts, sub, null, sys.id, sys.threadId, read = true, smscAt = r.optLong("smsc_at", 0).takeIf { t -> t > 0 }), now)
                }
            }
        }
        VaultStore.delete(context, rows.map { it.id })
        return rows.size
    }

    private fun hasClientRef(context: Context, ref: String): Boolean =
        SmsDb.get(context).rawQuery("SELECT 1 FROM messages WHERE client_ref = ? LIMIT 1", arrayOf(ref)).use { it.moveToFirst() }

    /** Move out of vault: the number goes back to the normal rules and every message to a normal conversation. */
    fun moveOut(context: Context, address: String): Int {
        val key = SmsPrefs.normalize(address)
        val rules = Blocklist.rules(context).filter { it.kind == "number" && it.action == "private" && SmsPrefs.normalize(it.pattern) == key }
        SmsDb.write(context) { db -> rules.forEach { db.delete("blocklist", "id = ?", arrayOf(it.id)) } }
        val ids = VaultStore.rows(context).filter { SmsPrefs.normalize(addressOf(it.record)) == key }.map { it.id }
        return restore(context, ids)
    }

    fun deleteConversation(context: Context, address: String): Int {
        val key = SmsPrefs.normalize(address)
        val ids = VaultStore.rows(context).filter { SmsPrefs.normalize(addressOf(it.record)) == key }.map { it.id }
        VaultStore.delete(context, ids)
        return ids.size
    }

    /**
     * Wipe the vault: every message, the keys, and the rules (blocked and private numbers). Without a key a blocked text
     * could only wait unsealed in quarantine, behind rules no screen could show, so they go too. Needs it open.
     */
    fun wipe(context: Context) {
        VaultStore.secretKey()
        SmsDb.write(context) { it.delete("blocklist", null, null) }
        VaultStore.dropQuarantine(context)
        VaultStore.wipe(context)
    }

    // -- rules (inside the open vault) ------------------------------------------------------------------------------------

    fun rules(context: Context): List<Blocklist.Rule> {
        VaultStore.secretKey()
        return Blocklist.rules(context)
    }

    fun addRule(context: Context, kind: String, pattern: String): String {
        VaultStore.secretKey()
        if (kind !in setOf("number", "prefix", "wildcard", "keyword")) throw StoreWrites.Invalid("A rule is a number, a prefix, a wildcard or a keyword.")
        val p = pattern.trim()
        if (p.isEmpty() || p.length > 200) throw StoreWrites.Invalid("Type what the rule matches.")
        if (kind == "wildcard" && p.none { it != '*' && it != '?' }) throw StoreWrites.Invalid("A wildcard needs more than * and ?.")
        return SmsDb.write(context) { Blocklist.add(it, kind, "block", null, p) }
    }

    /** A rule's removal; a private rule's is Move out of vault. */
    fun removeRule(context: Context, id: String): Blocklist.Rule? {
        VaultStore.secretKey()
        val rule = Blocklist.rules(context).firstOrNull { it.id == id } ?: throw StoreWrites.NotFound("No such rule.")
        if (rule.action == "private") moveOut(context, rule.pattern)
        else SmsDb.write(context) { it.delete("blocklist", "id = ?", arrayOf(id)) }
        return rule
    }

    // -- spam ---------------------------------------------------------------------------------------------------------------

    /** Report spam: the conversation to Spam, muted, and its sender's next texts go there silently. */
    fun reportSpam(context: Context, threadId: String) {
        val key = SmsDb.get(context).rawQuery("SELECT participants_key FROM threads WHERE id = ?", arrayOf(threadId)).use {
            if (it.moveToFirst()) it.getString(0) else throw StoreWrites.NotFound("No such conversation.")
        }
        SmsDb.write(context) { db ->
            db.insertWithOnConflict("spam_senders", null, ContentValues().apply { put("address", key); put("reported_at", System.currentTimeMillis()) }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        }
        StoreWrites.saveThread(context, threadId, mapOf("folder" to "spam", "muted" to true))
    }

    /** Not spam: back to the inbox, notifying again, and the sender off the list. */
    fun notSpam(context: Context, threadId: String) {
        val key = SmsDb.get(context).rawQuery("SELECT participants_key FROM threads WHERE id = ?", arrayOf(threadId)).use {
            if (it.moveToFirst()) it.getString(0) else throw StoreWrites.NotFound("No such conversation.")
        }
        SmsDb.write(context) { it.delete("spam_senders", "address = ?", arrayOf(key)) }
        StoreWrites.saveThread(context, threadId, mapOf("folder" to "inbox", "muted" to false))
    }

    const val SPAM_KEEP_MS = 30L * 24 * 60 * 60 * 1000

    /** Spam conversations whose last text is more than 30 days old are deleted (also from the phone's SMS store). */
    fun purgeSpam(context: Context, now: Long = System.currentTimeMillis()): Int {
        // the cut-off is written as a number: COALESCE has no column type, so a bound (text) value would compare as text and
        // every number sorts before text, which would purge all spam
        val cutoff = now - SPAM_KEEP_MS
        val old = SmsDb.get(context).rawQuery(
            "SELECT id FROM threads WHERE folder = 'spam' AND COALESCE(last_message_at, updated_at) < $cutoff",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        old.forEach { runCatching { StoreWrites.deleteThread(context, it) } }
        return old.size
    }
}
