package com.guardpulse.parentcontrol.parent

import com.guardpulse.parentcontrol.shared.DeviceFreshness
import com.guardpulse.parentcontrol.shared.PolicyConstants
import com.guardpulse.parentcontrol.shared.SyncAppliedRevision
import com.guardpulse.parentcontrol.shared.SyncDesiredRevision

internal fun deriveSyncStatus(
    phoneConnected: Boolean,
    controlAvailability: ControlAvailability,
    protocolVersion: Int,
    desired: SyncDesiredRevision?,
    applied: SyncAppliedRevision,
    freshness: DeviceFreshness
): ParentSyncStatus = when {
    controlAvailability == ControlAvailability.INVALID -> ParentSyncStatus.FAILED
    !phoneConnected -> ParentSyncStatus.SENDING
    controlAvailability == ControlAvailability.VALID &&
        protocolVersion < PolicyConstants.SYNC_PROTOCOL_VERSION -> ParentSyncStatus.TV_UPDATE_REQUIRED
    desired?.revisionId != null &&
        applied.revisionId == desired.revisionId &&
        applied.status == PolicyConstants.SYNC_STATUS_FAILED -> ParentSyncStatus.FAILED
    desired?.revisionId != null &&
        applied.revisionId != desired.revisionId &&
        freshness == DeviceFreshness.OFFLINE -> ParentSyncStatus.OFFLINE_PENDING
    desired?.revisionId != null &&
        applied.revisionId != desired.revisionId &&
        freshness == DeviceFreshness.DELAYED -> ParentSyncStatus.DELAYED
    desired?.revisionId != null && applied.revisionId != desired.revisionId ->
        ParentSyncStatus.WAITING_FOR_TV
    desired?.revisionId != null && applied.revisionId == desired.revisionId ->
        ParentSyncStatus.APPLIED
    freshness == DeviceFreshness.DELAYED -> ParentSyncStatus.DELAYED
    else -> ParentSyncStatus.IDLE
}

internal fun matchingRuntimeStates(
    revisionId: String,
    runtimeStates: Map<String, ParentState>
): Map<String, ParentState> = runtimeStates.filterValues { it.controlRevisionId == revisionId }

internal fun effectiveUsageMs(state: ParentState, serverNow: Long): Long {
    if (!state.foregroundActive) return state.usageMsToday.coerceAtLeast(0L)
    val capturedAt = state.usageCapturedAt ?: return state.usageMsToday.coerceAtLeast(0L)
    val elapsed = (serverNow - capturedAt)
        .coerceIn(0L, PolicyConstants.FOREGROUND_USAGE_EXTRAPOLATION_MAX_MS)
    return (state.usageMsToday + elapsed).coerceAtLeast(0L)
}

/** Local (device-timezone) ISO day key of an epoch-millis instant. */
internal fun activityDayKey(epochMs: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(epochMs))

/**
 * One display segment of the day timeline. `startFraction`/`endFraction` are
 * positions within the rendered window [windowStart, windowEnd]; a segment is
 * clipped to that window and dropped when it ends up empty.
 */
internal data class TimelineSegment(
    val packageName: String,
    val appLabel: String,
    val isMedia: Boolean,
    val hasOverlay: Boolean,
    val startFraction: Float,
    val endFraction: Float
)

/**
 * Builds the day-timeline segments from closed session rows. Sessions before
 * or after the window only contribute their overlapping part; an empty window
 * yields a single full-width idle segment.
 */
internal fun buildTimelineSegments(
    records: List<ParentActivityRecord>,
    windowStart: Long,
    windowEnd: Long
): List<TimelineSegment> {
    val span = (windowEnd - windowStart).coerceAtLeast(1L)
    val segments = records
        .filter { it.endedAt > windowStart && it.startedAt < windowEnd }
        .sortedBy { it.startedAt }
        .map { record ->
            val clippedStart = record.startedAt.coerceAtLeast(windowStart)
            val clippedEnd = record.endedAt.coerceAtMost(windowEnd)
            TimelineSegment(
                packageName = record.packageName,
                appLabel = record.appLabel,
                isMedia = record.isMedia(),
                hasOverlay = record.overlayMs > 0L,
                startFraction = (clippedStart - windowStart).toFloat() / span,
                endFraction = (clippedEnd - windowStart).toFloat() / span
            )
        }
        .filter { it.endFraction - it.startFraction > 0.001f }
    if (segments.isEmpty()) {
        return listOf(
            TimelineSegment(
                packageName = "",
                appLabel = "No activity",
                isMedia = false,
                hasOverlay = false,
                startFraction = 0f,
                endFraction = 1f
            )
        )
    }
    return segments
}
