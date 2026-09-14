import { Router } from "../http";
import { registerAuthRoutes } from "./authRoutes";
import { registerProfileRoutes } from "./profileRoutes";
import { registerFamilyRoutes } from "./familyRoutes";
import { registerSosRoutes } from "./sosRoutes";
import { registerSafetyEventRoutes } from "./safetyEventRoutes";
import { registerRealtimeRoutes } from "./realtimeRoutes";
import { registerEvidenceRoutes } from "./evidenceRoutes";

/** Builds the complete API surface. Public surface only — no debug routes. */
export function buildRouter(): Router {
  const router = new Router();

  registerAuthRoutes(router);
  registerProfileRoutes(router);
  registerFamilyRoutes(router);
  registerSosRoutes(router);
  registerSafetyEventRoutes(router);
  registerRealtimeRoutes(router);
  registerEvidenceRoutes(router);

  return router;
}
