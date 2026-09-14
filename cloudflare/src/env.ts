/**
 * Worker bindings and runtime configuration.
 *
 * Secrets are supplied through `wrangler secret put` (production) or
 * `.dev.vars` (local development). They are never committed and never have a
 * hard-coded fallback: when a required production value is missing the Worker
 * fails closed with HTTP 503 `server_misconfigured` instead of silently using
 * a fabricated default.
 */

export interface Env {
  DB: D1Database;
  EVIDENCE: R2Bucket;
  SAFETY_HUB: DurableObjectNamespace;

  /** HMAC secret for access tokens. REQUIRED (>= 32 chars). Secret binding. */
  AUTH_SECRET?: string;

  ENVIRONMENT?: string;
  TOKEN_ISSUER?: string;
  ACCESS_TOKEN_TTL_SECONDS?: string;
  REFRESH_TOKEN_TTL_SECONDS?: string;
  CORS_ALLOWED_ORIGINS?: string;
  MAX_EVIDENCE_BYTES?: string;
  EVIDENCE_RETENTION_DAYS?: string;
  LOCATION_RETENTION_DAYS?: string;
}

export class ConfigError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ConfigError";
  }
}

export interface AppConfig {
  environment: string;
  authSecret: string;
  issuer: string;
  accessTokenTtlSeconds: number;
  refreshTokenTtlSeconds: number;
  corsAllowedOrigins: string[];
  maxEvidenceBytes: number;
  evidenceRetentionDays: number;
  locationRetentionDays: number;
}

const MIN_SECRET_LENGTH = 32;
const DEFAULT_ACCESS_TTL_SECONDS = 900; // 15 minutes
const DEFAULT_REFRESH_TTL_SECONDS = 2_592_000; // 30 days

function readPositiveInt(raw: string | undefined, fallback: number, max: number): number {
  if (!raw) return fallback;
  const parsed = Number.parseInt(raw, 10);
  if (!Number.isFinite(parsed) || parsed <= 0 || parsed > max) return fallback;
  return parsed;
}

/**
 * Resolves configuration for a request. Throws `ConfigError` when a required
 * production value is absent so the caller can answer 503 instead of
 * pretending the request succeeded.
 */
export function resolveConfig(env: Env): AppConfig {
  const authSecret = env.AUTH_SECRET;
  if (!authSecret || authSecret.trim().length < MIN_SECRET_LENGTH) {
    throw new ConfigError(
      "AUTH_SECRET is missing or shorter than 32 characters. Set it with `wrangler secret put AUTH_SECRET`.",
    );
  }

  return {
    environment: env.ENVIRONMENT ?? "production",
    authSecret,
    issuer: env.TOKEN_ISSUER ?? "guardian-cloudflare",
    accessTokenTtlSeconds: readPositiveInt(env.ACCESS_TOKEN_TTL_SECONDS, DEFAULT_ACCESS_TTL_SECONDS, 3600),
    refreshTokenTtlSeconds: readPositiveInt(env.REFRESH_TOKEN_TTL_SECONDS, DEFAULT_REFRESH_TTL_SECONDS, 31_536_000),
    corsAllowedOrigins: (env.CORS_ALLOWED_ORIGINS ?? "")
      .split(",")
      .map((origin) => origin.trim())
      .filter((origin) => origin.length > 0),
    maxEvidenceBytes: readPositiveInt(env.MAX_EVIDENCE_BYTES, 5_242_880, 26_214_400),
    evidenceRetentionDays: readPositiveInt(env.EVIDENCE_RETENTION_DAYS, 90, 3650),
    locationRetentionDays: readPositiveInt(env.LOCATION_RETENTION_DAYS, 30, 3650),
  };
}

/** True when the Worker has the minimum production configuration it needs. */
export function isConfigured(env: Env): boolean {
  try {
    resolveConfig(env);
    return true;
  } catch {
    return false;
  }
}
