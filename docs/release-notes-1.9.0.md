# gallr 1.9.0 — Release Candidate Notes

**Version:** 1.9.0

**Android versionCode:** 32

**Android target API:** 36 (Android 16)

**iOS build:** 25

**Candidate date:** 2026-08-16

**Reader source for release artifacts:** `canonical-v2`

---

## Store “What’s New”

### Korean (`ko-KR`)

```text
MY GALLR에 나만의 예술 생활을 기록해 보세요.
• 로그인 없이 방문한 전시를 보관할 수 있습니다
• 좋아하는 갤러리를 팔로우하고 새 전시를 확인할 수 있습니다
• 계정을 만들면 기록을 백업하고 다른 기기에서 복원할 수 있습니다
• 갤러리별 새 전시 알림을 이 기기에서 받을 수 있습니다
```

### English (`en-US`)

```text
Keep your art life together in My Gallr.
• Archive exhibitions you visit without signing in
• Follow galleries and see their newly published exhibitions
• Create an account to back up and restore your archive
• Turn on per-gallery alerts for new exhibitions on this device
```

---

## Candidate scope

- My Gallr is available to guests and authenticated visitors from the fourth tab.
- The visit archive supports search, multi-select, persistent snapshots, duplicate prevention,
  removal, and opening-date eligibility.
- Gallery following supports search, persistent baselines, new-publication markers, gallery detail,
  visit history, full programme navigation, and device-specific publication alerts.
- A dismissible account invitation appears after three combined visits and followed galleries.
- Account backup merges guest data, restores on another device, retries safely, and isolates every
  account under authenticated database commands and RLS.
- The contextual “Did you see this exhibition?” gate appears only on eligible ended exhibition
  detail screens; it is intentionally absent from Featured and List browsing.

## Verification evidence

- Shared and Compose KMP style/tests, Android unit tests/lint/build, iOS Kotlin tests/frameworks,
  and the iOS host build passed.
- A clean isolated database applied all 75 migrations; 41 pgTAP files with 1,253 assertions, schema
  lint, security advisors, and all three two-session concurrency regressions passed.
- All 12 Edge Functions passed tests, format, lint, and type checks.
- Admin (198 tests), gallery owner (62 tests), public web build/accessibility, and 96 Playwright
  browser tests passed on Node 22.23.1.
- Physical Android and iPhone notification delivery and exhibition deep linking were verified during
  the provider rehearsal; temporary rehearsal resources were then removed.
- The iOS 26.5 simulator passed visit eligibility, save, restart persistence, duplicate prevention,
  gallery follow, account handoff, gallery detail, and notification-rationale flows.
- Firebase project `gallr-492618` is attached, the production Android app is registered as
  `com.gallr.app`, and both the FCM and Firebase Installations APIs are enabled. Its public client
  configuration is stored separately from staging in the `gallr-firebase-production` 1Password item.
- The signed Android App Bundle passed the fail-closed release gate and is signed by the exact upload
  certificate registered in Google Play (SHA-256 `00:0F:3D:56:37:26:0C:6A:F3:8A:7F:8F:23:6A:1C:23:E0:55:09:40:FF:83:51:29:8B:D8:CF:2A:02:2C:7E:F8`).
  The candidate AAB SHA-256 is `d730ec97903a84fa22ec302297e45574a107a59dcb4a9511debb11f674f5bb69`.
- Android location hardware is explicitly optional. Google Play validation reports no devices lost
  and one newly supported phone compared with the previous internal release.
- Internal Testing release `1.9.0 (32) — My Gallr RC` is active and available to the selected
  tester list. Version code 31 remains only in Play's immutable artifact library after its
  superseded draft bundle was removed.
- Google Play reports one non-blocking warning because native debug symbols are unavailable for
  pre-stripped third-party libraries; the R8 ReTrace mapping file is attached to the bundle.
- The five pending additive production migrations were applied to `gallr-korea` in recorded order,
  including the preceding editor-catalogue migration and the four My Gallr/gallery-alert migrations.
  A follow-up dry run reports no pending migrations. Legacy exhibition reads, the previous canonical
  projection, the gallery-aware canonical projection, and the canonical-integrity RPC all return
  successfully; all canonical rows have a stable gallery identity.
- A Play-installed 1.9.0 (32) upgrade on the physical Android test device preserved app data. The
  restored catalogue is visible, authenticated My Gallr backup reports success, and a gallery follow
  was written successfully.
- On the Play-installed physical Android device, the production Firebase installation registered as
  an active account-linked FCM address, the `021 갤러리` subscription was enabled, and Android granted
  notification permission. Two controlled Firebase device tests were posted without blocking: the
  first verified visible Korean title/body delivery and the second included the production deep-link
  payload. Tapping the second notification opened the exact `Rippling Structures` exhibition detail.
  The general production gallery-alert dispatcher remained disabled throughout the test.
- The reviewed `outbox-delivery` receiver passed all 25 function tests and checks, then deployed to
  production as active version 23. The deployment is inert for gallery alerts: no `GALLERY_ALERT_*`
  secrets or enable flag are configured, an unauthenticated invocation returns `401`, and no gallery
  delivery job was created. Existing public-rebuild and authenticated outbox boundaries are preserved.

## Release boundary and remaining gates

- [x] Version source, Android version, iOS version, changelog, and candidate notes are synchronized.
- [x] Android store bundles now fail closed unless all production Firebase client values are present.
- [x] iOS Debug uses APNs sandbox and Release uses APNs production.
- [x] Register the production Firebase Android app and store its public client fields in a dedicated
  production 1Password item.
- [x] Apply the five pending additive migrations to production and verify the legacy and canonical
  reader contracts after migration.
- [x] Deploy the updated `outbox-delivery` function inertly and verify its authentication boundary,
  test suite, secret state, and empty gallery-delivery queue.
- [ ] Provision the production provider credential and enable the gallery-alert dispatcher only in a
  separately reviewed activation; general delivery remains disabled.
- [ ] Complete the final signed-in physical-device account-isolation and hands-on VoiceOver pacing
  pass.
- [x] Build and verify the signed Android App Bundle after the Firebase gate is satisfied.
- [x] Publish Android 1.9.0 (32) to the Google Play Internal Testing track without changing
  production.
- [x] Connect a physical Android device and repeat production-project FID registration, notification
  delivery, and exhibition deep-link verification before any production Play rollout.
- [x] Archive, export, and verify the signed iOS App Store IPA, including version/build,
  `canonical-v2`, production APNs, distribution provisioning, and strict code-sign validation.
- [ ] Open the release PR to `develop`, pass CI/review, then promote separately to `main`.

The explicitly approved Google Play Internal Testing upload and additive production database
migration have been performed. No TestFlight upload, production Play rollout, production notification
dispatcher activation, tag, or GitHub Release has been performed.
