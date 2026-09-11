/**
 * Port of shared/DateKeys.kt. Day keys are ISO yyyy-MM-dd.
 * dayKeyUtc is the family used by TV-side day-keyed state; activityDayKey
 * (parent UI) uses the DEVICE-LOCAL calendar, matching ParentReducers.kt:51-52.
 */

export function today() {
  return formatLocalDay(new Date());
}

export function dayKeyUtc(epochMs) {
  return new Date(epochMs).toISOString().slice(0, 10);
}

export function utcDaysAgo(days, epochMs) {
  return dayKeyUtc(epochMs - days * 24 * 60 * 60_000);
}

export function daysAgo(days) {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return formatLocalDay(d);
}

function formatLocalDay(d) {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}
