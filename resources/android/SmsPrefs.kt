package com.blutrixx.plugins.nativephp_sms

import android.content.Context

/**
 * Small native-side settings store. Flags the headless paths need (whether to call PHP at all, a pending "compose"
 * request, the last boot) must be readable WITHOUT booting PHP, so they live in SharedPreferences, not in the Laravel
 * database. Messages, the blocklist and the journal live in sms.sqlite (SmsDb).
 */
object SmsPrefs {
    private const val FILE = "nativephp_sms"
    private const val KEY_BLOCKLIST = "blocklist"
    private const val KEY_EPHEMERAL = "ephemeral_enabled"
    private const val KEY_KEEP_ALIVE = "keep_alive_enabled"
    private const val KEY_COMPOSE_TO = "pending_compose_to"
    private const val KEY_COMPOSE_BODY = "pending_compose_body"
    private const val KEY_COMPOSE_PENDING = "pending_compose"
    private const val KEY_OPEN_THREAD = "pending_open_thread"
    private const val KEY_BOOT_AT = "last_boot_at"
    private const val KEY_BOOT_REASON = "last_boot_reason"
    private const val KEY_BOOT_KEEP_ALIVE = "last_boot_keep_alive"
    private const val KEY_BOOT_COMPLETED_AT = "last_boot_completed_at"
    private const val KEY_DEFAULT_SEEN = "default_seen"
    private const val KEY_DEFAULT_LOST_AT = "default_lost_at"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Comparison key for a phone number or sender id.
     *  - Alphanumeric senders ("MPESA") compare case-insensitively as-is.
     *  - Numbers compare on their last 9 digits, so "+255712345678",
     *    "0712345678" and "255 712 345 678" are the same sender. Short codes
     *    (9 digits or fewer) compare exactly.
     */
    fun normalize(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        if (trimmed.any { it.isLetter() }) return trimmed.lowercase()
        val digits = trimmed.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }

    /** The blocked numbers kept here before sms.sqlite (Blocklist moves them once); answers them and forgets them. */
    fun takeLegacyBlocklist(context: Context): Set<String> {
        val old = prefs(context).getStringSet(KEY_BLOCKLIST, emptySet()).orEmpty().toSet()
        prefs(context).edit().remove(KEY_BLOCKLIST).apply()
        return old
    }

    fun setEphemeralEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EPHEMERAL, enabled).apply()
    }

    fun isEphemeralEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EPHEMERAL, false)

    fun setKeepAliveEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_ALIVE, enabled).apply()
    }

    fun isKeepAliveEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_ALIVE, false)

    /** The last time the boot receiver ran (epoch ms), why, and what keep-alive did: proof that the app starts by itself. */
    /**
     * The boot receiver's last run. A run for BOOT_COMPLETED is also kept on its own (lastBootCompletedAt): it is the proof
     * that Android lets the app start by itself, and a later run for an app update must not erase it.
     */
    fun setLastBoot(context: Context, reason: String, keepAlive: String) {
        val now = System.currentTimeMillis()
        val edit = prefs(context).edit().putLong(KEY_BOOT_AT, now).putString(KEY_BOOT_REASON, reason).putString(KEY_BOOT_KEEP_ALIVE, keepAlive)
        if (reason == "boot_completed") edit.putLong(KEY_BOOT_COMPLETED_AT, now)
        edit.apply()
    }

    fun lastBootCompletedAt(context: Context): Long = prefs(context).getLong(KEY_BOOT_COMPLETED_AT, 0L)

    /**
     * Whether this app has been the default SMS app, and when it was found to be no longer (0: it is, or never was): the
     * person switched to another SMS app and may have forgotten to switch back, or something else took the role.
     */
    fun wasDefault(context: Context): Boolean = prefs(context).getBoolean(KEY_DEFAULT_SEEN, false)
    fun defaultLostAt(context: Context): Long = prefs(context).getLong(KEY_DEFAULT_LOST_AT, 0L)
    fun markDefault(context: Context) {
        prefs(context).edit().putBoolean(KEY_DEFAULT_SEEN, true).putLong(KEY_DEFAULT_LOST_AT, 0L).apply()
    }
    fun setDefaultLost(context: Context, at: Long) {
        prefs(context).edit().putLong(KEY_DEFAULT_LOST_AT, at).apply()
    }

    fun lastBootAt(context: Context): Long = prefs(context).getLong(KEY_BOOT_AT, 0L)
    fun lastBootReason(context: Context): String = prefs(context).getString(KEY_BOOT_REASON, "") ?: ""
    fun lastBootKeepAlive(context: Context): String = prefs(context).getString(KEY_BOOT_KEEP_ALIVE, "") ?: ""

    fun setPendingCompose(context: Context, to: String, body: String) {
        prefs(context).edit()
            .putBoolean(KEY_COMPOSE_PENDING, true)
            .putString(KEY_COMPOSE_TO, to)
            .putString(KEY_COMPOSE_BODY, body)
            .apply()
    }

    /** Returns (to, body) once, then clears it; null when nothing is pending. */
    fun takePendingCompose(context: Context): Pair<String, String>? {
        val p = prefs(context)
        if (!p.getBoolean(KEY_COMPOSE_PENDING, false)) return null
        val result = Pair(p.getString(KEY_COMPOSE_TO, "") ?: "", p.getString(KEY_COMPOSE_BODY, "") ?: "")
        p.edit().remove(KEY_COMPOSE_PENDING).remove(KEY_COMPOSE_TO).remove(KEY_COMPOSE_BODY).apply()
        return result
    }

    /** The conversation a tapped notification asked to open (OpenThreadActivity). */
    fun setPendingOpen(context: Context, threadId: String) {
        prefs(context).edit().putString(KEY_OPEN_THREAD, threadId).apply()
    }

    /** Returns the conversation to open once, then forgets it; null when none. */
    fun takePendingOpen(context: Context): String? {
        val p = prefs(context)
        val id = p.getString(KEY_OPEN_THREAD, null) ?: return null
        p.edit().remove(KEY_OPEN_THREAD).apply()
        return id
    }
}
