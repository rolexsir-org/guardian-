import type { AppConfig, Env } from "../env";
import { keyedFingerprint, randomId } from "../util/crypto";

export type AuditOutcome = "SUCCESS" | "FAILURE" | "DENIED";

export interface AuditInput {
  userId?: string | null;
  action: string;
  outcome: AuditOutcome;
  clientIp?: string | null;
  userAgent?: string | null;
  /** Must never contain passwords, tokens or precise location data. */
  details?: string | null;
  now: number;
}

/**
 * Writes a security/abuse audit entry. IP addresses are stored only as a keyed
 * fingerprint (HMAC with AUTH_SECRET) so no raw network identifiers are kept.
 */
export async function recordAudit(env: Env, config: AppConfig, input: AuditInput): Promise<void> {
  const ipHash = input.clientIp ? await keyedFingerprint(config.authSecret, input.clientIp) : null;
  try {
    await env.DB.prepare(
      `INSERT INTO audit_logs (id, user_id, action, outcome, ip_hash, user_agent, details, created_at)
       VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)`,
    )
      .bind(
        randomId(),
        input.userId ?? null,
        input.action,
        input.outcome,
        ipHash,
        input.userAgent ? input.userAgent.slice(0, 200) : null,
        input.details ? input.details.slice(0, 500) : null,
        input.now,
      )
      .run();
  } catch (error) {
    // Auditing must never break the caller's flow; failures are logged only.
    console.error(JSON.stringify({ level: "error", event: "audit_write_failed", message: String(error) }));
  }
}
