/** Cryptography helpers built on the WebCrypto API available in Workers. */

const encoder = new TextEncoder();

export function randomId(): string {
  return crypto.randomUUID();
}

export function randomToken(byteLength = 32): string {
  const bytes = new Uint8Array(byteLength);
  crypto.getRandomValues(bytes);
  return base64UrlEncode(bytes);
}

export function randomNumericCode(digits = 6): string {
  const bytes = new Uint8Array(digits);
  crypto.getRandomValues(bytes);
  let code = "";
  for (let index = 0; index < digits; index += 1) {
    code += String((bytes[index] ?? 0) % 10);
  }
  return code;
}

export function base64UrlEncode(bytes: Uint8Array): string {
  let binary = "";
  for (let index = 0; index < bytes.length; index += 1) {
    binary += String.fromCharCode(bytes[index] as number);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function base64UrlDecode(value: string): Uint8Array {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - (normalized.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

export function toHex(bytes: Uint8Array): string {
  let hex = "";
  for (let index = 0; index < bytes.length; index += 1) {
    hex += (bytes[index] as number).toString(16).padStart(2, "0");
  }
  return hex;
}

export async function sha256Hex(input: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(input));
  return toHex(new Uint8Array(digest));
}

export async function sha256Base64Url(bytes: Uint8Array): Promise<string> {
  // Copy into a fresh buffer so the digest never sees a SharedArrayBuffer view.
  const digest = await crypto.subtle.digest("SHA-256", new Uint8Array(bytes));
  return base64UrlEncode(new Uint8Array(digest));
}

export async function hmacSha256(secret: string, data: string): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("HMAC", key, encoder.encode(data));
  return new Uint8Array(signature);
}

/** Constant-time comparison; never short-circuits on the first differing byte. */
export function timingSafeEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let mismatch = 0;
  for (let index = 0; index < a.length; index += 1) {
    mismatch |= (a[index] as number) ^ (b[index] as number);
  }
  return mismatch === 0;
}

/** Keyed hash used so raw IP addresses never reach storage or logs. */
export async function keyedFingerprint(secret: string, value: string): Promise<string> {
  const digest = await hmacSha256(secret, value);
  return toHex(digest).slice(0, 32);
}
