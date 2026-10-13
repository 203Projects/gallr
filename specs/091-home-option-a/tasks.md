# Tasks: Home tab visual system v2 (Option A)

**Input**: Design documents from `/specs/091-home-option-a/`
**Prerequisites**: plan.md, spec.md, research.md (R1–R10), data-model.md, contracts/home-visual-tokens.md, contracts/routes-rail.md, quickstart.md

**Tests**: Required (constitution II, Test-First): every pure function, ViewModel change and copy change starts with a failing `commonTest` case; composables change only after their state functions are green. Device walkthroughs are in quickstart.md.

**Organization**: Phases 1–2 prepare tokens, blur mode, tint and the shared routes selection; Phases 3–6 are the four user stories in priority order; Phase 7 is polish. Paths are repository-relative; Gradle runs from the worktree root with `ANDROID_HOME=$HOME/Library/Android/sdk`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1 (soft legible page), US2 (routes rail), US3 (rail dots), US4 (DESIGN.md record)

## Phase 1: Setup

- [x] T001 Add `haze = "<current stable on Maven Central>"` and `haze = { module = "dev.chrisbanes.haze:haze", version.ref = "haze" }` to `gradle/libs.versions.toml`, then `implementation(libs.haze)` in the `commonMain` dependencies of `composeApp/build.gradle.kts`; run `./gradlew composeApp:dependencies --configuration commonMainImplementationDependenciesMetadata -q | grep haze` to confirm resolution for Android and iOS (R1)
- [x] T002 [P] Create `composeApp/src/commonMain/kotlin/com/gallr/app/ui/theme/HomeVisualTokens.kt` with the token object from contracts/home-visual-tokens.md (values from data-model.md), KDoc stating the Home-only scope
- [x] T003 [P] Add `HomeVisualTokensTest` in `composeApp/src/commonTest/kotlin/com/gallr/app/ui/theme/HomeVisualTokensTest.kt`: the darkening floor keeps `onBackground` at ≥ 4.5:1 over the theme `background` wash in both themes (pure luminance arithmetic with `relativeLuminance` from `share/PosterPalette.kt`), the dot limit is 10, and the radius is 16dp (R3, R7)

**Checkpoint**: Haze resolves on both targets; tokens exist and their contrast floor is asserted.

---

## Phase 2: Foundational

- [x] T004 [P] Write `composeApp/src/commonTest/kotlin/com/gallr/app/ui/graphics/HomeBlurModeTest.kt`: `homeBlurMode(blurAvailable, effectFailed)` → BLUR only when available and no failure; failure sticks for the session (R6)
- [x] T005 [P] Write `composeApp/src/commonTest/kotlin/com/gallr/app/ui/graphics/PosterTintTest.kt`: `posterTint(rgba)` returns the palette's dominant tone for a two-colour 32×32 buffer, `Absent` for an empty buffer, and `PosterTintCache` returns the same tint for the same URL without a second extraction (R2)
- [x] T006 [P] Write `shared/src/commonTest/kotlin/com/gallr/shared/home/RoutesRailTest.kt`: `routesRail` keeps order, truncates to 3, passes fewer through, empty in → empty out, `limit = 0` → empty (contracts/routes-rail.md)
- [x] T007 Implement `composeApp/src/commonMain/kotlin/com/gallr/app/ui/graphics/HomeBlur.kt`: `HomeBlurMode`, `homeBlurMode`, a session `effectFailed` flag set by the first logged `home_blur_unavailable` through `AppLog.tagged("HomeVisual")`, `Modifier.captionBand(mode, hazeState, tint, bandStart)` (Haze progressive effect + tint in BLUR; `grainWashBrush` fade in WASH) and `Modifier.homeBarBackdrop(mode, hazeState)` (R1, R6)
- [x] T008 Implement `composeApp/src/commonMain/kotlin/com/gallr/app/ui/graphics/PosterTint.kt`: `PosterTint`, `posterTint(rgba)` over `PosterPalette.fromRgba`, `PosterTintCache` keyed by URL, extraction off the main thread from an `ImageBitmap` downsampled to 32×32 via `readPixels`, `home_tint_failed` logged once per URL (R2)
- [x] T009 Add `routes: List<PublicRouteSummary> = emptyList()` to `HomeFeed` in `shared/src/commonMain/kotlin/com/gallr/shared/home/HomeFeed.kt` and implement `routesRail` + `HOME_ROUTES_LIMIT` in `shared/src/commonMain/kotlin/com/gallr/shared/home/RoutesRail.kt`
- [x] T010 Run `./gradlew shared:allTests composeApp:testAndroidHostTest shared:ktlintCheck composeApp:ktlintCheck -q`; T003–T006 green, no ktlint violations

**Checkpoint**: the decision functions, the tint pipeline and the shared selection exist and are tested; no composable has changed yet.

---

## Phase 3: User Story 1 — Home reads as one soft, legible magazine page (Priority: P1) 🎯 MVP

**Goal**: 16dp corners, progressive-blur caption bands, poster tint and a backdrop-blurred tab bar on the Home tab, with the gradient-wash and solid-bar fallback.

**Independent Test**: quickstart.md §2 steps 1–4 and 7 on Android 13+, Android 12 and iOS, light and dark, 100% and 200% font scale; §4 contrast sampling.

### Tests for User Story 1

- [x] T011 [P] [US1] Extend `composeApp/src/commonTest/kotlin/com/gallr/app/ui/tabs/home/HomePresentationTest.kt` (or add `HomeCardShapeTest.kt`): the hero, curator's eye, rail card and tile shape resolvers return `HomeVisualTokens.shape`, and the band start follows the caption height rule (0.45 or caption top − 30%) as a pure function `captionBandStart(coverHeightPx, captionHeightPx)` (R3, R7)
- [x] T012 [P] [US1] Add a guard test `composeApp/src/commonTest/kotlin/com/gallr/app/ui/theme/HomeOnlyShapesTest.kt` that reads the `composeApp/src/commonMain` sources at test time and fails if `RoundedCornerShape(` or `HomeVisualTokens` appears outside `ui/tabs/home/`, `ui/theme/`, `ui/graphics/` and `GallrNavigationBar.kt` (allow-list the pre-existing avatar `CircleShape` uses) (FR-001, SC-006)

### Implementation for User Story 1

- [x] T013 [US1] `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/home/HomeScreen.kt`: hold one `HazeState`, mark the `LazyColumn` as the haze source, derive `HomeBlurMode` once (with `rememberReduceMotion` for animated changes), remember a `PosterTintCache`, pass mode/state/cache to the sections
- [x] T014 [P] [US1] `HeroPager.kt`: `HomeVisualTokens.shape` for clip, border and press; cover clipped; caption on `captionBand`; tint from the cache after Coil's success callback; counter tile rounded; press deepens the band (instant under reduced motion)
- [x] T015 [P] [US1] `CuratorEyePager.kt`: the same geometry, band and tint treatment as the hero
- [x] T016 [P] [US1] `CollectionGrid.kt`: tiles clipped to the shape, caption on `captionBand` over the monochrome cover, tint applied, solid `onBackground` tile unchanged when no cover
- [x] T017 [P] [US1] `ExhibitionRail.kt`: cards clipped to the shape with the 1dp border inside the clip, tint on the card surface behind the text block (cover stays full colour, no band)
- [x] T018 [US1] `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/GallrNavigationBar.kt`: optional `backdrop: Modifier` (or `hazeState` + `mode`) parameter; on the Home tab the bar draws `homeBarBackdrop` with the `background` tint at `BAR_TINT_ALPHA`, labels and the orange indicator unchanged, solid in WASH; `App.kt` passes it only while the Home tab is selected
- [x] T019 [US1] Font-scale and clipping pass: at font scale 2.0 the counter tile, heart and caption are inside the rounded clip on every card (adjust paddings, never shrink type); run `./gradlew composeApp:testAndroidHostTest composeApp:ktlintCheck -q`
- [x] T020 [US1] Device walkthrough per quickstart.md §2 (Android 13+ and Android 12 emulators) and §3 (iOS simulator); record the screenshots and the §4 contrast ratios in the PR; raise `BAND_DARKENING_FLOOR` and DESIGN.md if any cover fails

**Checkpoint**: US1 is complete and visible on its own; all other tabs unchanged (T012 green).

---

## Phase 4: User Story 2 — Routes getting attention on Home (Priority: P2)

**Goal**: the 지금 주목받는 동선 rail after 내 취향, three listed routes in ranking order, opening the existing preview.

**Independent Test**: seed public routes with `scripts/sample-routes`, open Home; three cards in ranking order; tap opens the preview; none listed → no rail.

### Tests for User Story 2

- [x] T021 [P] [US2] `composeApp/src/commonTest/kotlin/com/gallr/app/viewmodel/HomeViewModelTest.kt`: three or more rows → `feed.routes` has three in order; a failing `listPublic` → empty and the state is still `Ready`; a signed-out visitor still gets routes (fake `PersonalRouteRepository`)
- [x] T022 [P] [US2] `HomePresentationTest.kt`: rail title/subtitle KO and EN, `N곳` / `N stops` with `1 stop`, the district arrow line, the unknown-author fallback

### Implementation for User Story 2

- [x] T023 [US2] `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/HomeViewModel.kt`: new `routeRepository: PersonalRouteRepository` constructor input and factory parameter; a once-per-ViewModel `flow { listPublic(limit = HOME_ROUTES_LIMIT) }` logging `home_routes_load_failed` on failure; combined with the other inputs; `feed.routes = routesRail(rows)`
- [x] T024 [US2] `HomePresentation.kt`: `routesTitle`, `routesSubtitle`, `routeStopCount(n, lang)`, `routeDistricts(summary, lang)` copy functions
- [x] T025 [US2] Create `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/home/RoutesRail.kt`: section header + `LazyRow` of 184dp cards (shape, border, placeholder block with the stop count, title, author line with the editor mark, meta line), one merged semantics node per card, `onRouteTap(summary, index)`
- [x] T026 [US2] `HomeScreen.kt`: insert the rail after 내 취향 and before 에디터 추천 when `feed.routes` is non-empty; `App.kt`: wire `onRouteTap` to `publicRoutesViewModel.openPreview(summary)` and the existing public route preview navigation; pass the route repository into `HomeViewModel.factory`
- [x] T027 [US2] Run `./gradlew shared:allTests composeApp:testAndroidHostTest composeApp:ktlintCheck -q`; then the device check (quickstart §2 step 6) with seeded routes, including back returning to the same Home position

**Checkpoint**: US2 complete; with no listed routes the page is unchanged from US1.

---

## Phase 5: User Story 3 — Carousels keep saying where you are (Priority: P3)

**Goal**: dots under the short rails that follow the first fully visible card; counters unchanged.

**Independent Test**: scroll 에디터 추천; the highlighted dot follows the first fully visible card; 내 취향 shows none; reduced motion snaps.

### Tests for User Story 3

- [x] T028 [P] [US3] `composeApp/src/commonTest/kotlin/com/gallr/app/ui/tabs/home/RailDotsTest.kt`: `highlightedCard` for offset 0, offset under half the spacing, offset over half the spacing, the last card, `count` over `DOT_LIMIT` → no dots, `count` of 1 → no dots (R4)

### Implementation for User Story 3

- [x] T029 [US3] Create `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/home/RailDots.kt`: `highlightedCard(...)` pure function and the `RailDots(count, highlighted)` composable (6dp dots, 4dp apart, `onBackground` / `outlineVariant`, no semantics, no animation)
- [x] T030 [US3] `ExhibitionRail.kt` and `RoutesRail.kt`: an opt-in `showDots` parameter driven by `LazyListState` + `highlightedCard`; on for 에디터 추천, 지금 주목받는 동선 and 팔로우한 갤러리, off for 내 취향 (`HomeScreen.kt`)
- [x] T031 [US3] Run the host tests and ktlint; device check per quickstart §2 step 5 and step 8 (reduced motion)

**Checkpoint**: US3 complete; hero and curator's eye counters untouched.

---

## Phase 6: User Story 4 — The design system records the decision (Priority: P4)

- [x] T032 [US4] `DESIGN.md`: add the "Home visual system v2 (Option A, owner decision 2026-10-11)" paragraph under the Home tab section and the 2026-10-11 Decisions Log row, both with the exact wording in contracts/home-visual-tokens.md and the values from `HomeVisualTokens`
- [x] T033 [US4] Verify DESIGN.md values against `HomeVisualTokens.kt` (a test in `HomeVisualTokensTest` may read DESIGN.md and assert the numbers appear), and run `rg "RoundedCornerShape\(" composeApp/src --glob '!**/ui/tabs/home/**'` per quickstart §6

**Checkpoint**: DESIGN.md and the tokens agree; the decision row exists.

---

## Phase 7: Polish & Cross-Cutting

- [x] T034 [P] `CHANGELOG.md` `## [Unreleased]` → `### Changed`: the Home tab's Option A look (corners, bands, tint, bar, dots) and `### Added`: the 지금 주목받는 동선 rail; voice: what the visitor now sees
- [x] T035 [P] `TODOS.md`: no new entries unless the owner approves one; move nothing to Completed without evidence
- [x] T036 Performance check per quickstart §5 on a Pixel 6a class device or the emulator with GPU profiling (SC-003); if the p95 frame time exceeds 16ms, reduce `bandBlurRadius`/`barBlurRadius` and record the new values in DESIGN.md
- [x] T037 Full gate: `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew shared:ktlintCheck composeApp:ktlintCheck androidApp:ktlintCheck shared:allTests composeApp:allTests androidApp:testDebugUnitTest androidApp:lintDebug androidApp:assembleDebug -q`, then the iOS simulator link `./gradlew composeApp:linkReleaseFrameworkIosSimulatorArm64`
- [ ] T038 Run the quickstart.md walkthroughs end to end and attach the screenshots (light/dark, both platforms, 200% font scale) to the PR; note the 089 migration dependency in the PR body

---

## Dependencies & Execution Order

- Phase 1 → Phase 2 → Phase 3 (US1). Phases 4 (US2) and 5 (US3) depend on Phase 2 and T013 (the shared haze state and tint cache in `HomeScreen.kt`) but not on each other; US3's `RoutesRail` dots (T030) need T025. Phase 6 (US4) can be written any time after T002 and must land with US1. Phase 7 after the stories chosen for the release.
- Within a story: tests first and red (T011–T012, T021–T022, T028) before the implementation tasks.
- T014–T017 touch different files and can run in parallel after T013.

## Parallel Example: Phase 2

```bash
# Tests first, in parallel (different files):
Task: "HomeBlurModeTest.kt"
Task: "PosterTintTest.kt"
Task: "RoutesRailTest.kt"
# Then the implementations T007, T008, T009 in parallel.
```

## Implementation Strategy

- **MVP**: Phases 1–3 (US1) with Phase 6 (US4): the owner's chosen look, recorded in DESIGN.md, verified on both platforms with the fallback.
- **Increment 2**: Phase 4 (US2) once the 089 migrations are applied to staging (the rail hides until then, so it can land earlier without harm).
- **Increment 3**: Phase 5 (US3), then Phase 7.
- Applying migrations to hosted projects, deploys and releases are separately authorised: stop and report before any of them.

---

## Phase 8: Revision 2 (owner feedback, 2026-10-12)

**Goal**: match the Option A artboard's composition without glass, add the 내 취향 banner and the
지금 가장 많이 본 curation, and fix the iOS Map crash found while reviewing. See spec.md
"Revision 2". These tasks replace T013–T018 (blur, tint, tab bar backdrop) and extend US2.

- [x] T039 Investigate the iOS Map crash: `IrLinkageError` on `org.jetbrains.skia.Image.encodeToData` in maplibre-compose 0.10.4 under Skiko 0.150 (Compose 1.12.1, raised on develop on 2026-10-05); not caused by this branch
- [x] T040 Hold `compose-multiplatform` at 1.11.1 and `coil` at 3.5.0 in `gradle/libs.versions.toml`; add `-Xpartial-linkage-loglevel=ERROR` to native binaries in `composeApp/build.gradle.kts` (the guarded release link fails on the old state and passes now); verify the Map tab and pin icons on the iOS simulator
- [x] T041 Remove the blur library and what depended on it: Haze from the catalogue and `composeApp/build.gradle.kts`, `ui/graphics/HomeBlur.kt`, `ui/graphics/PosterTint.kt`, their tests, the tab bar backdrop in `GallrNavigationBar.kt` and `App.kt`, and the unused grain-wash shader (`ui/graphics/GrainWash*.kt`)
- [x] T042 [P] Tests then code, shared: `HomeRoute` with stop covers and `homeRoutes(…)` in `home/RoutesRail.kt`; `HomeCollectionKind.MOST_VIEWED` and `buildHomeFeed(…, routeStops, popularExhibitionIds)` (`HomeFeedBuilderTest`, `RoutesRailTest`)
- [x] T043 [P] Migration `supabase/migrations/20261012120000_most_viewed_exhibitions.sql` and pgTAP `supabase/tests/database/064_most_viewed_exhibitions.test.sql`; lineage validator passes
- [x] T044 [P] Tests then code, shared: `MostViewedExhibitionApiClient`, `MostViewedExhibitionRepository(Impl)` (`MostViewedExhibitionApiClientTest`, `MostViewedExhibitionRepositoryImplTest`)
- [x] T045 Tests then code: `HomeViewModel` reads each shown route's stops and the most viewed ids once (`HomeViewModelTest`: stop covers, a failed stops read, the tile's order, a failed most viewed read); wire the repository in `App.kt`, `MainActivity.kt` and `MainViewController.kt`
- [x] T046 Tests then code: `HomeVisualTokens` around the scrim (`bottomScrimBand`, `topScrimBand`, `HomeVisualTokensTest`); `ui/tabs/home/HomeScrim.kt`
- [x] T047 Tests then code: presentation rules in `HomePresentation.kt` (`homeLead`, `curationTiles`, `themeTiles`, `bannerMetaLine`, `routeCopyCount`, new copy; `HomeLayoutPresentationTest`)
- [x] T048 Composables: `BannerPager.kt` with `TasteChips` (replaces `HeroPager.kt`), `CuratorEyePager.kt` as story cards with `StoryDots`, `RoutesRail.kt` as compact cards, `CollectionTiles.kt` with `CurationGrid` and `ThemeRail` (replaces `CollectionGrid.kt`), `SectionHeader.kt` at `titleMedium` Bold, and the new section order in `HomeScreen.kt`
- [x] T049 DESIGN.md: recomposed Home tab section, the 2026-10-12 Decisions Log row; `HomeTokensDocumentationTest` and `HomeOnlyShapesTest` updated
- [x] T050 CHANGELOG, CLAUDE.md (shim list, version hold, Recent Changes), TODOS.md (map library migration, remaining Home ideas, rollout note), contracts and quickstart
- [x] T051 Full gate and device walkthroughs (quickstart.md §7)
- [x] T052 Tests then code, shared: `HOME_SITUATIONS` and `HomeCollectionKind.SITUATION` in `home/HomeSituations.kt`, `HomeFeed.kt` and `HomeFeedBuilder.kt` (three exhibitions make a situation, themes skip covered terms, no visitor-suitability claims; `HomeFeedBuilderTest`)
- [x] T053 Tests then code: situation tile copy and the 상황별·주제별 section name in `HomePresentation.kt` (`HomeLayoutPresentationTest`); DESIGN.md "By mood and theme"; CHANGELOG; TODOS
- [x] T054 Migrations `20261012130000_visit_taxonomy_category.sql` and `20261012130100_visit_taxonomy_terms.sql`; pgTAP `065_visit_taxonomy_terms.test.sql`; `039_explainable_art_metadata.test.sql` counts art and visit terms separately
- [x] T055 Tests then code, shared: `VisitTag`, `Exhibition.visitTags`, `toDomainVisitTags` (`ExhibitionDtoTest`); `HomeCollectionKind.FREE_ADMISSION` and the `situation:family` collection from visit tags only (`HomeFeedBuilderTest`)
- [x] T056 Tests then code: the free admission tile copy and its place in the 큐레이션 grid (`HomeLayoutPresentationTest`)
- [x] T057 Admin and gallery workspace: the `visit` category in `domain.ts`, the response validators, `ExhibitionArtMetadataEditor` and copy, with their tests

## Phase 9: Revision 3 (owner feedback, 2026-10-13)

**Goal**: No AI or review wording for readers, thumbnail logo pins on the Map tab and route maps, and a
recommendation list that holds still when a card is saved. Requirements FR-023–FR-026 in spec.md.

- [x] T058 Fold the map library migration (worktree `maplibre-migration`) into this branch and drop the Compose 1.11.1 / Coil 3.5.0 hold (supersedes T040) in `gradle/libs.versions.toml`, `composeApp/build.gradle.kts`, CLAUDE.md, TODOS.md, CHANGELOG.md
- [x] T059 [P] Failing tests then removal of the drafting and reviewer lines: `CuratorNotePresentationTest`, `HomePresentationTest`, `CuratorNoteDtoTest`, `admin/src/export/carousel.test.ts`; `CuratorNoteBlock.kt`, `CuratorEyePager.kt`, `HomePresentation.kt`, `CuratorNote.kt`, `CuratorNoteDto.kt`, `admin/src/export/carousel.ts`, `carouselCanvas.ts`
- [x] T060 [P] Failing tests then held recommendation signals in `LocalDiscoveryViewModel.kt` (`refreshRecommendations`), wired in `App.kt` to Home tab selection, 모두 보기 and pull to refresh; `LocalDiscoveryViewModelTest.kt`
- [x] T061 Failing tests then pin image naming and cover lookup: `MapPinImageTest.kt`, `RouteStopCoversTest.kt`; `ui/map/MapPinImage.kt`, `shared/.../route/RouteStopCovers.kt`
- [x] T062 `ui/map/LogoPinPainter.kt` and `ui/map/MapPinImageResolver.kt`: the logo pin drawn from `drawable/logo.xml`'s path and supplied to the map on demand
- [x] T063 Map tab pin layers in `ui/tabs/map/SeoulDistrictMap.kt`: plain pin, cover pin, titles under pins, count badge; accessible targets resized
- [x] T064 Route map stops as numbered pins in `ui/route/RouteMap.kt`; `stopCovers` in `PersonalRouteComposerViewModel.kt` and `PublicRoutesViewModel.kt` with tests; `RouteMapFeaturesTest.kt`
- [x] T065 DESIGN.md: Map pins section, Route map panel, Home copy, decisions log rows dated 2026-10-13
- [x] T066 Device pass on the Android emulator and the iPhone 17 simulator: Map tab pins, a public route's map, the 내 취향 like
