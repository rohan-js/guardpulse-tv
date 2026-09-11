/**
 * Port of ParentSyncViewModel.kt: the single state store + every UI action,
 * including control gating (VALID/INVALID/MISSING/UNKNOWN), the pending-op
 * queue, control migration (maybeSeedControlV2), confirmed-vs-live state
 * promotion, pairing persistence, and the serverNow ticker.
 */
import {
  onAuthStateChanged, signInWithEmailAndPassword, createUserWithEmailAndPassword, signOut as fbSignOut,
} from 'firebase/auth';
import { ref, get as dbGet } from 'firebase/database';
import { auth, database, serverClock } from './firebase.js';
import { ParentRepository, ParentSyncRepository } from './repository.js';
import { parse as parseControl } from './controlProtocol.js';
import { promoteConfirmedControl } from './reducers.js';
import {
  COMMAND_RESCAN_APPS, COMMAND_RESET_TODAY, COMMAND_OPEN_SETUP,
  UNLOCK_APPROVED, UNLOCK_DENIED, UNLOCK_EXPIRED,
  UNLOCK_APPROVAL_ONE_VISIT, UNLOCK_APPROVAL_TIMED,
  PAIR_ACCEPTED, PAIR_REJECTED, PAIR_EXPIRED, PAIR_FAILED,
} from './policyConstants.js';
import { parsePairingPayload } from './pairing.js';

const SERVER_NOW_TICK_MS = 5_000;

export const store = {
  state: initialState(),
  listeners: new Set(),

  subscribe(listener) {
    this.listeners.add(listener);
    listener(this.state);
  },

  get() {
    return this.state;
  },

  setState(transform) {
    this.state = transform(this.state);
    this.listeners.forEach((listener) => listener(this.state));
  },

  setMessage(message) {
    this.setState((s) => ({ ...s, message: message ?? null }));
  },
};

function initialState() {
  return {
    configured: false,
    firebaseMessage: null,
    signedIn: false,
    authBusy: false,
    message: null,
    phoneConnected: false,
    serverNow: Date.now(),

    devices: [],
    selectedDeviceId: null,
    loadingDeviceDetails: false,

    apps: {},
    policies: {},
    modes: [],
    activeMode: { modeId: null, modeName: null, activatedAt: null },
    safeMode: { enabled: false, until: null, startedAt: null, startedBy: null },
    pin: null,
    states: {},
    security: null,
    unlockRequests: [],
    tamperEvents: [],
    commands: [],
    activityCurrent: null,
    activityHistory: [],

    desiredRevision: null,
    appliedRevision: { revisionId: null, status: null, appliedAt: null, sessionId: null, error: null },
    syncRuntime: null,

    controlAvailability: 'UNKNOWN',
    controlError: null,
    controlV2Exists: false,
    migrationRequested: false,
    desiredControl: null,
    confirmedControl: null, // { desired, policies, modes, activeMode, safeMode, confirmedStates }
    latestRuntimeStates: {},
    pendingControlOperations: [],

    legacyPoliciesLoaded: false,
    legacyModesLoaded: false,
    legacyActiveModeLoaded: false,
    legacySafeModeLoaded: false,
    legacyPinLoaded: false,

    pairRequest: null,
  };
}

export const repository = new ParentRepository(database, serverClock);
export const syncRepository = new ParentSyncRepository(database, makeObserver());

const SELECTED_DEVICE_KEY = 'selectedDeviceId';
const PENDING_PAIR_DEVICE_KEY = 'pendingPairDeviceId';
const PENDING_PAIR_REQUEST_KEY = 'pendingPairRequestId';

const localStorageGet = (key) => { try { return window.localStorage.getItem(key); } catch { return null; } };
const localStorageSet = (key, value) => { try { window.localStorage.setItem(key, value); } catch { /* private mode */ } };
const localStorageRemove = (key) => { try { window.localStorage.removeItem(key); } catch { /* private mode */ } };

/* ============================ observer wiring ============================ */

function makeObserver() {
  const S = () => store.setState;
  return {
    onApps: (v) => S()((s) => ({ ...s, apps: v, legacyPoliciesLoaded: s.legacyPoliciesLoaded })),
    onPolicies: (v) => S()((s) => ({ ...s, policies: v, legacyPoliciesLoaded: true })),
    onModes: (v) => S()((s) => ({ ...s, modes: v, legacyModesLoaded: true })),
    onActiveMode: (v) => S()((s) => ({ ...s, activeMode: v, legacyActiveModeLoaded: true })),
    onSafeMode: (v) => S()((s) => ({ ...s, safeMode: v, legacySafeModeLoaded: true })),
    onPin: (v) => S()((s) => ({ ...s, pin: v, legacyPinLoaded: true })),
    onStates: (v) => S()((s) => onRuntimeStates(s, v)),
    onSecurity: (v) => S()((s) => ({ ...s, security: v })),
    onUnlockRequests: (v) => S()((s) => ({ ...s, unlockRequests: v })),
    onTamperEvents: (v) => S()((s) => ({ ...s, tamperEvents: v })),
    onCommands: (v) => S()((s) => ({ ...s, commands: v })),
    onActivityCurrent: (v) => S()((s) => ({ ...s, activityCurrent: v })),
    onActivityHistory: (v) => S()((s) => ({ ...s, activityHistory: [...v].sort((a, b) => (b.startedAt ?? 0) - (a.startedAt ?? 0)) })),
    onDesiredRevision: (v) => S()((s) => ({ ...s, desiredRevision: v })),
    onAppliedRevision: (v) => S()((s) => onAppliedRevision(s, v)),
    onSyncRuntime: (v) => S()((s) => ({ ...s, syncRuntime: v, serverNow: serverClock.now() })),
    onControlV2: (availability, value, error) => S()((s) => onControlV2(s, availability, value, error)),
    onError: (message) => store.setMessage(message),
  };
}

function onRuntimeStates(s, states) {
  const latest = { ...s.latestRuntimeStates, ...states };
  const confirmed = s.confirmedControl
    ? promoteConfirmedControl(s.confirmedControl.desired, s.appliedRevision, latest)
    : s.confirmedControl;
  return {
    ...s,
    states: { ...s.states, ...states },
    latestRuntimeStates: latest,
    confirmedControl: confirmed ?? s.confirmedControl,
  };
}

function onAppliedRevision(s, applied) {
  const confirmed = promoteConfirmedControl(s.desiredRevision, applied, s.latestRuntimeStates);
  return { ...s, appliedRevision: applied, confirmedControl: confirmed ?? s.confirmedControl };
}

function onControlV2(s, availability, value, error) {
  const controlV2Exists = availability !== 'MISSING';
  let next = {
    ...s,
    controlAvailability: availability,
    controlError: error ?? null,
    controlV2Exists,
  };
  if (availability === 'VALID') {
    next.migrationRequested = true;
    next.desiredControl = value;
    next.confirmedControl = promoteConfirmedControl(value, s.appliedRevision, s.latestRuntimeStates) ?? next.confirmedControl;
    next = flushPendingControlOperations(next);
  } else if (availability === 'INVALID') {
    next.message = 'TV control is invalid. Mutations are disabled until it is repaired.';
  } else if (availability === 'MISSING') {
    next = maybeSeedControlV2(next);
  }
  return next;
}

function flushPendingControlOperations(s) {
  if (s.pendingControlOperations.length === 0) return s;
  const ops = [...s.pendingControlOperations];
  for (const op of ops) op();
  return { ...s, pendingControlOperations: [] };
}

function maybeSeedControlV2(s) {
  if (s.migrationRequested || s.controlV2Exists || !s.selectedDeviceId) return s;
  const loaded = s.legacyPoliciesLoaded && s.legacyModesLoaded && s.legacyActiveModeLoaded
    && s.legacySafeModeLoaded && s.legacyPinLoaded;
  if (!loaded) return s;
  const deviceId = s.selectedDeviceId;
  const snapshot = {
    policies: s.policies,
    modes: s.modes,
    activeMode: s.activeMode,
    safeMode: s.safeMode,
    pin: s.pin,
  };
  return withMigration(s, deviceId, snapshot);
}

function withMigration(s, deviceId, snapshot) {
  const next = { ...s, migrationRequested: true };
  repository.seedControlV2(
    deviceId, snapshot,
    () => store.setMessage('TV controls upgraded; waiting for TV acknowledgement'),
    (error) => {
      store.setMessage(error);
      store.setState((cur) => ({ ...cur, migrationRequested: false }));
    },
  );
  return next;
}

/* ============================== auth + shell ============================== */

export function initStore() {
  const configured = !isPlaceholder(globalThis.GUARDPULSE_CONFIG);
  serverClock.start();
  setInterval(() => store.setState((s) => ({ ...s, serverNow: serverClock.now() })), SERVER_NOW_TICK_MS);
  onAuthStateChanged(auth, (user) => {
    if (user) {
      store.setState((s) => ({ ...s, configured, signedIn: true }));
      syncRepository.observeConnection((connected) => {
        store.setState((s0) => ({ ...s0, phoneConnected: connected }));
        if (connected) refresh();
      });
      syncRepository.observeDevices(user.uid, (devices) => {
        store.setState((s0) => ({ ...s0, devices }));
        autoSelectDevice(devices);
      }, (error) => store.setMessage(error));
      resumePairRequestObserver();
    } else {
      clearSignedOutState();
    }
  });
  store.setState((s) => ({ ...s, configured }));
}

function isPlaceholder(config) {
  const values = Object.values(config ?? {});
  return values.length === 0 || values.some((v) => typeof v === 'string' && (
    v.trim() === '' || v.startsWith('replace_') || v.startsWith('your_') ||
    v.includes('your-firebase-project') || v.includes('example.invalid')
  ));
}

export function signIn(email, password, onBusy) {
  if (!validateAuthInput(email, password)) return;
  store.setState((s) => ({ ...s, authBusy: true }));
  onBusy?.(true);
  signInWithEmailAndPassword(auth, email.trim(), password)
    .then(() => store.setMessage('Signed in'))
    .catch((error) => store.setMessage(error?.message ?? 'Sign in failed'))
    .finally(() => {
      store.setState((s) => ({ ...s, authBusy: false }));
      onBusy?.(false);
    });
}

export function createAccount(email, password, onBusy) {
  if (!validateAuthInput(email, password)) return;
  store.setState((s) => ({ ...s, authBusy: true }));
  onBusy?.(true);
  createUserWithEmailAndPassword(auth, email.trim(), password)
    .then(() => store.setMessage('Account created'))
    .catch((error) => store.setMessage(error?.message ?? 'Account creation failed'))
    .finally(() => {
      store.setState((s) => ({ ...s, authBusy: false }));
      onBusy?.(false);
    });
}

export function signOut() {
  syncRepository.close();
  fbSignOut(auth);
}

function validateAuthInput(email, password) {
  if (!email || email.trim() === '') {
    store.setMessage('Enter an email address');
    return false;
  }
  if ((password ?? '').length < 6) {
    store.setMessage('Password must be at least 6 characters');
    return false;
  }
  return true;
}

function clearSignedOutState() {
  localStorageRemove(SELECTED_DEVICE_KEY);
  localStorageRemove(PENDING_PAIR_DEVICE_KEY);
  localStorageRemove(PENDING_PAIR_REQUEST_KEY);
  syncRepository.close();
  store.setState((s) => ({
    ...initialState(),
    configured: s.configured,
    firebaseMessage: s.firebaseMessage,
    phoneConnected: s.phoneConnected,
    serverNow: s.serverNow,
    message: s.message,
  }));
}

/* ============================ device selection ============================ */

function autoSelectDevice(devices) {
  const current = store.get();
  const currentExists = devices.some((d) => d.deviceId === current.selectedDeviceId);
  if (current.selectedDeviceId && currentExists) return;
  const persisted = localStorageGet(SELECTED_DEVICE_KEY);
  if (persisted && devices.some((d) => d.deviceId === persisted)) {
    selectDevice(persisted);
    return;
  }
  if (devices.length === 1) {
    selectDevice(devices[0].deviceId);
  }
}

export function selectDevice(deviceId) {
  localStorageSet(SELECTED_DEVICE_KEY, deviceId);
  store.setState((s) => resetDeviceLoading(s, deviceId));
  syncRepository.observeDevice(deviceId);
}

function resetDeviceLoading(s, deviceId) {
  return {
    ...s,
    selectedDeviceId: deviceId,
    loadingDeviceDetails: true,
    apps: {}, policies: {}, modes: [], activeMode: { modeId: null, modeName: null, activatedAt: null },
    safeMode: { enabled: false, until: null, startedAt: null, startedBy: null },
    pin: null, states: {}, latestRuntimeStates: {}, security: null,
    unlockRequests: [], tamperEvents: [], commands: [],
    activityCurrent: null, activityHistory: [],
    desiredRevision: null,
    appliedRevision: { revisionId: null, status: null, appliedAt: null, sessionId: null, error: null },
    syncRuntime: null,
    controlAvailability: 'UNKNOWN',
    controlError: null,
    controlV2Exists: false,
    migrationRequested: false,
    desiredControl: null,
    confirmedControl: null,
    pendingControlOperations: [],
    legacyPoliciesLoaded: false, legacyModesLoaded: false, legacyActiveModeLoaded: false,
    legacySafeModeLoaded: false, legacyPinLoaded: false,
    selectedDeviceLabel: s.devices.find((d) => d.deviceId === deviceId)?.label ?? deviceId,
  };
}

export function clearSelectedDevice() {
  localStorageRemove(SELECTED_DEVICE_KEY);
  syncRepository.clearSelectedDevice();
  store.setState((s) => resetDeviceLoading(s, null));
}

export function refresh() {
  syncRepository.refresh();
}

export async function reconnect() {
  store.setMessage('Reconnecting...');
  try {
    await auth.currentUser?.getIdToken(true);
    refresh();
    store.setMessage('Reconnected');
  } catch (error) {
    store.setMessage(error?.message ?? 'Reconnect failed');
  }
}

/* ============================== control gating ============================== */

function mutateControl(run) {
  const s = store.get();
  if (s.controlAvailability === 'VALID') {
    run();
  } else if (s.controlAvailability === 'INVALID') {
    store.setMessage('Control changes are disabled until synchronized control is repaired');
  } else {
    store.setState((cur) => ({ ...cur, pendingControlOperations: [...cur.pendingControlOperations, run] }));
    store.setMessage('Preparing synchronized TV controls; your change is queued');
    maybeSeedControlV2(store.get());
  }
}

/* ============================== pair flow ============================== */

export function pair(payload, manualDeviceId, manualCode) {
  const parsed = parsePairingPayload(payload);
  const deviceId = parsed.deviceId || manualDeviceId;
  const secret = parsed.secret;
  if (!deviceId || deviceId.trim() === '') {
    store.setMessage('Enter a TV device ID or paste the QR payload.');
    return;
  }
  if ((!secret || secret.trim() === '') && (!manualCode || manualCode.trim() === '')) {
    store.setMessage('Enter the 6-digit code or paste the QR payload.');
    return;
  }
  repository.createPairRequest(
    deviceId, secret, manualCode,
    (requestId) => {
      localStorageSet(PENDING_PAIR_DEVICE_KEY, deviceId);
      localStorageSet(PENDING_PAIR_REQUEST_KEY, requestId);
      store.setMessage('Pair request sent; waiting for TV');
      observePairRequest(deviceId, requestId);
    },
    (error) => store.setMessage(error),
  );
}

function observePairRequest(deviceId, requestId) {
  syncRepository.observePairRequest(deviceId, requestId, (request) => {
    if (request == null) return;
    store.setState((s) => ({ ...s, pairRequest: request }));
    if (request.status === PAIR_ACCEPTED) {
      clearPersistedPairRequest();
      store.setMessage('TV pairing confirmed');
      refresh();
    } else if (request.status === PAIR_REJECTED) {
      clearPersistedPairRequest();
      store.setMessage(request.error ?? 'TV rejected the pairing request');
    } else if (request.status === PAIR_EXPIRED) {
      clearPersistedPairRequest();
      store.setMessage('Pairing request expired');
    } else if (request.status === PAIR_FAILED) {
      clearPersistedPairRequest();
      store.setMessage(request.error ?? 'TV pairing failed');
    }
  }, (error) => store.setMessage(error));
}

function clearPersistedPairRequest() {
  localStorageRemove(PENDING_PAIR_DEVICE_KEY);
  localStorageRemove(PENDING_PAIR_REQUEST_KEY);
  syncRepository.clearPairRequestObserver();
  store.setState((s) => ({ ...s, pairRequest: null }));
}

function resumePairRequestObserver() {
  const deviceId = localStorageGet(PENDING_PAIR_DEVICE_KEY);
  const requestId = localStorageGet(PENDING_PAIR_REQUEST_KEY);
  if (deviceId && requestId) observePairRequest(deviceId, requestId);
}

/* ============================== commands ============================== */

export function sendCommand(type, packageName = null) {
  const deviceId = store.get().selectedDeviceId;
  if (!deviceId) {
    store.setMessage('Select a TV first');
    return;
  }
  repository.sendCommand(deviceId, type, packageName,
    () => store.setMessage('Command sent; waiting for TV'),
    (error) => store.setMessage(error));
}

export function rescanApps() {
  sendCommand(COMMAND_RESCAN_APPS);
}

export function resetToday(packageName) {
  sendCommand(COMMAND_RESET_TODAY, packageName);
}

export function openTvSetup() {
  sendCommand(COMMAND_OPEN_SETUP);
}

export function removeDevice(deviceId, label) {
  repository.removePairedDevice(
    deviceId,
    () => {
      store.setMessage('Removal requested; waiting for TV');
      if (store.get().selectedDeviceId === deviceId) clearSelectedDevice();
    },
    (error) => store.setMessage(error),
  );
}

/* ============================== policies ============================== */

export function policyValidationMessage(apps, packageName, policy) {
  const app = apps[packageName];
  if (app && app.blockable === false) {
    return `This app is protected: ${app.protectedReason ?? 'not blockable'}`;
  }
  if (policy.dailyLimitMinutes != null && !(policy.dailyLimitMinutes >= 1 && policy.dailyLimitMinutes <= 1440)) {
    return 'Daily limit must be between 1 and 1440 minutes';
  }
  return null;
}

export function updatePolicy(packageName, policy) {
  const s = store.get();
  const deviceId = s.selectedDeviceId;
  if (!deviceId) {
    store.setMessage('Select a TV before changing app settings');
    return;
  }
  const validation = policyValidationMessage(s.apps, packageName, policy);
  if (validation) {
    store.setMessage(validation);
    return;
  }
  mutateControl(() => repository.updatePolicy(deviceId, packageName, policy,
    () => store.setMessage('Sent to TV; waiting for acknowledgement'),
    (error) => store.setMessage(error)));
}

/* ============================== pin ============================== */

export function pinValidationMessage(pin) {
  return /^\d{6}$/.test(pin) ? null : 'PIN must be 6 digits';
}

export function setPin(pin) {
  const s = store.get();
  const deviceId = s.selectedDeviceId;
  if (!deviceId) {
    store.setMessage('Select a TV before setting a PIN');
    return;
  }
  const validation = pinValidationMessage(pin);
  if (validation) {
    store.setMessage(validation);
    return;
  }
  mutateControl(() => repository.setPin(deviceId, pin,
    () => store.setMessage('Sent to TV; waiting for acknowledgement'),
    (error) => store.setMessage(error)));
}

/* ============================== modes ============================== */

export function modeNameValidationMessage(name) {
  return name.trim() === '' ? 'Mode name cannot be empty' : null;
}

export function createMode(name) {
  const deviceId = store.get().selectedDeviceId;
  if (!deviceId) return store.setMessage('Select a TV first');
  const validation = modeNameValidationMessage(name);
  if (validation) return store.setMessage(validation);
  mutateControl(() => repository.createMode(deviceId, name.trim(),
    () => store.setMessage('Sent to TV; waiting for acknowledgement'),
    (error) => store.setMessage(error)));
}

export function renameMode(modeId, name) {
  const deviceId = store.get().selectedDeviceId;
  if (!deviceId) return store.setMessage('Select a TV first');
  const validation = modeNameValidationMessage(name);
  if (validation) return store.setMessage(validation);
  mutateControl(() => repository.updateModeName(deviceId, modeId, name.trim(),
    () => store.setMessage('Sent to TV; waiting for acknowledgement'),
    (error) => store.setMessage(error)));
}

export function deleteMode(modeId) {
  const s = store.get();
  if (!s.selectedDeviceId) return store.setMessage('Select a TV first');
  mutateControl(() => repository.deleteMode(s.selectedDeviceId, modeId, s.activeMode.modeId,
    () => store.setMessage('Sent to TV; waiting for acknowledgement'),
    (error) => store.setMessage(error)));
}

export function setActiveMode(mode) {
  const s = store.get();
  if (!s.selectedDeviceId) return store.setMessage('Select a TV first');
  mutateControl(() => repository.setActiveMode(s.selectedDeviceId, mode,
    () => store.setMessage('Sent to TV; waiting for acknowledgement'),
    (error) => store.setMessage(error)));
}

export function updateModePolicy(modeId, packageName, policy) {
  const s = store.get();
  const deviceId = s.selectedDeviceId;
  if (!deviceId) return store.setMessage('Select a TV first');
  const validation = policyValidationMessage(s.apps, packageName, policy);
  if (validation) return store.setMessage(validation);
  mutateControl(() => repository.updateModePolicy(deviceId, modeId, packageName, policy,
    () => store.setMessage('Sent to TV; waiting for acknowledgement'),
    (error) => store.setMessage(error)));
}

/* ============================== safe mode ============================== */

export function safeModeValidationMessage(minutes) {
  return minutes >= 1 && minutes <= 1440 ? null : 'Safe Mode duration must be between 1 and 1440 minutes';
}

export function startSafeMode(durationMinutes) {
  const s = store.get();
  const deviceId = s.selectedDeviceId;
  if (!deviceId) return store.setMessage('Select a TV first');
  const validation = safeModeValidationMessage(durationMinutes);
  if (validation) return store.setMessage(validation);
  mutateControl(() => repository.startSafeMode(deviceId, durationMinutes,
    () => store.setMessage('Sent to TV; waiting for acknowledgement'),
    (error) => store.setMessage(error)));
}

export function stopSafeMode() {
  const deviceId = store.get().selectedDeviceId;
  if (!deviceId) return store.setMessage('Select a TV first');
  mutateControl(() => repository.stopSafeMode(deviceId,
    () => store.setMessage('Sent to TV; waiting for acknowledgement'),
    (error) => store.setMessage(error)));
}

/* ============================== unlocks ============================== */

export function updateUnlock(request, status, approvalType = null, approvalDurationMs = null) {
  const deviceId = store.get().selectedDeviceId;
  if (!deviceId) return store.setMessage('Select a TV first');
  const s = store.get();
  const label = s.apps[request.packageName]?.label ?? request.packageName;
  if (status === UNLOCK_APPROVED && request.expiresAt != null && serverClock.now() > request.expiresAt) {
    repository.updateUnlock(deviceId, request, UNLOCK_EXPIRED,
      () => store.setMessage('Unlock request expired'),
      (error) => store.setMessage(error));
    return;
  }
  repository.updateUnlock(deviceId, request, status, approvalType, approvalDurationMs, () => {
    if (status === UNLOCK_APPROVED && approvalType === UNLOCK_APPROVAL_TIMED) {
      store.setMessage(`Unlock sent for ${approvalDurationMs / 60_000} minutes; waiting for TV`);
    } else if (status === UNLOCK_APPROVED) {
      store.setMessage('One-visit unlock sent; waiting for TV');
    } else if (status === UNLOCK_DENIED) {
      store.setMessage('Unlock denied');
    }
  }, (error) => store.setMessage(error));
}

/* ============================== migration repair ============================== */

export function repairControlV2() {
  const s = store.get();
  const deviceId = s.selectedDeviceId;
  if (!deviceId) return store.setMessage('Select a TV first');
  if (s.controlAvailability !== 'INVALID') {
    store.setMessage('Synchronized control does not require repair');
    return;
  }
  const loaded = s.legacyPoliciesLoaded && s.legacyModesLoaded && s.legacyActiveModeLoaded
    && s.legacySafeModeLoaded && s.legacyPinLoaded;
  if (!loaded) {
    store.setMessage('Legacy TV controls are still loading; reconnect and try again');
    return;
  }
  repository.seedControlV2(deviceId, {
    policies: s.policies,
    modes: s.modes,
    activeMode: s.activeMode,
    safeMode: s.safeMode,
    pin: s.pin,
  },
  () => store.setMessage('Repair sent; waiting for TV validation'),
  (error) => store.setMessage(error));
}

/** Used by the "Firebase not configured" screen. */
export async function loadFirebaseMessage() {
  try {
    await dbGet(ref(database, '.info/serverTimeOffset'));
    return null;
  } catch (error) {
    return error?.message ?? 'Firebase is not reachable';
  }
}
