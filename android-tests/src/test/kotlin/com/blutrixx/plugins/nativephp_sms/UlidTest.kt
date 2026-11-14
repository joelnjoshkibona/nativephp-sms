package com.blutrixx.plugins.nativephp_sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UlidTest {
    @Test fun idsInOneMillisecondCountUpAndSortInOrder() {
        val ids = (1..5000).map { Ulid.next(1_794_650_000_000L) }
        assertEquals(ids.sorted(), ids)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { Regex("^[0-9A-HJKMNP-TV-Z]{26}$").matches(it) })
    }

    @Test fun aLaterMillisecondSortsAfterAndAClockStepBackStillCountsUp() {
        val a = Ulid.next(1_900_000_000_000L)
        val b = Ulid.next(1_900_000_000_001L)
        val c = Ulid.next(1_899_999_999_000L)
        assertTrue(a < b && b < c)
    }

    @Test fun theTimeIsTheFirstTenCharacters() {
        assertEquals("01ARYZ6S41", Ulid.encode(1_469_918_176_385L, 0, 0).take(10)) // the ULID spec's example: ulid(1469918176385)
        assertEquals("0000000000" + "0".repeat(16), Ulid.encode(0, 0, 0))
        assertEquals("7ZZZZZZZZZ" + "Z".repeat(16), Ulid.encode((1L shl 48) - 1, 0xFFFF, -1L))
    }
}
