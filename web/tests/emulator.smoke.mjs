/**
 * Rules smoke test — runs ONLY inside the Firebase emulator (database+auth).
 * Purpose: prove every parent write path in repository.js passes the deployed
 * rules, and that malformed writes are denied. Simulates the TV side with a
 * second Firebase app signed in anonymously (mirroring the real TV).
 *
 * Run (JDK 17 required for the database emulator):
 *   JAVA_HOME="<jdk17>" npx -y firebase-tools@12 emulators:exec \
 *     --only database,auth --project guardpulse-parental-control-test \
 *     "npm --prefix web run test:smoke"
 */
import test, { after } from 'node:test';
import assert from 'node:assert/strict';

// Failsafe: if anything keeps the event loop alive after the suite, exit hard.
setTimeout(() => {
  console.error('SMOKE: forced exit (event loop held open after suite)');
  process.exit(1);
}, 90_000);

const TEST_PROJECT = 'guardpulse-parental-control-test';

globalThis.GUARDPULSE_CONFIG = {
  apiKey: 'demo-api-key',
  authDomain: `${TEST_PROJECT}.firebaseapp.com`,
  databaseURL: `http://127.0.0.1:9000?ns=${TEST_PROJECT}-default-rtdb`,
  projectId: TEST_PROJECT,
  appId: 'demo-app-id',
};

const [{ initializeApp }, fbAuth, fbDb] = await Promise.all([
  import('firebase/app'),
  import('firebase/auth'),
  import('firebase/database'),
]);
const { auth, database, serverClock } = await import('../js/firebase.js');
const { ParentRepository, ParentSyncRepository } = await import('../js/repository.js');
const { encode } = await import('../js/packageKeys.js');

// Wire the default (parent) app to the emulators before any I/O.
fbDb.connectDatabaseEmulator(database, '127.0.0.1', 9000);
fbAuth.connectAuthEmulator(auth, 'http://127.0.0.1:9099', { disableWarnings: true });
serverClock.start();

// A second app simulates the TV (anonymous auth, like the real device).
const tvApp = initializeApp(globalThis.GUARDPULSE_CONFIG, 'tv-app');
const tvAuth = fbAuth.getAuth(tvApp);
const tvDb = fbDb.getDatabase(tvApp);
fbDb.connectDatabaseEmulator(tvDb, '127.0.0.1', 9000);
fbAuth.connectAuthEmulator(tvAuth, 'http://127.0.0.1:9099', { disableWarnings: true });

const { ref, get, update, set, remove, push } = fbDb;
const { onAuthStateChanged } = fbAuth;

const DEVICE_ID = `tv-${Math.random().toString(16).slice(2, 10)}`;
const PARENT_EMAIL = 'parent-smoke@test.local';
const PARENT_PASSWORD = 'password123';

const repository = new ParentRepository(database, serverClock);
const syncRepository = new ParentSyncRepository(database, {
  onApps: () => {}, onPolicies: () => {}, onModes: () => {}, onActiveMode: () => {},
  onSafeMode: () => {}, onPin: () => {}, onStates: () => {}, onSecurity: () => {},
  onUnlockRequests: () => {}, onTamperEvents: () => {}, onCommands: () => {},
  onActivityCurrent: () => {}, onActivityHistory: () => {}, onDesiredRevision: () => {},
  onAppliedRevision: () => {}, onSyncRuntime: () => {}, onControlV2: () => {},
  onError: () => {},
});

async function waitForAuth(authInstance) {
  if (authInstance.currentUser) return;
  await new Promise((resolve) => {
    const unsub = onAuthStateChanged(authInstance, () => { unsub(); resolve(); });
  });
}

// Repository methods are callback-based (Android parity) and return nothing;
// wrap their (onSuccess, onError) callbacks into a promise for the tests.
const call = (run) => new Promise((resolve, reject) => {
  run((...args) => resolve(args), (message) => reject(new Error(message ?? 'write failed')));
});

test('setup: parent signs up, TV registers, pairing completes', { timeout: 25_000 }, async () => {
  // Emulator runs keep auth data; make sign-up idempotent.
  let cred;
  try {
    cred = await fbAuth.createUserWithEmailAndPassword(auth, PARENT_EMAIL, PARENT_PASSWORD);
  } catch (error) {
    if (error?.code === 'auth/email-already-in-use') {
      cred = await fbAuth.signInWithEmailAndPassword(auth, PARENT_EMAIL, PARENT_PASSWORD);
    } else {
      throw error;
    }
  }
  assert.ok(cred.user.uid);
  await waitForAuth(auth);

  await fbAuth.signInAnonymously(tvAuth);
  await waitForAuth(tvAuth);
  await set(ref(tvDb, `devices/${DEVICE_ID}/meta`), {
    deviceId: DEVICE_ID,
    tvUid: tvAuth.currentUser.uid,
    label: 'Smoke TV',
  });

  await call((ok, fail) => repository.createPairRequest(DEVICE_ID, 'pair-secret', '', ok, fail));
  const requests = (await get(ref(tvDb, `pairRequests/${DEVICE_ID}`))).val();
  const [requestId, request] = Object.entries(requests)[0];
  assert.equal(request.status, 'pending');
  assert.equal(request.secret, 'pair-secret');

  // TV flow (mirrors TvSyncService): claim ownerUid in a transaction FIRST,
  // then the mirror + acceptance multi-path update once ownerUid is set.
  await update(ref(tvDb), {
    [`devices/${DEVICE_ID}/meta/ownerUid`]: cred.user.uid,
    [`devices/${DEVICE_ID}/meta/pairedAt`]: Date.now(),
  });
  await update(ref(tvDb), {
    [`users/${cred.user.uid}/devices/${DEVICE_ID}/deviceId`]: DEVICE_ID,
    [`users/${cred.user.uid}/devices/${DEVICE_ID}/label`]: 'Smoke TV',
    [`users/${cred.user.uid}/devices/${DEVICE_ID}/online`]: true,
    [`users/${cred.user.uid}/devices/${DEVICE_ID}/pairedAt`]: Date.now(),
    [`users/${cred.user.uid}/devices/${DEVICE_ID}/lastSeen`]: Date.now(),
    [`pairRequests/${DEVICE_ID}/${requestId}/status`]: 'accepted',
    [`pairRequests/${DEVICE_ID}/${requestId}/respondedAt`]: Date.now(),
  });
  const meta = (await get(ref(tvDb, `devices/${DEVICE_ID}/meta`))).val();
  assert.equal(meta.ownerUid, cred.user.uid);
});

test('migration: seedControlV2 seeds control/v2 right after pairing', { timeout: 25_000 }, async () => {
  const policies = { 'com.example.app': { manualBlocked: false, dailyLimitMinutes: 30 } };
  await call(async (ok, fail) => repository.seedControlV2(DEVICE_ID, {
    policies,
    modes: [{ modeId: 'seed-1', name: 'Seed', appPolicies: {}, createdAt: 1, updatedAt: 1 }],
    activeMode: { modeId: null, modeName: null, activatedAt: null },
    safeMode: { enabled: false, until: 0, startedAt: null, startedBy: null },
    pin: (await get(ref(tvDb, `devices/${DEVICE_ID}/security/pin`))).val(),
  }, ok, fail));
  const control = (await get(ref(tvDb, `devices/${DEVICE_ID}/control/v2`))).val();
  assert.equal(control.schemaVersion, 2);
  assert.ok(control.apps[encode('com.example.app')]);
  assert.ok(control.modes['seed-1']);
  const desired = (await get(ref(tvDb, `devices/${DEVICE_ID}/sync/desired`))).val();
  assert.equal(desired.revisionId, control.revisionId);
  assert.equal(desired.kind, 'migration');
});

test('updatePolicy writes both trees + desired with matching revision', { timeout: 25_000 }, async () => {
  await call((ok, fail) => repository.updatePolicy(DEVICE_ID, 'com.example.app', { manualBlocked: true, dailyLimitMinutes: null }, ok, fail));
  const pkgKey = encode('com.example.app');
  const control = (await get(ref(tvDb, `devices/${DEVICE_ID}/control/v2`))).val();
  const desired = (await get(ref(tvDb, `devices/${DEVICE_ID}/sync/desired`))).val();
  const legacy = (await get(ref(tvDb, `devices/${DEVICE_ID}/policy/apps/${pkgKey}`))).val();
  assert.equal(control.apps[pkgKey].manualBlocked, true);
  assert.equal(desired.revisionId, control.revisionId);
  assert.equal(legacy.manualBlocked, true);

  await call((ok, fail) => repository.updatePolicy(DEVICE_ID, 'com.example.app', { manualBlocked: false, dailyLimitMinutes: 30 }, ok, fail));
  const updated = (await get(ref(tvDb, `devices/${DEVICE_ID}/control/v2/apps/${pkgKey}`))).val();
  assert.equal(updated.manualBlocked, false);
  assert.equal(updated.dailyLimitMinutes, 30);
});

test('rules deny undeclared fields ($other validate:false)', { timeout: 25_000 }, async () => {
  await assert.rejects(update(ref(database), {
    [`devices/${DEVICE_ID}/policy/apps/${encode('com.example.app')}/bogusField`]: 'x',
  }));
});

test('setPin writes a rules-valid PBKDF2 record to both trees', { timeout: 25_000 }, async () => {
  await call((ok, fail) => repository.setPin(DEVICE_ID, '123456', ok, fail));
  const pin = (await get(ref(tvDb, `devices/${DEVICE_ID}/security/pin`))).val();
  assert.match(pin.salt, /^[-_A-Za-z0-9]{22}$/);
  assert.match(pin.hash, /^[-_A-Za-z0-9]{43}$/);
  assert.equal(pin.version, 2);
  assert.equal(pin.algorithm, 'PBKDF2WithHmacSHA256');
  const controlPin = (await get(ref(tvDb, `devices/${DEVICE_ID}/control/v2/pin`))).val();
  assert.equal(controlPin.hash, pin.hash);
});

test('mode lifecycle: create, rename (with modeId heal), policy, activate, delete', { timeout: 25_000 }, async () => {
  await call((ok, fail) => repository.createMode(DEVICE_ID, 'Study', ok, fail));
  const modesNode = (await get(ref(tvDb, `devices/${DEVICE_ID}/policy/modes`))).val();
  const modeId = Object.keys(modesNode ?? {}).find((k) => modesNode[k]?.name === 'Study');
  assert.ok(modeId, 'created mode not found');

  await call((ok, fail) => repository.updateModeName(DEVICE_ID, modeId, 'Homework', ok, fail));
  const renamed = (await get(ref(tvDb, `devices/${DEVICE_ID}/control/v2/modes/${modeId}`))).val();
  assert.equal(renamed.name, 'Homework');

  await call((ok, fail) => repository.updateModePolicy(DEVICE_ID, modeId, 'com.example.app', { manualBlocked: true, dailyLimitMinutes: 15 }, ok, fail));
  const modeApp = (await get(ref(tvDb, `devices/${DEVICE_ID}/control/v2/modes/${modeId}/apps/${encode('com.example.app')}`))).val();
  assert.equal(modeApp.dailyLimitMinutes, 15);

  await call((ok, fail) => repository.setActiveMode(DEVICE_ID, { modeId, name: 'Homework' }, ok, fail));
  const activeMode = (await get(ref(tvDb, `devices/${DEVICE_ID}/control/v2/activeMode`))).val();
  assert.equal(activeMode.modeId, modeId);

  // Heal path: a legacy-only mode gets renamed; the leaf+modeId write must
  // materialize it in control/v2 instead of being denied.
  const legacyOnlyId = `legacy-${Math.random().toString(16).slice(2, 8)}`;
  await update(ref(database), {
    [`devices/${DEVICE_ID}/policy/modes/${legacyOnlyId}`]: {
      modeId: legacyOnlyId, name: 'Legacy', createdAt: Date.now(), updatedAt: Date.now(),
    },
  });
  await call((ok, fail) => repository.updateModeName(DEVICE_ID, legacyOnlyId, 'Legacy Renamed', ok, fail));
  const healed = (await get(ref(tvDb, `devices/${DEVICE_ID}/control/v2/modes/${legacyOnlyId}`))).val();
  assert.equal(healed.name, 'Legacy Renamed');
  assert.equal(healed.modeId, legacyOnlyId);

  await call((ok, fail) => repository.deleteMode(DEVICE_ID, modeId, modeId, ok, fail));
  const gone = (await get(ref(tvDb, `devices/${DEVICE_ID}/control/v2/modes/${modeId}`))).exists();
  assert.equal(gone, false);
  const activeGone = (await get(ref(tvDb, `devices/${DEVICE_ID}/control/v2/activeMode`))).exists();
  assert.equal(activeGone, false);
});

test('safe mode: start (rules window) and stop', { timeout: 25_000 }, async () => {
  await call((ok, fail) => repository.startSafeMode(DEVICE_ID, 15, ok, fail));
  const safeMode = (await get(ref(tvDb, `devices/${DEVICE_ID}/security/safeMode`))).val();
  assert.equal(safeMode.enabled, true);
  assert.ok(safeMode.until > Date.now());
  await call((ok, fail) => repository.stopSafeMode(DEVICE_ID, ok, fail));
  const stopped = (await get(ref(tvDb, `devices/${DEVICE_ID}/security/safeMode`))).val();
  assert.equal(stopped.enabled, false);
  assert.equal(stopped.until, 0);
});

test('commands: rescan/openSetup accepted by rules', { timeout: 25_000 }, async () => {
  await call((ok, fail) => repository.sendCommand(DEVICE_ID, 'rescanApps', null, ok, fail));
  await call((ok, fail) => repository.sendCommand(DEVICE_ID, 'openSetup', null, ok, fail));
  const commands = (await get(ref(tvDb, `devices/${DEVICE_ID}/commands`))).val();
  const types = Object.values(commands).map((c) => c.type);
  assert.ok(types.includes('rescanApps'));
  assert.ok(types.includes('openSetup'));
});

test('unlock request: TV creates pending, parent approves 15min', { timeout: 25_000 }, async () => {
  const requestRef = push(ref(tvDb, `devices/${DEVICE_ID}/unlockRequests`));
  const unlockId = requestRef.key;
  await set(requestRef, {
    requestId: unlockId,
    packageName: 'com.example.app',
    reason: 'manual',
    status: 'pending',
    createdAt: Date.now(),
    expiresAt: Date.now() + 600_000,
    ttlMs: 600_000,
  });
  await call((ok, fail) => repository.updateUnlock(
    DEVICE_ID, { requestId: unlockId }, 'approved', 'timed', 900_000,
    ok, fail,
  ));
  const unlock = (await get(ref(tvDb, `devices/${DEVICE_ID}/unlockRequests/${unlockId}`))).val();
  assert.equal(unlock.status, 'approved');
  assert.equal(unlock.approvalType, 'timed');
  assert.equal(unlock.approvalDurationMs, 900_000);
});


test('rules deny reusing the current control revisionId', { timeout: 25_000 }, async () => {
  const control = (await get(ref(tvDb, `devices/${DEVICE_ID}/control/v2`))).val();
  await assert.rejects(update(ref(database), {
    [`devices/${DEVICE_ID}/control/v2/revisionId`]: control.revisionId,
  }));
});

let observerRepo = null;

test('listeners: device list + control parse reach the observer', { timeout: 25_000 }, async () => {
  const seen = { devices: null, control: null };
  observerRepo = new ParentSyncRepository(database, {
    onApps: () => {}, onPolicies: () => {}, onModes: () => {}, onActiveMode: () => {},
    onSafeMode: () => {}, onPin: () => {}, onStates: () => {}, onSecurity: () => {},
    onUnlockRequests: () => {}, onTamperEvents: () => {}, onCommands: () => {},
    onActivityCurrent: () => {}, onActivityHistory: () => {}, onDesiredRevision: () => {},
    onAppliedRevision: () => {}, onSyncRuntime: () => {},
    onControlV2: (availability, value) => { if (availability === 'VALID') seen.control = value; },
    onError: () => {},
  });
  await fbAuth.signInWithEmailAndPassword(auth, PARENT_EMAIL, PARENT_PASSWORD);
  observerRepo.observeDevices(auth.currentUser.uid, (devices) => { seen.devices = devices; }, () => {});
  observerRepo.observeDevice(DEVICE_ID);
  await new Promise((resolve) => setTimeout(resolve, 2500));
  assert.ok(Array.isArray(seen.devices));
  assert.ok(seen.devices.some((d) => d.deviceId === DEVICE_ID));
  assert.ok(seen.control);
  assert.equal(seen.control.schemaVersion ?? 2, 2);
  assert.ok(seen.control.revisionId);
  observerRepo.close();
});

test('removePairedDevice deletes the mirror and sends unpair', { timeout: 25_000 }, async () => {
  const uidR = auth.currentUser.uid;
  console.log('R1 uid:', uidR, 'device:', DEVICE_ID);
  const mirrorBefore = await get(ref(database, `users/${uidR}/devices/${DEVICE_ID}`));
  console.log('R2 mirror exists before:', mirrorBefore.exists());
  await call((ok, fail) => {
    const wrappedFail = (m) => { console.log('R3 onError:', m); fail(m); };
    repository.removePairedDevice(DEVICE_ID, (...a) => { console.log('R4 onSuccess'); ok(...a); }, wrappedFail);
  });
  const mirror = await get(ref(database, `users/${uidR}/devices/${DEVICE_ID}`));
  assert.equal(mirror.exists(), false);
  const commands = (await get(ref(database, `devices/${DEVICE_ID}/commands`))).val();
  assert.ok(Object.values(commands ?? {}).some((c) => c.type === 'unpair'));
});

// node --test waits for the event loop; the RTDB websockets keep it alive
// unless both apps are deleted after the suite finishes.
after(async () => {
  try {
    syncRepository.close();
    const { deleteApp, getApp } = await import('firebase/app');
    await Promise.allSettled([deleteApp(getApp()), deleteApp(getApp('tv-app'))]);
  } catch { /* already cleaned */ }
});
