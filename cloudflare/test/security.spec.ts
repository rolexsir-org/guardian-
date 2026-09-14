import { describe, expect, it } from "vitest";
import { api, errorCode, registerUser, testEnv } from "./helpers";

const PROTECTED_GET_ROUTES = [
  "/v1/me/profile",
  "/v1/me/emergency-contacts",
  "/v1/auth/me",
  "/v1/auth/sessions",
  "/v1/sos",
  "/v1/evidence",
  "/v1/family/groups",
  "/v1/me/checkins",
];

describe("API security", () => {
  it("requires authentication on every protected route", async () => {
    for (const route of PROTECTED_GET_ROUTES) {
      const response = await api(route);
      expect(response.status, `expected 401 for ${route}`).toBe(401);
      expect(errorCode(response.body)).toBe("unauthenticated");
    }
  });

  it("rejects malformed JSON and oversized bodies", async () => {
    const malformed = await api("/v1/auth/login", { raw: "{not json", headers: { "content-type": "application/json" } });
    expect(malformed.status).toBe(400);
    expect(errorCode(malformed.body)).toBe("invalid_json");

    const oversized = await api("/v1/auth/login", {
      raw: JSON.stringify({ email: "a@b.co", password: "x".repeat(40_000) }),
      headers: { "content-type": "application/json" },
    });
    expect(oversized.status).toBe(413);
    expect(errorCode(oversized.body)).toBe("payload_too_large");

    const empty = await api("/v1/auth/login", { raw: "", headers: { "content-type": "application/json" } });
    expect(empty.status).toBe(400);
  });

  it("reports unknown routes and wrong methods explicitly", async () => {
    // Unknown paths answer 404 whether or not a token is supplied, so the API
    // never reveals which routes exist to an unauthenticated caller.
    const unknownAnonymously = await api("/v1/does-not-exist");
    expect(unknownAnonymously.status).toBe(404);
    expect(errorCode(unknownAnonymously.body)).toBe("not_found");

    const session = await registerUser("routing");
    const notFound = await api("/v1/does-not-exist", { token: session.accessToken });
    expect(notFound.status).toBe(404);
    expect(errorCode(notFound.body)).toBe("not_found");

    const wrongMethod = await api("/v1/sos", { method: "PUT", token: session.accessToken, body: {} });
    expect(wrongMethod.status).toBe(405);
    expect(errorCode(wrongMethod.body)).toBe("method_not_allowed");
  });

  it("blocks cross-account access to emergency contacts (IDOR)", async () => {
    const owner = await registerUser("idor-owner");
    const attacker = await registerUser("idor-attacker");

    const created = await api<{ data: { contact: { id: string } } }>("/v1/me/emergency-contacts", {
      token: owner.accessToken,
      body: { name: "Jane Doe", phone: "+15550100", relationship: "Sister", isVerified: true },
    });
    expect(created.status).toBe(201);
    const contactId = created.body.data.contact.id;

    const attackerList = await api<{ data: { contacts: unknown[] } }>("/v1/me/emergency-contacts", {
      token: attacker.accessToken,
    });
    expect(attackerList.body.data.contacts).toHaveLength(0);

    const attackerPatch = await api(`/v1/me/emergency-contacts/${contactId}`, {
      method: "PATCH",
      token: attacker.accessToken,
      body: { name: "Hijacked" },
    });
    expect(attackerPatch.status).toBe(404);

    const attackerDelete = await api(`/v1/me/emergency-contacts/${contactId}`, {
      method: "DELETE",
      token: attacker.accessToken,
    });
    expect(attackerDelete.status).toBe(404);

    const intact = await api<{ data: { contacts: { name: string }[] } }>("/v1/me/emergency-contacts", {
      token: owner.accessToken,
    });
    expect(intact.body.data.contacts[0]?.name).toBe("Jane Doe");
  });

  it("treats SQL metacharacters as data, never as query structure", async () => {
    const session = await registerUser("sqli");

    // Malicious email: rejected by validation, and no table is dropped.
    const injection = await api("/v1/auth/login", {
      body: { email: "'; DROP TABLE users; --", password: "GuardianPass123" },
      ip: "203.0.113.77",
    });
    expect(injection.status).toBe(400);

    // Malicious payload stored verbatim and returned verbatim.
    const payload = `Robert'); DROP TABLE safety_events;--`;
    const created = await api<{ data: { contact: { name: string } } }>("/v1/me/emergency-contacts", {
      token: session.accessToken,
      body: { name: payload, phone: "+15550111", relationship: "Friend" },
    });
    expect(created.status).toBe(201);
    expect(created.body.data.contact.name).toBe(payload);

    const rows = await testEnv.DB.prepare("SELECT COUNT(*) AS total FROM users").first<{ total: number }>();
    expect(rows?.total).toBeGreaterThan(0);

    // Path traversal style identifiers are simply not found.
    const traversal = await api("/v1/evidence/..%2F..%2Fetc%2Fpasswd", { token: session.accessToken });
    expect([400, 404]).toContain(traversal.status);
  });

  it("denies unknown browser origins and allows native clients", async () => {
    const blocked = await api("/v1/health", { headers: { origin: "https://evil.example" } });
    expect(blocked.status).toBe(403);
    expect(errorCode(blocked.body)).toBe("origin_not_allowed");

    const native = await api("/v1/health"); // no Origin header, as sent by Android
    expect(native.status).toBe(200);

    const preflight = await api("/v1/health", { method: "OPTIONS" });
    expect(preflight.status).toBe(204);
  });

  it("sets defensive response headers and exposes no internal detail", async () => {
    const response = await api("/v1/health");
    expect(response.headers.get("cache-control")).toBe("no-store");
    expect(response.headers.get("x-content-type-options")).toBe("nosniff");
    expect(response.headers.get("x-frame-options")).toBe("DENY");
    expect(response.headers.get("x-request-id")).not.toBeNull();

    const failure = await api("/v1/auth/me", { token: "broken.token.value" });
    const text = JSON.stringify(failure.body);
    expect(text).not.toMatch(/stack|at .*\.ts:|sqlite|SQLITE|D1_ERROR|pbkdf2/i);
  });

  it("never stores raw ip addresses or tokens in the database", async () => {
    const session = await registerUser("no-secrets");
    await api("/v1/auth/me", { token: session.accessToken, ip: "198.51.100.24" });

    const audit = await testEnv.DB.prepare(
      "SELECT ip_hash, user_agent, details FROM audit_logs WHERE user_id = ?1 ORDER BY created_at DESC LIMIT 10",
    )
      .bind(session.userId)
      .all<{ ip_hash: string | null; user_agent: string | null; details: string | null }>();

    for (const row of audit.results ?? []) {
      expect(row.ip_hash === null || /^[0-9a-f]{32}$/.test(row.ip_hash)).toBe(true);
      expect(row.details ?? "").not.toContain(session.accessToken);
      expect(row.details ?? "").not.toContain(session.refreshToken);
      expect(row.details ?? "").not.toContain(session.password);
    }

    const sessions = await testEnv.DB.prepare("SELECT refresh_token_hash FROM sessions WHERE user_id = ?1")
      .bind(session.userId)
      .all<{ refresh_token_hash: string }>();
    for (const row of sessions.results ?? []) {
      expect(row.refresh_token_hash).toMatch(/^[0-9a-f]{64}$/);
      expect(row.refresh_token_hash).not.toBe(session.refreshToken);
    }
  });

  it("enforces foreign keys and unique constraints in the schema", async () => {
    // Orphan rows must be rejected: this proves FK enforcement is active.
    await expect(
      testEnv.DB.prepare(
        "INSERT INTO emergency_contacts (id, user_id, name, phone, relationship, is_verified, priority, created_at, updated_at) VALUES ('x', 'missing-user', 'n', '1', '', 0, 0, 0, 0)",
      ).run(),
    ).rejects.toThrow();

    // The same client event id can only ever produce one SOS row per user.
    const session = await registerUser("fk-check");
    await testEnv.DB.prepare(
      "INSERT INTO sos_events (id, user_id, client_event_id, trigger_source, status, occurred_at, received_at) VALUES ('a', ?1, 'dup-key-0001', 'BUTTON', 'ACTIVE', 0, 0)",
    )
      .bind(session.userId)
      .run();
    await expect(
      testEnv.DB.prepare(
        "INSERT INTO sos_events (id, user_id, client_event_id, trigger_source, status, occurred_at, received_at) VALUES ('b', ?1, 'dup-key-0001', 'BUTTON', 'ACTIVE', 0, 0)",
      )
        .bind(session.userId)
        .run(),
    ).rejects.toThrow();

    // CHECK constraints reject impossible data.
    await expect(
      testEnv.DB.prepare(
        "INSERT INTO safety_events (id, kind, reporter_user_id, title, category, severity, description, latitude, longitude, geohash, occurred_at, created_at, status) VALUES ('c', 'HAZARD', ?1, 't', 'c', 'WARNING', '', 400, 0, 'x', 0, 0, 'ACTIVE')",
      )
        .bind(session.userId)
        .run(),
    ).rejects.toThrow();
  });
});
