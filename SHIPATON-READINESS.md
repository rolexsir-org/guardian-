# Guardian — RevenueCat Shipaton 2026 readiness audit

**Audit date:** 18 September 2026 · **Submission deadline:** 30 September 2026, 11:45 pm PDT (**12 days**)
**Verdict: NOT READY — not currently eligible, and not publishable to Google Play in its present state.**

The RevenueCat *integration code* is good. Everything around it that Shipaton actually
scores — a published store listing, a real product, a working purchase — is absent.
Two of the blockers below are calendar-bound and cannot be solved by writing code.

---

## 1. Eligibility against the official rules

Rules as published on the [Shipaton 2026 Devpost](https://revenuecat-shipaton-2026.devpost.com/rules).

| # | Requirement | Status | Evidence |
|---|---|---|---|
| 1 | App fully **published** on App Store / Google Play / Galaxy Store by the deadline | ❌ | No release exists. `versionCode = 1`, no signing keystore, `assembleRelease` is unsigned by design. |
| 2 | First public version released **1 Aug – 30 Sep 2026** | ⚠️ | Still achievable *only* if the store gate in §2 is already cleared. |
| 3 | RevenueCat SDK **powers at least one in-app purchase** | ❌ | SDK is wired but inert: `REVENUECAT_ANDROID_API_KEY` is empty, so `SubscriptionManager` is permanently in `ProState.NotConfigured`. No dashboard project, no products, no offering. |
| 4 | Judges can test **all premium features** (free trial or promo code) | ❌ | There are no premium features — see §3. No trial or promo code anywhere in the repo. |
| 5 | Demo video ≤ 2 min, public on YouTube/Vimeo | ❌ | Does not exist. |
| 6 | 1024 × 1024 app icon | ❌ | Launcher icon is the **stock AGP template** (green robot, `#3DDC84`) in `ic_launcher_foreground.xml`. |
| 7 | ≥ 1 screenshot at 1179 × 2556, no device frame | ❌ | Does not exist. |
| 8 | Public store URL, downloadable in the **US** | ❌ | Follows from #1. |
| 9 | RevenueCat project ID | ❌ | No project. |
| 10 | Text description of features | ⚠️ | README is developer-facing; needs a rewrite for judges. |
| 11 | Open-source LICENSE file | ❌ | No `LICENSE`. Only mandatory for the **Next Gen** (student) category. |

**Score: 0 of 9 hard submission artifacts.**

---

## 2. 🚩 Blocker A — the Google Play timeline is mathematically out of reach

If the Play developer account is a **personal** account created after 13 Nov 2023, Google
requires a closed test with **12 testers opted in for 14 continuous days** before you may
even *apply* for production access — and that application is itself reviewed
(Google: "usually 7 days or less").

```
18 Sep  + 14 days closed testing   = 02 Oct   ← already past the deadline
        + production-access review = ~09 Oct
        + app review               = later still
```

**You cannot start closed testing today and be live by 30 Sep.**

Two escape hatches, both requiring facts not in this repo:

- The closed test **is already running** and started on or before 16 Sep; or
- The account is an **organization** account (D-U-N-S verified), which sits outside that
  gate and can publish straight to production — still leaving only ~12 days for review.

A third option that dodges the gate entirely: the **Samsung Galaxy Store** (new for 2026)
has no equivalent 12×14 rule and is an eligible store.

> ⚠️ Please confirm which account type you have — this single fact decides whether a
> Shipaton submission is possible at all.

---

## 3. 🚩 Blocker B — Play will reject the upload: Billing Library 7

`gradle/libs.versions.toml` pins:

```toml
revenueCat = "8.11.0"   # RevenueCat Android SDK 8.x → Google Play Billing Library 7
```

Google's rule: **from 31 Aug 2026, all new apps and updates must use Billing Library 8+.**
That date is **18 days in the past**. The extension backstop (1 Nov 2026) had to be
requested *before* 31 Aug, which a brand-new app cannot have done.

**Consequence: Play Console will refuse the AAB outright.**

**Fix (small, mechanical — do this first):**

```toml
revenueCat = "9.1.1"   # RevenueCat 9.x ships Billing Library 8
```

RevenueCat 9.x keeps the API surface you already use (`awaitPurchase`, `awaitCustomerInfo`,
`awaitLogIn`, `awaitRestore`) and needs Kotlin ≥ 1.8 — already satisfied. Caveat from the
migration guide: ensure any one-time products are configured as **non-consumables** in the
dashboard, because Billing 8 cannot query consumed purchases.

---

## 4. 🚩 Blocker C — the entitlement unlocks nothing

`ProState.Active` appears in exactly **three** places in the whole app, all of them display
strings in `ui/more/MoreScreen.kt`. No feature, screen, or limit is gated. The paywall says
so out loud:

> "Guardian Pro supports development and **unlocks nothing** that is needed in an emergency:
> SOS, contacts, location alerts and evidence all stay free."

This fails three things at once:

- **Shipaton rule** — judges must be able to "unlock the in-app purchase and test all
  premium features". There are none to test.
- **Judging criterion "Monetization strategy"** — a donation button with no product story.
- **Google Play policy** — selling a subscription that delivers no benefit invites rejection.

The paywall also **never shows a price**. `SubscriptionManager.proPackage()` resolves the
package and `proOfferings` is exposed on the ViewModel, but `GuardianProDialog` renders no
`priceString`, no billing period, and no terms. Play requires price and renewal terms to be
disclosed before purchase.

**Suggested Pro tier that fits the product honestly** (keep every life-safety feature free —
that ethic is a genuine strength worth keeping):

| Free | Guardian Pro |
|---|---|
| SOS, emergency call/SMS, medical ID, offline records | Unlimited trusted contacts (free: 3) |
| 1 family member | Unlimited family members + parental controls |
| 24 h evidence retention | 90 day encrypted evidence vault |
| Live location during SOS | Always-on location sharing + geofenced risk alerts |

---

## 5. 🚩 Blocker D — the test suite does not compile

`app/src/test/java/com/guardian/safety/SubscriptionManagerTest.kt:61`

```kotlin
val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
```

Neither symbol is imported. The file imports `org.robolectric.RobolectricTestRunner` and
`org.robolectric.annotation.Config`, but **not** `org.robolectric.Robolectric` and **not**
`android.app.Activity`.

**`./gradlew testDebugUnitTest` fails to compile.** Two import lines fix it:

```kotlin
import android.app.Activity
import org.robolectric.Robolectric
```

This matters beyond the red build: it is the *only* automated proof that a purchase is never
faked, and the README cites it as verified coverage.

---

## 6. 🚩 Blocker E — CI has never run, and would fail itself if it did

There is **no `.github/` directory**. The workflows sit unused in `ci/`, and `gh run list`
returns nothing — zero runs in the repository's history. The README's claim that
"`.github/workflows/ci.yml` is the authority on whether Guardian builds" is currently
unbacked: nothing has ever verified an Android build.

Worse, both `hygiene` checks fail against this very repo — I ran them:

**Secret scan** — the regex `(^|/)\.env\.` matches the tracked, intentional `.env.example`:

```
$ git ls-files | grep -Ei '(^|/)\.env$|(^|/)\.env\.|...'
.env.example          ← job exits 1
```

**Pages-deploy ban** — the grep scans the whole repo, including the docs that *describe* the
ban, so it matches its own prose:

```
README.md:205:  ... there is no `wrangler pages deploy` anywhere ...
ci/ci.yml:58:   # ... A `wrangler pages deploy` anywhere in ...   ← job exits 1
```

Fixes: exclude `.env.example` (`grep -Ev '\.env\.example$'`) and restrict the Pages grep to
build config only (`--include=*.toml --include=*.json --include=*.yml` scoped to `cloudflare/`),
or match `wrangler pages deploy` as an executed command rather than any prose mention.

---

## 7. Google Play review risks (beyond the blockers)

| Risk | Detail |
|---|---|
| **No privacy policy** | Zero references repo-wide. Mandatory for a Play listing, and non-negotiable for an app touching location, microphone and medical data. A Data safety declaration is also required. |
| **`SEND_SMS` / `CALL_PHONE`** | Restricted permissions. Require a Permissions Declaration Form plus video justification. Personal safety is an accepted use case, but this is the single most common rejection path for apps like this — budget review time. |
| **Background location not requested** | `ACCESS_BACKGROUND_LOCATION` is absent, and `LocationService` is a plain class, **not** an Android `Service`. No `FOREGROUND_SERVICE*` permissions are declared (`grep -c` → 0). "Live location sharing" will not survive the app going to background — a core promise that does not work. |
| **Stock launcher icon** | The default green Android robot. Fails the 1024×1024 asset requirement and undermines "App design & execution" scoring. |
| **`usesCleartextTraffic=false` + empty `CLOUDFLARE_WORKER_URL`** | Unless the Worker is deployed, every cloud feature (family, chat, map, evidence, responders) reports "not configured". A judge installing from Play would see most of the app switched off. |

---

## 8. What is genuinely good

Worth protecting during the scramble:

- **`SubscriptionManager` is a correct, honest integration.** It distinguishes
  `NotConfigured` / `Unknown` / `Active` / `Inactive` / `Error`, and never collapses a
  failed lookup into "not subscribed". Purchases resolve to `Success` only after RevenueCat
  returns updated `CustomerInfo`; user cancellation is a distinct outcome; `restore()` is
  implemented; `logIn`/`logOut` bind purchases to the real account id on cold start. This is
  better than most hackathon entries.
- **Only the public SDK key is compiled in**, injected via env/`local.properties`, never committed.
- **Backend is real** — 57 Vitest tests, typed Worker, D1 + Durable Objects + R2, server-side
  authorization, cron retention sweep.
- **Fails closed** — SQLCipher with no plaintext fallback, biometric screens with no bypass.
- Product concept is strong and the "life-safety features stay free" ethic is a real
  differentiator for the pitch.

---

## 9. Minimum path to an eligible submission

Ordered by dependency. Steps 1–2 are code; step 3 is the calendar risk.

1. **Unblock the build** *(≈1 hour)*
   - `revenueCat = "9.1.1"` in `libs.versions.toml`
   - Add the two missing imports in `SubscriptionManagerTest.kt`
   - Install `ci/*.yml` into `.github/workflows/` and fix the two self-matching hygiene greps
   - Reconcile `README` "Kotlin 2.3.21" with the catalog's `kotlin = "2.2.10"`; verify KSP
     `2.3.5` (built for Kotlin 2.3) is compatible with the KGP 2.2.10 that AGP 9.1.1 pins —
     a likely mismatch I could not confirm offline
2. **Make Pro real** *(≈1 day)*
   - RevenueCat dashboard → project, `guardian_pro` entitlement, Play product, offering
   - Gate 3–4 features (table in §3); show `priceString`, period and terms on the paywall
   - Configure a **free trial** — satisfies the judge-access rule with no promo-code handling
3. **Ship** *(calendar-bound)*
   - Real icon, privacy policy URL, Data safety form, permission declarations
   - Generate a keystore, sign, upload — **today**, given §2
   - Deploy the Worker so cloud features are live for judges
4. **Submit** — 2-min video, 1024×1024 icon, 1179×2556 screenshot, store URL, project ID

---

## 10. Bottom line

The engineering is not the problem — the RevenueCat integration is cleaner than most entries
that will win prizes. What is missing is a **product decision** (what Pro actually sells), a
**version bump** that Google now mandates, and roughly **three weeks of store runway that the
calendar no longer contains**.

With 12 days left, honest assessment:

- **Standard categories** — unlikely unless a Play closed test is already running or the
  account is an organization account. The Galaxy Store is the most realistic route to a live
  listing in time.
- **Next Gen (student)** — very much achievable. No store release required; needs a demo
  video, a public repo, and a `LICENSE` file. Blockers A, B and the store artifacts all
  disappear. If anyone on the team has an academic email, **this is the category to target.**
