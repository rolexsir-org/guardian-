/**
 * Tiny HTTP layer: routing, JSON handling, CORS, security headers and
 * centralised error translation.
 *
 * Every API response is explicit JSON: `{ "data": ... }` on success and
 * `{ "error": { "code": ..., "message": ... } }` on failure. Internal
 * exception details are never returned to clients.
 */

import { ConfigError, type AppConfig, type Env } from "./env";
import { ApiError, redactForLog } from "./util/errors";
import type { AuthContext } from "./auth/sessions";

export interface RequestContext {
  request: Request;
  env: Env;
  config: AppConfig;
  ctx: ExecutionContext;
  url: URL;
  params: Record<string, string>;
  requestId: string;
  auth?: AuthContext;
  clientIp: string | null;
}

export type AuthedRequestContext = RequestContext & { auth: AuthContext };

export type RouteHandler = (context: RequestContext) => Promise<Response>;

export interface Route {
  method: string;
  segments: string[];
  handler: RouteHandler;
  requiresAuth: boolean;
}

export class Router {
  private readonly routes: Route[] = [];

  add(method: string, path: string, handler: RouteHandler, options: { auth?: boolean } = {}): void {
    this.routes.push({
      method: method.toUpperCase(),
      segments: path.split("/").filter((segment) => segment.length > 0),
      handler,
      requiresAuth: options.auth ?? false,
    });
  }

  get(path: string, handler: RouteHandler, options: { auth?: boolean } = {}): void {
    this.add("GET", path, handler, options);
  }

  post(path: string, handler: RouteHandler, options: { auth?: boolean } = {}): void {
    this.add("POST", path, handler, options);
  }

  put(path: string, handler: RouteHandler, options: { auth?: boolean } = {}): void {
    this.add("PUT", path, handler, options);
  }

  patch(path: string, handler: RouteHandler, options: { auth?: boolean } = {}): void {
    this.add("PATCH", path, handler, options);
  }

  delete(path: string, handler: RouteHandler, options: { auth?: boolean } = {}): void {
    this.add("DELETE", path, handler, options);
  }

  match(method: string, pathname: string): { route: Route; params: Record<string, string> } | null {
    const parts = pathname.split("/").filter((segment) => segment.length > 0);
    for (const route of this.routes) {
      if (route.method !== method.toUpperCase()) continue;
      if (route.segments.length !== parts.length) continue;

      const params: Record<string, string> = {};
      let matched = true;
      for (let index = 0; index < route.segments.length; index += 1) {
        const segment = route.segments[index] as string;
        const value = parts[index] as string;
        if (segment.startsWith(":")) {
          params[segment.slice(1)] = decodeURIComponent(value);
        } else if (segment !== value) {
          matched = false;
          break;
        }
      }
      if (matched) return { route, params };
    }
    return null;
  }

  hasPath(pathname: string): boolean {
    const parts = pathname.split("/").filter((segment) => segment.length > 0);
    return this.routes.some((route) => {
      if (route.segments.length !== parts.length) return false;
      return route.segments.every((segment, index) => segment.startsWith(":") || segment === parts[index]);
    });
  }
}

const SECURITY_HEADERS: Record<string, string> = {
  "cache-control": "no-store",
  "x-content-type-options": "nosniff",
  "referrer-policy": "no-referrer",
  "x-frame-options": "DENY",
};

const MAX_JSON_BODY_BYTES = 32_768;

export function jsonResponse(
  data: unknown,
  status = 200,
  extraHeaders: Record<string, string> = {},
): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", ...SECURITY_HEADERS, ...extraHeaders },
  });
}

export function dataResponse(data: unknown, status = 200, extraHeaders: Record<string, string> = {}): Response {
  return jsonResponse({ data }, status, extraHeaders);
}

export function emptyResponse(status = 204, extraHeaders: Record<string, string> = {}): Response {
  return new Response(null, { status, headers: { ...SECURITY_HEADERS, ...extraHeaders } });
}

export function errorResponse(error: unknown, requestId: string): Response {
  if (error instanceof ApiError) {
    const headers: Record<string, string> = { "x-request-id": requestId };
    if (error.retryAfterSeconds !== undefined) headers["retry-after"] = String(error.retryAfterSeconds);
    return jsonResponse({ error: { code: error.code, message: error.message } }, error.status, headers);
  }

  if (error instanceof ConfigError) {
    console.error(
      JSON.stringify({ level: "error", requestId, event: "server_misconfigured", message: error.message }),
    );
    return jsonResponse(
      {
        error: {
          code: "server_misconfigured",
          message: "The Guardian backend is not fully configured. Contact the operator.",
        },
      },
      503,
      { "x-request-id": requestId },
    );
  }

  const summary = error instanceof Error ? redactForLog(error.message) : "unknown error";
  console.error(JSON.stringify({ level: "error", requestId, event: "unhandled_error", message: summary }));
  return jsonResponse(
    { error: { code: "internal_error", message: "An unexpected error occurred." } },
    500,
    { "x-request-id": requestId },
  );
}

/**
 * Browser origins are only allowed when explicitly configured. Native Android
 * clients do not send an `Origin` header and are unaffected.
 */
export function checkOrigin(request: Request, allowedOrigins: string[]): string | null {
  const origin = request.headers.get("origin");
  if (!origin) return null;
  if (allowedOrigins.includes(origin)) return origin;
  return "";
}

export function corsHeaders(origin: string): Record<string, string> {
  if (!origin) {
    return { vary: "Origin" };
  }
  return {
    "access-control-allow-origin": origin,
    "access-control-allow-credentials": "true",
    "access-control-allow-methods": "GET, POST, PUT, PATCH, DELETE, OPTIONS",
    "access-control-allow-headers": "authorization, content-type, x-device-id, x-file-name, x-sos-event-id",
    "access-control-max-age": "600",
    vary: "Origin",
  };
}

export async function readJsonBody(request: Request, maxBytes = MAX_JSON_BODY_BYTES): Promise<unknown> {
  const contentLengthHeader = request.headers.get("content-length");
  if (contentLengthHeader) {
    const declared = Number.parseInt(contentLengthHeader, 10);
    if (Number.isFinite(declared) && declared > maxBytes) {
      throw new ApiError(413, "payload_too_large", "Request body is too large.");
    }
  }

  const raw = await request.text();
  if (raw.length > maxBytes) {
    throw new ApiError(413, "payload_too_large", "Request body is too large.");
  }
  if (raw.trim().length === 0) {
    throw new ApiError(400, "invalid_body", "Request body is required.");
  }

  try {
    return JSON.parse(raw) as unknown;
  } catch {
    throw new ApiError(400, "invalid_json", "Request body is not valid JSON.");
  }
}

export function clientIp(request: Request): string | null {
  const connectingIp = request.headers.get("cf-connecting-ip");
  if (connectingIp) return connectingIp;
  const forwarded = request.headers.get("x-forwarded-for");
  if (forwarded) return forwarded.split(",")[0]?.trim() ?? null;
  return null;
}
