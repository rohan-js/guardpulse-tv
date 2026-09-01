package com.guardpulse.parentcontrol.tv.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaAccessibilityParserTest {
    @Test
    fun parsesYouTubeTitleAndProgress() {
        val result = MediaAccessibilityParser.parse(
            "com.google.android.youtube.tv",
            listOf(
                AccessibilityTextNode("Example video", "com.google.android.youtube.tv:id/title"),
                AccessibilityTextNode("Pause"),
                AccessibilityTextNode("12:34 / 45:00")
            )
        )

        assertEquals("Example video", result?.title)
        assertEquals(754_000L, result?.positionMs)
        assertEquals(2_700_000L, result?.durationMs)
        assertEquals(MediaObservation.PLAYBACK_PLAYING, result?.playbackState)
        assertEquals(MediaObservation.CONFIDENCE_HIGH, result?.confidence)
    }

    @Test
    fun usesWindowTitleAsFallback() {
        val result = MediaAccessibilityParser.parse(
            "com.google.android.youtube.tv",
            listOf(
                AccessibilityTextNode("Example video", "__window_title__"),
                AccessibilityTextNode("Pause")
            )
        )

        assertEquals("Example video", result?.title)
        assertEquals(MediaObservation.PLAYBACK_PLAYING, result?.playbackState)
    }

    @Test
    fun doesNotInventGenericTitleWithoutMediaSignals() {
        val result = MediaAccessibilityParser.parse(
            "com.example.app",
            listOf(AccessibilityTextNode("Welcome to settings"))
        )

        assertNull(result)
    }

    @Test
    fun parsesHourDuration() {
        assertEquals(3_723_000L, MediaAccessibilityParser.parseTime("1:02:03"))
    }
}
