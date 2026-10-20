# gallr 1.14.0 — Release Candidate Notes

**Version:** 1.14.0

**Android versionCode:** 43

**iOS build:** 33

**Candidate date:** 2026-10-20

**Reader source for release artifacts:** `canonical-v2`

**Previous store release:** 1.13.1 (Android 42, iOS 32), submitted 2026-10-14.

---

## Store “What’s New”

### Korean (`ko-KR`)

```text
동선이 더 쓸모 있어졌습니다.
• 다른 사람이 공유한 동선을 한 화면에서 둘러보고, 인기순·최신순·동네별로 고를 수 있습니다
• 언제 갈지 고르면 그날 그 시간에 맞춰 동선을 확인해 드립니다
• 동선 지도를 전체 화면으로 키워서 확대하고 움직일 수 있습니다
• 탭 바가 모든 화면에 남고, 탭마다 보던 화면을 기억합니다
• 지도에서 전시마다 핀이 하나씩, 겹치지 않게 보입니다
```

### English (`en-US`)

```text
Routes got more useful.
• Browse every shared route on one screen, by popularity, date or district
• Pick when you are going and the route is checked for that day and time
• Open any route map full screen to zoom and pan
• The tab bar stays on every screen and each tab remembers where you were
• One pin per exhibition on the map, never stacked
```

---

## Candidate scope

Everything merged to `develop` since 1.13.1 (pull requests #349 to #367), as listed under `## [1.14.0]` in
`CHANGELOG.md`:

- **Mobile:** 동선 둘러보기 (092), walking paths and the full-screen route map (093), the persistent tab bar with a
  trail per tab (094), Home's visual system app-wide (095), the visit-time choice, plain verdicts, 공유됨 and the
  share-sheet fix (096), one pin per exhibition (097), the required-update gate and nudge (098), the 에디터의 시선
  heading, the recenter-button label fix.
- **Admin:** the Instagram carousel for any exhibition (#349), English notes from the Korean description (#353), the
  앱 버전 section (098).
- **Web:** the shared-route page's verdict wording (#365).

## Verification evidence

Recorded on this candidate's tree (`develop` at `eb44c3bc` plus the version change):

- Every pull request above passed its CI before merge, including the iOS simulator tests and the uncached iOS link.
  #367 merged while four of its checks were still running; the same gates had passed locally on its final tree:
  shared 1428 and composeApp 1403 tests on the Android host and iOS simulator targets, ktlint, Android lint, the
  debug build, Admin typecheck/tests/build, pgTAP (71 files), the migration-lineage validator.
- The 1.14.0 debug build (versionCode 43) installs and launches on the Android emulator against production.
- 098 on the Android emulator against a local stack: the notice shows below the minimum, back does nothing, the
  button opens Google Play, an offline relaunch keeps the notice, a lowered minimum lifts it, the nudge shows and
  나중에 hides it for the day.

Not yet verified for this candidate: a device pass on both platforms of the whole scope (093's full-screen map and
dashed legs were walked on the Android emulator and the iOS simulator on their own branch; 092, 094, 095, 096 and
097 on the Android emulator on theirs); the iOS build with the new version; real walking paths, which need the
function below.

## Release order

1. Merge this version change to `develop`, then promote `develop` to `main` through a pull request.
2. Apply the migrations that are not yet on staging and production, in order: `20261014120000` (public route
   browse), `20261014130000` (walking-route cache), `20261020100000` (app version policy). Each is a separately
   authorized step; the app degrades without them (empty browse list, dashed legs, no update gate).
3. Deploy Admin so the 앱 버전 section and the renamed export are live.
4. Build the signed Android App Bundle (versionCode 43) with the Play-registered upload key and the signed iOS archive
   (build 33), both with the `canonical-v2` reader.
5. Upload both builds to their testing tracks, run the device pass, then submit 1.14.0 for review on both stores.
6. After both stores publish: set each platform's recommended and last released version to 1.14.0 in Admin → 앱 버전.
   Raise the minimum only to a version already live in both stores, and never above the release being shipped.

The `walking-route` function is not part of this order. It is deployed only after HeiGIT's written confirmation,
with its secrets, and an app without it keeps dashed estimates.

## Release boundary and remaining gates

- [x] Version source, Android version, iOS version, changelog and candidate notes are synchronized.
- [ ] Staging and production migrations applied and verified (`20261014120000`, `20261014130000`,
      `20261020100000`); confirm the hosted list before applying.
- [ ] Admin deployed.
- [ ] Release pull request to `main` merged.
- [ ] Signed Android App Bundle built and published to Internal testing.
- [ ] Signed iOS archive exported and uploaded to App Store Connect.
- [ ] Device pass on both platforms.
- [ ] Review submission on both stores.
- [ ] Publish the approved release in Play Console and release the approved version in App Store Connect.
- [ ] App version policy set to 1.14.0 (recommended, last released) on both platforms after publication.

Each unchecked step is a separate owner approval. No tag or GitHub Release has been created.
