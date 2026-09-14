/** Request validation. Every rejection produces a safe, explicit error. */

import { badRequest } from "./errors";

const EMAIL_PATTERN = /^[^\s@]+@[^\s@.]+(\.[^\s@.]+)+$/;
const PHONE_PATTERN = /^[0-9+()\-.\s]{3,32}$/;

export function asRecord(value: unknown): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw badRequest("invalid_body", "Request body must be a JSON object.");
  }
  return value as Record<string, unknown>;
}

export interface StringOptions {
  min?: number;
  max?: number;
  pattern?: RegExp;
  patternMessage?: string;
  trim?: boolean;
}

export function requireString(value: unknown, field: string, options: StringOptions = {}): string {
  if (typeof value !== "string") throw badRequest("invalid_field", `'${field}' must be a string.`);
  const result = options.trim === false ? value : value.trim();
  const min = options.min ?? 1;
  const max = options.max ?? 256;
  if (result.length < min) throw badRequest("invalid_field", `'${field}' is too short.`);
  if (result.length > max) throw badRequest("invalid_field", `'${field}' is too long.`);
  if (options.pattern && !options.pattern.test(result)) {
    throw badRequest("invalid_field", options.patternMessage ?? `'${field}' is not valid.`);
  }
  return result;
}

export function optionalString(value: unknown, field: string, options: StringOptions = {}): string | undefined {
  if (value === undefined || value === null) return undefined;
  return requireString(value, field, options);
}

export function requireEmail(value: unknown): string {
  const email = requireString(value, "email", { min: 3, max: 320 }).toLowerCase();
  if (!EMAIL_PATTERN.test(email)) throw badRequest("invalid_field", "'email' is not a valid email address.");
  return email;
}

export function requirePassword(value: unknown): string {
  // Upper bound protects the PBKDF2 work factor from being abused.
  const password = requireString(value, "password", { min: 10, max: 200, trim: false });
  if (!/[A-Za-z]/.test(password) || !/[0-9]/.test(password)) {
    throw badRequest("invalid_field", "'password' must contain at least one letter and one number.");
  }
  return password;
}

export function requirePhone(value: unknown, field = "phone"): string {
  return requireString(value, field, {
    min: 3,
    max: 32,
    pattern: PHONE_PATTERN,
    patternMessage: `'${field}' must be a valid phone number.`,
  });
}

export interface NumberOptions {
  min?: number;
  max?: number;
}

export function requireNumber(value: unknown, field: string, options: NumberOptions = {}): number {
  const parsed = typeof value === "number" ? value : Number(value);
  if (!Number.isFinite(parsed)) throw badRequest("invalid_field", `'${field}' must be a number.`);
  if (options.min !== undefined && parsed < options.min) throw badRequest("invalid_field", `'${field}' is below the minimum.`);
  if (options.max !== undefined && parsed > options.max) throw badRequest("invalid_field", `'${field}' exceeds the maximum.`);
  return parsed;
}

export function optionalNumber(value: unknown, field: string, options: NumberOptions = {}): number | undefined {
  if (value === undefined || value === null) return undefined;
  return requireNumber(value, field, options);
}

export function requireLatitude(value: unknown): number {
  return requireNumber(value, "latitude", { min: -90, max: 90 });
}

export function requireLongitude(value: unknown): number {
  return requireNumber(value, "longitude", { min: -180, max: 180 });
}

export function optionalBoolean(value: unknown, field: string): boolean | undefined {
  if (value === undefined || value === null) return undefined;
  if (typeof value !== "boolean") throw badRequest("invalid_field", `'${field}' must be a boolean.`);
  return value;
}

export function requireEnum<T extends string>(value: unknown, field: string, allowed: readonly T[]): T {
  if (typeof value !== "string" || !allowed.includes(value as T)) {
    throw badRequest("invalid_field", `'${field}' must be one of: ${allowed.join(", ")}.`);
  }
  return value as T;
}

export function optionalEnum<T extends string>(value: unknown, field: string, allowed: readonly T[]): T | undefined {
  if (value === undefined || value === null) return undefined;
  return requireEnum(value, field, allowed);
}

/**
 * Reduces a client-supplied file name to a safe, inert token: directory
 * components, control characters, quotes, shell metacharacters and repeated
 * separators are all removed. The result is used only for display and the
 * `content-disposition` header — object keys are always generated server-side.
 */
export function sanitizeFileName(value: string): string {
  const base = value.split(/[\\/]/).pop() ?? "evidence.bin";
  const cleaned = base
    .replace(/[^A-Za-z0-9._-]/g, "_")
    .replace(/\.{2,}/g, ".")
    .replace(/_{2,}/g, "_")
    .replace(/^[._-]+/, "")
    .slice(-96);
  return cleaned.length > 0 ? cleaned : "evidence.bin";
}
