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

Not yet verified, and required before a production rollout:

- [ ] The signed Android App Bundle and the signed iOS archive.
- [ ] A minified release build on a physical Android device and an iPhone: Map tab, route map, and a pinch that
  starts on a pin. The automated pinch assertion was removed with the engine change.
- [ ] The staging rehearsal of the release order below.

## Release order

Hosted state on 2026-10-14 (read-only check): production `gallr-korea` is at migration `20260930075724`; staging
`gallr-staging` is at `20261012000000`.

1. Staging: apply the pending migrations in order (`20261012120000` to `20261014100000`), with these deploys
   in between: Admin and the gallery workspace before `20261012130100` (older builds reject the `visit` category);
   Admin again after `20261013110000` (it reads `stale`) and after `20261014100000` (it sends the author name).
2. Staging rehearsal per `specs/089-personal-routes/tasks.md` T073: `mobile-analytics` deploy, Admin, web with the
   route rewrite and the preview scraper check.
3. Production in the same order, starting from `20261009120000`.
4. Release pull request `develop` → `main`.
5. Mobile builds last: Android bundle to Play Internal testing, iOS archive to TestFlight, then review submission.

## Release boundary and remaining gates

- [x] Version source, Android version, iOS version, changelog and candidate notes are synchronized.
- [ ] Staging migrations and deploys applied and rehearsed.
- [ ] Production migrations and deploys applied (separately approved).
- [ ] Release pull request to `main` merged (separately approved).
- [ ] Signed Android App Bundle built with the Play-registered upload key and uploaded to Internal testing.
- [ ] Signed iOS archive exported and uploaded to App Store Connect.
- [ ] Device pass on both platforms.
- [ ] Review submission on both stores (separately approved).

No hosted migration, deploy, store upload, tag or GitHub Release has been performed for this candidate.
