/**
 * Port of ParentSecurityFeature.kt (Protection Health, SyncHealthCard with the
 * full deriveSyncStatus text matrix, Safe Mode, One-Tap Modes, TV Setup
 * Access, Parent PIN, pending unlock requests, approved-waiting section) and
 * the Events tab.
 */
import {
  h, card, emptyPanel, statusLabel, statusPill, runtimeRow, field,
  switchToggle, formatTimestamp, formatAge, unlockApprovalLabel,
} from './components.js';
import {
  store, reconnect, repairControlV2, startSafeMode, stopSafeMode, createMode, renameMode,
  deleteMode, setActiveMode, updateModePolicy, openTvSetup, setPin, updateUnlock,
} from '../store.js';
import { modeUsageMs, isPendingUnlock, isCriticalTamperEvent } from '../reducers.js';
import { freshness } from '../controlProtocol.js';
import { CURRENT_VERSION as PIN_HASH_CURRENT_VERSION } from '../pinHasher.js';
import {
  SYNC_PROTOCOL_VERSION, UNLOCK_APPROVED, UNLOCK_DENIED, UNLOCK_APPROVAL_ONE_VISIT,
  UNLOCK_APPROVAL_TIMED, UNLOCK_15_MINUTES_MS, UNLOCK_30_MINUTES_MS,
  ENFORCEMENT_FALLBACK,
} from '../policyConstants.js';

export function renderSecurityTab(state) {
  if (!state.selectedDeviceId) {
    return emptyPanel('No TV selected', 'Select or pair a TV before changing security settings.');
  }
  if (state.loadingDeviceDetails && state.security == null) {
    return emptyPanel('Loading security', 'Reading TV protection health from Firebase...');
  }
  return h('div', { style: { display: 'flex', flexDirection: 'column', gap: '16px' } },
    h('div', {},
      h('div', { class: 'section-label' }, 'Security Settings'),
      h('div', { class: 'small muted', style: { margin: '2px 0 12px' } }, 'Manage protection layers and review pending requests.'),
    ),
    protectionHealthCard(state),
    syncHealthCard(state),
    safeModeCard(state),
    modesCard(state),
    tvSetupCard(),
    parentPinCard(state),
    pendingUnlocksCard(state),
    approvedWaitingCard(state),
  );
}

/* ============================== protection health ============================== */

function protectionHealthCard(state) {
  const security = state.security ?? {};
  const rows = [];
  rows.push(runtimeRow('Enforcement Mode', security.enforcementMode ?? 'unprotected',
    (security.enforcementMode ?? 'unprotected') !== 'unprotected'));
  const adminLabel = security.deviceAdminSetupAvailable === false ? 'Device Admin unavailable' : 'Device Admin';
  const adminValue = security.deviceAdmin === true ? 'Active'
    : security.deviceAdminSetupAvailable === false ? 'Unavailable' : 'Needs setup';
  rows.push(runtimeRow(adminLabel, adminValue, security.deviceAdmin === true || security.deviceAdminSetupAvailable === false));
  rows.push(runtimeRow('Accessibility', security.accessibility ? 'Active' : 'Needs action', security.accessibility === true));
  rows.push(runtimeRow('Usage Access', security.usageAccess ? 'Active' : 'Needs action', security.usageAccess === true));
  rows.push(runtimeRow('Media titles', security.mediaTitlesEnabled ? 'Active' : 'Needs action', security.mediaTitlesEnabled === true));
  rows.push(runtimeRow('Network Filter', 'Not required for app locks', true));
  rows.push(runtimeRow('Background Access', security.backgroundUnrestricted ? 'Unrestricted' : 'Battery restricted', security.backgroundUnrestricted === true));
  rows.push(runtimeRow('PIN', security.pinConfigured ? 'Configured' : 'Missing', security.pinConfigured === true));
  rows.push(runtimeRow('Healthy', security.protectionHealthy ? 'Healthy' : 'Needs setup', security.protectionHealthy === true));
  rows.push(runtimeRow('Active Mode', state.activeMode?.modeName ?? 'Normal policy', state.activeMode?.modeId != null));
  const safeActive = state.safeMode?.enabled === true && (state.safeMode.until ?? 0) > state.serverNow;
  rows.push(runtimeRow('Safe Mode', safeActive ? `Active until ${formatTimestamp(state.safeMode.until)}` : 'Off', !safeActive));

  const banners = [];
  if (security.pinConfigured === true && (security.pinHashVersion ?? 0) < PIN_HASH_CURRENT_VERSION) {
    banners.push(h('div', { class: 'banner red' }, 'PIN security upgrade required. Set a new PIN below to upgrade protection.'));
  }
  if ((security.enforcementMode ?? '') === ENFORCEMENT_FALLBACK) {
    banners.push(h('div', { class: 'small muted' }, 'Fallback mode protects via Accessibility and PIN gate. It is not uninstall-proof.'));
  }
  if (security.lastSyncError) {
    banners.push(h('div', { class: 'small', style: { color: 'var(--alert-red)' } }, `Last sync error: ${security.lastSyncError}`));
  }
  if (security.lastForegroundPackage) {
    banners.push(h('div', { class: 'small muted' }, `Last foreground: ${security.lastForegroundPackage}`));
  }
  return card(h('div', { class: 'card-title' }, 'Protection Health'), ...rows, ...banners);
}

/* ============================== sync health ============================== */

export function syncHealthInputs(state) {
  const protocolReady = (state.syncRuntime?.protocolVersion ?? 0) >= 2;
  const device = state.devices.find((d) => d.deviceId === state.selectedDeviceId);
  const tvConnected = protocolReady ? state.syncRuntime?.connected === true : device?.online === true;
  return {
    controlAvailability: state.controlAvailability,
    phoneConnected: state.phoneConnected,
    protocolVersion: state.syncRuntime?.protocolVersion ?? 0,
    desired: state.desiredRevision,
    applied: state.appliedRevision,
    freshness: freshness(tvConnected, device?.lastSeen ?? null, state.serverNow),
    protocolReady,
    tvConnected,
  };
}

function syncHealthCard(state) {
  const inputs = syncHealthInputs(state);
  const status = deriveSyncStatus(inputs);
  const { statusText, color } = syncStatusText(state, inputs, status);
  const healthy = state.phoneConnected && (status === 'APPLIED' || (status === 'IDLE' && inputs.freshness === 'LIVE'));

  const runtime = state.syncRuntime ?? {};
  const rows = [
    runtimeRow('Phone Firebase', state.phoneConnected ? 'Connected' : 'Offline', state.phoneConnected),
    runtimeRow('TV connection', inputs.freshness === 'LIVE' ? 'Live' : inputs.freshness === 'DELAYED' ? 'Delayed' : 'Offline', inputs.freshness === 'LIVE'),
    runtimeRow('Sync protocol', inputs.protocolReady ? 'V2' : 'Legacy', inputs.protocolReady),
  ];
  if (runtime.lastPolicyAppliedAt) rows.push(runtimeRow('Policy applied', formatTimestamp(runtime.lastPolicyAppliedAt), true));
  if (runtime.lastStateWriteAt) rows.push(runtimeRow('Usage updated', formatTimestamp(runtime.lastStateWriteAt), true));
  if (runtime.lastInventoryWriteAt) rows.push(runtimeRow('Inventory updated', formatTimestamp(runtime.lastInventoryWriteAt), true));

  const command = state.commands[0];
  if (command) {
    rows.push(h('div', { class: 'row' },
      h('div', { class: 'grow small', style: { fontWeight: 600 } }, 'Latest command'),
      h('div', { class: 'small muted' }, `${command.type}: ${command.status}`),
      statusPill(command.status === 'done' ? 'OK' : 'Action', command.status === 'done'),
    ));
    if (command.error) rows.push(h('div', { class: 'small', style: { color: 'var(--alert-red)' } }, command.error));
  }

  const errorText = state.appliedRevision?.error
    ?? ((runtime.lastErrorAt ?? 0) >= (runtime.lastSuccessAt ?? 0) ? runtime.lastError : null);
  if (errorText) rows.push(h('div', { class: 'small', style: { color: 'var(--alert-red)' } }, errorText));

  const repair = state.controlAvailability === 'INVALID'
    ? h('div', { style: { display: 'flex', flexDirection: 'column', gap: '8px' } },
      h('div', { class: 'small', style: { color: 'var(--alert-red)' } },
        state.controlError ?? 'The synchronized control snapshot is malformed.'),
      h('button', { class: 'btn danger-outline', onClick: () => repairConfirm() }, 'Repair synchronized control'),
    )
    : null;

  return card(
    h('div', { class: 'row' },
      h('div', { class: 'grow card-title' }, 'Synchronization'),
      h('button', { class: 'textbtn small', onClick: () => reconnect() }, 'Refresh'),
    ),
    h('div', { class: 'row' }, h('span', { class: 'label-chip', style: { background: 'var(--surface-tint)', color } }, statusText)),
    ...rows,
    repair,
  );
}

/** ParentSecurityFeature.kt statusText (lines 123-142) — exact matrix. */
function syncStatusText(state, inputs, status) {
  if (!state.phoneConnected) {
    const queued = inputs.desired?.revisionId != null && inputs.applied?.revisionId !== inputs.desired.revisionId;
    return { statusText: queued ? 'Phone offline - writes queued' : 'Phone offline', color: 'var(--alert-red)' };
  }
  let text;
  switch (status) {
    case 'SENDING': text = 'Phone offline - writes queued'; break;
    case 'WAITING_FOR_TV': text = 'Waiting for TV'; break;
    case 'APPLIED': text = 'Applied'; break;
    case 'DELAYED': text = 'TV connection delayed'; break;
    case 'OFFLINE_PENDING': text = 'TV offline - change pending'; break;
    case 'FAILED': text = 'TV rejected latest change'; break;
    case 'TV_UPDATE_REQUIRED': text = 'TV update required'; break;
    default:
      text = inputs.freshness === 'LIVE' ? 'Synchronized'
        : inputs.freshness === 'DELAYED' ? 'TV connection delayed' : 'TV offline';
  }
  const healthy = status === 'APPLIED' || (status === 'IDLE' && inputs.freshness === 'LIVE');
  const color = healthy ? 'var(--success-green)'
    : (status === 'WAITING_FOR_TV' || status === 'DELAYED') ? 'var(--action-blue)' : 'var(--alert-red)';
  return { statusText: text, color };
}

function repairConfirm() {
  import('../main.js').then(({ confirmDialog }) =>
    confirmDialog(
      'Repair synchronized control?',
      'This replaces the malformed V2 control with the last valid legacy-compatible policy.',
      'Repair', true,
    ).then((yes) => { if (yes) repairControlV2(); }));
}

/* ============================== safe mode ============================== */

function safeModeCard(state) {
  const active = state.safeMode?.enabled === true && (state.safeMode.until ?? 0) > state.serverNow;
  const customInput = h('input', {
    type: 'text', inputmode: 'numeric', maxLength: 4, placeholder: 'Minutes',
    'data-persist-key': 'safemode-custom',
  });
  customInput.addEventListener('input', () => {
    customInput.value = customInput.value.replace(/\D/g, '').slice(0, 4);
  });
  const startWith = (minutes) => import('../main.js').then(({ confirmDialog }) =>
    confirmDialog(
      'Start Safe Mode?',
      `All TV PIN locks will pause for ${minutes} minutes, until ${formatTimestamp(state.serverNow + minutes * 60_000)}.`,
      'Start', true,
    ).then((yes) => { if (yes) startSafeMode(minutes); }));

  return card(
    h('div', { class: 'card-title' }, 'Emergency Safe Mode'),
    active
      ? h('div', { class: 'small' }, `All app, Live TV, Settings, and protected Settings-section locks are paused until ${formatTimestamp(state.safeMode.until)}.`)
      : h('div', { class: 'small muted' }, 'Pause all TV PIN locks for a chosen duration without disabling sync, inventory, or health reporting.'),
    active
      ? h('button', {
        class: 'btn danger',
        onClick: () => import('../main.js').then(({ confirmDialog }) =>
          confirmDialog(
            'Deactivate Safe Mode?',
            'TV app, Live TV, Settings, and Settings-section locks will resume immediately.',
            'Deactivate', true,
          ).then((yes) => { if (yes) stopSafeMode(); })),
      }, 'Deactivate Safe Mode')
      : h('div', { style: { display: 'flex', flexDirection: 'column', gap: '10px' } },
        h('div', { class: 'row wrap' },
          [15, 30, 60, 120].map((minutes) => h('button', {
            class: 'day-chip', onClick: () => startWith(minutes),
          }, `${minutes}m`)),
        ),
        h('div', { class: 'row wrap' },
          h('div', { style: { width: '130px' } }, customInput),
          h('button', {
            class: 'btn',
            onClick: () => {
              const minutes = parseInt(customInput.value, 10);
              if (!(minutes >= 1 && minutes <= 1440)) {
                store.setMessage('Safe Mode duration must be between 1 and 1440 minutes');
                return;
              }
              startWith(minutes);
            },
          }, 'Start'),
        ),
        h('div', { class: 'small muted' }, 'Custom duration must be 1 to 1440 minutes.'),
      ),
  );
}

/* ============================== one-tap modes ============================== */

let expandedModeId = null;

function modesCard(state) {
  const nameInput = h('input', { type: 'text', placeholder: 'Study time', 'data-persist-key': 'new-mode-name' });
  const createRow = h('div', { class: 'row' },
    h('div', { class: 'grow' }, nameInput),
    h('button', {
      class: 'btn small',
      onClick: () => {
        createMode(nameInput.value);
        nameInput.value = '';
      },
    }, 'Create'),
  );

  const modeRows = state.modes.length === 0
    ? h('div', { class: 'small muted' }, 'No custom modes yet.')
    : state.modes.map((mode) => modeSummaryRow(state, mode));

  return card(
    h('div', { class: 'card-title' }, 'One-Tap Modes'),
    h('div', { class: 'small muted' }, 'Create named policy sets. When a mode is active, listed apps use the mode rules and unlisted apps are allowed.'),
    createRow,
    ...modeRows,
  );
}

function modeSummaryRow(state, mode) {
  const active = state.activeMode?.modeId === mode.modeId;
  if (expandedModeId == null && active) expandedModeId = mode.modeId;
  const expanded = expandedModeId === mode.modeId;
  const lockedApps = Object.values(mode.appPolicies).filter((p) => p.manualBlocked).length;
  const dailyLimits = Object.values(mode.appPolicies).filter((p) => p.dailyLimitMinutes != null).length;

  const children = [
    h('div', {
      class: 'row',
      style: active ? { background: 'var(--surface-tint)', borderRadius: '12px', padding: '8px', border: '1.5px solid var(--action-blue)' } : { padding: '8px' },
    },
      h('div', { class: 'grow' },
        h('div', { style: { fontWeight: 700 } }, mode.name),
        h('div', { class: 'small muted' }, `${lockedApps} locked · ${dailyLimits} limits`),
        statusLabel(active ? 'Open' : 'Closed', active ? 'var(--success-green)' : 'var(--text-muted)'),
      ),
      h('button', { class: 'textbtn small', onClick: () => { expandedModeId = expanded ? null : mode.modeId; rerender(); } }, expanded ? 'Close' : 'Open'),
      switchToggle(active, false, (enabled) => activateConfirm(enabled ? mode : null)),
    ),
  ];

  if (expanded) {
    const renameInput = h('input', { type: 'text', value: mode.name, 'data-persist-key': `rename-${mode.modeId}` });
    children.push(h('div', { class: 'row' },
      h('div', { class: 'grow' }, renameInput),
      h('button', { class: 'btn small', onClick: () => renameMode(mode.modeId, renameInput.value) }, 'Save'),
      h('button', {
        class: 'btn danger-outline small',
        onClick: () => import('../main.js').then(({ confirmDialog }) =>
          confirmDialog(
            `Delete ${mode.name}?`,
            'This permanently removes the mode and its app rules.',
            'Delete', true,
          ).then((yes) => { if (yes) { deleteMode(mode.modeId); expandedModeId = null; } })),
      }, 'Delete'),
    ));
    children.push(h('div', { class: 'small muted', style: { marginTop: '2px' } }, 'Per-app rules while this mode is active:'));
    const blockableApps = Object.values(state.apps).filter((app) => app.blockable);
    if (blockableApps.length === 0) {
      children.push(h('div', { class: 'small muted' }, 'No blockable apps uploaded yet.'));
    } else {
      children.push(...blockableApps
        .sort((a, b) => a.label.toLowerCase().localeCompare(b.label.toLowerCase()))
        .map((app) => modeAppPolicyRow(state, mode, app)));
    }
  }
  return h('div', { style: { display: 'flex', flexDirection: 'column', gap: '8px' } }, ...children);
}

function activateConfirm(mode) {
  import('../main.js').then(({ confirmDialog }) =>
    confirmDialog(
      mode == null ? 'Disable active mode?' : `Activate ${mode.name}?`,
      mode == null
        ? 'The TV will return to normal per-app policies.'
        : "The TV will immediately apply this mode's app locks and limits.",
      mode == null ? 'Disable' : 'Activate', false,
    ).then((yes) => { if (yes) setActiveMode(mode); }));
}

function modeAppPolicyRow(state, mode, app) {
  const policy = mode.appPolicies[app.packageName] ?? { manualBlocked: false, dailyLimitMinutes: null };
  const usageMs = modeUsageMs(state.states, app.packageName, state.serverNow);
  const limitReached = policy.dailyLimitMinutes != null && usageMs >= policy.dailyLimitMinutes * 60_000;
  const pending = modePolicyPending(state, mode.modeId, app.packageName);

  const limitInput = h('input', {
    type: 'text', inputmode: 'numeric', maxLength: 4,
    placeholder: policy.dailyLimitMinutes != null ? String(policy.dailyLimitMinutes) : 'Limit',
    'data-persist-key': `mode-${mode.modeId}-${app.packageName}`,
  });
  limitInput.addEventListener('input', () => {
    limitInput.value = limitInput.value.replace(/\D/g, '').slice(0, 4);
  });

  return h('div', { style: { display: 'flex', flexDirection: 'column', gap: '6px', padding: '8px 0', borderBottom: '1px solid var(--surface-tint)' } },
    h('div', { class: 'row' },
      h('div', { class: 'grow' },
        h('div', { style: { fontWeight: 600 } }, app.label),
        h('div', { class: 'small muted ellipsis' }, app.packageName),
        policy.dailyLimitMinutes != null
          ? h('div', { class: `small ${limitReached ? '' : 'muted'}`, style: limitReached ? { color: 'var(--alert-red)', fontWeight: 700 } : {} },
            `${formatUsage(usageMs)} / ${policy.dailyLimitMinutes} mins used today`)
          : h('div', { class: 'small muted' }, `${formatUsage(usageMs)} used today`),
      ),
      switchToggle(!policy.manualBlocked, pending, (allowed) => {
        updateModePolicy(mode.modeId, app.packageName, { ...policy, manualBlocked: !allowed });
      }),
    ),
    h('div', { class: 'row wrap' },
      h('div', { class: 'small muted', style: { width: '128px' } }, 'Mode daily limit'),
      h('div', { style: { width: '110px' } }, limitInput),
      h('button', {
        class: 'btn small', disabled: pending,
        onClick: () => {
          const value = limitInput.value.trim();
          const limit = value !== '' ? parseInt(value, 10) : null;
          updateModePolicy(mode.modeId, app.packageName, { ...policy, dailyLimitMinutes: limit != null && limit > 0 ? limit : null });
        },
      }, 'Save'),
      h('button', {
        class: 'btn neutral small', disabled: pending || policy.dailyLimitMinutes == null,
        onClick: () => updateModePolicy(mode.modeId, app.packageName, { ...policy, dailyLimitMinutes: null }),
      }, 'Clear'),
    ),
  );
}

function modePolicyPending(state, modeId, packageName) {
  const desiredModeRule = state.desiredControl?.modes?.[modeId]?.apps?.[packageName];
  const confirmedModeRule = state.confirmedControl?.desired?.modes?.[modeId]?.apps?.[packageName];
  if (desiredModeRule != null && state.confirmedControl != null) {
    if (confirmedModeRule == null) return true;
    if (confirmedModeRule.manualBlocked !== desiredModeRule.manualBlocked) return true;
    if ((confirmedModeRule.dailyLimitMinutes ?? null) !== (desiredModeRule.dailyLimitMinutes ?? null)) return true;
  }
  if (state.confirmedControl == null) {
    if ((state.appliedRevision?.revisionId ?? null) !== (state.desiredRevision?.revisionId ?? null)) return true;
    if (state.appliedRevision?.status !== 'applied') return true;
  }
  return false;
}

function rerender() {
  window.dispatchEvent(new CustomEvent('guardpulse:rerender'));
}

/* ============================== TV setup ============================== */

function tvSetupCard() {
  return card(
    h('div', { class: 'card-title' }, 'TV Setup Access'),
    h('div', { class: 'small muted' }, 'Open the hidden setup screen on the selected TV. The TV will require the parent PIN before showing setup.'),
    h('button', { class: 'btn secondary', onClick: () => openTvSetup() }, 'Open TV Setup'),
  );
}

/* ============================== parent PIN ============================== */

function parentPinCard(state) {
  const security = state.security ?? {};
  const pinInput = h('input', {
    type: 'password', inputmode: 'numeric', maxLength: 6, placeholder: 'Enter PIN',
    'data-persist-key': 'new-pin',
  });
  pinInput.addEventListener('input', () => {
    pinInput.value = pinInput.value.replace(/\D/g, '').slice(0, 6);
  });
  const legacyBanner = security.pinConfigured === true && (security.pinHashVersion ?? 0) < PIN_HASH_CURRENT_VERSION
    ? h('div', { class: 'small', style: { color: 'var(--alert-red)' } }, 'Your existing PIN still works, but it uses legacy hashing. Set it again to upgrade security.')
    : null;

  return card(
    h('div', { class: 'card-title' }, 'Parent PIN'),
    legacyBanner,
    h('div', { class: 'row', style: { gap: '6px' } },
      Array.from({ length: 6 }, (_, i) => h('span', {
        style: {
          width: '12px', height: '12px', borderRadius: '50%',
          background: security.pinConfigured ? 'var(--action-blue)' : 'var(--outline-soft)', display: 'inline-block',
        },
      })),
    ),
    field('New 6-digit PIN', pinInput),
    h('button', {
      class: 'btn',
      onClick: () => import('../main.js').then(({ confirmDialog }) =>
        confirmDialog(
          security.pinConfigured ? 'Change parent PIN?' : 'Set parent PIN?',
          'This PIN controls the TV lock wall and protected setup access.',
          security.pinConfigured ? 'Change PIN' : 'Set PIN', false,
        ).then((yes) => { if (yes) setPin(pinInput.value); })),
    }, security.pinConfigured ? 'Change PIN' : 'Set PIN'),
  );
}

/* ============================== unlock requests ============================== */

function pendingUnlocksCard(state) {
  const pending = state.unlockRequests.filter((r) => isPendingUnlock(r, state.serverNow));
  if (pending.length === 0) return h('div', {});
  const rows = pending.map((request) => {
    const app = state.apps[request.packageName];
    return h('div', { class: 'card tint', style: { borderColor: 'var(--action-blue)', borderWidth: '2px' } },
      h('div', { class: 'row' },
        h('div', { class: 'icon-tile locked' }, '📺'),
        h('div', { class: 'grow' },
          h('div', { style: { fontWeight: 700 } }, app?.label ?? request.packageName),
          h('div', { class: 'small muted ellipsis' }, request.packageName),
        ),
      ),
      h('div', { class: 'small', style: { color: 'var(--alert-red)' } }, request.reason),
      h('div', { class: 'small muted' },
        `Age: ${formatAge(request.createdAt, state.serverNow)}`,
        ` · Requested: ${formatTimestamp(request.createdAt)}`,
        ` · Expires: ${formatTimestamp(request.expiresAt)}`,
        ` · Approval: ${unlockApprovalLabel(request)}`,
      ),
      h('div', { class: 'row wrap' },
        h('button', { class: 'btn danger-outline small', onClick: () => updateUnlock(request, UNLOCK_DENIED) }, 'Deny'),
        h('button', { class: 'btn small', onClick: () => updateUnlock(request, UNLOCK_APPROVED, UNLOCK_APPROVAL_ONE_VISIT, null) }, 'One Visit'),
        h('button', { class: 'btn small', onClick: () => updateUnlock(request, UNLOCK_APPROVED, UNLOCK_APPROVAL_TIMED, UNLOCK_15_MINUTES_MS) }, '15 Minutes'),
        h('button', { class: 'btn small', onClick: () => updateUnlock(request, UNLOCK_APPROVED, UNLOCK_APPROVAL_TIMED, UNLOCK_30_MINUTES_MS) }, '30 Minutes'),
      ),
    );
  });
  return h('div', { style: { display: 'flex', flexDirection: 'column', gap: '12px' } },
    h('div', { class: 'section-label' }, 'Pending Unlock Requests'),
    ...rows,
  );
}

function approvedWaitingCard(state) {
  const waiting = state.unlockRequests.filter((r) => r.status === UNLOCK_APPROVED && r.tvApplyStatus !== 'applied');
  const rows = waiting.length === 0
    ? h('div', { class: 'small muted' }, 'No approvals are waiting on the TV.')
    : waiting.map((request) => {
      const app = state.apps[request.packageName];
      return h('div', { class: 'list-row' },
        h('div', { class: 'grow' },
          h('div', { style: { fontWeight: 600 } }, app?.label ?? request.packageName),
          h('div', { class: 'small muted' }, request.packageName),
          h('div', { class: 'small muted' }, `Approved ${formatAge(request.updatedAt, state.serverNow)} · ${unlockApprovalLabel(request)}`),
        ),
        statusLabel('Waiting for TV', 'var(--action-blue)'),
      );
    });
  return card(
    h('div', { class: 'card-title' }, 'Approved — Waiting for TV'),
    rows,
  );
}

/* ============================== events tab ============================== */

export function renderEventsTab(state) {
  if (!state.selectedDeviceId) {
    return emptyPanel('No TV selected', 'Select or pair a TV before reviewing events.');
  }
  if (state.tamperEvents.length === 0) {
    return emptyPanel('No events', 'Tamper and protection events will appear here.');
  }
  const header = h('div', {},
    h('div', { class: 'section-label' }, 'Events'),
    h('div', { class: 'small muted', style: { margin: '2px 0 12px' } }, 'Tamper and protection events from the selected TV.'),
  );
  const rows = state.tamperEvents.map((event) => {
    const critical = isCriticalTamperEvent(event);
    return h('div', { class: `card ${critical ? 'danger-outline' : ''}`, style: { padding: '12px 14px', flexDirection: 'row', gap: '12px', alignItems: 'center' } },
      h('div', { class: `icon-tile ${critical ? 'locked' : ''}` }, critical ? '⚠️' : '🛡️'),
      h('div', { class: 'grow' },
        h('div', { style: { fontWeight: 700 } }, event.type || 'Event'),
        h('div', { class: 'small muted' }, event.message ?? 'No details'),
        h('div', { class: 'small muted' }, `Time: ${formatTimestamp(event.createdAt)}`),
      ),
    );
  });
  return h('div', { style: { display: 'flex', flexDirection: 'column', gap: '12px' } }, header, ...rows);
}
