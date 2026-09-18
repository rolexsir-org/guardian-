# Free hosting options for the Guardian backend

**Headline: you are already on the free option.** Everything this repository deploys
runs on the Cloudflare **Workers Free** plan — no credit card, no trial clock. The
deployment is failing because of a wrong project type in the dashboard, not because of a
paywall. Fix it with [`cloudflare-deploy.md`](./cloudflare-deploy.md) and the bill is $0.

Vercel is not a cheaper alternative here: it is free for the part that is already free
(compute) and forces you to buy — or add free tiers of — the three things Cloudflare
currently gives you in one account.

---

## What Guardian costs on Workers Free today

Every primitive in `wrangler.toml` has a permanent free allowance:

| Binding | Free allowance | Guardian's usage |
| --- | --- | --- |
| Worker requests | 100,000 / day | The whole HTTP API |
| **`SAFETY_HUB`** (Durable Object) | 100,000 requests/day, 13,000 GB-s/day | Realtime hub |
| `DB` (D1) | 5 M row reads/day, 100 K row writes/day, 5 GB | 19 tables, 88 SQL statements |
| `EVIDENCE` (R2) | 10 GB stored, 1 M Class A + 10 M Class B ops/month, **zero egress** | Evidence blobs |
| Cron trigger | Included | Nightly retention job |

Two details that matter and that you have already got right:

1. **SQLite-backed Durable Objects are on the Free plan.** Key-value-backed DOs are
   paid-only. `wrangler.toml` line 46 declares
   `new_sqlite_classes = ["SafetyHub"]` — the free-tier-eligible kind. Nothing needs
   changing.[1](https://developers.cloudflare.com/durable-objects/platform/limits/)
2. **R2 egress is free.** Evidence is audio/video; on any metered-egress provider
   (including Vercel Blob and S3) downloads are the line item that grows.

### Limits worth knowing

* D1 free-tier daily caps became **hard failures on 1 September 2026** — past 5 M reads
  or 100 K writes in a UTC day, queries return errors until midnight UTC rather than
  degrading.[2](https://developers.cloudflare.com/changelog/post/2026-09-01-d1-free-tier-limit-enforcement/)
* Free-plan D1 also caps a single database at **500 MB** (5 GB account-wide) and **50
  queries per Worker invocation**.[3](https://developers.cloudflare.com/d1/platform/limits/)
* Free-plan DO storage: 5 GB per account, 1 GB per object.

For a family-safety app, 100 K writes/day is the number to watch — location shares and
presence heartbeats are the write-heavy paths. Batch or throttle heartbeats before you
consider paying. Workers Paid is $5/month if you outgrow it.

---

## Free alternatives, honestly compared

If the goal is "free" rather than "not Cloudflare", note what each option costs you in
work. The row that decides it is always **`SafetyHub`** — 370 lines depending on
hibernatable WebSockets, tagged fan-out, per-scope storage, alarms and the
single-writer guarantee of `idFromName()`. See
[`vercel-migration-analysis.md`](./vercel-migration-analysis.md).

| Option | Free? | Realtime / `SafetyHub` | Rewrite | Verdict |
| --- | --- | --- | --- | --- |
| **Cloudflare Workers Free** (today) | Yes, permanent | Native — no changes | **None** | ✅ Recommended |
| Vercel Hobby + Neon + Upstash + Blob | Free tiers, 4 vendors | Rebuild on Redis pub/sub; sockets capped at 300 s–30 min | 4–6 weeks | ❌ Most work, weakest result |
| Fly.io | Trial credit, then paid | Real persistent process — WebSockets fine | ~2 weeks (Node + Postgres) | ⚠️ Not durably free |
| Render Free | Yes | WebSockets supported, but **service sleeps when idle** | ~2 weeks | ❌ Sleeping = missed SOS |
| Railway | Trial credit, then paid | Fine | ~2 weeks | ❌ Not free |
| Deno Deploy / Bun + Supabase | Free tiers | No single-writer primitive; Supabase Realtime instead | 3–4 weeks | ⚠️ Lateral move |
| Self-host (VPS) | ~$4/mo minimum | Fine | ~2 weeks | ❌ Not free, you run it |

Two things to take from the table:

* **Nothing free replaces Durable Objects.** Every alternative means rebuilding the
  realtime hub on Redis, a managed realtime vendor, or a persistent process you operate.
* **"Free" tiers that sleep are disqualifying for this app.** Render's free web services
  idle out; a safety app whose SOS socket is cold is worse than no socket.

### On Vercel's free tier specifically

Vercel Hobby covers the Functions. It does not provide a SQL database (Vercel Postgres
is Neon resold through the Marketplace), and its WebSocket beta pins a connection to one
instance with no built-in fan-out, presence or ordering — shared state has to live in an
external store such as Redis.[4](https://ably.com/vercel/vercel-websockets-vs-ably)
You would end up on four free tiers, each with its own cap, instead of one.

---

## If you want to reduce cost or risk without migrating

1. **Stay free, add headroom.** Index the hot read paths (migration `0002` already adds
   retention indexes) and throttle presence/location writes — writes are the scarcer quota.
2. **Watch the quotas.** Dashboard → D1 → your database → Metrics → Row Metrics. Cloudflare
   emails you when a daily cap is hit.
3. **Deploy from CI, not the dashboard.** `ci/deploy-backend.yml` runs `wrangler deploy`
   on `workflow_dispatch` and is free on GitHub Actions for public repos.
4. **Keep evidence retention short.** `EVIDENCE_RETENTION_DAYS = "90"` plus the nightly
   cron is what keeps you under the 10 GB R2 allowance; lower it if storage grows.

## Bottom line

The cheapest, fastest and most reliable path is the one you are already on. Fixing the
dashboard project type is a five-field form; migrating to a different free tier is 2–6
weeks of work that makes the SOS path less dependable and multiplies the number of
quotas that can silently stop your app.

---

### Sources

1. [Durable Objects — Limits](https://developers.cloudflare.com/durable-objects/platform/limits/) — SQLite-backed DOs on the Free plan; free-plan storage caps; KV-backed DOs are paid-only.
2. [D1 enforces free tier daily query limits](https://developers.cloudflare.com/changelog/post/2026-09-01-d1-free-tier-limit-enforcement/) — hard failures from 1 September 2026, reset at midnight UTC.
3. [D1 — Limits](https://developers.cloudflare.com/d1/platform/limits/) — 500 MB/database and 50 queries per invocation on Free.
4. [Vercel WebSockets vs Ably](https://ably.com/vercel/vercel-websockets-vs-ably) — connection pinning, duration caps, no built-in fan-out/presence/ordering.
