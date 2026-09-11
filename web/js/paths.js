/**
 * Port of shared/FirebasePaths.kt — every path must stay string-identical
 * to the Android contract; the deployed RTDB rules are keyed on these shapes.
 */
import { encode as packageKey } from './packageKeys.js';

export const userDevices = (parentUid) => `users/${parentUid}/devices`;
export const userDevice = (parentUid, deviceId) => `users/${parentUid}/devices/${deviceId}`;
export const deviceRoot = (deviceId) => `devices/${deviceId}`;
export const deviceMeta = (deviceId) => `devices/${deviceId}/meta`;
export const deviceApps = (deviceId) => `devices/${deviceId}/apps`;
export const deviceApp = (deviceId, packageName) => `devices/${deviceId}/apps/${packageKey(packageName)}`;
export const devicePolicyApps = (deviceId) => `devices/${deviceId}/policy/apps`;
export const devicePolicyApp = (deviceId, packageName) =>
  `devices/${deviceId}/policy/apps/${packageKey(packageName)}`;
export const devicePolicyModes = (deviceId) => `devices/${deviceId}/policy/modes`;
export const devicePolicyMode = (deviceId, modeId) => `devices/${deviceId}/policy/modes/${modeId}`;
export const devicePolicyModeApp = (deviceId, modeId, packageName) =>
  `devices/${deviceId}/policy/modes/${modeId}/apps/${packageKey(packageName)}`;
export const devicePolicyActiveMode = (deviceId) => `devices/${deviceId}/policy/activeMode`;
export const deviceControlV2 = (deviceId) => `devices/${deviceId}/control/v2`;
export const deviceControlV2Apps = (deviceId) => `devices/${deviceId}/control/v2/apps`;
export const deviceControlV2App = (deviceId, packageName) =>
  `devices/${deviceId}/control/v2/apps/${packageKey(packageName)}`;
export const deviceControlV2Modes = (deviceId) => `devices/${deviceId}/control/v2/modes`;
export const deviceControlV2Mode = (deviceId, modeId) => `devices/${deviceId}/control/v2/modes/${modeId}`;
export const deviceControlV2ModeApp = (deviceId, modeId, packageName) =>
  `devices/${deviceId}/control/v2/modes/${modeId}/apps/${packageKey(packageName)}`;
export const deviceControlV2ActiveMode = (deviceId) => `devices/${deviceId}/control/v2/activeMode`;
export const deviceControlV2SafeMode = (deviceId) => `devices/${deviceId}/control/v2/safeMode`;
export const deviceControlV2Pin = (deviceId) => `devices/${deviceId}/control/v2/pin`;
export const deviceSync = (deviceId) => `devices/${deviceId}/sync`;
export const deviceSyncDesired = (deviceId) => `devices/${deviceId}/sync/desired`;
export const deviceSyncApplied = (deviceId) => `devices/${deviceId}/sync/applied`;
export const deviceSyncRuntime = (deviceId) => `devices/${deviceId}/sync/runtime`;
export const deviceStateApps = (deviceId) => `devices/${deviceId}/state/apps`;
export const deviceStateApp = (deviceId, packageName) =>
  `devices/${deviceId}/state/apps/${packageKey(packageName)}`;
export const deviceHeartbeat = (deviceId) => `devices/${deviceId}/heartbeat`;
export const deviceCommands = (deviceId) => `devices/${deviceId}/commands`;
export const deviceSecurity = (deviceId) => `devices/${deviceId}/security`;
export const deviceSecurityPin = (deviceId) => `devices/${deviceId}/security/pin`;
export const deviceSecurityRuntime = (deviceId) => `devices/${deviceId}/security/runtime`;
export const deviceSecuritySafeMode = (deviceId) => `devices/${deviceId}/security/safeMode`;
export const deviceTamperEvents = (deviceId) => `devices/${deviceId}/tamperEvents`;
export const deviceActivityCurrent = (deviceId) => `devices/${deviceId}/activity/current`;
export const deviceActivityHistory = (deviceId) => `devices/${deviceId}/activity/history`;
export const deviceUnlockRequests = (deviceId) => `devices/${deviceId}/unlockRequests`;
export const deviceUnlockRequest = (deviceId, requestId) =>
  `devices/${deviceId}/unlockRequests/${requestId}`;
export const pairRequests = (deviceId) => `pairRequests/${deviceId}`;
export const pairRequest = (deviceId, requestId) => `pairRequests/${deviceId}/${requestId}`;
