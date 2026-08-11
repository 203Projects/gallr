# TODOS

Architecture items reviewed: 2026-08-09. Revalidate external service and release status before
acting on older operational entries.

## P1 — Post-Launch

### Complete the Supabase legacy API-key migration before the end of 2026
Supabase is deprecating the JWT-based `anon` and `service_role` keys by the end of 2026. The
repository now prefers publishable-key configuration names on mobile and public web and accepts the
replacement publishable/secret key formats. Lower-priority compatibility fallbacks, rehearsal
tooling, and some Edge Functions retain legacy names until deployed environments and older mobile
builds are proven migrated.

- Effort: M (authorized operator + repository cleanup)
- Migration: Inventory every production/staging client and server consumer; create separate
  publishable and component-scoped secret keys; update the matching 1Password items and deployment
  configuration one environment at a time; then verify browser, mobile, Auth/RLS, Edge Functions,
  scheduled jobs, CI, and cutover tooling.
- Compatibility gate: Account for already-installed mobile versions before disabling legacy keys.
  Supabase provides no automatic usage indicator, so record explicit evidence that no supported
  client or integration still uses them and retain an approved rollback path.
- Cleanup scope: Remove the remaining lower-priority `*_ANON_KEY` configuration,
  `SUPABASE_ANON_KEY` and `SUPABASE_SERVICE_ROLE_KEY` resolution, fallback tests, and current-guide
  references; require the named publishable/secret key maps in hosted functions.
  Preserve immutable migrations and historical release records.
- External change: Disabling the legacy keys is a separately authorized, reversible Dashboard/API
  operation. Confirm the exact project and environment before changing it; never copy credentials
  between production and staging.
- 2026-08-09 evidence: The Supabase account exposes Seoul production
  (`oqrvbstopuppznxqoonp`), retained Singapore compatibility (`yhuhjxswjbrtmbpbrciq`), and an
  unrelated project; the worktree is intentionally unlinked. 1Password access succeeds. Seoul and
  Singapore retain the platform `default` publishable/secret pair plus enabled legacy keys. Seoul
  now also has production-only `delete_account` publishable/secret keys for the deployed account
  deletion function; both are stored in separate 1Password items. Other components still use the
  default or compatibility keys. Vercel Admin and Gallery use publishable-key variable names.
  Public web production is `canonical-v2`; `SUPABASE_PUBLISHABLE_KEY` now contains the Seoul
  publishable key, the deployed compatibility `SUPABASE_ANON_KEY` value was replaced with the same
  key and narrowed to production only, and a fresh production deployment plus public smoke checks
  passed. Keep the deprecated name only until the already-implemented preferred-name reader reaches
  `main`; deleting it before that deployment would break the next automatic build. No Supabase
  legacy key has been disabled. The local product guard now covers all 11 Edge Functions, including
  mandatory gateway JWT verification for `delete-account`; all function, Admin, Gallery, Web, KMP,
  Android, and iOS gates pass. Final key retirement still requires the preferred readers to ship and
  supported installed clients to age out.
- 2026-08-11 preview evidence: Seoul has a dedicated `public_web_preview` publishable key stored in
  a separate 1Password item and scoped in Vercel to PR #156's public-web Preview branch. The public
  web now rejects the deprecated `SUPABASE_ANON_KEY` name. Its current-head Preview built the
  `canonical-v2` reader from 322 live exhibitions, returned HTTP 200 for home and catalog, and then
  the obsolete global Preview variable was removed. Production's compatibility variable remains
  intentionally gated on the publishable-only reader reaching `main`; Supabase's platform legacy
  keys remain enabled for supported installed clients and other documented consumers.
- Reference: [Supabase migration guide](https://supabase.com/docs/guides/getting-started/migrating-to-new-api-keys).

### Push Notifications
Weekly "N new exhibitions near you" push via FCM (Android) + APNs (iOS). Primary retention mechanism. Needs a reviewed server-side scheduler and delivery worker; do not revive the retired Apps Script pipeline. Depends on basic analytics being in place.
- Effort: M (human) → S (CC: ~1 day)
- Context: Design doc identifies retention as key initiative. Without a trigger, users forget to open the app.

## P2 — Quality of Life

### Open in Maps
Button on ExhibitionDetailScreen to open Apple Maps / Naver Map / Google Maps with exhibition coordinates. Completes the discover → save → navigate → visit loop.
- Effort: S (CC: ~30 min)
- Context: Latitude/longitude already in data model but unused on detail screen.

### Featured/Editor's Pick Badges
Show visual badges on detail screen and cards for featured / editor's pick exhibitions.
- Effort: S (CC: ~30 min)
- Context: `isFeatured` and `isEditorsPick` fields exist in data model.

## P3 — Technical Debt

### Track Naver Maps Android SDK D8 stack-map metadata
The latest available Android SDK (`com.naver.maps:map-sdk:3.23.3`) assembles successfully with AGP
9.3 but emits repeated D8 `Expected stack map table for method with non-linear control flow`
warnings from the vendor runtime JAR during a cold release dex build.

- Effort: S (upstream dependency)
- Current impact: Debug and release APK assembly, lint, and duplicate-class checks pass. Do not hide
  the warning globally because that could mask first-party D8 diagnostics.
- Exit criteria: Re-test the newest Naver Maps SDK when published and upgrade once its runtime JAR
  supplies compatible stack-map metadata; remove this entry after a cold `assembleRelease` is clean.
- Reference: [Naver Maps Android SDK](https://github.com/navermaps/android-map-sdk).

### Full Analytics Dashboard
Expand basic 3-event logging to a proper analytics solution (Mixpanel, Amplitude, or Supabase dashboard).
- Effort: M (CC: ~1 day)
- Depends on: Basic analytics events being in place first.
