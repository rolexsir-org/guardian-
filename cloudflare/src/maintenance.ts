/**
 * Scheduled retention / housekeeping job (cron trigger).
 *
 * Keeps the database free of expired location data, archived events, stale
 * presence rows, unused invite codes and rate-limit windows — and removes
 * expired evidence objects from R2 so no data outlives its retention window.
 */

import type { AppConfig, Env } from "./env";
import { purgeExpiredEvidence } from "./storage/evidence";
import { purgeExpiredShares } from "./db/locations";
import { archiveExpiredSafetyEvents } from "./db/safetyEvents";
import { markStalePresenceOffline } from "./db/presence";
import { purgeRateLimitCounters } from "./util/ratelimit";

export interface MaintenanceReport {
  evidencePurged: number;
  locationSharesPurged: number;
  safetyEventsArchived: number;
  presenceMarkedOffline: number;
}

export async function runMaintenance(env: Env, config: AppConfig, now: number = Date.now()): Promise<MaintenanceReport> {
  const evidencePurged = await purgeExpiredEvidence(env, now);
  const locationSharesPurged = await purgeExpiredShares(env, now);
  const safetyEventsArchived = await archiveExpiredSafetyEvents(env, now);
  const presenceMarkedOffline = await markStalePresenceOffline(env, now);

  await env.DB.prepare(`DELETE FROM family_invites WHERE expires_at <= ?1`).bind(now).run();
  await env.DB.prepare(`DELETE FROM refresh_token_history WHERE rotated_at <= ?1`)
    .bind(now - config.refreshTokenTtlSeconds * 1000)
    .run();
  await purgeRateLimitCounters(env.DB, now - 24 * 60 * 60 * 1000);

  return { evidencePurged, locationSharesPurged, safetyEventsArchived, presenceMarkedOffline };
}
