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
emergency-number resolution, sensor fail-closed behaviour, and subscription state.

## Security notes

* Secrets are never committed. `.env`, `dev.vars`, `*.jks`, `*.keystore`,
  `keystore.properties`, `google-services.json` and `.wrangler` are git-ignored.
* Android backup and data-extraction rules exclude the encrypted database, the secure
  preference file and any evidence directory.
* Cleartext traffic is disabled; the client only accepts HTTPS endpoints.
* R8 minification and resource shrinking are on, with keep rules limited to what the
  SQLCipher native bridge, Room and the Compose/serialization stack require.

## Origin of this repository

This repository was created from the GitHub template
[`google-gemini/aistudio-repository-template`](https://github.com/google-gemini/aistudio-repository-template).
Every file that came from the template has been removed or rewritten; nothing from it
remains in the working tree.

The template attribution itself is stored as immutable repository metadata and is not
part of the Git history. GitHub exposes no supported API or UI operation to remove it
(`PATCH /repos/{owner}/{repo}` returns `403 Resource not accessible by integration`, and the
GraphQL schema has no field or mutation for it). It is therefore **retained**: the
`template_repository` field on `rolexsir-org/guardian-` still names the template, and
that is a factual statement of origin rather than leftover content.
