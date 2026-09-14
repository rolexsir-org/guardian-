/**
 * Helpers that push domain events into the realtime layer.
 *
 * These are always best-effort: the relational record in D1 is the source of
 * truth, and an SOS must never fail because realtime delivery was unavailable.
 * Failures are logged and surfaced through the response's `realtime` flag, and
 * clients recover state from the REST API on reconnect.
 */

import type { Env } from "../env";
import { geohash } from "../util/geohash";
import { groupIdsForUser } from "../db/family";
import { familyScope, geoScope, type RealtimeEvent } from "../realtime/protocol";

const HUB_ORIGIN = "https://safety-hub.internal";

export interface ActiveSosRecord {
  sosId: string;
  userId: string;
  triggerSource: string;
  status: string;
  latitude: number | null;
  longitude: number | null;
  occurredAt: number;
}

interface PublishPayload {
  event?: RealtimeEvent;
  trackSos?: ActiveSosRecord;
  clearSosUserId?: string;
}

async function postToScope(env: Env, scope: string, path: string, payload: PublishPayload | unknown): Promise<boolean> {
  try {
    const id = env.SAFETY_HUB.idFromName(scope);
    const stub = env.SAFETY_HUB.get(id);
    const response = await stub.fetch(
      new Request(`${HUB_ORIGIN}${path}`, {
        method: "POST",
        headers: { "content-type": "application/json", "x-guardian-scope": scope },
        body: JSON.stringify(payload),
      }),
    );
    return response.ok;
  } catch (error) {
    console.error(
      JSON.stringify({ level: "warn", event: "realtime_publish_failed", scope, message: String(error) }),
    );
    return false;
  }
}

/** Publishes an event to every family group the user belongs to. */
export async function notifyFamily(
  env: Env,
  userId: string,
  payload: PublishPayload,
): Promise<boolean> {
  const groupIds = await groupIdsForUser(env, userId);
  if (groupIds.length === 0) return true;

  const results = await Promise.all(groupIds.map((groupId) => postToScope(env, familyScope(groupId), "/publish", payload)));
  return results.every((ok) => ok);
}

/** Publishes an event to members of one specific family group. */
export async function notifyGroup(env: Env, groupId: string, payload: PublishPayload): Promise<boolean> {
  return await postToScope(env, familyScope(groupId), "/publish", payload);
}

/** Publishes an event to nearby clients subscribed to the same geohash cell. */
export async function notifyGeoCell(
  env: Env,
  latitude: number,
  longitude: number,
  payload: PublishPayload,
): Promise<boolean> {
  const cell = geohash(latitude, longitude, 4);
  return await postToScope(env, geoScope(cell), "/publish", payload);
}

export async function updatePresenceInScopes(
  env: Env,
  userId: string,
  status: "ONLINE" | "OFFLINE",
  batteryLevel: number | null,
  now: number,
): Promise<boolean> {
  const groupIds = await groupIdsForUser(env, userId);
  if (groupIds.length === 0) return true;

  const entry = { userId, status, lastSeenAt: now, batteryLevel };
  const results = await Promise.all(
    groupIds.map((groupId) => postToScope(env, familyScope(groupId), "/presence", entry)),
  );
  return results.every((ok) => ok);
}

/** Removes an active SOS from the realtime snapshot state after resolution. */
export async function clearActiveSos(env: Env, userId: string): Promise<boolean> {
  const groupIds = await groupIdsForUser(env, userId);
  if (groupIds.length === 0) return true;
  const results = await Promise.all(
    groupIds.map((groupId) => postToScope(env, familyScope(groupId), "/sos/clear", { userId })),
  );
  return results.every((ok) => ok);
}
