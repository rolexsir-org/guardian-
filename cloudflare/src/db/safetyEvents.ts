import type { Env } from "../env";
import { boundingBox, distanceMeters, geohash } from "../util/geohash";

export const SAFETY_EVENT_KINDS = ["HAZARD", "INCIDENT"] as const;
export type SafetyEventKind = (typeof SAFETY_EVENT_KINDS)[number];

export const SAFETY_SEVERITIES = ["CRITICAL", "WARNING", "INFO"] as const;
export type SafetySeverity = (typeof SAFETY_SEVERITIES)[number];

export interface SafetyEventRow {
  id: string;
  kind: SafetyEventKind;
  reporter_user_id: string | null;
  title: string;
  category: string;
  severity: SafetySeverity;
  description: string;
  latitude: number;
  longitude: number;
  geohash: string;
  occurred_at: number;
  created_at: number;
  expires_at: number | null;
  status: string;
  confirmations: number;
}

export interface PublicSafetyEvent {
  id: string;
  kind: SafetyEventKind;
  title: string;
  category: string;
  severity: SafetySeverity;
  description: string;
  latitude: number;
  longitude: number;
  occurredAt: number;
  createdAt: number;
  expiresAt: number | null;
  status: string;
  confirmations: number;
  reportedByUserId: string | null;
  distanceMeters?: number;
}

export function toPublicSafetyEvent(row: SafetyEventRow, distance?: number): PublicSafetyEvent {
  const base: PublicSafetyEvent = {
    id: row.id,
    kind: row.kind,
    title: row.title,
    category: row.category,
    severity: row.severity,
    description: row.description,
    latitude: row.latitude,
    longitude: row.longitude,
    occurredAt: row.occurred_at,
    createdAt: row.created_at,
    expiresAt: row.expires_at,
    status: row.status,
    confirmations: row.confirmations,
    reportedByUserId: row.reporter_user_id,
  };
  if (distance !== undefined) base.distanceMeters = Math.round(distance);
  return base;
}

const COLUMNS = `id, kind, reporter_user_id, title, category, severity, description, latitude, longitude,
  geohash, occurred_at, created_at, expires_at, status, confirmations`;

export async function createSafetyEvent(
  env: Env,
  input: {
    id: string;
    kind: SafetyEventKind;
    reporterUserId: string;
    title: string;
    category: string;
    severity: SafetySeverity;
    description: string;
    latitude: number;
    longitude: number;
    occurredAt: number;
    expiresAt: number;
    now: number;
  },
): Promise<SafetyEventRow> {
  const hash = geohash(input.latitude, input.longitude, 6);
  await env.DB.prepare(
    `INSERT INTO safety_events (
       id, kind, reporter_user_id, title, category, severity, description, latitude, longitude,
       geohash, occurred_at, created_at, expires_at, status, confirmations
     ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, 'ACTIVE', 0)`,
  )
    .bind(
      input.id,
      input.kind,
      input.reporterUserId,
      input.title,
      input.category,
      input.severity,
      input.description,
      input.latitude,
      input.longitude,
      hash,
      input.occurredAt,
      input.now,
      input.expiresAt,
    )
    .run();

  const row = await env.DB.prepare(`SELECT ${COLUMNS} FROM safety_events WHERE id = ?1`).bind(input.id).first<SafetyEventRow>();
  if (!row) throw new Error("safety event insert did not persist");
  return row;
}

export async function getSafetyEvent(env: Env, eventId: string): Promise<SafetyEventRow | null> {
  return await env.DB.prepare(`SELECT ${COLUMNS} FROM safety_events WHERE id = ?1`).bind(eventId).first<SafetyEventRow>();
}

export interface NearbyQuery {
  latitude: number;
  longitude: number;
  radiusMeters: number;
  kind?: SafetyEventKind | undefined;
  limit?: number | undefined;
  now: number;
}

export async function listNearbySafetyEvents(
  env: Env,
  query: NearbyQuery,
): Promise<PublicSafetyEvent[]> {
  const box = boundingBox(query.latitude, query.longitude, query.radiusMeters);
  const limit = Math.min(Math.max(query.limit ?? 100, 1), 200);

  const rows = await env.DB.prepare(
    `SELECT ${COLUMNS} FROM safety_events
     WHERE status = 'ACTIVE'
       AND (expires_at IS NULL OR expires_at > ?1)
       AND latitude BETWEEN ?2 AND ?3
       AND longitude BETWEEN ?4 AND ?5
       AND (?6 IS NULL OR kind = ?6)
     ORDER BY occurred_at DESC
     LIMIT ?7`,
  )
    .bind(query.now, box.minLat, box.maxLat, box.minLng, box.maxLng, query.kind ?? null, limit)
    .all<SafetyEventRow>();

  return (rows.results ?? [])
    .map((row) => ({
      row,
      distance: distanceMeters(query.latitude, query.longitude, row.latitude, row.longitude),
    }))
    .filter((entry) => entry.distance <= query.radiusMeters)
    .sort((a, b) => a.distance - b.distance)
    .map((entry) => toPublicSafetyEvent(entry.row, entry.distance));
}

export interface VoteResult {
  confirmations: number;
  voted: boolean;
}

/** Idempotent per user: one confirmation per safety event. */
export async function confirmSafetyEvent(env: Env, eventId: string, userId: string, now: number): Promise<VoteResult | null> {
  const event = await getSafetyEvent(env, eventId);
  if (!event) return null;

  const insert = await env.DB.prepare(
    `INSERT INTO safety_event_votes (event_id, user_id, created_at) VALUES (?1, ?2, ?3)
     ON CONFLICT (event_id, user_id) DO NOTHING`,
  )
    .bind(eventId, userId, now)
    .run();

  const voted = (insert.meta.changes ?? 0) > 0;
  if (voted) {
    await env.DB.prepare(`UPDATE safety_events SET confirmations = confirmations + 1 WHERE id = ?1`).bind(eventId).run();
  }

  const updated = await getSafetyEvent(env, eventId);
  return { confirmations: updated?.confirmations ?? event.confirmations, voted };
}

export async function retractSafetyEventVote(env: Env, eventId: string, userId: string): Promise<VoteResult | null> {
  const event = await getSafetyEvent(env, eventId);
  if (!event) return null;

  const removed = await env.DB.prepare(`DELETE FROM safety_event_votes WHERE event_id = ?1 AND user_id = ?2`)
    .bind(eventId, userId)
    .run();

  const wasVoted = (removed.meta.changes ?? 0) > 0;
  if (wasVoted) {
    await env.DB.prepare(
      `UPDATE safety_events SET confirmations = CASE WHEN confirmations > 0 THEN confirmations - 1 ELSE 0 END WHERE id = ?1`,
    )
      .bind(eventId)
      .run();
  }

  const updated = await getSafetyEvent(env, eventId);
  return { confirmations: updated?.confirmations ?? event.confirmations, voted: false };
}

export async function archiveExpiredSafetyEvents(env: Env, now: number): Promise<number> {
  const result = await env.DB.prepare(
    `UPDATE safety_events SET status = 'ARCHIVED' WHERE status = 'ACTIVE' AND expires_at IS NOT NULL AND expires_at <= ?1`,
  )
    .bind(now)
    .run();
  return result.meta.changes ?? 0;
}
