/**
 * Emergency (SOS) endpoints.
 *
 * Design rules honoured here:
 *  * The Android client performs all local emergency actions (call, SMS,
 *    siren, local audit record) WITHOUT waiting for this API. This endpoint is
 *    the durable, shared record of the event.
 *  * Creation is idempotent per `clientEventId`, so an offline device can
 *    replay the same SOS until it receives an acknowledgement without ever
 *    creating duplicates.
 *  * The response always reports whether realtime fan-out succeeded; a
 *    realtime failure never turns a stored emergency into an error.
 */

import { randomId } from "../util/crypto";
import { badRequest, conflict, forbidden, notFound, unauthorized } from "../util/errors";
import {
  asRecord,
  optionalNumber,
  optionalString,
  requireEnum,
  requireNumber,
  requireString,
  requireLatitude,
  requireLongitude,
} from "../util/validate";
import { enforceRateLimit, RATE_LIMITS } from "../util/ratelimit";
import { activeSosForGroup, createSosEvent, getSosEvent, listSosEvents, toPublicSosEvent, updateSosStatus } from "../db/sos";
import { getMembership, sharesFamilyGroup } from "../db/family";
import { recordLocationShare } from "../db/locations";
import { recordAudit } from "../db/audit";
import { clearActiveSos, notifyFamily, notifyGeoCell } from "../safety/broadcast";
import { dataResponse, readJsonBody, type Router } from "../http";

const TRIGGER_SOURCES = ["BUTTON", "SHAKE", "PIN", "FALL", "CRASH", "VOICE", "HARDWARE", "REMOTE"] as const;

export function registerSosRoutes(router: Router): void {
  router.post(
    "/v1/sos",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const { env, config, request } = context;
      const body = asRecord(await readJsonBody(request));

      const clientEventId = requireString(body.clientEventId, "clientEventId", { min: 8, max: 128, trim: false });
      const triggerSource = requireEnum(body.triggerSource, "triggerSource", TRIGGER_SOURCES);

      const hasLatitude = body.latitude !== undefined && body.latitude !== null;
      const hasLongitude = body.longitude !== undefined && body.longitude !== null;
      if (hasLatitude !== hasLongitude) {
        throw badRequest("incomplete_location", "Latitude and longitude must be provided together.");
      }
      const latitude = hasLatitude ? requireLatitude(body.latitude) : undefined;
      const longitude = hasLongitude ? requireLongitude(body.longitude) : undefined;

      const accuracyM = optionalNumber(body.accuracyM, "accuracyM", { min: 0, max: 100_000 });
      const batteryLevel = optionalNumber(body.batteryLevel, "batteryLevel", { min: 0, max: 100 });
      const networkStatus = optionalString(body.networkStatus, "networkStatus", { max: 24 });
      const deviceInfo = optionalString(body.deviceInfo, "deviceInfo", { max: 120 });
      // A device that was offline may report an older occurrence time; the value
      // is bounded so a wrong clock cannot distort the incident timeline.
      const occurredAt = requireNumber(body.occurredAt ?? Date.now(), "occurredAt", {
        min: Date.now() - 7 * 24 * 60 * 60 * 1000,
        max: Date.now() + 5 * 60 * 1000,
      });

      await enforceRateLimit(env.DB, `sos:user:${auth.userId}`, RATE_LIMITS.sosPerUser);
      await enforceRateLimit(env.DB, `sos:ip:${context.clientIp ?? "unknown"}`, RATE_LIMITS.sosPerIp);

      const now = Date.now();
      const { event, created } = await createSosEvent(env, {
        id: randomId(),
        userId: auth.userId,
        clientEventId,
        triggerSource,
        latitude,
        longitude,
        accuracyM,
        batteryLevel,
        networkStatus,
        deviceInfo,
        occurredAt,
        now,
      });

      // Store the emergency location as a time-boxed share (24h) for the family.
      if (latitude !== undefined && longitude !== undefined) {
        await recordLocationShare(env, {
          id: randomId(),
          userId: auth.userId,
          groupId: null,
          latitude,
          longitude,
          accuracyM,
          batteryLevel,
          source: "SOS",
          ttlMs: 24 * 60 * 60 * 1000,
          now,
        });
      }

      const publicEvent = toPublicSosEvent(event);
      const realtimePayload = {
        event: { kind: "SOS_CREATED" as const, at: now, data: publicEvent as unknown as Record<string, unknown> },
        trackSos: {
          sosId: event.id,
          userId: auth.userId,
          triggerSource: event.trigger_source,
          status: event.status,
          latitude: event.latitude,
          longitude: event.longitude,
          occurredAt: event.occurred_at,
        },
      };

      const familyDelivered = await notifyFamily(env, auth.userId, realtimePayload);
      const geoDelivered =
        latitude !== undefined && longitude !== undefined
          ? await notifyGeoCell(env, latitude, longitude, {
              event: {
                kind: "SOS_CREATED",
                at: now,
                data: { ...publicEvent, approximate: true } as unknown as Record<string, unknown>,
              },
            })
          : true;

      await recordAudit(env, config, {
        userId: auth.userId,
        action: created ? "sos.created" : "sos.replayed",
        outcome: "SUCCESS",
        clientIp: context.clientIp,
        userAgent: request.headers.get("user-agent"),
        // No coordinates: audit logs must not become a location history.
        details: `trigger=${triggerSource} sosId=${event.id}`,
        now,
      });

      return dataResponse(
        {
          event: publicEvent,
          duplicate: !created,
          acknowledgedAt: now,
          realtime: { family: familyDelivered, nearby: geoDelivered },
        },
        created ? 201 : 200,
      );
    },
    { auth: true },
  );

  router.get(
    "/v1/sos",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const limitParam = context.url.searchParams.get("limit");
      const limit = limitParam ? Number.parseInt(limitParam, 10) : 50;
      const events = await listSosEvents(context.env, auth.userId, Number.isFinite(limit) ? limit : 50);
      return dataResponse({ events: events.map(toPublicSosEvent) });
    },
    { auth: true },
  );

  router.get(
    "/v1/sos/:sosId",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const sosId = requireString(context.params.sosId, "sosId", { max: 64, trim: false });

      const event = await getSosEvent(context.env, sosId);
      if (!event) throw notFound("sos_not_found", "Emergency event not found.");
      if (event.user_id !== auth.userId && !(await sharesFamilyGroup(context.env, auth.userId, event.user_id))) {
        throw forbidden("sos_access_denied", "You do not have access to this emergency event.");
      }
      return dataResponse({ event: toPublicSosEvent(event) });
    },
    { auth: true },
  );

  router.post(
    "/v1/sos/:sosId/acknowledge",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const sosId = requireString(context.params.sosId, "sosId", { max: 64, trim: false });

      const event = await getSosEvent(context.env, sosId);
      if (!event) throw notFound("sos_not_found", "Emergency event not found.");
      const isOwner = event.user_id === auth.userId;
      if (!isOwner && !(await sharesFamilyGroup(context.env, auth.userId, event.user_id))) {
        throw forbidden("sos_access_denied", "You do not have access to this emergency event.");
      }
      if (event.status === "RESOLVED" || event.status === "CANCELLED") {
        throw conflict("sos_closed", "This emergency event is already closed.");
      }

      const now = Date.now();
      const updated = await updateSosStatus(context.env, sosId, "ACKNOWLEDGED", {
        now,
        actorUserId: auth.userId,
        note: `acknowledged by ${isOwner ? "subject" : "guardian"}`,
      });

      await notifyFamily(context.env, event.user_id, {
        event: {
          kind: "SOS_UPDATED",
          at: now,
          data: { sosId, status: "ACKNOWLEDGED", acknowledgedAt: now },
        },
      });

      return dataResponse({ event: updated ? toPublicSosEvent(updated) : null });
    },
    { auth: true },
  );

  router.post(
    "/v1/sos/:sosId/resolve",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const { env, config, request } = context;
      const sosId = requireString(context.params.sosId, "sosId", { max: 64, trim: false });
      const body = asRecord(await readJsonBody(request));
      const note = optionalString(body.note, "note", { max: 300 });
      const cancelled = body.cancelled === true;

      const event = await getSosEvent(env, sosId);
      if (!event) throw notFound("sos_not_found", "Emergency event not found.");
      const isOwner = event.user_id === auth.userId;
      if (!isOwner && !(await sharesFamilyGroup(env, auth.userId, event.user_id))) {
        throw forbidden("sos_access_denied", "You do not have access to this emergency event.");
      }

      const now = Date.now();
      const status = cancelled ? "CANCELLED" : "RESOLVED";
      const updated = await updateSosStatus(env, sosId, status, { now, actorUserId: auth.userId, note: note ?? null });

      await clearActiveSos(env, event.user_id);
      await notifyFamily(env, event.user_id, {
        event: { kind: "SOS_UPDATED", at: now, data: { sosId, status, resolvedAt: now } },
      });
      await recordAudit(env, config, {
        userId: auth.userId,
        action: "sos.resolved",
        outcome: "SUCCESS",
        clientIp: context.clientIp,
        userAgent: request.headers.get("user-agent"),
        details: `status=${status} sosId=${sosId}`,
        now,
      });

      return dataResponse({ event: updated ? toPublicSosEvent(updated) : null });
    },
    { auth: true },
  );

  router.get(
    "/v1/family/groups/:groupId/sos/active",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const groupId = requireString(context.params.groupId, "groupId", { max: 64, trim: false });

      const membership = await getMembership(context.env, groupId, auth.userId);
      if (!membership) throw forbidden("not_a_member", "You are not a member of this family group.");

      const events = await activeSosForGroup(context.env, groupId);
      return dataResponse({ events: events.map(toPublicSosEvent) });
    },
    { auth: true },
  );
}
