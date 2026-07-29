const fs = require("fs");
const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");

let testEnv;

beforeAll(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: "guardpulse-parental-control-test",
    database: {
      rules: fs.readFileSync("database.rules.json", "utf8"),
    },
  });
});

afterAll(async () => {
  await testEnv.cleanup();
});

beforeEach(async () => {
  await testEnv.clearDatabase();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.database();
    await db.ref("devices/tv1/meta").set({
      deviceId: "tv1",
      tvUid: "tvUid",
      ownerUid: "parentUid",
      label: "Mi TV",
      androidSdk: 28,
    });
    await db.ref("users/parentUid/devices/tv1").set({
      deviceId: "tv1",
      label: "Mi TV",
      pairedAt: 1,
    });
  });
});

function dbAs(uid) {
  return testEnv.authenticatedContext(uid).database();
}

test("paired parent can read only paired device", async () => {
  await assertSucceeds(dbAs("parentUid").ref("devices/tv1/apps").get());
  await assertFails(dbAs("otherParent").ref("devices/tv1/apps").get());
});

test("parent can write desired policy but not TV state", async () => {
  await assertSucceeds(
    dbAs("parentUid").ref("devices/tv1/policy/apps/Y29tLnZpZGVv").set({
      packageName: "com.video",
      manualBlocked: true,
      dailyLimitMinutes: 30,
      updatedAt: 1,
    })
  );

  await assertFails(
    dbAs("parentUid").ref("devices/tv1/state/apps/Y29tLnZpZGVv").set({
      packageName: "com.video",
      requestedSuspended: true,
      enforcementMode: "fallback",
      fallbackLocked: true,
      usageMinutesToday: 0,
    })
  );
});

test("parent can manage modes active mode and safe mode", async () => {
  await assertSucceeds(
    dbAs("parentUid").ref("devices/tv1/policy/modes/mode1").set({
      modeId: "mode1",
      name: "Study",
      createdAt: 1,
      updatedAt: 1,
      updatedBy: "parentUid",
      apps: {
        Y29tLnZpZGVv: {
          packageName: "com.video",
          manualBlocked: true,
          dailyLimitMinutes: 30,
          updatedAt: 1,
        },
      },
    })
  );

  await assertSucceeds(
    dbAs("parentUid").ref("devices/tv1/policy/activeMode").set({
      modeId: "mode1",
      modeName: "Study",
      activatedAt: 2,
      updatedBy: "parentUid",
    })
  );

  await assertSucceeds(
    dbAs("parentUid").ref("devices/tv1/security/safeMode").set({
      enabled: true,
      until: 1800003,
      startedAt: 3,
      startedBy: "parentUid",
    })
  );

  await assertSucceeds(
    dbAs("parentUid").ref("devices/tv1/security/safeMode").set({
      enabled: false,
      until: 0,
      updatedAt: 4,
      updatedBy: "parentUid",
    })
  );
});

test("TV and unpaired parents cannot write parent-only controls", async () => {
  await assertFails(
    dbAs("tvUid").ref("devices/tv1/policy/modes/mode1").set({
      modeId: "mode1",
      name: "Study",
    })
  );

  await assertFails(
    dbAs("otherParent").ref("devices/tv1/security/safeMode").set({
      enabled: true,
      until: 9999999999999,
    })
  );
});

test("TV can write runtime state but not desired policy", async () => {
  await assertSucceeds(
    dbAs("tvUid").ref("devices/tv1/security/runtime").update({
      enforcementMode: "fallback",
      accessibility: true,
      usageAccess: true,
      updatedAt: 1,
    })
  );

  await assertFails(
    dbAs("tvUid").ref("devices/tv1/policy/apps/Y29tLnZpZGVv").set({
      packageName: "com.video",
      manualBlocked: true,
    })
  );
});

test("parent creates command and TV updates command status", async () => {
  await assertSucceeds(
    dbAs("parentUid").ref("devices/tv1/commands/cmd1").set({
      type: "rescanApps",
      requestedBy: "parentUid",
      createdAt: 1,
      ttlMs: 300000,
      status: "pending",
    })
  );

  await assertSucceeds(
    dbAs("tvUid").ref("devices/tv1/commands/cmd1").update({
      status: "running",
      claimedAt: 2,
      sessionId: "session-1",
    })
  );

  await assertSucceeds(
    dbAs("tvUid").ref("devices/tv1/commands/cmd1").update({
      status: "done",
      completedAt: 3,
    })
  );

  await assertFails(
    dbAs("tvUid").ref("devices/tv1/commands/cmd1").update({
      status: "running",
      claimedAt: 4,
    })
  );
});

test("parent can create open setup command and unknown commands are rejected", async () => {
  await assertSucceeds(
    dbAs("parentUid").ref("devices/tv1/commands/cmdOpenSetup").set({
      type: "openSetup",
      requestedBy: "parentUid",
      createdAt: 1,
    })
  );

  await assertFails(
    dbAs("parentUid").ref("devices/tv1/commands/cmdUnknown").set({
      type: "openHiddenThing",
      requestedBy: "parentUid",
      createdAt: 1,
    })
  );
});

test("unpaired parent cannot approve unlock request", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.database();
    await db.ref("devices/tv1/unlockRequests/request1").set({
      requestId: "request1",
      packageName: "com.video",
      reason: "manual",
      status: "pending",
      createdAt: 1,
      expiresAt: 9999999999999,
    });
  });

  await assertFails(
    dbAs("otherParent").ref("devices/tv1/unlockRequests/request1").update({
      status: "approved",
      updatedAt: 2,
      updatedBy: "otherParent",
    })
  );
});

test("paired parent can choose unlock approval type and invalid duration is rejected", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.database();
    await db.ref("devices/tv1/unlockRequests/request1").set({
      requestId: "request1",
      packageName: "com.video",
      reason: "manual",
      status: "pending",
      createdAt: 1,
      expiresAt: 9999999999999,
    });
  });

  await assertSucceeds(
    dbAs("parentUid").ref("devices/tv1/unlockRequests/request1").update({
      status: "approved",
      approvalType: "timed",
      approvalDurationMs: 900000,
      updatedAt: 2,
      updatedBy: "parentUid",
    })
  );

  await assertFails(
    dbAs("parentUid").ref("devices/tv1/unlockRequests/request1").update({
      status: "approved",
      approvalType: "timed",
      approvalDurationMs: 120000,
      updatedAt: 3,
      updatedBy: "parentUid",
    })
  );
});

test("parent writes coherent V2 control and desired revision while TV cannot", async () => {
  const control = {
    schemaVersion: 2,
    revisionId: "revision-1",
    updatedAt: 10,
    updatedBy: "parentUid",
    apps: {
      Y29tLnZpZGVv: {
        packageName: "com.video",
        manualBlocked: true,
        dailyLimitMinutes: 30,
        updatedAt: 10,
      },
    },
    modes: {
      mode1: {
        modeId: "mode1",
        name: "Study",
        apps: {
          Y29tLnZpZGVv: {
            packageName: "com.video",
            manualBlocked: true,
          },
        },
      },
    },
    activeMode: { modeId: "mode1", modeName: "Study", activatedAt: 10 },
    safeMode: { enabled: false, until: 0 },
  };

  await assertSucceeds(
    dbAs("parentUid").ref("devices/tv1").update({
      "control/v2": control,
      "sync/desired": {
        revisionId: "revision-1",
        kind: "migration",
        target: "control",
        requestedAt: 10,
        requestedBy: "parentUid",
      },
    })
  );

  await assertFails(
    dbAs("tvUid").ref("devices/tv1/control/v2").set({
      ...control,
      revisionId: "revision-tv",
      updatedBy: "tvUid",
    })
  );
  await assertFails(
    dbAs("tvUid").ref("devices/tv1/sync/desired").set({
      revisionId: "revision-tv",
      kind: "migration",
      requestedAt: 11,
      requestedBy: "tvUid",
    })
  );
});

test("V2 rejects missing mode references and unsupported schemas", async () => {
  await assertFails(
    dbAs("parentUid").ref("devices/tv1/control/v2").set({
      schemaVersion: 2,
      revisionId: "bad-mode",
      updatedBy: "parentUid",
      activeMode: { modeId: "missing" },
      safeMode: { enabled: false, until: 0 },
    })
  );

  await assertFails(
    dbAs("parentUid").ref("devices/tv1/control/v2").set({
      schemaVersion: 3,
      revisionId: "future-schema",
      updatedBy: "parentUid",
      safeMode: { enabled: false, until: 0 },
    })
  );
});

test("TV writes acknowledgements runtime diagnostics and precise usage only", async () => {
  await assertSucceeds(
    dbAs("parentUid").ref("devices/tv1").update({
      "control/v2": {
        schemaVersion: 2,
        revisionId: "revision-1",
        updatedAt: 10,
        updatedBy: "parentUid",
        safeMode: { enabled: false, until: 0 },
      },
      "sync/desired": {
        revisionId: "revision-1",
        kind: "migration",
        requestedAt: 10,
        requestedBy: "parentUid",
      },
    })
  );
  await assertSucceeds(
    dbAs("tvUid").ref("devices/tv1/sync/runtime").set({
      connected: true,
      sessionId: "session-1",
      protocolVersion: 2,
      lastPolicyAppliedAt: 20,
      lastUsageWriteAt: 21,
      lastSuccessAt: 21,
    })
  );
  await assertSucceeds(
    dbAs("tvUid").ref("devices/tv1/sync/applied").set({
      revisionId: "revision-1",
      status: "applied",
      appliedAt: 20,
      sessionId: "session-1",
    })
  );
  await assertSucceeds(
    dbAs("tvUid").ref("devices/tv1/state/apps/Y29tLnZpZGVv").set({
      packageName: "com.video",
      requestedSuspended: false,
      enforcementMode: "fallback",
      fallbackLocked: false,
      usageMinutesToday: 1,
      usageMsToday: 65000,
      rawUsageMsToday: 65000,
      usageCapturedAt: 30,
      foregroundActive: true,
      foregroundStartedAt: 25,
      controlRevisionId: "revision-1",
      updatedAt: 30,
    })
  );

  await assertFails(
    dbAs("parentUid").ref("devices/tv1/sync/applied").set({
      revisionId: "revision-1",
      status: "applied",
      appliedAt: 20,
      sessionId: "parent-session",
    })
  );
  await assertFails(
    dbAs("tvUid").ref("devices/tv1/state/apps/Y29tLnZpZGVv").update({
      usageMsToday: -1,
    })
  );
  await assertFails(
    dbAs("tvUid").ref("devices/tv1/state/apps/Y29tLnZpZGVv").update({
      controlRevisionId: 123,
    })
  );
});

test("TV acknowledges approved unlock without gaining approval authority", async () => {
  await assertSucceeds(
    dbAs("tvUid").ref("devices/tv1/unlockRequests/requestAck").set({
      requestId: "requestAck",
      packageName: "com.video",
      reason: "manual",
      status: "pending",
      createdAt: 1,
      expiresAt: 9999999999999,
      ttlMs: 600000,
    })
  );

  await assertFails(
    dbAs("tvUid").ref("devices/tv1/unlockRequests/requestAck").update({
      status: "approved",
    })
  );

  await assertSucceeds(
    dbAs("parentUid").ref("devices/tv1/unlockRequests/requestAck").update({
      status: "approved",
      approvalType: "oneVisit",
      updatedAt: 2,
      updatedBy: "parentUid",
    })
  );

  await assertSucceeds(
    dbAs("tvUid").ref("devices/tv1/unlockRequests/requestAck").update({
      tvApplyStatus: "applied",
      tvAppliedAt: 3,
    })
  );
});

test("pair request exposes lifecycle only to its parent and TV", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.database().ref("devices/tv1/meta/ownerUid").remove();
  });
  await assertSucceeds(
    dbAs("parentUid").ref("pairRequests/tv1/pair1").set({
      parentUid: "parentUid",
      code: "123456",
      createdAt: 1,
      expiresAt: 300001,
      status: "pending",
    })
  );
  await assertSucceeds(dbAs("parentUid").ref("pairRequests/tv1/pair1").get());
  await assertFails(dbAs("otherParent").ref("pairRequests/tv1/pair1").get());
  await assertSucceeds(
    dbAs("tvUid").ref("pairRequests/tv1/pair1").update({
      status: "accepted",
      respondedAt: 2,
    })
  );
  await assertFails(
    dbAs("parentUid").ref("pairRequests/tv1/pair1").update({ status: "rejected" })
  );
});

test("TV cannot replace an existing owner or write another parent's device entry", async () => {
  await assertFails(
    dbAs("tvUid").ref("devices/tv1/meta/ownerUid").set("otherParent")
  );
  await assertFails(
    dbAs("tvUid").ref("users/otherParent/devices/tv1").set({
      deviceId: "tv1",
      label: "Mi TV",
    })
  );
  await assertSucceeds(
    dbAs("tvUid").ref().update({
      "devices/tv1/meta/ownerUid": null,
      "users/parentUid/devices/tv1": null,
    })
  );
  await assertSucceeds(
    dbAs("tvUid").ref("devices/tv1/meta/ownerUid").set("otherParent")
  );
});

test("new PIN records require valid PBKDF2 parameters while legacy hashes remain valid", async () => {
  await assertSucceeds(
    dbAs("parentUid").ref("devices/tv1/security/pin").set({
      salt: "abcdefghijklmnopqrstuv",
      hash: "abcdefghijklmnopqrstuvwxyzABCDEFGH123456789",
      version: 2,
      algorithm: "PBKDF2WithHmacSHA256",
      iterations: 210000,
      updatedAt: 1,
      updatedBy: "parentUid",
    })
  );
  await assertFails(
    dbAs("parentUid").ref("devices/tv1/security/pin").set({
      salt: "abcdefghijklmnopqrstuv",
      hash: "abcdefghijklmnopqrstuvwxyzABCDEFGH123456789",
      version: 2,
      algorithm: "SHA-256",
      iterations: 1,
      updatedAt: 2,
      updatedBy: "parentUid",
    })
  );
  await assertSucceeds(
    dbAs("parentUid").ref("devices/tv1/security/pin").set({
      salt: "abcdefghijklmnopqrstuv",
      hash: "abcdefghijklmnopqrstuvwxyzABCDEFGH123456789",
      updatedAt: 3,
      updatedBy: "parentUid",
    })
  );
});

test("paired parent can delete terminal history but not pending work", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.database();
    await db.ref("devices/tv1/commands/done").set({
      type: "rescanApps",
      status: "done",
      createdAt: 1,
    });
    await db.ref("devices/tv1/commands/pending").set({
      type: "rescanApps",
      status: "pending",
      createdAt: 2,
    });
    await db.ref("devices/tv1/unlockRequests/expired").set({
      requestId: "expired",
      packageName: "com.video",
      reason: "manual",
      status: "expired",
      createdAt: 1,
    });
    await db.ref("devices/tv1/tamperEvents/event1").set({
      type: "pinRetryLocked",
      createdAt: 1,
    });
  });

  await assertSucceeds(dbAs("parentUid").ref("devices/tv1/commands/done").remove());
  await assertFails(dbAs("parentUid").ref("devices/tv1/commands/pending").remove());
  await assertSucceeds(dbAs("parentUid").ref("devices/tv1/unlockRequests/expired").remove());
  await assertSucceeds(dbAs("parentUid").ref("devices/tv1/tamperEvents/event1").remove());
});

test("request identity fields are immutable after creation", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.database().ref("devices/tv1/unlockRequests/requestImmutable").set({
      requestId: "requestImmutable",
      packageName: "com.video",
      reason: "manual",
      status: "pending",
      createdAt: 1,
      expiresAt: 600001,
    });
  });
  await assertFails(
    dbAs("parentUid").ref("devices/tv1/unlockRequests/requestImmutable").update({
      packageName: "com.other",
      status: "approved",
      updatedAt: 2,
      updatedBy: "parentUid",
    })
  );
});
