/**
 * Session store.
 *
 * A session is the durable, revocable link between a user and one device.
 * Rotation + replay detection implement RFC 6819 / OAuth 2.1 style
 * refresh-token rotation.
 */

import type { AppConfig, Env } from "../env";
import { randomId } from "../util/crypto";
import { forbidden, unauthorized } from "../util/errors";
import { generateRefreshToken, hashRefreshToken, signAccessToken, verifyAccessToken } from "./tokens";

export interface AuthContext {
  userId: string;
  sessionId: string;
}

export interface DeviceInfo {
  deviceId?: string | undefined;
  label?: string | undefined;
  appVersion?: string | undefined;
  platform?: string | undefined;
  userAgent?: string | undefined;
}

export interface IssuedSession {
  userId: string;
  sessionId: string;
  accessToken: string;
  refreshToken: string;
  accessExpiresAt: number;
  refreshExpiresAt: number;
}

interface SessionRow {
  id: string;
  user_id: string;
  refresh_token_hash: string;
  refresh_expires_at: number;
  access_expires_at: number;
  revoked_at: number | null;
  last_used_at: number;
  created_at: number;
  device_id: string | null;
  device_label: string | null;
}

export interface SessionSummary {
  id: string;
  deviceId: string | null;
  deviceLabel: string | null;
  createdAt: number;
  lastUsedAt: number;
  accessExpiresAt: number;
  refreshExpiresAt: number;
  revokedAt: number | null;
}

async function issueTokens(
  config: AppConfig,
  userId: string,
  sessionId: string,
  now: number,
): Promise<{ accessToken: string; accessExpiresAt: number }> {
  const { token, expiresAt } = await signAccessToken(config.authSecret, config.issuer, {
    sub: userId,
    sid: sessionId,
    issuedAt: now,
    ttlSeconds: config.accessTokenTtlSeconds,
  });
  return { accessToken: token, accessExpiresAt: expiresAt };
}

async function upsertDevice(env: Env, userId: string, device: DeviceInfo, now: number): Promise<void> {
  if (!device.deviceId) return;
  await env.DB.prepare(
    `INSERT INTO devices (id, user_id, platform, label, app_version, created_at, last_seen_at)
     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?6)
     ON CONFLICT (user_id, id) DO UPDATE SET
       last_seen_at = ?6,
       platform = ?3,
       app_version = COALESCE(?5, app_version),
       label = COALESCE(?4, label)`,
  )
    .bind(
      device.deviceId,
      userId,
      device.platform ?? "android",
      device.label ?? null,
      device.appVersion ?? null,
      now,
    )
    .run();
}

export async function createSession(
  env: Env,
  config: AppConfig,
  userId: string,
  device: DeviceInfo,
  now: number = Date.now(),
): Promise<IssuedSession> {
  const sessionId = randomId();
  const refreshToken = generateRefreshToken();
  const refreshTokenHash = await hashRefreshToken(refreshToken);
  const refreshExpiresAt = now + config.refreshTokenTtlSeconds * 1000;
  const { accessToken, accessExpiresAt } = await issueTokens(config, userId, sessionId, now);

  await env.DB.prepare(
    `INSERT INTO sessions (
       id, user_id, refresh_token_hash, device_id, device_label,
       created_at, last_used_at, access_expires_at, refresh_expires_at
     ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?6, ?7, ?8)`,
  )
    .bind(
      sessionId,
      userId,
      refreshTokenHash,
      device.deviceId ?? null,
      device.label ?? device.userAgent ?? null,
      now,
      accessExpiresAt,
      refreshExpiresAt,
    )
    .run();

  await upsertDevice(env, userId, device, now);

  return { userId, sessionId, accessToken, refreshToken, accessExpiresAt, refreshExpiresAt };
}

/**
 * Rotates a refresh token. Presenting a token that was already rotated (or a
 * token belonging to a revoked session) is treated as replay: the session is
 * revoked and 401 is returned.
 */
export async function rotateSession(
  env: Env,
  config: AppConfig,
  presentedRefreshToken: string,
  device: DeviceInfo,
  now: number = Date.now(),
): Promise<IssuedSession> {
  const presentedHash = await hashRefreshToken(presentedRefreshToken);

  const session = await env.DB.prepare(
    `SELECT id, user_id, refresh_token_hash, refresh_expires_at, access_expires_at, revoked_at,
            last_used_at, created_at, device_id, device_label
     FROM sessions WHERE refresh_token_hash = ?1`,
  )
    .bind(presentedHash)
    .first<SessionRow>();

  if (!session) {
    const replay = await env.DB.prepare(
      `SELECT session_id, user_id FROM refresh_token_history WHERE token_hash = ?1`,
    )
      .bind(presentedHash)
      .first<{ session_id: string; user_id: string }>();

    if (replay) {
      await revokeSession(env, replay.session_id, "refresh_token_reuse", now);
      await recordSessionEvent(env, replay.user_id, "auth.refresh.reuse_detected", "DENIED", now);
      throw unauthorized("refresh_token_reuse", "Session has been revoked. Please sign in again.");
    }
    throw unauthorized("invalid_refresh_token", "Refresh token is invalid.");
  }

  if (session.revoked_at !== null) {
    throw unauthorized("session_revoked", "Session has been revoked. Please sign in again.");
  }
  if (session.refresh_expires_at <= now) {
    await revokeSession(env, session.id, "refresh_token_expired", now);
    throw unauthorized("refresh_token_expired", "Session has expired. Please sign in again.");
  }

  const refreshToken = generateRefreshToken();
  const refreshTokenHash = await hashRefreshToken(refreshToken);
  const { accessToken, accessExpiresAt } = await issueTokens(config, session.user_id, session.id, now);

  // The absolute refresh expiry is preserved: rotating never extends the
  // lifetime of a session indefinitely.
  await env.DB.batch([
    env.DB.prepare(
      `INSERT INTO refresh_token_history (token_hash, session_id, user_id, rotated_at)
       VALUES (?1, ?2, ?3, ?4)`,
    ).bind(presentedHash, session.id, session.user_id, now),
    env.DB.prepare(
      `UPDATE sessions
         SET refresh_token_hash = ?1, rotated_at = ?2, last_used_at = ?2, access_expires_at = ?3
       WHERE id = ?4`,
    ).bind(refreshTokenHash, now, accessExpiresAt, session.id),
  ]);

  await upsertDevice(env, session.user_id, device, now);

  return {
    userId: session.user_id,
    sessionId: session.id,
    accessToken,
    refreshToken,
    accessExpiresAt,
    refreshExpiresAt: session.refresh_expires_at,
  };
}

export async function revokeSession(env: Env, sessionId: string, reason: string, now: number = Date.now()): Promise<void> {
  await env.DB.prepare(
    `UPDATE sessions SET revoked_at = ?1, revoked_reason = ?2 WHERE id = ?3 AND revoked_at IS NULL`,
  )
    .bind(now, reason, sessionId)
    .run();
}

export async function revokeAllSessions(
  env: Env,
  userId: string,
  reason: string,
  now: number = Date.now(),
  exceptSessionId?: string,
): Promise<number> {
  const result = await env.DB.prepare(
    `UPDATE sessions SET revoked_at = ?1, revoked_reason = ?2
     WHERE user_id = ?3 AND revoked_at IS NULL AND (?4 IS NULL OR id <> ?4)`,
  )
    .bind(now, reason, userId, exceptSessionId ?? null)
    .run();
  return result.meta.changes ?? 0;
}

export async function listSessions(env: Env, userId: string, now: number = Date.now()): Promise<SessionSummary[]> {
  const rows = await env.DB.prepare(
    `SELECT id, device_id, device_label, created_at, last_used_at, access_expires_at, refresh_expires_at, revoked_at
     FROM sessions
     WHERE user_id = ?1 AND revoked_at IS NULL AND refresh_expires_at > ?2
     ORDER BY last_used_at DESC
     LIMIT 50`,
  )
    .bind(userId, now)
    .all<{
      id: string;
      device_id: string | null;
      device_label: string | null;
      created_at: number;
      last_used_at: number;
      access_expires_at: number;
      refresh_expires_at: number;
      revoked_at: number | null;
    }>();

  return (rows.results ?? []).map((row) => ({
    id: row.id,
    deviceId: row.device_id,
    deviceLabel: row.device_label,
    createdAt: row.created_at,
    lastUsedAt: row.last_used_at,
    accessExpiresAt: row.access_expires_at,
    refreshExpiresAt: row.refresh_expires_at,
    revokedAt: row.revoked_at,
  }));
}

async function recordSessionEvent(
  env: Env,
  userId: string,
  action: string,
  outcome: "SUCCESS" | "FAILURE" | "DENIED",
  now: number,
): Promise<void> {
  await env.DB.prepare(
    `INSERT INTO audit_logs (id, user_id, action, outcome, ip_hash, user_agent, details, created_at)
     VALUES (?1, ?2, ?3, ?4, NULL, NULL, NULL, ?5)`,
  )
    .bind(randomId(), userId, action, outcome, now)
    .run();
}

/**
 * Validates the `Authorization: Bearer <jwt>` header and the session it refers
 * to. Revocation is enforced server-side on every request, so logging out (or
 * an administrator revoking a session) takes effect immediately.
 */
export async function authenticateRequest(
  env: Env,
  config: AppConfig,
  request: Request,
  now: number = Date.now(),
): Promise<AuthContext> {
  const header = request.headers.get("authorization");
  if (!header) throw unauthorized();
  const match = /^Bearer\s+(.+)$/i.exec(header.trim());
  if (!match) throw unauthorized("invalid_token", "Authorization header must use the Bearer scheme.");

  const claims = await verifyAccessToken(config.authSecret, config.issuer, (match[1] as string).trim(), now);

  const row = await env.DB.prepare(
    `SELECT s.id AS session_id, s.user_id AS user_id, s.revoked_at AS revoked_at, s.last_used_at AS last_used_at,
            u.disabled_at AS disabled_at
     FROM sessions s
     JOIN users u ON u.id = s.user_id
     WHERE s.id = ?1`,
  )
    .bind(claims.sid)
    .first<{ session_id: string; user_id: string; revoked_at: number | null; last_used_at: number; disabled_at: number | null }>();

  if (!row || row.user_id !== claims.sub) throw unauthorized("session_not_found", "Session no longer exists.");
  if (row.revoked_at !== null) throw unauthorized("session_revoked", "Session has been revoked.");
  if (row.disabled_at !== null) throw forbidden("account_disabled", "Account is not available.");

  // Write throttling: `last_used_at` is refreshed at most once a minute to keep
  // request latency low without losing device tracking accuracy.
  if (now - row.last_used_at > 60_000) {
    await env.DB.prepare(`UPDATE sessions SET last_used_at = ?1 WHERE id = ?2`).bind(now, row.session_id).run();
  }

  return { userId: row.user_id, sessionId: row.session_id };
}
