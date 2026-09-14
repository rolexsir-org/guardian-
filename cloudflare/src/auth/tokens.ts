/**
 * Token handling.
 *
 *  * Access tokens: short lived (15 min default) HS256 JWTs signed with
 *    AUTH_SECRET. They carry the user id (`sub`) and session id (`sid`).
 *  * Refresh tokens: 256 bits of CSPRNG output. Only a SHA-256 hash is stored,
 *    rotation is mandatory and replay revokes the whole session.
 */

import {
  base64UrlDecode,
  base64UrlEncode,
  hmacSha256,
  randomToken,
  sha256Hex,
  timingSafeEqual,
} from "../util/crypto";
import { unauthorized } from "../util/errors";

export const ACCESS_TOKEN_AUDIENCE = "guardian-android";

export interface AccessTokenClaims {
  sub: string;
  sid: string;
  iat: number;
  exp: number;
  iss: string;
  aud: string;
}

interface JwtHeader {
  alg: string;
  typ: string;
}

function encodeSegment(value: unknown): string {
  return base64UrlEncode(new TextEncoder().encode(JSON.stringify(value)));
}

function decodeSegment<T>(segment: string): T {
  const text = new TextDecoder().decode(base64UrlDecode(segment));
  return JSON.parse(text) as T;
}

export async function signAccessToken(
  secret: string,
  issuer: string,
  claims: { sub: string; sid: string; issuedAt: number; ttlSeconds: number },
): Promise<{ token: string; expiresAt: number }> {
  const expiresAt = claims.issuedAt + claims.ttlSeconds * 1000;
  const header: JwtHeader = { alg: "HS256", typ: "JWT" };
  const payload: AccessTokenClaims = {
    sub: claims.sub,
    sid: claims.sid,
    iat: Math.floor(claims.issuedAt / 1000),
    exp: Math.floor(expiresAt / 1000),
    iss: issuer,
    aud: ACCESS_TOKEN_AUDIENCE,
  };

  const signingInput = `${encodeSegment(header)}.${encodeSegment(payload)}`;
  const signature = await hmacSha256(secret, signingInput);
  return { token: `${signingInput}.${base64UrlEncode(signature)}`, expiresAt };
}

export async function verifyAccessToken(
  secret: string,
  issuer: string,
  token: string,
  now: number = Date.now(),
): Promise<AccessTokenClaims> {
  const parts = token.split(".");
  if (parts.length !== 3) throw unauthorized("invalid_token", "Access token is malformed.");

  const [headerSegment, payloadSegment, signatureSegment] = parts as [string, string, string];

  let header: JwtHeader;
  let claims: AccessTokenClaims;
  try {
    header = decodeSegment<JwtHeader>(headerSegment);
    claims = decodeSegment<AccessTokenClaims>(payloadSegment);
  } catch {
    throw unauthorized("invalid_token", "Access token is malformed.");
  }

  // Only the expected algorithm is accepted; "none" and friends are rejected.
  if (header.alg !== "HS256" || header.typ !== "JWT") {
    throw unauthorized("invalid_token", "Unsupported token algorithm.");
  }

  let providedSignature: Uint8Array;
  try {
    providedSignature = base64UrlDecode(signatureSegment);
  } catch {
    throw unauthorized("invalid_token", "Access token is malformed.");
  }

  const expectedSignature = await hmacSha256(secret, `${headerSegment}.${payloadSegment}`);
  if (!timingSafeEqual(expectedSignature, providedSignature)) {
    throw unauthorized("invalid_token", "Access token signature is invalid.");
  }

  if (typeof claims.exp !== "number" || claims.exp * 1000 <= now) {
    throw unauthorized("token_expired", "Access token has expired.");
  }
  if (typeof claims.iat !== "number" || claims.iat * 1000 > now + 60_000) {
    throw unauthorized("invalid_token", "Access token has an invalid issue time.");
  }
  if (claims.iss !== issuer) throw unauthorized("invalid_token", "Access token issuer mismatch.");
  if (claims.aud !== ACCESS_TOKEN_AUDIENCE) throw unauthorized("invalid_token", "Access token audience mismatch.");
  if (typeof claims.sub !== "string" || claims.sub.length === 0) {
    throw unauthorized("invalid_token", "Access token subject is missing.");
  }
  if (typeof claims.sid !== "string" || claims.sid.length === 0) {
    throw unauthorized("invalid_token", "Access token session is missing.");
  }

  return claims;
}

export function generateRefreshToken(): string {
  return randomToken(32);
}

export async function hashRefreshToken(token: string): Promise<string> {
  return sha256Hex(`guardian-refresh:${token}`);
}
