package com.guardpulse.parentcontrol.tv.usage

import com.guardpulse.parentcontrol.tv.fallback.LiveForegroundSession
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageTrackerTest {
    @Test
    fun liveSessionAddsElapsedWhenBaselineIsStale() {
        val usage = UsageTracker.applyLiveForegroundSession(
            baselineUsageMs = mapOf("com.video" to 120_000L),
            liveSession = LiveForegroundSession(
                packageName = "com.video",
                startedAt = 1_000L,
                baselineUsageMs = 120_000L,
                dayKey = "2026-07-06",
                lastObservedAt = 31_000L
            ),
            now = 31_000L,
            today = "2026-07-06"
        )

        assertEquals(150_000L, usage["com.video"])
    }

    @Test
    fun liveSessionDoesNotDoubleCountWhenBaselineAlreadyAdvanced() {
        val usage = UsageTracker.applyLiveForegroundSession(
            baselineUsageMs = mapOf("com.video" to 180_000L),
            liveSession = LiveForegroundSession(
                packageName = "com.video",
                startedAt = 1_000L,
                baselineUsageMs = 120_000L,
                dayKey = "2026-07-06",
                lastObservedAt = 31_000L
            ),
            now = 31_000L,
            today = "2026-07-06"
        )

        assertEquals(180_000L, usage["com.video"])
    }

    @Test
    fun liveSessionIsIgnoredAfterDayRollover() {
        val usage = UsageTracker.applyLiveForegroundSession(
            baselineUsageMs = mapOf("com.video" to 180_000L),
            liveSession = LiveForegroundSession(
                packageName = "com.video",
                startedAt = 1_000L,
                baselineUsageMs = 120_000L,
                dayKey = "2026-07-05",
                lastObservedAt = 31_000L
            ),
            now = 31_000L,
            today = "2026-07-06"
        )

        assertEquals(180_000L, usage["com.video"])
    }

    @Test
    fun liveSessionNeverAdvancesPastObservationGrace() {
        val usage = UsageTracker.applyLiveForegroundSession(
            baselineUsageMs = mapOf("com.video" to 120_000L),
            liveSession = LiveForegroundSession(
                packageName = "com.video",
                startedAt = 1_000L,
                baselineUsageMs = 120_000L,
                dayKey = "2026-07-06",
                lastObservedAt = 5_000L
            ),
            now = 31_000L,
            today = "2026-07-06"
        )

        assertEquals(125_500L, usage["com.video"])
    }
}
