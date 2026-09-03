package com.guardpulse.parentcontrol.parent

import com.guardpulse.parentcontrol.shared.PolicyConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentActivityReducersTest {
    private fun record(
        id: String,
        startedAt: Long,
        endedAt: Long,
        isMedia: Boolean = false,
        overlayMs: Long = 0L,
        packageName: String = "com.example.app"
    ) = ParentActivityRecord(
        id = id,
        type = if (isMedia) "media" else "app",
        packageName = packageName,
        appLabel = packageName.substringAfterLast('.'),
        title = if (isMedia) "Title $id" else null,
        startedAt = startedAt,
        endedAt = endedAt,
        overlayMs = overlayMs
    )

    @Test
    fun dayKeyUsesLocalCalendarDay() {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val instant = format.parse("2026-09-03 12:00:00")!!.time
        val key = activityDayKey(instant)
        val (windowStart, windowEnd) = dayWindow(key)
        // The window must cover the whole local day containing the instant.
        assertTrue(windowStart <= instant && instant < windowEnd)
        assertEquals(key, activityDayKey(windowStart))
        assertEquals(key, activityDayKey(windowEnd - 1))
    }

    @Test
    fun dayWindowSpans24Hours() {
        val (start, end) = dayWindow("2026-09-03")
        assertEquals(24L * 60L * 60_000L, end - start)
    }

    @Test
    fun timelineSegmentsClipToWindow() {
        // Window 10:00–11:00; session 09:50–10:10 overlaps the first 10 minutes.
        val windowStart = dayWindow("2026-09-03").first + 10L * 60_000L
        val windowEnd = windowStart + 60L * 60_000L
        val segments = buildTimelineSegments(
            listOf(record("a", windowStart - 10L * 60_000L, windowStart + 10L * 60_000L)),
            windowStart,
            windowEnd
        )
        assertEquals(1, segments.size)
        assertEquals(0f, segments[0].startFraction, 0.001f)
        assertEquals(10f / 60f, segments[0].endFraction, 0.001f)
    }

    @Test
    fun timelineMarksOverlayAndMediaDistinctly() {
        val windowStart = dayWindow("2026-09-03").first
        val windowEnd = windowStart + 60L * 60_000L
        val segments = buildTimelineSegments(
            listOf(
                record("m", windowStart, windowStart + 30L * 60_000L, isMedia = true, overlayMs = 60_000L),
                record("a", windowStart + 30L * 60_000L, windowEnd)
            ),
            windowStart,
            windowEnd
        )
        assertEquals(2, segments.size)
        assertTrue(segments[0].isMedia)
        assertTrue(segments[0].hasOverlay)
        assertFalse(segments[1].isMedia)
        assertFalse(segments[1].hasOverlay)
        assertEquals(1f, segments[1].endFraction, 0.001f)
    }

    @Test
    fun emptyTimelineYieldsIdleSegment() {
        val windowStart = dayWindow("2026-09-03").first
        val segments = buildTimelineSegments(emptyList(), windowStart, windowStart + 3_600_000L)
        assertEquals(1, segments.size)
        assertEquals(0f, segments[0].startFraction, 0.001f)
        assertEquals(1f, segments[0].endFraction, 0.001f)
    }

    @Test
    fun sessionsOutsideWindowAreDropped() {
        val windowStart = dayWindow("2026-09-03").first
        val segments = buildTimelineSegments(
            listOf(record("early", windowStart - 7_200_000L, windowStart - 3_600_000L)),
            windowStart,
            windowStart + 3_600_000L
        )
        assertEquals(1, segments.size)
        assertEquals("No activity", segments[0].appLabel)
    }

    @Test
    fun nowWatchingInterpolatesOnlyWhilePlaying() {
        val now = 1_000_000L
        val captured = now - 10_000L
        val playing = ParentActivityNow(
            packageName = "com.example",
            appLabel = "Example",
            appStartedAt = captured,
            playbackState = "playing",
            positionMs = 60_000L,
            durationMs = 120_000L,
            positionCapturedAt = captured,
            playbackSpeed = 1f,
            updatedAt = captured
        )
        assertEquals(70_000L, playing.interpolatedPositionMs(now))
        // Clamped to duration.
        assertEquals(120_000L, playing.copy(positionMs = 119_000L).interpolatedPositionMs(now))
        val paused = playing.copy(playbackState = "paused")
        assertEquals(60_000L, paused.interpolatedPositionMs(now))
    }

    @Test
    fun nowWatchingStaleness() {
        val current = ParentActivityNow(
            packageName = "com.example",
            appLabel = "Example",
            appStartedAt = 0L,
            updatedAt = 100_000L
        )
        assertTrue(current.isStale(100_000L + ParentActivityNow.STALE_AFTER_MS + 1))
        assertFalse(current.isStale(100_000L + 1_000L))
    }

    @Test
    fun waitingForTvWhenTvNeverConfirmed() {
        val desired = ControlSnapshotV2Fixture(desiredRevision = true)
        val state = ParentSyncUiState(
            desiredControl = desired,
            confirmedControl = null,
            appliedRevision = com.guardpulse.parentcontrol.shared.SyncAppliedRevision(
                revisionId = "older",
                status = PolicyConstants.SYNC_STATUS_APPLIED
            )
        )
        assertTrue(state.isAppPolicyWaitingForTv("com.example.app"))
    }

    @Test
    fun waitingForTvClearsWhenConfirmedMatches() {
        val desired = ControlSnapshotV2Fixture(desiredRevision = true)
        val state = ParentSyncUiState(
            desiredControl = desired,
            confirmedControl = desired,
            appliedRevision = com.guardpulse.parentcontrol.shared.SyncAppliedRevision(
                revisionId = desired.revisionId,
                status = PolicyConstants.SYNC_STATUS_APPLIED
            )
        )
        assertFalse(state.isAppPolicyWaitingForTv("com.example.app"))
    }

    @Test
    fun approvedButUnappliedRequestsAreVisible() {
        fun request(tvApplyStatus: String?) = UnlockRequest(
            requestId = "r",
            packageName = "com.example",
            reason = "manual",
            status = PolicyConstants.UNLOCK_APPROVED,
            createdAt = 1L,
            expiresAt = null,
            tvApplyStatus = tvApplyStatus
        )
        val waiting = listOf(request(null), request("pending-marker"))
        assertTrue(
            waiting.all {
                it.status == PolicyConstants.UNLOCK_APPROVED &&
                    it.tvApplyStatus != PolicyConstants.SYNC_STATUS_APPLIED
            }
        )
        assertFalse(
            request(PolicyConstants.SYNC_STATUS_APPLIED).let {
                it.status == PolicyConstants.UNLOCK_APPROVED &&
                    it.tvApplyStatus != PolicyConstants.SYNC_STATUS_APPLIED
            }
        )
    }

    private fun ControlSnapshotV2Fixture(desiredRevision: Boolean): com.guardpulse.parentcontrol.shared.ControlSnapshotV2 {
        val apps = mapOf(
            "com.example.app" to com.guardpulse.parentcontrol.shared.ControlAppRule(
                packageName = "com.example.app",
                manualBlocked = true
            )
        )
        return com.guardpulse.parentcontrol.shared.ControlSnapshotV2(
            revisionId = if (desiredRevision) "desired-1" else "confirmed-1",
            apps = apps
        )
    }
}
