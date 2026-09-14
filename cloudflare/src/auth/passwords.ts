/**
 * Password hashing: PBKDF2-HMAC-SHA256, 210 000 iterations, 128-bit random
 * salt, 256-bit derived key. Format:
 *
 *   pbkdf2-sha256$<iterations>$<salt base64url>$<derived key base64url>
 *
 * Plaintext passwords are never logged, stored or echoed back.
 */

import { base64UrlDecode, base64UrlEncode, timingSafeEqual } from "../util/crypto";

const encoder = new TextEncoder();
const PBKDF2_ITERATIONS = 210_000;
const SALT_BYTES = 16;
const DERIVED_KEY_BITS = 256;
const MIN_ITERATIONS = 100_000;
const MAX_ITERATIONS = 1_000_000;

async function derive(password: string, salt: Uint8Array, iterations: number): Promise<Uint8Array> {
  const material = await crypto.subtle.importKey("raw", encoder.encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", salt: new Uint8Array(salt), iterations, hash: "SHA-256" },
    material,
    DERIVED_KEY_BITS,
  );
  return new Uint8Array(bits);
}

export async function hashPassword(password: string): Promise<string> {
  const salt = new Uint8Array(SALT_BYTES);
  crypto.getRandomValues(salt);
  const key = await derive(password, salt, PBKDF2_ITERATIONS);
  return `pbkdf2-sha256$${PBKDF2_ITERATIONS}$${base64UrlEncode(salt)}$${base64UrlEncode(key)}`;
}

export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  const parts = stored.split("$");
  if (parts.length !== 4 || parts[0] !== "pbkdf2-sha256") return false;

  const iterations = Number.parseInt(parts[1] as string, 10);
  if (!Number.isFinite(iterations) || iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) return false;

  let salt: Uint8Array;
  let expected: Uint8Array;
  try {
    salt = base64UrlDecode(parts[2] as string);
    expected = base64UrlDecode(parts[3] as string);
  } catch {
    return false;
  }

  const actual = await derive(password, salt, iterations);
  return timingSafeEqual(actual, expected);
}

/**
 * A valid hash that never matches a real password. Used to keep login response
 * time constant when the account does not exist (avoids user enumeration via
 * timing).
 */
let dummyHashPromise: Promise<string> | null = null;
export function dummyPasswordHash(): Promise<string> {
  dummyHashPromise ??= hashPassword(`guardian-dummy-${Math.random()}`);
  return dummyHashPromise;
}
