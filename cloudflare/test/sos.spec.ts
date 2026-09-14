import { describe, expect, it } from "vitest";
import { api, errorCode, registerUser, testEnv } from "./helpers";

interface SosEnvelope {
  data: {
    event: { id: string; status: string; userId: string; clientEventId: string; triggerSource: string };
    duplicate: boolean;
    acknowledgedAt: number;
    realtime: { family: boolean; nearby: boolean };
  };
}

async function createGroupWithMembers(): Promise<{ ownerToken: string; ownerId: string; memberToken: string; memberId: string; groupId: string }> {
  const owner = await registerUser("sos-owner");
  const member = await registerUser("sos-member");
  const group = await api<{ data: { group: { id: string } } }>("/v1/family/groups", {
    token: owner.accessToken,
    body: { name: "Emergency Family" },
  });
  const groupId = group.body.data.group.id;

  const invite = await api<{ data: { invite: { code: string } } }>(`/v1/family/groups/${groupId}/invites`, {
    token: owner.accessToken,
    body: { role: "GUARDIAN" },
  });
  await api("/v1/family/join", { token: member.accessToken, body: { inviteCode: invite.body.data.invite.code } });

  return {
    ownerToken: owner.accessToken,
    ownerId: owner.userId,
    memberToken: member.accessToken,
    memberId: member.userId,
    groupId,
  };
}

describe("emergency (SOS) events", () => {
  it("stores an SOS with location and acknowledges synchronisation", async () => {
    const { ownerToken, ownerId } = await createGroupWithMembers();

    const response = await api<SosEnvelope>("/v1/sos", {
      token: ownerToken,
      body: {
        clientEventId: "evt-1111-2222-3333",
        triggerSource: "BUTTON",
        latitude: 37.7749,
        longitude: -122.4194,
        accuracyM: 8,
        batteryLevel: 42,
        networkStatus: "OFFLINE",
        deviceInfo: "Pixel 8",
        occurredAt: Date.now() - 60_000,
      },
    });

    expect(response.status).toBe(201);
    expect(response.body.data.duplicate).toBe(false);
    expect(response.body.data.event.status).toBe("ACTIVE");
    expect(response.body.data.event.userId).toBe(ownerId);
    expect(response.body.data.acknowledgedAt).toBeGreaterThan(0);
    expect(response.body.data.realtime.family).toBe(true);

    const stored = await testEnv.DB.prepare("SELECT COUNT(*) AS total FROM sos_events WHERE user_id = ?1")
      .bind(ownerId)
      .first<{ total: number }>();
    expect(stored?.total).toBe(1);
  });

  it("is idempotent for replayed offline events", async () => {
    const { ownerToken, ownerId } = await createGroupWithMembers();
    const payload = {
      clientEventId: "offline-replay-0001",
      triggerSource: "SHAKE",
      latitude: 37.7749,
      longitude: -122.4194,
      occurredAt: Date.now() - 120_000,
    };

    const first = await api<SosEnvelope>("/v1/sos", { token: ownerToken, body: payload });
    const second = await api<SosEnvelope>("/v1/sos", { token: ownerToken, body: payload });
    const third = await api<SosEnvelope>("/v1/sos", { token: ownerToken, body: payload });

    expect(first.status).toBe(201);
    expect(first.body.data.duplicate).toBe(false);
    expect(second.status).toBe(200);
    expect(second.body.data.duplicate).toBe(true);
    expect(third.body.data.event.id).toBe(first.body.data.event.id);

    const stored = await testEnv.DB.prepare("SELECT COUNT(*) AS total FROM sos_events WHERE user_id = ?1")
      .bind(ownerId)
      .first<{ total: number }>();
    expect(stored?.total).toBe(1);
  });

  it("accepts an SOS without coordinates (location unavailable)", async () => {
    const { ownerToken } = await createGroupWithMembers();
    const response = await api<SosEnvelope>("/v1/sos", {
      token: ownerToken,
      body: { clientEventId: "no-location-0001", triggerSource: "PIN", occurredAt: Date.now() },
    });
    expect(response.status).toBe(201);
    expect(response.body.data.event.status).toBe("ACTIVE");
  });

  it("rejects incomplete or impossible locations", async () => {
    const { ownerToken } = await createGroupWithMembers();

    const partial = await api("/v1/sos", {
      token: ownerToken,
      body: { clientEventId: "partial-location-01", triggerSource: "BUTTON", latitude: 37.7749 },
    });
    expect(partial.status).toBe(400);
    expect(errorCode(partial.body)).toBe("incomplete_location");

    const invalid = await api("/v1/sos", {
      token: ownerToken,
      body: { clientEventId: "invalid-location-01", triggerSource: "BUTTON", latitude: 120, longitude: 10 },
    });
    expect(invalid.status).toBe(400);

    const future = await api("/v1/sos", {
      token: ownerToken,
      body: {
        clientEventId: "future-timestamp-1",
        triggerSource: "BUTTON",
        occurredAt: Date.now() + 60 * 60 * 1000,
      },
    });
    expect(future.status).toBe(400);
  });

  it("exposes an active SOS to family guardians and never to strangers", async () => {
    const { ownerToken, ownerId, memberToken, groupId } = await createGroupWithMembers();
    const stranger = await registerUser("sos-stranger");

    const sos = await api<SosEnvelope>("/v1/sos", {
      token: ownerToken,
      body: { clientEventId: "family-visible-0001", triggerSource: "VOICE", occurredAt: Date.now() },
    });
    const sosId = sos.body.data.event.id;

    const guardianView = await api<{ data: { event: { id: string } } }>(`/v1/sos/${sosId}`, { token: memberToken });
    expect(guardianView.status).toBe(200);
    expect(guardianView.body.data.event.id).toBe(sosId);

    const strangerView = await api(`/v1/sos/${sosId}`, { token: stranger.accessToken });
    expect(strangerView.status).toBe(403);
    expect(errorCode(strangerView.body)).toBe("sos_access_denied");

    const familyActive = await api<{ data: { events: { id: string }[] } }>(
      `/v1/family/groups/${groupId}/sos/active`,
      { token: memberToken },
    );
    expect(familyActive.status).toBe(200);
    expect(familyActive.body.data.events.map((event) => event.id)).toContain(sosId);

    const strangerActive = await api(`/v1/family/groups/${groupId}/sos/active`, { token: stranger.accessToken });
    expect(strangerActive.status).toBe(403);

    const acknowledged = await api<{ data: { event: { status: string } } }>(`/v1/sos/${sosId}/acknowledge`, {
      method: "POST",
      token: memberToken,
      body: {},
    });
    expect(acknowledged.status).toBe(200);
    expect(acknowledged.body.data.event.status).toBe("ACKNOWLEDGED");

    const resolved = await api<{ data: { event: { status: string } } }>(`/v1/sos/${sosId}/resolve`, {
      method: "POST",
      token: ownerToken,
      body: { note: "Person is safe" },
    });
    expect(resolved.status).toBe(200);
    expect(resolved.body.data.event.status).toBe("RESOLVED");

    const activeAfterResolution = await api<{ data: { events: unknown[] } }>(
      `/v1/family/groups/${groupId}/sos/active`,
      { token: memberToken },
    );
    expect(activeAfterResolution.body.data.events).toHaveLength(0);

    expect(ownerId).toBeTruthy();
  });

  it("prevents an outsider from resolving somebody else's emergency", async () => {
    const { ownerToken } = await createGroupWithMembers();
    const stranger = await registerUser("sos-stranger-2");

    const sos = await api<SosEnvelope>("/v1/sos", {
      token: ownerToken,
      body: { clientEventId: "resolve-guard-0001", triggerSource: "BUTTON", occurredAt: Date.now() },
    });

    const attempt = await api(`/v1/sos/${sos.body.data.event.id}/resolve`, {
      method: "POST",
      token: stranger.accessToken,
      body: {},
    });
    expect(attempt.status).toBe(403);
  });

  it("limits emergency endpoint abuse per account", async () => {
    const { ownerToken } = await createGroupWithMembers();
    let limited = false;

    for (let index = 0; index < 35; index += 1) {
      const response = await api("/v1/sos", {
        token: ownerToken,
        body: { clientEventId: `flood-event-${String(index).padStart(4, "0")}`, triggerSource: "BUTTON", occurredAt: Date.now() },
      });
      if (response.status === 429) {
        limited = true;
        expect(errorCode(response.body)).toBe("rate_limited");
        break;
      }
      expect([200, 201]).toContain(response.status);
    }

    expect(limited).toBe(true);
  });

  it("shares the emergency location with the family for 24 hours", async () => {
    const { ownerToken, groupId, memberToken } = await createGroupWithMembers();

    await api("/v1/sos", {
      token: ownerToken,
      body: {
        clientEventId: "location-share-0001",
        triggerSource: "FALL",
        latitude: 37.7749,
        longitude: -122.4194,
        occurredAt: Date.now(),
      },
    });

    const locations = await api<{ data: { locations: { latitude: number; source: string }[] } }>(
      `/v1/family/groups/${groupId}/locations`,
      { token: memberToken },
    );
    expect(locations.status).toBe(200);
    const shared = locations.body.data.locations[0];
    expect(shared?.source).toBe("SOS");
    expect(shared?.latitude).toBeCloseTo(37.7749, 4);
  });
});
