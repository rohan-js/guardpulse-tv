/**
 * Port of shared/ControlProtocol.kt (parse, parseDesired, freshness) operating
 * on plain RTDB JSON values. Validation must match the Android parser so the
 * dashboard shows the same VALID/INVALID verdicts the TV will enforce.
 */
import { encode as encodePackageKey } from './packageKeys.js';
import {
  SYNC_PROTOCOL_VERSION, REVISION_KINDS, DEFAULT_LOCKED_PACKAGES,
} from './policyConstants.js';
import {
  CURRENT_ITERATIONS as PIN_DEFAULT_ITERATIONS,
  CURRENT_ALGORITHM as PIN_ALGORITHM,
  LEGACY_VERSION as PIN_LEGACY_VERSION,
  CURRENT_VERSION as PIN_CURRENT_VERSION,
} from './pinHasher.js';

export const SYNC_PROTOCOL = SYNC_PROTOCOL_VERSION;

export function parse(value) {
  if (value == null || typeof value !== 'object') {
    return { ok: false, error: 'V2 control snapshot is missing' };
  }
  const schemaVersion = value.schemaVersion;
  if (typeof schemaVersion !== 'number' || schemaVersion !== SYNC_PROTOCOL_VERSION) {
    return { ok: false, error: `Unsupported control schema: ${String(schemaVersion)}` };
  }
  const revisionId = value.revisionId;
  if (typeof revisionId !== 'string' || revisionId.trim() === '') {
    return { ok: false, error: 'Control revision is missing' };
  }

  const appsResult = parseApps(value.apps ?? {});
  if (!appsResult.ok) return appsResult;

  const modesResult = parseModes(value.modes ?? {});
  if (!modesResult.ok) return modesResult;
  const modes = modesResult.value;

  let activeMode = null;
  if (value.activeMode != null && typeof value.activeMode === 'object') {
    const modeId = value.activeMode.modeId;
    if (typeof modeId !== 'string' || modeId.trim() === '') {
      return { ok: false, error: 'Active mode id is missing' };
    }
    if (!Object.prototype.hasOwnProperty.call(modes, modeId)) {
      return { ok: false, error: `Active mode ${modeId} does not exist` };
    }
    activeMode = {
      modeId,
      modeName: optionalString(value.activeMode.modeName),
      activatedAt: optionalNumber(value.activeMode.activatedAt),
    };
  }

  const safeModeRaw = value.safeMode;
  if (safeModeRaw == null || typeof safeModeRaw !== 'object') {
    return { ok: false, error: 'Safe Mode state is missing' };
  }
  if (typeof safeModeRaw.enabled !== 'boolean') {
    return { ok: false, error: 'Safe Mode enabled flag is missing' };
  }
  if (typeof safeModeRaw.until !== 'number') {
    return { ok: false, error: 'Safe Mode expiry is missing' };
  }
  if (safeModeRaw.enabled && safeModeRaw.until <= 0) {
    return { ok: false, error: 'Safe Mode expiry must be in the future while enabled' };
  }
  const safeMode = {
    enabled: safeModeRaw.enabled,
    until: safeModeRaw.until,
    startedAt: optionalNumber(safeModeRaw.startedAt),
    startedBy: optionalString(safeModeRaw.startedBy),
  };

  let pin = null;
  const pinRaw = value.pin;
  if (pinRaw != null && typeof pinRaw === 'object') {
    if (typeof pinRaw.salt !== 'string' || pinRaw.salt.trim() === '') {
      return { ok: false, error: 'PIN salt is missing' };
    }
    if (typeof pinRaw.hash !== 'string' || pinRaw.hash.trim() === '') {
      return { ok: false, error: 'PIN hash is missing' };
    }
    const version = pinRaw.version ?? PIN_LEGACY_VERSION;
    if (version !== PIN_LEGACY_VERSION && version !== PIN_CURRENT_VERSION) {
      return { ok: false, error: `Unsupported PIN hash version: ${String(version)}` };
    }
    if (version === PIN_CURRENT_VERSION) {
      if (pinRaw.algorithm !== PIN_ALGORITHM) {
        return { ok: false, error: 'PIN hash algorithm is not supported' };
      }
      const iterations = pinRaw.iterations;
      if (typeof iterations !== 'number' || iterations < PIN_DEFAULT_ITERATIONS || iterations > 1_000_000) {
        return { ok: false, error: 'PIN hash iteration count is out of range' };
      }
    }
    pin = {
      salt: pinRaw.salt,
      hash: pinRaw.hash,
      version,
      algorithm: optionalString(pinRaw.algorithm),
      iterations: optionalNumber(pinRaw.iterations),
      updatedAt: optionalNumber(pinRaw.updatedAt),
    };
  }

  return {
    ok: true,
    value: {
      revisionId,
      updatedAt: optionalNumber(value.updatedAt),
      updatedBy: optionalString(value.updatedBy),
      apps: appsResult.value,
      modes,
      activeMode,
      safeMode,
      pin,
    },
  };
}

function parseApps(raw) {
  const apps = {};
  for (const key of Object.keys(raw)) {
    const child = raw[key];
    if (child == null || typeof child !== 'object') continue;
    const packageName = child.packageName;
    if (typeof packageName !== 'string' || packageName.trim() === '') {
      return { ok: false, error: `App ${key} has no package name` };
    }
    if (encodePackageKey(packageName) !== key) {
      return { ok: false, error: `App key does not match package name: ${key}` };
    }
    if (typeof child.manualBlocked !== 'boolean') {
      return { ok: false, error: `App ${packageName} has no block flag` };
    }
    const limitResult = optionalBoundedLimit(child.dailyLimitMinutes);
    if (limitResult.error) return { ok: false, error: `App daily limit is out of range: ${packageName}` };
    const sessionResult = optionalBoundedLimit(child.sessionLimitMinutes);
    if (sessionResult.error) return { ok: false, error: `App session limit is out of range: ${packageName}` };
    apps[key] = {
      packageName,
      manualBlocked: child.manualBlocked,
      dailyLimitMinutes: limitResult.value,
      sessionLimitMinutes: sessionResult.value,
      updatedAt: optionalNumber(child.updatedAt),
    };
  }
  return { ok: true, value: apps };
}

function parseModes(raw) {
  const modes = {};
  for (const key of Object.keys(raw)) {
    const child = raw[key];
    if (child == null || typeof child !== 'object') continue;
    const modeId = typeof child.modeId === 'string' && child.modeId.trim() !== '' ? child.modeId : key;
    if (modeId.trim() === '') return { ok: false, error: 'Mode id is missing' };
    if (modeId !== key) return { ok: false, error: 'Mode key does not match mode ID' };
    const name = typeof child.name === 'string' ? child.name.trim() : '';
    if (name === '') return { ok: false, error: `Mode ${modeId} has no name` };
    const appsResult = parseApps(child.apps ?? {});
    if (!appsResult.ok) return appsResult;
    modes[key] = {
      modeId,
      name,
      apps: appsResult.value,
      createdAt: optionalNumber(child.createdAt),
      updatedAt: optionalNumber(child.updatedAt),
    };
  }
  return { ok: true, value: modes };
}

function optionalBoundedLimit(raw) {
  if (raw === undefined || raw === null) return { value: null, error: false };
  if (typeof raw !== 'number' || !Number.isInteger(raw) || raw < 1 || raw > 1440) {
    return { value: null, error: true };
  }
  return { value: raw, error: false };
}

export function parseDesired(value) {
  if (value == null || typeof value !== 'object') return null;
  const revisionId = value.revisionId;
  if (typeof revisionId !== 'string' || revisionId.trim() === '') return null;
  if (!REVISION_KINDS.has(value.kind)) return null;
  return {
    revisionId,
    kind: value.kind,
    target: optionalString(value.target),
    requestedAt: optionalNumber(value.requestedAt),
    requestedBy: optionalString(value.requestedBy),
  };
}

export function freshness(connected, lastSeen, now) {
  const age = typeof lastSeen === 'number' ? Math.max(0, now - lastSeen) : Number.MAX_SAFE_INTEGER;
  if (!connected || age > 90_000) return 'OFFLINE';
  if (age > 45_000) return 'DELAYED';
  return 'LIVE';
}

/**
 * Port of ControlSnapshotV2.effectiveApps(): active-mode rules win, then the
 * six default-locked Settings sections are put-if-absent as blocked.
 */
export function effectiveApps(snapshot) {
  const base = { ...(snapshot.activeMode ? snapshot.modes[snapshot.activeMode.modeId]?.apps ?? {} : snapshot.apps) };
  for (const pkg of DEFAULT_LOCKED_PACKAGES) {
    if (!Object.prototype.hasOwnProperty.call(base, pkg)) {
      base[pkg] = { packageName: pkg, manualBlocked: true, dailyLimitMinutes: null, sessionLimitMinutes: null, updatedAt: null };
    }
  }
  return base;
}

function optionalString(v) {
  return typeof v === 'string' ? v : null;
}

function optionalNumber(v) {
  return typeof v === 'number' ? v : null;
}
