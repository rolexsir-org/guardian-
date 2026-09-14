import type { Env } from "../env";

export const FAMILY_ROLES = ["OWNER", "PARENT", "GUARDIAN", "CHILD", "MEMBER"] as const;
export type FamilyRole = (typeof FAMILY_ROLES)[number];

/** Roles allowed to manage the family group (invite/remove members). */
export const MANAGER_ROLES: readonly FamilyRole[] = ["OWNER", "PARENT", "GUARDIAN"];

export interface GroupRow {
  id: string;
  name: string;
  owner_user_id: string;
  created_at: number;
  updated_at: number;
  archived_at: number | null;
}

export interface MembershipRow {
  id: string;
  group_id: string;
  user_id: string;
  role: FamilyRole;
  joined_at: number;
  removed_at: number | null;
}

export interface GroupSummary {
  id: string;
  name: string;
  ownerUserId: string;
  myRole: FamilyRole;
  memberCount: number;
  createdAt: number;
}

export interface MemberSummary {
  memberId: string;
  userId: string;
  role: FamilyRole;
  displayName: string;
  joinedAt: number;
}

export async function createGroup(
  env: Env,
  input: { id: string; name: string; ownerUserId: string; membershipId: string; now: number },
): Promise<GroupRow> {
  await env.DB.batch([
    env.DB.prepare(
      `INSERT INTO family_groups (id, name, owner_user_id, created_at, updated_at) VALUES (?1, ?2, ?3, ?4, ?4)`,
    ).bind(input.id, input.name, input.ownerUserId, input.now),
    env.DB.prepare(
      `INSERT INTO family_members (id, group_id, user_id, role, joined_at) VALUES (?1, ?2, ?3, 'OWNER', ?4)`,
    ).bind(input.membershipId, input.id, input.ownerUserId, input.now),
  ]);

  const row = await env.DB.prepare(
    `SELECT id, name, owner_user_id, created_at, updated_at, archived_at FROM family_groups WHERE id = ?1`,
  )
    .bind(input.id)
    .first<GroupRow>();
  if (!row) throw new Error("group insert did not persist");
  return row;
}

export async function listGroupsForUser(env: Env, userId: string): Promise<GroupSummary[]> {
  const rows = await env.DB.prepare(
    `SELECT g.id AS id, g.name AS name, g.owner_user_id AS owner_user_id, g.created_at AS created_at,
            m.role AS role,
            (SELECT COUNT(*) FROM family_members fm WHERE fm.group_id = g.id AND fm.removed_at IS NULL) AS member_count
     FROM family_members m
     JOIN family_groups g ON g.id = m.group_id
     WHERE m.user_id = ?1 AND m.removed_at IS NULL AND g.archived_at IS NULL
     ORDER BY g.created_at ASC
     LIMIT 20`,
  )
    .bind(userId)
    .all<{ id: string; name: string; owner_user_id: string; created_at: number; role: FamilyRole; member_count: number }>();

  return (rows.results ?? []).map((row) => ({
    id: row.id,
    name: row.name,
    ownerUserId: row.owner_user_id,
    myRole: row.role,
    memberCount: row.member_count,
    createdAt: row.created_at,
  }));
}

export async function getGroup(env: Env, groupId: string): Promise<GroupRow | null> {
  return await env.DB.prepare(
    `SELECT id, name, owner_user_id, created_at, updated_at, archived_at
     FROM family_groups WHERE id = ?1 AND archived_at IS NULL`,
  )
    .bind(groupId)
    .first<GroupRow>();
}

/** Returns the caller's active membership, or null when not a member (403 at the API edge). */
export async function getMembership(env: Env, groupId: string, userId: string): Promise<MembershipRow | null> {
  return await env.DB.prepare(
    `SELECT id, group_id, user_id, role, joined_at, removed_at
     FROM family_members
     WHERE group_id = ?1 AND user_id = ?2 AND removed_at IS NULL`,
  )
    .bind(groupId, userId)
    .first<MembershipRow>();
}

export async function listMembers(env: Env, groupId: string): Promise<MemberSummary[]> {
  const rows = await env.DB.prepare(
    `SELECT m.id AS member_id, m.user_id AS user_id, m.role AS role, m.joined_at AS joined_at,
            u.display_name AS display_name
     FROM family_members m
     JOIN users u ON u.id = m.user_id
     WHERE m.group_id = ?1 AND m.removed_at IS NULL
     ORDER BY m.joined_at ASC
     LIMIT 100`,
  )
    .bind(groupId)
    .all<{ member_id: string; user_id: string; role: FamilyRole; joined_at: number; display_name: string }>();

  return (rows.results ?? []).map((row) => ({
    memberId: row.member_id,
    userId: row.user_id,
    role: row.role,
    displayName: row.display_name,
    joinedAt: row.joined_at,
  }));
}

export async function createInvite(
  env: Env,
  input: { id: string; groupId: string; codeHash: string; role: FamilyRole; createdBy: string; now: number; expiresAt: number },
): Promise<void> {
  await env.DB.prepare(
    `INSERT INTO family_invites (id, group_id, code_hash, role, created_by, created_at, expires_at)
     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)`,
  )
    .bind(input.id, input.groupId, input.codeHash, input.role, input.createdBy, input.now, input.expiresAt)
    .run();
}

export async function findInviteByHash(
  env: Env,
  codeHash: string,
  now: number,
): Promise<{ id: string; group_id: string; role: FamilyRole; expires_at: number; consumed_at: number | null } | null> {
  return await env.DB.prepare(
    `SELECT id, group_id, role, expires_at, consumed_at
     FROM family_invites WHERE code_hash = ?1 AND consumed_at IS NULL AND expires_at > ?2`,
  )
    .bind(codeHash, now)
    .first<{ id: string; group_id: string; role: FamilyRole; expires_at: number; consumed_at: number | null }>();
}

export async function consumeInvite(env: Env, inviteId: string, userId: string, now: number): Promise<boolean> {
  const result = await env.DB.prepare(
    `UPDATE family_invites SET consumed_at = ?1, consumed_by = ?2 WHERE id = ?3 AND consumed_at IS NULL`,
  )
    .bind(now, userId, inviteId)
    .run();
  return (result.meta.changes ?? 0) > 0;
}

export async function addMember(
  env: Env,
  input: { id: string; groupId: string; userId: string; role: FamilyRole; now: number },
): Promise<void> {
  await env.DB.prepare(
    `INSERT INTO family_members (id, group_id, user_id, role, joined_at) VALUES (?1, ?2, ?3, ?4, ?5)
     ON CONFLICT (group_id, user_id) DO UPDATE SET role = ?4, removed_at = NULL, joined_at = ?5`,
  )
    .bind(input.id, input.groupId, input.userId, input.role, input.now)
    .run();
}

export async function removeMember(env: Env, groupId: string, memberId: string, now: number): Promise<MembershipRow | null> {
  const existing = await env.DB.prepare(
    `SELECT id, group_id, user_id, role, joined_at, removed_at FROM family_members WHERE id = ?1 AND group_id = ?2`,
  )
    .bind(memberId, groupId)
    .first<MembershipRow>();
  if (!existing) return null;

  await env.DB.prepare(`UPDATE family_members SET removed_at = ?1 WHERE id = ?2 AND group_id = ?3`)
    .bind(now, memberId, groupId)
    .run();
  return existing;
}

export async function updateMemberRole(env: Env, groupId: string, memberId: string, role: FamilyRole): Promise<boolean> {
  const result = await env.DB.prepare(`UPDATE family_members SET role = ?1 WHERE id = ?2 AND group_id = ?3 AND removed_at IS NULL`)
    .bind(role, memberId, groupId)
    .run();
  return (result.meta.changes ?? 0) > 0;
}

/** True when the two users share at least one active family group. */
export async function sharesFamilyGroup(env: Env, userId: string, otherUserId: string): Promise<boolean> {
  const row = await env.DB.prepare(
    `SELECT 1 AS shared
     FROM family_members a
     JOIN family_members b ON b.group_id = a.group_id
     WHERE a.user_id = ?1 AND b.user_id = ?2 AND a.removed_at IS NULL AND b.removed_at IS NULL
     LIMIT 1`,
  )
    .bind(userId, otherUserId)
    .first<{ shared: number }>();
  return row !== null;
}

export async function groupIdsForUser(env: Env, userId: string): Promise<string[]> {
  const rows = await env.DB.prepare(
    `SELECT group_id FROM family_members WHERE user_id = ?1 AND removed_at IS NULL LIMIT 20`,
  )
    .bind(userId)
    .all<{ group_id: string }>();
  return (rows.results ?? []).map((row) => row.group_id);
}

/** Family members (excluding the caller) that must be reachable in an emergency. */
export async function guardianUserIdsFor(env: Env, userId: string): Promise<string[]> {
  const rows = await env.DB.prepare(
    `SELECT DISTINCT other.user_id AS user_id
     FROM family_members mine
     JOIN family_members other ON other.group_id = mine.group_id
     WHERE mine.user_id = ?1 AND mine.removed_at IS NULL
       AND other.removed_at IS NULL AND other.user_id <> ?1
     LIMIT 200`,
  )
    .bind(userId)
    .all<{ user_id: string }>();
  return (rows.results ?? []).map((row) => row.user_id);
}
