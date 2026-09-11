/**
 * Firebase bootstrap for the web dashboard. Only this module (and modules the
 * smoke test swaps via import maps) touch the SDK singletons.
 *
 * Config resolution order:
 *   1. globalThis.GUARDPULSE_CONFIG injected by js/config.local.js (gitignored,
 *      real values for the hosted deployment) — a plain <script> sets it before
 *      this module loads. In the emulator smoke test, Node code sets
 *      globalThis.GUARDPULSE_CONFIG before importing this module.
 *   2. js/config.example.js placeholders (committed; app renders the
 *      "Firebase not configured" screen, mirroring the phone app)
 */
import { initializeApp } from 'firebase/app';
import { getAuth } from 'firebase/auth';
import { getDatabase } from 'firebase/database';

const app = initializeApp(globalThis.GUARDPULSE_CONFIG);

export const auth = getAuth(app);
export const database = getDatabase(app);

/**
 * Port of shared/FirebaseServerClock.kt. `now()` = device time + server
 * offset; `offsetFresh()` gates safe-mode starts exactly like the phone app
 * (the rules compare `until` against ServerValue.TIMESTAMP at commit).
 */
class ServerClock {
  constructor(database) {
    this.offsetMs = 0;
    this.offsetReceived = false;
    this.started = false;
    this.unsubscribe = null;
    this.database = database;
  }

  start() {
    if (this.started) return;
    this.started = true;
    // Lazy import avoids a cycle: firebase-database is loaded by the shell.
    import('firebase/database').then(({ ref, onValue }) => {
      this.unsubscribe = onValue(ref(this.database, '.info/serverTimeOffset'), (snap) => {
        this.offsetMs = typeof snap.val() === 'number' ? snap.val() : 0;
        this.offsetReceived = true;
      });
    });
  }

  stop() {
    if (this.unsubscribe) this.unsubscribe();
    this.unsubscribe = null;
    this.started = false;
  }

  now() {
    return Date.now() + this.offsetMs;
  }

  offsetMillis() {
    return this.offsetMs;
  }

  offsetFresh() {
    return this.offsetReceived;
  }
}

export const serverClock = new ServerClock(database);

export function isPlaceholderConfig(config) {
  const values = Object.values(config ?? {});
  return values.some((v) => typeof v === 'string' && (
    v.trim() === '' || v.startsWith('replace_') || v.startsWith('your_') ||
    v.includes('your-firebase-project') || v.includes('example.invalid')
  ));
}
