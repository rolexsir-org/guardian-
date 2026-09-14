import { describe, expect, it } from "vitest";
import { api, errorCode, registerUser, testEnv } from "./helpers";

const JPEG_BYTES = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01]);

async function upload(
  token: string,
  bytes: Uint8Array,
  options: { contentType?: string; fileName?: string; sosEventId?: string } = {},
): Promise<ReturnType<typeof api>> {
  const headers: Record<string, string> = { "content-type": options.contentType ?? "image/jpeg" };
  if (options.fileName) headers["x-file-name"] = options.fileName;
  if (options.sosEventId) headers["x-sos-event-id"] = options.sosEventId;
  return await api("/v1/evidence", { method: "POST", token, raw: bytes, headers });
}

describe("evidence storage (R2)", () => {
  it("stores an authenticated upload with a server-generated object key", async () => {
    const owner = await registerUser("evidence-owner");

    const response = await upload(owner.accessToken, JPEG_BYTES, { fileName: "sos-photo.jpg" });
    expect(response.status).toBe(201);

    const file = (response.body as { data: { file: { id: string; sizeBytes: number; sha256: string } } }).data.file;
    expect(file.sizeBytes).toBe(JPEG_BYTES.byteLength);
    expect(file.sha256.length).toBeGreaterThan(20);

    const row = await testEnv.DB.prepare("SELECT object_key, content_type FROM evidence_files WHERE id = ?1")
      .bind(file.id)
      .first<{ object_key: string; content_type: string }>();

    // Keys are derived from the authenticated user id only — no client input.
    expect(row?.object_key.startsWith(`users/${owner.userId}/`)).toBe(true);
    expect(row?.object_key).not.toContain("..");
    expect(row?.object_key).not.toContain("sos-photo");
    expect(row?.content_type).toBe("image/jpeg");
  });

  it("requires authentication and rejects unsupported media types", async () => {
    const anonymous = await upload("", JPEG_BYTES);
    expect(anonymous.status).toBe(401);

    const owner = await registerUser("evidence-mime");
    const html = await upload(owner.accessToken, new Uint8Array([60, 104, 116, 109, 108, 62]), {
      contentType: "text/html",
    });
    expect(html.status).toBe(400);
    expect(errorCode(html.body)).toBe("unsupported_media_type");

    const empty = await upload(owner.accessToken, new Uint8Array(0));
    expect(empty.status).toBe(400);
    expect(errorCode(empty.body)).toBe("empty_file");
  });

  it("enforces the configured maximum size", async () => {
    const owner = await registerUser("evidence-size");
    const oversized = new Uint8Array(4096);
    oversized.fill(1);
    const response = await upload(owner.accessToken, oversized);
    expect(response.status).toBe(413);
    expect(errorCode(response.body)).toBe("payload_too_large");
  });

  it("sanitises file names and never reflects path separators", async () => {
    const owner = await registerUser("evidence-name");
    const response = await upload(owner.accessToken, JPEG_BYTES, { fileName: "../../etc/passwd;rm -rf /.jpg" });
    expect(response.status).toBe(201);

    const download = await api("/v1/evidence/" + (response.body as { data: { file: { id: string } } }).data.file.id, {
      token: owner.accessToken,
    });
    expect(download.status).toBe(200);
    const disposition = download.headers.get("content-disposition") ?? "";
    expect(disposition).not.toContain("..");
    expect(disposition).not.toContain("/etc/passwd");
    // Only inert characters survive sanitisation.
    const fileName = /filename="([^"]+)"/.exec(disposition)?.[1] ?? "";
    expect(fileName).toMatch(/^[A-Za-z0-9._-]+$/);
    expect(fileName).not.toContain("passwd");
  });

  it("lets the owner download the identical bytes and blocks other accounts", async () => {
    const owner = await registerUser("evidence-access");
    const stranger = await registerUser("evidence-stranger");

    const created = await upload(owner.accessToken, JPEG_BYTES);
    const evidenceId = (created.body as { data: { file: { id: string } } }).data.file.id;

    const ownerDownload = await api(`/v1/evidence/${evidenceId}`, { token: owner.accessToken });
    expect(ownerDownload.status).toBe(200);
    expect(ownerDownload.headers.get("content-type")).toBe("image/jpeg");
    expect(ownerDownload.headers.get("cache-control")).toBe("no-store");

    const strangerDownload = await api(`/v1/evidence/${evidenceId}`, { token: stranger.accessToken });
    expect(strangerDownload.status).toBe(403);
    expect(errorCode(strangerDownload.body)).toBe("evidence_access_denied");

    const strangerDelete = await api(`/v1/evidence/${evidenceId}`, { method: "DELETE", token: stranger.accessToken });
    expect(strangerDelete.status).toBe(403);

    const missing = await api("/v1/evidence/00000000-0000-0000-0000-000000000000", { token: owner.accessToken });
    expect(missing.status).toBe(404);
  });

  it("allows a family guardian to fetch evidence attached to an emergency", async () => {
    const owner = await registerUser("evidence-family-owner");
    const guardian = await registerUser("evidence-guardian");
    const stranger = await registerUser("evidence-outsider");

    const group = await api<{ data: { group: { id: string } } }>("/v1/family/groups", {
      token: owner.accessToken,
      body: { name: "Evidence Family" },
    });
    const invite = await api<{ data: { invite: { code: string } } }>(
      `/v1/family/groups/${group.body.data.group.id}/invites`,
      { token: owner.accessToken, body: { role: "GUARDIAN" } },
    );
    await api("/v1/family/join", { token: guardian.accessToken, body: { inviteCode: invite.body.data.invite.code } });

    const sos = await api<{ data: { event: { id: string } } }>("/v1/sos", {
      token: owner.accessToken,
      body: { clientEventId: "evidence-sos-0001", triggerSource: "BUTTON", occurredAt: Date.now() },
    });

    const created = await upload(owner.accessToken, JPEG_BYTES, { sosEventId: sos.body.data.event.id });
    expect(created.status).toBe(201);
    const evidenceId = (created.body as { data: { file: { id: string } } }).data.file.id;

    const guardianDownload = await api(`/v1/evidence/${evidenceId}`, { token: guardian.accessToken });
    expect(guardianDownload.status).toBe(200);

    const strangerDownload = await api(`/v1/evidence/${evidenceId}`, { token: stranger.accessToken });
    expect(strangerDownload.status).toBe(403);

    // Evidence cannot be attached to somebody else's emergency.
    const foreignSos = await api("/v1/sos", {
      method: "POST",
      token: guardian.accessToken,
      body: { clientEventId: "evidence-sos-0002", triggerSource: "BUTTON", occurredAt: Date.now() },
    });
    const foreignId = (foreignSos.body as { data: { event: { id: string } } }).data.event.id;
    const crossLink = await upload(owner.accessToken, JPEG_BYTES, { sosEventId: foreignId });
    expect(crossLink.status).toBe(403);
  });

  it("deletes objects on request and purges expired evidence", async () => {
    const owner = await registerUser("evidence-delete");

    const created = await upload(owner.accessToken, JPEG_BYTES);
    const evidenceId = (created.body as { data: { file: { id: string } } }).data.file.id;
    const keyRow = await testEnv.DB.prepare("SELECT object_key FROM evidence_files WHERE id = ?1")
      .bind(evidenceId)
      .first<{ object_key: string }>();
    expect(await testEnv.EVIDENCE.get(keyRow?.object_key ?? "")).not.toBeNull();

    const removed = await api(`/v1/evidence/${evidenceId}`, { method: "DELETE", token: owner.accessToken });
    expect(removed.status).toBe(204);
    expect(await testEnv.EVIDENCE.get(keyRow?.object_key ?? "")).toBeNull();

    const afterDelete = await api(`/v1/evidence/${evidenceId}`, { token: owner.accessToken });
    expect(afterDelete.status).toBe(404);

    // Retention: an expired object is removed by the scheduled maintenance job.
    const expiring = await upload(owner.accessToken, JPEG_BYTES);
    const expiringId = (expiring.body as { data: { file: { id: string } } }).data.file.id;
    const expiringKey = await testEnv.DB.prepare("SELECT object_key FROM evidence_files WHERE id = ?1")
      .bind(expiringId)
      .first<{ object_key: string }>();

    const { runMaintenance } = await import("../src/maintenance");
    const { resolveConfig } = await import("../src/env");
    const report = await runMaintenance(testEnv, resolveConfig(testEnv), Date.now() + 365 * 24 * 60 * 60 * 1000);
    expect(report.evidencePurged).toBeGreaterThanOrEqual(1);
    expect(await testEnv.EVIDENCE.get(expiringKey?.object_key ?? "")).toBeNull();
  });

  it("lists only the caller's own files", async () => {
    const owner = await registerUser("evidence-list");
    const other = await registerUser("evidence-list-other");
    await upload(owner.accessToken, JPEG_BYTES);

    const own = await api<{ data: { files: unknown[] } }>("/v1/evidence", { token: owner.accessToken });
    expect(own.body.data.files.length).toBeGreaterThanOrEqual(1);

    const others = await api<{ data: { files: unknown[] } }>("/v1/evidence", { token: other.accessToken });
    expect(others.body.data.files).toHaveLength(0);
  });
});
