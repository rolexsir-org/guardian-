# Guardian backend (Cloudflare Workers)

Production backend for the Guardian Android app: **Workers + D1 + Durable Objects + R2**.
It replaces Firebase Auth / Realtime Database usage with an HTTP + WebSocket API owned by
this repository. RevenueCat remains the independent subscription system and is not part of
this Worker.

```
Android app  ──HTTPS/JSON──▶  Worker (src/index.ts)  ──▶  D1   (structured data)
             ──WSS────────▶  SafetyHub DO            ──▶  R2   (evidence blobs)
```

## Layout

| Path | Contents |
| --- | --- |
| `src/index.ts` | Worker entry: CORS, routing, auth injection, `scheduled()` retention job, exports the `SafetyHub` Durable Object |
| `src/http.ts` | Router, `RequestContext`, response/error helpers |
| `src/env.ts` | Bindings + runtime config; **fails closed** with 503 `server_misconfigured` when a required value is missing |
| `src/auth/` | Password hashing (PBKDF2-HMAC-SHA256), access tokens (HS256 JWT), sessions + refresh rotation |
| `src/db/` | Typed D1 access: users, sessions, contacts, family, SOS, safety events, locations, presence, feed, audit |
| `src/realtime/protocol.ts` | WebSocket message contract (server `hello`/`event`/`pong`/`sync.gap`/`error`, client `ping`/`sync`) |
| `src/safety/SafetyHub.ts` | Durable Object: hibernatable WebSockets, presence, replay buffer, active SOS |
| `src/safety/broadcast.ts` | Server-side fan-out to family / geo scopes (best effort; D1 stays the source of truth) |
| `src/storage/evidence.ts` | Private R2 evidence storage (allow-list, size cap, server-generated keys, retention) |
| `src/api/` | Route registration only — no business logic |
| `migrations/` | Versioned D1 schema migrations |
| `test/` | Vitest (workerd) suites: smoke, auth, family, sos, safety events, security, realtime, evidence |

## Prerequisites

* Node.js ≥ 20, npm
* A Cloudflare account with Workers, D1, Durable Objects and R2 available
* `npx wrangler login` (or `CLOUDFLARE_API_TOKEN` + `CLOUDFLARE_ACCOUNT_ID` in the environment)

## Required production values

These are **not** in the repository and must be created by the operator. Nothing here is
fabricated — `wrangler.toml` ships a literal placeholder for the D1 id and no secret.

```bash
npm install --legacy-peer-deps

# 1. Database — copy the printed database_id into wrangler.toml ([[d1_databases]])
npx wrangler d1 create guardian-db

# 2. Evidence bucket — create it, then set [[r2_buckets]].bucket_name to the real name
npx wrangler r2 bucket create guardian-evidence

# 3. Token secret — never commit it; store it as a Worker secret
openssl rand -base64 48 | npx wrangler secret put AUTH_SECRET

# 4. Apply the schema
npm run db:migrate:remote
```

`wrangler.toml` bindings: `DB` (D1), `EVIDENCE` (R2), `SAFETY_HUB` (Durable Object
`SafetyHub`, migration tag `v1`, `new_sqlite_classes`). Non-secret variables
(`ACCESS_TOKEN_TTL_SECONDS`, `REFRESH_TOKEN_TTL_SECONDS`, `CORS_ALLOWED_ORIGINS`,
`MAX_EVIDENCE_BYTES`, `EVIDENCE_RETENTION_DAYS`, `LOCATION_RETENTION_DAYS`) live in the
`[vars]` block. The equivalent Android-side template is the repository root
`.env.example` (empty placeholders only).

## Local development

```bash
npm run dev                 # wrangler dev, with the DO + local D1/R2 emulation
npm run db:migrate:local    # apply migrations to the local D1
```

For local runs, put the secret in `cloudflare/.dev.vars` (git ignored):

```
AUTH_SECRET=<48-byte random value>
```

## Tests and checks

```bash
npm test                    # vitest run  (workerd, real bindings via miniflare)
npm run typecheck           # tsc --noEmit
npm run deploy:dry-run      # wrangler deploy --dry-run --outdir dist
```

Test runs use isolated bindings and their own secret; they never touch production data.
`MAX_EVIDENCE_BYTES` is lowered to 2048 in `vitest.config.ts` so size enforcement is
exercised.

## API surface

All responses are `{ "data": ... }` or `{ "error": { "code", "message" } }`. Requests and
responses carry `no-store`, `nosniff`, `no-referrer`; `x-request-id` is echoed back.

| Method + path | Purpose |
| --- | --- |
| `POST /v1/auth/register` · `login` · `refresh` · `logout` · `logout-all` | Account + session lifecycle (refresh tokens rotate on every use) |
| `GET /v1/auth/me` · `GET /v1/auth/sessions` · `DELETE /v1/auth/sessions/:id` | Current identity, device/session list, revoke one session |
| `GET|PUT /v1/me/profile` | Guardian profile (no medical data stored server-side) |
| `GET|POST /v1/me/emergency-contacts`, `PATCH|DELETE .../:contactId` | Emergency contacts (cap 20) |
| `POST /v1/me/presence` | Presence heartbeat |
| `POST|GET /v1/family/groups`, `POST /v1/family/join` | Family group creation / invite redeem |
| `POST /v1/family/groups/:id/invites` | Invite codes (hashed at rest, TTL, rate limited) |
| `GET /v1/family/groups/:id/members`, `PATCH|DELETE .../members/:memberId` | Membership + roles |
| `GET|POST /v1/family/groups/:id/messages`, `GET|POST .../locations` | Family feed and location shares (TTL) |
| `GET|POST /v1/me/checkins`, `PATCH /v1/me/checkins/:id` | Safety check-ins |
| `GET /v1/family/realtime-scopes` | Authorised realtime scopes for this user |
| `POST /v1/sos`, `GET /v1/sos`, `GET /v1/sos/:id` | SOS create (idempotent per `clientEventId`) and read |
| `POST /v1/sos/:id/acknowledge` · `resolve`, `GET /v1/family/groups/:id/sos/active` | Guardian acknowledgement / resolution |
| `GET|POST /v1/safety-events`, `POST|DELETE /v1/safety-events/:id/votes` | Nearby hazard feed, reporting, votes |
| `GET|POST /v1/evidence`, `GET|DELETE /v1/evidence/:id` | Evidence upload/download/delete (private R2) |
| `GET /v1/realtime/family/:groupId`, `GET /v1/realtime/nearby` | WebSocket upgrade (handled by `SafetyHub`) |
| `GET /v1/health` | Liveness + configuration check (no secrets returned) |

## Security model (summary)

* Passwords: PBKDF2-HMAC-SHA256, 210 000 iterations, per-user salt; verification rejects
  out-of-range iteration counts; unknown e-mail addresses are padded with a dummy hash.
* Access tokens: HS256 JWT, 15-minute default TTL, pinned `iss`/`aud`, ±60 s clock skew.
  Refresh tokens: 32-byte CSPRNG, stored only as SHA-256, mandatory rotation; reuse
  revokes the session and is audited.
* Every request re-validates the session row in D1, so revoking a session takes effect
  immediately. Logout-all is supported.
* Fixed-window rate limits per IP and per account on auth, SOS, safety events, votes,
  evidence and reads; IPs are stored only as a keyed HMAC.
* Authorisation is always resolved server-side (membership/ownership), never from client
  input; all `:id` routes verify access.
* Evidence: private bucket, MIME allow-list, size cap, sanitised display names,
  server-generated object keys, owner-only writes, time-limited retention.
* Errors never leak internals; unexpected failures return a generic code.

## Retention

The cron trigger (`17 3 * * *`) runs `runMaintenance()`, which purges expired evidence and
location shares, archives expired safety events, marks stale presence offline, and clears
expired invites / refresh-token history / rate-limit rows.

## Status

The Worker code, migrations and test suites in this directory are complete and verified in
CI-equivalent local runs (`vitest`, `tsc --noEmit`, `wrangler deploy --dry-run`). A real
deployment is **blocked** until the operator supplies the values listed under
[Required production values](#required-production-values) — `database_id`,
`R2_BUCKET_NAME`, and `AUTH_SECRET` are intentionally absent from this repository.
