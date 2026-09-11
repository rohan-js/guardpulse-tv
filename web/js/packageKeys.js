/**
 * Port of shared/PackageKeys.kt — base64url without padding, UTF-8.
 * Must stay byte-identical to the Android implementation: the RTDB rules
 * validate that the path key equals PackageKeys.encode(packageName).
 */

export function encode(packageName) {
  const bytes = new TextEncoder().encode(packageName);
  let binary = '';
  bytes.forEach((b) => { binary += String.fromCharCode(b); });
  return base64ToBase64Url(btoa(binary));
}

export function decode(key) {
  const binary = atob(base64UrlToBase64(key));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return new TextDecoder().decode(bytes);
}

export function base64ToBase64Url(b64) {
  return b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

export function base64UrlToBase64(url) {
  let b64 = url.replace(/-/g, '+').replace(/_/g, '/');
  while (b64.length % 4 !== 0) b64 += '=';
  return b64;
}

/**
 * Port of ParentRepository.kt normalizedPackageName (lines 576-586):
 * prefer the stored packageName when its re-encoded form matches the map
 * key; else fall back to decoding the key; else the stored value.
 */
export function normalizedPackageName(encodedKey, storedPackageName) {
  const key = (encodedKey ?? '').trim() !== '' ? encodedKey : null;
  const decoded = key != null ? tryDecode(key) : null;
  const stored = storedPackageName != null && storedPackageName.trim() !== '' ? storedPackageName : null;
  if (stored != null && key != null && tryEncode(stored) === key) return stored;
  return decoded ?? stored ?? null;
}

function tryDecode(key) {
  try { return decode(key); } catch { return null; }
}

function tryEncode(pkg) {
  try { return encode(pkg); } catch { return null; }
}
