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
    fun contentDescTitleBeatsDetailText() {
        // Nuvio movie cards: the title lives in content-description while text
        // holds details ("2h 1m"). Tagged identically to the walker output.
        val result = MediaAccessibilityParser.parse(
            "com.nuvio.app",
            listOf(
                AccessibilityTextNode("I Swear", TvActivityTracker.CONTENT_DESCRIPTION_VIEW_ID),
                AccessibilityTextNode("Movie • Biography"),
                AccessibilityTextNode("2h 1m"),
                AccessibilityTextNode("8.4")
            )
        )

        assertEquals("I Swear", result?.title)
    }

    @Test
    fun viewIdTitleStillWinsOverContentDesc() {
        val result = MediaAccessibilityParser.parse(
            "com.nuvio.app",
            listOf(
                AccessibilityTextNode("View title", "com.nuvio.app:id/title"),
                AccessibilityTextNode("Desc title", TvActivityTracker.CONTENT_DESCRIPTION_VIEW_ID)
            )
        )

        assertEquals("View title", result?.title)
    }

    @Test
    fun parsesHourDuration() {
        assertEquals(3_723_000L, MediaAccessibilityParser.parseTime("1:02:03"))
    }
}
