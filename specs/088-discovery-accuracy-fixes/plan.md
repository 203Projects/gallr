# Implementation Plan: Local discovery accuracy fixes

**Branch**: `088-discovery-accuracy-fixes` | **Date**: 2026-10-03 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/088-discovery-accuracy-fixes/spec.md`

## Summary

Fix four measured accuracy defects in on-device discovery without new flows:

1. **For You routes** rank every eligible exhibition near the route origin through a new prepared-index
   query, `rankRouteCandidates`. They prefer personal evidence, may include saved shows and fill the rest
   from nearby shows.
2. **Opening hours**: a pure parser reads the free-text `hours` field. The planner drops venues closed on
   the visit date and only accepts orderings where each stop with known hours is finished before closing,
   from a Korea-time start stamped at build.
3. **Text similarity** ignores venue boilerplate and ubiquitous n-grams, and uses a 0.08 threshold.
   Prototyped on the live catalogue: pairs counted as similar fall from 58% to 1.7%, and the two
   same-artist shows become the top pair.
4. **Taste evidence** combines matches across all saved and visited anchors with a bounded noisy-OR.

Everything is pure `shared/commonMain` logic, orchestrated by the existing ViewModel and verified against
a public-catalogue fixture.

## Technical Context

**Language/Version**: Kotlin Multiplatform (versions from `gradle/libs.versions.toml`)
**Primary Dependencies**: kotlinx-datetime (`LocalTime`, `DayOfWeek`, `TimeZone.of("Asia/Seoul")`), kotlinx-serialization (fixture decoding through the existing `ExhibitionDto`), JetBrains lifecycle ViewModel. No new dependency.
**Storage**: N/A. Derived values only; nothing persisted.
**Testing**: kotlin-test + kotlinx-coroutines-test in `commonTest`; `shared:testAndroidHostTest`, `composeApp:testAndroidHostTest`, `allTests` on macOS
**Target Platform**: Android and iOS via `shared` and `composeApp`
**Project Type**: Mobile (KMP library + shared Compose app)
**Performance Goals**: Route build under one second on a mid-range phone for catalogues up to 500 live exhibitions (SC-007). For You search stays bounded by beam width 64 × stop count; ordering checks stay at most 5! permutations.
**Constraints**: On device only; no network, permission, persisted profile, analytics field, schema change or promotion input (FR-018 to FR-021). Deterministic with stable ID tie-breaks (FR-019).
**Scale/Scope**: About 70–80 live exhibitions and 64+ venues today; 2–5 stops; 5 km radius

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Status |
|---|---|---|
| I. Spec-First | `spec.md` with prioritized stories and acceptance scenarios exists; checklist passed | Pass |
| II. Test-First | Each change starts from a failing `commonTest`: hours golden cases, fixture route/similarity/taste tests, planner and ViewModel tests. Shared logic is fully unit-tested | Pass |
| III. Simplicity | One new package (`hours`) with one model and one function; one new method on the existing index; no new service, wrapper or dependency. Non-For-You modes keep today's algorithm and only fall back when hours make it infeasible | Pass |
| IV. Incremental Delivery | Stories ship independently: US1 (For You pool), US2 (hours), US3 (similarity and aggregation) touch separable code and have separate tests | Pass |
| V. Observability | No new failure path: the parser is total. Planner failures stay logged by the ViewModel (`plan_route`) with type-only `AppLog`; no IDs or text logged | Pass |
| VI. Shared-First | Parser, scoring, relevance ranking and planning live in `shared/src/commonMain`. The ViewModel only stamps time and passes inputs. Copy changes stay in `composeApp/commonMain` presentation. No platform source-set code | Pass |

**Post-design re-check (after Phase 1)**: Pass. The design adds no platform code, no `expect`/`actual`,
and no persistence. `ExhibitionRecommendationIndex` changes from `fun interface` to `interface`; the only
implementers are `LocalExhibitionRecommendationIndex` and a ViewModel test fake, both updated in this
feature. The planner signature change is internal to the repository.

## Project Structure

### Documentation (this feature)

```text
specs/088-discovery-accuracy-fixes/
├── spec.md
├── plan.md                         # this file
├── research.md                     # R1–R10 decisions and measurements
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── shared-discovery-api.md     # public Kotlin surface and copy
│   └── opening-hours-grammar.md    # golden parsing cases
├── fixture/
│   ├── export.sql                  # public catalogue snapshot query
│   └── to-kotlin.mjs               # JSON → commonTest fixture generator
├── checklists/requirements.md
└── tasks.md                        # /speckit.tasks
```

### Source Code (repository root)

```text
shared/src/commonMain/kotlin/com/gallr/shared/
├── hours/
│   ├── WeeklyOpeningHours.kt             # NEW: OpeningHoursCompleteness, DailyOpening, WeeklyOpeningHours
│   └── OpeningHoursParser.kt             # NEW: parseOpeningHours()
├── recommendation/
│   ├── ExhibitionRecommendation.kt       # Saved evidence, RouteRelevance(Context), index interface
│   └── LocalExhibitionRecommender.kt     # noisy-OR aggregation, n-gram suppression, threshold, rankRouteCandidates
└── map/
    └── NeighborhoodRoutePlanner.kt       # startTime, schedule feasibility, closedCount, forYouRelevance

shared/src/commonTest/kotlin/com/gallr/shared/
├── fixture/
│   ├── PublishedCatalogueFixture.kt      # GENERATED public snapshot
│   ├── PublishedCatalogueHoursExpectations.kt
│   └── DiscoveryFixture.kt               # decodes rows via ExhibitionDto.toDomain()
├── hours/OpeningHoursParserTest.kt       # contract golden cases
├── hours/DiscoveryFixtureHoursTest.kt
├── recommendation/LocalExhibitionRecommenderTest.kt          # aggregation, suppression, route ranking
├── recommendation/ExplainableLocalExhibitionRecommenderTest.kt
├── recommendation/DiscoveryFixtureSimilarityTest.kt
├── recommendation/DiscoveryFixtureTasteTest.kt
├── map/NeighborhoodRoutePlannerTest.kt   # hours feasibility, closedCount, For You fill
└── map/DiscoveryFixtureRouteTest.kt

composeApp/src/commonMain/kotlin/com/gallr/app/
├── viewmodel/LocalDiscoveryViewModel.kt  # nowProvider, Korea-time stamping, rankRouteCandidates, closedCount
├── ui/route/RoutePresentation.kt         # per-stop unverified label, shortage closed sentence
├── ui/route/RoutePlannerScreen.kt        # pass stop schedule status and closedCount to labels
└── ui/discovery/RecommendationPresentation.kt   # Saved evidence label

composeApp/src/commonTest/kotlin/com/gallr/app/
├── viewmodel/LocalDiscoveryViewModelTest.kt
├── ui/route/RoutePresentationTest.kt
└── ui/discovery/RecommendationPresentationTest.kt
```

**Structure Decision**: Existing KMP layout. Domain logic in `shared/commonMain` under the existing
`recommendation` and `map` packages plus a new `hours` package. Orchestration and copy in
`composeApp/commonMain`. No `androidMain`, `iosMain` or `iosApp` changes.

## Implementation order

Each step is red → green → refactor.

1. **Fixture** (blocks the SC tests): run the approved export, generate `PublishedCatalogueFixture.kt`,
   add the decoder and the hand-checked hours expectations.
2. **US2 hours parser**: contract golden cases, then `parseOpeningHours`, then the fixture coverage test.
3. **US2 planner**: feasibility and closed-day tests on synthetic exhibitions, then `startTime`,
   schedules, wait time, `closedCount`, per-stop warnings and the fallback first-fit; then the fixture
   week sweep.
4. **US1 relevance**: `rankRouteCandidates` tests (radius, saved kept, visited excluded, zero-evidence
   fillers, determinism), then the planner's For You fill and personal credit, then the fixture
   neighbourhood test.
5. **US1/US2 ViewModel**: `nowProvider` stamping in `Asia/Seoul`, relevance computed from the request
   origin, `closedCount` in `RouteUiState.Insufficient`.
6. **US3 similarity and aggregation**: suppression and threshold tests, noisy-OR tests, schema version
   bump; fixture similarity and taste tests.
7. **Presentation**: Saved label, unverified-stop label, shortage sentence; presentation tests.
8. **Verification**: quickstart sections 4 and 5.

## Decisions made during implementation

- **R11, personal evidence first.** On the fixture, generic flags outweighed genuine text matches, so the
  For You list now orders candidates with personal evidence first (FR-022). Recorded in research.md and
  data-model.md.
- **R12, partial hours never claim a closure.** `isKnownClosedOn` is true only for a complete reading.
- **Test premises updated (T031).** Recommender tests that relied on text shared by every exhibition, or by
  exhibitions at one venue, were rewritten so each test exhibition has its own venue and shared text is
  limited to the save and its match. Each change is commented in the test.

## Risks

| Risk | Mitigation |
|---|---|
| The fixture snapshot differs from the 2026-10-02 measurements (catalogue changes daily) | SC tests assert the target behaviour on the generated snapshot. Original measurements are recorded in `research.md`. The 2026-10-03 snapshot has 78 live exhibitions, 68 with hours, 5 with artists and 4 with art terms |
| Changed similarity reorders today's For You lists | Expected. Existing explainability tests are updated only where their premise was boilerplate overlap; artist and term tests keep their assertions |
| Waiting time raises `durationBand` for some routes | The value moves within the existing allowlisted bands; no field change (research R10) |
| Late-evening builds return shortages more often | Intended honest behaviour (spec Assumptions); the shortage message now names closed venues |
| Fixture size (~250 KB of public text) slows the iOS test compile | One literal per row; measure in step 1. If compile time regresses noticeably, trim descriptions to the first 4 KB and record the trim in the generator |

## Complexity Tracking

No constitution violations.
