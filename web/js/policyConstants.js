/**
 * Port of shared/PolicyConstants.kt — every constant the parent UI renders or
 * the write paths embed. Keep values string-identical to the Kotlin source.
 */

export const TV_PACKAGE = 'com.guardpulse.parentcontrol.tv';
export const PARENT_PACKAGE = 'com.guardpulse.parentcontrol.parent';

export const SETTINGS_APPS_PACKAGE = 'com.guardpulse.policy.settings_apps';
export const SETTINGS_DEVICE_PREFERENCES_PACKAGE = 'com.guardpulse.policy.settings_device_preferences';
export const SETTINGS_DEVELOPER_OPTIONS_PACKAGE = 'com.guardpulse.policy.settings_developer_options';
export const SETTINGS_SECURITY_RESTRICTIONS_PACKAGE = 'com.guardpulse.policy.settings_security_restrictions';
export const SETTINGS_ACCESSIBILITY_PACKAGE = 'com.guardpulse.policy.settings_accessibility';
export const SETTINGS_RESET_PACKAGE = 'com.guardpulse.policy.settings_reset';
export const DEPRECATED_SETTINGS_SECTIONS_PACKAGE = 'com.guardpulse.policy.settings_sections';

// (packageName, label, shortLabel, unlock key) — order drives parent UI lists.
export const SETTINGS_SECTION_POLICIES = [
  { packageName: SETTINGS_APPS_PACKAGE, label: 'Settings: Apps', shortLabel: 'Apps', key: 'settings-apps' },
  { packageName: SETTINGS_DEVICE_PREFERENCES_PACKAGE, label: 'Settings: Device Preferences', shortLabel: 'Device Preferences', key: 'settings-device-preferences' },
  { packageName: SETTINGS_DEVELOPER_OPTIONS_PACKAGE, label: 'Settings: Developer options', shortLabel: 'Developer options', key: 'settings-developer-options' },
  { packageName: SETTINGS_SECURITY_RESTRICTIONS_PACKAGE, label: 'Settings: Security & restrictions', shortLabel: 'Security & restrictions', key: 'settings-security-restrictions' },
  { packageName: SETTINGS_ACCESSIBILITY_PACKAGE, label: 'Settings: Accessibility', shortLabel: 'Accessibility', key: 'settings-accessibility' },
  { packageName: SETTINGS_RESET_PACKAGE, label: 'Settings: Reset', shortLabel: 'Reset', key: 'settings-reset' },
];

export const COMMAND_RESCAN_APPS = 'rescanApps';
export const COMMAND_RESET_TODAY = 'resetToday';
export const COMMAND_UNPAIR = 'unpair';
export const COMMAND_OPEN_SETUP = 'openSetup';

export const BLOCK_REASON_MANUAL = 'manual';
export const BLOCK_REASON_DAILY_LIMIT = 'dailyLimit';
export const BLOCK_REASON_RISKY_SETTINGS = 'riskySettings';
export const BLOCK_REASON_NETWORK_FILTER_MISSING = 'networkFilterMissing';
export const BLOCK_REASON_SOURCE_LOCK = 'sourceLock';
export const BLOCK_REASON_SETTINGS_SECTION = 'settingsSection';

export const ENFORCEMENT_DEVICE_OWNER = 'deviceOwner';
export const ENFORCEMENT_FALLBACK = 'fallback';
export const ENFORCEMENT_UNPROTECTED = 'unprotected';

export const UNLOCK_PENDING = 'pending';
export const UNLOCK_APPROVED = 'approved';
export const UNLOCK_DENIED = 'denied';
export const UNLOCK_EXPIRED = 'expired';
export const UNLOCK_APPROVAL_ONE_VISIT = 'oneVisit';
export const UNLOCK_APPROVAL_TIMED = 'timed';

export const SYNC_PROTOCOL_VERSION = 2;
export const SYNC_STATUS_APPLIED = 'applied';
export const SYNC_STATUS_FAILED = 'failed';
export const COMMAND_PENDING = 'pending';
export const COMMAND_RUNNING = 'running';
export const COMMAND_DONE = 'done';
export const COMMAND_FAILED = 'failed';
export const COMMAND_EXPIRED = 'expired';

export const PAIR_PENDING = 'pending';
export const PAIR_ACCEPTED = 'accepted';
export const PAIR_REJECTED = 'rejected';
export const PAIR_EXPIRED = 'expired';
export const PAIR_FAILED = 'failed';

export const REVISION_APP_POLICY = 'appPolicy';
export const REVISION_MODE_CREATE = 'modeCreate';
export const REVISION_MODE_UPDATE = 'modeUpdate';
export const REVISION_MODE_DELETE = 'modeDelete';
export const REVISION_MODE_POLICY = 'modePolicy';
export const REVISION_ACTIVE_MODE = 'activeMode';
export const REVISION_SAFE_MODE = 'safeMode';
export const REVISION_PIN = 'pin';
export const REVISION_MIGRATION = 'migration';
export const REVISION_KINDS = new Set([
  REVISION_APP_POLICY, REVISION_MODE_CREATE, REVISION_MODE_UPDATE, REVISION_MODE_DELETE,
  REVISION_MODE_POLICY, REVISION_ACTIVE_MODE, REVISION_SAFE_MODE, REVISION_PIN, REVISION_MIGRATION,
]);

export const TAMPER_ADMIN_DISABLE_REQUESTED = 'adminDisableRequested';
export const TAMPER_ADMIN_DISABLED = 'adminDisabled';
export const TAMPER_ACCESSIBILITY_DISABLED = 'accessibilityDisabled';
export const TAMPER_USAGE_ACCESS_MISSING = 'usageAccessMissing';
export const TAMPER_VPN_DISABLED = 'vpnDisabled';
export const TAMPER_RISKY_SETTINGS_OPENED = 'riskySettingsOpened';
export const TAMPER_PIN_RETRY_LOCKED = 'pinRetryLocked';

export const HEARTBEAT_INTERVAL_MS = 30_000;
export const FOREGROUND_USAGE_UPLOAD_INTERVAL_MS = 10_000;
export const FOREGROUND_USAGE_EXTRAPOLATION_MAX_MS = 20_000;
export const PAIRING_TTL_MS = 600_000;
export const TEMP_UNLOCK_MS = 600_000;
export const UNLOCK_15_MINUTES_MS = 900_000;
export const UNLOCK_30_MINUTES_MS = 1_800_000;
export const SAFE_MODE_DURATION_MS = 1_800_000;
export const TAMPER_EVENT_THROTTLE_MS = 900_000;
export const COMMAND_OPEN_SETUP_TTL_MS = 60_000;
export const COMMAND_STANDARD_TTL_MS = 300_000;
export const COMMAND_UNPAIR_TTL_MS = 600_000;

export function commandTtlMs(type) {
  if (type === COMMAND_OPEN_SETUP) return COMMAND_OPEN_SETUP_TTL_MS;
  if (type === COMMAND_UNPAIR) return COMMAND_UNPAIR_TTL_MS;
  return COMMAND_STANDARD_TTL_MS;
}

export const SOURCE_LOCK_PACKAGES = new Set(['com.android.tv']);
export const SOURCE_LOCK_RUNTIME_PACKAGES = new Set([...SOURCE_LOCK_PACKAGES, 'com.droidlogic.tvinput']);
export const PRIMARY_SETTINGS_PACKAGES = new Set(['com.android.tv.settings', 'com.android.settings']);
export const SETTINGS_SECTION_LOCK_PACKAGES = new Set(SETTINGS_SECTION_POLICIES.map((s) => s.packageName));
export const VIRTUAL_POLICY_PACKAGES = new Set(SETTINGS_SECTION_LOCK_PACKAGES);
export const DEPRECATED_VIRTUAL_POLICY_PACKAGES = new Set([DEPRECATED_SETTINGS_SECTIONS_PACKAGE]);
export const PARENT_VISIBLE_LOCK_PACKAGES = new Set([
  ...SOURCE_LOCK_PACKAGES, ...PRIMARY_SETTINGS_PACKAGES, ...SETTINGS_SECTION_LOCK_PACKAGES,
]);
export const DEFAULT_LOCKED_PACKAGES = new Set(SETTINGS_SECTION_LOCK_PACKAGES);

export const ALWAYS_PROTECTED_PACKAGES = new Set([
  TV_PACKAGE,
  'com.android.systemui',
  'com.android.settings',
  'com.android.tv.settings',
  'com.android.packageinstaller',
  'com.google.android.packageinstaller',
  'com.android.documentsui',
  'com.android.permissioncontroller',
  'com.google.android.permissioncontroller',
  'com.google.android.gms',
  'com.google.android.gsf',
  'com.google.android.tvlauncher',
  'com.google.android.apps.tv.launcherx',
]);

export const RISKY_SETTINGS_PACKAGES = new Set([]);

export function isDefaultLocked(packageName) {
  return DEFAULT_LOCKED_PACKAGES.has(packageName);
}

export function settingsSectionPolicy(packageName) {
  return SETTINGS_SECTION_POLICIES.find((s) => s.packageName === packageName) ?? null;
}

export function settingsSectionPolicyForKey(key) {
  return SETTINGS_SECTION_POLICIES.find((s) => s.key === key) ?? null;
}

export function sourceLockPolicyPackage(packageName) {
  return SOURCE_LOCK_RUNTIME_PACKAGES.has(packageName) ? 'com.android.tv' : null;
}
