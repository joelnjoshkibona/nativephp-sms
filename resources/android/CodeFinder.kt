package com.blutrixx.plugins.nativephp_sms

/**
 * The one-time code a text holds, for the notification's Copy code (milestone 1.4). The same rules as the app's
 * detect.ts, checked against the same cases (resources/js/codeCases.json): 4 to 8 digits, 123-456, or 4 to 10 capitals
 * and digits with at least one of each, only in a text that says it is a code (code, OTP, PIN, verification, siri,
 * msimbo …), never inside a link, an email or a phone number, never an amount after a currency. The one nearest after
 * such a word wins, else the nearest before it. Nothing found is logged.
 */
object CodeFinder {
    private val EMAIL = Regex("""[A-Z0-9._%+-]+@[A-Z0-9-]+(?:\.[A-Z0-9-]+)*\.[A-Z]{2,}""", RegexOption.IGNORE_CASE)
    private val LINK = Regex(
        """\b(?:https?://|www\.)[^\s<>"']+|\b[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|net|org|tz|co|io|app|me|info|biz|ke|ug)(?:/[^\s<>"']*)?(?![\w.@-]*[\w@])""",
        RegexOption.IGNORE_CASE,
    )
    private val USSD = Regex("""\*\d{2,4}(?:\*\d{1,8})*#""")
    private val PHONE = Regex("""(?<![\w+])(?:\+\d[\d -]{6,16}\d|0[67]\d{2}[ -]?\d{3}[ -]?\d{3}|255[67]\d{8})(?!\d)""")
    private val WORD = Regex("""\b(?:code|otp|pin|passcode|password|verification|verify|one[- ]time|siri|msimbo|nenosiri|uthibitisho)\b""", RegexOption.IGNORE_CASE)
    private val CODE = Regex("""(?<![\w])(\d{3}[- ]\d{3}|\d{4,8}|(?=[A-Z0-9]*\d)(?=[A-Z0-9]*[A-Z])[A-Z0-9]{4,10})(?!\w)""")
    private val CURRENCY = Regex("""(?:tsh|tzs|ksh|kes|ugx|usd|sh)\.?\s*$|\$\s*$""", RegexOption.IGNORE_CASE)

    private fun trimEnd(s: String): String {
        var out = s
        while (out.isNotEmpty() && (out.last() in ".,!?;:" || (out.last() == ')' && out.count { it == '(' } < out.count { it == ')' }))) out = out.dropLast(1)
        return out
    }

    /** Where links, emails and numbers are: a code is never inside one. */
    private fun taken(body: String): List<IntRange> {
        val spans = mutableListOf<IntRange>()
        fun add(start: Int, end: Int) {
            if (end > start && spans.none { start < it.last + 1 && end > it.first }) spans += start until end
        }
        EMAIL.findAll(body).forEach { add(it.range.first, it.range.last + 1) }
        LINK.findAll(body).forEach { add(it.range.first, it.range.first + trimEnd(it.value).length) }
        USSD.findAll(body).forEach { add(it.range.first, it.range.last + 1) }
        PHONE.findAll(body).forEach { m ->
            val digits = m.value.count { it.isDigit() }
            if (digits in 8..15) add(m.range.first, m.range.last + 1)
        }
        return spans
    }

    fun find(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val words = WORD.findAll(body).map { it.range.first to it.range.last + 1 }.toList()
        if (words.isEmpty()) return null
        val taken = taken(body)
        var best: String? = null
        var bestScore = Int.MAX_VALUE
        for (m in CODE.findAll(body)) {
            val start = m.range.first
            val end = m.range.last + 1
            if (taken.any { start < it.last + 1 && end > it.first }) continue
            if (CURRENCY.containsMatchIn(body.substring(maxOf(0, start - 6), start))) continue
            val score = words.minOf { (ws, we) -> if (start >= we) start - we else (ws - end) * 2 + 1 }
            if (score < bestScore) {
                bestScore = score
                best = m.value.replace("-", "").replace(" ", "")
            }
        }
        return best
    }
}
