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

    @Test
    fun ownPackageNotLockedDecisionKeepsDedupeKeyAlive() {
        val guard = LockLaunchGuard()
        val decision = FallbackDecision(true, PolicyConstants.BLOCK_REASON_MANUAL, "com.video")
        assertEquals("com.video", guard.evaluate("com.video", decision, 1_000L)?.packageName)
        // The wall (own package) foregrounds itself; its not-locked decision
        // must not reset the dedupe key, or every covered-app event relaunches
        // the wall and wipes the PIN being typed.
        assertNull(guard.evaluate("com.guardpulse", FallbackDecision(false), 1_100L, isOwnPackage = true))
        assertNull(guard.evaluate("com.video", decision, 1_200L))
        // Still inside the 1.5 s window from the last real launch.
        assertNull(guard.evaluate("com.video", decision, 2_400L))
        assertEquals("com.video", guard.evaluate("com.video", decision, 2_600L)?.packageName)
    }
}
