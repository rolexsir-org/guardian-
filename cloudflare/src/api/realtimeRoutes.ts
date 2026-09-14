/**
 * Realtime WebSocket entry points.
 *
 * The Worker authenticates the caller, authorises the scope and only then
 * forwards the upgrade request to the Durable Object with trusted headers. The
 * client can never choose an arbitrary scope: it is always derived from a
 * server-side membership check or from the caller's own coordinates.
 */

import { badRequest, forbidden, unauthorized } from "../util/errors";
import { requireLatitude, requireLongitude, requireString } from "../util/validate";
import { getMembership } from "../db/family";
import { geohash } from "../util/geohash";
import { familyScope, geoScope } from "../realtime/protocol";
import type { RequestContext, Router } from "../http";

const HUB_ORIGIN = "https://safety-hub.internal";

async function forwardToHub(context: RequestContext, scope: string, userId: string): Promise<Response> {
  const id = context.env.SAFETY_HUB.idFromName(scope);
  const stub = context.env.SAFETY_HUB.get(id);

  const headers = new Headers(context.request.headers);
  headers.set("x-guardian-scope", scope);
  headers.set("x-guardian-user-id", userId);
  headers.delete("cookie");

  const response = await stub.fetch(
    new Request(`${HUB_ORIGIN}/connect`, {
      method: "GET",
      headers,
    }),
  );

  if (response.webSocket === null || response.webSocket === undefined) {
    return new Response(
      JSON.stringify({ error: { code: "realtime_unavailable", message: "Realtime channel could not be established." } }),
      { status: 503, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" } },
    );
  }

  return new Response(null, { status: 101, webSocket: response.webSocket });
}

export function registerRealtimeRoutes(router: Router): void {
  router.get(
    "/v1/realtime/family/:groupId",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const groupId = requireString(context.params.groupId, "groupId", { max: 64, trim: false });

      const membership = await getMembership(context.env, groupId, auth.userId);
      if (!membership) throw forbidden("not_a_member", "You are not a member of this family group.");

      return await forwardToHub(context, familyScope(groupId), auth.userId);
    },
    { auth: true },
  );

  router.get(
    "/v1/realtime/nearby",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();

      const latParam = context.url.searchParams.get("latitude");
      const lngParam = context.url.searchParams.get("longitude");
      if (!latParam || !lngParam) throw badRequest("missing_location", "latitude and longitude are required.");

      const latitude = requireLatitude(Number(latParam));
      const longitude = requireLongitude(Number(lngParam));
      const cell = geohash(latitude, longitude, 4);

      return await forwardToHub(context, geoScope(cell), auth.userId);
    },
    { auth: true },
  );
}
