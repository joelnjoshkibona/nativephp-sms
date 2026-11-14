package com.blutrixx.plugins.nativephp_sms

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Where an incoming text goes, in this order (the plan's receive pipeline):
 *   1. a private number      -> sealed into the vault; "New message" with no name or words, or nothing if silent
 *   2. the blocklist         -> sealed into the vault; no notification
 *   3. a reported spammer    -> sms.sqlite (thread in Spam) and the system store; no notification
 *   4. everyone else         -> the system store, sms.sqlite and the conversation's notification (none when it is muted
 *                               or open on screen)
 * The blocklist is checked before anything else knows the sender, so a rule you set wins even over a saved contact.
 * A vault text never reaches sms.sqlite, the system store, change_log, the journal's details or a notification's text.
 */
object ReceivePipeline {
    private const val TAG = "NativephpSms.Pipeline"

    sealed class Route {
        data class Private(val notify: String) : Route()
        object Blocked : Route()
        object Spam : Route()
        object Normal : Route()
    }

    data class Delivered(
        val address: String,
        val body: String,
        val receivedAt: Long,
        val smscTs: Long,
        val subId: Int,
        val simSlot: Int?,
        val parts: Int,
    )

    data class Outcome(
        val route: Route,
        val messageId: String? = null,
        val threadId: String? = null,
        val systemId: Long? = null,
        val sealed: Boolean = false,
        val notified: Boolean = false,
    )

    /** Pure: which route a text takes, from the rules (already decrypted) and the reported spammers' keys. */
    fun decide(address: String, body: String, rules: List<Blocklist.Rule>, spam: Set<String>): Route {
        rules.firstOrNull { it.action == "private" && matches(it, address, body) }?.let { return Route.Private(it.notify ?: "generic") }
        if (rules.any { it.action == "block" && matches(it, address, body) }) return Route.Blocked
        if (SmsPrefs.normalize(address) in spam) return Route.Spam
        return Route.Normal
    }

    /** A sender as rules compare it: lower-case for a name ("MPESA"), digits only for a number. */
    private fun comparable(raw: String): String =
        if (raw.any { it.isLetter() }) raw.trim().lowercase() else raw.filter { it.isDigit() }

    internal fun matches(rule: Blocklist.Rule, address: String, body: String): Boolean {
        val pattern = rule.pattern.trim()
        if (pattern.isEmpty()) return false
        return when (rule.kind) {
            "number" -> SmsPrefs.normalize(address).let { it.isNotEmpty() && it == SmsPrefs.normalize(pattern) }
            "prefix" -> comparable(pattern).let { p -> p.isNotEmpty() && comparable(address).startsWith(p) }
            "wildcard" -> {
                // * any run, ? one character; compared like a sender (digits of a number, a name in lower case)
                val p = if (pattern.any { it.isLetter() }) pattern.lowercase() else pattern.filter { it.isDigit() || it == '*' || it == '?' }
                val regex = p.map { ch -> when (ch) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(ch.toString()) } }.joinToString("")
                p.any { it != '*' } && Regex("^$regex$").matches(comparable(address))
            }
            "keyword" -> body.lowercase().contains(pattern.lowercase())
            else -> false
        }
    }

    fun spamKeys(context: Context): Set<String> =
        SmsDb.get(context).rawQuery("SELECT address FROM spam_senders", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    /** Carries out the route for one delivered text. Never throws: a failure is journalled and the text kept where it got to. */
    fun receive(context: Context, d: Delivered): Outcome {
        val route = decide(d.address, d.body, Blocklist.rules(context), spamKeys(context))
        return when (route) {
            is Route.Private, Route.Blocked -> toVault(context, d, route)
            Route.Spam -> toStore(context, d, route, notify = false)
            Route.Normal -> toStore(context, d, route, notify = true)
        }
    }

    private fun toVault(context: Context, d: Delivered, route: Route): Outcome {
        val record = JSONObject()
            .put("why", if (route is Route.Private) "private" else "blocked")
            .put("direction", "in")
            .put("from", d.address)
            .put("body", d.body)
            .put("smsc_ts", d.smscTs)
            .put("sub_id", d.subId)
            .put("parts", d.parts)
        val sealed = VaultStore.put(context, record, d.receivedAt)
        val notified = route is Route.Private && route.notify != "silent" && SmsNotifier.notifyPrivate(context)
        return Outcome(route, sealed = sealed, notified = notified)
    }

    private fun toStore(context: Context, d: Delivered, route: Route, notify: Boolean): Outcome {
        // the system store first: once it is there the text survives whatever happens next (Phase 0's rule)
        val inserted = SmsStore.insertInbox(context, d.address, d.body, d.receivedAt, d.smscTs, d.subId)
        val saved = try {
            SmsDb.write(context) { db ->
                MessageStore.insertIncoming(
                    db,
                    MessageStore.Incoming(
                        address = d.address, body = d.body, ts = d.receivedAt, subId = d.subId, simSlot = d.simSlot,
                        systemId = inserted.id, systemThreadId = inserted.threadId, folder = if (route == Route.Spam) "spam" else "inbox",
                    ),
                )
            }
        } catch (e: Exception) {
            // the catch-up import brings it in from the system store later
            Log.e(TAG, "sms.sqlite write failed", e)
            EventJournal.append(context, "store_error", JSONObject().put("error", e.message ?: e.javaClass.simpleName))
            null
        }
        val notified = notify && when (val thread = saved?.threadId) {
            null -> SmsNotifier.notifyIncoming(context, inserted.threadId, d.address, d.body) // the store write failed: the plain one
            else -> SmsNotifier.notifyThread(context, thread, d.address) == null
        }
        return Outcome(route, saved?.messageId, saved?.threadId, inserted.id, notified = notified)
    }
}
