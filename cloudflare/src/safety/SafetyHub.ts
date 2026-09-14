/**
 * SafetyHub — the only Durable Object in the Guardian backend.
 *
 * One instance per realtime scope:
 *   * `family:<groupId>` — family coordination: presence, active SOS state,
 *     location shares, family messages.
 *   * `geo:<cell>`       — community hazard broadcast for a geohash cell.
 *
 * Responsibilities:
 *   * WebSocket fan-out (no polling).
 *   * Durable, sequenced event buffer so a client that reconnects can replay
 *     what it missed (`sync`) or fall back to a full snapshot (`sync.gap`).
 *   * Presence tracking with automatic expiry (replaces the legacy
 *     `.info/connected` + `onDisconnect`).
 *   * Active-SOS state that survives DO eviction, so an interrupted connection
 *     can never lose an emergency.
 *
 * All requests reach this object through the Worker, which validates the caller
 * and only then supplies the trusted `x-guardian-*` headers. Direct public
 * access is impossible: Durable Objects are not routable from the internet.
 */

import type { Env } from "../env";
import {
  parseClientMessage,
  parsePresence,
  type PresenceEntry,
  type RealtimeEvent,
  type ServerMessage,
  type StoredEvent,
} from "../realtime/protocol";

const MAX_BUFFERED_EVENTS = 200;
const MAX_EVENT_BYTES = 8_192;
const PRESENCE_STALE_MS = 120_000;
const ALARM_INTERVAL_MS = 60_000;

interface ActiveSosRecord {
  sosId: string;
  userId: string;
  triggerSource: string;
  status: string;
  latitude: number | null;
  longitude: number | null;
  occurredAt: number;
}

export class SafetyHub implements DurableObject {
  private readonly state: DurableObjectState;
  private readonly env: Env;

  private scope = "";
  private seq = 0;
  private events: StoredEvent[] = [];
  private presence: Record<string, PresenceEntry> = {};
  private activeSos: Record<string, ActiveSosRecord> = {};

  constructor(state: DurableObjectState, env: Env) {
    this.state = state;
    this.env = env;
    this.state.blockConcurrencyWhile(async () => {
      const [scope, seq, events, presence, activeSos] = await Promise.all([
        this.state.storage.get<string>("scope"),
        this.state.storage.get<number>("seq"),
        this.state.storage.get<StoredEvent[]>("events"),
        this.state.storage.get<Record<string, PresenceEntry>>("presence"),
        this.state.storage.get<Record<string, ActiveSosRecord>>("activeSos"),
      ]);
      this.scope = scope ?? "";
      this.seq = seq ?? 0;
      this.events = events ?? [];
      this.presence = presence ?? {};
      this.activeSos = activeSos ?? {};
    });
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const suppliedScope = request.headers.get("x-guardian-scope") ?? "";
    if (suppliedScope) {
      this.scope = suppliedScope;
    }
    if (!this.scope && url.pathname !== "/snapshot") {
      return this.json({ error: "scope_required" }, 400);
    }
    if (url.pathname !== "/snapshot" && this.scope) {
      await this.state.storage.put("scope", this.scope);
    }

    switch (url.pathname) {
      case "/connect":
        return await this.handleConnect(request);
      case "/publish":
        return await this.handlePublish(request);
      case "/presence":
        return await this.handlePresence(request);
      case "/snapshot":
        return this.json({ scope: this.scope, seq: this.seq, presence: Object.values(this.presence), activeSos: Object.values(this.activeSos) });
      case "/sos/clear":
        return await this.handleSosClear(request);
      default:
        return this.json({ error: "not_found" }, 404);
    }
  }

  private json(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
      status,
      headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
    });
  }

  private async handleConnect(request: Request): Promise<Response> {
    if (request.headers.get("upgrade")?.toLowerCase() !== "websocket") {
      return this.json({ error: "upgrade_required" }, 426);
    }
    const userId = request.headers.get("x-guardian-user-id");
    if (!userId) return this.json({ error: "user_required" }, 401);

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];

    // Hibernatable WebSocket: the object can be evicted and revived without
    // dropping the connection. Tags are used to find sockets per user.
    this.state.acceptWebSocket(server, [`user:${userId}`]);

    const now = Date.now();
    this.presence[userId] = {
      userId,
      status: "ONLINE",
      lastSeenAt: now,
      batteryLevel: this.presence[userId]?.batteryLevel ?? null,
    };
    await this.persistPresence();
    await this.broadcast({ kind: "PRESENCE", at: now, data: { ...this.presence[userId] } }, false);

    const hello: ServerMessage = {
      type: "hello",
      scope: this.scope,
      seq: this.seq,
      serverTime: now,
      presence: Object.values(this.presence),
      activeSos: Object.values(this.activeSos) as unknown as Record<string, unknown>[],
    };
    server.send(JSON.stringify(hello));

    await this.scheduleAlarm();
    return new Response(null, { status: 101, webSocket: client });
  }

  private async handlePublish(request: Request): Promise<Response> {
    const raw = await request.text();
    if (raw.length > MAX_EVENT_BYTES) return this.json({ error: "event_too_large" }, 413);

    let payload: { event?: RealtimeEvent; trackSos?: ActiveSosRecord; clearSosUserId?: string };
    try {
      payload = JSON.parse(raw) as typeof payload;
    } catch {
      return this.json({ error: "invalid_json" }, 400);
    }

    if (payload.trackSos) {
      this.activeSos[payload.trackSos.userId] = payload.trackSos;
      await this.state.storage.put("activeSos", this.activeSos);
    }
    if (payload.clearSosUserId) {
      delete this.activeSos[payload.clearSosUserId];
      await this.state.storage.put("activeSos", this.activeSos);
    }
    if (!payload.event) {
      return this.json({ ok: true });
    }

    const seq = await this.broadcast(payload.event, true);
    return this.json({ ok: true, seq });
  }

  private async handlePresence(request: Request): Promise<Response> {
    const entry = parsePresence(await request.text());
    if (!entry) return this.json({ error: "invalid_presence" }, 400);

    this.presence[entry.userId] = entry;
    await this.persistPresence();
    await this.broadcast({ kind: "PRESENCE", at: entry.lastSeenAt, data: { ...entry } }, false);
    await this.scheduleAlarm();
    return this.json({ ok: true });
  }

  private async handleSosClear(request: Request): Promise<Response> {
    let body: { userId?: string };
    try {
      body = JSON.parse(await request.text()) as { userId?: string };
    } catch {
      return this.json({ error: "invalid_json" }, 400);
    }
    if (body.userId) {
      delete this.activeSos[body.userId];
      await this.state.storage.put("activeSos", this.activeSos);
    }
    return this.json({ ok: true });
  }

  private async persistPresence(): Promise<void> {
    await this.state.storage.put("presence", this.presence);
  }

  /** Appends to the replay buffer and fans the event out to every socket. */
  private async broadcast(event: RealtimeEvent, buffer: boolean): Promise<number> {
    let seq = this.seq;
    if (buffer) {
      seq = this.seq + 1;
      this.seq = seq;
      this.events.push({ seq, event });
      if (this.events.length > MAX_BUFFERED_EVENTS) {
        this.events = this.events.slice(this.events.length - MAX_BUFFERED_EVENTS);
      }
      await this.state.storage.put({ seq, events: this.events });
    }

    const message: ServerMessage = { type: "event", seq, event };
    const encoded = JSON.stringify(message);
    for (const socket of this.state.getWebSockets()) {
      try {
        socket.send(encoded);
      } catch {
        // A failed socket is cleaned up by webSocketClose/webSocketError.
      }
    }
    return seq;
  }

  private async scheduleAlarm(): Promise<void> {
    const existing = await this.state.storage.getAlarm();
    if (existing === null) {
      await this.state.storage.setAlarm(Date.now() + ALARM_INTERVAL_MS);
    }
  }

  async alarm(): Promise<void> {
    const now = Date.now();
    let changed = false;

    for (const [userId, entry] of Object.entries(this.presence)) {
      if (entry.status === "ONLINE" && now - entry.lastSeenAt > PRESENCE_STALE_MS) {
        this.presence[userId] = { ...entry, status: "OFFLINE" };
        changed = true;
        await this.state.storage.put("presence", this.presence);
        await this.broadcast({ kind: "PRESENCE", at: now, data: { ...this.presence[userId] } }, false);
        await this.updateD1Presence(userId, "OFFLINE");
      }
    }

    if (changed) {
      await this.persistPresence();
    }

    // Keep rescheduling only while somebody is connected, otherwise let the
    // object hibernate (no cost, no polling).
    if (this.state.getWebSockets().length > 0) {
      await this.state.storage.setAlarm(now + ALARM_INTERVAL_MS);
    }
  }

  private async updateD1Presence(userId: string, status: "ONLINE" | "OFFLINE"): Promise<void> {
    try {
      await this.env.DB.prepare(
        `UPDATE presence SET status = ?1, last_seen_at = ?2 WHERE user_id = ?3 AND status <> ?1`,
      )
        .bind(status, Date.now(), userId)
        .run();
    } catch (error) {
      console.error(JSON.stringify({ level: "warn", event: "presence_mirror_failed", message: String(error) }));
    }
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    if (typeof message !== "string") return;
    const parsed = parseClientMessage(message);
    if (!parsed) {
      ws.send(JSON.stringify({ type: "error", code: "invalid_message" } satisfies ServerMessage));
      return;
    }

    const now = Date.now();
    const userId = this.userIdForSocket(ws);

    if (parsed.type === "ping") {
      if (userId) {
        const entry = this.presence[userId];
        if (!entry || now - entry.lastSeenAt > 30_000) {
          this.presence[userId] = {
            userId,
            status: "ONLINE",
            lastSeenAt: now,
            batteryLevel: entry?.batteryLevel ?? null,
          };
          await this.persistPresence();
          await this.updateD1Presence(userId, "ONLINE");
          await this.scheduleAlarm();
        } else {
          this.presence[userId] = { ...entry, lastSeenAt: now };
          await this.persistPresence();
        }
      }
      ws.send(JSON.stringify({ type: "pong", serverTime: now } satisfies ServerMessage));
      return;
    }

    // `sync`: replay everything the client missed. If the requested position is
    // older than the buffer, hand out a full snapshot instead of a partial view.
    const oldestSeq = this.events.length > 0 ? (this.events[0] as StoredEvent).seq : this.seq + 1;
    if (parsed.since < oldestSeq - 1) {
      ws.send(
        JSON.stringify({
          type: "sync.gap",
          seq: this.seq,
          serverTime: now,
          presence: Object.values(this.presence),
          activeSos: Object.values(this.activeSos) as unknown as Record<string, unknown>[],
        } satisfies ServerMessage),
      );
      return;
    }

    for (const stored of this.events) {
      if (stored.seq > parsed.since) {
        ws.send(JSON.stringify({ type: "event", seq: stored.seq, event: stored.event } satisfies ServerMessage));
      }
    }
  }

  async webSocketClose(ws: WebSocket, _code: number, _reason: string, _wasClean: boolean): Promise<void> {
    const userId = this.userIdForSocket(ws);
    try {
      ws.close();
    } catch {
      // Already closing.
    }
    if (!userId) return;

    const stillConnected = this.state
      .getWebSockets(`user:${userId}`)
      .some((socket) => socket !== ws && socket.readyState === 1);
    if (stillConnected) return;

    const now = Date.now();
    this.presence[userId] = {
      userId,
      status: "OFFLINE",
      lastSeenAt: now,
      batteryLevel: this.presence[userId]?.batteryLevel ?? null,
    };
    await this.persistPresence();
    await this.broadcast({ kind: "PRESENCE", at: now, data: { ...this.presence[userId] } }, false);
    await this.updateD1Presence(userId, "OFFLINE");
  }

  async webSocketError(ws: WebSocket, error: unknown): Promise<void> {
    console.error(JSON.stringify({ level: "warn", event: "websocket_error", message: String(error) }));
    await this.webSocketClose(ws, 1011, "error", false);
  }

  private userIdForSocket(ws: WebSocket): string | null {
    for (const tag of this.state.getTags(ws)) {
      if (tag.startsWith("user:")) return tag.slice("user:".length);
    }
    return null;
  }
}
