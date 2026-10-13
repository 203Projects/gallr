# Quickstart: verifying the Home tab visual system v2

Run from the repository root. Worktrees need `ANDROID_HOME=$HOME/Library/Android/sdk` in front of
Gradle and a copy of the main checkout's gitignored `local.properties` for Supabase configuration.

## 1. Unit tests first

```bash
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew shared:allTests composeApp:testAndroidHostTest -q
```

Expect green: `RoutesRailTest`, `HomeFeedBuilderTest`, `MostViewedExhibitionApiClientTest`,
`MostViewedExhibitionRepositoryImplTest`, `HomeVisualTokensTest`, `HomeLayoutPresentationTest`,
`RoutesRailPresentationTest`, `RailDotsTest`, `HomeViewModelTest`, `HomeOnlyShapesTest`,
`HomeTokensDocumentationTest` and every existing Home test. Then
`./gradlew shared:ktlintCheck composeApp:ktlintCheck androidApp:ktlintCheck`.

## 2. Local data

The page needs a catalogue, listed routes, approved notes and some opens to show every section. On a
local scratch Supabase stack with the migrations replayed:

1. Load a catalogue snapshot and listed routes with
   `node scripts/sample-routes/create-local-sample-routes.mjs --container supabase_db_<id> --catalogue <snapshot> --list`.
2. For 지금 가장 많이 본, insert `exhibition_opened` rows for today into
   `content.mobile_analytics_daily` for at least three open exhibitions (three or more opens each).
3. For 큐레이터의 시선, approve notes in Admin, or insert approved `content.curator_note_versions`
   rows whose `insights_ko` entries are objects with a `text` field, and link them from
   `content.curator_notes.approved_version_id`.
4. Build with `-Psupabase.url=http://10.0.2.2:<port> -Psupabase.anon.key=<local anon key> -Pexhibition.catalog.source=canonical-v2`
   (Android) or the matching `GALLR_SUPABASE_*` build settings (iOS, `http://127.0.0.1:<port>`).

## 3. Walkthrough (Android emulator and iOS simulator, light and dark)

1. Signed out with nothing saved: the featured list leads as banners (3:4 covers, `01 / 06` pill,
   save button, white caption on the scrim, 이번 주 추천 as the reason line).
2. Save one exhibition: the page now leads with 내 취향 under its header with the basis line and
   `모두 보기 ›`, taste chips under the banner, and 이번 주 추천 becomes a rail lower down.
3. 큐레이터의 시선: story cards with the hook and insight at the top and the exhibition
   chip at the bottom; the dots under it follow the page, the current one an orange pill.
4. 지금 주목받는 동선: compact cards with the maker, the name, `N곳 · 구 → 구` and stop covers with
   `+N`; tapping opens the route preview and its stops load.
5. 큐레이션: 지금 가장 많이 본 first, then the timing tiles, in colour with white captions.
6. 상황별·주제별: a rail of smaller tiles, the situations first (조용히 보고 싶은 날, 사진 찍기 좋은
   공간 and so on, each with its `#tag` line), then neighbourhoods and themes; headlines break at a
   word.
7. Every card and tile has 16dp corners; the tab bar is solid; no other tab has rounded shapes.
8. Font scale 200%: captions stay on their scrims and nothing clips.
9. Reduced motion on: pressing a banner changes the scrim without animation.
10. The Map tab opens and draws its pin icons on both platforms.

## 4. Contrast (SC-002)

The floor is tested in `HomeVisualTokensTest`: white text over a pure white cover under the scrim is
at least 4.5:1. To spot-check a screenshot, sample the cover just beside the banner title's first
line and compute the ratio against white.

## 5. Performance (SC-003)

On a Pixel 6a class device, scroll Home for ten seconds with the frame-time overlay; the 95th
percentile should stay under 16ms. On an emulator compare against a build of `develop` with the
same gestures (`adb shell dumpsys gfxinfo <package>`), since emulators cannot reach 16ms.

## 6. Database

Replay the migrations on a scratch stack, then run the pgTAP suites (suite 057 fails in any local
replay; exclude it locally), `supabase db lint --schema public,content,content_private --fail-on error`,
`supabase db advisors --type security` and `supabase/tests/personal_routes_concurrency.sh`. Suite
059 covers `list_most_viewed_exhibition_ids`.

## 7. Release order

The routes rail reads spec 089's ranking, and 지금 가장 많이 본 reads
`list_most_viewed_exhibition_ids` (migration `20261012120000_most_viewed_exhibitions`) over the
aggregate mobile analytics. Apply the 089 migrations and this one to staging and production before
releasing an app build with this feature; the most viewed tile also needs mobile analytics
collection enabled, which is still off for release builds. Until then each section hides itself.
Applying migrations, deploys and releases are separately authorised: stop and report before any of
them.

## 8. Verification record (2026-10-12, revision 2)

Against a local scratch stack on this branch's migrations (52 open exhibitions, five listed sample
routes, four sample notes, sample opens for eight exhibitions):

- **Automated**: ktlint on `shared`, `composeApp` and `androidApp`; `shared:allTests` (1250) and
  `composeApp:allTests` (1108) on the Android host and the iOS simulator; `androidApp` unit tests,
  `lintDebug` and `assembleDebug`; and `composeApp:linkReleaseFrameworkIosSimulatorArm64` with the
  linkage guard. Admin: typecheck, 420 tests, build. Gallery workspace: typecheck, 131 tests, build.
  Clean migration replay with 63 pgTAP suites (2102 tests), no lint errors, one security advisor
  finding that comes from `develop` (`public.curator_notes_published`), concurrency check passing,
  lineage validator passing.
- **Android 16 (API 36 emulator)**, light and dark: steps 1 to 10 of §3 hold. At 200% font scale
  captions stay on their scrims and nothing clips.
- **iOS 26.5 (iPhone 17 simulator)**, light, and dark with the largest standard text size: the
  banner, story cards, route cards, curations, situation tiles and the route preview match Android;
  captions stay on their scrims; the Map tab opens and draws pin icons after the version hold (it
  aborted before). The 내 취향 lead was exercised on Android only.
- **Situations** on the 52-exhibition local catalogue: the five read from art terms appear, with
  ten exhibitions in the quiet and photograph tiles (the cap).
- **Visit terms**, with five local exhibitions tagged free and four tagged for families: the 무료
  관람 curation and the 아이·가족과 함께 tile appear on Android. The Admin and gallery editors were
  exercised by their component tests, not in a browser.
- **Performance, emulator, debug build, same gestures**: this branch p50 22ms / p95 36ms, 8.9% janky
  frames; `develop` p50 23ms / p95 65ms, 14.9%. A Pixel 6a class device check remains open.
- **Not done**: a TalkBack or VoiceOver pass, a physical device, and Android 12 for this revision
  (no blur path remains, so the API level no longer changes what is drawn).
- **Known and unchanged**: coming back from a detail screen returns Home to the top (TODOS.md,
  "Keep each tab's scroll position when returning from a detail screen").
