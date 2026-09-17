# Guardian

A personal-safety Android app: one-button emergency alerts, live location sharing with
a trusted circle, community safety reporting, encrypted on-device records and an
optional Guardian Pro subscription.

Guardian is offline-first and local-first. The phone keeps working — emergency calling,
emergency SMS, medical ID, contacts, local records — with no network and no account.
The Cloudflare backend adds the things that need a server: family groups, live presence,
remote SOS dispatch and an encrypted evidence vault.

## Repository layout

```
app/          Android application (Kotlin, Jetpack Compose)
cloudflare/   Guardian backend: Worker + D1 + Durable Objects + R2 (TypeScript)
```

The two halves are deployed independently and share nothing but an HTTPS API contract.

## Backend (`cloudflare/`)

A single Cloudflare Worker fronts everything. There is no other service: no Firebase,
no third-party identity provider, no separate realtime host.

| Concern | Where it lives |
| --- | --- |
| Auth (email + password, PBKDF2 + HMAC tokens) | `src/auth/`, `src/api/authRoutes.ts` |
| Relational data (users, families, contacts, incidents, SOS) | D1 — `migrations/` |
| Live presence and realtime fan-out | Durable Object `SafetyHub` — `src/safety/` |
| Evidence files | R2, private bucket, server-generated keys |
| Maintenance (stale-presence sweep, retention purge) | cron `17 3 * * *` — `src/maintenance.ts` |

Authorization is enforced server-side: a client cannot read another family's data by
editing an id, and evidence objects are written under server-generated keys in a
non-public bucket.

```bash
cd cloudflare
npm ci
npm run typecheck
npm test          # 57 tests
```

`wrangler.toml` intentionally ships `database_id = "REPLACE_WITH_D1_DATABASE_ID"`. It is
replaced with the real id at deploy time; a real database id must never be committed.

### Deploying: this is a Worker, not a Pages site

The Cloudflare Workers Builds check on pull requests currently **fails** with:

```
✘ [ERROR] Could not detect a directory containing static files (e.g. html, css and js) for the project
```

That message is misleading — Guardian has no frontend to deploy and does not need one.
The cause is a **build configuration** problem, not a repository problem:

* the Worker's `wrangler.toml` lives in `cloudflare/`, not at the repository root;
* Workers Builds runs from the repository root by default, finds no wrangler config,
  and falls back to Pages-style static-asset detection — which then finds no `index.html`.

Reproduce the failure and the fix locally:

```bash
npx wrangler deploy --dry-run              # from repo root -> the static-files error
cd cloudflare && npx wrangler deploy --dry-run --outdir dist   # -> succeeds
```

The fix is to point the build at the Worker, in the Cloudflare dashboard under
*Workers & Pages → guardian → Settings → Builds*:

| Setting | Value |
| --- | --- |
| Root directory | `cloudflare` |
| Build command | `npm ci && npm run typecheck && npm test` |
| Deploy command | `npx wrangler deploy` |

`.github/workflows/deploy-backend.yml` (staged in `ci/`, see below) already deploys
correctly because it `cd`s into `cloudflare/` first.

**Do not "fix" this by adding an `index.html` or a static directory.** That would
suppress the error message while deploying an empty site instead of the API, and the
Worker endpoints would silently 404. Changing the build root is the only correct fix,
and it requires dashboard access that CI tokens alone do not grant.

## Android (`app/`)

Package `com.guardian.safety`. Kotlin 2.3.21, AGP 9.1, Jetpack Compose, Room, WorkManager.

* **Encrypted at rest.** Room runs on SQLCipher (`net.zetetic:sqlcipher-android`, which
  supports 16 KB page devices). The key is generated per install and wrapped by the
  Android Keystore; there is no plaintext fallback and no bundled key.
* **Fails closed.** If encrypted storage cannot be opened, protected features refuse to
  run and say why. They never degrade to an unencrypted database.
* **Offline-first.** Local records are written first; sync happens later with
  deterministic client-generated event and idempotency ids. Nothing is marked synced
  without a real server acknowledgement, and a retried SOS is matched by its client
  event id rather than duplicated.
* **No simulated results.** Emergency calls, SMS, sensor triggers and profile saves
  report what actually happened — including a dialler hand-off the user still has to
  confirm, or a recorder that could not start.

### Build configuration

All configuration is injected at build time and is absent by default:

| Property / env var | Purpose |
| --- | --- |
| `CLOUDFLARE_WORKER_URL` | Public HTTPS base URL of the deployed Worker |
| `REVENUECAT_ANDROID_API_KEY` | RevenueCat **public** Android SDK key |
| `REVENUECAT_ENTITLEMENT_ID` | Entitlement treated as Guardian Pro (default `guardian_pro`) |
| `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | Release signing |

```bash
cp .env.example .env    # then fill in real values
./gradlew clean test lint assembleDebug assembleRelease
```

With no `CLOUDFLARE_WORKER_URL` the app still builds and runs: cloud features report
that the service is not configured, and the device-only features keep working. With no
`REVENUECAT_ANDROID_API_KEY`, Guardian Pro reports "not available in this build" — it
never pretends a purchase succeeded. Release builds are unsigned unless signing
credentials are present; no keystore is committed.

### Tests

```bash
./gradlew test          # JVM + Robolectric
./gradlew connectedAndroidTest
```

Covered: package identity, authentication and token handling, offline queueing and
retry, location capture, the SQLCipher 6→7 migration, encrypted preference flags,
emergency-number resolution, sensor fail-closed behaviour, subscription state, and the
emergency call/SMS result contract.

## Continuous integration

`.github/workflows/ci.yml` is the authority on whether Guardian builds. It runs on
every push and pull request:

| Job | What actually runs |
| --- | --- |
| `cloudflare` | `npm ci`, `npm run typecheck`, `npm test`, `wrangler deploy --dry-run` |
| `hygiene` | Fails if secret-bearing files become tracked, if Firebase / AI Studio references return to shipped source, or if demo coordinates appear in `app/src/main` |
| `android` | `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `assembleRelease` on JDK 21 |

JDK 21 is required because the unit tests run under Robolectric against SDK 36.
`assembleRelease` in CI produces an intentionally **unsigned** artifact: no keystore
exists in the repository, and one is never committed.

`.github/workflows/deploy-backend.yml` deploys the Worker with `wrangler deploy` and is
`workflow_dispatch` only. It refuses to run — with a clear message rather than a
fabricated success — until `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID` and
`D1_DATABASE_ID` exist as repository secrets, then applies D1 migrations, deploys, and
smoke-tests `/v1/health`.

## Security notes

* Secrets are never committed. `.env`, `dev.vars`, `*.jks`, `*.keystore`,
  `keystore.properties`, `google-services.json` and `.wrangler` are git-ignored.
* Android backup and data-extraction rules exclude the encrypted database, the secure
  preference file and any evidence directory.
* Cleartext traffic is disabled; the client only accepts HTTPS endpoints.
* R8 minification and resource shrinking are on, with keep rules limited to what the
  SQLCipher native bridge, Room and the Compose/serialization stack require.
* Protected screens fail closed. `BiometricAuthManager` reports an explicit outcome and
  never treats "no enrolled biometric", a missing host activity or an unexpected
  exception as success, and no screen offers a bypass that skips confirmation.
* Hand-offs to other apps (dial, SMS composer, maps, share) go through
  `util/ExternalIntents`, which catches `ActivityNotFoundException` and reports the
  failure instead of crashing a safety screen. The manifest declares a scoped
  `<queries>` element for exactly those handlers — `QUERY_ALL_PACKAGES` is not used.

### Dependency advisories

`npm audit` in `cloudflare/` reports **4 high-severity advisories and 0 in production
dependencies**:

```bash
cd cloudflare
npm audit --omit=dev   # -> 0 vulnerabilities
npm audit              # -> 4 high, all via wrangler/miniflare/vitest-pool-workers
```

All four resolve to `sharp` → `libheif` (GHSA-g89c-p67h-r497, GHSA-2jg2-4ch7-h545),
pulled in transitively by `wrangler` and the vitest Workers pool. They are build- and
test-time only: nothing from `devDependencies` is bundled into the deployed Worker, as
`wrangler deploy --dry-run` confirms. No upgrade is available that keeps the pinned
wrangler major, so they are accepted and tracked rather than silently ignored.

## Release status

Honest summary of what has been executed against real tooling versus what is waiting
on an external credential. Nothing below is claimed without a command having run.

**Verified**

* Cloudflare Worker: `tsc --noEmit` clean, `vitest run` 57/57 passing, and
  `wrangler deploy --dry-run` bundling the Worker with all four bindings resolved
  (`SAFETY_HUB` DO, `DB` D1, `EVIDENCE` R2, vars).
* D1 schema: both migrations applied to a real local D1 (53 statements), producing
  19 tables and 32 indexes.
* Backend is a Worker end to end — there is no `wrangler pages deploy` anywhere, and
  CI fails the build if one is reintroduced.
* Production npm dependencies: 0 advisories (`npm audit --omit=dev`).

**Known failing check**

The `Workers Builds: guardian` check on pull requests fails with "Could not detect a
directory containing static files". This is a Cloudflare build-root misconfiguration,
not a code defect — see *Deploying: this is a Worker, not a Pages site* above for the
reproduction and the dashboard setting that fixes it. It is deliberately **not** masked
with a placeholder `index.html`.

**Blocked on credentials the repository must not contain**

| Gate | Blocker |
| --- | --- |
| Production deploy, D1/R2/DO provisioning | `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`, `D1_DATABASE_ID`, `AUTH_SECRET` |
| Release signing | No keystore; `assembleRelease` is unsigned by design |
| RevenueCat production billing | No public Android SDK key and no store configuration |

**Not verified locally**

Android compilation, unit tests, lint and both APK assemblies run in CI, not in the
authoring environment: that sandbox has no JDK and no network route to
`dl.google.com` or `repo.maven.apache.org`, so Gradle cannot resolve AGP, Kotlin or
AndroidX at all. Results are therefore taken from the `android` CI job rather than
asserted here. On-device QA (real call, real SMS, real GPS, sensors, reboot,
background restrictions) requires hardware and has not been performed.


