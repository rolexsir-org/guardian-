import type { Env } from "../env";

export interface ContactRow {
  id: string;
  user_id: string;
  name: string;
  phone: string;
  relationship: string;
  is_verified: number;
  priority: number;
  created_at: number;
  updated_at: number;
}

export interface PublicContact {
  id: string;
  name: string;
  phone: string;
  relationship: string;
  isVerified: boolean;
  priority: number;
  createdAt: number;
  updatedAt: number;
}

export function toPublicContact(row: ContactRow): PublicContact {
  return {
    id: row.id,
    name: row.name,
    phone: row.phone,
    relationship: row.relationship,
    isVerified: row.is_verified === 1,
    priority: row.priority,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

const COLUMNS = `id, user_id, name, phone, relationship, is_verified, priority, created_at, updated_at`;

export async function listContacts(env: Env, userId: string): Promise<ContactRow[]> {
  const rows = await env.DB.prepare(`SELECT ${COLUMNS} FROM emergency_contacts WHERE user_id = ?1 ORDER BY priority ASC, created_at ASC LIMIT 50`)
    .bind(userId)
    .all<ContactRow>();
  return rows.results ?? [];
}

export async function getContact(env: Env, userId: string, contactId: string): Promise<ContactRow | null> {
  return await env.DB.prepare(`SELECT ${COLUMNS} FROM emergency_contacts WHERE id = ?1 AND user_id = ?2`)
    .bind(contactId, userId)
    .first<ContactRow>();
}

export async function countContacts(env: Env, userId: string): Promise<number> {
  const row = await env.DB.prepare(`SELECT COUNT(*) AS total FROM emergency_contacts WHERE user_id = ?1`)
    .bind(userId)
    .first<{ total: number }>();
  return row?.total ?? 0;
}

export async function insertContact(
  env: Env,
  input: {
    id: string;
    userId: string;
    name: string;
    phone: string;
    relationship: string;
    isVerified: boolean;
    priority: number;
    now: number;
  },
): Promise<ContactRow | null> {
  await env.DB.prepare(
    `INSERT INTO emergency_contacts (id, user_id, name, phone, relationship, is_verified, priority, created_at, updated_at)
     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?8)`,
  )
    .bind(
      input.id,
      input.userId,
      input.name,
      input.phone,
      input.relationship,
      input.isVerified ? 1 : 0,
      input.priority,
      input.now,
    )
    .run();
  return await getContact(env, input.userId, input.id);
}

export async function updateContact(
  env: Env,
  input: {
    userId: string;
    contactId: string;
    name?: string;
    phone?: string;
    relationship?: string;
    isVerified?: boolean;
    priority?: number;
    now: number;
  },
): Promise<ContactRow | null> {
  const existing = await getContact(env, input.userId, input.contactId);
  if (!existing) return null;

  await env.DB.prepare(
    `UPDATE emergency_contacts
       SET name = ?1, phone = ?2, relationship = ?3, is_verified = ?4, priority = ?5, updated_at = ?6
     WHERE id = ?7 AND user_id = ?8`,
  )
    .bind(
      input.name ?? existing.name,
      input.phone ?? existing.phone,
      input.relationship ?? existing.relationship,
      (input.isVerified ?? existing.is_verified === 1) ? 1 : 0,
      input.priority ?? existing.priority,
      input.now,
      input.contactId,
      input.userId,
    )
    .run();

  return await getContact(env, input.userId, input.contactId);
}

export async function deleteContact(env: Env, userId: string, contactId: string): Promise<boolean> {
  const result = await env.DB.prepare(`DELETE FROM emergency_contacts WHERE id = ?1 AND user_id = ?2`)
    .bind(contactId, userId)
    .run();
  return (result.meta.changes ?? 0) > 0;
}
