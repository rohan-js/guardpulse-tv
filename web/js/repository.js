/**
 * Port of ParentRepository.kt (writes) + ParentSyncRepository.kt (listeners).
 * Field maps are reproduced character-for-character from the Kotlin source:
 * the deployed RTDB rules use "$other": {".validate": false}, so any
 * undeclared field denies the WHOLE multi-path update. Keys present with a
 * null value DELETE that child (identical to the Android SDK's semantics for
 * Map<String, Any?> in updateChildren); keys Kotlin omits are omitted here.
 */
import {
  ref, push, child, get, update, set, remove, onValue, query,
  orderByChild, limitToLast, limitToFirst, endAt, serverTimestamp,
} from 'firebase/database';
import * as paths from './paths.js';
import { encode as encodePackageKey, normalizedPackageName } from './packageKeys.js';
import { parse as parseControl, parseDesired } from './controlProtocol.js';
import {
  SYNC_PROTOCOL_VERSION, REVISION_APP_POLICY, REVISION_MODE_CREATE, REVISION_MODE_UPDATE,
  REVISION_MODE_DELETE, REVISION_MODE_POLICY, REVISION_ACTIVE_MODE, REVISION_SAFE_MODE,
  REVISION_PIN, REVISION_MIGRATION, COMMAND_PENDING, PAIR_PENDING, PAIRING_TTL_MS,
  commandTtlMs,
} from './policyConstants.js';
import { create as createPinHash, LEGACY_VERSION as PIN_LEGACY_VERSION } from './pinHasher.js';
import { attachRetentionCleaner } from './retention.js';
import { auth } from './firebase.js';

const MAX_SAFE_MODE_WINDOW_MS = 86_340_000; // rules cap 86,400,000 minus one minute skew headroom

const uid = () => auth.currentUser?.uid ?? null;

/* ============================== WRITES ============================== */

export class ParentRepository {
  constructor(database, serverClock) {
    this.database = database;
    this.serverClock = serverClock;
    this.queueTail = Promise.resolve();
    this.onQueuedWriteFailed = null;
  }

  enqueueControlWrite(build) {
    // Single-flight control-write serialization: each enqueued op runs only
    // after the previous one fully settles. A promise chain is immune to the
    // interleaving hazards of a boolean in-flight flag (a completing op's
    // bookkeeping racing a newly enqueued op).
    const run = this.queueTail.then(() => build());
    this.queueTail = run.then(() => {}, () => {});
    return run;
  }

  finishQueuedWrite(task, onSuccess, onError, fallbackMessage) {
    task
      .then(() => onSuccess())
      .catch((error) => onError(error?.message ?? fallbackMessage));
  }

  newRevisionId(deviceId) {
    const key = push(child(ref(this.database, paths.deviceSync(deviceId)), 'revisionKeys')).key;
    return key ?? `${this.serverClock.now()}-${crypto.randomUUID()}`;
  }

  controlUpdate(deviceId, kind, target, onSuccess, onError, mutate) {
    const currentUid = uid();
    if (!currentUid) return onError('Sign in before changing TV controls');
    this.onQueuedWriteFailed = onError;
    this.enqueueControlWrite(async () => {
      const revisionId = this.newRevisionId(deviceId);
      const updates = {};
      mutate(updates);
      const controlPath = paths.deviceControlV2(deviceId);
      updates[`${controlPath}/schemaVersion`] = SYNC_PROTOCOL_VERSION;
      updates[`${controlPath}/revisionId`] = revisionId;
      updates[`${controlPath}/updatedAt`] = serverTimestamp();
      updates[`${controlPath}/updatedBy`] = currentUid;
      this.addDesiredRevision(updates, deviceId, revisionId, kind, target, currentUid);
      this.finishQueuedWrite(
        update(ref(this.database), updates),
        onSuccess, onError, 'Control update failed',
      );
    });
  }

  addDesiredRevision(updates, deviceId, revisionId, kind, target, currentUid) {
    updates[paths.deviceSyncDesired(deviceId)] = {
      revisionId,
      kind,
      target,
      requestedAt: serverTimestamp(),
      requestedBy: currentUid,
    };
  }

  appPolicyValue(packageName, policy) {
    return {
      packageKey: encodePackageKey(packageName),
      packageName,
      manualBlocked: policy.manualBlocked,
      dailyLimitMinutes: policy.dailyLimitMinutes ?? null,
      updatedAt: serverTimestamp(),
    };
  }

  updatePolicy(deviceId, packageName, policy, onSuccess, onError) {
    const value = this.appPolicyValue(packageName, policy);
    this.controlUpdate(deviceId, REVISION_APP_POLICY, packageName, onSuccess, onError, (updates) => {
      updates[paths.devicePolicyApp(deviceId, packageName)] = value;
      updates[paths.deviceControlV2App(deviceId, packageName)] = value;
    });
  }

  async setPin(deviceId, pin, onSuccess, onError) {
    const hash = await createPinHash(pin);
    const currentUid = uid();
    const value = {
      salt: hash.salt,
      hash: hash.hash,
      version: hash.version,
      algorithm: hash.algorithm,
      iterations: hash.iterations,
      updatedAt: serverTimestamp(),
      updatedBy: currentUid,
    };
    this.controlUpdate(deviceId, REVISION_PIN, 'pin', onSuccess, onError, (updates) => {
      updates[paths.deviceSecurityPin(deviceId)] = value;
      updates[paths.deviceControlV2Pin(deviceId)] = value;
    });
  }

  createMode(deviceId, name, onSuccess, onError) {
    const modeId = push(ref(this.database, paths.devicePolicyModes(deviceId))).key;
    if (!modeId) return onError('Could not create mode');
    const value = {
      modeId,
      name,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
      updatedBy: uid(),
    };
    this.controlUpdate(deviceId, REVISION_MODE_CREATE, modeId, onSuccess, onError, (updates) => {
      updates[paths.devicePolicyMode(deviceId, modeId)] = value;
      updates[paths.deviceControlV2Mode(deviceId, modeId)] = value;
    });
  }

  updateModeName(deviceId, modeId, name, onSuccess, onError) {
    this.controlUpdate(deviceId, REVISION_MODE_UPDATE, modeId, onSuccess, onError, (updates) => {
      for (const path of [paths.devicePolicyMode(deviceId, modeId), paths.deviceControlV2Mode(deviceId, modeId)]) {
        // modeId must ride every write: the node validate requires it, so a
        // rename into a not-yet-existing v2 node would otherwise be denied
        // wholesale (leaf name/updatedAt alone fail hasChildren).
        updates[`${path}/modeId`] = modeId;
        updates[`${path}/name`] = name;
        updates[`${path}/updatedAt`] = serverTimestamp();
        updates[`${path}/updatedBy`] = uid();
      }
    });
  }

  updateModePolicy(deviceId, modeId, packageName, policy, onSuccess, onError) {
    const value = this.appPolicyValue(packageName, policy);
    this.controlUpdate(deviceId, REVISION_MODE_POLICY, `${modeId}:${packageName}`, onSuccess, onError, (updates) => {
      updates[paths.devicePolicyModeApp(deviceId, modeId, packageName)] = value;
      updates[paths.deviceControlV2ModeApp(deviceId, modeId, packageName)] = value;
    });
  }

  deleteMode(deviceId, modeId, activeModeId, onSuccess, onError) {
    this.controlUpdate(deviceId, REVISION_MODE_DELETE, modeId, onSuccess, onError, (updates) => {
      updates[paths.devicePolicyMode(deviceId, modeId)] = null;
      updates[paths.deviceControlV2Mode(deviceId, modeId)] = null;
      if (activeModeId === modeId) {
        updates[paths.devicePolicyActiveMode(deviceId)] = null;
        updates[paths.deviceControlV2ActiveMode(deviceId)] = null;
      }
    });
  }

  setActiveMode(deviceId, mode, onSuccess, onError) {
    const value = mode ? {
      modeId: mode.modeId,
      modeName: mode.name,
      activatedAt: serverTimestamp(),
      updatedBy: uid(),
    } : null;
    this.controlUpdate(deviceId, REVISION_ACTIVE_MODE, mode?.modeId ?? 'none', onSuccess, onError, (updates) => {
      updates[paths.devicePolicyActiveMode(deviceId)] = value;
      updates[paths.deviceControlV2ActiveMode(deviceId)] = value;
    });
  }

  startSafeMode(deviceId, durationMinutes, onSuccess, onError) {
    if (!this.serverClock.offsetFresh()) {
      // Without the real server offset, `until` derives from raw device time
      // while startedAt resolves to true server time at commit; the rules
      // reject until <= startedAt, denying the whole control write.
      return onError('Device clock is not synced yet — try again in a few seconds');
    }
    const durationMs = Math.min(Math.max(durationMinutes, 1), 1440) * 60_000;
    const effectiveMs = Math.min(durationMs, MAX_SAFE_MODE_WINDOW_MS);
    const value = {
      enabled: true,
      until: this.serverClock.now() + effectiveMs,
      startedAt: serverTimestamp(),
      startedBy: uid(),
    };
    this.controlUpdate(deviceId, REVISION_SAFE_MODE, 'enabled', onSuccess, onError, (updates) => {
      updates[paths.deviceSecuritySafeMode(deviceId)] = value;
      updates[paths.deviceControlV2SafeMode(deviceId)] = value;
    });
  }

  stopSafeMode(deviceId, onSuccess, onError) {
    const value = {
      enabled: false,
      until: 0,
      updatedAt: serverTimestamp(),
      updatedBy: uid(),
    };
    this.controlUpdate(deviceId, REVISION_SAFE_MODE, 'disabled', onSuccess, onError, (updates) => {
      updates[paths.deviceSecuritySafeMode(deviceId)] = value;
      updates[paths.deviceControlV2SafeMode(deviceId)] = value;
    });
  }

  sendCommand(deviceId, type, packageName, onSuccess, onError) {
    const currentUid = uid();
    if (!currentUid) return onError('Sign in before changing TV controls');
    const value = {
      type,
      requestedBy: currentUid,
      createdAt: serverTimestamp(),
      ttlMs: commandTtlMs(type),
      status: COMMAND_PENDING,
    };
    if (packageName != null) value.packageName = packageName;
    set(push(ref(this.database, paths.deviceCommands(deviceId))), value)
      .then(onSuccess)
      .catch((error) => onError(error?.message ?? 'Command failed'));
  }

  createPairRequest(deviceId, secret, manualCode, onSuccess, onError) {
    const currentUid = uid();
    if (!currentUid) return onError('Sign in before changing TV controls');
    const requestRef = push(ref(this.database, paths.pairRequests(deviceId)));
    const requestId = requestRef.key;
    if (!requestId) return onError('Could not create pair request');
    set(requestRef, {
      parentUid: currentUid,
      secret: secret ?? null,
      code: (manualCode ?? '').trim() !== '' ? manualCode : null,
      createdAt: serverTimestamp(),
      expiresAt: this.serverClock.now() + PAIRING_TTL_MS,
      status: PAIR_PENDING,
    })
      .then(() => onSuccess(requestId))
      .catch((error) => onError(error?.message ?? 'Pair request failed'));
  }

  removePairedDevice(deviceId, onSuccess, onError) {
    const currentUid = uid();
    if (!currentUid) return onError('Sign in before changing TV controls');
    // Delete the parent's own mirror node immediately (rules permit it): the
    // unpair command alone deadlocks removal when the TV is offline. The
    // command still goes out so a LIVE TV clears meta/ownerUid, which is
    // required before it can ever be re-paired; the TV's later redundant
    // mirror delete is a rules-safe no-op.
    remove(ref(this.database, paths.userDevice(currentUid, deviceId)))
      .then(() => this.sendCommand(deviceId, 'unpair', null, onSuccess, () => onSuccess()))
      .catch((error) => onError(error?.message ?? 'Device removal failed'));
  }

  updateUnlock(deviceId, request, status, approvalType = null, approvalDurationMs = null, onSuccess, onError) {
    const value = {
      status,
      updatedAt: serverTimestamp(),
      updatedBy: uid(),
    };
    if (approvalType != null) value.approvalType = approvalType;
    if (approvalDurationMs != null) value.approvalDurationMs = approvalDurationMs;
    update(ref(this.database, paths.deviceUnlockRequest(deviceId, request.requestId)), value)
      .then(onSuccess)
      .catch((error) => onError(error?.message ?? 'Unlock update failed'));
  }

  seedControlV2(deviceId, { policies, modes, activeMode, safeMode, pin }, onSuccess, onError) {
    const currentUid = uid();
    if (!currentUid) return onError('Sign in before changing TV controls');
    this.onQueuedWriteFailed = onError;
    this.enqueueControlWrite(async () => {
      const revisionId = this.newRevisionId(deviceId);
      const encodeAll = (map) => Object.fromEntries(
        Object.entries(map ?? {}).map(([pkg, policy]) => [encodePackageKey(pkg), this.appPolicyValue(pkg, policy)]),
      );
      const control = {
        schemaVersion: SYNC_PROTOCOL_VERSION,
        revisionId,
        updatedAt: serverTimestamp(),
        updatedBy: currentUid,
        apps: encodeAll(policies),
        modes: Object.fromEntries((modes ?? []).map((mode) => [mode.modeId, {
          modeId: mode.modeId,
          name: mode.name,
          createdAt: mode.createdAt ?? null,
          updatedAt: mode.updatedAt ?? null,
          apps: encodeAll(mode.appPolicies),
        }])),
        activeMode: activeMode?.modeId ? {
          modeId: activeMode.modeId,
          modeName: activeMode.modeName,
          activatedAt: activeMode.activatedAt,
        } : null,
        safeMode: {
          enabled: safeMode.enabled,
          until: safeMode.until ?? 0,
          startedAt: safeMode.startedAt,
          startedBy: safeMode.startedBy,
        },
        pin: pin ? {
          salt: pin.salt,
          hash: pin.hash,
          version: pin.version,
          algorithm: pin.algorithm,
          iterations: pin.iterations,
          updatedAt: pin.updatedAt,
        } : null,
      };
      const updates = { [paths.deviceControlV2(deviceId)]: control };
      this.addDesiredRevision(updates, deviceId, revisionId, REVISION_MIGRATION, 'control', currentUid);
      this.finishQueuedWrite(
        update(ref(this.database), updates),
        onSuccess, onError, 'Control migration failed',
      );
    });
  }
}

/* ============================== READS ============================== */

const INITIAL_RETRY_MS = 5_000;
const MAX_RETRY_MS = 5 * 60_000;

export class ParentSyncRepository {
  constructor(database, observer, retentionNow = Date.now) {
    this.database = database;
    this.observer = observer;
    this.detailRegistrations = [];
    this.deviceRegistration = null;
    this.connectionRegistration = null;
    this.pairingRegistration = null;
    this.currentUid = null;
    this.currentDeviceId = null;
    this.devicesCallback = null;
    this.errorCallback = () => {};
    this.retryDelayMs = INITIAL_RETRY_MS;
    this.retryTimer = null;
    this.retention = attachRetentionCleaner(database, retentionNow);
  }

  observeConnection(onConnected) {
    this.connectionRegistration?.unsubscribe();
    this.connectionRegistration = this.register(
      ref(this.database, '.info/connected'), false,
      () => {},
      (snap) => onConnected(snap.val() === true),
    );
  }

  observeDevices(uidValue, onDevices, onError) {
    this.currentUid = uidValue;
    this.devicesCallback = onDevices;
    this.errorCallback = onError;
    this.attachDeviceList();
  }

  observeDevice(deviceId) {
    this.clearDeviceDetails();
    this.currentDeviceId = deviceId;
    this.attachDeviceDetails();
  }

  clearSelectedDevice() {
    this.clearDeviceDetails();
  }

  refresh() {
    this.retryDelayMs = INITIAL_RETRY_MS;
    this.reattach();
  }

  observePairRequest(deviceId, requestId, onValue, onError) {
    this.clearPairRequestObserver();
    this.pairingRegistration = this.register(
      ref(this.database, paths.pairRequest(deviceId, requestId)), false,
      onError,
      (snap) => {
        if (!snap.exists()) return onValue(null);
        const v = snap.val();
        onValue({
          deviceId,
          requestId,
          status: v.status ?? 'pending',
          createdAt: numOrNull(v.createdAt),
          expiresAt: numOrNull(v.expiresAt),
          error: strOrNull(v.error),
        });
      },
    );
  }

  clearPairRequestObserver() {
    this.pairingRegistration?.unsubscribe();
    this.pairingRegistration = null;
  }

  close() {
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.retryTimer = null;
    this.deviceRegistration?.unsubscribe();
    this.deviceRegistration = null;
    this.connectionRegistration?.unsubscribe();
    this.connectionRegistration = null;
    this.clearPairRequestObserver();
    this.clearDeviceDetails();
    this.currentUid = null;
    this.currentDeviceId = null;
    this.observer = null;
  }

  attachDeviceList() {
    if (!this.currentUid || !this.devicesCallback) return;
    this.deviceRegistration?.unsubscribe();
    const callback = this.devicesCallback;
    this.deviceRegistration = this.register(
      ref(this.database, paths.userDevices(this.currentUid)), true,
      this.errorCallback,
      (snap) => {
        this.retryDelayMs = INITIAL_RETRY_MS;
        const value = snap.val() ?? {};
        callback(Object.entries(value).map(([key, v]) => {
          const deviceId = v?.deviceId ?? key;
          return {
            deviceId,
            label: v?.label ?? deviceId,
            lastSeen: numOrNull(v?.lastSeen),
            online: v?.online === true,
            enforcementMode: v?.enforcementMode ?? 'unprotected',
            protectionHealthy: v?.protectionHealthy === true,
          };
        }));
      },
    );
  }

  attachDeviceDetails() {
    const deviceId = this.currentDeviceId;
    if (!deviceId) return;
    const obs = this.observer;
    if (!obs) return;
    this.runRetention(deviceId);

    const observe = (path, data, keepSynced = true) => {
      this.detailRegistrations.push(this.register(ref(this.database, path), keepSynced, obs.onError, data));
    };
    const observeQuery = (q, data) => {
      this.detailRegistrations.push(this.register(q, false, obs.onError, data));
    };

    observe(paths.deviceApps(deviceId), (snap) => {
      const out = {};
      for (const [key, v] of Object.entries(snap.val() ?? {})) {
        const packageName = normalizedPackageName(key, v?.packageName ?? null);
        if (!packageName) continue;
        if (DEPRECATED_SET.has(packageName)) continue;
        out[packageName] = {
          packageName,
          label: v?.label ?? packageName,
          blockable: v?.blockable === true,
          protectedReason: strOrNull(v?.protectedReason),
        };
      }
      obs.onApps(out);
    });

    observe(paths.devicePolicyApps(deviceId), (snap) => {
      const out = {};
      for (const [key, v] of Object.entries(snap.val() ?? {})) {
        const packageName = normalizedPackageName(key, v?.packageName ?? null);
        if (!packageName) continue;
        out[packageName] = parentPolicy(v);
      }
      obs.onPolicies(out);
    });

    observe(paths.devicePolicyModes(deviceId), (snap) => {
      const modes = [];
      for (const [key, v] of Object.entries(snap.val() ?? {})) {
        const modeId = v?.modeId ?? key;
        const appPolicies = {};
        for (const [appKey, appValue] of Object.entries(v?.apps ?? {})) {
          const packageName = normalizedPackageName(appKey, appValue?.packageName ?? null);
          if (!packageName) continue;
          appPolicies[packageName] = parentPolicy(appValue);
        }
        modes.push({
          modeId,
          name: v?.name ?? 'Mode',
          appPolicies,
          createdAt: numOrNull(v?.createdAt),
          updatedAt: numOrNull(v?.updatedAt),
        });
      }
      modes.sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()));
      obs.onModes(modes);
    });

    observe(paths.devicePolicyActiveMode(deviceId), (snap) => {
      const v = snap.val();
      obs.onActiveMode({
        modeId: strOrNull(v?.modeId),
        modeName: strOrNull(v?.modeName),
        activatedAt: numOrNull(v?.activatedAt),
      });
    });

    observe(paths.deviceSecuritySafeMode(deviceId), (snap) => {
      const v = snap.val();
      obs.onSafeMode({
        enabled: v?.enabled === true,
        until: numOrNull(v?.until),
        startedAt: numOrNull(v?.startedAt),
        startedBy: strOrNull(v?.startedBy),
      });
    });

    observe(paths.deviceSecurityPin(deviceId), (snap) => {
      const v = snap.val();
      if (!v || !v.salt || !v.hash) return obs.onPin(null);
      obs.onPin({
        salt: v.salt,
        hash: v.hash,
        version: typeof v.version === 'number' ? v.version : PIN_LEGACY_VERSION,
        algorithm: strOrNull(v.algorithm),
        iterations: numOrNull(v.iterations),
        updatedAt: numOrNull(v.updatedAt),
      });
    });

    observe(paths.deviceStateApps(deviceId), (snap) => {
      const out = {};
      for (const [key, v] of Object.entries(snap.val() ?? {})) {
        const packageName = normalizedPackageName(key, v?.packageName ?? null);
        if (!packageName) continue;
        out[packageName] = parentState(v);
      }
      obs.onStates(out);
    });

    observe(paths.deviceSecurityRuntime(deviceId), (snap) => obs.onSecurity(securityRuntime(snap.val())));

    observeQuery(
      query(ref(this.database, paths.deviceUnlockRequests(deviceId)), orderByChild('createdAt'), limitToLast(30)),
      (snap) => {
        const rows = [];
        for (const [key, v] of Object.entries(snap.val() ?? {})) {
          rows.push({
            requestId: v?.requestId ?? key,
            packageName: v?.packageName ?? '',
            reason: v?.reason ?? '',
            status: v?.status ?? '',
            createdAt: numOrNull(v?.createdAt),
            expiresAt: numOrNull(v?.expiresAt),
            updatedAt: numOrNull(v?.updatedAt),
            approvalType: strOrNull(v?.approvalType),
            approvalDurationMs: numOrNull(v?.approvalDurationMs),
            tvApplyStatus: strOrNull(v?.tvApplyStatus),
            tvAppliedAt: numOrNull(v?.tvAppliedAt),
          });
        }
        rows.sort((a, b) => (b.createdAt ?? 0) - (a.createdAt ?? 0));
        obs.onUnlockRequests(rows);
      },
    );

    observeQuery(
      query(ref(this.database, paths.deviceTamperEvents(deviceId)), orderByChild('createdAt'), limitToLast(50)),
      (snap) => {
        const rows = [];
        for (const [key, v] of Object.entries(snap.val() ?? {})) {
          rows.push({
            eventId: key,
            type: v?.type ?? '',
            message: strOrNull(v?.message),
            createdAt: numOrNull(v?.createdAt),
          });
        }
        rows.sort((a, b) => (b.createdAt ?? 0) - (a.createdAt ?? 0));
        obs.onTamperEvents(rows);
      },
    );

    observeQuery(
      query(ref(this.database, paths.deviceCommands(deviceId)), orderByChild('createdAt'), limitToLast(20)),
      (snap) => {
        const rows = [];
        for (const [key, v] of Object.entries(snap.val() ?? {})) {
          if (v?.type == null) continue;
          rows.push({
            commandId: key,
            type: v.type,
            packageName: strOrNull(v.packageName),
            status: v.status ?? 'pending',
            createdAt: numOrNull(v.createdAt),
            startedAt: numOrNull(v.startedAt),
            completedAt: numOrNull(v.completedAt),
            error: strOrNull(v.error),
          });
        }
        rows.sort((a, b) => (b.createdAt ?? 0) - (a.createdAt ?? 0));
        obs.onCommands(rows);
      },
    );

    observe(paths.deviceActivityCurrent(deviceId), (snap) => {
      const v = snap.val();
      if (!v || !snap.exists()) return obs.onActivityCurrent(null);
      if (v.packageName == null) return;
      obs.onActivityCurrent({
        packageName: v.packageName,
        appLabel: v.appLabel ?? v.packageName ?? '',
        appStartedAt: v.appStartedAt ?? 0,
        overlayState: v.overlayState ?? 'none',
        mediaTitle: strOrNull(v.mediaTitle),
        mediaSubtitle: strOrNull(v.mediaSubtitle),
        playbackState: v.playbackState ?? 'unknown',
        positionMs: numOrNull(v.positionMs),
        durationMs: numOrNull(v.durationMs),
        positionCapturedAt: numOrNull(v.positionCapturedAt),
        playbackSpeed: typeof v.playbackSpeed === 'number' ? v.playbackSpeed : 0,
        updatedAt: v.updatedAt ?? 0,
      });
    });

    observeQuery(
      query(ref(this.database, paths.deviceActivityHistory(deviceId)), orderByChild('startedAt'), limitToLast(200)),
      (snap) => {
        const rows = [];
        for (const [key, v] of Object.entries(snap.val() ?? {})) {
          const startedAt = numOrNull(v?.startedAt);
          const endedAt = numOrNull(v?.endedAt);
          const type = v?.type;
          const packageName = v?.packageName;
          if (startedAt == null || endedAt == null || type == null || packageName == null) continue;
          rows.push({
            id: key,
            type,
            packageName,
            appLabel: v.appLabel ?? packageName ?? '',
            title: strOrNull(v.title),
            subtitle: strOrNull(v.subtitle),
            startedAt,
            endedAt,
            durationMs: numOrNull(v.durationMs),
            playbackState: strOrNull(v.playbackState),
            overlayMs: v.overlayMs ?? 0,
          });
        }
        obs.onActivityHistory(rows);
      },
    );

    observe(paths.deviceSyncDesired(deviceId), (snap) => obs.onDesiredRevision(parseDesired(snap.val())));

    observe(paths.deviceSyncApplied(deviceId), (snap) => {
      const v = snap.val();
      obs.onAppliedRevision({
        revisionId: strOrNull(v?.revisionId),
        status: strOrNull(v?.status),
        appliedAt: numOrNull(v?.appliedAt),
        sessionId: strOrNull(v?.sessionId),
        error: strOrNull(v?.error),
      });
    });

    observe(paths.deviceSyncRuntime(deviceId), (snap) => obs.onSyncRuntime(syncRuntime(snap.val())));

    observe(paths.deviceControlV2(deviceId), (snap) => {
      if (!snap.exists()) return obs.onControlV2('MISSING', null);
      const result = parseControl(snap.val());
      if (result.ok) obs.onControlV2('VALID', result.value);
      else obs.onControlV2('INVALID', null, result.error);
    });
  }

  runRetention(deviceId) {
    this.retention(deviceId);
  }

  register(queryOrRef, _keepSynced, onError, onData) {
    const unsubscribe = onValue(
      queryOrRef,
      (snap) => {
        this.retryDelayMs = INITIAL_RETRY_MS;
        onData(snap);
      },
      (error) => {
        onError(error?.message ?? 'Read failed');
        this.scheduleRetry();
      },
    );
    return { queryOrRef, unsubscribe };
  }

  scheduleRetry() {
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.retryTimer = setTimeout(() => this.reattach(), this.retryDelayMs);
    this.retryDelayMs = Math.min(this.retryDelayMs * 2, MAX_RETRY_MS);
  }

  reattach() {
    this.attachDeviceList();
    if (this.currentDeviceId && this.observer) {
      this.clearDeviceDetails();
      this.attachDeviceDetails();
    }
  }

  clearDeviceDetails() {
    this.detailRegistrations.forEach((r) => r.unsubscribe());
    this.detailRegistrations = [];
  }
}

const DEPRECATED_SET = new Set(['com.guardpulse.policy.settings_sections']);

/* ============================== PARSERS ============================== */

function parentPolicy(v) {
  const limit = typeof v?.dailyLimitMinutes === 'number' && Number.isInteger(v.dailyLimitMinutes)
    && v.dailyLimitMinutes >= 1 && v.dailyLimitMinutes <= 1440
    ? v.dailyLimitMinutes : null;
  return { manualBlocked: v?.manualBlocked === true, dailyLimitMinutes: limit };
}

function parentState(v) {
  return {
    suspended: v?.suspended === true,
    requestedSuspended: v?.requestedSuspended === true,
    manualBlocked: v?.manualBlocked === true,
    dailyLimitBlocked: v?.dailyLimitBlocked === true,
    networkBlocked: v?.networkBlocked === true,
    vpnApplied: v?.vpnApplied === true,
    vpnActive: v?.vpnActive === true,
    lockBlocked: v?.lockBlocked === true,
    lockReason: strOrNull(v?.lockReason),
    vpnLastError: strOrNull(v?.vpnLastError),
    fallbackLocked: v?.fallbackLocked === true,
    enforcementMode: v?.enforcementMode ?? 'unprotected',
    blockReason: strOrNull(v?.blockReason),
    usageMinutesToday: typeof v?.usageMinutesToday === 'number' ? v.usageMinutesToday : 0,
    usageMsToday: typeof v?.usageMsToday === 'number'
      ? v.usageMsToday
      : (typeof v?.usageMinutesToday === 'number' ? v.usageMinutesToday : 0) * 60_000,
    usageCapturedAt: numOrNull(v?.usageCapturedAt),
    foregroundActive: v?.foregroundActive === true,
    foregroundStartedAt: numOrNull(v?.foregroundStartedAt),
    controlRevisionId: strOrNull(v?.controlRevisionId),
    updatedAt: numOrNull(v?.updatedAt),
    lastError: strOrNull(v?.lastError),
  };
}

function securityRuntime(v) {
  return {
    enforcementMode: v?.enforcementMode ?? 'unprotected',
    deviceOwner: v?.deviceOwner === true,
    deviceAdmin: v?.deviceAdmin === true,
    deviceAdminSetupAvailable: v?.deviceAdminSetupAvailable !== false,
    accessibility: v?.accessibility === true,
    usageAccess: v?.usageAccess === true,
    mediaTitlesEnabled: v?.mediaTitlesEnabled === true,
    vpnPrepared: v?.vpnPrepared === true,
    vpnActive: v?.vpnActive === true,
    vpnBlockedCount: typeof v?.vpnBlockedCount === 'number' ? v.vpnBlockedCount : 0,
    vpnLastError: strOrNull(v?.vpnLastError),
    backgroundUnrestricted: v?.backgroundUnrestricted === true,
    pinConfigured: v?.pinConfigured === true,
    pinHashVersion: typeof v?.pinHashVersion === 'number' ? v.pinHashVersion : 0,
    protectionHealthy: v?.protectionHealthy === true,
    lastForegroundPackage: strOrNull(v?.lastForegroundPackage),
    lastSyncError: strOrNull(v?.lastSyncError),
    safeModeActive: v?.safeModeActive === true,
    safeModeUntil: numOrNull(v?.safeModeUntil),
    activeModeId: strOrNull(v?.activeModeId),
    activeModeName: strOrNull(v?.activeModeName),
    updatedAt: numOrNull(v?.updatedAt),
  };
}

function syncRuntime(v) {
  return {
    connected: v?.connected === true,
    sessionId: strOrNull(v?.sessionId),
    protocolVersion: typeof v?.protocolVersion === 'number' ? v.protocolVersion : 0,
    connectedAt: numOrNull(v?.connectedAt),
    lastPolicyReceivedAt: numOrNull(v?.lastPolicyReceivedAt),
    lastPolicyAppliedAt: numOrNull(v?.lastPolicyAppliedAt),
    lastStateWriteAt: numOrNull(v?.lastStateWriteAt),
    lastUsageWriteAt: numOrNull(v?.lastUsageWriteAt),
    lastHeartbeatWriteAt: numOrNull(v?.lastHeartbeatWriteAt),
    lastInventoryWriteAt: numOrNull(v?.lastInventoryWriteAt),
    lastHealthWriteAt: numOrNull(v?.lastHealthWriteAt),
    lastCommandWriteAt: numOrNull(v?.lastCommandWriteAt),
    lastUnlockWriteAt: numOrNull(v?.lastUnlockWriteAt),
    lastTamperWriteAt: numOrNull(v?.lastTamperWriteAt),
    lastSuccessAt: numOrNull(v?.lastSuccessAt),
    lastFailedChannel: strOrNull(v?.lastFailedChannel),
    lastError: strOrNull(v?.lastError),
    lastErrorAt: numOrNull(v?.lastErrorAt),
    inventoryRevision: strOrNull(v?.inventoryRevision),
  };
}

function numOrNull(v) {
  return typeof v === 'number' ? v : null;
}

function strOrNull(v) {
  return typeof v === 'string' ? v : null;
}
