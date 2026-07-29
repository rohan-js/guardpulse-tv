package com.guardpulse.parentcontrol.tv.fallback

import com.guardpulse.parentcontrol.shared.PolicyConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LockLaunchGuardTest {
    @Test
    fun homeThenSecondLockedAppNeverReopensFirstTarget() {
        val guard = LockLaunchGuard()
        val first = guard.evaluate(
            "com.first",
            FallbackDecision(true, PolicyConstants.BLOCK_REASON_MANUAL, "com.first"),
            1_000L
        )
        assertEquals("com.first", first?.packageName)

        assertNull(guard.evaluate("launcher", FallbackDecision(false), 2_000L))

        val second = guard.evaluate(
            "com.second",
            FallbackDecision(true, PolicyConstants.BLOCK_REASON_MANUAL, "com.second"),
            3_000L
        )
        assertEquals("com.second", second?.packageName)
    }

    @Test
    fun duplicateObservationIsSuppressedWithoutChangingTarget() {
        val guard = LockLaunchGuard()
        val decision = FallbackDecision(true, PolicyConstants.BLOCK_REASON_MANUAL, "com.video")
        assertEquals("com.video", guard.evaluate("com.video", decision, 1_000L)?.packageName)
        assertNull(guard.evaluate("com.video", decision, 2_000L))
        assertEquals("com.video", guard.evaluate("com.video", decision, 3_000L)?.packageName)
    }
}
