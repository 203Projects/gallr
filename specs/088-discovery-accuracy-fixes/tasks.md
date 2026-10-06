# Tasks: Local discovery accuracy fixes

**Input**: Design documents from `/specs/088-discovery-accuracy-fixes/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Required. The spec asks for commonTest coverage and a defect-reproducing fixture, and constitution
principle II requires test-first for shared logic. Every test task must be run and seen failing before its
implementation task starts.

**Organization**: One phase per user story in spec priority order. US1 and US2 both edit
`NeighborhoodRoutePlanner.kt` and `LocalDiscoveryViewModel.kt`, so their implementation tasks are sequential
across stories even though each story is independently testable and shippable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1 For You route pool, US2 opening hours, US3 taste evidence quality

Common paths:
- `SHARED` = `shared/src/commonMain/kotlin/com/gallr/shared`
- `SHARED_TEST` = `shared/src/commonTest/kotlin/com/gallr/shared`
- `APP` = `composeApp/src/commonMain/kotlin/com/gallr/app`
- `APP_TEST` = `composeApp/src/commonTest/kotlin/com/gallr/app`

---

## Phase 1: Setup

**Purpose**: Commit the public-catalogue fixture (exported 2026-10-03, 78 live exhibitions, 68 with hours).

- [x] T001 Export the public catalogue with `specs/088-discovery-accuracy-fixes/fixture/export.sql` and generate `shared/src/commonTest/kotlin/com/gallr/shared/fixture/PublishedCatalogueFixture.kt` with `specs/088-discovery-accuracy-fixes/fixture/to-kotlin.mjs` (done 2026-10-03; see quickstart §1)
- [x] T002 Run `./gradlew shared:ktlintCheck shared:testAndroidHostTest` and confirm the generated fixture compiles and lints with no changes to existing results; if it fails, fix the generator in `specs/088-discovery-accuracy-fixes/fixture/to-kotlin.mjs` and regenerate rather than hand-editing the fixture

---

## Phase 2: Foundational (blocks the fixture-based tests in every story)

- [x] T003 Create `SHARED_TEST/fixture/DiscoveryFixture.kt`: an `internal object DiscoveryFixture` that decodes each `PublishedCatalogueFixture.rowsJson` entry with a `Json { ignoreUnknownKeys = true; coerceInputValues = true }` instance into `com.gallr.shared.data.network.dto.ExhibitionDto` and maps it with `toDomain()`, failing the test if any row returns null. Expose `exhibitions: List<Exhibition>` (sorted by id), `referenceDate = LocalDate(2026, 10, 2)`, and named origins `HANNAM = GeoPoint(37.5345, 127.0010)`, `SEONGSU = GeoPoint(37.5446, 127.0557)`, `CHEONGDAM = GeoPoint(37.5240, 127.0470)`, `SAMCHEONG = GeoPoint(37.5795, 126.9815)`
- [x] T004 Add `SHARED_TEST/fixture/DiscoveryFixtureTest.kt` asserting the decoder returns 78 exhibitions with unique ids, every one with valid coordinates, and 68 with non-blank `hours` (guards against silent DTO mapping drift)

**Checkpoint**: The fixture loads in `shared:testAndroidHostTest`.

---

## Phase 3: User Story 1 - For You route works where the visitor is (Priority: P1) 🎯 MVP

**Goal**: For You routes choose from every eligible exhibition near the route origin, prefer personal evidence, may include saved shows and fill the rest from nearby shows (FR-001 to FR-005).

**Independent Test**: `DiscoveryFixtureRouteTest.forYouRouteSucceedsFromMeasuredNeighbourhoods` passes, and the For You recommendations screen still shows at most six items with saved and visited excluded.

### Tests for User Story 1 (write first, confirm failing)

- [x] T005 [P] [US1] Add `rankRouteCandidates` tests in `SHARED_TEST/recommendation/RouteRelevanceTest.kt`: only exhibitions within `maxDistanceKm` of `origin` and catalogue-visible on `today` are returned; bookmarked exhibitions are included with `RecommendationEvidence.Saved` as the first evidence and no taste evidence; visited exhibitions are excluded; exhibitions with no evidence are returned with an empty evidence list and `hasPersonalEvidence = false`; artist, art-term, text or followed-gallery matches set `hasPersonalEvidence = true`; ordering is score descending then id and identical for shuffled input; promotion state is never read
- [x] T006 [P] [US1] Add planner tests in `SHARED_TEST/map/NeighborhoodRoutePlannerTest.kt` using the new `plan(exhibitions, bookmarkedIds, request, forYouRelevance)` signature: For You returns a full route when only non-personal candidates exist; a personal candidate is chosen over a non-personal one at similar distance; a personal candidate that requires a large detour loses to a compact non-personal cluster; a saved exhibition is eligible and keeps `Saved` evidence in `recommendationEvidenceByExhibitionId`; a visited exhibition never appears. Update the existing For You tests (`for you route selects highest recommendations…`, `trades a small relevance difference…`, `prefers a compact same direction cluster…`) to build `RouteRelevance` inputs while keeping their asserted outcomes
- [x] T007 [P] [US1] Add `SHARED_TEST/map/DiscoveryFixtureRouteTest.kt` with `forYouRouteSucceedsFromMeasuredNeighbourhoods`: using `DiscoveryFixture`, a `LocalExhibitionRecommender` index, no history, `today = referenceDate`, 5 km radius and three stops, For You routes from HANNAM, SEONGSU and CHEONGDAM each return `RoutePlanResult.Success` with three distinct venues whenever at least three distinct open venues exist within the radius (assert that precondition explicitly per origin)
- [x] T008 [P] [US1] Add ViewModel tests in `APP_TEST/viewmodel/LocalDiscoveryViewModelTest.kt`: building a For You route calls `rankRouteCandidates` with the request origin, `ROUTE_RADIUS_KM`, current bookmarks, visits and follows; the recommendations state still holds at most six items with saved and visited excluded; update the test fake index (currently implementing the `fun interface`) to implement both methods
- [x] T009 [P] [US1] Add presentation tests in `APP_TEST/ui/discovery/RecommendationPresentationTest.kt` for `RecommendationEvidence.Saved`: `저장한 전시` in KO and `SAVED` in EN

### Implementation for User Story 1

- [x] T010 [US1] In `SHARED/recommendation/ExhibitionRecommendation.kt`: add `RecommendationEvidence.Saved`; add `RouteRelevanceContext` (require `maxDistanceKm > 0`) and `RouteRelevance` (require score in 0..10_000 and evidence size 0..2) with KDoc stating the contract in `contracts/shared-discovery-api.md`; change `ExhibitionRecommendationIndex` from `fun interface` to `interface` with `recommend` and `rankRouteCandidates`
- [x] T011 [US1] In `SHARED/recommendation/LocalExhibitionRecommender.kt`: implement `rankRouteCandidates` on `LocalExhibitionRecommendationIndex`, reusing the existing per-candidate scoring with the route origin and radius; keep bookmarked candidates (`Saved` evidence first, no taste evidence), exclude visited, keep zero-evidence candidates, skip the diversity pass and limit, and order by score then id. Extend `evidenceTier`, `deduplicationKey` and `stableSortKey` for `Saved` (tier before artist). Ensure `recommend()` never emits `Saved`
- [x] T012 [US1] In `SHARED/map/NeighborhoodRoutePlanner.kt`: replace the `recommendations` parameter with `forYouRelevance: List<RouteRelevance> = emptyList()`; For You eligibility = membership in `forYouRelevance`; add a `PERSONAL_RELEVANCE_CREDIT_METERS` constant to the beam objective for `hasPersonalEvidence` candidates; snapshot each selected candidate's evidence (possibly empty) into `recommendationEvidenceByExhibitionId`; update the `SAVED`, `NEIGHBORHOOD` and `CLOSING_SOON` call paths to the new signature without behaviour change
- [x] T013 [US1] In `APP/viewmodel/LocalDiscoveryViewModel.kt`: keep the prepared index and latest `RecommendationInputs` in `DiscoverySnapshot`; in `buildRoute()`, when the mode is `FOR_YOU`, compute `rankRouteCandidates(RouteRelevanceContext(origin = request.origin, maxDistanceKm = ROUTE_RADIUS_KM, today = request.visitDate, …))` on the background dispatcher and pass it to `routePlanner.plan`; other modes pass an empty list
- [x] T014 [US1] In `APP/ui/discovery/RecommendationPresentation.kt`: add the `RecommendationEvidence.Saved` branch to `localizedRecommendationEvidence` (`저장한 전시` / `SAVED`)
- [x] T015 [US1] Run `./gradlew shared:testAndroidHostTest composeApp:testAndroidHostTest` and confirm T005–T009 pass with all pre-existing tests green

**Checkpoint**: For You routes succeed from the measured neighbourhoods; the For You list is unchanged in shape.

---

## Phase 4: User Story 2 - Routes never send visitors to a closed venue (Priority: P1)

**Goal**: Read free-text hours, exclude venues closed on the visit date, order stops so each known-hours stop finishes before closing from a Korea-time start, and disclose unverified hours per stop (FR-006 to FR-012).

**Independent Test**: `DiscoveryFixtureRouteTest.noStopClosedOrLateAcrossWeek` and `DiscoveryFixtureHoursTest.readsListedHours` pass.

### Tests for User Story 2 (write first, confirm failing)

- [x] T016 [P] [US2] Add `SHARED_TEST/hours/OpeningHoursParserTest.kt` with one test per golden case 1–21 in `specs/088-discovery-accuracy-fixes/contracts/opening-hours-grammar.md`, asserting exact `byDay` and `completeness`; add a property-style test that `parseOpeningHours` never throws for a fixed list of malformed strings (unbalanced parentheses, lone dashes, `25:00`, emoji, 10 KB of text)
- [x] T017 [P] [US2] Add `SHARED_TEST/fixture/PublishedCatalogueHoursExpectations.kt` (hand-checked expected `WeeklyOpeningHours` keyed by exhibition id for every fixture row with non-blank hours, written from the raw text, not from parser output) and `SHARED_TEST/hours/DiscoveryFixtureHoursTest.kt` asserting all but at most one of the 68 rows read as `COMPLETE` or `PARTIAL` and every read value equals its expectation (SC-003)
- [x] T018 [P] [US2] Add planner hours tests in `SHARED_TEST/map/NeighborhoodRoutePlannerTest.kt`: a venue closed on the visit weekday is excluded in all four modes; with `startTime` set, every known-hours stop has `visitStart >= opens` and `visitEnd <= closes`; an early arrival records the wait in `estimatedWaitMinutes` and `estimatedTotalMinutes = travel + visit + wait`; a set that is only feasible in a non-distance-optimal order returns that order; when today's top-N set is infeasible, non-For-You modes fall back to first-fit in mode order; `stopSchedules` matches `stops` in order and size; `HOURS_UNVERIFIED` is present only if some stop is `UNVERIFIED`; a shortage caused by closures reports `closedCount`; `startTime = null` starts at the earliest opening among selected stops; a route whose hours allow today's selection returns exactly the previous stops and order. Update the existing assertion that `HOURS_UNVERIFIED` is always present
- [x] T019 [P] [US2] Add `noStopClosedOrLateAcrossWeek` to `SHARED_TEST/map/DiscoveryFixtureRouteTest.kt`: for each of the four modes, each weekday from `referenceDate` to `referenceDate + 6`, start times 10:00 to 17:00 hourly, origins HANNAM and SAMCHEONG, with bookmarks set to a fixed list of five fixture ids for `SAVED`, no successful route contains a known-closed stop or a known-hours stop with `visitEnd > closes` (SC-002)
- [x] T020 [P] [US2] Add ViewModel tests in `APP_TEST/viewmodel/LocalDiscoveryViewModelTest.kt`: with `nowProvider` fixed at 2026-10-05T05:30:00Z, `buildRoute()` sends `visitDate = 2026-10-05` and `startTime = 14:30` (Asia/Seoul) to the planner even when the device default zone differs; a shortage maps `closedCount` into `RouteUiState.Insufficient`
- [x] T021 [P] [US2] Add presentation tests in `APP_TEST/ui/route/RoutePresentationTest.kt`: an unverified stop with raw hours reads `운영 시간 미확인 · {raw}` / `HOURS NOT VERIFIED · {raw}`; a verified stop keeps `운영 시간 · {raw}` / `HOURS · {raw}`; the shortage message appends `주변 {n}곳은 지금 문을 닫았거나 곧 닫습니다.` / `1 nearby venue is closed or closing soon.` / `{n} nearby venues are closed or closing soon.` only when `closedCount > 0`; update the existing warning test fixture that always included `HOURS_UNVERIFIED`

### Implementation for User Story 2

- [x] T022 [P] [US2] Create `SHARED/hours/WeeklyOpeningHours.kt` with `OpeningHoursCompleteness`, `DailyOpening` (require `closes > opens`) and `WeeklyOpeningHours` (`isVerified`, `openingOn(date)`, `isKnownClosedOn(date)`) per `data-model.md`, with KDoc
- [x] T023 [US2] Create `SHARED/hours/OpeningHoursParser.kt` with `fun parseOpeningHours(text: String?): WeeklyOpeningHours` implementing the grammar in research R4 and the golden cases (segments, 12/24-hour ranges, English and Korean day ranges and lists, wrap past Sunday, `Closed`/`Closed on <days>`/`휴관`/`휴무`, ignored parentheticals and holiday/dated notes, overnight ranges rejected); total and deterministic
- [x] T024 [US2] In `SHARED/map/NeighborhoodRoutePlanner.kt`: add `RoutePlanningRequest.startTime: LocalTime? = null`, `RouteStopHoursStatus`, `RouteStopSchedule`, `ExhibitionRouteEstimate.stopSchedules` and `estimatedWaitMinutes` (total includes wait), and `InsufficientCandidates.closedCount = 0`; parse each candidate's hours once per `plan` call; exclude known-closed venues and venues that cannot finish one visit before closing after the walk from the origin, counting distinct venues in `closedCount`; add a schedule simulation used by `bestOrdering` (feasible orderings only), the For You beam (prune infeasible states, track departure time) and a first-fit fallback for non-For-You modes when the top-N set has no feasible ordering; emit `HOURS_UNVERIFIED` only when some stop is unverified
- [x] T025 [US2] In `APP/viewmodel/LocalDiscoveryViewModel.kt`: add `nowProvider: () -> Instant = { Clock.System.now() }` to the constructor and `factory`; in `buildRoute()` stamp the request with `visitDate` and `startTime` from `nowProvider()` in `TimeZone.of("Asia/Seoul")` before planning; add `closedCount` to `RouteUiState.Insufficient` and map it from the planner result
- [x] T026 [US2] In `APP/ui/route/RoutePresentation.kt` and `APP/ui/route/RoutePlannerScreen.kt`: make `routeHoursLabel` and `routeStopSemanticsLabel` take the stop's `RouteStopHoursStatus` and render the unverified prefix; extend `insufficientRouteMessage` with `closedCount`; pass `stopSchedules[index].hoursStatus` and `state.closedCount` from the screen
- [x] T027 [US2] Run `./gradlew shared:testAndroidHostTest composeApp:testAndroidHostTest` and confirm T016–T021 pass with all pre-existing tests green

**Checkpoint**: No route in the fixture week sweep sends a visitor to a closed venue.

---

## Phase 5: User Story 3 - Taste reasons mean something (Priority: P2)

**Goal**: Text similarity ignores venue boilerplate and ubiquitous n-grams with a calibrated threshold, and evidence combines across anchors (FR-013 to FR-017).

**Independent Test**: `DiscoveryFixtureSimilarityTest` and `DiscoveryFixtureTasteTest` pass.

### Tests for User Story 3 (write first, confirm failing)

- [x] T028 [P] [US3] Add tests in `SHARED_TEST/recommendation/LocalExhibitionRecommenderTest.kt`: two exhibitions at the same venue sharing only a boilerplate paragraph produce no `TextSimilarity` evidence; two exhibitions at different venues describing the same artist and subject still produce it; an n-gram present in more than `max(2, 20%)` of a 10-exhibition catalogue does not create similarity; a three-exhibition catalogue keeps similarity between two near-identical descriptions (floor of 2); three saves sharing an artist rank a matching candidate above an otherwise equal candidate matching a single unrelated save; aggregated strength is identical for any anchor order; visible evidence names the strongest single anchor; a prepared index from schema version 3 is not reused
- [x] T029 [P] [US3] Add `SHARED_TEST/recommendation/DiscoveryFixtureSimilarityTest.kt`: `textSimilarPairShare` asserts that at most 10% of fixture exhibition pairs produce `TextSimilarity` evidence when one of the pair is saved (SC-004), using the public `recommend` API over all pairs or a pair sweep through `rankRouteCandidates`; `sameArtistShowsAreMostSimilar` asserts that saving one of the two Georg Baselitz exhibitions makes the other its strongest text match
- [x] T030 [P] [US3] Add `SHARED_TEST/recommendation/DiscoveryFixtureTasteTest.kt` with at least four scenario histories built from fixture ids (Baselitz pair; the two Kukje Gallery painting shows; shows sharing the fixture's `medium:painting` art term; a no-history visitor) asserting the expected match ranks in the top three For You results (SC-005), every shown reason references a saved, visited or followed source present in the scenario (SC-006), and the no-history scenario shows no artist, art-term or text reason
- [x] T031 [P] [US3] Review `SHARED_TEST/recommendation/ExplainableLocalExhibitionRecommenderTest.kt` for tests whose premise is overlap of venue boilerplate; update only those inputs so they keep testing the intended evidence, and record each change in the test's comment

### Implementation for User Story 3

- [x] T032 [US3] In `SHARED/recommendation/LocalExhibitionRecommender.kt`: in `prepare`, remove venue-boilerplate n-grams (present in at least two exhibitions sharing `galleryIdentity()`) from those exhibitions' raw features and remove n-grams whose document frequency exceeds `max(2, 0.20 × catalogue size)`, before the IDF; set `SIMILARITY_REASON_THRESHOLD = 0.08`; bump `FEATURE_SCHEMA_VERSION` to 4
- [x] T033 [US3] In `SHARED/recommendation/LocalExhibitionRecommender.kt`: replace best-single-anchor strength in `bestArtistMatch`, `bestArtTermMatch` and `bestTextMatch` with a noisy-OR over all matching anchors per source (`1 − Π(1 − sᵢ)`), keeping the strongest anchor as the evidence anchor and the existing stable tie-breaks
- [x] T034 [US3] Run `./gradlew shared:testAndroidHostTest composeApp:testAndroidHostTest` and confirm T028–T031 pass with all pre-existing tests green

**Checkpoint**: Similarity reasons appear only for genuinely close exhibitions.

---

## Phase 6: Polish & Verification

- [x] T035 Update KDoc on `ExhibitionRecommendationIndex`, `NeighborhoodRoutePlanner.plan`, `RoutePlanningRequest` and `parseOpeningHours` in `SHARED/` to match `contracts/shared-discovery-api.md`, and remove any now-unused `recommendations` plumbing in `APP/viewmodel/LocalDiscoveryViewModel.kt`
- [x] T036 Run `./gradlew shared:ktlintCheck composeApp:ktlintCheck androidApp:ktlintCheck shared:allTests composeApp:allTests` (quickstart §4)
- [x] T037 Run `./gradlew composeApp:testAndroidHostTest androidApp:lintDebug androidApp:assembleDebug composeApp:linkReleaseFrameworkIosSimulatorArm64` (quickstart §4)
- [x] T038 Run the boundary check in quickstart §5 step 4 (`git diff develop --stat` over `supabase/`, analytics, network, the Android manifest and `iosApp/`) and confirm it is empty (SC-008)
- [ ] T039 Manual device checks in quickstart §5 steps 1–3 on Android and iOS (SC-007, Monday closures, per-stop unverified label); record results in the PR description
- [x] T040 Update `CHANGELOG.md` under the next unreleased version with a Fixed entry for For You routes, closed-venue routing and similarity reasons

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (T001–T002)** → **Foundational (T003–T004)** → user stories.
- **US1 (T005–T015)** and **US2 (T016–T027)**: tests are independent and can be written in parallel. Implementation is sequential, US1 then US2, because T012/T024 share `NeighborhoodRoutePlanner.kt` and T013/T025 share `LocalDiscoveryViewModel.kt`. US2 can ship without US1.
- **US3 (T028–T034)**: depends only on Foundational. T032/T033 share `LocalExhibitionRecommender.kt` with T011, so run after T011 to avoid conflicts.
- **Polish (T035–T040)** after all stories.

### Within each story

Tests first and failing → models → shared logic → ViewModel → presentation → story test run.

### Parallel opportunities

- T005, T006, T007, T008, T009 (different files).
- T016, T017, T018, T019, T020, T021, and T022 (model file) in parallel; T023 after T022.
- T028, T029, T030, T031.
- US3 tests can be written while US1/US2 implementation is in progress.

## Parallel Example: User Story 2

```text
Task: "T016 OpeningHoursParserTest golden cases in SHARED_TEST/hours/OpeningHoursParserTest.kt"
Task: "T017 Hand-checked hours expectations and DiscoveryFixtureHoursTest"
Task: "T018 Planner hours tests in SHARED_TEST/map/NeighborhoodRoutePlannerTest.kt"
Task: "T021 RoutePresentationTest copy for unverified hours and closed count"
Task: "T022 WeeklyOpeningHours model in SHARED/hours/WeeklyOpeningHours.kt"
```

## Implementation Strategy

### MVP

Setup + Foundational + US1. This fixes the most visible failure (For You routes returning "not enough
exhibitions" in most neighbourhoods) and is shippable on its own.

### Incremental delivery

1. US1: For You routes work from the map center.
2. US2: no closed venues; honest per-stop hours disclosure.
3. US3: trustworthy similarity reasons and combined taste.
4. Polish and full verification before the PR to `develop`.
