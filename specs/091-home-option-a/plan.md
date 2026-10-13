# Implementation Plan: Home tab visual system v2 (Option A)

**Branch**: `091-home-option-a` | **Date**: 2026-10-12 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/091-home-option-a/spec.md`

## Summary

The Home tab keeps its sections and data and changes its skin to the owner's Option A: 16dp corners
on every Home card and tile, captions on a progressive blur of the cover instead of the grain wash,
a faint per-card tint taken from the poster, a tab bar that blurs the page beneath it on the Home
tab, position dots under the short editorial rails, and one new rail, 지금 주목받는 동선, fed by the
public routes ranking from spec 089 and opening the existing public route preview. Everything else
in the app stays 0dp and opaque. Decisions are in [research.md](research.md) (R1–R10).

- **Blur**: Haze (Compose Multiplatform) provides both the progressive caption bands (the cover is
  the haze source, the caption band a progressive haze effect with the tint) and the tab bar's
  backdrop blur; where Haze cannot blur (Android 11 and earlier) the bands fall back to the existing
  gradient wash and the bar stays solid (R1, R6).
- **Tint**: the dominant colour comes from the loaded cover through the existing `PosterPalette`
  extractor on a 32×32 downsample, cached per cover URL for the session (R2).
- **Tokens**: `HomeVisualTokens` in the theme package holds radius, band, tint and bar values; the
  same numbers are written into DESIGN.md with the 2026-10-11 decision row (R3, R10).
- **Rail dots and the routes rail**: a pure `highlightedCard` function decides the dot from the list
  state (R4); `HomeFeed.routes` and a pure `routesRail` selection in `shared` take the ranking's
  first three listed routes (R5); tapping opens the preview through the existing
  `PublicRoutesViewModel` path and records the preview-open event it already records (R9).

## Technical Context

**Language/Version**: Kotlin Multiplatform and Compose Multiplatform (versions from
`gradle/libs.versions.toml`); AGSL/SkSL only as already used by the grain wash
**Primary Dependencies**: existing: Coil 3 (`AsyncImage` and its success result), JetBrains lifecycle
ViewModel, kotlinx-coroutines, the `GrainWash` shader, `PosterPalette`; new: `dev.chrisbanes.haze:haze`
(Compose Multiplatform blur; the version pinned in the catalog is the current stable on Maven Central
at implementation time, logged in Complexity Tracking)
**Storage**: none new; the tint cache is in memory for the session
**Testing**: kotlin-test + kotlinx-coroutines-test in `commonTest` (shared selection, tokens and
fallback decisions, dot index, ViewModel flows, presentation copy); Android host tests; emulator
(Android 13+ and Android 12) and iOS simulator walkthroughs per [quickstart.md](quickstart.md)
**Target Platform**: Android (minSdk as configured; blur from Android 12) and iOS (Skia); no web
or Admin change
**Project Type**: Mobile app (KMP)
**Performance Goals**: Home scroll at 60fps on a Pixel 6a class device with blur on (SC-003, p95
frame under 16ms); the tint never delays the first paint; the routes rail adds one PostgREST read
that joins the feed off the main thread
**Constraints**: Home tab only (FR-001); 4.5:1 caption contrast on every fixture cover (FR-002);
no accent beyond its three roles; reduced motion never animates blur, tint or dots (FR-010); no new
analytics event (FR-014); ships after the 089 migrations are applied to the hosted projects
**Scale/Scope**: 1 tab, 6 composables touched (hero, curator's eye, rail, collection tile, nav bar,
section header) plus 1 new rail and 1 dots component; 1 shared model field and 1 selection function;
1 DESIGN.md section and row

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Status |
|---|---|---|
| I. Spec-First | `spec.md` with four prioritized stories, acceptance scenarios, edge cases, FR-001–FR-014, SC-001–SC-006; checklist complete; the owner chose Option A on 2026-10-11 (D5) | PASS |
| II. Test-First | `commonTest` first for `routesRail`, `HomeFeed.routes`, `highlightedCard`, `HomeBlurMode` (fallback decision), `posterTint` (downsample + palette), the ViewModel's routes flow and the rail copy; the composables change only after their state functions are green | PASS |
| III. Simplicity & YAGNI | One library for both blur needs instead of a hand-rolled backdrop capture; tokens as one object; the routes rail reuses `PublicRouteSummary`, `listPublic` and the preview; no new screen, repository or event | PASS (Haze logged below) |
| IV. Incremental Delivery | US1 (geometry, bands, tint, bar) ships alone and is visible at once; US2 (routes rail) and US3 (dots) are independent sections and can land in either order; US4 (DESIGN.md) accompanies US1 | PASS |
| V. Observability | Blur unavailable and shader failures log once per process through `AppLog.tagged("HomeVisual")` (`home_blur_unavailable`, `home_tint_failed`); the routes read failure logs `home_routes_load_failed` and hides the rail; no IDs or URLs in logs | PASS |
| VI. Shared-First | `HomeFeed.routes` and `routesRail` in `shared/commonMain`; tokens, fallback decision, dot arithmetic, tint derivation and composables in `composeApp/commonMain`; platform code limited to the Android API-level read already behind an `expect` for the grain wash | PASS |
| VII. Mobile-First | Visitors meet the new look and the routes rail only in the app; the web and Admin are untouched | PASS |
| Quality: KMP dependency check | One new `composeApp` dependency (Haze); justified in Complexity Tracking, pinned through the version catalog | PASS (logged) |
| Quality: public interfaces documented | Contracts in `contracts/`; KDoc at definitions; DESIGN.md tokens | PASS |

Post-design re-check (after Phase 1): unchanged, all PASS. The only business rule (which routes the
rail shows, in which order, and when it hides) is a pure function in `shared`; the visual decisions
are pure functions over tokens and platform facts in `composeApp/commonMain`; composables render.

## Project Structure

### Documentation (this feature)

```text
specs/091-home-option-a/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── home-visual-tokens.md
│   └── routes-rail.md
├── checklists/requirements.md
└── tasks.md              # /speckit.tasks
```

### Source Code (repository root)

```text
gradle/libs.versions.toml                                   # haze version + library alias
composeApp/build.gradle.kts                                 # commonMain: libs.haze

shared/src/commonMain/kotlin/com/gallr/shared/
├── home/HomeFeed.kt                                        # + routes: List<PublicRouteSummary>
└── home/RoutesRail.kt                                      # routesRail(summaries, limit = 3): pure selection
shared/src/commonTest/kotlin/com/gallr/shared/home/RoutesRailTest.kt

composeApp/src/commonMain/kotlin/com/gallr/app/
├── ui/theme/HomeVisualTokens.kt                            # radius, band, tint, bar tokens (DESIGN.md values)
├── ui/graphics/HomeBlur.kt                                 # HomeBlurMode decision + haze helpers + wash fallback
├── ui/graphics/PosterTint.kt                               # tint from a loaded cover (downsample → PosterPalette), session cache
├── ui/tabs/home/HeroPager.kt                               # corners, caption band, tint
├── ui/tabs/home/CuratorEyePager.kt                         # corners, caption band, tint
├── ui/tabs/home/ExhibitionRail.kt                          # corners, tint, dots under short rails
├── ui/tabs/home/CollectionGrid.kt                          # corners, caption band, tint
├── ui/tabs/home/RailDots.kt                                # dots composable + highlightedCard(listState) pure function
├── ui/tabs/home/RoutesRail.kt                              # 지금 주목받는 동선 cards
├── ui/tabs/home/HomePresentation.kt                        # rail copy KO/EN
├── ui/tabs/home/HomeScreen.kt                              # section order, haze source, routes rail wiring
├── ui/components/GallrNavigationBar.kt                     # backdrop blur on Home (haze effect) with solid fallback
├── viewmodel/HomeViewModel.kt                              # routes read joined into the feed
└── App.kt                                                  # route card → PublicRoutesViewModel.openPreview + navigation
composeApp/src/commonTest/kotlin/com/gallr/app/
├── ui/graphics/HomeBlurModeTest.kt  ui/graphics/PosterTintTest.kt
├── ui/tabs/home/RailDotsTest.kt     ui/tabs/home/HomePresentationTest.kt (rail copy)
└── viewmodel/HomeViewModelTest.kt   (routes flow, failure hides the rail)

DESIGN.md                                                   # Home visual system v2 tokens + Decisions Log row (2026-10-11)
CHANGELOG.md                                                # Unreleased: Added
```

**Structure Decision**: the feature stays inside the existing Home package and theme; the one
shared addition is the routes selection beside the other feed rules. No new module, screen or
repository.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| New `composeApp` dependency `dev.chrisbanes.haze:haze` | Backdrop blur of the page under the tab bar and progressive blur bands need the content behind an area captured and blurred on both platforms; Haze does this with one API on Android (RenderEffect) and iOS (Skia) and exposes progressive masks and tints | `Modifier.blur` blurs only the element it is applied to, never what lies behind it, so the bar would need a hand-rolled layer capture per platform; a custom two-pass Gaussian shader in AGSL/SkSL is more code than the whole feature and still cannot read the backdrop |

## Revision 2 (2026-10-12)

The owner's feedback changed the visual approach and added two sections; see spec.md "Revision 2", tasks.md Phase 8 and the contracts. What changes in this plan: no blur library (Haze is removed, and with it `HomeBlur.kt`, `PosterTint.kt` and the grain-wash shader); captions use a black scrim with a tested contrast floor; new shared types `HomeRoute` and `HomeCollectionKind.MOST_VIEWED`; a new database read `list_most_viewed_exhibition_ids` with its client and repository; and a dependency hold (Compose Multiplatform 1.11.1, Coil 3.5.0) with a release-link guard, because the map library aborts on Compose 1.12. Constitution check: logic stays in `shared` (Principle VI), tests accompany each rule (TDD), the feature is app-only (Principle VII).
