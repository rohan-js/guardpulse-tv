/**
 * Port of ParentDevicesFeature.kt parsePairingPayload (lines 95-104):
 * extracts deviceId + secret from a guardpulse://pair?... payload.
 */
export function parsePairingPayload(payload) {
  const raw = (payload ?? '').trim();
  if (raw === '') return { deviceId: null, secret: null };
  try {
    const url = new URL(raw);
    return {
      deviceId: url.searchParams.get('deviceId'),
      secret: url.searchParams.get('secret'),
    };
  } catch {
    const deviceId = raw.match(/[?&]deviceId=([^&]+)/)?.[1] ?? null;
    const secret = raw.match(/[?&]secret=([^&]+)/)?.[1] ?? null;
    return {
      deviceId: deviceId ? decodeURIComponent(deviceId) : null,
      secret: secret ? decodeURIComponent(secret) : null,
    };
  }
}
