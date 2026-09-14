/**
 * Fixed-window rate limiter backed by D1.
 *
 * Used to protect authentication, emergency and reporting endpoints against
 * abuse/replay floods. Counters are per bucket (for example
 * `login:ip:<hashed-ip>`), so a single abusive key cannot exhaust the quota of
 * another user.
 */

import { rateLimited } from "./errors";

export const RATE_LIMITS = {
  registerPerIp: { limit: 5, windowSeconds: 3600 },
  registerPerEmail: { limit: 5, windowSeconds: 3600 },
  loginPerIp: { limit: 20, windowSeconds: 900 },
  loginPerEmail: { limit: 10, windowSeconds: 900 },
  refreshPerIp: { limit: 60, windowSeconds: 900 },
  sosPerUser: { limit: 30, windowSeconds: 3600 },
  sosPerIp: { limit: 60, windowSeconds: 3600 },
  safetyEventPerUser: { limit: 20, windowSeconds: 3600 },
  votePerUser: { limit: 120, windowSeconds: 3600 },
  evidencePerUser: { limit: 30, windowSeconds: 3600 },
  readPerUser: { limit: 1200, windowSeconds: 60 },
} as const;

export interface RateLimitRule {
  limit: number;
  windowSeconds: number;
}

export async function enforceRateLimit(
  db: D1Database,
  bucket: string,
  rule: RateLimitRule,
  now: number = Date.now(),
): Promise<void> {
  const windowMs = rule.windowSeconds * 1000;
  const windowStart = Math.floor(now / windowMs) * windowMs;

  const row = await db
    .prepare(
      `INSERT INTO rate_limit_counters (bucket, window_start, count)
       VALUES (?1, ?2, 1)
       ON CONFLICT (bucket, window_start) DO UPDATE SET count = count + 1
       RETURNING count`,
    )
    .bind(bucket, windowStart)
    .first<{ count: number }>();

  const count = row?.count ?? 1;
  if (count > rule.limit) {
    throw rateLimited(rule.windowSeconds);
  }
}

/** Housekeeping for the scheduled retention job. */
export async function purgeRateLimitCounters(db: D1Database, olderThanMs: number): Promise<void> {
  await db.prepare("DELETE FROM rate_limit_counters WHERE window_start < ?1").bind(olderThanMs).run();
}
