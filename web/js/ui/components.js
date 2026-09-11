/**
 * Tiny DOM helpers + the shared visual primitives ported from ParentUiShared.kt
 * (StatusPill, StatusLabel, EmptyPanel, MetaTile, format helpers).
 */

export function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs ?? {})) {
    if (value == null || value === false) continue;
    if (key.startsWith('on') && typeof value === 'function') {
      el.addEventListener(key.slice(2).toLowerCase(), value);
    } else if (key === 'class') {
      el.className = value;
    } else if (key === 'style' && typeof value === 'object') {
      Object.assign(el.style, value);
    } else if (key === 'value') {
      el.value = value;
    } else if (key === 'checked') {
      el.checked = value === true;
    } else if (key === 'disabled') {
      el.disabled = value === true;
    } else if (key === 'autofocus') {
      el.autofocus = true;
    } else if (key === 'type' || key === 'placeholder' || key === 'id' || key === 'maxLength'
      || key === 'inputmode' || key === 'src' || key === 'title') {
      el.setAttribute(key.toLowerCase(), String(value));
    } else {
      el.setAttribute(key, String(value));
    }
  }
  appendChildren(el, children);
  return el;
}

function appendChildren(el, children) {
  for (const child of children.flat(Infinity)) {
    if (child == null || child === false) continue;
    el.append(child.nodeType ? child : document.createTextNode(String(child)));
  }
}

export function clear(el) {
  while (el.firstChild) el.removeChild(el.firstChild);
}

/** ParentUiShared.kt StatusPill. */
export function statusPill(text, ok, kind = ok ? 'ok' : 'bad') {
  return h('span', { class: `pill ${kind}` }, text);
}

/** ParentUiShared.kt StatusLabel — uppercase chip, OutlineSoft renders muted. */
export function statusLabel(text, color) {
  const style = { background: 'var(--surface-tint)' };
  let fg = color;
  if (color === 'outline') fg = 'var(--text-muted)';
  if (fg) style.color = fg;
  return h('span', { class: 'label-chip', style }, text);
}

/** ParentUiShared.kt EmptyPanel. */
export function emptyPanel(title, detail) {
  return h('div', { class: 'empty-panel' },
    h('div', { style: { fontSize: '26px' } }, '📺'),
    h('div', { class: 'title' }, title),
    h('div', { class: 'small' }, detail),
  );
}

export function sectionLabel(text) {
  return h('div', { class: 'section-label' }, text);
}

export function card(...children) {
  return h('div', { class: 'card' }, ...children);
}

export function statusDot(ok) {
  return h('span', { class: `status-dot ${ok ? 'ok' : 'bad'}` });
}

/** ParentUiShared.kt RuntimeRow: label + value + StatusPill. */
export function runtimeRow(label, value, ok) {
  return h('div', { class: 'row' },
    h('div', { class: 'grow small', style: { fontWeight: 600 } }, label),
    h('div', { class: 'small muted ellipsis', style: { maxWidth: '40%' } }, value),
    statusPill(ok ? 'OK' : 'Action', ok),
  );
}

export function metaTile(k, v, ok) {
  return h('div', { class: 'meta-tile' },
    h('span', { class: 'k' }, k),
    h('span', { style: { fontWeight: 700, color: ok == null ? undefined : (ok ? 'var(--success-green)' : 'var(--alert-red)') } }, v),
  );
}

export function field(labelText, inputEl, errorText) {
  return h('div', { class: 'field' },
    labelText ? h('label', {}, labelText) : null,
    inputEl,
    errorText ? h('div', { class: 'error-text' }, errorText) : null,
  );
}

export function switchToggle(checked, disabled, onChange) {
  const input = h('input', { type: 'checkbox', checked, disabled });
  input.addEventListener('change', () => onChange(input.checked));
  return h('label', { class: 'switch' }, input, h('span', { class: 'slider' }));
}

export function formatUsage(ms) {
  const totalSeconds = Math.floor(Math.max(0, ms ?? 0) / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) return `${hours}h ${minutes}m`;
  if (minutes > 0) return `${minutes}m ${seconds}s`;
  return `${seconds}s`;
}

export function formatTimestamp(ts) {
  if (ts == null || ts <= 0) return 'unknown';
  return new Date(ts).toLocaleString(undefined, { day: '2-digit', month: 'short', hour: 'numeric', minute: '2-digit' });
}

export function formatAge(ts, now) {
  if (ts == null || ts <= 0) return 'unknown';
  const elapsed = Math.max(0, now - ts);
  const minutes = Math.floor(elapsed / 60_000);
  if (minutes < 1) return 'just now';
  if (minutes === 1) return '1 min';
  if (minutes < 60) return `${minutes} mins`;
  const hours = Math.floor(minutes / 60);
  const rem = minutes % 60;
  return `${hours}h ${rem}m`;
}

/** ParentUiShared.kt unlockApprovalLabel (lines 175-183). */
export function unlockApprovalLabel(request) {
  if (request.status === 'pending') return 'waiting';
  if (request.approvalType === 'timed') return `${Math.round((request.approvalDurationMs ?? 0) / 60000)} minutes`;
  if (request.approvalType === 'oneVisit' || request.approvalType == null) return 'one visit';
  return request.approvalType;
}
