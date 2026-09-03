package com.guardpulse.parentcontrol.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.guardpulse.parentcontrol.shared.PolicyConstants
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Activity tab: a live "Now Watching" card, a day timeline of closed sessions,
 * and a filterable history list. All data arrives via the sync state; the only
 * local computation is the playhead interpolation and the timeline clipping.
 */
@Composable
internal fun ActivityTab(
    selectedDevice: ParentDevice?,
    selectedDeviceId: String?,
    loadingDeviceDetails: Boolean,
    activityCurrent: ParentActivityNow?,
    activityHistory: List<ParentActivityRecord>,
    serverNow: Long
) {
    var selectedDay by remember { mutableStateOf(activityDayKey(serverNow)) }
    var appFilter by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 18.dp, vertical = 14.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (selectedDeviceId == null) {
            item { EmptyPanel("No TV selected", "Pick a TV on the Devices tab to see its activity.") }
            return@LazyColumn
        }
        item {
            GuardSectionTitle("Activity", trailing = selectedDevice?.label)
        }
        item {
            NowWatchingCard(activityCurrent, serverNow)
        }
        item {
            DayPicker(
                history = activityHistory,
                todayKey = activityDayKey(serverNow),
                selectedDay = selectedDay,
                onSelect = { day ->
                    selectedDay = day
                    appFilter = null
                }
            )
        }
        item {
            val (windowStart, windowEnd) = dayWindow(selectedDay)
            val dayRecords = activityHistory
                .filter { activityDayKey(it.startedAt) == selectedDay }
            TimelineCard(dayRecords, windowStart, windowEnd)
        }
        item {
            AppFilterRow(
                records = activityHistory.filter { activityDayKey(it.startedAt) == selectedDay },
                selected = appFilter,
                onSelect = { filter -> appFilter = if (appFilter == filter) null else filter }
            )
        }
        val visibleRecords = activityHistory
            .filter { activityDayKey(it.startedAt) == selectedDay }
            .filter { appFilter == null || it.packageName == appFilter }
            .sortedByDescending { it.startedAt }
        if (visibleRecords.isEmpty()) {
            item {
                EmptyPanel(
                    title = "Nothing recorded",
                    detail = if (selectedDay == activityDayKey(serverNow)) {
                        "Sessions appear here after the kid switches apps or videos."
                    } else {
                        "No sessions were recorded on this day (history is kept 7 days)."
                    }
                )
            }
        } else {
            items(visibleRecords, key = { it.id }) { record ->
                ActivityRecordRow(record)
            }
        }
        if (loadingDeviceDetails) {
            item { Text("Loading…", color = TextMuted, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun NowWatchingCard(current: ParentActivityNow?, serverNow: Long) {
    GuardCard {
        Text("Now Watching", style = MaterialTheme.typography.labelMedium, color = TextMuted)
        Spacer(Modifier.height(6.dp))
        when {
            current == null -> Text(
                "Nothing detected yet",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted
            )
            current.isStale(serverNow) -> {
                Text(
                    "${current.appLabel} — last seen ${formatAge(current.updatedAt, serverNow)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                current.mediaTitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        current.appLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(8.dp))
                    if (current.overlayState == ParentActivityNow.OVERLAY_LOCKED) {
                        StatusLabel("Locked", AlertRed)
                    } else {
                        StatusLabel(
                            if (current.playbackState == "playing") "Playing" else "Idle",
                            if (current.playbackState == "playing") SuccessGreen else TextMuted
                        )
                    }
                }
                current.mediaTitle?.let { title ->
                    Spacer(Modifier.height(4.dp))
                    Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    current.mediaSubtitle?.let { subtitle ->
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val durationMs = current.durationMs
                    val positionMs = current.interpolatedPositionMs(serverNow)
                    if (durationMs != null && durationMs > 0 && positionMs != null) {
                        Spacer(Modifier.height(8.dp))
                        ProgressBar(positionMs.toFloat() / durationMs.toFloat())
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${formatUsage(positionMs)} / ${formatUsage(durationMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    val clamped = fraction.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(SurfaceTint)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(ActionBlue)
        )
    }
}

@Composable
private fun DayPicker(
    history: List<ParentActivityRecord>,
    todayKey: String,
    selectedDay: String,
    onSelect: (String) -> Unit
) {
    val dayFormatter = remember { SimpleDateFormat("EEE dd MMM", Locale.getDefault()) }
    val days = remember(history, todayKey) {
        val seen = history.map { activityDayKey(it.startedAt) }.toSet() + todayKey
        seen.sortedDescending().take(7)
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(days, key = { it }) { day ->
            val selected = day == selectedDay
            val label = if (day == todayKey) "Today" else {
                dayFormatter.format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day)!!)
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Color_White else TextMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) ActionBlue else SurfaceCard)
                    .clickable { onSelect(day) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun TimelineCard(
    dayRecords: List<ParentActivityRecord>,
    windowStart: Long,
    windowEnd: Long
) {
    val segments = remember(dayRecords, windowStart, windowEnd) {
        buildTimelineSegments(dayRecords, windowStart, windowEnd)
    }
    val hourFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    GuardCard {
        GuardSectionTitle("Timeline", trailing = "${hourFormatter.format(Date(windowStart))}–${hourFormatter.format(Date(windowEnd))}")
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(SurfaceTint)
        ) {
            segments.forEach { segment ->
                val widthFraction = (segment.endFraction - segment.startFraction).coerceAtLeast(0f)
                Box(
                    modifier = Modifier
                        .width(0.dp)
                        .weight(widthFraction.coerceAtLeast(0.005f))
                        .height(16.dp)
                        .background(
                            when {
                                segment.hasOverlay -> AlertRed
                                segment.isMedia -> ActionBlue
                                segment.packageName.isEmpty() -> SurfaceTint
                                else -> GuardNavySoft
                            }
                        )
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LegendDot(ActionBlue, "Video")
            LegendDot(GuardNavySoft, "App")
            LegendDot(AlertRed, "Lock overlay")
        }
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .height(8.dp)
                .width(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}

@Composable
private fun AppFilterRow(
    records: List<ParentActivityRecord>,
    selected: String?,
    onSelect: (String) -> Unit
) {
    val apps = remember(records) {
        records.groupBy { it.packageName }
            .map { (pkg, rows) -> pkg to rows.first().appLabel }
            .sortedBy { it.second.lowercase() }
    }
    if (apps.size < 2) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(apps, key = { it.first }) { (pkg, label) ->
            val active = pkg == selected
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                color = if (active) Color_White else TextMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (active) GuardNavy else SurfaceCard)
                    .clickable { onSelect(pkg) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun ActivityRecordRow(record: ParentActivityRecord) {
    val timeFormatter = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    GuardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    record.title ?: record.appLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(record.appLabel)
                        append(" · ")
                        append(timeFormatter.format(Date(record.startedAt)))
                        append("–")
                        append(timeFormatter.format(Date(record.endedAt)))
                        if (record.isMedia()) {
                            record.durationMs?.let { append(" · ").append(formatUsage(it)) }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
            if (record.overlayMs > 0L) {
                StatusLabel("Locked ${formatUsage(record.overlayMs)}", AlertRed)
            } else if (record.isMedia() && record.playbackState == "playing") {
                StatusLabel("Playing", SuccessGreen)
            }
        }
    }
}

/** Inclusive start / exclusive end of the local calendar day for an ISO day key. */
internal fun dayWindow(dayKey: String): Pair<Long, Long> {
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val start = format.parse(dayKey)!!.time
    return start to start + 24L * 60L * 60_000L
}

private val Color_White = androidx.compose.ui.graphics.Color.White
