package com.guardpulse.parentcontrol.tv.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTitlePolicyTest {
    @Test
    fun knownPlayerPackageAlwaysWalks() {
        assertTrue(MediaTitlePolicy.shouldWalkNodes("com.google.android.youtube.tv", emptyList(), emptySet()))
    }

    @Test
    fun activeSessionEnablesUnknownPackage() {
        assertTrue(MediaTitlePolicy.shouldWalkNodes("com.nuvio.player", emptyList(), setOf("com.nuvio.player")))
    }

    @Test
    fun timecodeTextEnablesUnknownPackage() {
        assertTrue(MediaTitlePolicy.shouldWalkNodes("com.example.app", listOf("12:34"), emptySet()))
    }

    @Test
    fun plainPackageWithoutEvidenceIsSkipped() {
        assertFalse(MediaTitlePolicy.shouldWalkNodes("com.android.tv.settings", listOf("General settings"), emptySet()))
    }

    @Test
    fun timecodeRegexMatchesPlausibleTimes() {
        assertFalse(MediaTitlePolicy.looksLikeVideoEvidence("1920x1080"))
        assertFalse(MediaTitlePolicy.looksLikeVideoEvidence("General settings"))
        assertTrue(MediaTitlePolicy.looksLikeVideoEvidence("12:34"))
        assertTrue(MediaTitlePolicy.looksLikeVideoEvidence("1:02:03"))
    }

    @Test
    fun stremioAndSmartTubeAreKnown() {
        assertTrue("com.stremio.one" in MediaTitlePolicy.knownPlayers)
        assertTrue("org.smarttube.stable" in MediaTitlePolicy.knownPlayers)
    }
}
