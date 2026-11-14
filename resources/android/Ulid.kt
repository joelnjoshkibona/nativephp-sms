package com.blutrixx.plugins.nativephp_sms

import java.security.SecureRandom

/**
 * ULIDs made on the phone: 48 bits of milliseconds and 80 random bits, in Crockford base32 (26 characters). Ids made in the
 * same millisecond count up from the previous one, so sorting by id is sorting by creation, which the sync cursor and the
 * message list rely on.
 */
object Ulid {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()
    private var lastMs = -1L
    private var hi = 0L // top 16 of the 80 random bits
    private var lo = 0L // bottom 64

    @Synchronized
    fun next(now: Long = System.currentTimeMillis()): String {
        if (now > lastMs) {
            lastMs = now
            hi = (random.nextInt() and 0xFFFF).toLong()
            lo = random.nextLong()
        } else {
            // same (or an earlier, if the clock stepped back) millisecond: one more than the last id
            lo += 1
            if (lo == 0L) {
                hi = (hi + 1) and 0xFFFF
                if (hi == 0L) lastMs += 1 // 2^80 ids in one millisecond: move on rather than repeat
            }
        }
        return encode(lastMs, hi, lo)
    }

    internal fun encode(ms: Long, hi: Long, lo: Long): String {
        val out = CharArray(26)
        var t = ms
        for (i in 9 downTo 0) {
            out[i] = ALPHABET[(t and 31).toInt()]
            t = t ushr 5
        }
        // 80 random bits as 16 characters: hi (16 bits) then lo (64 bits), 5 bits at a time from the end
        var low = lo
        var high = hi
        for (i in 25 downTo 10) {
            out[i] = ALPHABET[(low and 31).toInt()]
            low = (low ushr 5) or ((high and 31) shl 59)
            high = high ushr 5
        }
        return String(out)
    }
}
