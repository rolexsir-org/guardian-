import type { Env } from "../env";

export const LOCATION_SOURCES = ["SOS", "CHECKIN", "SAFE_ZONE", "MANUAL"] as const;
export type LocationSource = (typeof LOCATION_SOURCES)[number];

export interface LocationShareRow {
  id: string;
  user_id: string;
  group_id: string | null;
  latitude: number;
  longitude: number;
  accuracy_m: number | null;
  battery_level: number | null;
  source: LocationSource;
  created_at: number;
  expires_at: number;
}

export interface PublicLocationShare {
  userId: string;
  displayName?: string;
  latitude: number;
  longitude: number;
  accuracyM: number | null;
  batteryLevel: number | null;
  source: LocationSource;
  recordedAt: number;
  expiresAt: number;
  stale: boolean;
}

const COLUMNS = `id, user_id, group_id, latitude, longitude, accuracy_m, battery_level, source, created_at, expires_at`;

export async function recordLocationShare(
  env: Env,
  input: {
    id: string;
    userId: string;
    groupId?: string | null;
    latitude: number;
    longitude: number;
    accuracyM?: number | undefined;
    batteryLevel?: number | undefined;
    source: LocationSource;
    ttlMs: number;
    now: number;
  },
): Promise<LocationShareRow> {
  const expiresAt = input.now + input.ttlMs;
  await env.DB.prepare(
    `INSERT INTO location_shares (id, user_id, group_id, latitude, longitude, accuracy_m, battery_level, source, created_at, expires_at)
     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)`,
  )
    .bind(
      input.id,
      input.userId,
      input.groupId ?? null,
      input.latitude,
      input.longitude,
      input.accuracyM ?? null,
      input.batteryLevel ?? null,
      input.source,
      input.now,
      expiresAt,
    )
    .run();

  const row = await env.DB.prepare(`SELECT ${COLUMNS} FROM location_shares WHERE id = ?1`).bind(input.id).first<LocationShareRow>();
  if (!row) throw new Error("location share insert did not persist");
  return row;
}

/**
 * Latest live share of every member of a group. Only shares inside their
 * retention window are returned; expired rows are never surfaced.
 */
export async function latestSharesForGroup(env: Env, groupId: string, now: number): Promise<PublicLocationShare[]> {
  // Regular shares are scoped to the group they were posted to. Emergency
  // (SOS) shares are visible to every family group the member belongs to, so a
  // guardian always sees the live location of an active emergency.
  const rows = await env.DB.prepare(
    `SELECT l.user_id AS user_id, u.display_name AS display_name, l.latitude AS latitude, l.longitude AS longitude,
            l.accuracy_m AS accuracy_m, l.battery_level AS battery_level, l.source AS source,
            l.created_at AS created_at, l.expires_at AS expires_at
     FROM location_shares l
     JOIN users u ON u.id = l.user_id
     JOIN family_members m ON m.user_id = l.user_id AND m.group_id = ?1 AND m.removed_at IS NULL
     JOIN (
       SELECT l2.user_id AS user_id, MAX(l2.created_at) AS max_created
       FROM location_shares l2
       JOIN family_members m2 ON m2.user_id = l2.user_id AND m2.group_id = ?1 AND m2.removed_at IS NULL
       WHERE l2.expires_at > ?2 AND (l2.group_id = ?1 OR l2.source = 'SOS')
       GROUP BY l2.user_id
     ) newest ON newest.user_id = l.user_id AND newest.max_created = l.created_at
     WHERE l.expires_at > ?2 AND (l.group_id = ?1 OR l.source = 'SOS')
     ORDER BY l.created_at DESC
     LIMIT 100`,
  )
    .bind(groupId, now)
    .all<{
      user_id: string;
      display_name: string;
      latitude: number;
      longitude: number;
      accuracy_m: number | null;
      battery_level: number | null;
      source: LocationSource;
      created_at: number;
      expires_at: number;
    }>();

  const staleThresholdMs = 10 * 60 * 1000;
  return (rows.results ?? []).map((row) => ({
    userId: row.user_id,
    displayName: row.display_name,
    latitude: row.latitude,
    longitude: row.longitude,
    accuracyM: row.accuracy_m,
    batteryLevel: row.battery_level,
    source: row.source,
    recordedAt: row.created_at,
    expiresAt: row.expires_at,
    stale: now - row.created_at > staleThresholdMs,
  }));
}

export async function purgeExpiredShares(env: Env, now: number): Promise<number> {
  const result = await env.DB.prepare(`DELETE FROM location_shares WHERE expires_at <= ?1`).bind(now).run();
  return result.meta.changes ?? 0;
}
