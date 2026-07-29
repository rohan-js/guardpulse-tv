# GuardPulse Android TV Parental Control

<p align="center">
  <img src="docs/assets/guardpulse-logo.png" alt="GuardPulse Logo" width="400" />
</p>

<p align="center">
  <b>GuardPulse</b> is a Firebase-backed parental-control system for Android TV, with a parent phone dashboard, TV-side foreground PIN wall enforcement, daily limits, tamper alerts, and optional Device Owner hardening.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-TV-3DDC84?logo=android&logoColor=white" alt="Android TV" />
  <img src="https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Firebase-FFCA28?logo=firebase&logoColor=black" alt="Firebase" />
  <img src="https://img.shields.io/badge/Realtime%20Database-FFCA28?logo=firebase&logoColor=black" alt="Firebase Realtime Database" />
  <img src="https://img.shields.io/badge/Gradle-02303A?logo=gradle&logoColor=white" alt="Gradle" />
  <img src="https://img.shields.io/badge/Public%20Template-Safe%20Config-2B7A77" alt="Public template with safe config" />
</p>

---

## Quick Navigation

- [Introduction](#introduction)
- [Key Features](#key-features)
- [How It Works](#how-it-works)
- [Architecture](#architecture)
- [Reliability Model](#reliability-model)
- [Modules](#modules)
- [Firebase Setup](#firebase-setup)
- [Build Guide](#build-guide)
- [Private Release Build](#private-release-build)
- [Parent App Setup](#parent-app-setup)
- [TV Fallback Install](#tv-fallback-install)
- [Device Owner Provisioning](#device-owner-provisioning)
- [Remote Unlock + PIN Wall](#remote-unlock--pin-wall)
- [Firebase Paths](#firebase-paths)
- [Security Limits](#security-limits)
- [Project Info](#project-info)

---

# Introduction

**GuardPulse** is designed for Android TV environments where ordinary app blocking is not enough. Instead of relying on network blocking, the TV app watches the foreground app through Accessibility and immediately covers blocked apps with a full-screen parent PIN wall.

It supports two enforcement paths:

| Mode | Best For | Behavior |
| :--- | :--- | :--- |
| **Fallback Mode** | Real-world TVs where Device Owner is blocked by firmware | Uses Accessibility, Usage Access, Device Admin, Firebase sync, and a PIN wall |
| **Device Owner Mode** | Fresh/factory-reset TVs that allow enterprise provisioning | Adds stronger Android policy controls such as app suspension and restrictions |

> This public repository intentionally uses placeholder Firebase values. Do not commit live Firebase project IDs, API keys, device IDs, APKs, local backups, or operational notes.

---

## Key Features

| Feature | Description |
| :--- | :--- |
| **Foreground PIN Wall** | Blocked Android TV apps open at the system level, then are immediately covered by a PIN screen. |
| **Acknowledged Parent Dashboard** | Controls remain pending until the TV validates, stores, enforces, and acknowledges the matching V2 revision. |
| **One-Visit Unlocks** | Correct PIN or parent approval unlocks the current app visit only; leaving the app clears the unlock. |
| **Timed Parent Approvals** | Parent approvals can grant one visit, 15 minutes, or 30 minutes. |
| **One-Tap Modes and Safe Mode** | Named policy sets and a confirmed, time-bounded emergency pause are synchronized through the same control revision. |
| **Live TV Source Lock** | Handles HDMI/source apps such as Live TV through the same PIN-wall model. |
| **Settings Section Locks** | Protects dangerous Settings areas such as Apps, Accessibility, Security, Developer options, and Reset. |
| **Daily Limits** | Usage access tracks app time and turns reached limits into PIN-wall locks. |
| **Tamper Alerts** | Firebase tamper feed reports missing protection, admin disable attempts, and protected Settings access. |
| **Hidden TV Setup** | TV setup screen can be hidden from launcher and opened remotely from the parent app. |
| **Device Owner Option** | Provisioning scripts support stronger policy mode on compatible fresh TVs. |

---

## How It Works

GuardPulse uses Firebase as the coordination layer between the parent phone and the Android TV app.

1. **Pair TV** from the parent app using a QR payload or manual device ID/code.
2. **Parent app writes one atomic V2 control snapshot** with a unique revision and mirrors legacy paths for compatibility.
3. **TV sync actor validates and encrypts the snapshot locally**, then enforces it against the latest fresh foreground observation.
4. **Accessibility service detects foreground apps** and protected Settings sections and is the only component allowed to open `LockActivity`.
5. **TV writes an applied acknowledgement** only after policy state has been committed. Until then, the parent keeps showing the last confirmed value.
6. **PIN or parent approval grants one visit or a bounded timed unlock**, then returns to the underlying target app.

### Flow Summary

```text
Parent App
   |
   | control/v2 revision, commands, unlock approvals
   v
Firebase Realtime Database
   |
   | applied acknowledgement, state, usage, health, inventory
   v
Android TV App
   |
   | Accessibility foreground detection
   v
PIN Wall / Device Owner Enforcement
```

---

## Architecture

| Component | Role |
| :--- | :--- |
| **Parent App** | Signs in parents, pairs TVs, controls app locks, sets daily limits, approves unlock requests, and shows tamper events. |
| **TV App** | Runs the hidden setup, sync service, app inventory upload, PIN wall, fallback monitor, and optional Device Owner policies. |
| **Shared Module** | Owns versioned contracts, Firebase paths, package-key encoding, PIN hashing, freshness rules, and sync status reducers. |
| **Firebase Auth** | Email/password login for parents and anonymous auth for TV devices. |
| **Realtime Database** | Stores desired control, acknowledgements, runtime state, inventory, commands, unlock requests, and tamper events. |

### Reliability Model

- `/control/v2` is the desired policy authority after migration.
- `/sync/desired` identifies the parent revision the TV must apply.
- `/sync/applied` confirms the exact revision and TV session that enforced it.
- Parent controls show `Sending`, `Waiting for TV`, `Applied`, `Delayed`, `Offline - pending`, `Failed`, or `TV update required`.
- The TV keeps its last valid encrypted snapshot and PIN while Firebase is unavailable or a new snapshot is malformed.
- TV callbacks, retries, commands, foreground events, and writes are serialized through one actor so old asynchronous completions cannot replace newer state.
- Usage combines Android Usage Stats with a persistent foreground-session ledger and millisecond reset offsets.

See [Reliability Architecture](docs/reliability-architecture.md) for invariants, recovery behavior, pairing security, retention, and rollout details.

---

## Modules

| Module | Description |
| :--- | :--- |
| `:parent` | Android phone controller app built with Kotlin/Compose UI. |
| `:tv` | Android TV app with Accessibility fallback lock, hidden setup, sync, pairing, and Device Owner support. |
| `:shared` | Shared Kotlin code for Firebase contracts, constants, package keys, dates, and PIN hashing. |
| `firebase/` | Realtime Database rules and rules tests. |
| `scripts/` | PowerShell helpers for building, installing, and provisioning. |

---

## Firebase Setup

Use the Firebase Spark plan and enable:

- **Authentication**
  - Email/password for parent users
  - Anonymous auth for TV devices
- **Realtime Database**

Public debug builds use explicit placeholder `BuildConfig` values. Real values are read only by release builds from an ignored `firebase.local.properties` file in the repository root:

```properties
firebase.apiKey=YOUR_FIREBASE_API_KEY
firebase.projectId=your-firebase-project-id
firebase.databaseUrl=https://your-firebase-project-id-default-rtdb.firebaseio.com
parent.appId=YOUR_PARENT_FIREBASE_APP_ID
tv.appId=YOUR_TV_FIREBASE_APP_ID
```

Also replace the placeholder project in `.firebaserc` locally before deploying rules. Release builds fail rather than silently producing an APK when required Firebase values are absent.

Deploy database rules:

```powershell
firebase use your-firebase-project-id
firebase deploy --only database
```

Run rules tests:

```powershell
npm --prefix firebase install
firebase emulators:exec --only database "npm --prefix firebase test"
```

> Keep live Firebase config local. Do not commit real project IDs, API keys, service account files, device IDs, or app backups to a public repository.

---

## Build Guide

### Recommended Build

```powershell
.\scripts\build.ps1
```

### Direct Gradle Build

```powershell
.\gradlew.bat --no-daemon --console=plain :tv:assembleDebug :parent:assembleDebug
```

### Unit Tests

```powershell
.\gradlew.bat --no-daemon --console=plain test
```

Generated APKs:

| App | Output |
| :--- | :--- |
| Parent phone app | `parent/build/outputs/apk/debug/parent-debug.apk` |
| TV app | `tv/build/outputs/apk/debug/tv-debug.apk` |

CI runs unit tests, lint, public debug assemblies, and Firebase Realtime Database emulator tests under JDK 21. It never receives live Firebase or signing credentials.

## Private Release Build

GuardPulse `0.2.0` uses version code `2`, R8, resource shrinking, and a private signing certificate. Create an ignored `signing.local.properties`:

```properties
storeFile=C:/absolute/path/to/private.keystore
storePassword=LOCAL_ONLY
keyAlias=LOCAL_ONLY
keyPassword=LOCAL_ONLY
storeType=JKS
expectedSha256=EXPECTED_CERTIFICATE_SHA256
```

Build the matched release:

```powershell
.\gradlew.bat --no-daemon --console=plain :tv:assembleRelease :parent:assembleRelease
```

The build verifies the signing certificate SHA-256 before compilation. Use replace-only TV installation (`adb install -r`) so app data, pairing identity, Accessibility approval, and Usage Access remain intact. Never publish either local properties file, the keystore, APKs, operational context, or device identifiers.

---

## Parent App Setup

1. Build the parent APK.
2. Install it on the parent Android phone.
3. Sign in with Firebase email/password authentication.
4. Pair a TV using the QR payload or manual pairing details shown on the TV setup screen.
5. Set a 6-digit parent PIN in the Security tab.
6. Control app locks, daily limits, TV setup access, and unlock approvals from the dashboard.

Install helper:

```powershell
.\scripts\install-parent.ps1
```

---

## TV Fallback Install

Fallback mode is the practical path for TVs where Device Owner provisioning is unavailable.

```powershell
.\scripts\build.ps1
.\scripts\install-tv-fallback.ps1
```

Then complete these TV-side setup steps:

1. Enable the **Device Service** Accessibility service.
2. Grant Usage Access if the firmware requires manual confirmation.
3. Allow background/battery unrestricted operation.
4. Pair the TV with the parent app.
5. Confirm the parent app has a configured PIN.

### Fallback Enforcement

| Surface | Enforcement |
| :--- | :--- |
| Normal apps | Foreground PIN wall |
| Daily limit reached | Foreground PIN wall |
| Live TV / HDMI source app | Source-lock PIN wall |
| Whole Settings app | Parent-controlled row |
| Protected Settings sections | Separate one-visit section locks |
| TV setup screen | Hidden setup opened from parent command and gated by PIN |

---

## Device Owner Provisioning

Use Device Owner mode only on a fresh/factory-reset TV where Android allows enterprise provisioning:

```powershell
.\scripts\provision-tv.ps1
```

Device Owner mode can apply stronger Android policy controls, including app suspension, uninstall restrictions, Settings restrictions, safe-mode blocking, debugging restrictions, and unknown-source install restrictions where supported by firmware.

> Some Android TV firmware blocks Device Owner setup after the TV has already been configured. Use fallback mode when provisioning fails.

---

## Remote Unlock + PIN Wall

The lock screen supports two unlock paths:

| Unlock Path | Result |
| :--- | :--- |
| **Correct PIN** | Grants a one-visit unlock and returns to the target app. |
| **Ask Parent to Unlock** | Creates an immutable request; parent can approve one visit, 15 minutes, 30 minutes, or deny. |
| **Parent unblocks app** | The parent remains pending until the TV applies the V2 revision; only then does the visible wall dismiss. |
| **Daily limit reset** | The wall dismisses after the TV processes and confirms the reset command. |

One-visit app unlocks clear when the user leaves the unlocked app. Settings section unlocks clear when leaving that protected section or leaving Settings.

---

## Firebase Paths

| Path | Purpose |
| :--- | :--- |
| `/users/{uid}/devices` | Parent-visible paired TV list. |
| `/devices/{deviceId}/apps` | TV-uploaded app inventory. |
| `/devices/{deviceId}/control/v2` | Atomic desired control snapshot: apps, modes, Safe Mode, Settings locks, and PIN. |
| `/devices/{deviceId}/sync/desired` | Latest parent-requested revision. |
| `/devices/{deviceId}/sync/applied` | TV acknowledgement for an exact revision and session. |
| `/devices/{deviceId}/sync/runtime` | Connection, protocol, channel timestamps, and last failure diagnostics. |
| `/devices/{deviceId}/policy/*` | Legacy compatibility paths dual-written for one rollout release. |
| `/devices/{deviceId}/state/apps` | Runtime lock state and precise usage uploaded by TV. |
| `/devices/{deviceId}/security/pin` | Salted PIN hash metadata written by parent app. |
| `/devices/{deviceId}/security/runtime` | TV protection health, enforcement mode, and foreground status. |
| `/devices/{deviceId}/unlockRequests` | TV-created unlock requests approved/denied by parent. |
| `/devices/{deviceId}/tamperEvents` | Protection and risky-settings tamper events. |
| `/devices/{deviceId}/commands` | Parent-issued commands such as rescan, reset today, unpair, and open setup. |

Package names are stored as Firebase-safe encoded keys. Each app record also stores its original `packageName`.

Commands, unlock requests, and pair requests are retained for seven days after reaching a terminal state. Tamper events are retained for 30 days and bounded to the newest 200 records.

---

## Security Limits

GuardPulse fallback mode is designed for practical parental control on consumer Android TV firmware. It is not the same as a fully managed enterprise device.

| Risk | Notes |
| :--- | :--- |
| Accessibility disabled | The app can report and recover where possible, but Android still exposes system controls. |
| Device Admin disabled | Tamper events are uploaded when detected. |
| App uninstall attempts | Protected through available fallback controls, but not as strongly as Device Owner. |
| Recovery/factory reset | Cannot be prevented by a normal APK. |
| Root/firmware flashing | Out of scope for app-level protection. |
| Physical access attacks | Out of scope for software-only controls. |
| Offline or malformed policy | TV continues enforcing the last valid encrypted local V2 snapshot; the parent does not present an unacknowledged value as confirmed. |
| PIN database exposure | New PINs use PBKDF2-HMAC-SHA256 with 210,000 iterations, a random 16-byte salt, and a 32-byte hash. Legacy hashes remain verification-only until reset. |

For the strongest protection, use Device Owner mode on compatible hardware. For normal home TVs, fallback mode provides a practical PIN-wall and tamper-alert layer.

---

## Project Info

| Item | Value |
| :--- | :--- |
| Project | GuardPulse Android TV Parental Control |
| Primary language | Kotlin |
| Platform | Android phone + Android TV |
| Backend | Firebase Auth + Realtime Database |
| Current release | `0.2.0` (`versionCode 2`) |
| Repository mode | Public template with placeholder Firebase config |

Built for Android TV parental-control workflows where app access needs to be managed from a parent phone and enforced directly on the TV screen.
