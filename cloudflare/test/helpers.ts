import { env, SELF } from "cloudflare:test";
import type { Env } from "../src/env";

/**
 * The `cloudflare:test` env is typed through the generated `Cloudflare.Env`
 * namespace; this cast binds it to the Worker's own Env interface so tests fail
 * to compile when a binding is renamed.
 */
export const testEnv = env as unknown as Env;

export const TEST_SECRET = "test-secret-value-not-used-in-production";
export const TEST_ISSUER = "guardian-cloudflare";

export interface TestSession {
  userId: string;
  email: string;
  password: string;
  sessionId: string;
  accessToken: string;
  refreshToken: string;
  accessExpiresAt: number;
  refreshExpiresAt: number;
}

export interface JsonResponse<T = Record<string, unknown>> {
  status: number;
  body: T;
  headers: Headers;
}

export interface RequestOptions {
  method?: string;
  token?: string | null;
  body?: unknown;
  headers?: Record<string, string>;
  /** Distinct fake client IP so per-IP rate limits do not collide across tests. */
  ip?: string;
  raw?: BodyInit;
}

export function uniqueIp(seed: string): string {
  // Deterministic pseudo IP derived from the seed (test-only value).
  let hash = 0;
  for (let index = 0; index < seed.length; index += 1) {
    hash = (hash * 31 + seed.charCodeAt(index)) % 65536;
  }
  return `203.0.113.${(hash % 250) + 1}`;
}

export async function api<T = Record<string, unknown>>(path: string, options: RequestOptions = {}): Promise<JsonResponse<T>> {
  const headers = new Headers(options.headers ?? {});
  if (options.body !== undefined) headers.set("content-type", "application/json");
  if (options.token) headers.set("authorization", `Bearer ${options.token}`);
  if (options.ip) headers.set("cf-connecting-ip", options.ip);

  const response = await SELF.fetch(`https://guardian.test${path}`, {
    method: options.method ?? (options.body !== undefined || options.raw !== undefined ? "POST" : "GET"),
    headers,
    body: options.raw ?? (options.body !== undefined ? JSON.stringify(options.body) : undefined),
  });

  const text = await response.text();
  let parsed: unknown = null;
  if (text.length > 0) {
    try {
      parsed = JSON.parse(text);
    } catch {
      parsed = { raw: text };
    }
  }
  return { status: response.status, body: parsed as T, headers: response.headers };
}

interface AuthEnvelope {
  data: {
    user: { id: string; email: string; displayName: string };
    session: {
      sessionId: string;
      accessToken: string;
      refreshToken: string;
      accessExpiresAt: number;
      refreshExpiresAt: number;
    };
  };
}

export async function registerUser(seed: string, password = "GuardianPass123"): Promise<TestSession> {
  const email = `${seed}-${Math.random().toString(36).slice(2, 10)}@guardian.test`;
  const response = await api<AuthEnvelope>("/v1/auth/register", {
    body: { email, password, displayName: `Guardian ${seed}` },
    ip: uniqueIp(`${seed}:${email}`),
  });
  if (response.status !== 201) {
    throw new Error(`registration failed: ${response.status} ${JSON.stringify(response.body)}`);
  }
  const { user, session } = response.body.data;
  return {
    userId: user.id,
    email: user.email,
    password,
    sessionId: session.sessionId,
    accessToken: session.accessToken,
    refreshToken: session.refreshToken,
    accessExpiresAt: session.accessExpiresAt,
    refreshExpiresAt: session.refreshExpiresAt,
  };
}

export async function loginUser(email: string, password: string, ip?: string): Promise<TestSession> {
  const response = await api<AuthEnvelope>("/v1/auth/login", { body: { email, password }, ip: ip ?? uniqueIp(email) });
  if (response.status !== 200) {
    throw new Error(`login failed: ${response.status} ${JSON.stringify(response.body)}`);
  }
  const { user, session } = response.body.data;
  return {
    userId: user.id,
    email: user.email,
    password,
    sessionId: session.sessionId,
    accessToken: session.accessToken,
    refreshToken: session.refreshToken,
    accessExpiresAt: session.accessExpiresAt,
    refreshExpiresAt: session.refreshExpiresAt,
  };
}

export function errorCode(body: unknown): string | null {
  const record = body as { error?: { code?: string } } | null;
  return record?.error?.code ?? null;
}
