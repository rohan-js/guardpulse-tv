/**
 * Port of ParentAppsFeature.kt — app policy cards with the exact status-label
 * and reason-line matrices, pending gating, usage strip, and the expanded
 * policy panel (daily limit save/clear, reset-today, parent/runtime chips).
 */
import {
  h, card, emptyPanel, statusLabel, switchToggle, formatUsage,
} from './components.js';
import { rescanApps, updatePolicy, resetToday, store } from '../store.js';
import {
  SOURCE_LOCK_PACKAGES, PRIMARY_SETTINGS_PACKAGES, settingsSectionPolicy,
  DEPRECATED_VIRTUAL_POLICY_PACKAGES, ENFORCEMENT_UNPROTECTED,
} from '../policyConstants.js';
import { effectiveUsageMs, defaultParentPolicy } from '../reducers.js';
import { confirmDialog } from '../main.js';

let searchQuery = '';
const expandedApps = new Set();

export function renderAppsTab(state) {
  const device = state.devices.find((d) => d.deviceId === state.selectedDeviceId);
  const header = h('div', { class: 'row' },
    h('div', { class: 'grow' },
      h('div', { class: 'section-label' }, 'Managing Device'),
      h('div', { class: 'row', style: { marginTop: '6px' } },
        h('div', { class: 'icon-tile' }, '📺'),
        h('span', { style: { fontWeight: 700 } }, device?.label ?? state.selectedDeviceId ?? 'No TV selected'),
      ),
    ),
    h('button', { class: 'btn pillbtn', onClick: () => rescanApps() }, 'Rescan'),
  );

  return h('div', { style: { display: 'flex', flexDirection: 'column', gap: '14px' } },
    header,
    body(state),
  );
}

function body(state) {
  if (!state.selectedDeviceId) {
    return emptyPanel('No TV selected', 'Select or pair a TV before managing apps.');
  }
  if (state.loadingDeviceDetails && Object.keys(state.apps).length === 0) {
    return emptyPanel('Loading apps', 'Waiting for the TV to upload its app list.');
  }
  const apps = Object.values(state.apps)
    .filter((app) => !DEPRECATED_VIRTUAL_POLICY_PACKAGES.has(app.packageName));
  if (apps.length === 0) {
    return emptyPanel('No apps yet', 'Start Sync Service or Rescan Installed Apps on the TV.');
  }

  const search = h('input', {
    type: 'search', placeholder: 'Search apps...', value: searchQuery,
    'data-persist-key': 'apps-search',
  });
  search.addEventListener('input', () => {
    searchQuery = search.value;
    fillList();
  });

  const listHolder = h('div', { id: 'apps-list', class: 'card', style: { gap: '6px' } });
  appsHolder = listHolder;
  fillList();
  return h('div', { style: { display: 'flex', flexDirection: 'column', gap: '12px' } },
    h('div', { class: 'field' }, search),
    listHolder,
  );
}

let appsHolder = null;

function fillList() {
  if (!appsHolder) return;
  const state = store.get();
  const apps = Object.values(state.apps)
    .filter((app) => !DEPRECATED_VIRTUAL_POLICY_PACKAGES.has(app.packageName));
  appsHolder.innerHTML = '';
  const query = searchQuery.toLowerCase();
  const filtered = apps
    .filter((app) => app.label.toLowerCase().includes(query) || app.packageName.toLowerCase().includes(query))
    .sort((a, b) => a.label.toLowerCase().localeCompare(b.label.toLowerCase()));
  if (filtered.length === 0) {
    appsHolder.append(h('div', { class: 'small muted', style: { padding: '10px 4px' } }, 'No apps match the search.'));
    return;
  }
  filtered.forEach((app) => appsHolder.append(appPolicyCard(state, app)));
}

function rerender() {
  window.dispatchEvent(new CustomEvent('guardpulse:rerender'));
}

export function appPolicyCard(state, app) {
  const pkg = app.packageName;
  const sourceApp = SOURCE_LOCK_PACKAGES.has(pkg);
  const settingsApp = PRIMARY_SETTINGS_PACKAGES.has(pkg);
  const section = settingsSectionPolicy(pkg);
  const sectionName = section?.shortLabel ?? 'Settings section';

  const confirmed = state.confirmedControl;
  const liveState = state.states[pkg] ?? {};
  const st = confirmed ? confirmed.confirmedStates[pkg] ?? liveState : liveState;
  const runtimeConfirmed = st.controlRevisionId != null;

  const policy = confirmed
    ? confirmed.policies[pkg] ?? state.policies[pkg] ?? defaultParentPolicy(pkg)
    : state.policies[pkg] ?? defaultParentPolicy(pkg);

  const lockBlocked = runtimeConfirmed
    ? (st.lockBlocked === true || (!app.blockable && st.fallbackLocked === true))
    : ((app.blockable && (policy.manualBlocked || st.manualBlocked === true || st.dailyLimitBlocked === true))
      || (!app.blockable && st.fallbackLocked === true));

  const sourceLocked = sourceApp && lockBlocked;
  const settingsLocked = settingsApp && lockBlocked;
  const settingsSectionsLocked = section != null && lockBlocked;

  const pending = appWaitingForTv(state, pkg);
  const requestedPolicy = state.desiredControl?.apps?.[pkg];

  const statusText = pending ? 'Waiting for TV'
    : !app.blockable ? 'Protected'
      : sourceLocked ? 'Live TV locked'
        : settingsSectionsLocked ? `${sectionName} locked`
          : settingsLocked ? 'Settings locked'
            : sourceApp ? 'Live TV allowed'
              : section != null ? `${sectionName} allowed`
                : settingsApp ? 'Settings allowed'
                  : lockBlocked ? 'App locked'
                    : st.dailyLimitBlocked ? 'Daily limit lock'
                      : 'App allowed';
  const statusColor = pending ? 'var(--action-blue)'
    : !app.blockable ? 'var(--text-muted)'
      : lockBlocked || st.dailyLimitBlocked ? 'var(--alert-red)' : 'var(--action-blue)';

  const expanded = expandedApps.has(pkg);
  const usageMs = effectiveUsageMs(st, state.serverNow);

  const cardChildren = [
    h('div', { class: 'accent', style: { background: statusColor } }),
    h('div', {
      class: 'head',
      onClick: (event) => {
        if (event.target.closest('.switch') || event.target.closest('button')) return;
        if (expandedApps.has(pkg)) expandedApps.delete(pkg); else expandedApps.add(pkg);
        rerender();
      },
    },
      h('div', { class: `icon-tile ${!app.blockable || lockBlocked ? 'locked' : ''}` }, !app.blockable ? '🔒' : '📺'),
      h('div', { class: 'grow' },
        h('div', { style: { fontWeight: 700 } }, app.label),
        h('div', { class: 'small muted ellipsis' }, pkg),
        h('div', { style: { marginTop: '5px' } }, statusLabel(statusText, statusColor)),
        reasonLine({
          pending, requestedPolicy, app, sourceLocked, st, settingsSectionsLocked,
          settingsLocked, lockBlocked, sectionName, policy,
        }),
      ),
      switchToggle(!policy.manualBlocked, app.blockable === false || pending, (allowed) => {
        updatePolicy(pkg, { ...policy, manualBlocked: !allowed });
      }),
    ),
  ];

  if (usageMs > 0 || st.dailyLimitBlocked) cardChildren.push(usageStrip(usageMs, st.dailyLimitBlocked === true, pkg));
  if (expanded) cardChildren.push(expandedPanel(state, pkg, app, policy, st, pending, sectionName, sourceApp, settingsApp, section));

  return h('div', { class: 'card app-card', style: { padding: '14px 16px 14px 21px', position: 'relative' } }, ...cardChildren);
}

/** ParentModels.kt isAppPolicyWaitingForTv (290-302) + isAppPolicyPending (275-282). */
function appWaitingForTv(state, pkg) {
  const st = state.states[pkg] ?? {};
  const desiredRule = state.desiredControl?.apps?.[pkg];
  const confirmed = state.confirmedControl;
  if (confirmed == null) {
    if ((state.appliedRevision?.revisionId ?? null) !== (state.desiredRevision?.revisionId ?? null)) return true;
    if (state.appliedRevision?.status !== 'applied') return true;
  }
  if (desiredRule != null) {
    const confirmedRule = confirmed?.desired?.apps?.[pkg];
    if (confirmedRule == null) return true;
    if (confirmedRule.manualBlocked !== desiredRule.manualBlocked) return true;
    if ((confirmedRule.dailyLimitMinutes ?? null) !== (desiredRule.dailyLimitMinutes ?? null)) return true;
  }
  return st.controlRevisionId != null
    && st.controlRevisionId !== (confirmed?.desired?.revisionId ?? null);
}

/** ParentAppsFeature.kt reason line (lines 310-329) — exact priority order. */
function reasonLine(ctx) {
  const {
    pending, requestedPolicy, app, sourceLocked, st, settingsSectionsLocked,
    settingsLocked, lockBlocked, sectionName, policy,
  } = ctx;
  let text = null;
  if (pending && requestedPolicy?.manualBlocked === true) text = 'Lock requested; TV confirmation pending';
  else if (pending) text = 'Unlock or limit change requested; TV confirmation pending';
  else if (!app.blockable) text = `Reason: ${app.protectedReason ?? 'System critical'}`;
  else if (sourceLocked && st.dailyLimitBlocked) text = 'Daily limit source lock';
  else if (sourceLocked && policy.manualBlocked) text = 'Live TV source locked by parent';
  else if (sourceLocked) text = 'Live TV source locked';
  else if (settingsSectionsLocked && policy.manualBlocked) text = `${sectionName} locked by parent`;
  else if (settingsSectionsLocked) text = `${sectionName} locked`;
  else if (settingsLocked && st.dailyLimitBlocked) text = 'Daily limit settings lock';
  else if (settingsLocked && policy.manualBlocked) text = 'Settings locked by parent';
  else if (settingsLocked) text = 'Settings locked';
  else if (lockBlocked && st.dailyLimitBlocked) text = 'Daily limit lock';
  else if (lockBlocked && policy.manualBlocked) text = 'Locked by parent';
  else if (lockBlocked) text = 'App locked';
  else if (st.dailyLimitBlocked) text = 'Daily limit lock';
  else if (policy.manualBlocked) text = 'Locked by parent';
  else if (policy.dailyLimitMinutes != null) text = `Daily Limit Active (${policy.dailyLimitMinutes} mins)`;
  if (!text) return null;
  const color = (lockBlocked || st.dailyLimitBlocked) ? 'var(--alert-red)' : 'var(--text-muted)';
  return h('div', { class: 'small', style: { marginTop: '4px', color } }, text);
}

function usageStrip(usageMs, blocked, pkg) {
  return h('div', { class: `usage-strip ${blocked ? 'blocked' : ''}` },
    h('span', {}, blocked ? '⛔' : '⏱️'),
    h('span', { 'data-live-usage': pkg }, `${formatUsage(usageMs)} used today`),
  );
}

/** ParentAppsFeature.kt expanded panel (lines 361-431). */
function expandedPanel(state, pkg, app, policy, st, pending, sectionName, sourceApp, settingsApp, section) {
  const lockBlocked = st.lockBlocked === true;
  const parentChipText = sourceApp
    ? (policy.manualBlocked ? 'Source locked by parent' : 'Source allowed by parent')
    : section != null
      ? `${sectionName} ${policy.manualBlocked ? 'locked' : 'allowed'} by parent`
      : settingsApp
        ? `Settings ${policy.manualBlocked ? 'locked' : 'allowed'} by parent`
        : (policy.manualBlocked ? 'Locked by parent' : 'Allowed by parent');
  const runtimeChipText = sourceApp
    ? 'Source lock active'
    : section != null
      ? `${sectionName} lock active`
      : settingsApp
        ? 'Settings lock active'
        : (lockBlocked ? 'Screen lock active' : 'No screen lock');

  const limitInput = h('input', {
    type: 'text', inputmode: 'numeric', maxLength: 4,
    placeholder: policy.dailyLimitMinutes != null ? String(policy.dailyLimitMinutes) : 'Limit',
    'data-persist-key': `limit-${pkg}`,
  });
  limitInput.addEventListener('input', () => {
    limitInput.value = limitInput.value.replace(/\D/g, '').slice(0, 4);
  });

  return h('div', { style: { display: 'flex', flexDirection: 'column', gap: '10px', marginTop: '4px' } },
    h('div', { class: 'small muted' }, pkg),
    h('div', { class: 'row wrap' },
      statusLabel(parentChipText, policy.manualBlocked ? 'var(--alert-red)' : 'var(--success-green)'),
      statusLabel(runtimeChipText, lockBlocked ? 'var(--alert-red)' : 'var(--success-green)'),
      statusLabel(`Mode: ${st.enforcementMode ?? 'unprotected'}`,
        (st.enforcementMode ?? 'unprotected') !== ENFORCEMENT_UNPROTECTED ? 'var(--success-green)' : 'var(--text-muted)'),
    ),
    h('div', { class: 'row wrap' },
      h('div', { style: { width: '130px' } }, limitInput),
      h('button', {
        class: 'btn small', disabled: app.blockable === false || pending,
        onClick: () => {
          const value = limitInput.value.trim();
          const limit = value !== '' ? parseInt(value, 10) : null;
          updatePolicy(pkg, { ...policy, dailyLimitMinutes: limit != null && limit > 0 ? limit : null });
        },
      }, 'Save'),
      h('button', {
        class: 'btn neutral small', disabled: app.blockable === false || pending || policy.dailyLimitMinutes == null,
        onClick: () => updatePolicy(pkg, { ...policy, dailyLimitMinutes: null }),
      }, 'Clear'),
      h('button', {
        class: 'textbtn danger', disabled: app.blockable === false,
        onClick: () => confirmDialog(
          "Reset today's limit?",
          `This clears today's daily-limit lock and usage offset for ${app.label}.`,
          'Reset today', false,
        ).then((yes) => { if (yes) resetToday(pkg); }),
      }, 'Reset today'),
    ),
    st.lastError ? h('div', { class: 'small', style: { color: 'var(--alert-red)' } }, `Error: ${st.lastError}`) : null,
  );
}
