/**
 * Port of shared/PinHasher.kt — byte-identical hashes via WebCrypto.
 * v2: PBKDF2-HMAC-SHA256, 210,000 iterations, 16-byte salt, 32-byte key,
 *     base64url unpadded.
 * v1 (legacy, verify-only): SHA-256 of UTF-8 "salt:pin", base64url unpadded.
 */

export const LEGACY_VERSION = 1;
export const CURRENT_VERSION = 2;
export const CURRENT_ALGORITHM = 'PBKDF2WithHmacSHA256';
export const CURRENT_ITERATIONS = 210_000;
const KEY_LENGTH_BITS = 256;

export function assertPinFormat(pin) {
  if (!/^\d{6}$/.test(pin)) throw new Error('PIN must be exactly six digits');
}

export async function create(pin) {
  assertPinFormat(pin);
  const saltBytes = crypto.getRandomValues(new Uint8Array(16));
  const salt = bytesToBase64Url(saltBytes);
  return {
    salt,
    hash: await pbkdf2(pin, salt, CURRENT_ITERATIONS),
    version: CURRENT_VERSION,
    algorithm: CURRENT_ALGORITHM,
    iterations: CURRENT_ITERATIONS,
  };
}

export async function verify(pin, salt, expectedHash, version = CURRENT_VERSION, algorithm = null, iterations = null) {
  if (!/^\d{6}$/.test(pin) || !salt || !expectedHash) return false;
  let actual;
  if (version === LEGACY_VERSION) {
    actual = await legacyHash(pin, salt);
  } else if (version === CURRENT_VERSION) {
    if (algorithm != null && algorithm !== CURRENT_ALGORITHM) return false;
    const rounds = iterations ?? CURRENT_ITERATIONS;
    if (rounds < CURRENT_ITERATIONS || rounds > 1_000_000) return false;
    actual = await pbkdf2(pin, salt, rounds);
  } else {
    return false;
  }
  // Kotlin compares the UTF-8 bytes of the base64 strings (MessageDigest.isEqual).
  return constantTimeEqualUtf8(actual, expectedHash);
}

async function pbkdf2(pin, salt, iterations) {
  const keyMaterial = await crypto.subtle.importKey(
    'raw', new TextEncoder().encode(pin), 'PBKDF2', false, ['deriveBits'],
  );
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', hash: 'SHA-256', salt: base64UrlToBytes(salt), iterations },
    keyMaterial,
    KEY_LENGTH_BITS,
  );
  return bytesToBase64Url(new Uint8Array(bits));
}

async function legacyHash(pin, salt) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`${salt}:${pin}`));
  return bytesToBase64Url(new Uint8Array(digest));
}

function constantTimeEqualUtf8(a, b) {
  const aBytes = new TextEncoder().encode(a);
  const bBytes = new TextEncoder().encode(b);
  if (aBytes.length !== bBytes.length) return false;
  let diff = 0;
  for (let i = 0; i < aBytes.length; i++) diff |= aBytes[i] ^ bBytes[i];
  return diff === 0;
}

function bytesToBase64Url(bytes) {
  let binary = '';
  bytes.forEach((b) => { binary += String.fromCharCode(b); });
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

function base64UrlToBytes(url) {
  let b64 = url.replace(/-/g, '+').replace(/_/g, '/');
  while (b64.length % 4 !== 0) b64 += '=';
  const binary = atob(b64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}
