import { describe, expect, it } from "vitest";
import { signAccessToken } from "../src/auth/tokens";
import { api, errorCode, loginUser, registerUser, testEnv, TEST_ISSUER, TEST_SECRET, uniqueIp } from "./helpers";

interface MeEnvelope {
  data: { user: { id: string; email: string; displayName: string }; session: { id: string } };
}

describe("authentication", () => {
  it("registers a real account with server-issued tokens", async () => {
    const session = await registerUser("register");

    // A stable server-generated identity, not a client-supplied one.
    expect(session.userId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
    expect(session.accessToken.split(".")).toHaveLength(3);
    expect(session.refreshToken.length).toBeGreaterThanOrEqual(32);
    expect(session.accessExpiresAt).toBeGreaterThan(Date.now());

    const me = await api<MeEnvelope>("/v1/auth/me", { token: session.accessToken });
    expect(me.status).toBe(200);
    expect(me.body.data.user.id).toBe(session.userId);
    expect(me.body.data.session.id).toBe(session.sessionId);
  });

  it("stores only PBKDF2 hashes, never the plaintext password", async () => {
    const session = await registerUser("hashing");
    const row = await testEnv.DB.prepare("SELECT password_hash FROM users WHERE id = ?1")
      .bind(session.userId)
      .first<{ password_hash: string }>();

    expect(row).not.toBeNull();
    expect(row?.password_hash.startsWith("pbkdf2-sha256$")).toBe(true);
    expect(row?.password_hash).not.toContain(session.password);
  });

  it("rejects duplicate registration without revealing account state", async () => {
    const first = await registerUser("dupe");
    const second = await api("/v1/auth/register", {
      body: { email: first.email, password: "AnotherPass123", displayName: "Copy Cat" },
      ip: uniqueIp("dupe-second"),
    });
    expect(second.status).toBe(409);
    expect(errorCode(second.body)).toBe("email_unavailable");
  });

  it("returns a generic error for a wrong password and for an unknown account", async () => {
    const session = await registerUser("credentials");

    const wrongPassword = await api("/v1/auth/login", {
      body: { email: session.email, password: "WrongPassword123" },
      ip: uniqueIp("wrong-password"),
    });
    expect(wrongPassword.status).toBe(401);
    expect(errorCode(wrongPassword.body)).toBe("invalid_credentials");

    const unknown = await api("/v1/auth/login", {
      body: { email: "nobody@guardian.test", password: "WrongPassword123" },
      ip: uniqueIp("unknown-account"),
    });
    expect(unknown.status).toBe(401);
    expect(errorCode(unknown.body)).toBe("invalid_credentials");
  });

  it("validates registration input", async () => {
    const weak = await api("/v1/auth/register", {
      body: { email: "weak@guardian.test", password: "short", displayName: "Weak" },
      ip: uniqueIp("weak"),
    });
    expect(weak.status).toBe(400);

    const badEmail = await api("/v1/auth/register", {
      body: { email: "not-an-email", password: "GuardianPass123", displayName: "Bad" },
      ip: uniqueIp("bad-email"),
    });
    expect(badEmail.status).toBe(400);
  });

  it("rejects missing, malformed and tampered access tokens", async () => {
    const session = await registerUser("tokens");

    const anonymous = await api("/v1/auth/me");
    expect(anonymous.status).toBe(401);

    const malformed = await api("/v1/auth/me", { token: "not-a-jwt" });
    expect(malformed.status).toBe(401);

    const tampered = await api("/v1/auth/me", { token: `${session.accessToken}tampered` });
    expect(tampered.status).toBe(401);
    expect(errorCode(tampered.body)).toBe("invalid_token");
  });

  it("rejects an expired access token", async () => {
    const session = await registerUser("expiry");
    const now = Date.now();
    const expired = await signAccessToken(TEST_SECRET, TEST_ISSUER, {
      sub: session.userId,
      sid: session.sessionId,
      issuedAt: now - 3_600_000,
      ttlSeconds: 10,
    });

    const response = await api("/v1/auth/me", { token: expired.token });
    expect(response.status).toBe(401);
    expect(errorCode(response.body)).toBe("token_expired");
  });

  it("rotates refresh tokens and detects reuse", async () => {
    const session = await registerUser("rotation");

    const refreshed = await api<{ data: { session: { refreshToken: string; accessToken: string } } }>("/v1/auth/refresh", {
      body: { refreshToken: session.refreshToken },
      ip: uniqueIp("refresh"),
    });
    expect(refreshed.status).toBe(200);
    const rotated = refreshed.body.data.session;
    expect(rotated.refreshToken).not.toBe(session.refreshToken);

    // Replaying the already-rotated token must not mint new credentials.
    const replay = await api("/v1/auth/refresh", {
      body: { refreshToken: session.refreshToken },
      ip: uniqueIp("refresh-replay"),
    });
    expect(replay.status).toBe(401);
    expect(errorCode(replay.body)).toBe("refresh_token_reuse");

    // ...and the whole session is revoked as a theft response.
    const revoked = await api("/v1/auth/me", { token: rotated.accessToken });
    expect(revoked.status).toBe(401);
    expect(errorCode(revoked.body)).toBe("session_revoked");

    const refreshAfterRevoke = await api("/v1/auth/refresh", {
      body: { refreshToken: rotated.refreshToken },
      ip: uniqueIp("refresh-after-revoke"),
    });
    expect(refreshAfterRevoke.status).toBe(401);
  });

  it("rejects an invalid refresh token", async () => {
    const response = await api("/v1/auth/refresh", {
      body: { refreshToken: "a".repeat(64) },
      ip: uniqueIp("invalid-refresh"),
    });
    expect(response.status).toBe(401);
    expect(errorCode(response.body)).toBe("invalid_refresh_token");
  });

  it("revokes the current session on logout", async () => {
    const session = await registerUser("logout");

    const logout = await api("/v1/auth/logout", { method: "POST", token: session.accessToken, body: {} });
    expect(logout.status).toBe(204);

    const afterLogout = await api("/v1/auth/me", { token: session.accessToken });
    expect(afterLogout.status).toBe(401);
    expect(errorCode(afterLogout.body)).toBe("session_revoked");

    const refreshAfterLogout = await api("/v1/auth/refresh", {
      body: { refreshToken: session.refreshToken },
      ip: uniqueIp("logout-refresh"),
    });
    expect(refreshAfterLogout.status).toBe(401);
  });

  it("revokes every device session on logout-all", async () => {
    const first = await registerUser("logout-all");
    const second = await loginUser(first.email, first.password, uniqueIp("logout-all-second"));

    const logoutAll = await api("/v1/auth/logout-all", { method: "POST", token: first.accessToken, body: {} });
    expect(logoutAll.status).toBe(200);

    for (const token of [first.accessToken, second.accessToken]) {
      const response = await api("/v1/auth/me", { token });
      expect(response.status).toBe(401);
    }
  });

  it("lists device sessions and revokes a specific one", async () => {
    const first = await registerUser("session-list");
    const second = await loginUser(first.email, first.password, uniqueIp("session-list-second"));

    const list = await api<{ data: { sessions: { id: string; current: boolean }[] } }>("/v1/auth/sessions", {
      token: first.accessToken,
    });
    expect(list.status).toBe(200);
    expect(list.body.data.sessions.length).toBeGreaterThanOrEqual(2);
    expect(list.body.data.sessions.some((entry) => entry.id === first.sessionId && entry.current)).toBe(true);

    const revoke = await api(`/v1/auth/sessions/${second.sessionId}`, { method: "DELETE", token: first.accessToken });
    expect(revoke.status).toBe(204);

    const secondAfterRevoke = await api("/v1/auth/me", { token: second.accessToken });
    expect(secondAfterRevoke.status).toBe(401);

    // A session that is not owned by the caller can never be revoked.
    const other = await registerUser("session-list-other");
    const idor = await api(`/v1/auth/sessions/${other.sessionId}`, { method: "DELETE", token: first.accessToken });
    expect(idor.status).toBe(400);
    expect(errorCode(idor.body)).toBe("session_not_found");
  });

  it("rate limits repeated login attempts", async () => {
    const session = await registerUser("rate-limit");
    const ip = uniqueIp("rate-limit-login");
    let sawTooMany = false;

    for (let attempt = 0; attempt < 14; attempt += 1) {
      const response = await api("/v1/auth/login", {
        body: { email: session.email, password: "DefinitelyWrong123" },
        ip,
      });
      if (response.status === 429) {
        sawTooMany = true;
        expect(errorCode(response.body)).toBe("rate_limited");
        expect(response.headers.get("retry-after")).not.toBeNull();
        break;
      }
      expect(response.status).toBe(401);
    }

    expect(sawTooMany).toBe(true);
  });

  it("fails closed when AUTH_SECRET is not configured", async () => {
    // The Worker is simulated without its secret binding by calling the handler
    // through a temporary module-level config in a separate worker request is
    // not possible; instead assert the resolver contract directly.
    const { resolveConfig } = await import("../src/env");
    expect(() => resolveConfig({ DB: testEnv.DB, EVIDENCE: testEnv.EVIDENCE, SAFETY_HUB: testEnv.SAFETY_HUB })).toThrow();
    expect(() =>
      resolveConfig({
        DB: testEnv.DB,
        EVIDENCE: testEnv.EVIDENCE,
        SAFETY_HUB: testEnv.SAFETY_HUB,
        AUTH_SECRET: "short",
      }),
    ).toThrow();
  });
});
