/**
 * Unit tests for the JS ports of the shared contracts. Run: npm test (web/).
 * Golden vectors are computed from the Kotlin reference implementation's
 * documented formulas (PinHasher.kt, PackageKeys.kt, ControlProtocol.kt).
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { encode, decode, normalizedPackageName } from '../js/packageKeys.js';
import { create, verify, CURRENT_VERSION, CURRENT_ALGORITHM, CURRENT_ITERATIONS, LEGACY_VERSION } from '../js/pinHasher.js';
import { parse, parseDesired, freshness, effectiveApps } from '../js/controlProtocol.js';
import {
  deriveSyncStatus, effectiveUsageMs, buildTimelineSegments, isActivityStale,
  interpolatedPositionMs, isPendingUnlock, isCriticalTamperEvent, formatUsage,
} from '../js/reducers.js';
import { parsePairingPayload } from '../js/pairing.js';
import { commandTtlMs, DEFAULT_LOCKED_PACKAGES } from '../js/policyConstants.js';

/* ------------------------------ PackageKeys ------------------------------ */

test('PackageKeys: Kotlin-verified vector (Youtube TV)', () => {
  // Java Base64.getUrlEncoder().withoutPadding().encode("com.google.android.youtube.tv")
  assert.equal(encode('com.google.android.youtube.tv'), 'Y29tLmdvb2dsZS5hbmRyb2lkLnlvdXR1YmUudHY');
});

test('PackageKeys: round-trip and Firebase-key safety', () => {
  const samples = ['com.android.tv', 'com.guardpulse.policy.settings_apps', 'in.startv.hotstar'];
  for (const pkg of samples) {
    const key = encode(pkg);
    assert.ok(!key.includes('.') && !key.includes('=') && !key.includes('/') && !key.includes('+'));
    assert.equal(decode(key), pkg);
  }
});

test('normalizedPackageName: stored wins when it re-encodes to the key', () => {
  const key = encode('com.example.app');
  assert.equal(normalizedPackageName(key, 'com.example.app'), 'com.example.app');
  assert.equal(normalizedPackageName(key, 'WRONG'), 'com.example.app'); // stored mismatches → decoded
  assert.equal(normalizedPackageName(key, null), 'com.example.app');
  assert.equal(normalizedPackageName(null, 'com.example.app'), 'com.example.app');
});

/* ------------------------------- PinHasher ------------------------------- */

test('PinHasher: create+verify round trip, wrong PIN rejected', async () => {
  const hash = await create('123456');
  assert.equal(hash.version, CURRENT_VERSION);
  assert.equal(hash.algorithm, CURRENT_ALGORITHM);
  assert.equal(hash.iterations, CURRENT_ITERATIONS);
  assert.match(hash.salt, /^[-_A-Za-z0-9]{22}$/);
  assert.match(hash.hash, /^[-_A-Za-z0-9]{43}$/);
  assert.equal(await verify('123456', hash.salt, hash.hash, hash.version, hash.algorithm, hash.iterations), true);
  assert.equal(await verify('654321', hash.salt, hash.hash, hash.version, hash.algorithm, hash.iterations), false);
  assert.equal(await verify('', hash.salt, hash.hash, hash.version, hash.algorithm, hash.iterations), false);
});

test('PinHasher: legacy v1 golden vector (SHA-256 of "salt:pin")', async () => {
  const salt = 'c2FsdC1mb3ItdGVzdA'; // base64url("salt-for-test"), from PinHasherTest.kt
  // Golden vector: SHA-256 of UTF-8 "c2FsdC1mb3ItdGVzdA:123456", base64url unpadded.
  const golden = 'c96IovgSojY2idvpsYt4hvR1BjMwNlvaVDp5SbOf258';
  assert.equal(await verify('123456', salt, golden, LEGACY_VERSION), true);
  assert.equal(await verify('654321', salt, golden, LEGACY_VERSION), false);
});

test('PinHasher: PBKDF2 golden vector (cross-computed with Python hashlib.pbkdf2_hmac)', async () => {
  // PBKDF2-HMAC-SHA256(pin="123456", salt=b64urlDecode("c2FsdC1mb3ItdGVzdA"),
  // iterations=210000, dkLen=32) — the exact operation shared/PinHasher.kt
  // pbkdf2() performs, cross-computed independently in Python.
  const salt = 'c2FsdC1mb3ItdGVzdA';
  const golden = 'o1YwxNW_6uzHwvWYHi5wK9QjaKqlhHu-Ebv81Mq7Ktg';
  assert.equal(await verify('123456', salt, golden, CURRENT_VERSION, CURRENT_ALGORITHM, 210000), true);
  assert.equal(await verify('654321', salt, golden, CURRENT_VERSION, CURRENT_ALGORITHM, 210000), false);
  // A self-derived hash must be 43 base64url chars and verify.
  const hash = await create('654321');
  assert.equal(hash.hash.length, 43);
  assert.equal(await verify('654321', hash.salt, hash.hash, hash.version, hash.algorithm, hash.iterations), true);
});

test('PinHasher: rejects out-of-range iterations and wrong algorithm for v2', async () => {
  const salt = 'c2FsdC1mb3ItdGVzdA';
  assert.equal(await verify('123456', salt, 'x'.repeat(43), CURRENT_VERSION, 'WrongAlg', 210000), false);
  assert.equal(await verify('123456', salt, 'x'.repeat(43), CURRENT_VERSION, CURRENT_ALGORITHM, 1000), false);
  assert.equal(await verify('123456', salt, 'x'.repeat(43), 3), false);
});

/* ----------------------------- ControlProtocol ----------------------------- */

function validSnapshot(overrides = {}) {
  return {
    schemaVersion: 2,
    revisionId: 'rev-1',
    updatedAt: 123,
    updatedBy: 'parent-1',
    apps: {
      [encode('com.example.app')]: { packageKey: encode('com.example.app'), packageName: 'com.example.app', manualBlocked: false, updatedAt: 1 },
    },
    modes: {},
    activeMode: null,
    safeMode: { enabled: false, until: 0 },
    pin: null,
    ...overrides,
  };
}

test('ControlProtocol.parse: valid snapshot parses', () => {
  const result = parse(validSnapshot());
  assert.equal(result.ok, true);
  assert.equal(result.value.revisionId, 'rev-1');
});

test('ControlProtocol.parse: rejects bad schema, missing revision, key mismatch, missing safeMode', () => {
  assert.equal(parse(validSnapshot({ schemaVersion: 3 })).ok, false);
  assert.equal(parse(validSnapshot({ revisionId: '' })).ok, false);
  const keyMismatch = validSnapshot();
  keyMismatch.apps = { WRONG: { packageKey: 'WRONG', packageName: 'com.example.app', manualBlocked: false } };
  assert.equal(parse(keyMismatch).ok, false);
  const noSafeMode = validSnapshot();
  delete noSafeMode.safeMode;
  assert.equal(parse(noSafeMode).ok, false);
});

test('ControlProtocol.parse: activeMode must exist in modes; safeMode enabled requires until>0', () => {
  // Modes are keyed by the RAW modeId (parent writes control/v2/modes/$modeId
  // with the push key), and parse requires key == modeId field.
  const modes = { m1: { modeId: 'm1', name: 'Study', apps: {} } };
  assert.equal(parse(validSnapshot({ activeMode: { modeId: 'm2' }, modes })).ok, false);
  assert.equal(parse(validSnapshot({ activeMode: { modeId: 'm1' }, modes })).ok, true);
  assert.equal(parse(validSnapshot({ safeMode: { enabled: true, until: 0 } })).ok, false);
});

test('ControlProtocol.parse: pin validation (version bounds, algorithm, iterations)', () => {
  const good = { salt: 'a'.repeat(22), hash: 'b'.repeat(43), version: 2, algorithm: CURRENT_ALGORITHM, iterations: 210000 };
  assert.equal(parse(validSnapshot({ pin: good })).ok, true);
  assert.equal(parse(validSnapshot({ pin: { ...good, iterations: 1000 } })).ok, false);
  assert.equal(parse(validSnapshot({ pin: { ...good, algorithm: 'X' } })).ok, false);
  assert.equal(parse(validSnapshot({ pin: { ...good, version: 7 } })).ok, false);
  assert.equal(parse(validSnapshot({ pin: { salt: '', hash: '' } })).ok, false);
});

test('ControlProtocol.parseDesired: null unless kind is known', () => {
  assert.equal(parseDesired({ revisionId: 'r', kind: 'appPolicy' })?.kind, 'appPolicy');
  assert.equal(parseDesired({ revisionId: 'r', kind: 'nope' }), null);
  assert.equal(parseDesired({ kind: 'appPolicy' }), null);
});

test('freshness: 45s/90s boundaries and null lastSeen', () => {
  const now = 1_000_000;
  assert.equal(freshness(true, now - 45_000, now), 'LIVE');
  assert.equal(freshness(true, now - 45_001, now), 'DELAYED');
  assert.equal(freshness(true, now - 90_000, now), 'DELAYED');
  assert.equal(freshness(true, now - 90_001, now), 'OFFLINE');
  assert.equal(freshness(false, now, now), 'OFFLINE');
  assert.equal(freshness(true, null, now), 'OFFLINE');
});

test('effectiveApps: active mode rules win, default-locked sections putIfAbsent', () => {
  const snapshot = {
    revisionId: 'r',
    apps: { [encode('com.a')]: { packageName: 'com.a', manualBlocked: false } },
    modes: { m1: { modeId: 'm1', name: 'M', apps: { [encode('com.b')]: { packageName: 'com.b', manualBlocked: true } } } },
    activeMode: { modeId: 'm1' },
    safeMode: { enabled: false, until: 0 },
  };
  const apps = effectiveApps(snapshot);
  assert.equal(apps[encode('com.b')].manualBlocked, true);
  for (const pkg of DEFAULT_LOCKED_PACKAGES) {
    assert.equal(apps[pkg]?.manualBlocked, true, `${pkg} default-locked`);
  }
});

/* ------------------------------- reducers ------------------------------- */

test('deriveSyncStatus: full matrix in evaluation order', () => {
  const base = { phoneConnected: true, protocolVersion: 2, freshness: 'LIVE' };
  const applied = { revisionId: 'r1', status: 'applied' };
  const desired = { revisionId: 'r1' };
  assert.equal(deriveSyncStatus({ ...base, controlAvailability: 'INVALID' }), 'FAILED');
  assert.equal(deriveSyncStatus({ ...base, controlAvailability: 'VALID', phoneConnected: false }), 'SENDING');
  assert.equal(deriveSyncStatus({ ...base, controlAvailability: 'VALID', protocolVersion: 1, desired, applied }), 'TV_UPDATE_REQUIRED');
  assert.equal(deriveSyncStatus({ ...base, controlAvailability: 'VALID', desired, applied: { revisionId: 'r1', status: 'failed' } }), 'FAILED');
  assert.equal(deriveSyncStatus({ ...base, controlAvailability: 'VALID', desired: { revisionId: 'r2' }, applied, freshness: 'OFFLINE' }), 'OFFLINE_PENDING');
  assert.equal(deriveSyncStatus({ ...base, controlAvailability: 'VALID', desired: { revisionId: 'r2' }, applied, freshness: 'DELAYED' }), 'DELAYED');
  assert.equal(deriveSyncStatus({ ...base, controlAvailability: 'VALID', desired: { revisionId: 'r2' }, applied }), 'WAITING_FOR_TV');
  assert.equal(deriveSyncStatus({ ...base, controlAvailability: 'VALID', desired, applied }), 'APPLIED');
  assert.equal(deriveSyncStatus({ ...base, controlAvailability: 'VALID', desired: null, applied: null, freshness: 'DELAYED' }), 'DELAYED');
  assert.equal(deriveSyncStatus({ ...base, controlAvailability: 'VALID', desired: null, applied: null }), 'IDLE');
});

test('effectiveUsageMs: 20s extrapolation cap, only while foregroundActive', () => {
  const state = { foregroundActive: true, usageMsToday: 60_000, usageCapturedAt: 1_000_000 };
  assert.equal(effectiveUsageMs(state, 1_005_000), 65_000);
  assert.equal(effectiveUsageMs(state, 2_000_000), 80_000); // capped at +20s
  assert.equal(effectiveUsageMs({ ...state, foregroundActive: false }, 1_005_000), 60_000);
  assert.equal(effectiveUsageMs({ ...state, usageCapturedAt: null }, 1_005_000), 60_000);
});

test('interpolatedPositionMs: plays forward, clamps to duration, frozen when paused', () => {
  const base = { positionMs: 10_000, positionCapturedAt: 1_000_000, playbackSpeed: 1, playbackState: 'playing', durationMs: 20_000 };
  assert.equal(interpolatedPositionMs(base, 1_005_000), 15_000);
  assert.equal(interpolatedPositionMs(base, 2_000_000), 20_000);
  assert.equal(interpolatedPositionMs({ ...base, playbackState: 'paused' }, 1_005_000), 10_000);
});

test('isActivityStale: 90s threshold', () => {
  assert.equal(isActivityStale({ updatedAt: 1_000_000 }, 1_000_000 + 90_000), false);
  assert.equal(isActivityStale({ updatedAt: 1_000_000 }, 1_000_000 + 90_001), true);
});

test('buildTimelineSegments: clipping, empty fallback, overlay classification', () => {
  const start = 0;
  const end = 100_000;
  const segments = buildTimelineSegments([
    { packageName: 'com.a', type: 'app', startedAt: -5_000, endedAt: 10_000, overlayMs: 0 },
    { packageName: 'com.b', type: 'media', startedAt: 90_000, endedAt: 200_000, overlayMs: 5_000 },
  ], start, end);
  assert.equal(segments.length, 2);
  assert.equal(segments[0].fractionStart, 0);
  assert.equal(segments[1].hasOverlay, true);
  const empty = buildTimelineSegments([], start, end);
  assert.equal(empty.length, 1);
  assert.equal(empty[0].empty, true);
});

test('isPendingUnlock + isCriticalTamperEvent', () => {
  assert.equal(isPendingUnlock({ status: 'pending', expiresAt: 2_000_000 }, 1_000_000), true);
  assert.equal(isPendingUnlock({ status: 'pending', expiresAt: 500_000 }, 1_000_000), false);
  assert.equal(isPendingUnlock({ status: 'approved' }, 1_000_000), false);
  assert.equal(isCriticalTamperEvent({ type: 'adminDisabled' }), true);
  assert.equal(isCriticalTamperEvent({ type: 'riskySettingsOpened' }), true);
  assert.equal(isCriticalTamperEvent({ type: 'pinRetryLocked' }), false);
});

test('formatUsage matches ParentUiShared formatting', () => {
  assert.equal(formatUsage(59_000), '59s');
  assert.equal(formatUsage(61_000), '1m 1s');
  assert.equal(formatUsage(3_600_000), '1h 0m');
});

/* ------------------------------- pairing ------------------------------- */

test('parsePairingPayload: guardpulse URI query params', () => {
  const parsed = parsePairingPayload('guardpulse://pair?deviceId=TV-123&secret=abc');
  assert.equal(parsed.deviceId, 'TV-123');
  assert.equal(parsed.secret, 'abc');
  assert.equal(parsePairingPayload('').deviceId, null);
});

test('commandTtlMs matches PolicyConstants', () => {
  assert.equal(commandTtlMs('openSetup'), 60_000);
  assert.equal(commandTtlMs('unpair'), 600_000);
  assert.equal(commandTtlMs('rescanApps'), 300_000);
});
