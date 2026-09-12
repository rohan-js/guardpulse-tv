# GuardPulse Web Dashboard

A zero-build, feature-complete web port of the GuardPulse parent phone app,
hosted free on Firebase Hosting (Spark tier) inside the existing
`<your-firebase-project>` project. Same Firebase Auth (parent email/password),
same Realtime Database rules — **no backend code, no billing**.

## What it replicates

- Sign in / create account / sign out
- Devices tab: paired-TV cards, select/remove, pairing via QR-paste, manual
  device ID + 6-digit code, and camera QR scan where the browser supports
  `BarcodeDetector`
- Apps tab: full app-policy cards (block toggles, daily limits, reset-today,
  "Waiting for TV" gating, usage strips with the 20s live extrapolation)
- Security tab: protection health, the full sync-status matrix, malformed-
  control repair, Emergency Safe Mode (15/30/60/120 + custom), One-Tap Modes
  (create/rename/delete/activate + per-mode rules), TV setup access, parent
  PIN (PBKDF2, byte-identical to the Android hasher), pending unlock requests
  (deny / one visit / 15 / 30 minutes), approved-waiting section
- Activity tab: Now Watching (with playback progress extrapolation and lock
  overlay state), 7-day picker, timeline, per-app history
- Events tab: tamper feed with critical highlighting
- Retention cleanup (7 days after terminal state; tamper 30 days / newest 200)
  and control migration/repair — identical semantics to the phone app

## Layout

```
web/
  index.html          import map -> gstatic Firebase SDK v10.14.1
  styles.css
  js/
    config.example.js committed placeholder config
    config.local.js   REAL web-app config (gitignored; created once)
    firebase.js       app init + server clock (offset-aware)
    paths.js / packageKeys.js / policyConstants.js / dateKeys.js
    pinHasher.js      WebCrypto PBKDF2 port (golden-vector tested)
    controlProtocol.js parse/parseDesired/freshness port
    repository.js     all writes (single-flight queue, exact rules field maps)
                      + all listeners (port of ParentRepository/ParentSyncRepository)
    store.js          ParentSyncViewModel port (gating, migration, pairing)
    reducers.js       deriveSyncStatus, usage/timeline helpers
    retention.js      retention cleaner port
    ui/               shell + tab views
  tests/              node --test unit suite + emulator rules smoke test
```

## Local development

```
cd web
npm install          # only needed for tests
npm test             # unit tests (pure ports, no emulator)
python -m http.server 8080   # or any static server, from web/
```

Copy `js/config.example.js` to `js/config.local.js` and fill in the Firebase
**web app** config (`firebase apps:sdkconfig WEB <appId>`). The app shows the
"Firebase not configured" screen until real values are present.

## Rules smoke test (writes vs the deployed rules, no live data)

```
# JDK 17 required (database emulator)
JAVA_HOME="<jdk17>" npx -y firebase-tools@12 emulators:exec \
  --only database,auth --project guardpulse-parental-control-test \
  "npm --prefix web run test:smoke"
```

The smoke test signs up a fake parent, simulates the TV with a second
anonymous-auth app, and exercises every write path (policy, PIN, modes incl.
the modeId-heal rename, safe mode, commands, unlock approval, migration,
device removal) against the real rules file, plus negative cases (undeclared
fields and revision reuse must be denied).

## Deploy

```
firebase deploy --only hosting --project <your-firebase-project>
```

`firebase.json` already contains the hosting block (`public: web`). The
deployed site serves `js/config.local.js` — the web config is public by
design (Firebase web API keys are not secrets; all data access is gated by
the RTDB rules through parent auth).
