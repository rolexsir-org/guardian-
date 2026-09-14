/**
 * Realtime wire protocol shared by the Durable Object, the Worker and the
 * Android client. Frames are JSON; sequence numbers allow a reconnecting client
 * to detect a gap and request a full resync instead of silently corrupting
 * its safety state.
 */

export type RealtimeEventKind =
  | "SOS_CREATED"
  | "SOS_UPDATED"
  | "PRESENCE"
  | "LOCATION_SHARE"
  | "SAFETY_EVENT_CREATED"
  | "FAMILY_MESSAGE"
  | "CHECKIN_UPDATED";

export interface RealtimeEvent {
  kind: RealtimeEventKind;
  at: number;
  data: Record<string, unknown>;
}

export interface PresenceEntry {
  userId: string;
  status: "ONLINE" | "OFFLINE";
  lastSeenAt: number;
  batteryLevel: number | null;
}

export interface StoredEvent {
  seq: number;
  event: RealtimeEvent;
}

export interface ServerHello {
  type: "hello";
  scope: string;
  seq: number;
  serverTime: number;
  presence: PresenceEntry[];
  activeSos: Record<string, unknown>[];
}

export interface ServerEventMessage {
  type: "event";
  seq: number;
  event: RealtimeEvent;
}

export interface ServerPong {
  type: "pong";
  serverTime: number;
}

export interface ServerSyncGap {
  type: "sync.gap";
  seq: number;
  serverTime: number;
  presence: PresenceEntry[];
  activeSos: Record<string, unknown>[];
}

export interface ServerErrorMessage {
  type: "error";
  code: string;
}

export type ServerMessage = ServerHello | ServerEventMessage | ServerPong | ServerSyncGap | ServerErrorMessage;

export type ClientMessage = { type: "ping" } | { type: "sync"; since: number };

export const SCOPE_PREFIXES = ["family:", "geo:"] as const;

export function familyScope(groupId: string): string {
  return `family:${groupId}`;
}

export function geoScope(cell: string): string {
  return `geo:${cell}`;
}

export function parseClientMessage(raw: string): ClientMessage | null {
  try {
    const parsed = JSON.parse(raw) as { type?: unknown; since?: unknown };
    if (parsed.type === "ping") return { type: "ping" };
    if (parsed.type === "sync" && typeof parsed.since === "number" && Number.isFinite(parsed.since)) {
      return { type: "sync", since: Math.max(0, Math.floor(parsed.since)) };
    }
    return null;
  } catch {
    return null;
  }
}

export function parsePresence(raw: string): PresenceEntry | null {
  try {
    const parsed = JSON.parse(raw) as Partial<PresenceEntry>;
    if (typeof parsed.userId !== "string" || parsed.userId.length === 0) return null;
    if (parsed.status !== "ONLINE" && parsed.status !== "OFFLINE") return null;
    return {
      userId: parsed.userId,
      status: parsed.status,
      lastSeenAt: typeof parsed.lastSeenAt === "number" ? parsed.lastSeenAt : Date.now(),
      batteryLevel: typeof parsed.batteryLevel === "number" ? parsed.batteryLevel : null,
    };
  } catch {
    return null;
  }
}
