/**
 * Community safety events (hazards + incidents).
 *
 * Replaces the Firebase Realtime Database `safety_incidents` tree. Reads are
 * relational and radius filtered; writes are authenticated, validated and rate
 * limited. Upvotes are idempotent per user.
 */

import { randomId } from "../util/crypto";
import { badRequest, forbidden, notFound, unauthorized } from "../util/errors";
import {
  asRecord,
  optionalEnum,
  requireEnum,
  requireLatitude,
  requireLongitude,
  requireNumber,
  requireString,
} from "../util/validate";
import { enforceRateLimit, RATE_LIMITS } from "../util/ratelimit";
import {
  confirmSafetyEvent,
  createSafetyEvent,
  getSafetyEvent,
  listNearbySafetyEvents,
  retractSafetyEventVote,
  toPublicSafetyEvent,
  SAFETY_EVENT_KINDS,
  SAFETY_SEVERITIES,
} from "../db/safetyEvents";
import { recordAudit } from "../db/audit";
import { notifyGeoCell, notifyFamily } from "../safety/broadcast";
import { dataResponse, readJsonBody, type Router } from "../http";

const DEFAULT_RADIUS_METERS = 5_000;
const MAX_RADIUS_METERS = 50_000;
const DEFAULT_TTL_MINUTES = 24 * 60;

export function registerSafetyEventRoutes(router: Router): void {
  router.get(
    "/v1/safety-events",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();

      const latParam = context.url.searchParams.get("latitude");
      const lngParam = context.url.searchParams.get("longitude");
      if (!latParam || !lngParam) {
        throw badRequest("location_required", "latitude and longitude query parameters are required.");
      }

      const latitude = requireLatitude(Number(latParam));
      const longitude = requireLongitude(Number(lngParam));
      const radiusMeters = Math.min(
        Number(context.url.searchParams.get("radiusMeters") ?? DEFAULT_RADIUS_METERS) || DEFAULT_RADIUS_METERS,
        MAX_RADIUS_METERS,
      );
      const kind = optionalEnum(context.url.searchParams.get("kind"), "kind", SAFETY_EVENT_KINDS);
      const limitParam = Number(context.url.searchParams.get("limit") ?? 100);

      await enforceRateLimit(context.env.DB, `read:${auth.userId}`, RATE_LIMITS.readPerUser);

      const events = await listNearbySafetyEvents(context.env, {
        latitude,
        longitude,
        radiusMeters,
        kind,
        limit: Number.isFinite(limitParam) ? limitParam : 100,
        now: Date.now(),
      });

      return dataResponse({ events, radiusMeters });
    },
    { auth: true },
  );

  router.post(
    "/v1/safety-events",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const { env, config, request } = context;
      const body = asRecord(await readJsonBody(request));

      const kind = requireEnum(body.kind, "kind", SAFETY_EVENT_KINDS);
      const title = requireString(body.title, "title", { min: 1, max: 120 });
      const category = requireString(body.category, "category", { min: 1, max: 40 });
      const severity = requireEnum(body.severity, "severity", SAFETY_SEVERITIES);
      const description = requireString(body.description ?? "", "description", { min: 0, max: 2000 });
      const latitude = requireLatitude(body.latitude);
      const longitude = requireLongitude(body.longitude);
      const ttlMinutes = requireNumber(body.ttlMinutes ?? DEFAULT_TTL_MINUTES, "ttlMinutes", {
        min: 5,
        max: 7 * 24 * 60,
      });

      await enforceRateLimit(env.DB, `safety-event:${auth.userId}`, RATE_LIMITS.safetyEventPerUser);

      const now = Date.now();
      const occurredAt = requireNumber(body.occurredAt ?? now, "occurredAt", {
        min: now - 7 * 24 * 60 * 60 * 1000,
        max: now + 5 * 60 * 1000,
      });

      const row = await createSafetyEvent(env, {
        id: randomId(),
        kind,
        reporterUserId: auth.userId,
        title,
        category,
        severity,
        description,
        latitude,
        longitude,
        occurredAt,
        expiresAt: now + ttlMinutes * 60_000,
        now,
      });

      const event = toPublicSafetyEvent(row);
      await notifyGeoCell(env, latitude, longitude, {
        event: { kind: "SAFETY_EVENT_CREATED", at: now, data: event as unknown as Record<string, unknown> },
      });
      await notifyFamily(env, auth.userId, {
        event: { kind: "SAFETY_EVENT_CREATED", at: now, data: event as unknown as Record<string, unknown> },
      });
      await recordAudit(env, config, {
        userId: auth.userId,
        action: `safety_event.${kind.toLowerCase()}`,
        outcome: "SUCCESS",
        clientIp: context.clientIp,
        userAgent: request.headers.get("user-agent"),
        details: `category=${category} severity=${severity}`,
        now,
      });

      return dataResponse({ event }, 201);
    },
    { auth: true },
  );

  router.post(
    "/v1/safety-events/:eventId/votes",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const eventId = requireString(context.params.eventId, "eventId", { max: 64, trim: false });

      await enforceRateLimit(context.env.DB, `vote:${auth.userId}`, RATE_LIMITS.votePerUser);

      const event = await getSafetyEvent(context.env, eventId);
      if (!event) throw notFound("event_not_found", "Safety event not found.");
      if (event.reporter_user_id === auth.userId) {
        throw forbidden("self_confirmation_denied", "You cannot confirm your own report.");
      }

      const result = await confirmSafetyEvent(context.env, eventId, auth.userId, Date.now());
      if (!result) throw notFound("event_not_found", "Safety event not found.");
      return dataResponse(result);
    },
    { auth: true },
  );

  router.delete(
    "/v1/safety-events/:eventId/votes",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const eventId = requireString(context.params.eventId, "eventId", { max: 64, trim: false });

      const result = await retractSafetyEventVote(context.env, eventId, auth.userId);
      if (!result) throw notFound("event_not_found", "Safety event not found.");
      return dataResponse(result);
    },
    { auth: true },
  );
}
