package com.guardpulse.parentcontrol.tv.system

import com.guardpulse.parentcontrol.shared.DateKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SystemTimeAndDayKeysTest {
    @Test
    fun utcDayKeyIsIndependentOfDeviceTimezone() {
        // 2026-09-02 00:30 IST (+05:30) is still 2026-09-01 in UTC: a timezone
        // shift must not roll the usage day over.
        val istMidnight = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
            set(2026, 8, 2, 0, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals("2026-09-01", DateKeys.dayKeyUtc(istMidnight))

        val utcDayBoundary = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, 8, 2, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals("2026-09-02", DateKeys.dayKeyUtc(utcDayBoundary))
        assertEquals("2026-09-01", DateKeys.dayKeyUtc(utcDayBoundary - 1))
    }

    @Test
    fun utcDaysAgoSubtractsWholeDays() {
        val instant = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, 8, 2, 15, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals("2026-09-02", DateKeys.utcDaysAgo(0, instant))
        assertEquals("2026-09-01", DateKeys.utcDaysAgo(1, instant))
        assertEquals("2026-08-26", DateKeys.utcDaysAgo(7, instant))
    }

    @Test
    fun utcDayKeysCompareLexicographicallyLikeChronology() {
        assertTrue("2026-08-31" < "2026-09-01")
        assertTrue("2026-09-01" < "2026-09-02")
        assertTrue("2025-12-31" < "2026-01-01")
    }
}
