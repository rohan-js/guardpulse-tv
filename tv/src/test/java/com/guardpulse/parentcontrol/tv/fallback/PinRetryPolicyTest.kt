package com.guardpulse.parentcontrol.tv.fallback

import org.junit.Assert.assertEquals
import org.junit.Test

class PinRetryPolicyTest {
    @Test
    fun retryDelayEscalatesAndCapsAtFiveMinutes() {
        assertEquals(1_000L, FallbackStateStore.pinDelayMs(1))
        assertEquals(1_000L, FallbackStateStore.pinDelayMs(3))
        assertEquals(5_000L, FallbackStateStore.pinDelayMs(4))
        assertEquals(15_000L, FallbackStateStore.pinDelayMs(5))
        assertEquals(30_000L, FallbackStateStore.pinDelayMs(6))
        assertEquals(60_000L, FallbackStateStore.pinDelayMs(7))
        assertEquals(300_000L, FallbackStateStore.pinDelayMs(20))
    }
}
