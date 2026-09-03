package com.guardpulse.parentcontrol.tv.sync

import com.guardpulse.parentcontrol.tv.policy.AppPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectivePoliciesTest {
    private val youtube = "com.google.android.youtube.tv"
    private val settings = "com.guardpulse.policy.settings_accessibility"

    @Test
    fun noActiveModeReturnsBaseUntouched() {
        val base = mapOf(youtube to AppPolicy(manualBlocked = true))
        val merged = EffectivePolicies.merge(base, null)
        assertEquals(base, merged)
    }

    @Test
    fun modeOverridesOnlyAppsItCovers() {
        val base = mapOf(
            youtube to AppPolicy(manualBlocked = true),
            "com.example.other" to AppPolicy(manualBlocked = false)
        )
        val mode = mapOf(youtube to AppPolicy(manualBlocked = false))
        val merged = EffectivePolicies.merge(base, mode)
        // Mode explicitly allows YouTube — the mode's customization wins.
        assertEquals(false, merged[youtube]!!.manualBlocked)
        // The mode does not mention the other app — the root toggle still applies.
        assertEquals(false, merged["com.example.other"]!!.manualBlocked)
    }

    @Test
    fun rootToggleAppliesToUncoveredAppsWhileModeActive() {
        val base = mapOf(youtube to AppPolicy(manualBlocked = true))
        val mode = mapOf("com.example.study" to AppPolicy(manualBlocked = true))
        val merged = EffectivePolicies.merge(base, mode)
        assertEquals(true, merged[youtube]!!.manualBlocked)
        assertEquals(true, merged["com.example.study"]!!.manualBlocked)
    }

    @Test
    fun defaultLockedSectionsStillApplyWhenNeitherSourceMentionsThem() {
        val merged = EffectivePolicies.merge(emptyMap(), emptyMap())
        assertTrue(merged[settings]!!.manualBlocked)
    }

    @Test
    fun explicitPolicyBeatsDefaultLock() {
        val mode = mapOf(settings to AppPolicy(manualBlocked = false))
        val merged = EffectivePolicies.merge(emptyMap(), mode)
        assertEquals(false, merged[settings]!!.manualBlocked)
    }
}
