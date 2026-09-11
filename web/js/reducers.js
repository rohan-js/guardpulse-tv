/**
 * Port of the pure parent-side reducers (ParentReducers.kt) and model helpers
 * (ParentModels.kt: isStale, interpolatedPositionMs; ParentUiShared: formats,
 * defaultParentPolicy; ParentSyncRepository parsers live in repository.js).
 */
import { FOREGROUND_USAGE_EXTRAPOLATION_MAX_MS, PRIMARY_SETTINGS_PACKAGES, SETTINGS_SECTION_POLICIES, DEPRECATED_VIRTUAL_POLICY_PACKAGES } from './policyConstants.js';

export const SYNC_STATUS = {
  IDLE: 'IDLE',
  SENDING: 'SENDING',
  WAITING_FOR_TV: 'WAITING_FOR_TV',
  APPLIED: 'APPLIED',
  DELAYED: 'DELAYED',
  OFFLINE_PENDING: 'OFFLINE_PENDING',
  FAILED: 'FAILED',
  TV_UPDATE_REQUIRED: 'TV_UPDATE_REQUIRED',
};

export const STALE_AFTER_MS = 90_000;

/**
 * Port of ParentReducers.kt deriveSyncStatus (lines 8-35) — evaluation order
 * is load-bearing; do not reorder.
 */
export function deriveSyncStatus({ controlAvailability, phoneConnected, protocolVersion, desired, applied, freshness }) {
  if (controlAvailability === 'INVALID') return SYNC_STATUS.FAILED;
  if (!phoneConnected) return SYNC_STATUS.SENDING;
  if (controlAvailability === 'VALID' && protocolVersion < 2) return SYNC_STATUS.TV_UPDATE_REQUIRED;
  if (desired != null && applied?.revisionId === desired.revisionId && applied?.status === 'failed') {
    return SYNC_STATUS.FAILED;
  }
  if (desired != null && applied?.revisionId !== desired.revisionId && freshness === 'OFFLINE') {
    return SYNC_STATUS.OFFLINE_PENDING;
  }
  if (desired != null && applied?.revisionId !== desired.revisionId && freshness === 'DELAYED') {
    return SYNC_STATUS.DELAYED;
  }
  if (desired != null && applied?.revisionId !== desired.revisionId) return SYNC_STATUS.WAITING_FOR_TV;
  if (desired != null && applied?.revisionId === desired.revisionId) return SYNC_STATUS.APPLIED;
  if (freshness === 'DELAYED') return SYNC_STATUS.DELAYED;
  return SYNC_STATUS.IDLE;
}

/**
 * Port of ParentReducers.kt effectiveUsageMs (lines 42-48): live-foreground
 * usage extrapolates serverNow forward, capped at 20s past the capture.
 */
export function effectiveUsageMs(state, serverNow) {
  if (!state.foregroundActive) return Math.max(0, state.usageMsToday ?? 0);
  const capturedAt = state.usageCapturedAt;
  if (capturedAt == null) return Math.max(0, state.usageMsToday ?? 0);
  const elapsed = Math.min(Math.max(serverNow - capturedAt, 0), FOREGROUND_USAGE_EXTRAPOLATION_MAX_MS);
  return Math.max(0, (state.usageMsToday ?? 0) + elapsed);
}

/** ParentUiShared.kt modeUsageMs (lines 146-157): settings sections aggregate the max across primary Settings packages. */
export function modeUsageMs(states, packageName, serverNow) {
  if (SETTINGS_SECTION_POLICIES.some((s) => s.packageName === packageName)) {
    let max = 0;
    for (const pkg of PRIMARY_SETTINGS_PACKAGES) {
      const state = states[pkg];
      if (state) max = Math.max(max, effectiveUsageMs(state, serverNow));
    }
    return max;
  }
  const state = states[packageName];
  return state ? effectiveUsageMs(state, serverNow) : 0;
}

/** ParentModels.kt ParentActivityNow.isStale. */
export function isActivityStale(current, now) {
  return now - (current?.updatedAt ?? 0) > STALE_AFTER_MS;
}

/** ParentModels.kt interpolatedPositionMs — only extrapolates while playing. */
export function interpolatedPositionMs(current, now) {
  const base = current?.positionMs;
  if (base == null) return null;
  if (current.playbackState !== 'playing') return base;
  const capturedAt = current.positionCapturedAt;
  if (capturedAt == null) return base;
  const elapsed = Math.max(0, now - capturedAt);
  const estimate = base + Math.trunc(elapsed * (current.playbackSpeed ?? 0));
  return current.durationMs != null ? Math.min(estimate, current.durationMs) : estimate;
}

/** ParentReducers.kt activityDayKey — device-LOCAL calendar day. */
export function activityDayKey(epochMs) {
  const d = new Date(epochMs);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

/** ParentReducers.kt buildTimelineSegments (lines 73-108). */
export function buildTimelineSegments(records, windowStart, windowEnd) {
  const inWindow = records
    .filter((r) => (r.endedAt ?? 0) > windowStart && (r.startedAt ?? 0) < windowEnd)
    .sort((a, b) => (a.startedAt ?? 0) - (b.startedAt ?? 0));
  const span = Math.max(1, windowEnd - windowStart);
  const segments = inWindow.map((r) => {
    const start = Math.max(r.startedAt ?? 0, windowStart);
    const end = Math.min(r.endedAt ?? 0, windowEnd);
    return {
      record: r,
      start,
      end,
      fractionStart: (start - windowStart) / span,
      fractionEnd: (end - windowStart) / span,
      hasOverlay: (r.overlayMs ?? 0) > 0,
      isMedia: r.type === 'media',
    };
  }).filter((s) => s.fractionEnd - s.fractionStart > 0.001);
  if (segments.length === 0) {
    return [{ record: null, start: windowStart, end: windowEnd, fractionStart: 0, fractionEnd: 1, hasOverlay: false, isMedia: false, empty: true }];
  }
  return segments;
}

/**
 * Port of ParentSyncViewModel promoteConfirmedControl / toParentPolicies chain:
 * confirmed control derives from DESIRED only when applied matches.
 */
export function promoteConfirmedControl(desired, applied, latestRuntimeStates) {
  if (desired == null || applied == null) return null;
  if (applied.revisionId !== desired.revisionId || applied.status !== 'applied') return null;
  const policies = {};
  for (const [pkg, rule] of Object.entries(desired.apps ?? {})) {
    policies[pkg] = { manualBlocked: rule.manualBlocked, dailyLimitMinutes: rule.dailyLimitMinutes ?? null };
  }
  const modes = Object.values(desired.modes ?? {})
    .map((m) => ({
      modeId: m.modeId,
      name: m.name,
      appPolicies: Object.fromEntries(
        Object.entries(m.apps ?? {}).map(([pkg, rule]) => [pkg, { manualBlocked: rule.manualBlocked, dailyLimitMinutes: rule.dailyLimitMinutes ?? null }]),
      ),
      createdAt: m.createdAt ?? null,
      updatedAt: m.updatedAt ?? null,
    }))
    .sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()));
  const activeMode = desired.activeMode
    ? {
      modeId: desired.activeMode.modeId,
      modeName: desired.activeMode.modeName
        ?? modes.find((m) => m.modeId === desired.activeMode.modeId)?.name
        ?? null,
      activatedAt: desired.activeMode.activatedAt ?? null,
    }
    : { modeId: null, modeName: null, activatedAt: null };
  const safeMode = {
    enabled: desired.safeMode?.enabled ?? false,
    until: desired.safeMode?.until ?? null,
    startedAt: desired.safeMode?.startedAt ?? null,
    startedBy: desired.safeMode?.startedBy ?? null,
  };
  const confirmedStates = {};
  for (const [pkg, state] of Object.entries(latestRuntimeStates ?? {})) {
    if (state.controlRevisionId === desired.revisionId) confirmedStates[pkg] = state;
  }
  return { desired, policies, modes, activeMode, safeMode, confirmedStates };
}

/** ParentModels.kt SyncState.isAppPolicyPending (lines 275-282). */
export function isAppPolicyPending(desiredControl, confirmedControl, pkg, state) {
  const desiredRule = desiredControl?.apps?.[pkg];
  if (desiredRule != null && confirmedControl != null) {
    const confirmedRule = confirmedControl.apps?.[pkg];
    if (confirmedRule == null) return true;
    if (confirmedRule.manualBlocked !== desiredRule.manualBlocked) return true;
    if ((confirmedRule.dailyLimitMinutes ?? null) !== (desiredRule.dailyLimitMinutes ?? null)) return true;
  }
  return state.controlRevisionId != null && state.controlRevisionId !== (confirmedControl?.revisionId ?? null);
}

/** ParentModels.kt isAppPolicyWaitingForTv (lines 290-302). */
export function isAppPolicyWaitingForTv(syncState, pkg) {
  if (syncState.confirmedControl == null) {
    if (syncState.appliedRevision?.revisionId !== syncState.desiredRevision?.revisionId) return true;
    if (syncState.appliedRevision?.status !== 'applied') return true;
  }
  const desiredRule = syncState.desiredControl?.apps?.[pkg];
  if (desiredRule != null) {
    const confirmedRule = syncState.confirmedControl?.apps?.[pkg];
    if (confirmedRule == null) return true;
    if (confirmedRule.manualBlocked !== desiredRule.manualBlocked) return true;
    if ((confirmedRule.dailyLimitMinutes ?? null) !== (desiredRule.dailyLimitMinutes ?? null)) return true;
  }
  return isAppPolicyPending(syncState.desiredControl, syncState.confirmedControl, pkg, syncState.runtimeStates?.[pkg] ?? {});
}

/** ParentUiShared.kt defaultParentPolicy (lines 126-132). */
export function defaultParentPolicy(packageName) {
  return {
    manualBlocked: SETTINGS_SECTION_POLICIES.some((s) => s.packageName === packageName),
    dailyLimitMinutes: null,
  };
}

export function isMediaRecord(record) {
  return record.type === 'media';
}

export function visibleApps(apps) {
  return Object.values(apps ?? {}).filter((app) => !DEPRECATED_VIRTUAL_POLICY_PACKAGES.has(app.packageName));
}

/** ParentSecurityFeature.kt isPendingUnlock (lines 97-100). */
export function isPendingUnlock(request, now) {
  return request.status === 'pending' && (request.expiresAt == null || now <= request.expiresAt);
}

/** ParentSecurityFeature.kt critical-event rule (line 623). */
export function isCriticalTamperEvent(event) {
  const type = (event.type ?? '').toLowerCase();
  return type.includes('disabled') || type.includes('risky');
}

/** ParentUiShared.kt formatUsage (lines 134-144). */
export function formatUsage(ms) {
  const totalSeconds = Math.floor(Math.max(0, ms ?? 0) / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) return `${hours}h ${minutes}m`;
  if (minutes > 0) return `${minutes}m ${seconds}s`;
  return `${seconds}s`;
}

/** ParentUiShared.kt formatTimestamp (lines 159-162). */
export function formatTimestamp(ts) {
  if (ts == null || ts <= 0) return 'unknown';
  return new Date(ts).toLocaleString(undefined, { day: '2-digit', month: 'short', hour: 'numeric', minute: '2-digit' });
}

/** ParentUiShared.kt formatAge (lines 164-173). */
export function formatAge(ts, now) {
  if (ts == null || ts <= 0) return 'unknown';
  const elapsed = Math.max(0, now - ts);
  const minutes = Math.floor(elapsed / 60_000);
  if (minutes < 1) return 'just now';
  if (minutes === 1) return '1 min';
  if (minutes < 60) return `${minutes} mins`;
  const hours = Math.floor(minutes / 60);
  const rem = minutes % 60;
  return `${hours}h ${rem}m`;
}
