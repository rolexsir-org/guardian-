/**
 * Guardian Cloudflare Worker entry point.
 *
 * Request pipeline:
 *   CORS guard -> configuration check (fail closed) -> route match ->
 *   authentication (when required) -> handler -> response headers.
 *
 * Errors are translated centrally and never leak internals, stack traces or
 * secret material.
 */

import { ConfigError, isConfigured, resolveConfig, type AppConfig, type Env } from "./env";
import { buildRouter } from "./api";
import { authenticateRequest } from "./auth/sessions";
import {
  checkOrigin,
  clientIp,
  corsHeaders,
  emptyResponse,
  errorResponse,
  jsonResponse,
  type RequestContext,
  type Route,
} from "./http";
import { runMaintenance } from "./maintenance";
import { SafetyHub } from "./safety/SafetyHub";

// The Durable Object class must be exported from the Worker entry point.
export { SafetyHub };

const router = buildRouter();

function withHeaders(response: Response, extra: Record<string, string>): Response {
  const headers = new Headers(response.headers);
  for (const [key, value] of Object.entries(extra)) {
    if (value.length > 0) headers.set(key, value);
  }
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
}

async function handleRequest(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const requestId = crypto.randomUUID();
  const url = new URL(request.url);
  const origin = checkOrigin(request, (env.CORS_ALLOWED_ORIGINS ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter((value) => value.length > 0));

  try {
    if (origin === "") {
      return jsonResponse({ error: { code: "origin_not_allowed", message: "Origin is not allowed." } }, 403, {
        "x-request-id": requestId,
      });
    }

    const cors = corsHeaders(origin ?? "");

    if (request.method === "OPTIONS") {
      return emptyResponse(204, cors);
    }

    // Liveness probe: no configuration detail beyond a boolean is exposed.
    if (url.pathname === "/v1/health") {
      return withHeaders(
        jsonResponse({ data: { status: "ok", configured: isConfigured(env), time: Date.now() } }),
        { "x-request-id": requestId, ...cors },
      );
    }

    // Fails closed with 503 `server_misconfigured` when AUTH_SECRET is absent.
    const config: AppConfig = resolveConfig(env);

    const match = router.match(request.method, url.pathname);
    if (!match) {
      const status = router.hasPath(url.pathname) ? 405 : 404;
      const code = status === 405 ? "method_not_allowed" : "not_found";
      return withHeaders(
        jsonResponse({ error: { code, message: status === 405 ? "Method not allowed." : "Endpoint not found." } }, status),
        { "x-request-id": requestId, ...cors },
      );
    }

    const route: Route = match.route;
    const context: RequestContext = {
      request,
      env,
      config,
      ctx,
      url,
      params: match.params,
      requestId,
      clientIp: clientIp(request),
    };

    if (route.requiresAuth) {
      context.auth = await authenticateRequest(env, config, request);
    }

    const response = await route.handler(context);
    const headers: Record<string, string> = { "x-request-id": requestId, ...cors };
    if (response.status === 101 || response.webSocket) {
      return response;
    }
    return withHeaders(response, headers);
  } catch (error) {
    const response = errorResponse(error, requestId);
    const level = error instanceof ConfigError ? "error" : error instanceof Error && error.name === "ApiError" ? "info" : "error";
    if (level === "error" && !(error instanceof ConfigError)) {
      console.error(
        JSON.stringify({ level, requestId, event: "request_failed", path: url.pathname, method: request.method }),
      );
    }
    return withHeaders(response, { "x-request-id": requestId });
  }
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    return await handleRequest(request, env, ctx);
  },

  async scheduled(_controller: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    const config = resolveConfig(env);
    ctx.waitUntil(
      runMaintenance(env, config).then((report) => {
        console.log(JSON.stringify({ level: "info", event: "maintenance_completed", ...report }));
      }),
    );
  },
} satisfies ExportedHandler<Env>;
