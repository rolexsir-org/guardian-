/**
 * Evidence (R2) endpoints. Firebase Storage was never used by Guardian, so R2
 * is wired only for the emergency evidence vault: authenticated upload, owner
 * (or family, for SOS-linked files) download, deletion and retention purge.
 * The bucket is never public.
 */

import { unauthorized } from "../util/errors";
import { optionalString, requireString } from "../util/validate";
import { enforceRateLimit, RATE_LIMITS } from "../util/ratelimit";
import { deleteEvidence, downloadEvidence, listEvidence, storeEvidence } from "../storage/evidence";
import { dataResponse, emptyResponse, type Router } from "../http";

export function registerEvidenceRoutes(router: Router): void {
  router.get(
    "/v1/evidence",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const files = await listEvidence(context.env, auth.userId);
      return dataResponse({ files });
    },
    { auth: true },
  );

  router.post(
    "/v1/evidence",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();

      await enforceRateLimit(context.env.DB, `evidence:${auth.userId}`, RATE_LIMITS.evidencePerUser);

      const sosEventId = optionalString(context.request.headers.get("x-sos-event-id"), "x-sos-event-id", {
        max: 64,
        trim: false,
      });

      const file = await storeEvidence(context.env, context.config, context.request, {
        userId: auth.userId,
        sosEventId: sosEventId ?? null,
        now: Date.now(),
      });

      return dataResponse({ file }, 201);
    },
    { auth: true },
  );

  router.get(
    "/v1/evidence/:evidenceId",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const evidenceId = requireString(context.params.evidenceId, "evidenceId", { max: 64, trim: false });
      return await downloadEvidence(context.env, evidenceId, auth.userId);
    },
    { auth: true },
  );

  router.delete(
    "/v1/evidence/:evidenceId",
    async (context) => {
      const auth = context.auth;
      if (!auth) throw unauthorized();
      const evidenceId = requireString(context.params.evidenceId, "evidenceId", { max: 64, trim: false });
      await deleteEvidence(context.env, evidenceId, auth.userId, Date.now());
      return emptyResponse(204);
    },
    { auth: true },
  );
}
