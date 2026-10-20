# gallr 1.14.0 — Release Candidate Notes

**Version:** 1.14.0

**Android versionCode:** 43

**iOS build:** 33

**Candidate date:** 2026-10-20

**Reader source for release artifacts:** `canonical-v2`

**Previous store release:** Android readers are on 1.9.2 (34, released 2026-08-20); 1.13.1 (42) was approved on
2026-10-14 and is still waiting on Publish in Play Console (managed publishing), so it is replaced by this release
without ever reaching readers. iOS: 1.13.1 (32) was submitted 2026-10-14; confirm its state in App Store Connect
before submitting 1.14.0.

**Hidden in this release:** routes (동선). Every route feature merged since 1.13.1 is in the build behind
`ReleaseFeatures.ROUTES = false` (owner decision 2026-10-20): no listed route exists yet and the composer needs a
UX pass. Readers see no 동선 rail, MY section, count or 동선에 추가 button. The 1.13.1 store text promised routes;
the text below no longer does.

---

## Store “What’s New”

### Korean (`ko-KR`)

```text
홈이 새로워졌습니다.
• 이미지 중심의 새 홈: 내 취향 전시, 에디터의 시선, 상황별 컬렉션, 무료 관람과 가족 전시
• 탭 바가 모든 화면에 남고, 탭마다 보던 화면을 기억합니다
• 지도에서 전시마다 핀이 하나씩, 겹치지 않게 보입니다
• 전시 페이지에서 전시 전경 사진을 넘겨 볼 수 있습니다
```

### English (`en-US`)

```text
A new Home.
• An image-led Home with picks for your taste, the editor's eye, collections for the day you are having, free and family exhibitions
• The tab bar stays on every screen and each tab remembers where you were
• One pin per exhibition on the map, never stacked
• Swipe through installation views on the exhibition page
```

Readers never received 1.13.0 or 1.13.1, so this text also carries the Home redesign from those builds.

---

## Candidate scope

Everything merged to `develop` since 1.13.1 (pull requests #349 to #367), as listed under `## [1.14.0]` in
`CHANGELOG.md`:

- **Mobile, shown:** the persistent tab bar with a trail per tab (094), Home's visual system app-wide (095), one pin
  per exhibition (097), the required-update gate and nudge (098), the 에디터의 시선 heading, the recenter-button
  label fix.
- **Mobile, built but hidden** (`ReleaseFeatures.ROUTES`): 동선 둘러보기 (092), walking paths and the full-screen
  route map (093), the visit-time choice, plain verdicts, 공유됨 and the share-sheet fix (096).
- **Admin:** the Instagram carousel for any exhibition (#349), English notes from the Korean description (#353), the
  앱 버전 section (098).
- **Web:** the shared-route page's verdict wording (#365).

## Verification evidence

Recorded on this candidate's tree (`develop` at `eb44c3bc` plus the version change):

- Every pull request above passed its CI before merge, including the iOS simulator tests and the uncached iOS link.
  #367 merged while four of its checks were still running; the same gates had passed locally on its final tree:
  shared 1428 and composeApp 1403 tests on the Android host and iOS simulator targets, ktlint, Android lint, the
  debug build, Admin typecheck/tests/build, pgTAP (71 files), the migration-lineage validator.
- The 1.14.0 debug build (versionCode 43) installs and launches on the Android emulator against production and on
  the iPhone 17 simulator; Settings shows 1.14.0 on both.
- With routes hidden, on the Android emulator: Home shows no 동선 rail, MY shows 방문 and 팔로잉 only, and the
  exhibition page has no 동선에 추가 button. `HomeViewModelTest` proves the public-routes read is skipped.
- 098 on the Android emulator against a local stack: the notice shows below the minimum, back does nothing, the
  button opens Google Play, an offline relaunch keeps the notice, a lowered minimum lifts it, the nudge shows and
  나중에 hides it for the day.

Not yet verified for this candidate: a device pass on both platforms of the whole scope (093's full-screen map and
dashed legs were walked on the Android emulator and the iOS simulator on their own branch; 092, 094, 095, 096 and
097 on the Android emulator on theirs); the iOS build with the new version; real walking paths, which need the
function below.

## Release order

1. Merge this version change to `develop`, then promote `develop` to `main` through a pull request.
2. Apply `20261020100000` (app version policy) to staging and production; the two route migrations may wait for the
   release that shows routes. Each is a separately authorized step; the app degrades without them.
3. Deploy Admin so the 앱 버전 section and the renamed export are live.
4. Build the signed Android App Bundle (versionCode 43) with the Play-registered upload key and the signed iOS archive
   (build 33), both with the `canonical-v2` reader.
5. Upload both builds to their testing tracks, run the device pass, then submit 1.14.0 for review on both stores.
   Android: discard the unpublished 1.13.1 (42) production change first, as 1.13.0 (41) was discarded for 1.13.1.
   iOS: if 1.13.1 (32) is still in review, remove it from review and attach build 33 to the version record.
6. After both stores publish: set each platform's recommended and last released version to 1.14.0 in Admin → 앱 버전.
   Raise the minimum only to a version already live in both stores, and never above the release being shipped.

The `walking-route` function is not part of this order. It is deployed only after HeiGIT's written confirmation,
with its secrets, and an app without it keeps dashed estimates.

## Release boundary and remaining gates

- [x] Version source, Android version, iOS version, changelog and candidate notes are synchronized.
- [ ] Staging and production migrations applied and verified (`20261014120000`, `20261014130000`,
      `20261020100000`); confirm the hosted list before applying. Only `20261020100000` is needed by what readers
      see in 1.14.0; the two route migrations may wait for the release that shows routes.
- [ ] Admin deployed.
- [ ] Release pull request to `main` merged.
- [ ] Signed Android App Bundle built and published to Internal testing.
- [ ] Signed iOS archive exported and uploaded to App Store Connect.
- [ ] Device pass on both platforms.
- [ ] Review submission on both stores.
- [ ] Publish the approved release in Play Console and release the approved version in App Store Connect.
- [ ] App version policy set to 1.14.0 (recommended, last released) on both platforms after publication.

Each unchecked step is a separate owner approval. No tag or GitHub Release has been created.
