/**
 * App shell: root routing (missing-config → auth → dashboard), tab routing,
 * confirm dialogs, toast surface, and the re-render loop. Mirrors
 * MainActivity.kt's routing and dialog scaffolding.
 */
import { store, initStore } from './store.js';
import { h, clear, statusPill, formatUsage } from './ui/components.js';
import { effectiveUsageMs, interpolatedPositionMs } from './reducers.js';
import { renderAuthView, renderMissingFirebaseView } from './ui/authView.js';
import { renderDevicesTab } from './ui/devicesTab.js';
import { renderActivityTab } from './ui/activityTab.js';
import { renderAppsTab } from './ui/appsTab.js';
import { renderSecurityTab, renderEventsTab } from './ui/securityTab.js';

const TABS = ['Devices', 'Activity', 'Apps', 'Security', 'Events'];
let activeTab = 'Devices';
let lastToastMessage = null;
let toastTimer = null;
let lastRenderSig = null;

initStore();

store.subscribe(() => {
  const state = store.get();
  // Toasts ride every store change, independent of render skipping.
  showToastIfNeeded(state.message);
  const sig = stateSignature(state);
  if (sig === lastRenderSig) {
    if (state.signedIn && state.configured) updateLiveBits(state);
    return;
  }
  lastRenderSig = sig;
  render();
});
window.addEventListener('guardpulse:rerender', () => {
  lastRenderSig = null; // local-UI changes (search/expand) force a rebuild
  render();
});

// 1 Hz tick patches only the live-usage/playhead text nodes in place. A full
// re-render every second would replace the DOM under the user's cursor,
// swallowing clicks and stealing focus mid-typing.
setInterval(() => {
  const state = store.get();
  if (state.signedIn && state.configured) updateLiveBits(state);
}, 1000);

function updateLiveBits(state) {
  document.querySelectorAll('[data-live-usage]').forEach((el) => {
    const st = state.states[el.getAttribute('data-live-usage')] ?? {};
    el.textContent = `${formatUsage(effectiveUsageMs(st, state.serverNow))} used today`;
  });
  const current = state.activityCurrent;
  if (!current) return;
  const position = interpolatedPositionMs(current, state.serverNow);
  if (position == null) return;
  document.querySelectorAll('[data-live-position]').forEach((el) => {
    el.textContent = `${formatUsage(position)} / ${formatUsage(current.durationMs)}`;
  });
  document.querySelectorAll('[data-live-progress]').forEach((el) => {
    if (current.durationMs != null && current.durationMs > 0) {
      el.style.width = `${Math.min(1, Math.max(0, position / current.durationMs)) * 100}%`;
    }
  });
}

function stateSignature(state) {
  // serverNow (5s tick) and message (toast) are excluded: they must not
  // rebuild the DOM under the user's cursor.
  return activeTab + '|' + JSON.stringify({ ...state, serverNow: 0, message: null });
}

function render() {
  const state = store.get();
  const appRoot = document.getElementById('app');
  const previousValues = captureInputValues(appRoot);
  const activeKey = appRoot.querySelector(':scope input:focus, :scope textarea:focus')
    ?.getAttribute('data-persist-key') ?? null;
  clear(appRoot);

  if (!state.configured) {
    appRoot.append(renderMissingFirebaseView(state));
    return;
  }
  if (!state.signedIn) {
    appRoot.append(renderAuthView(state));
    return;
  }
  appRoot.append(renderShell(state));
  restoreInputValues(appRoot, previousValues);
  if (activeKey) {
    const refocus = appRoot.querySelector(`[data-persist-key="${activeKey}"]`);
    if (refocus) refocus.focus();
  }
  showToastIfNeeded(state.message);
}

function renderShell(state) {
  const content = h('div', { class: 'content', id: 'tab-content' });
  const shell = h('div', { class: 'app-shell' },
    h('header', { class: 'topbar' },
      h('div', { class: 'brand' },
        h('span', { class: 'title' }, 'GuardPulse'),
        h('span', { class: 'subtitle' }, selectedLabel(state)),
      ),
      h('button', {
        class: 'signout',
        onClick: () => confirmDialog(
          'Sign out?',
          'You will stop receiving TV status until you sign in again.',
          'Sign out', false,
        ).then((yes) => { if (yes) import('./store.js').then((m) => m.signOut()); }),
      }, 'Sign out'),
    ),
    h('nav', { class: 'tabbar' },
      TABS.map((tab) => h('button', {
        class: tab === activeTab ? 'active' : '',
        onClick: () => { activeTab = tab; render(); },
      }, tab)),
    ),
    content,
  );
  renderTab(content, state);
  return shell;
}

function selectedLabel(state) {
  const device = state.devices.find((d) => d.deviceId === state.selectedDeviceId);
  return device?.label ?? state.selectedDeviceId ?? 'No TV selected';
}

function renderTab(container, state) {
  clear(container);
  switch (activeTab) {
    case 'Devices': container.append(renderDevicesTab(state)); break;
    case 'Activity': container.append(renderActivityTab(state)); break;
    case 'Apps': container.append(renderAppsTab(state)); break;
    case 'Security': container.append(renderSecurityTab(state)); break;
    case 'Events': container.append(renderEventsTab(state)); break;
    default: break;
  }
}

/* ============================== toasts ============================== */

function showToastIfNeeded(message) {
  if (!message || message === lastToastMessage) return;
  lastToastMessage = message;
  const wrap = h('div', { class: 'toast-wrap' }, h('div', { class: 'toast' }, message));
  document.getElementById('overlay-root').append(wrap);
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => wrap.remove(), 3800);
}

/* ============================== confirm dialog ============================== */

export function confirmDialog(title, body, confirmLabel, destructive) {
  return new Promise((resolve) => {
    const overlayRoot = document.getElementById('overlay-root');
    const close = (result) => { overlay.remove(); resolve(result); };
    const overlay = h('div', { class: 'overlay' },
      h('div', { class: 'dialog' },
        h('div', { class: 'title' }, title),
        h('div', { class: 'small muted' }, body),
        h('div', { class: 'actions' },
          h('button', { class: 'btn neutral', onClick: () => close(false) }, 'Cancel'),
          h('button', {
            class: destructive ? 'btn danger' : 'btn',
            onClick: () => close(true),
          }, confirmLabel),
        ),
      ),
    );
    overlayRoot.append(overlay);
  });
}

/* ===================== uncontrolled input preservation ===================== */

function captureInputValues(root) {
  const values = {};
  root?.querySelectorAll('input, textarea').forEach((el) => {
    const key = el.dataset.persistKey;
    if (key) values[key] = el.type === 'checkbox' ? el.checked : el.value;
  });
  return values;
}

function restoreInputValues(root, values) {
  root.querySelectorAll('input, textarea').forEach((el) => {
    const key = el.dataset.persistKey;
    if (key && key in values && document.activeElement !== el) {
      if (el.type === 'checkbox') el.checked = values[key];
      else el.value = values[key];
    }
  });
}

export { statusPill };
