# AGENTS.md — Coding Agent Guide for GuardPulse TV

> Companion to `PROJECT_CONTEXT.md` (read its latest "Authoritative Handoff" section first) and `tv/AGENTS.md` (module-level guide for the TV agent). This file is the whole-repo entry point.

---

## 1. IDENTITY & SCOPE

* **Product:** GuardPulse TV — Android TV enforcement agent (`tv/`) + Android phone parent app (`parent/`) + shared Kotlin protocol/models (`shared/`).
* **Repo root:** `D:\UVM\PROJECTS\somthing1\guardpulse-tv`
* **Remote:** `https://github.com/rohan-js/guardpulse-tv` (public, branch `main`).
* **Independent of the laptop repo** (`D:\UVM\PROJECTS\somthing1\guardpulse-laptop`): separate Firebase projects, separate rules, separate version streams (TV `0.2.x`, parent `0.3.x`).

### Hard rules

1. Never commit: `firebase.local.properties`, `signing.local.properties`, `local.properties`, `.wrangler/`, `.zcode/`, `bin/`/`obj/` artifacts, debug logs.
2. Never deploy laptop-project Firebase rules to the TV project, or TV rules to the laptop project.
3. **Every user-visible string must be English** — hard user rule (4+ past incidents).
4. Never `adb install` or otherwise touch the TV device unless the user explicitly allows it — he installs builds himself.
5. No Firebase billing / paid-plan workarounds — the user refuses billing outright.
6. Verify file state over summaries — Read a target before editing it; notes have drifted from disk before.

---

## 2. TOOLCHAIN CHEAT-SHEET

| Task | Command |
|---|---|
| Build everything | `./gradlew.bat :tv:assembleDebug :parent:assembleDebug` |
| All unit tests | `./gradlew.bat test` (or per module `:tv:testDebugUnitTest`) |
| Ship check | `./gradlew.bat test :tv:assembleDebug :parent:assembleDebug` must be green |
| Rules syntax check | Python JSON parse of `firebase/database.rules.json` |
| Rules behavior tests | Pinned emulator flow (mirror the laptop repo: `npx -y firebase-tools@12 emulators:exec ...`; JDK 17 only) |
| Lint on changed Kotlin | `./gradlew.bat :tv:lint :parent:lint` when touching manifests/services |

**Path notes**

* Shell is Git Bash on Windows. Prefix Windows tools with `MSYS_NO_PATHCONV=1` when passing `/flags` (`adb`, `reg`, `schtasks`) or leading-slash args — MSYS otherwise mangles them into paths.
* JDK 17 at `C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\`. No JDK 21 on this machine.
* Android SDK via `local.properties` (`sdk.dir`). `firebase.local.properties` holds the TV project's web config — gitignored, never commit.

---

## 3. ARCHITECTURE QUICK REFERENCE

* **`tv/`** — foreground `TvSyncService` streams `devices/{id}` (SSE: `control/v2`, `sync/desired`, `commands`) and acks `sync/applied {revisionId,status,sessionId}`. `PairingManager` with one-deep rotation grace (`PairingGracePolicy`, 2× TTL). `AppMonitorAccessibilityService` = foreground app detection, overlay tracking, blocking. `UsageTracker` uploads per-app usage (UTC day keys). `tv/activity/*` = media pipeline (`MediaSessionListenerService` + `MediaSessionHub` + `MediaAccessibilityParser` + `MediaBrowserProbe` + `PlaybackAudioMonitor` → `ActivityStore`) powering the parent Activity tab.
* **`parent/`** — Compose phone app: `ParentSyncViewModel` + `ParentRepository` (RTDB listeners, single-flight queued control writes), Activity tab, DeviceCard banners, `ParentNotifications` (unlock/tamper/offline channels).
* **`shared/`** — `ControlProtocol` (parse/toFirebaseMap incl. `sessionLimitMinutes`), `FirebasePaths`, `PinHasher` (v1 SHA-256 + v2 PBKDF2 600k), `PolicyConstants`, `DeviceFreshness`.
* **Data flow contract:** parent writes `control/v2` + `sync/desired` atomically in ONE updateChildren with a fresh `revisionId`; the TV validates, persists, applies, acks `sync/applied`. Pairing = `pairRequests` → `meta.ownerUid` claim; unpair = command + both-side mirror deletes (rules-safe even when the TV is dead).

---

## 4. CURRENT STATE (2026-09-10)

* Head **`70436d4`** — "Full-codebase audit fixes (TV 0.2.8, parent 0.3.2); harden live rules". Working tree clean.
* Shipped line: `2d5c2af` (TV 0.2.6 + parent 0.3.0 — TV build INSTALLED + verified on the device) → `e2db520` (TV 0.2.7 + parent 0.3.1 — media title capture v2) → `76bf8e2` (pairing grace) → `d18a050` (session-limit parity, companion to laptop 0.2.35) → `70436d4` (audit round).
* Repo-root APKs `PARENT-0.3.0-debugsigned.apk` / `PARENT-0.3.1-debugsigned.apk` are staged for MANUAL install by the user.
* Rules hardened + deployed live (tvUid terminal deletes, heartbeat `stoppedBy:"parentPin"` arm, `packageKey` legacy mirrors).
* Cross-repo ops: kid laptop runs laptop-0.2.36 (see laptop repo PROJECT_CONTEXT §2026-09-10); the brother's Windows account is still Administrator — outside this repo's control but the reason tamper hardening matters.

---

## 5. TESTING CHECKLIST BEFORE ANY COMMIT

* [ ] `./gradlew.bat test` — all modules green
* [ ] `./gradlew.bat :tv:assembleDebug :parent:assembleDebug` — BUILD SUCCESSFUL
* [ ] If rules changed: JSON syntax check + rules test suite; deploy ONLY on explicit user go
* [ ] `git diff --stat` ≈ `git diff -w --stat` (no CRLF explosion from batch edits)
* [ ] `git status --porcelain` shows no `bin/`, `obj/`, logs, or local-properties files

---

## 6. KNOWN PITFALLS

1. **Old cwd path is dead**: the pre-move workspace under `D:\UVM\PROJECTS\somthing1\somthgn1\` is an empty husk — the repo lives at `somthing1\guardpulse-tv`.
2. Subagent/batch edits can flip LF↔CRLF — always verify with `git diff -w --stat`.
3. Python/heredoc Kotlin edits: use raw strings or escaped backslashes for regex.
4. JDK 21 absent — never invoke bare `firebase emulators:exec`.
5. The TV is a live family device — never push enforcement changes or commands to it without consent.
6. `parent/` and laptop-repo parent app share heritage but DIVERGED — do not copy-paste fixes across repos without re-reading both sides.

---

## 7. COMMIT MESSAGE STYLE

Short imperative subject; tag shipped versions as `(TV 0.2.x, parent 0.3.y)` in the subject. Examples from history:

```
Full-codebase audit fixes (TV 0.2.8, parent 0.3.2); harden live rules
Session-limit parity + parent robustness (companion to laptop 0.2.35)
Add rotation-boundary pairing grace (one-deep prev generation, 2x TTL)
Media title capture v2: content-desc titles, Nuvio/Stremio coverage (TV 0.2.7, parent 0.3.1)
```
