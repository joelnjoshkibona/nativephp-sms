package com.blutrixx.plugins.nativephp_sms

/**
 * The vault's recovery key as a person writes it down: 128 random bits in base32 (no 0/1/8/9 to misread: the RFC 4648
 * alphabet), 26 characters in groups of 4 ("ABCD-EFGH-…-WX"). Parsing ignores case, spaces and dashes, and reads 0 as O,
 * 1 as I and 8 as B, so a copy made by hand still works.
 */
object RecoveryKey {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun format(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return out.chunked(4).joinToString("-")
    }

    /** The bytes of a typed recovery key, or null when it is not one (26 base32 characters for 16 bytes). */
    fun parse(typed: String): ByteArray? {
        val clean = typed.uppercase().filter { !it.isWhitespace() && it != '-' }
            .map { when (it) { '0' -> 'O'; '1' -> 'I'; '8' -> 'B'; else -> it } }.joinToString("")
        if (clean.length != 26 || clean.any { it !in ALPHABET }) return null
        val out = ByteArray(16)
        var buffer = 0
        var bits = 0
        var i = 0
        for (ch in clean) {
            buffer = (buffer shl 5) or ALPHABET.indexOf(ch)
            bits += 5
            if (bits >= 8) {
                if (i < out.size) out[i++] = (buffer shr (bits - 8)).toByte()
                bits -= 8
            }
        }
        return if (i == out.size) out else null
    }
}
