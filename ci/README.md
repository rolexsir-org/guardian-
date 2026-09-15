# CI workflows — pending installation

These two workflow files are complete and YAML-valid, but they are **not yet in
`.github/workflows/`** in the remote repository.

## Why

The GitHub App token used by the automation that wrote them is not granted the
`workflows` permission, so GitHub rejects the push at the server:

```
! [remote rejected] refusing to allow a GitHub App to create or update workflow
  `.github/workflows/ci.yml` without `workflows` permission
```

The REST Contents API returns `403 Resource not accessible by integration` for the
same reason. Every other path in this repository pushes normally — only
`.github/workflows/` is blocked. This is an external GitHub permission limit, not a
problem with the files.

## Installing them (one command, from a clone with a normal user account)

```bash
git checkout arena/01a0a47d-guardian
git pull
mkdir -p .github/workflows
git mv ci/ci.yml .github/workflows/ci.yml
git mv ci/deploy-backend.yml .github/workflows/deploy-backend.yml
git rm ci/README.md
git commit -m "ci: install Guardian CI and backend deploy workflows"
git push
```

Actions will pick them up on that push. Alternatively, paste the file contents into
GitHub's web editor (Actions → New workflow → set up a workflow yourself), which is
also not subject to the App-token restriction.

## What each workflow does

### `ci.yml` — runs on push and pull_request

| Job | Commands |
| --- | --- |
| `cloudflare` | `npm ci`, `npm run typecheck`, `npm test`, `wrangler deploy --dry-run`, plus a guard that fails if `wrangler pages deploy` is ever reintroduced |
| `hygiene` | Fails on tracked secret files, on Firebase / AI Studio references in shipped source, and on demo coordinates in `app/src/main` |
| `android` | Matrix of `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `assembleRelease` on JDK 21 with the Android SDK provisioned |

JDK 21 is required: the unit tests run under Robolectric against SDK 36.
`assembleRelease` produces an unsigned artifact because no keystore is committed.

### `deploy-backend.yml` — `workflow_dispatch` only

Deploys the **Worker** with `wrangler deploy` (never Pages). It fails fast with an
explicit message listing the missing secrets rather than pretending to deploy, then
applies D1 migrations, deploys, and smoke-tests `/v1/health`.

Required repository secrets (Settings → Secrets and variables → Actions):

* `CLOUDFLARE_API_TOKEN`
* `CLOUDFLARE_ACCOUNT_ID`
* `D1_DATABASE_ID`
* `CLOUDFLARE_WORKER_URL` (optional; enables the post-deploy smoke test)

`AUTH_SECRET` is not a repository secret — it is a Worker secret, set once with
`npx wrangler secret put AUTH_SECRET`.
