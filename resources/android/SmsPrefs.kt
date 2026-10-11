package com.blutrixx.plugins.nativephp_sms

import android.content.Context

/**
 * Small native-side settings store. Everything the headless receive path needs
 * (blocklist, whether to call PHP at all, a pending "compose" request) must be
 * readable WITHOUT booting PHP, so it lives in SharedPreferences, not in the
 * Laravel database.
 */
object SmsPrefs {
    private const val FILE = "nativephp_sms"
    private const val KEY_BLOCKLIST = "blocklist"
    private const val KEY_EPHEMERAL = "ephemeral_enabled"
    private const val KEY_COMPOSE_TO = "pending_compose_to"
    private const val KEY_COMPOSE_BODY = "pending_compose_body"
    private const val KEY_COMPOSE_PENDING = "pending_compose"

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

    fun setBlocklist(context: Context, numbers: Collection<String>) {
        val normalized = numbers.map { normalize(it) }.filter { it.isNotEmpty() }.toSet()
        prefs(context).edit().putStringSet(KEY_BLOCKLIST, normalized).apply()
    }

    fun blocklistSize(context: Context): Int =
        prefs(context).getStringSet(KEY_BLOCKLIST, emptySet())?.size ?: 0

    fun isBlocked(context: Context, address: String?): Boolean {
        val key = normalize(address)
        if (key.isEmpty()) return false
        return prefs(context).getStringSet(KEY_BLOCKLIST, emptySet())?.contains(key) == true
    }

    fun setEphemeralEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EPHEMERAL, enabled).apply()
    }

    fun isEphemeralEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EPHEMERAL, false)

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
}
