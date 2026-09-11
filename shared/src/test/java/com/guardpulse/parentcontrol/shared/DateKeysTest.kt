package com.guardpulse.parentcontrol.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DateKeysTest {
    @Test
    fun todayUsesStableLocalDayShape() {
        assertTrue(DateKeys.today().matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }

    @Test
    fun dayKeyUtcMatchesPlainUtcFormatting() {
        // 2026-09-10T12:34:56Z
        val epoch = 1_789_043_696_000L
        assertEquals("2026-09-10", DateKeys.dayKeyUtc(epoch))
    }

    @Test
    fun dayKeyUtcCacheRespectsUtcDayBoundary() {
        val utcMidnight = 1_789_084_800_000L // 2026-09-11T00:00:00Z
        assertEquals("2026-09-11", DateKeys.dayKeyUtc(utcMidnight))
        assertEquals("2026-09-10", DateKeys.dayKeyUtc(utcMidnight - 1L))
        // Repeated reads within the same UTC day must return the cached value.
        assertEquals("2026-09-10", DateKeys.dayKeyUtc(utcMidnight - 1L))
    }

    @Test
    fun utcDaysAgoIsPureSubtractionAcrossDst() {
        val epoch = 1_789_043_696_000L
        assertEquals(DateKeys.dayKeyUtc(epoch - 7L * 24 * 60 * 60_000), DateKeys.utcDaysAgo(7, epoch))
    }
}
