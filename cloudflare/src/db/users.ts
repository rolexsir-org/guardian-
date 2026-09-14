import type { Env } from "../env";

export interface UserRow {
  id: string;
  email: string;
  password_hash: string;
  display_name: string;
  email_verified: number;
  phone: string | null;
  locale: string | null;
  disabled_at: number | null;
  created_at: number;
  updated_at: number;
}

export interface PublicUser {
  id: string;
  email: string;
  displayName: string;
  emailVerified: boolean;
  phone: string | null;
  locale: string | null;
  createdAt: number;
  updatedAt: number;
}

export function toPublicUser(row: UserRow): PublicUser {
  return {
    id: row.id,
    email: row.email,
    displayName: row.display_name,
    emailVerified: row.email_verified === 1,
    phone: row.phone,
    locale: row.locale,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

const USER_COLUMNS = `id, email, password_hash, display_name, email_verified, phone, locale, disabled_at, created_at, updated_at`;

export async function findUserByEmail(env: Env, email: string): Promise<UserRow | null> {
  return await env.DB.prepare(`SELECT ${USER_COLUMNS} FROM users WHERE lower(email) = lower(?1)`)
    .bind(email)
    .first<UserRow>();
}

export async function findUserById(env: Env, userId: string): Promise<UserRow | null> {
  return await env.DB.prepare(`SELECT ${USER_COLUMNS} FROM users WHERE id = ?1`).bind(userId).first<UserRow>();
}

export async function insertUser(
  env: Env,
  input: { id: string; email: string; passwordHash: string; displayName: string; phone?: string; locale?: string; now: number },
): Promise<UserRow> {
  await env.DB.batch([
    env.DB.prepare(
      `INSERT INTO users (id, email, password_hash, display_name, email_verified, phone, locale, created_at, updated_at)
       VALUES (?1, ?2, ?3, ?4, 0, ?5, ?6, ?7, ?7)`,
    ).bind(input.id, input.email, input.passwordHash, input.displayName, input.phone ?? null, input.locale ?? null, input.now),
    env.DB.prepare(`INSERT INTO profiles (user_id, display_name, phone, locale, updated_at) VALUES (?1, ?2, ?3, ?4, ?5)`).bind(
      input.id,
      input.displayName,
      input.phone ?? null,
      input.locale ?? null,
      input.now,
    ),
    env.DB.prepare(
      `INSERT INTO presence (user_id, status, last_seen_at, battery_level, source) VALUES (?1, 'ONLINE', ?2, NULL, 'APP')`,
    ).bind(input.id, input.now),
  ]);

  const row = await findUserById(env, input.id);
  if (!row) throw new Error("user insert did not persist");
  return row;
}

export async function updateProfile(
  env: Env,
  userId: string,
  input: { displayName?: string; phone?: string | null; locale?: string | null },
  now: number,
): Promise<UserRow | null> {
  const statements: D1PreparedStatement[] = [];

  if (input.displayName !== undefined) {
    statements.push(
      env.DB.prepare(`UPDATE users SET display_name = ?1, updated_at = ?2 WHERE id = ?3`).bind(input.displayName, now, userId),
      env.DB.prepare(`UPDATE profiles SET display_name = ?1, updated_at = ?2 WHERE user_id = ?3`).bind(
        input.displayName,
        now,
        userId,
      ),
    );
  }
  if (input.phone !== undefined) {
    statements.push(
      env.DB.prepare(`UPDATE users SET phone = ?1, updated_at = ?2 WHERE id = ?3`).bind(input.phone, now, userId),
      env.DB.prepare(`UPDATE profiles SET phone = ?1, updated_at = ?2 WHERE user_id = ?3`).bind(input.phone, now, userId),
    );
  }
  if (input.locale !== undefined) {
    statements.push(
      env.DB.prepare(`UPDATE users SET locale = ?1, updated_at = ?2 WHERE id = ?3`).bind(input.locale, now, userId),
      env.DB.prepare(`UPDATE profiles SET locale = ?1, updated_at = ?2 WHERE user_id = ?3`).bind(input.locale, now, userId),
    );
  }

  if (statements.length > 0) {
    await env.DB.batch(statements);
  }
  return await findUserById(env, userId);
}
