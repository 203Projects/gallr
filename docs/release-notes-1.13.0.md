# gallr 1.13.0 — Release Candidate Notes

**Version:** 1.13.0

**Android versionCode:** 41

**iOS build:** 31

**Candidate date:** 2026-10-14

**Reader source for release artifacts:** `canonical-v2`

**Previous store release:** 1.10.1 (Android 36, iOS 30). Versions 1.10.2 to 1.11.1 changed the web, Admin and
gallery workspace only, and 1.12.0 was prepared on `develop` but never promoted, so this release carries its mobile
changes too.

---

## Store “What’s New”

### Korean (`ko-KR`)

```text
홈이 새로워졌습니다.
• 이미지 중심의 새 홈: 내 취향 전시, 상황별 컬렉션, 무료 관람과 가족 전시
• 나만의 전시 동선을 만들고 링크로 공유할 수 있습니다
• 전시 페이지에서 전시 전경 사진을 넘겨 볼 수 있습니다
• 지도 핀에 전시 포스터가 보입니다
```

### English (`en-US`)

```text
A new Home.
• An image-led Home with picks for your taste, collections for the day you are having, free and family exhibitions
• Build your own exhibition route and share it with a link
• Swipe through installation views on the exhibition page
• Map pins now show the exhibition's cover
```

Held back from the store text on purpose, because each stays hidden until its content exists:

- 큐레이터의 시선 needs approved notes; production has none yet.
- 추천 동선 needs at least ten routes listed by editors (spec 089).
- 지금 가장 많이 본 needs analytics collection, which is still off in release builds.

---

## Candidate scope

- The home tab is image-led (Option A): 내 취향 as the leading banner, 큐레이터의 시선 caption cards, 큐레이션 and
  상황별 collections, 무료 관람 and 아이·가족과 함께, and a rail of the public routes copied most.
- Personal routes: build, reorder, save and share a named route in the Map route sheet; shared routes open on the web
  without the app; 추천 동선 lists routes staff approved; a route can be copied into a draft or reported.
- The exhibition page pages through the cover and up to twelve gallery pictures, and shows the curator's note with
  one picture per insight, without a byline or drafting wording.
- The map runs on `org.maplibre.compose` 0.20.0. Pins are the gallr logo with the exhibition's cover; route stops are
  the same pins, numbered. This also restores the Map tab on iOS, which aborted in `develop` builds between
  2026-10-05 and this release; no store build ever had that fault.
- Discovery fixes from 1.12.0: For You routes rank from the route origin, routes respect venue hours in Korea time,
  and taste evidence ignores venue boilerplate.
- Staff tools that ship with the same backend: route review with an author-name check on approval, curator notes
  written at the desk and approved by a person, magazine-style and roundup Instagram carousels.

## Verification evidence

Recorded for the combined tree that this candidate is cut from (commit `724aa143` plus the version change):

- Shared Kotlin: 646 Android host tests and 644 iOS simulator tests pass. Compose app: 599 Android host tests and
  593 iOS simulator tests pass. ktlint, Android lint and the debug build pass.
- A minified `assembleRelease` build compiles with the new map engine (unsigned, built from `091-home-option-a`
  before the routes merge).
- A clean local database applied all 112 migrations; 68 pgTAP files with 2,232 assertions, schema lint and the
  security and performance advisors pass.
- Admin (483 tests), gallery workspace (131 tests) and the `draft-curator-notes` function (45 tests, format, lint and
  type checks) pass on Node 22.23.1 and the pinned Deno.
- Four independent pre-landing reviews (routes, desk drafting, database and Admin, Kotlin and CI) found no blocking
  defect; their findings are fixed or recorded in `TODOS.md`.

Verified on 2026-10-14 after the backend rollout:

- The minified Android release build (R8) ran on an Android 17 emulator against production: Home, List, the Map tab
  with thumbnail pins and the exhibition gallery (`이미지 5장 중 2번째` on a backfilled exhibition), with no crash and
  `libmaplibre-native-c.so` loaded.
- The iOS Release configuration ran on an iOS 26 simulator against production: Home and the Map tab with thumbnail
  pins and saved rings. The Map abort of the earlier `develop` builds does not occur.
- The signed Android App Bundle passed `validateStoreRelease` and is signed by the upload certificate registered in
  Google Play (SHA-256 `00:0F:3D:56:37:26:0C:6A:F3:8A:7F:8F:23:6A:1C:23:E0:55:09:40:FF:83:51:29:8B:D8:CF:2A:02:2C:7E:F8`).
  The AAB SHA-256 is `e1d151a6736555c085855d5523658c7abb54eb78da62c2e2060106ee6b611eb1`. Google Play reports no
  devices lost and one non-blocking warning (no native debug symbols for pre-stripped third-party libraries).
- The signed iOS archive is 1.13.0 (31), `com.gallr.app`, Apple Distribution for team `A5WW8X98HT`, production APNs,
  `canonical-v2`, strict code-sign validation passing. The IPA SHA-256 is
  `7081df844498f1e4710bcb8df4b4cdaa0665e4f34d2dead13417b0fb029ec9f6`.

Not yet verified, and required before a production rollout on the stores:

- [ ] A minified release build on a physical Android device and an iPhone: Map tab, route map, and a pinch that
  starts on a pin. The automated pinch assertion was removed with the engine change.
- [x] TestFlight processing of build 31 confirmed in App Store Connect: "Ready to Submit", available to the
  internal `gallr testers` group.

## Release order (as performed on 2026-10-14)

Before: production `gallr-korea` at migration `20260930075724`, staging `gallr-staging` at `20261012000000`.

1. **Staging.** The ten migrations `20261012120000` to `20261014100000` applied cleanly (Vercel Preview builds of
   `develop` are the staging Admin and gallery workspace and were already deployed). Schema read-back matched, the
   new public reads answered 200 for an anonymous client and staff-only functions refused it. Staging holds no
   catalogue data, so this rehearsed the schema, not content.
2. **Production, first batch.** The twelve migrations `20261009120000` to `20261012130000`. The released 1.10.1
   catalogue reads stayed healthy.
3. **Release pull request #324** merged `develop` into `main` (`794da462`), which deployed Admin, the gallery
   workspace and the public web. This came before the `visit` terms because older builds reject that category.
4. **Production, second batch.** The eight migrations `20261012130100` to `20261014100000`. Production is at
   `20261014100000` with nothing pending; the gallery view serves the backfilled pictures, the shared-route page
   answers on gallrmap.com, and staff-only functions refuse anonymous callers.
5. **Mobile builds.** Android 1.13.0 (41) published to the Google Play Internal testing track; iOS 1.13.0 (31)
   uploaded to App Store Connect and available on TestFlight.
6. **Review submission.** Android: the Internal testing release was promoted to Production (full roll-out,
   replacing 1.9.2 (34)) and sent to Google for review. Managed publishing is on, so an approved release waits for
   a manual Publish. iOS: App Store version 1.13.0 with build 31 and the What's New text in English and Korean was
   submitted to App Review with "Manually release this version".

Not part of this release: the `mobile-analytics` function is not deployed to production and analytics collection
stays off in release builds.

## Release boundary and remaining gates

- [x] Version source, Android version, iOS version, changelog and candidate notes are synchronized.
- [x] Staging migrations applied and verified.
- [x] Production migrations and deploys applied and verified (owner-approved on 2026-10-14).
- [x] Release pull request to `main` merged (#324).
- [x] Signed Android App Bundle built with the Play-registered upload key and published to Internal testing.
- [x] Signed iOS archive exported and uploaded to App Store Connect.
- [ ] Device pass on both platforms.
- [x] Review submission on both stores (owner-approved on 2026-10-14).
- [ ] Publish the approved release in Play Console and release the approved version in App Store Connect.

The owner approved the staging and production database rollout, the promotion to `main`, the uploads to the two
testing tracks and the review submissions. Neither store has published 1.13.0 yet: both wait for review and then for
a manual release. An older pending Play change that pauses the closed testing track `1.0.1` was left unsubmitted.
No tag or GitHub Release has been created.
