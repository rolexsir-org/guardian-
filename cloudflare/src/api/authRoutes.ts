/**
 * Authentication endpoints.
 *
 * * Registration and login return a short lived access token plus a rotating
 *   refresh token. No fake tokens, no client-trusted identities: the user id
 *   always comes from the server-side database.
 * * Failures are deliberately generic (`invalid_credentials`) so the API does
 *   not leak whether an address is registered, and password verification runs
 *   whether or not the account exists (constant-ish timing).
 */

import type { Env } from "../env";
import { keyedFingerprint, randomId } from "../util/crypto";
import { badRequest, conflict, unauthorized } from "../util/errors";
import { asRecord, optionalString, requireEmail, requirePassword, requireString } from "../util/validate";
import { enforceRateLimit, RATE_LIMITS, type RateLimitRule } from "../util/ratelimit";
import { dummyPasswordHash, hashPassword, verifyPassword } from "../auth/passwords";
import {
  createSession,
  listSessions,
  revokeAllSessions,
  revokeSession,
  rotateSession,
  type DeviceInfo,
} from "../auth/sessions";
import { findUserByEmail, findUserById, insertUser, toPublicUser } from "../db/users";
import { recordAudit } from "../db/audit";
import { upsertPresence } from "../db/presence";
import { updatePresenceInScopes } from "../safety/broadcast";
import { dataResponse, emptyResponse, readJsonBody, type RequestContext, type Router } from "../http";

interface SessionPayload {
  sessionId: string;
  accessToken: string;
  refreshToken: string;
  accessExpiresAt: number;
  refreshExpiresAt: number;
  tokenType: "Bearer";
}

function sessionPayload(session: {
  sessionId: string;
  accessToken: string;
  refreshToken: string;
  accessExpiresAt: number;
  refreshExpiresAt: number;
}): SessionPayload {
  return {
    sessionId: session.sessionId,
    accessToken: session.accessToken,
    refreshToken: session.refreshToken,
    accessExpiresAt: session.accessExpiresAt,
    refreshExpiresAt: session.refreshExpiresAt,
    tokenType: "Bearer",
  };
}

function deviceFrom(context: RequestContext, body: Record<string, unknown>): DeviceInfo {
  const headerDeviceId = context.request.headers.get("x-device-id") ?? undefined;
  return {
    deviceId: optionalString(body.deviceId, "deviceId", { max: 128 }) ?? headerDeviceId,
    label: optionalString(body.deviceLabel, "deviceLabel", { max: 80 }),
    appVersion: optionalString(body.appVersion, "appVersion", { max: 32 }),
    platform: optionalString(body.platform, "platform", { max: 32 }) ?? "android",
    userAgent: context.request.headers.get("user-agent") ?? undefined,
  };
}

async function throttle(env: Env, bucket: string, rule: RateLimitRule): Promise<void> {
  await enforceRateLimit(env.DB, bucket, rule);
}

export function registerAuthRoutes(router: Router): void {
  router.post("/v1/auth/register", async (context) => {
    const { env, request, config } = context;
    const body = asRecord(await readJsonBody(request));
    const email = requireEmail(body.email);
    const password = requirePassword(body.password);
    const displayName = requireString(body.displayName, "displayName", { min: 1, max: 80 });
    const phone = optionalString(body.phone, "phone", { max: 32 });
    const locale = optionalString(body.locale, "locale", { max: 16 });

    const emailBucket = await keyedFingerprint(config.authSecret, email);
    await throttle(env, `register:ip:${context.clientIp ?? "unknown"}`, RATE_LIMITS.registerPerIp);
    await throttle(env, `register:email:${emailBucket}`, RATE_LIMITS.registerPerEmail);

    const existing = await findUserByEmail(env, email);
    if (existing) {
      await recordAudit(env, config, {
        userId: existing.id,
        action: "auth.register",
        outcome: "DENIED",
        clientIp: context.clientIp,
        userAgent: request.headers.get("user-agent"),
        details: "duplicate email",
        now: Date.now(),
      });
      throw conflict("email_unavailable", "That email address cannot be used.");
    }

    const now = Date.now();
    const passwordHash = await hashPassword(password);
    const userId = randomId();

    try {
      await insertUser(env, { id: userId, email, passwordHash, displayName, phone, locale, now });
    } catch (error) {
      if (String(error).includes("UNIQUE")) {
        throw conflict("email_unavailable", "That email address cannot be used.");
      }
      throw error;
    }

    const session = await createSession(env, config, userId, deviceFrom(context, body), now);
    await upsertPresence(env, { userId, status: "ONLINE", source: "APP", now });
    await updatePresenceInScopes(env, userId, "ONLINE", null, now);

    const user = await findUserById(env, userId);
    await recordAudit(env, config, {
      userId,
      action: "auth.register",
      outcome: "SUCCESS",
      clientIp: context.clientIp,
      userAgent: request.headers.get("user-agent"),
      details: null,
      now,
    });

    if (!user) throw badRequest("registration_failed", "Account could not be created.");
    return dataResponse({ user: toPublicUser(user), session: sessionPayload(session) }, 201);
  });

  router.post("/v1/auth/login", async (context) => {
    const { env, request, config } = context;
    const body = asRecord(await readJsonBody(request));
    const email = requireEmail(body.email);
    const password = requireString(body.password, "password", { min: 1, max: 200, trim: false });

    const emailBucket = await keyedFingerprint(config.authSecret, email);
    await throttle(env, `login:ip:${context.clientIp ?? "unknown"}`, RATE_LIMITS.loginPerIp);
    await throttle(env, `login:email:${emailBucket}`, RATE_LIMITS.loginPerEmail);

    const now = Date.now();
    const user = await findUserByEmail(env, email);

    // Always perform a PBKDF2 verification so response timing does not reveal
    // whether the account exists.
    const storedHash = user?.password_hash ?? (await dummyPasswordHash());
    const passwordMatches = await verifyPassword(password, storedHash);

    if (!user || !passwordMatches || user.disabled_at !== null) {
      await recordAudit(env, config, {
        userId: user?.id ?? null,
        action: "auth.login",
        outcome: "FAILURE",
        clientIp: context.clientIp,
        userAgent: request.headers.get("user-agent"),
        details: user ? "invalid credentials" : "unknown account",
        now,
      });
      throw unauthorized("invalid_credentials", "Email or password is incorrect.");
    }

    const session = await createSession(env, config, user.id, deviceFrom(context, body), now);
    await upsertPresence(env, { userId: user.id, status: "ONLINE", source: "APP", now });
    await updatePresenceInScopes(env, user.id, "ONLINE", null, now);

    await recordAudit(env, config, {
      userId: user.id,
      action: "auth.login",
      outcome: "SUCCESS",
      clientIp: context.clientIp,
      userAgent: request.headers.get("user-agent"),
      details: null,
      now,
    });

    return dataResponse({ user: toPublicUser(user), session: sessionPayload(session) });
  });

  router.post("/v1/auth/refresh", async (context) => {
    const { env, request, config } = context;
    const body = asRecord(await readJsonBody(request));
    const refreshToken = requireString(body.refreshToken, "refreshToken", { min: 20, max: 512, trim: false });

    await throttle(env, `refresh:ip:${context.clientIp ?? "unknown"}`, RATE_LIMITS.refreshPerIp);

    const session = await rotateSession(env, config, refreshToken, deviceFrom(context, body));
    const user = await findUserById(env, session.userId);
    if (!user) throw unauthorized("invalid_refresh_token", "Refresh token is invalid.");

    return dataResponse({ user: toPublicUser(user), session: sessionPayload(session) });
  });

  router.post(
    "/v1/auth/logout",
    async (context) => {
      const { env, request, config } = context;
      const auth = context.auth;
      if (!auth) throw unauthorized();

      const now = Date.now();
      await revokeSession(env, auth.sessionId, "logout", now);
      await recordAudit(env, config, {
        userId: auth.userId,
        action: "auth.logout",
        outcome: "SUCCESS",
        clientIp: context.clientIp,
        userAgent: request.headers.get("user-agent"),
        details: null,
        now,
      });
      return emptyResponse(204);
    },
    { auth: true },
  );

  router.post(
    "/v1/auth/logout-all",
    async (context) => {
      const { env, request, config } = context;
      const auth = context.auth;
      if (!auth) throw unauthorized();

      const now = Date.now();
      const revoked = await revokeAllSessions(env, auth.userId, "logout_all", now);
      await upsertPresence(env, { userId: auth.userId, status: "OFFLINE", source: "APP", now });
      await updatePresenceInScopes(env, auth.userId, "OFFLINE", null, now);
      await recordAudit(env, config, {
        userId: auth.userId,
        action: "auth.logout_all",
        outcome: "SUCCESS",
        clientIp: context.clientIp,
        userAgent: request.headers.get("user-agent"),
        details: `revoked ${revoked} sessions`,
        now,
      });
      return dataResponse({ revokedSessions: revoked });
    },
    { auth: true },
  );

  router.get(
    "/v1/auth/me",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const user = await findUserById(context.env, auth.userId);
      if (!user) throw unauthorized("session_not_found", "Session no longer exists.");
      return dataResponse({ user: toPublicUser(user), session: { id: auth.sessionId } });
    },
    { auth: true },
  );

  router.get(
    "/v1/auth/sessions",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const sessions = await listSessions(context.env, auth.userId);
      return dataResponse({
        sessions: sessions.map((session) => ({
          id: session.id,
          deviceId: session.deviceId,
          deviceLabel: session.deviceLabel,
          createdAt: session.createdAt,
          lastUsedAt: session.lastUsedAt,
          accessExpiresAt: session.accessExpiresAt,
          refreshExpiresAt: session.refreshExpiresAt,
          current: session.id === auth.sessionId,
        })),
      });
    },
    { auth: true },
  );

  router.delete(
    "/v1/auth/sessions/:sessionId",
    async (context) => {
      const { env, request, config } = context;
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const sessionId = requireString(context.params.sessionId, "sessionId", { max: 64, trim: false });

      const owned = (await listSessions(env, auth.userId)).some((session) => session.id === sessionId);
      if (!owned) throw badRequest("session_not_found", "Session not found or already revoked.");

      await revokeSession(env, sessionId, "revoked_by_user", Date.now());
      await recordAudit(env, config, {
        userId: auth.userId,
        action: "auth.session_revoke",
        outcome: "SUCCESS",
        clientIp: context.clientIp,
        userAgent: request.headers.get("user-agent"),
        details: "device session revoked",
        now: Date.now(),
      });
      return emptyResponse(204);
    },
    { auth: true },
  );
}
