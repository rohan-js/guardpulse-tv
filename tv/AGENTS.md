# AGENTS.md — GuardPulse TV module (`tv/`, plus `shared/`)

Operating guide for any AI agent or developer working on the Android TV app of
GuardPulse. Read this before changing anything in `tv/` or `shared/`. For full
project history and the superseded handoffs, read `../PROJECT_CONTEXT.md`
(2026-09-03 section is authoritative).

## What this app is

`com.guardpulse.parentcontrol.tv` (module `tv/`, namespace
`com.guardpulse.parentcontrol.tv`) — a parental-control enforcement agent that
runs permanently on an Android TV (Xiaomi MiTV4I, Android 9, SDK 28, NOT device
owner → "fallback mode"). It receives control data from a parent phone app via
Firebase RTDB (`control/v2` desired state), enforces it locally through an
accessibility service that launches a PIN-gated lock wall over blocked apps,
acks applied revisions, and uploads telemetry (state, usage, activity,
heartbeat, security probes). Module `shared/` holds the protocol contracts
(`ControlProtocol`, `FirebasePaths`, `PolicyConstants`, `PinHasher`, `DateKeys`,
`PackageKeys`) used by both this app and the parent phone app — changes there
affect BOTH clients and possibly the deployed Firebase rules.

## Current shipped state

- TV: **0.2.7 (versionCode 9)**, commit `e2db520`, INSTALLED and live-verified
  on the TV at `192.168.1.9:5555` (device id `38763e9b-521b-4414-90ba-ef7bb155d58d`).
- Firebase project `rithik-parental-control`; rules in `firebase/` are DEPLOYED
  and byte-accurate to what the live DB enforces.
- 45 TV unit tests green (`./gradlew :tv:testDebugUnitTest`).
- Full handoff with release-by-release details: `../PROJECT_CONTEXT.md`,
  section "Authoritative Continuation Handoff (2026-09-03)".

## Hard rules (violating these has caused real incidents)

1. **Never bump into a stale signing identity.** The installed TV app and the
   parent phone app are BOTH signed with the ANDROID DEBUG KEYSTORE
   (`tv-install-backups/debug.keystore`, passwords `android`), not the repo
   release keystore. Build `assembleRelease`, then zipalign, then
   `apksigner sign --ks tv-install-backups/debug.keystore --ks-pass
   pass:android --key-pass pass:android`, then `adb install -r`.
   A release-signed APK cannot update the device.
2. **Every Firebase write must match the deployed rules.** The rules use
   `"$other": {".validate": false}` everywhere. Writing ANY field not declared
   for that node (e.g. `startedAt` instead of `claimedAt` in command claims —
   this silently killed the whole remote-command channel once) fails the whole
   write with "Permission denied". When adding a field: rules JSON + deploy +
   both clients in the same change-set. Validate the JSON
   (`python -c "import json;json.load(open('firebase/database.rules.json'))"`)
   and deploy: `firebase deploy --only database --project rithik-parental-control`.
3. **Never clear app data or uninstall on the TV** unless the user explicitly
   accepts losing pairing, PIN, and accessibility grants. Update with
   `adb install -r` only.
4. **Stale policy caches were a shipped bug once** (0.2.3). `LocalPolicyStore`
   uses a process-wide companion cache with synchronous write-through — if you
   add another write path to `local_policy` prefs, keep the cache coherent in
   the SAME call stack (the prefs change-listener is async and was the root
   cause).
5. **Anything that writes `state/apps/<pkg>` must write the FULL field set** —
   partial leaf writes create nodes the rules reject. This is why
   `uploadForegroundUsage` is gated to inventory packages.
6. **Ack semantics:** `sync/applied` requires revisionId == sync/desired AND ==
   control/v2 AND sessionId == sync/runtime/sessionId. Guard every ack path.
7. **Multi-path atomicity cuts both ways:** one bad child denies the whole
   `updateChildren`. When adding channels, follow the existing
   `recordSyncError(channel)` / `markChannelSynced(channel)` pattern.
8. Do not run disruptive ADB actions (reboot, force-stop) while the TV is in
   use without asking. `force-stop` puts the app in STOPPED state — broadcasts
   (including BOOT_COMPLETED) stop until the app is started once.

## Architecture map (who does what)

- `tv/sync/TvSyncService.kt` (~1700 LOC, the core): foreground service; owns
  Firebase listeners, the control-apply pipeline (`applyV2Control` →
  `effectivePolicies()` → `saveEffectivePolicies()` → `applyPoliciesAndUpload`),
  state/usage/heartbeat/inventory/activity uploads, command handling, remote
  unlock application, security probes (5-min cache), SystemTimeGuard offset
  refresh. Retry/backoff lives in `TvSyncEngine` (serialized Main.immediate
  event loop; per-event runCatching; exponential backoff 5s→10min).
- `tv/sync/EffectivePolicies.kt`: PURE merge (unit-tested) — base policies,
  overlaid by active-mode entries, defaults putIfAbsent. If you touch policy
  semantics, change it here and keep TvSyncService thin.
- `tv/policy/LocalPolicyStore.kt`: SharedPreferences `local_policy` + the
  process-wide policy cache (see hard rule 4). Day keys are UTC on
  SystemTimeGuard time.
- `tv/fallback/AppMonitorAccessibilityService.kt`: the enforcer. Evaluates
  every window event → `SettingsSectionDetector` (section locks) →
  `FallbackProtection.shouldLock` → `LockLaunchGuard` (1.5s dedupe) → opens
  `LockActivity`. Also feeds `TvActivityTracker` (media titles) and live usage
  sessions. Poll safety-net every 1s; 300ms settle recheck after
  TYPE_WINDOW_STATE_CHANGED.
- `tv/fallback/LockActivity.kt`: PIN wall. Binds remote unlock listeners on
  EVERY bind (onCreate + onNewIntent); auto-dismiss poll (750ms) honors
  unlocks; BACK swallowed; singleTask.
- `tv/fallback/FallbackStateStore.kt`: unlock grants (temp/app-visit/
  per-section), PIN record (Keystore via SecureValueStore, fail-closed on
  corruption), admin-disable gate, safe mode (server-time hardened).
- `tv/activity/`: media-title capture. `MediaAccessibilityParser` (pure, the
  title-selection heuristics), `TvActivityTracker` (snapshot cache + SQLite
  history v3), `MediaTitlePolicy` (evidence-based walk gate), `MediaBrowserProbe`
  (binds apps' MediaBrowserServices), `MediaSessionListenerService` +
  `MediaSessionHub` (system sessions — INERT on the current TV, see blockers),
  `PlaybackAudioMonitor`. Upload channel lives in TvSyncService
  (`uploadActivityTelemetry`).
- `tv/system/SystemTimeGuard.kt`: monotonic clock floor + server offset. ALL
  new deadline logic must use it, not `System.currentTimeMillis()`.
- `tv/pairing/PairingManager.kt`: pairing code/secret (constant-time compare,
  20-strike rotation), cached parent UID.
- `tv/network/NetworkFilterController.kt`: intentional no-op stub (VPN feature
  not implemented on TV) — keep the "disabled" status write-once semantics.
- `shared/`: contracts shared with the parent app. `PolicyConstants` section
  lists drive BOTH the TV detector and the parent phone's card rendering —
  adding a virtual section there makes it appear on the phone automatically
  after the next inventory rescan.

## Testing

- `./gradlew :tv:testDebugUnitTest` — 45 tests. Pure-JVM only (no Robolectric);
  keep new logic in pure objects (see EffectivePolicies, MediaTitlePolicy,
  MediaAccessibilityParser, TvStateDiff, ApprovedUnlockPolicy) and test there.
- Rules tests (`firebase/database.rules.test.js`) need the Firebase emulator +
  JDK 21 (NOT available on this machine) — validate rules JSON syntax with
  Python and pattern-copy existing clauses instead.
- Before shipping: run tests, assembleRelease, debug-sign, `adb install -r`,
  then verify against LIVE Firebase (`firebase database:get` with
  `MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"` from Git Bash; `database:update`
  needs `-f`). Suggested live checks: `sync/applied` matches `sync/desired`,
  `security/runtime/lastSyncError` null, `state/apps` locks flip after a
  toggle, `activity/current` updates.

## On-device debugging notes

- App label in accessibility dumps: "Device Support Service" (that's ours).
- `am start -n com.guardpulse.parentcontrol.tv/.MainActivity` opens the PIN-
  gated setup screen (launcher entry exists since 0.2.7; monkey does NOT work).
- If the lock wall is up and you need in: create + approve an unlockRequest in
  RTDB (status pending → approved with approvalType oneVisit + updatedAt) for
  the locked package; TvSyncService applies it even without LockActivity.
- Secure settings sometimes need re-injection after reboots:
  `enabled_accessibility_services` +
  `accessibility_enabled=1` (restore commands in PROJECT_CONTEXT.md).
- `uiautomator dump` often returns "null root node" while our service is
  bound; try repeatedly after key events, dump to a RELATIVE path, and pull
  with `adb pull data/local/tmp/x.xml`.

## Known limitations (do not "fix" without understanding)

- Notification access (media sessions) is ungrantable on this TV ROM —
  `MediaSessionListenerService` stays dormant; titles come from the
  screen-reader route. YouTube fullscreen has an empty a11y tree → no titles
  there; this is a platform limitation, not a bug.
- `networkBlocked`/`vpnApplied`/`vpnActive` state fields are always false
  (the VPN feature doesn't exist on TV; schema kept for phone compatibility).
- `lastValidV2Snapshot` was removed (write-only dead code) — don't re-add
  snapshot persistence without a consumer.
