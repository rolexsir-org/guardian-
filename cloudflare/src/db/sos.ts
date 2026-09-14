import type { Env } from "../env";

export const SOS_STATUSES = ["ACTIVE", "ACKNOWLEDGED", "RESOLVED", "CANCELLED"] as const;
export type SosStatus = (typeof SOS_STATUSES)[number];

export const SOS_TRIGGER_SOURCES = [
  "BUTTON",
  "SHAKE",
  "PIN",
  "FALL",
  "CRASH",
  "VOICE",
  "HARDWARE",
  "REMOTE",
] as const;
export type SosTriggerSource = (typeof SOS_TRIGGER_SOURCES)[number];

export interface SosRow {
  id: string;
  user_id: string;
  client_event_id: string;
  trigger_source: string;
  status: SosStatus;
  latitude: number | null;
  longitude: number | null;
  accuracy_m: number | null;
  battery_level: number | null;
  network_status: string | null;
  device_info: string | null;
  occurred_at: number;
  received_at: number;
  acknowledged_at: number | null;
  resolved_at: number | null;
  resolution_note: string | null;
}

export interface PublicSosEvent {
  id: string;
  userId: string;
  clientEventId: string;
  triggerSource: string;
  status: SosStatus;
  latitude: number | null;
  longitude: number | null;
  accuracyM: number | null;
  batteryLevel: number | null;
  networkStatus: string | null;
  deviceInfo: string | null;
  occurredAt: number;
  receivedAt: number;
  acknowledgedAt: number | null;
  resolvedAt: number | null;
  resolutionNote: string | null;
}

export function toPublicSosEvent(row: SosRow): PublicSosEvent {
  return {
    id: row.id,
    userId: row.user_id,
    clientEventId: row.client_event_id,
    triggerSource: row.trigger_source,
    status: row.status,
    latitude: row.latitude,
    longitude: row.longitude,
    accuracyM: row.accuracy_m,
    batteryLevel: row.battery_level,
    networkStatus: row.network_status,
    deviceInfo: row.device_info,
    occurredAt: row.occurred_at,
    receivedAt: row.received_at,
    acknowledgedAt: row.acknowledged_at,
    resolvedAt: row.resolved_at,
    resolutionNote: row.resolution_note,
  };
}

const COLUMNS = `id, user_id, client_event_id, trigger_source, status, latitude, longitude, accuracy_m,
  battery_level, network_status, device_info, occurred_at, received_at, acknowledged_at, resolved_at, resolution_note`;

export interface CreateSosInput {
  id: string;
  userId: string;
  clientEventId: string;
  triggerSource: string;
  latitude?: number | undefined;
  longitude?: number | undefined;
  accuracyM?: number | undefined;
  batteryLevel?: number | undefined;
  networkStatus?: string | undefined;
  deviceInfo?: string | undefined;
  occurredAt: number;
  now: number;
}

export interface CreateSosResult {
  event: SosRow;
  /** false when the same client event id was already stored (offline replay). */
  created: boolean;
}

/**
 * Idempotent SOS creation. An offline device may replay the same
 * `clientEventId` many times; the unique index guarantees exactly one event.
 */
export async function createSosEvent(env: Env, input: CreateSosInput): Promise<CreateSosResult> {
  const insert = await env.DB.prepare(
    `INSERT INTO sos_events (
       id, user_id, client_event_id, trigger_source, status, latitude, longitude, accuracy_m,
       battery_level, network_status, device_info, occurred_at, received_at
     ) VALUES (?1, ?2, ?3, ?4, 'ACTIVE', ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)
     ON CONFLICT (user_id, client_event_id) DO NOTHING`,
  )
    .bind(
      input.id,
      input.userId,
      input.clientEventId,
      input.triggerSource,
      input.latitude ?? null,
      input.longitude ?? null,
      input.accuracyM ?? null,
      input.batteryLevel ?? null,
      input.networkStatus ?? null,
      input.deviceInfo ?? null,
      input.occurredAt,
      input.now,
    )
    .run();

  const created = (insert.meta.changes ?? 0) > 0;

  const row = await env.DB.prepare(`SELECT ${COLUMNS} FROM sos_events WHERE user_id = ?1 AND client_event_id = ?2`)
    .bind(input.userId, input.clientEventId)
    .first<SosRow>();
  if (!row) throw new Error("sos event insert did not persist");

  return { event: row, created };
}

export async function getSosEvent(env: Env, sosId: string): Promise<SosRow | null> {
  return await env.DB.prepare(`SELECT ${COLUMNS} FROM sos_events WHERE id = ?1`).bind(sosId).first<SosRow>();
}

export async function listSosEvents(env: Env, userId: string, limit = 50): Promise<SosRow[]> {
  const rows = await env.DB.prepare(
    `SELECT ${COLUMNS} FROM sos_events WHERE user_id = ?1 ORDER BY occurred_at DESC LIMIT ?2`,
  )
    .bind(userId, Math.min(Math.max(limit, 1), 200))
    .all<SosRow>();
  return rows.results ?? [];
}

export async function updateSosStatus(
  env: Env,
  sosId: string,
  status: SosStatus,
  input: { now: number; note?: string | null; actorUserId: string },
): Promise<SosRow | null> {
  const existing = await getSosEvent(env, sosId);
  if (!existing) return null;

  const acknowledgedAt = status === "ACKNOWLEDGED" ? input.now : existing.acknowledged_at;
  const resolvedAt = status === "RESOLVED" || status === "CANCELLED" ? input.now : existing.resolved_at;

  await env.DB.prepare(
    `UPDATE sos_events
       SET status = ?1, acknowledged_at = ?2, resolved_at = ?3, resolution_note = COALESCE(?4, resolution_note)
     WHERE id = ?5`,
  )
    .bind(status, acknowledgedAt, resolvedAt, input.note ?? null, sosId)
    .run();

  return await getSosEvent(env, sosId);
}

/** Active SOS events of every member of the given group (family dashboard). */
export async function activeSosForGroup(env: Env, groupId: string): Promise<SosRow[]> {
  const rows = await env.DB.prepare(
    `SELECT ${COLUMNS.split(",")
      .map((column) => `s.${column.trim()}`)
      .join(", ")}
     FROM sos_events s
     JOIN family_members m ON m.user_id = s.user_id AND m.removed_at IS NULL
     WHERE m.group_id = ?1 AND s.status IN ('ACTIVE', 'ACKNOWLEDGED')
     ORDER BY s.occurred_at DESC
     LIMIT 50`,
  )
    .bind(groupId)
    .all<SosRow>();
  return rows.results ?? [];
}
