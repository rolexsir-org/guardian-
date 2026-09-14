/** Family chat messages and safety check-ins. */

import type { Env } from "../env";

export interface FamilyMessageRow {
  id: string;
  group_id: string;
  sender_user_id: string;
  body: string;
  is_emergency: number;
  created_at: number;
}

export interface PublicFamilyMessage {
  id: string;
  groupId: string;
  senderUserId: string;
  senderName: string;
  body: string;
  isEmergency: boolean;
  createdAt: number;
}

export async function insertFamilyMessage(
  env: Env,
  input: { id: string; groupId: string; senderUserId: string; body: string; isEmergency: boolean; now: number },
): Promise<FamilyMessageRow> {
  await env.DB.prepare(
    `INSERT INTO family_messages (id, group_id, sender_user_id, body, is_emergency, created_at)
     VALUES (?1, ?2, ?3, ?4, ?5, ?6)`,
  )
    .bind(input.id, input.groupId, input.senderUserId, input.body, input.isEmergency ? 1 : 0, input.now)
    .run();

  const row = await env.DB.prepare(
    `SELECT id, group_id, sender_user_id, body, is_emergency, created_at FROM family_messages WHERE id = ?1`,
  )
    .bind(input.id)
    .first<FamilyMessageRow>();
  if (!row) throw new Error("family message insert did not persist");
  return row;
}

export async function listFamilyMessages(
  env: Env,
  groupId: string,
  since: number | null,
  limit = 100,
): Promise<PublicFamilyMessage[]> {
  const rows = await env.DB.prepare(
    `SELECT m.id AS id, m.group_id AS group_id, m.sender_user_id AS sender_user_id, u.display_name AS sender_name,
            m.body AS body, m.is_emergency AS is_emergency, m.created_at AS created_at
     FROM family_messages m
     JOIN users u ON u.id = m.sender_user_id
     WHERE m.group_id = ?1 AND (?2 IS NULL OR m.created_at > ?2)
     ORDER BY m.created_at DESC
     LIMIT ?3`,
  )
    .bind(groupId, since, Math.min(Math.max(limit, 1), 200))
    .all<{
      id: string;
      group_id: string;
      sender_user_id: string;
      sender_name: string;
      body: string;
      is_emergency: number;
      created_at: number;
    }>();

  return (rows.results ?? []).map((row) => ({
    id: row.id,
    groupId: row.group_id,
    senderUserId: row.sender_user_id,
    senderName: row.sender_name,
    body: row.body,
    isEmergency: row.is_emergency === 1,
    createdAt: row.created_at,
  }));
}

export interface CheckinRow {
  id: string;
  user_id: string;
  group_id: string | null;
  duration_minutes: number;
  status: "ACTIVE" | "COMPLETED" | "ALERTED";
  note: string;
  started_at: number;
  due_at: number;
  completed_at: number | null;
}

export interface PublicCheckin {
  id: string;
  userId: string;
  groupId: string | null;
  durationMinutes: number;
  status: string;
  note: string;
  startedAt: number;
  dueAt: number;
  completedAt: number | null;
}

export async function insertCheckin(
  env: Env,
  input: { id: string; userId: string; groupId: string | null; durationMinutes: number; note: string; now: number },
): Promise<CheckinRow> {
  const dueAt = input.now + input.durationMinutes * 60_000;
  await env.DB.prepare(
    `INSERT INTO checkins (id, user_id, group_id, duration_minutes, status, note, started_at, due_at)
     VALUES (?1, ?2, ?3, ?4, 'ACTIVE', ?5, ?6, ?7)`,
  )
    .bind(input.id, input.userId, input.groupId, input.durationMinutes, input.note, input.now, dueAt)
    .run();

  const row = await env.DB.prepare(
    `SELECT id, user_id, group_id, duration_minutes, status, note, started_at, due_at, completed_at FROM checkins WHERE id = ?1`,
  )
    .bind(input.id)
    .first<CheckinRow>();
  if (!row) throw new Error("checkin insert did not persist");
  return row;
}

export async function listCheckins(env: Env, userId: string, limit = 50): Promise<PublicCheckin[]> {
  const rows = await env.DB.prepare(
    `SELECT id, user_id, group_id, duration_minutes, status, note, started_at, due_at, completed_at
     FROM checkins WHERE user_id = ?1 ORDER BY started_at DESC LIMIT ?2`,
  )
    .bind(userId, Math.min(Math.max(limit, 1), 200))
    .all<CheckinRow>();

  return (rows.results ?? []).map((row) => ({
    id: row.id,
    userId: row.user_id,
    groupId: row.group_id,
    durationMinutes: row.duration_minutes,
    status: row.status,
    note: row.note,
    startedAt: row.started_at,
    dueAt: row.due_at,
    completedAt: row.completed_at,
  }));
}

export async function completeCheckin(
  env: Env,
  checkinId: string,
  userId: string,
  status: "COMPLETED" | "ALERTED",
  now: number,
): Promise<CheckinRow | null> {
  const result = await env.DB.prepare(
    `UPDATE checkins SET status = ?1, completed_at = ?2 WHERE id = ?3 AND user_id = ?4`,
  )
    .bind(status, now, checkinId, userId)
    .run();
  if ((result.meta.changes ?? 0) === 0) return null;

  return await env.DB.prepare(
    `SELECT id, user_id, group_id, duration_minutes, status, note, started_at, due_at, completed_at FROM checkins WHERE id = ?1`,
  )
    .bind(checkinId)
    .first<CheckinRow>();
}
