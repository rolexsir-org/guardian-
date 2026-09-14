import type { Env } from "../env";

export type PresenceStatus = "ONLINE" | "OFFLINE";

export interface PublicPresence {
  userId: string;
  status: PresenceStatus;
  lastSeenAt: number;
  batteryLevel: number | null;
  /** true when the heartbeat is older than the freshness window. */
  stale: boolean;
}

const FRESH_WINDOW_MS = 2 * 60 * 1000;

export async function upsertPresence(
  env: Env,
  input: { userId: string; status: PresenceStatus; batteryLevel?: number | undefined; source?: string; now: number },
): Promise<void> {
  await env.DB.prepare(
    `INSERT INTO presence (user_id, status, last_seen_at, battery_level, source)
     VALUES (?1, ?2, ?3, ?4, ?5)
     ON CONFLICT (user_id) DO UPDATE SET
       status = ?2,
       last_seen_at = ?3,
       battery_level = COALESCE(?4, presence.battery_level),
       source = ?5`,
  )
    .bind(input.userId, input.status, input.now, input.batteryLevel ?? null, input.source ?? "APP")
    .run();
}

export async function presenceForUsers(env: Env, userIds: string[], now: number): Promise<PublicPresence[]> {
  if (userIds.length === 0) return [];
  const placeholders = userIds.map((_, index) => `?${index + 1}`).join(", ");
  const rows = await env.DB.prepare(
    `SELECT user_id, status, last_seen_at, battery_level FROM presence WHERE user_id IN (${placeholders})`,
  )
    .bind(...userIds)
    .all<{ user_id: string; status: PresenceStatus; last_seen_at: number; battery_level: number | null }>();

  return (rows.results ?? []).map((row) => ({
    userId: row.user_id,
    status: row.status,
    lastSeenAt: row.last_seen_at,
    batteryLevel: row.battery_level,
    stale: now - row.last_seen_at > FRESH_WINDOW_MS,
  }));
}

/** Marks heartbeats older than the freshness window as offline. */
export async function markStalePresenceOffline(env: Env, now: number): Promise<number> {
  const result = await env.DB.prepare(
    `UPDATE presence SET status = 'OFFLINE' WHERE status = 'ONLINE' AND last_seen_at < ?1`,
  )
    .bind(now - FRESH_WINDOW_MS)
    .run();
  return result.meta.changes ?? 0;
}
