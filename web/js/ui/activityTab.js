/**
 * Port of ActivityFeature.kt — Now Watching card (staleness 90s, overlay
 * locked chip, playback extrapolation), day picker (7 chips), timeline
 * segments, app filter chips, and history rows.
 */
import {
  h, card, emptyPanel, statusLabel, formatUsage, formatTimestamp, formatAge, statusDot,
} from './components.js';
import {
  isActivityStale, interpolatedPositionMs, buildTimelineSegments, activityDayKey, isMediaRecord,
} from '../reducers.js';

let selectedDay = null;
let appFilter = null;

export function renderActivityTab(state) {
  if (!state.selectedDeviceId) {
    return emptyPanel('No TV selected', 'Pick a TV on the Devices tab to see its activity.');
  }
  selectedDay = normalizeSelectedDay(state);
  return h('div', { style: { display: 'flex', flexDirection: 'column', gap: '16px' } },
    nowWatchingCard(state),
    dayPicker(state),
    timelineCard(state),
    historyCard(state),
  );
}

function normalizeSelectedDay(state) {
  const keys = availableDayKeys(state);
  if (selectedDay && keys.includes(selectedDay)) return selectedDay;
  return keys[0] ?? activityDayKey(state.serverNow);
}

function availableDayKeys(state) {
  const keys = new Set(state.activityHistory.map((r) => activityDayKey(r.startedAt)));
  keys.add(activityDayKey(state.serverNow));
  return [...keys].sort().reverse().slice(0, 7);
}

/** ActivityFeature.kt NowWatchingCard (lines 121-182). */
function nowWatchingCard(state) {
  const current = state.activityCurrent;
  const now = state.serverNow;
  const children = [h('div', { class: 'card-title' }, 'Now Watching')];

  if (current == null) {
    children.push(h('div', { class: 'muted' }, 'Nothing detected yet'));
  } else if (isActivityStale(current, now)) {
    children.push(h('div', { style: { fontWeight: 700 } }, `${current.appLabel} — last seen ${formatAge(current.updatedAt, now)}`));
    if (current.mediaTitle) children.push(h('div', { class: 'small muted' }, current.mediaTitle));
  } else {
    children.push(h('div', { class: 'row' },
      h('div', { class: 'grow', style: { fontWeight: 700, fontSize: '16px' } }, current.appLabel),
      current.overlayState === 'locked'
        ? statusLabel('Locked', 'var(--alert-red)')
        : statusLabel(current.playbackState === 'playing' ? 'Playing' : 'Idle',
          current.playbackState === 'playing' ? 'var(--success-green)' : 'var(--text-muted)'),
    ));
    if (current.mediaTitle) children.push(h('div', { style: { fontWeight: 600 } }, current.mediaTitle));
    if (current.mediaSubtitle) children.push(h('div', { class: 'small muted ellipsis' }, current.mediaSubtitle));
    const position = interpolatedPositionMs(current, now);
    if (current.durationMs != null && current.durationMs > 0 && position != null) {
      const fraction = Math.min(1, Math.max(0, position / current.durationMs));
      children.push(h('div', { class: 'progress-track' },
        h('div', { class: 'progress-fill', 'data-live-progress': '1', style: { width: `${fraction * 100}%` } })));
      children.push(h('div', { class: 'small muted', 'data-live-position': '1' }, `${formatUsage(position)} / ${formatUsage(current.durationMs)}`));
    }
  }
  return card(...children);
}

/** ActivityFeature.kt DayPicker (lines 205-235). */
function dayPicker(state) {
  const keys = availableDayKeys(state);
  return h('div', { class: 'chip-row' },
    keys.map((key) => {
      const label = key === activityDayKey(state.serverNow) ? 'Today' : dayChipLabel(key);
      return h('button', {
        class: `day-chip ${key === selectedDay ? 'active' : ''}`,
        onClick: () => {
          selectedDay = key;
          appFilter = null;
          window.dispatchEvent(new CustomEvent('guardpulse:rerender'));
        },
      }, label);
    }),
  );
}

function dayChipLabel(key) {
  const [y, m, d] = key.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined, { weekday: 'short', day: '2-digit', month: 'short' });
}

function dayWindow(state, dayKey) {
  const [y, m, d] = dayKey.split('-').map(Number);
  const start = new Date(y, m - 1, d).getTime();
  return [start, start + 24 * 60 * 60_000];
}

/** ActivityFeature.kt TimelineCard (lines 238-282). */
function timelineCard(state) {
  const [windowStart, windowEnd] = dayWindow(state, selectedDay);
  const segments = buildTimelineSegments(state.activityHistory, windowStart, windowEnd);
  const first = segments[0];
  const windowLabel = `${new Date(windowStart).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })}–${new Date(windowEnd).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })}`;
  return card(
    h('div', { class: 'row' },
      h('div', { class: 'grow card-title' }, 'Timeline'),
      h('span', { class: 'small muted' }, windowLabel),
    ),
    h('div', { class: 'timeline-bar' },
      segments.map((seg) => {
        const background = seg.empty ? 'var(--surface-tint)'
          : seg.hasOverlay ? 'var(--alert-red)'
            : seg.isMedia ? 'var(--action-blue)' : 'var(--guard-navy-soft)';
        return h('div', {
          class: 'timeline-segment',
          style: {
            left: `${seg.fractionStart * 100}%`,
            width: `${Math.max(0, (seg.fractionEnd - seg.fractionStart) * 100)}%`,
            background,
          },
          title: seg.empty ? 'No activity' : `${seg.record.appLabel}: ${new Date(seg.start).toLocaleTimeString()} – ${new Date(seg.end).toLocaleTimeString()}`,
        });
      }),
    ),
    h('div', { class: 'timeline-legend' },
      legendDot('var(--action-blue)', 'Video'),
      legendDot('var(--guard-navy-soft)', 'App'),
      legendDot('var(--alert-red)', 'Lock overlay'),
    ),
  );
}

function legendDot(color, label) {
  return h('span', {}, h('span', { class: 'legend-dot', style: { background: color } }), label);
}

/** ActivityFeature.kt AppFilterRow + history rows (lines 300-364). */
function historyCard(state) {
  const [windowStart, windowEnd] = dayWindow(state, selectedDay);
  const dayRecords = state.activityHistory
    .filter((r) => r.startedAt >= windowStart && r.startedAt < windowEnd);

  const appKeys = [...new Set(dayRecords.map((r) => r.packageName))];
  const filterRow = appKeys.length >= 2
    ? h('div', { class: 'chip-row' },
      appKeys.sort().map((pkg) => {
        const label = dayRecords.find((r) => r.packageName === pkg)?.appLabel ?? pkg;
        return h('button', {
          class: `day-chip ${appFilter === pkg ? 'active' : ''}`,
          onClick: () => {
            appFilter = appFilter === pkg ? null : pkg;
            window.dispatchEvent(new CustomEvent('guardpulse:rerender'));
          },
        }, label);
      }),
    )
    : null;

  const visible = dayRecords
    .filter((r) => appFilter == null || r.packageName === appFilter)
    .sort((a, b) => (b.startedAt ?? 0) - (a.startedAt ?? 0));

  const rows = visible.length === 0
    ? h('div', { class: 'small muted', style: { padding: '8px 2px' } },
      dayRecords.length === 0
        ? (selectedDay === activityDayKey(state.serverNow)
          ? 'Nothing recorded. Sessions appear here after the kid switches apps or videos.'
          : 'No sessions were recorded on this day.')
        : 'No sessions for the selected app on this day.')
    : visible.map((record) => historyRow(state, record));

  return card(
    h('div', { class: 'card-title' }, 'History'),
    filterRow,
    ...rows,
  );
}

function historyRow(state, record) {
  const endTime = new Date(record.endedAt).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
  const startTime = new Date(record.startedAt).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
  let subtitle = `${record.appLabel} · ${startTime}–${endTime}`;
  if (isMediaRecord(record) && record.durationMs != null) subtitle += ` · ${formatUsage(record.durationMs)}`;
  return h('div', { class: 'list-row' },
    h('div', { class: 'grow' },
      h('div', { style: { fontWeight: 600 } }, record.title ?? record.appLabel),
      h('div', { class: 'small muted' }, subtitle),
    ),
    (record.overlayMs ?? 0) > 0
      ? statusLabel(`Locked ${formatUsage(record.overlayMs)}`, 'var(--alert-red)')
      : (isMediaRecord(record) && record.playbackState === 'playing')
        ? statusLabel('Playing', 'var(--success-green)')
        : null,
  );
}
