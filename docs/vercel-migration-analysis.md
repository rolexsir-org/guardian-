# Replacing Cloudflare with Vercel — feasibility analysis

**Question:** can we drop Cloudflare entirely and run the Guardian backend on Vercel?

**Short answer:** technically yes, but it is not a port — it is a rewrite of roughly
5,100 lines of Worker code plus the adoption of two or three *additional* paid vendors,
and it makes the realtime safety path materially weaker. Vercel does not replace
Cloudflare here; it replaces one of the four Cloudflare services in use and forces you
to buy the other three elsewhere.

Recommendation: **stay on Workers.** The current deploy failure is a five-field dashboard
form (see [`cloudflare-deploy.md`](./cloudflare-deploy.md)), not an architectural problem.

---

## What is actually deployed today

Measured from the repository, not estimated:

| Component | Size | Cloudflare primitive |
| --- | --- | --- |
| HTTP API (`src/api/`, `src/http.ts`, `src/index.ts`) | ~1,970 lines | Workers |
| Data layer (`src/db/`) | 1,282 lines, **88 raw SQL statements** | D1 (SQLite) |
| Realtime hub (`src/safety/`, `src/realtime/`) | ~590 lines | Durable Objects |
| Evidence storage (`src/storage/evidence.ts`) | 223 lines | R2 |
| Auth (`src/auth/`) | 510 lines | WebCrypto |
| Retention cron | `scheduled()` + 36-line `maintenance.ts` | Cron Triggers |
| Schema | 19 tables, 2 migrations | D1 |
| Tests | 1,746 lines, 58 specs, run in real `workerd` | vitest-pool-workers |

The Android client (`ApiClient.kt`, `RealtimeClient.kt`) talks to this over HTTPS + WSS
and is configured by a single base URL (`CloudConfig.workerUrl`), so the *client* is
portable. The server is not.

---

## Service-by-service mapping

| Today | On Vercel | Verdict |
| --- | --- | --- |
| **Workers** (HTTP routing) | Vercel Functions | ✅ Straightforward. Fetch-API handlers port with modest edits. |
| **D1** (SQLite, 88 SQL statements) | ❌ No first-party SQL database | Must buy Neon / Supabase / Turso. Vercel Postgres is now Neon resold via Marketplace. |
| **Durable Objects** (`SafetyHub`) | ❌ No equivalent, at any price | The hard blocker — see below. |
| **R2** (evidence blobs) | Vercel Blob | ⚠️ Works, but **egress is billed**; R2 egress is free. Evidence is video/audio. |
| **Cron Triggers** | Vercel Cron | ✅ Equivalent (Pro plan for useful frequency). |
| **WebCrypto** (PBKDF2, HS256) | Node crypto | ✅ Fine. |

So one green column, one blocker, and three "go buy it elsewhere".

## The blocker: `SafetyHub`

`src/safety/SafetyHub.ts` (370 lines) is a Durable Object, and it leans on essentially
every DO-specific capability:

* `state.acceptWebSocket()` — **hibernatable** WebSockets: sockets survive with zero
  billed CPU between messages, which is what makes always-on family presence affordable.
* `state.getWebSockets(tag)` — server-side fan-out to every socket in a family group.
* `state.storage` — strongly-consistent per-scope storage holding `seq`, the replay
  buffer, `presence`, and `activeSos`.
* `state.storage.setAlarm()` — the alarm loop that expires stale presence.
* `state.blockConcurrencyWhile()` — safe cold-start hydration.
* `idFromName(scope)` — **single-writer** guarantee: one authoritative instance per
  family group and per geohash cell, so event sequence numbers are globally ordered.

Vercel has no single-writer stateful primitive. Its WebSocket support entered public
beta on 22 June 2026, and the constraints are structural rather than temporary: a
connection is **pinned to one function instance**, capped at 300 s by default and up to
1,800 s maximum in beta, with **no built-in fan-out, presence, or ordering** — shared
state must live in an external store, and Vercel's own reference architecture uses Redis
for cross-instance broadcasting.[1](https://ably.com/vercel/vercel-websockets-vs-ably)[2](https://neodrop.ai/post/V1pXEp_RaC8)

Translated to Guardian:

* **Every SOS socket drops at least every 30 minutes.** `RealtimeClient.kt` does have
  exponential backoff and a `sync`/`sync.gap` resync path, so it would survive — but
  forced reconnects during an active SOS are exactly the wrong failure mode for a
  personal-safety app.
* **Fan-out must be rebuilt on Redis.** `getWebSockets("user:...")` becomes publish to
  Redis, subscribe on every instance, filter locally.
* **Ordering is lost.** `seq` is currently authoritative because one DO owns the scope.
  With N instances behind Redis you get ordering within a connection only, so the replay
  buffer and `sync.gap` contract need redesigning around an external monotonic counter.
* **Presence and alarms need a new home** — a Redis TTL sweep plus a cron job.

The realistic Vercel realtime answer is a managed provider (Ably, Pusher, PubNub) — which
is a fourth vendor and a per-connection bill for a feature that is currently near-free.

## Cost and operational shape

Today: one vendor, one `wrangler deploy`, egress-free evidence downloads, hibernating
sockets billed only on message.

After: Vercel (compute) + Neon/Supabase (Postgres) + Vercel Blob or S3 (evidence,
metered egress) + Upstash Redis or Ably (realtime state). Four bills, four dashboards,
four failure domains, four sets of credentials in CI.

## Work required

| Task | Estimate |
| --- | --- |
| Rewrite 88 SQL statements for Postgres, add a pooled driver | 2–4 days |
| Port 19-table schema + 2 migrations to a Postgres migration tool | 1 day |
| Re-architect `SafetyHub` on Redis pub/sub + external sequencing | 1–2 weeks |
| Port routing, auth, evidence, cron | 3–5 days |
| Rewrite 58 specs — `vitest-pool-workers` gives real bindings, which has no Vercel analogue | 3–5 days |
| Re-verify the security model (rate limits, session revocation) against new stores | 2–3 days |

**Roughly 4–6 weeks**, against a backend that already passes `tsc --noEmit`,
`wrangler deploy --dry-run` and all 58 tests.

## When Vercel *would* be the right call

Reasonable triggers, none of which appear to apply right now:

* You add a Next.js web console and want frontend + API in one place.
* Your team already operates Vercel and Cloudflare is the odd one out.
* You need Postgres features (joins across large tables, extensions, full-text search)
  that SQLite/D1 genuinely cannot serve.

Even then the sane split is: **frontend on Vercel, realtime backend stays on Workers.**
The Android client only reads `CloudConfig.workerUrl`, so it does not care which host
serves the API.

## Cheaper ways to get the benefit you may be after

* **Don't like the Cloudflare dashboard?** Deploy from CI — `ci/deploy-backend.yml`
  already does `wrangler deploy` on `workflow_dispatch`.
* **Want a web presence?** Add `[assets]` to `wrangler.toml` and serve static files from
  the same Worker, or put a Vercel frontend in front of the existing API.
* **Want Postgres specifically?** Hyperdrive lets a Worker use Neon/Supabase without
  giving up Durable Objects.

---

### Sources

1. [Vercel WebSockets vs Ably: fan-out, presence, and ordering compared](https://ably.com/vercel/vercel-websockets-vs-ably) — beta announced 22 June 2026; connection pinning, 300 s/800 s/1,800 s caps, no built-in fan-out, presence or ordering.
2. [Vercel WebSockets: migrate or watch? (2026)](https://neodrop.ai/post/V1pXEp_RaC8) — connections close at max function duration; shared state must live in an external store; Vercel's sample architecture uses Redis.
3. [WebSockets on Vercel: why serverless functions can't host them](https://ably.com/topic/ai-stack/websockets-on-vercel-why-serverless-functions-cant-host-them) — background on the serverless/persistent-connection mismatch and Fluid Compute's scope.
