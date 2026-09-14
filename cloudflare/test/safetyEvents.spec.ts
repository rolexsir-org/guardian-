import { describe, expect, it } from "vitest";
import { api, errorCode, registerUser } from "./helpers";

const SF = { latitude: 37.7749, longitude: -122.4194 };
// Each test uses its own coordinates: tests inside a file share one D1 instance.
const FILTER_AREA = { latitude: 37.9001, longitude: -122.1001 };
const VOTE_AREA = { latitude: 37.8801, longitude: -122.2001 };
const EXPIRY_AREA = { latitude: 37.8601, longitude: -122.3001 };

interface EventEnvelope {
  data: { event: { id: string; kind: string; confirmations: number; status: string; title: string } };
}

describe("community safety events", () => {
  it("creates a hazard report that other clients can discover by radius", async () => {
    const reporter = await registerUser("hazard-reporter");
    const neighbour = await registerUser("hazard-neighbour");
    const faraway = await registerUser("hazard-faraway");

    const created = await api<EventEnvelope>("/v1/safety-events", {
      token: reporter.accessToken,
      body: {
        kind: "HAZARD",
        title: "Road debris blocking lane",
        category: "Hazard",
        severity: "WARNING",
        description: "Large debris on the right lane",
        latitude: SF.latitude,
        longitude: SF.longitude,
      },
    });
    expect(created.status).toBe(201);
    expect(created.body.data.event.kind).toBe("HAZARD");
    expect(created.body.data.event.status).toBe("ACTIVE");

    const near = await api<{ data: { events: { id: string; distanceMeters: number }[] } }>(
      `/v1/safety-events?latitude=${SF.latitude}&longitude=${SF.longitude}&radiusMeters=5000`,
      { token: neighbour.accessToken },
    );
    expect(near.status).toBe(200);
    expect(near.body.data.events.map((event) => event.id)).toContain(created.body.data.event.id);
    expect(near.body.data.events[0]?.distanceMeters).toBeLessThan(100);

    const far = await api<{ data: { events: unknown[] } }>(
      `/v1/safety-events?latitude=${SF.latitude + 3}&longitude=${SF.longitude}&radiusMeters=5000`,
      { token: faraway.accessToken },
    );
    expect(far.body.data.events).toHaveLength(0);
  });

  it("filters by kind and rejects invalid coordinates", async () => {
    const reporter = await registerUser("hazard-kind");

    await api("/v1/safety-events", {
      token: reporter.accessToken,
      body: {
        kind: "INCIDENT",
        title: "Streetlight outage",
        category: "Hazard",
        severity: "INFO",
        description: "Dark stretch of road",
        latitude: FILTER_AREA.latitude,
        longitude: FILTER_AREA.longitude,
      },
    });

    const hazardsOnly = await api<{ data: { events: { kind: string }[] } }>(
      `/v1/safety-events?latitude=${FILTER_AREA.latitude}&longitude=${FILTER_AREA.longitude}&kind=HAZARD`,
      { token: reporter.accessToken },
    );
    expect(hazardsOnly.body.data.events).toHaveLength(0);

    const incidentsOnly = await api<{ data: { events: { kind: string }[] } }>(
      `/v1/safety-events?latitude=${FILTER_AREA.latitude}&longitude=${FILTER_AREA.longitude}&kind=INCIDENT`,
      { token: reporter.accessToken },
    );
    expect(incidentsOnly.body.data.events).toHaveLength(1);

    const invalid = await api("/v1/safety-events", {
      token: reporter.accessToken,
      body: {
        kind: "HAZARD",
        title: "Impossible",
        category: "Hazard",
        severity: "WARNING",
        description: "",
        latitude: 91,
        longitude: 0,
      },
    });
    expect(invalid.status).toBe(400);

    const badSeverity = await api("/v1/safety-events", {
      token: reporter.accessToken,
      body: {
        kind: "HAZARD",
        title: "Bad severity",
        category: "Hazard",
        severity: "CATASTROPHIC",
        description: "",
        latitude: SF.latitude,
        longitude: SF.longitude,
      },
    });
    expect(badSeverity.status).toBe(400);

    // A create without coordinates is rejected...
    const missingLocation = await api("/v1/safety-events", {
      method: "POST",
      token: reporter.accessToken,
      body: { kind: "HAZARD", title: "No coords", category: "Hazard", severity: "WARNING", description: "" },
    });
    expect(missingLocation.status).toBe(400);
    expect(errorCode(missingLocation.body)).toBe("invalid_field");

    // ...and so is a radius query without a position.
    const missingQuery = await api("/v1/safety-events", { token: reporter.accessToken });
    expect(missingQuery.status).toBe(400);
    expect(errorCode(missingQuery.body)).toBe("location_required");
  });

  it("counts confirmations idempotently and blocks self-confirmation", async () => {
    const reporter = await registerUser("hazard-vote");
    const witness = await registerUser("hazard-witness");

    const created = await api<EventEnvelope>("/v1/safety-events", {
      token: reporter.accessToken,
      body: {
        kind: "HAZARD",
        title: "Flooded underpass",
        category: "Weather",
        severity: "CRITICAL",
        description: "Water is knee deep",
        latitude: VOTE_AREA.latitude,
        longitude: VOTE_AREA.longitude,
      },
    });
    const eventId = created.body.data.event.id;

    const selfVote = await api(`/v1/safety-events/${eventId}/votes`, { method: "POST", token: reporter.accessToken, body: {} });
    expect(selfVote.status).toBe(403);

    const firstVote = await api<{ data: { confirmations: number; voted: boolean } }>(
      `/v1/safety-events/${eventId}/votes`,
      { method: "POST", token: witness.accessToken, body: {} },
    );
    expect(firstVote.status).toBe(200);
    expect(firstVote.body.data).toEqual({ confirmations: 1, voted: true });

    const secondVote = await api<{ data: { confirmations: number; voted: boolean } }>(
      `/v1/safety-events/${eventId}/votes`,
      { method: "POST", token: witness.accessToken, body: {} },
    );
    expect(secondVote.body.data).toEqual({ confirmations: 1, voted: false });

    const retracted = await api<{ data: { confirmations: number; voted: boolean } }>(
      `/v1/safety-events/${eventId}/votes`,
      { method: "DELETE", token: witness.accessToken },
    );
    expect(retracted.body.data).toEqual({ confirmations: 0, voted: false });

    const missing = await api(`/v1/safety-events/does-not-exist/votes`, {
      method: "POST",
      token: witness.accessToken,
      body: {},
    });
    expect(missing.status).toBe(404);
  });

  it("keeps archived/expired events out of the live feed", async () => {
    const reporter = await registerUser("hazard-expiry");

    const created = await api<EventEnvelope>("/v1/safety-events", {
      token: reporter.accessToken,
      body: {
        kind: "HAZARD",
        title: "Temporary road closure",
        category: "Hazard",
        severity: "WARNING",
        description: "Parade route",
        latitude: EXPIRY_AREA.latitude,
        longitude: EXPIRY_AREA.longitude,
        ttlMinutes: 5,
      },
    });
    expect(created.status).toBe(201);

    const before = await api<{ data: { events: unknown[] } }>(
      `/v1/safety-events?latitude=${EXPIRY_AREA.latitude}&longitude=${EXPIRY_AREA.longitude}`,
      { token: reporter.accessToken },
    );
    expect(before.body.data.events).toHaveLength(1);

    const { runMaintenance } = await import("../src/maintenance");
    const { resolveConfig } = await import("../src/env");
    const { testEnv } = await import("./helpers");
    await runMaintenance(testEnv, resolveConfig(testEnv), Date.now() + 6 * 60_000);

    const after = await api<{ data: { events: unknown[] } }>(
      `/v1/safety-events?latitude=${EXPIRY_AREA.latitude}&longitude=${EXPIRY_AREA.longitude}`,
      { token: reporter.accessToken },
    );
    expect(after.body.data.events).toHaveLength(0);
  });
});
