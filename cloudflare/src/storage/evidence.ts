/**
 * R2 evidence storage.
 *
 * The bucket is private: objects are only reachable through authenticated API
 * calls, and object keys are generated server-side (`users/<userId>/<yyyy>/<mm>/<uuid>`)
 * so caller input can never influence a path (no traversal, no collisions).
 * Size, MIME type and retention are enforced here.
 */

import type { AppConfig, Env } from "../env";
import { randomId, sha256Base64Url } from "../util/crypto";
import { badRequest, notFound, payloadTooLarge, forbidden } from "../util/errors";
import { sanitizeFileName } from "../util/validate";
import { sharesFamilyGroup } from "../db/family";
import { getSosEvent } from "../db/sos";

const ALLOWED_MIME_TYPES = new Set([
  "image/jpeg",
  "image/png",
  "image/webp",
  "audio/mpeg",
  "audio/mp4",
  "audio/aac",
  "audio/ogg",
  "audio/3gpp",
  "video/mp4",
  "application/pdf",
  "application/json",
  "text/plain",
]);

export interface EvidenceRow {
  id: string;
  user_id: string;
  sos_event_id: string | null;
  object_key: string;
  content_type: string;
  size_bytes: number;
  sha256: string;
  created_at: number;
  expires_at: number;
  deleted_at: number | null;
}

export interface PublicEvidence {
  id: string;
  sosEventId: string | null;
  contentType: string;
  sizeBytes: number;
  sha256: string;
  createdAt: number;
  expiresAt: number;
  fileName?: string;
}

function toPublicEvidence(row: EvidenceRow): PublicEvidence {
  return {
    id: row.id,
    sosEventId: row.sos_event_id,
    contentType: row.content_type,
    sizeBytes: row.size_bytes,
    sha256: row.sha256,
    createdAt: row.created_at,
    expiresAt: row.expires_at,
  };
}

export async function storeEvidence(
  env: Env,
  config: AppConfig,
  request: Request,
  input: { userId: string; sosEventId?: string | null; now: number },
): Promise<PublicEvidence> {
  const contentType = (request.headers.get("content-type") ?? "").split(";")[0]?.trim().toLowerCase() ?? "";
  if (!ALLOWED_MIME_TYPES.has(contentType)) {
    throw badRequest("unsupported_media_type", "This file type is not accepted for evidence upload.");
  }

  const declaredLength = Number.parseInt(request.headers.get("content-length") ?? "", 10);
  if (Number.isFinite(declaredLength) && declaredLength > config.maxEvidenceBytes) {
    throw payloadTooLarge("payload_too_large", "Evidence file exceeds the maximum allowed size.");
  }

  if (input.sosEventId) {
    const sos = await getSosEvent(env, input.sosEventId);
    if (!sos || sos.user_id !== input.userId) {
      throw forbidden("sos_access_denied", "Evidence can only be linked to your own emergency events.");
    }
  }

  const bytes = new Uint8Array(await request.arrayBuffer());
  if (bytes.byteLength === 0) throw badRequest("empty_file", "Evidence file is empty.");
  if (bytes.byteLength > config.maxEvidenceBytes) {
    throw payloadTooLarge("payload_too_large", "Evidence file exceeds the maximum allowed size.");
  }

  const evidenceId = randomId();
  const now = new Date(input.now);
  const objectKey = `users/${input.userId}/${now.getUTCFullYear()}/${String(now.getUTCMonth() + 1).padStart(2, "0")}/${evidenceId}`;
  const sha256 = await sha256Base64Url(bytes);
  const fileName = sanitizeFileName(request.headers.get("x-file-name") ?? "evidence.bin");
  const expiresAt = input.now + config.evidenceRetentionDays * 24 * 60 * 60 * 1000;

  await env.EVIDENCE.put(objectKey, bytes, {
    httpMetadata: { contentType },
    customMetadata: { userId: input.userId, evidenceId, fileName, sha256 },
  });

  try {
    await env.DB.prepare(
      `INSERT INTO evidence_files (id, user_id, sos_event_id, object_key, content_type, size_bytes, sha256, created_at, expires_at)
       VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)`,
    )
      .bind(
        evidenceId,
        input.userId,
        input.sosEventId ?? null,
        objectKey,
        contentType,
        bytes.byteLength,
        sha256,
        input.now,
        expiresAt,
      )
      .run();
  } catch (error) {
    // Never leave an orphan object behind when the metadata write fails.
    await env.EVIDENCE.delete(objectKey);
    throw error;
  }

  return {
    id: evidenceId,
    sosEventId: input.sosEventId ?? null,
    contentType,
    sizeBytes: bytes.byteLength,
    sha256,
    createdAt: input.now,
    expiresAt,
    fileName,
  };
}

async function getEvidenceRow(env: Env, evidenceId: string): Promise<EvidenceRow | null> {
  return await env.DB.prepare(
    `SELECT id, user_id, sos_event_id, object_key, content_type, size_bytes, sha256, created_at, expires_at, deleted_at
     FROM evidence_files WHERE id = ?1 AND deleted_at IS NULL`,
  )
    .bind(evidenceId)
    .first<EvidenceRow>();
}

/**
 * Authorisation: the owner always has access. A family member may fetch
 * evidence that is attached to an emergency event of that family.
 */
async function assertEvidenceAccess(env: Env, row: EvidenceRow, requesterUserId: string): Promise<void> {
  if (row.user_id === requesterUserId) return;
  if (row.sos_event_id) {
    const sos = await getSosEvent(env, row.sos_event_id);
    if (sos && sos.user_id === row.user_id && (await sharesFamilyGroup(env, requesterUserId, row.user_id))) {
      return;
    }
  }
  throw forbidden("evidence_access_denied", "You do not have access to this file.");
}

export async function listEvidence(env: Env, userId: string): Promise<PublicEvidence[]> {
  const rows = await env.DB.prepare(
    `SELECT id, user_id, sos_event_id, object_key, content_type, size_bytes, sha256, created_at, expires_at, deleted_at
     FROM evidence_files WHERE user_id = ?1 AND deleted_at IS NULL ORDER BY created_at DESC LIMIT 100`,
  )
    .bind(userId)
    .all<EvidenceRow>();
  return (rows.results ?? []).map(toPublicEvidence);
}

export async function downloadEvidence(env: Env, evidenceId: string, requesterUserId: string): Promise<Response> {
  const row = await getEvidenceRow(env, evidenceId);
  if (!row) throw notFound("evidence_not_found", "Evidence file not found.");
  await assertEvidenceAccess(env, row, requesterUserId);

  const object = await env.EVIDENCE.get(row.object_key);
  if (!object) throw notFound("evidence_not_found", "Evidence file is no longer stored.");

  const headers = new Headers({
    "content-type": row.content_type,
    "content-length": String(row.size_bytes),
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
    "content-disposition": `attachment; filename="${sanitizeFileName(
      object.customMetadata?.fileName ?? "evidence.bin",
    )}"`,
  });

  return new Response(object.body, { status: 200, headers });
}

export async function deleteEvidence(env: Env, evidenceId: string, requesterUserId: string, now: number): Promise<boolean> {
  const row = await getEvidenceRow(env, evidenceId);
  if (!row) throw notFound("evidence_not_found", "Evidence file not found.");
  if (row.user_id !== requesterUserId) throw forbidden("evidence_access_denied", "You do not own this file.");

  await env.EVIDENCE.delete(row.object_key);
  await env.DB.prepare(`UPDATE evidence_files SET deleted_at = ?1 WHERE id = ?2`).bind(now, evidenceId).run();
  return true;
}

/** Scheduled retention: removes expired objects and their metadata. */
export async function purgeExpiredEvidence(env: Env, now: number): Promise<number> {
  const rows = await env.DB.prepare(
    `SELECT id, object_key FROM evidence_files WHERE deleted_at IS NULL AND expires_at <= ?1 LIMIT 500`,
  )
    .bind(now)
    .all<{ id: string; object_key: string }>();

  const items = rows.results ?? [];
  for (const item of items) {
    await env.EVIDENCE.delete(item.object_key);
    await env.DB.prepare(`UPDATE evidence_files SET deleted_at = ?1 WHERE id = ?2`).bind(now, item.id).run();
  }
  return items.length;
}
