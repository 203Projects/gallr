---
description: "Task list for 093 walking routes and a full-screen route map"
---

# Tasks: Walking routes and a full-screen route map

**Input**: Design documents from `/specs/093-walking-route-map/`
**Prerequisites**: plan.md (incl. owner decisions 2026-10-14), spec.md, research.md (R1–R9), data-model.md,
contracts/walking-route.md, quickstart.md

**Tests**: Required. The constitution's Principle II (Test-First) applies. Each test task is written and seen to fail
before the implementation it covers.

**Organization**: US2 (full-screen map) needs no backend and can ship first. US1, US3 and US4 depend on the
Foundational phase: the database, the Edge Function and the shared walking-leg layer.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1–US4 from spec.md
- Paths are relative to the repository root. In a worktree, prefix Gradle with
  `ANDROID_HOME=$HOME/Library/Android/sdk`. Deno tasks run from `supabase/functions/walking-route`.

---

## Phase 1: Setup

- [X] T001 Run `node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs`. Then confirm that `20261014130000` and pgTAP `067` are unused on `origin/develop`, on the open 092 branch (`origin/092-public-route-browse`, which uses `20261014120000`/`066`) and in the other worktrees. If either is taken, pick the next free values and update plan.md, research.md R9 and contracts/walking-route.md.
- [X] T002 [P] Scaffold `supabase/functions/walking-route/`:
  - `deno.json` with `test` and `check` tasks, copied from `supabase/functions/promoted-nearby/deno.json`;
  - `index.ts` wiring `createWalkingRouteHandler({ env, createBackend })`, as `promoted-nearby/index.ts` does;
  - an empty `fixtures/` folder;
  - `deno.lock` generated with `deno cache`.

  Add `[functions.walking-route]` to `supabase/config.toml` with `enabled = true`, `verify_jwt = false`, and `import_map`/`entrypoint` set like `record-exhibition-view`. Add `supabase/functions/walking-route/.env.local` to `.gitignore` if the existing patterns do not already cover it.

---

## Phase 2: Foundational (blocks US1, US3 and US4; not US2)

### Database (contract: contracts/walking-route.md, "Database objects")

- [X] T003 Write `supabase/tests/database/067_walking_route_cache.test.sql`, which fails first. It covers:
  - `content_private.walking_legs` and `content_private.walking_route_quota` exist with RLS on; `anon` and `authenticated` have no privileges on them.
  - The three `public.service_walking_*` functions can be executed only by `service_role`, with `search_path` pinned to empty.
  - The `from_key < to_key` check rejects unordered rows.
  - `service_walking_legs_store` stores a pair only when both `exhibition_id`s exist in `exhibition_catalog_v2` with matching rounded coordinates, and skips the pair silently otherwise. A corrected coordinate gives a new key.
  - `service_walking_legs_lookup` returns only exact keys.
  - `service_walking_route_consume` enforces 30 provider calls a minute and 1,500 a Seoul day project-wide, and 120 legs an hour per source. It is atomic, and its windows roll.

  Use the fixture style of `054_public_route_ranking.test.sql`.
- [X] T004 Create `supabase/migrations/20261014130000_walking_route_cache.sql` (`begin`/`commit`, idempotent `if not exists` / `create or replace`) with the tables, the check constraint, indexes, the three `security definer` service functions (`set search_path = ''`), the revokes and grants, and comments. Replay it in an isolated scratch stack until T003 and all existing suites pass (057 is excluded locally, per memory). Then run `supabase db lint` and the security advisors.

### Edge Function (contract: contracts/walking-route.md)

- [X] T005 [P] Write `supabase/functions/walking-route/polyline_test.ts` (fails first), then `polyline.ts`:
  - precision-5 encode and decode that round-trip;
  - reversal;
  - Douglas–Peucker simplification to at most 500 points that keeps both endpoints.
- [X] T006 [P] Write `providers/openrouteservice_test.ts` (fails first) against a recorded `fixtures/ors-foot-walking.json`, then `providers/openrouteservice.ts`:
  - POST to `https://api.heigit.org/openrouteservice/v2/directions/foot-walking/json` with `{coordinates:[[lng,lat],…], elevation:false, instructions:true}` and `Authorization: ORS_API_KEY`;
  - split the geometry into legs by `way_points` indices, with per-leg distance from `segments[i].distance`;
  - map HTTP 4xx/5xx and timeouts (8 s) to "unavailable" for every requested leg, and "no route" (ORS error code 2009 or similar) to unavailable for that leg.
- [X] T007 [P] Write `providers/osrm_test.ts` (fails first) against a recorded `fixtures/osrm-foot.json`, then `providers/osrm.ts`: `GET {OSRM_BASE_URL}/route/v1/foot/{lng,lat;…}?overview=full&geometries=polyline&steps=false`, splitting by `legs[i].distance` and the leg geometry. Both adapters implement the same `WalkingProvider` interface, chosen by `WALKING_ROUTE_PROVIDER=ors|osrm`.
- [X] T008 Write `handler_test.ts` (fails first) with a fake backend and a fake provider. It covers:
  - every status code in the contract;
  - the exact origin allow-list;
  - kill switch off: all legs `unavailable` and no backend or provider call;
  - a full cache hit never calls the provider;
  - a mixed request routes only the misses, in one provider call per consecutive run;
  - a reverse lookup returns a reversed polyline;
  - the store is called only for venue pairs;
  - a position-only first waypoint is `unavailable` unless `WALKING_ROUTE_ROUTE_POSITIONS=true`, and is never passed to the store or the logs;
  - provider budget exhausted: misses `unavailable`, hits served;
  - source quota exceeded: `429` with `Retry-After` of 3600 or less;
  - legs under 10 m come back `routed` with 0 m;
  - logged lines contain no digits-with-decimal coordinates, ids or polylines.
- [X] T009 Implement `handler.ts` (validation, orchestration, response), `backend.ts` (service RPC calls with the component-scoped secret key from `../_shared/supabase_keys.ts`, `resolveSupabaseSecretKey`), and `quota.ts` (HMAC-SHA-256 of the ingress address from `cf-connecting-ip` / `x-forwarded-for`, with `WALKING_ROUTE_HASH_SECRET`, following `mobile-analytics/handler.ts`). T005–T008 must pass with `deno task test`, and `deno task check` must be clean.
- [X] T010 Write `supabase/functions/walking-route/README.md` covering:
  - the contract summary;
  - every env var: `WALKING_ROUTE_ENABLED`, `WALKING_ROUTE_ALLOWED_ORIGINS`, `WALKING_ROUTE_PROVIDER`, `ORS_API_KEY`, `OSRM_BASE_URL`, `WALKING_ROUTE_HASH_SECRET`, `WALKING_ROUTE_ROUTE_POSITIONS`;
  - the release gate: HeiGIT's written confirmation that storing results is allowed, recorded before `WALKING_ROUTE_ENABLED=true` in production;
  - deploy order: migration, then function and secrets, then app;
  - the required credits (R7);
  - the switch to OSRM.

  It must match the `geocode-address` README's secret-handling runbook: never commit keys, keep env files ignored, and `--project-ref` verified against `supabase projects list`.

### Shared walking-leg layer (data-model.md)

- [X] T011 [P] Write `shared/src/commonTest/kotlin/com/gallr/shared/map/PolylineTest.kt` (fails first): decode known vectors, decode negative coordinates, and decode malformed input to an empty list.
- [X] T012 [P] Write `shared/src/commonTest/kotlin/com/gallr/shared/map/WalkingLegsTest.kt` (fails first). It covers:
  - `WalkingLegKey` rounds to 5 decimals;
  - `WalkingLegBook` answers both directions, reversing the geometry;
  - `RoutedLegEstimator` returns a routed leg with path distance and `walkingMinutes`, or falls back to `APPROXIMATE` with two-point geometry;
  - `walkingMinutes` is `ceil(distance / 4.5 km/h)`;
  - `legsToRequest` makes batches of at most 6 points overlapping by one, skips legs already in the book, and never includes a point that is not a venue.
- [X] T013 [P] Write `shared/src/commonTest/kotlin/com/gallr/shared/data/network/WalkingRouteApiClientTest.kt` (fails first, Ktor `MockEngine`). It covers:
  - the URL is `…/functions/v1/walking-route`;
  - `Origin: app://gallr` and the publishable `apikey` are sent, with no bearer token;
  - the body is waypoints only, each carrying `exhibition_id`;
  - a malformed or unknown leg status becomes unavailable;
  - non-200 is a failure.
- [X] T014 [P] Write `shared/src/commonTest/kotlin/com/gallr/shared/repository/WalkingRouteRepositoryTest.kt` (fails first). It covers:
  - `resolve` fills the book and ticks the version once per arrival;
  - a second `resolve` for the same stops makes no request;
  - unavailable legs are not stored in the book, so they retry next time (US3);
  - failure returns `Result.failure` and logs `walking_route_resolve_failed` with the exception type only.
- [X] T015 Implement `shared/src/commonMain/kotlin/com/gallr/shared/map/Polyline.kt` and `WalkingLegs.kt` (key, book, estimator, `walkingMinutes`, `legsToRequest`). T011 and T012 must pass.
- [X] T016 Implement `shared/src/commonMain/kotlin/com/gallr/shared/data/network/WalkingRouteApiClient.kt` and `dto/WalkingRouteDto.kt` (`@Serializable`, snake_case, explicit malformed-leg handling). T013 must pass.
- [X] T017 Implement `shared/src/commonMain/kotlin/com/gallr/shared/repository/WalkingRouteRepository.kt` (interface) and `WalkingRouteRepositoryImpl.kt` (shared `WalkingLegBook`, `version: StateFlow<Long>`, `runCatching` boundary). T014 must pass. Checkpoint: `./gradlew shared:ktlintCheck shared:allTests` green.

---

## Phase 3: User Story 2 — Explore the route on a full-screen map (Priority: P1) 🎯 ships first

**Goal**: Tapping the map panel on the planner result, composer and public preview opens a full-screen interactive
map: zoom and pan with north up, fit route, close and back, credits. The panel stays a picture.

**Independent Test**: On each screen, open the map, zoom to one stop, fit, close, and confirm the screen beneath is
unchanged (works with today's straight lines).

- [X] T018 [P] [US2] Write `composeApp/src/commonTest/kotlin/com/gallr/app/ui/route/RouteMapPresentationTest.kt` additions (fail first). They cover the KO/EN labels for expand ("지도 크게 보기" / "EXPAND MAP"), fit route ("경로 전체 보기" / "FIT ROUTE") and close ("닫기" / "CLOSE"); the route description ("경로 지도 · 정류장 3개 · 약 2.4 km" / "ROUTE MAP · 3 STOPS · ABOUT 2.4 KM"); the panel credit "© OpenStreetMap"; and the full credit, which includes the provider credit.
- [X] T019 [P] [US2] Write `composeApp/src/iosTest/kotlin/com/gallr/app/ui/route/RouteMapFullScreenTest.kt` (`runComposeUiTest` v2, fails first). It covers:
  - the expand control invokes `onExpand` once even on a double tap;
  - the overlay shows the fit-route and close controls with their labels;
  - close invokes `onClose`;
  - the overlay root carries the route description;
  - the credit text is present.
- [X] T020 [US2] Add the labels, description and credits to `composeApp/src/commonMain/kotlin/com/gallr/app/ui/route/RouteMapPresentation.kt`. T018 must pass.
- [X] T021 [US2] Refactor `composeApp/src/commonMain/kotlin/com/gallr/app/ui/route/RouteMap.kt`:
  - extract the sources and layers into a `RouteMapLayers` content used by both the panel and the full-screen map;
  - add an optional `onExpand` with a 44dp monochrome expand control at the top-right, with 0dp corners and the expand label;
  - add a compact `labelSmall` "© OpenStreetMap" credit at the bottom-left;
  - keep the panel's touch-blocking layer and `MapInteractions.None`, so the panel still never zooms or pans (FR-017).
- [X] T022 [US2] Create `composeApp/src/commonMain/kotlin/com/gallr/app/ui/route/RouteMapFullScreen.kt`:
  - a full-screen `Box` on `background` with `WindowInsets.safeDrawing`;
  - a `MaplibreMap` with `MapInteractions` for pan and zoom (incl. double-tap), rotation off, and `CameraConstraints` of min zoom 10, max zoom 18 and `maxPitch = 0`;
  - the camera fitted with `routeMapViewport` on open and on "fit route" (animated, with reduce-motion respected via `isReduceMotionOrScreenReaderActive`);
  - a 44dp close control, a "fit route" control and `PlatformBackHandler(onClose)`;
  - the credit, styled like the Map tab's (`SeoulDistrictMap.kt`), opening openstreetmap.org/copyright via `LocalUriHandler`;
  - root semantics with the route description.

  T019 must pass.
- [X] T023 [US2] Host the overlay in `ui/route/RoutePlannerScreen.kt`, `ui/route/composer/RouteComposerScreen.kt` (`RouteEvaluationMap`) and `ui/route/publicroutes/PublicRoutePreviewScreen.kt`: a screen-level `Box` with `var mapExpanded by rememberSaveable { mutableStateOf(false) }`, the panel's `onExpand = { mapExpanded = true }`, and `RouteMapFullScreen` drawn last when expanded. The screen's own state and `LazyListState` stay untouched (FR-015).
- [X] T024 [US2] Checkpoint:
  - `./gradlew composeApp:ktlintCheck composeApp:allTests`;
  - the uncached iOS link `./gradlew composeApp:linkDebugFrameworkIosSimulatorArm64 -Pgallr.checkNativeLinkage=true` (map gate);
  - quickstart §4 step 5 on the Android emulator and the iOS simulator.
  - 2026-10-14: automated parts green (composeApp host 615, iOS 614 incl. 3 overlay UI tests; uncached iOS link). Android emulator: the public preview's expand control opens the full-screen map fitted to the route; double-tap zooms, drag pans, north stays up, fit route returns, system back closes onto the unchanged preview; dashed estimate shown with no routing function served. iOS simulator (iPhone 17 Pro Max, iOS 26.5): the same preview opens the full-screen map fitted to the route; pinch zooms, drag pans, north stays up, fit route returns, close returns to the unchanged preview; the credit omits openrouteservice while no leg is routed. A left-edge swipe pans the map, as gallr has no iOS swipe-back (`PlatformBackHandler` is a no-op there), so close is the way out.

---

## Phase 4: User Story 1 — See the real walking path between stops (Priority: P1)

**Goal**: Venue-to-venue legs follow streets. Distance, times and verdicts come from the paths and are the same on
every surface. Stop selection never changes.

**Independent Test**: A route across the Han River shows street-following legs and path distances on the planner,
composer, preview and share card.

- [X] T025 [P] [US1] Write `shared/src/commonTest/kotlin/com/gallr/shared/map/PlannerRetimeTest.kt` (fails first). It covers:
  - `retime` keeps stops and order;
  - legs, totals, departure and schedules come from `RoutedLegEstimator`;
  - a stop pushed past closing gets `fitsHours = false` and is not dropped;
  - `RouteWarning.APPROXIMATE_DISTANCE` is present only when a venue-to-venue leg is approximate (plan decision 3), and the origin leg never counts.
- [X] T026 [P] [US1] Extend `shared/src/commonTest/kotlin/com/gallr/shared/route/RouteEvaluatorTest.kt` (fails first): with a `RoutedLegEstimator`, legs are `ROUTED` with walking minutes from path distance, verdicts use them, and the leg from the device origin stays `APPROXIMATE`.
- [X] T027 [US1] In `shared/src/commonMain/kotlin/com/gallr/shared/map/NeighborhoodRoutePlanner.kt`, add `retime(route, request, legEstimator)` and `RouteStopSchedule.fitsHours` (default true), and change `plan()`'s `APPROXIMATE_DISTANCE` rule to venue-to-venue legs only. If `RouteEvaluator` needs it, expose a matching venue-only flag on `PersonalRouteEvaluation`. T025 and T026 must pass, and existing planner and evaluator tests must stay green.
- [X] T028 [P] [US1] Add a `FakeWalkingRouteRepository` to the `composeApp` commonTest fakes. Extend `LocalDiscoveryViewModelTest`, `PersonalRouteComposerViewModelTest` and `PublicRoutesViewModelTest` (fail first). They cover:
  - planner: `resolve(stops)` after a successful plan; a book tick retimes the shown route; the selected stops are identical before and after;
  - composer: adding, removing and reordering a stop resolves only changed legs (the fake records the batches); the evaluation uses routed legs;
  - preview: opening resolves; reopening in the same session makes no request.
- [X] T029 [US1] Wire `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/LocalDiscoveryViewModel.kt`: inject `WalkingRouteRepository`; after `RoutePlanResult.Success`, launch `resolve(stops)`; combine the book version so the shown route is `retime`d with `RoutedLegEstimator`.
- [X] T030 [US1] Wire `viewmodel/PersonalRouteComposerViewModel.kt`: replace the default `LocalApproximateRouteLegEstimator` with `RoutedLegEstimator(repository.book)`, resolve on stop or order change (on drop, not during a drag), and re-evaluate on version ticks.
- [X] T031 [US1] Wire `viewmodel/PublicRoutesViewModel.kt`: resolve on `openPreview` once the stops load, and evaluate with `RoutedLegEstimator`.
- [X] T032 [US1] In `composeApp/src/commonMain/kotlin/com/gallr/app/share/RouteShareCard.kt`, take the distance from the same `RoutedLegEstimator` (FR-005), and add a test that the card distance equals the composer's for a fully routed route.
- [X] T033 [US1] In `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`, construct one `WalkingRouteApiClient` and `WalkingRouteRepositoryImpl` (supabase URL and publishable key, like `PromotionApiClient`), and pass it to the three ViewModel factories and the share orchestrator.
- [X] T034 [US1] In `ui/route/RoutePlannerScreen.kt` and `RoutePresentation.kt`, give a planner row whose schedule has `fitsHours = false` the shared blocking status line via `routeStatusTemplate` (`VisitCutShort` / `ArrivesAfterClose`), with a presentation test. The planner keeps its stops.

---

## Phase 5: User Story 3 — Know when a path is only an estimate (Priority: P2)

**Goal**: An estimated leg is drawn dashed with estimate wording. The route appears at once and updates in place.
The note follows the venue-only rule.

**Independent Test**: With routing off, legs are dashed and the note shows; after re-enabling and reopening, legs
are solid.

- [X] T035 [P] [US3] Extend `composeApp/src/commonTest/kotlin/com/gallr/app/ui/route/RouteMapFeaturesTest.kt` (fails first): leg features split into routed (solid) and estimated (dashed) collections from `RouteMapLeg(geometry, routed)`; the origin leg is always estimated; a zero-length leg draws nothing.
- [X] T036 [P] [US3] Extend the presentation tests (`RoutePresentation` planner summary and the composer summary in `RouteComposerPresentation`, fail first): the approximate-distance disclosure appears only with an estimated venue-to-venue leg.
- [X] T037 [US3] In `RouteMap.kt` / `RouteMapLayers`, draw two `LineLayer`s: routed as a solid 2dp black line, estimated as a 2dp black line with `dasharray` [2, 2]. Feed both the panel and the full-screen map from `RouteMapLeg`, and update the three screens' callers. T035 must pass.
- [X] T038 [US3] Apply the venue-only disclosure rule in `RoutePresentation.kt` and the composer summary. T036 must pass.
- [X] T039 [US3] Extend the ViewModel tests (fail first, then fix if needed): with the fake repository failing, the route shows at once with estimates; when legs arrive later, it updates without a reload; a failed leg is requested again on the next resolve.

---

## Phase 6: User Story 4 — Keep the visitor's location private (Priority: P2)

**Goal**: The visitor's position is never sent, stored or logged.

- [X] T040 [P] [US4] Write tests (fail first if any path leaks):
  - `WalkingLegsTest` / `WalkingRouteRepositoryTest`: `legsToRequest` and `resolve` never emit a waypoint without an `exhibitionId`, even when an origin is known;
  - `PersonalRouteComposerViewModelTest` and `LocalDiscoveryViewModelTest`: with location granted, every recorded batch contains only stop waypoints, and the origin leg is `APPROXIMATE`.
- [X] T041 [US4] Confirm the Deno position-only and log-redaction cases from T008 pass. Add a pgTAP assertion to `067` that `walking_legs` has no column able to hold a position that is not a venue (only `from_key`/`to_key` written by the venue-verified store). Fix any gap found.

---

## Phase 7: Polish & cross-cutting

- [X] T042 [P] In `DESIGN.md`, update "Route map panel": venue-to-venue line along streets, dashed estimates (incl. the first leg from the visitor's position), the expand control, the compact credit, and a new "Full-screen route map" subsection (controls, north up, fit route, credit, back). Add a decisions-log row. The owner approves this before merge.
- [X] T043 Run the full verification contract:
  - `./gradlew shared:ktlintCheck shared:allTests composeApp:ktlintCheck composeApp:allTests androidApp:ktlintCheck composeApp:testAndroidHostTest androidApp:lintDebug androidApp:assembleDebug`;
  - the uncached iOS link;
  - `deno task test` / `deno task check` for `walking-route`;
  - pgTAP (all suites);
  - the lineage validator;
  - `git diff --check`.
- [ ] T044 Run the manual pass (quickstart §4, all seven steps) on the Android emulator and the iOS simulator against a seeded local stack with `supabase functions serve walking-route`. The openrouteservice key goes only into the ignored `.env.local`, supplied by the owner and never printed. Spot-check five Seoul gallery legs for sensible footpaths (research R1).
  - Blocked on the owner's openrouteservice key: real paths cannot be produced locally without it. The fallback path (function unreachable, dashed estimates) was checked on the Android emulator.
- [ ] T045 Release preparation (not executed in this feature, recorded in `README.md` and the PR):
  - the owner obtains HeiGIT's written confirmation;
  - the owner creates the openrouteservice key and the secrets are set;
  - deploy in this order: migration, then function, then app;
  - `CHANGELOG.md` and the CLAUDE.md "Recent Changes" line at release.

---

## Dependencies & execution order

- **Setup (T001–T002)** comes first.
- **US2 (T018–T024)** depends only on Setup, and can run in parallel with Foundational.
- **Foundational (T003–T017)** blocks US1, US3 and US4:
  - database: T003 → T004;
  - Edge Function: T005 ∥ T006 ∥ T007 → T008 → T009 → T010;
  - shared: T011 ∥ T012 ∥ T013 ∥ T014 → T015 → T016 → T017;
  - the three tracks run in parallel.
- **US1 (T025–T034)** depends on Foundational and, for the map legs, on T021. Tests T025, T026 and T028 run in parallel; then T027 → T029 ∥ T030 ∥ T031 → T032 → T033 → T034.
- **US3 (T035–T039)** depends on US1's wiring (T029–T033) and on T021/T022.
- **US4 (T040–T041)** depends on T017 and T029–T030.
- **Polish (T042–T045)** comes last. T042 can start once US2 and US3 visuals settle.

## Parallel examples

```text
Track A (database): T003 → T004
Track B (function): T005 ∥ T006 ∥ T007 → T008 → T009 → T010
Track C (shared):   T011 ∥ T012 ∥ T013 ∥ T014 → T015 → T016 → T017
Track D (US2 map):  T018 ∥ T019 → T020 → T021 → T022 → T023 → T024
```

## Implementation strategy

1. **First increment (US2):** Setup plus T018–T024. A full-screen route map with today's lines, releasable on its own.
2. **Walking paths:** Foundational, then US1 and US3 together. The backend deploys before the app, and the app
   falls back gracefully if the function is off (kill switch) or unreachable.
3. **US4 checks,** then Polish. Production enablement waits for HeiGIT's written confirmation (T045).
