# Deploying the Guardian backend on Cloudflare (dashboard / Git integration)

## The error

```
✘ [ERROR] Could not detect a directory containing static files (e.g. html, css and js) for the project
```

This message is produced by **Cloudflare Pages** (or by a Workers Build that was
created with the Pages preset). Pages expects the build to leave a directory of
static assets — `index.html`, CSS, JS — and publishes that directory to the CDN.

Guardian has no such directory, and should not have one:

| Path | What it is |
| --- | --- |
| `cloudflare/` | The backend — a **Cloudflare Worker** (`src/index.ts`) with D1, a `SafetyHub` Durable Object, and an R2 bucket. It is code, not static files. |
| `app/` | The Android client (Gradle/Kotlin). Never deployed to Cloudflare. |

So the deploy is not failing because something is missing from the repository.
It is failing because the Cloudflare project is the **wrong type**. A Worker must
be deployed with `wrangler deploy`, never `wrangler pages deploy`.

---

## Fix: deploy as a Worker

### 1. Remove / stop using the Pages project

Dashboard → **Workers & Pages** → the failing project.

* If its type is **Pages**, it cannot be converted. Delete it
  (Settings → Delete project) or simply disconnect the Git integration
  (Settings → Builds & deployments → Manage → Disconnect) so it stops building.
* Any custom domain attached to it must be released before it can be reused.

### 2. Create a Worker connected to this repository

Dashboard → **Workers & Pages** → **Create** → **Workers** tab →
**Import a repository** (do *not* use the Pages tab).

Select `rolexsir-org/guardian-` and set the build configuration exactly as below.
The **root directory is the critical setting** — leaving it at `/` is what makes
Cloudflare scan the repository root, find no `wrangler.toml` there, fall back to
static-site detection, and emit the error above.

| Setting | Value |
| --- | --- |
| Project name | `guardian-api` (must match `name` in `cloudflare/wrangler.toml`) |
| Production branch | `main` |
| **Root directory** | `cloudflare` |
| Build command | `npm ci` |
| Deploy command | `npx wrangler deploy` |
| Build output directory | *(leave empty — Workers have no static output)* |
| Node version | 20 or newer (`NODE_VERSION=20` build variable if needed) |

Nothing else is required: `cloudflare/wrangler.toml` already declares `main`,
the `DB` / `EVIDENCE` / `SAFETY_HUB` bindings, the cron trigger and `[vars]`.

### 3. Provide the resources the Worker binds to

The repository intentionally ships placeholders instead of real ids. Create the
resources once, from a local clone in `cloudflare/`:

```bash
cd cloudflare
npm ci

npx wrangler d1 create guardian-db          # copy the printed database_id
npx wrangler r2 bucket create guardian-evidence
openssl rand -base64 48 | npx wrangler secret put AUTH_SECRET
npx wrangler d1 migrations apply guardian-db --remote
```

Then edit `cloudflare/wrangler.toml` and replace
`database_id = "REPLACE_WITH_D1_DATABASE_ID"` with the real id, and commit it.
A build will fail with a binding error until this is done.

`AUTH_SECRET` is a **Worker secret**, never a repo value and never in `[vars]`.
Set it either with the command above or in the dashboard:
Worker → Settings → Variables and Secrets → Add → type *Secret*.

### 4. Verify

```bash
curl -s https://guardian-api.<your-subdomain>.workers.dev/v1/health
```

A healthy Worker returns `{"data":{...}}` with no secrets in the payload. A
`503 server_misconfigured` means a required binding or `AUTH_SECRET` is still
missing — `src/env.ts` fails closed on purpose.

---

## Alternative: deploy from the CLI or GitHub Actions

Both bypass the dashboard's project-type detection entirely.

```bash
cd cloudflare
npm ci
npx wrangler deploy
```

`ci/deploy-backend.yml` does the same thing from GitHub Actions
(`workflow_dispatch`), using the `CLOUDFLARE_API_TOKEN` and
`CLOUDFLARE_ACCOUNT_ID` repository secrets. See `ci/README.md` for how to install
the workflow files into `.github/workflows/`.

---

## If you genuinely want a website too

Guardian currently has no web frontend. If a marketing page or web console is
added later, the correct approach is **Workers static assets**, not a second
Pages project — add an `[assets]` block to `cloudflare/wrangler.toml`:

```toml
[assets]
directory = "./public"
binding = "ASSETS"
```

That keeps a single Worker serving `/v1/*` API routes plus static files, and the
deploy command stays `wrangler deploy`.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `Could not detect a directory containing static files` | Pages project, or Workers Build with root directory `/` | Steps 1–2 above: Worker project, root directory `cloudflare` |
| `Missing entry-point` / `main` not found | Root directory not set to `cloudflare` | Set it in Build configuration |
| `Couldn't find a D1 DB with the name or binding 'guardian-db'` | `database_id` still the placeholder | Step 3 |
| `503 server_misconfigured` from `/v1/health` | `AUTH_SECRET` not set for this Worker | `npx wrangler secret put AUTH_SECRET` |
| Build installs nothing / `npm ci` fails | Node < 20 on the builder | Set build variable `NODE_VERSION=20` |
