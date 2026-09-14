import { SELF } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { api, registerUser, testEnv } from "./helpers";

interface Socket {
  socket: WebSocket;
  messages: Record<string, unknown>[];
  waitFor: (predicate: (messages: Record<string, unknown>[]) => boolean, timeoutMs?: number) => Promise<Record<string, unknown>[]>;
}

function collect(response: Response): Socket {
  const socket = response.webSocket as WebSocket;
  const messages: Record<string, unknown>[] = [];
  let notify: (() => void) | null = null;

  socket.accept();
  socket.addEventListener("message", (event: MessageEvent) => {
    const data = typeof event.data === "string" ? event.data : "";
    try {
      messages.push(JSON.parse(data) as Record<string, unknown>);
    } catch {
      messages.push({ type: "unparsed", raw: data });
    }
    notify?.();
  });

  const waitFor = async (
    predicate: (buffered: Record<string, unknown>[]) => boolean,
    timeoutMs = 4000,
  ): Promise<Record<string, unknown>[]> => {
    const deadline = Date.now() + timeoutMs;
    while (!predicate(messages)) {
      if (Date.now() > deadline) throw new Error(`timed out waiting for realtime message: ${JSON.stringify(messages)}`);
      await new Promise<void>((resolve) => {
        notify = resolve;
        setTimeout(resolve, 50);
      });
      notify = null;
    }
    return messages.slice();
  };

  return { socket, messages, waitFor };
}

async function openSocket(path: string, token?: string): Promise<Response> {
  const headers = new Headers({ upgrade: "websocket", connection: "upgrade" });
  if (token) headers.set("authorization", `Bearer ${token}`);
  return await SELF.fetch(`https://guardian.test${path}`, { headers });
}

async function familyWithMembers(): Promise<{
  groupId: string;
  ownerToken: string;
  memberToken: string;
  ownerId: string;
  memberId: string;
}> {
  const owner = await registerUser("rt-owner");
  const member = await registerUser("rt-member");
  const group = await api<{ data: { group: { id: string } } }>("/v1/family/groups", {
    token: owner.accessToken,
    body: { name: "Realtime Family" },
  });
  const groupId = group.body.data.group.id;
  const invite = await api<{ data: { invite: { code: string } } }>(`/v1/family/groups/${groupId}/invites`, {
    token: owner.accessToken,
    body: { role: "GUARDIAN" },
  });
  await api("/v1/family/join", { token: member.accessToken, body: { inviteCode: invite.body.data.invite.code } });
  return {
    groupId,
    ownerToken: owner.accessToken,
    memberToken: member.accessToken,
    ownerId: owner.userId,
    memberId: member.userId,
  };
}

describe("realtime family channel", () => {
  it("rejects unauthenticated and unauthorised socket attempts", async () => {
    const { groupId } = await familyWithMembers();

    const anonymous = await openSocket(`/v1/realtime/family/${groupId}`);
    expect(anonymous.status).toBe(401);

    const outsider = await registerUser("rt-outsider");
    const outsiderAttempt = await openSocket(`/v1/realtime/family/${groupId}`, outsider.accessToken);
    expect(outsiderAttempt.status).toBe(403);

    const badToken = await openSocket(`/v1/realtime/family/${groupId}`, "not-a-real-token");
    expect(badToken.status).toBe(401);
  });

  it("delivers a hello snapshot, live SOS events and heartbeat pongs", async () => {
    const { groupId, ownerToken } = await familyWithMembers();

    const response = await openSocket(`/v1/realtime/family/${groupId}`, ownerToken);
    expect(response.status).toBe(101);
    const socket = collect(response);

    await socket.waitFor((messages) => messages.some((message) => message.type === "hello"));
    const hello = socket.messages.find((message) => message.type === "hello") as {
      scope: string;
      seq: number;
      presence: { status: string }[];
      activeSos: unknown[];
    };
    expect(hello.scope).toBe(`family:${groupId}`);
    expect(hello.seq).toBeGreaterThanOrEqual(0);
    expect(hello.activeSos).toHaveLength(0);

    // A real emergency written through the REST API is pushed without polling.
    const sos = await api<{ data: { event: { id: string } } }>("/v1/sos", {
      token: ownerToken,
      body: {
        clientEventId: "realtime-sos-0001",
        triggerSource: "BUTTON",
        latitude: 37.7749,
        longitude: -122.4194,
        occurredAt: Date.now(),
      },
    });
    expect(sos.status).toBe(201);

    await socket.waitFor((messages) =>
      messages.some(
        (message) =>
          message.type === "event" &&
          (message.event as { kind?: string } | undefined)?.kind === "SOS_CREATED",
      ),
    );

    socket.socket.send(JSON.stringify({ type: "ping" }));
    await socket.waitFor((messages) => messages.some((message) => message.type === "pong"));

    // Presence for the connected member is exported to the family group.
    const members = await api<{ data: { members: { online: boolean }[] } }>(`/v1/family/groups/${groupId}/members`, {
      token: ownerToken,
    });
    expect(members.body.data.members.some((member) => member.online)).toBe(true);

    socket.socket.close();
  });

  it("replays missed events after a reconnect and recovers active SOS state", async () => {
    const { groupId, ownerToken, memberToken } = await familyWithMembers();

    // Guardian connects briefly and captures the sequence number, then drops.
    const first = collect(await openSocket(`/v1/realtime/family/${groupId}`, memberToken));
    await first.waitFor((messages) => messages.some((message) => message.type === "hello"));
    const firstHello = first.messages.find((message) => message.type === "hello") as { seq: number };
    first.socket.close();

    // Two events happen while the guardian is offline.
    const sos = await api<{ data: { event: { id: string } } }>("/v1/sos", {
      token: ownerToken,
      body: { clientEventId: "reconnect-sos-001", triggerSource: "FALL", occurredAt: Date.now() },
    });
    await api(`/v1/family/groups/${groupId}/messages`, {
      token: ownerToken,
      body: { body: "Guardian is en route", isEmergency: true },
    });

    const second = collect(await openSocket(`/v1/realtime/family/${groupId}`, memberToken));
    await second.waitFor((messages) => messages.some((message) => message.type === "hello"));
    const hello = second.messages.find((message) => message.type === "hello") as {
      seq: number;
      activeSos: { sosId: string }[];
    };
    expect(hello.seq).toBeGreaterThan(firstHello.seq);
    // An interrupted connection never loses the emergency: the snapshot carries it.
    expect(hello.activeSos.map((entry) => entry.sosId)).toContain(sos.body.data.event.id);

    second.socket.send(JSON.stringify({ type: "sync", since: firstHello.seq }));
    await second.waitFor((messages) => {
      const kinds = messages
        .filter((message) => message.type === "event")
        .map((message) => (message.event as { kind: string }).kind);
      return kinds.includes("SOS_CREATED") && kinds.includes("FAMILY_MESSAGE");
    }, 6000);

    const kinds = second.messages
      .filter((message) => message.type === "event")
      .map((message) => (message.event as { kind: string }).kind);
    expect(kinds).toContain("SOS_CREATED");
    expect(kinds).toContain("FAMILY_MESSAGE");

    second.socket.close();
  });

  it("detects a sequence gap and falls back to a full snapshot", async () => {
    const { groupId, ownerToken } = await familyWithMembers();

    // Fill and overflow the replay buffer directly through the hub so the
    // oldest retained sequence moves past the client's position.
    const stub = testEnv.SAFETY_HUB.get(testEnv.SAFETY_HUB.idFromName(`family:${groupId}`));
    for (let index = 0; index < 205; index += 1) {
      const response = await stub.fetch("https://safety-hub.internal/publish", {
        method: "POST",
        headers: { "content-type": "application/json", "x-guardian-scope": `family:${groupId}` },
        body: JSON.stringify({
          event: { kind: "PRESENCE", at: Date.now(), data: { index } },
        }),
      });
      if (!response.ok) throw new Error(`publish failed at ${index}`);
    }

    const socket = collect(await openSocket(`/v1/realtime/family/${groupId}`, ownerToken));
    await socket.waitFor((messages) => messages.some((message) => message.type === "hello"));
    socket.socket.send(JSON.stringify({ type: "sync", since: 1 }));

    await socket.waitFor((messages) => messages.some((message) => message.type === "sync.gap"));
    const gap = socket.messages.find((message) => message.type === "sync.gap") as {
      seq: number;
      presence: unknown[];
      activeSos: unknown[];
    };
    // The snapshot must represent the server's current position...
    expect(gap.seq).toBeGreaterThanOrEqual(205);
    // ...and carry the full recovery state.
    expect(Array.isArray(gap.presence)).toBe(true);
    expect(Array.isArray(gap.activeSos)).toBe(true);

    socket.socket.close();
  });

  it(
    "marks family presence offline when the socket drops",
    async () => {
      const { groupId, ownerToken, memberToken, memberId } = await familyWithMembers();

      // Owner stays connected and observes the member's presence.
      const observer = collect(await openSocket(`/v1/realtime/family/${groupId}`, ownerToken));
      await observer.waitFor((messages) => messages.some((message) => message.type === "hello"));

      const memberSocket = collect(await openSocket(`/v1/realtime/family/${groupId}`, memberToken));
      await memberSocket.waitFor((messages) => messages.some((message) => message.type === "hello"));

      const presenceWithStatus = (status: string) => (messages: Record<string, unknown>[]) =>
        messages.some(
          (message) =>
            message.type === "event" &&
            (message.event as { kind?: string } | undefined)?.kind === "PRESENCE" &&
            (message.event as { data?: { status?: string } }).data?.status === status,
        );

      await observer.waitFor(presenceWithStatus("ONLINE"), 8000);

      memberSocket.socket.close();
      await observer.waitFor(presenceWithStatus("OFFLINE"), 8000);

      // The durable presence row is updated too, so a cold client sees the truth.
      const deadline = Date.now() + 5000;
      let memberOnline = true;
      while (Date.now() < deadline) {
        const members = await api<{ data: { members: { userId: string; online: boolean }[] } }>(
          `/v1/family/groups/${groupId}/members`,
          { token: ownerToken },
        );
        memberOnline = members.body.data.members.find((entry) => entry.userId === memberId)?.online ?? false;
        if (!memberOnline) break;
        await new Promise((resolve) => setTimeout(resolve, 100));
      }
      // The durable presence row (not just the in-memory socket state) is updated.
      expect(memberOnline).toBe(false);

      observer.socket.close();
    },
    20_000,
  );
});
