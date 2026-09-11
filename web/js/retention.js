/**
 * Port of ParentRetentionCleaner.kt — runs on every device attach.
 * Retention: commands/unlockRequests 7 days after their TERMINAL timestamp
 * (completedAt / updatedAt, createdAt fallback); tamperEvents 30 days and
 * newest-200. The createdAt-ordered query only bounds candidates (it is the
 * declared index); each row is filtered on its terminal timestamp.
 */

import { query, orderByChild, endAt, limitToFirst, limitToLast, get, update, ref } from 'firebase/database';

const TERMINAL_RETENTION_MS = 7 * 24 * 60 * 60_000;
const TAMPER_RETENTION_MS = 30 * 24 * 60 * 60_000;
const MAX_TAMPER_EVENTS = 200;
const TAMPER_QUERY_LIMIT = 250;
const CLEANUP_BATCH_SIZE = 100;

const COMMAND_TERMINAL = new Set(['done', 'failed', 'expired']);
const UNLOCK_TERMINAL = new Set(['approved', 'denied', 'expired']);

export function attachRetentionCleaner(database, now = Date.now) {
  const cleanedDevices = new Set();
  return function cleanup(deviceId) {
    if (cleanedDevices.has(deviceId)) return;
    cleanedDevices.add(deviceId);
    const cutoff = now() - TERMINAL_RETENTION_MS;
    // Failure re-arms: the next attach retries, matching the phone cleaner.
    cleanupTerminal(database, `devices/${deviceId}/commands`, cutoff, COMMAND_TERMINAL, 'completedAt')
      .then(() => cleanupTerminal(database, `devices/${deviceId}/unlockRequests`, cutoff, UNLOCK_TERMINAL, 'updatedAt'))
      .then(() => cleanupTamperEvents(database, deviceId, now()))
      .catch(() => cleanedDevices.delete(deviceId));
  };
}

async function cleanupTerminal(database, path, cutoff, terminalStatuses, terminalField) {
  const snap = await get(query(
    ref(database, path),
    orderByChild('createdAt'),
    endAt(cutoff),
    limitToFirst(CLEANUP_BATCH_SIZE),
  ));
  const value = snap.val();
  if (value == null || typeof value !== 'object') return;
  const updates = {};
  for (const [key, row] of Object.entries(value)) {
    if (!terminalStatuses.has(row?.status)) continue;
    const createdAt = row?.createdAt ?? 0;
    const terminalAt = (row?.[terminalField] ?? 0) > 0 ? row[terminalField] : createdAt;
    if (terminalAt > cutoff) continue;
    updates[`${path}/${key}`] = null;
  }
  if (Object.keys(updates).length > 0) await update(ref(database, path), updates);
}

async function cleanupTamperEvents(database, deviceId, nowMs) {
  const path = `devices/${deviceId}/tamperEvents`;
  const snap = await get(query(ref(database, path), limitToLast(TAMPER_QUERY_LIMIT)));
  const value = snap.val();
  if (value == null || typeof value !== 'object') return;
  const rows = Object.entries(value)
    .map(([key, row]) => ({ key, createdAt: row?.createdAt ?? 0 }))
    .sort((a, b) => b.createdAt - a.createdAt);
  const updates = {};
  rows.forEach(({ key, createdAt }, index) => {
    if (index >= MAX_TAMPER_EVENTS || createdAt < nowMs - TAMPER_RETENTION_MS) {
      updates[`${path}/${key}`] = null;
    }
  });
  if (Object.keys(updates).length > 0) await update(ref(database, path), updates);
}
