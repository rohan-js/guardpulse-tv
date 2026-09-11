// Committed placeholder Firebase web config — NEVER put live values here.
// Real values live in js/config.local.js (gitignored); create it from this
// file using the values from the Firebase console (Project settings -> Your
// apps -> Web app) or `firebase apps:sdkconfig WEB <appId>`.
// The guard lets config.local.js (loaded first) win.
if (!window.GUARDPULSE_CONFIG) {
  window.GUARDPULSE_CONFIG = {
    apiKey: 'YOUR_FIREBASE_API_KEY',
    authDomain: 'your-firebase-project.firebaseapp.com',
    databaseURL: 'https://your-firebase-project-default-rtdb.firebaseio.com',
    projectId: 'your-firebase-project',
    appId: 'YOUR_WEB_FIREBASE_APP_ID',
  };
}
