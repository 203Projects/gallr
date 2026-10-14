# Implementation Plan: Walking routes and a full-screen route map

**Branch**: `093-walking-route-map` | **Date**: 2026-10-14 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/093-walking-route-map/spec.md`

## Summary

Route lines follow real streets, and distances come from the walking path. Each venue-to-venue leg is computed once
by a new `walking-route` Edge Function that caches it in Postgres and calls free OpenStreetMap routing
(openrouteservice Standard via `api.heigit.org`, with a tested OSRM adapter as the switch-over). The app keeps its
synchronous route timing: an asynchronously filled `WalkingLegBook` feeds a `RoutedLegEstimator`, so the planner,
composer, public preview and share card retime from real distances without changing which stops are chosen. Legs
that can't be routed stay dashed estimates.

Tapping the map panel on any of the three route screens opens a full-screen overlay map with zoom and pan (north up),
fit-route and close controls, and the required credits. The panel itself stays a picture.

The visitor's own position is never sent anywhere. The leg from it stays an on-device dashed estimate (research R3,
owner decision 2026-10-14).

## Technical Context

**Language/Version**: Kotlin 2.4.20 Multiplatform; SQL (Postgres 17 on Supabase); TypeScript on Deno (root `.tool-versions`)
**Primary Dependencies**: Compose Multiplatform, maplibre-compose 0.20.0 (unchanged), Ktor, kotlinx.serialization; no new app dependency. The Edge Function uses only the Deno standard library and `fetch`.
**Storage**: Supabase Postgres: `content_private.walking_legs` cache and `content_private.walking_route_quota`. Nothing on the device beyond an in-memory leg book.
**Testing**: kotlin-test and coroutines-test in `commonTest`; Compose UI tests in `composeApp/src/iosTest` (`runComposeUiTest` v2); Deno tests; pgTAP
**Target Platform**: Android and iOS apps; Supabase Edge Functions and database
**Project Type**: Mobile app plus backend
**Performance Goals**: cached routes draw their paths within 1 s, new routes within 3 s (SC-002); at least 90% cache hits after warm-up (SC-003); 30 provider calls a minute and 1,500 a day, under the free 40 and 2,000
**Constraints**: free provider; credentials server-side; no visitor position leaves the device; estimates never shown as paths; stop selection unchanged; DESIGN.md monochrome
**Scale/Scope**: hundreds to low thousands of new venue pairs a month; 1 Edge Function, 1 migration, 3 route screens, 1 new overlay composable

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Gate | Status |
|-----------|------|--------|
| I. Spec-First | `spec.md` committed (`8ddad52b`) before planning | PASS |
| II. Test-First | Failing tests come first: pgTAP 067, Deno handler and adapter tests, shared tests (decoder, book, estimator, batch planning, `retime`), ViewModel tests, iOS UI tests for the overlay | PASS (enforced in tasks) |
| III. Simplicity & YAGNI | Synchronous estimator kept; no new app dependency; screen overlay rather than a navigation layer; OSRM adapter justified by the unconfirmed storage terms (R1) | PASS, see Complexity Tracking |
| IV. Incremental Delivery | US2 (full-screen map) ships without the backend; US1 and US3 together with the function; US4 is a constraint verified by tests | PASS |
| V. Observability | Function logs bounded outcome counts only; app logs `walking_route_resolve_failed` with the exception type only; no coordinates, ids or polylines | PASS |
| VI. Shared-First | Book, estimator, decoder, batching, `retime`, client and repository live in `shared/commonMain`; ViewModels only trigger resolve and re-evaluate | PASS |
| VII. Mobile-First | App-only change; the public web route page is untouched (FR-020). The web privacy notice needs no change under R3 | PASS |

**Post-design re-check**: unchanged, all PASS.

## Owner decisions (2026-10-14)

1. **Leg from the visitor's position (R3):** stays an on-device dashed estimate; the position never leaves the
   phone. Spec US4, FR-001, FR-004 and FR-007 amended.
2. **Provider (R1/R2):** openrouteservice, conditional on HeiGIT confirming in writing, before release, that storing
   results is allowed; otherwise switch to self-hosted OSRM by configuration.
3. **Approximate-distance note:** shown only when a venue-to-venue leg is estimated. The always-estimated first leg
   is marked by its dashed line and "약" wording.

## Project Structure

### Documentation (this feature)

```text
specs/093-walking-route-map/
├── plan.md, research.md (R1–R9), data-model.md, quickstart.md
├── contracts/walking-route.md
├── checklists/requirements.md
└── tasks.md            # /speckit.tasks
```

### Source Code (repository root)

```text
supabase/
├── migrations/20261014130000_walking_route_cache.sql      # walking_legs, walking_route_quota, 3 service RPCs
├── tests/database/067_walking_route_cache.test.sql
├── functions/walking-route/                               # index.ts, handler.ts, backend.ts, quota.ts,
│   │                                                      # providers/{openrouteservice,osrm}.ts, polyline.ts, README.md
│   └── *_test.ts, fixtures/ (recorded provider responses)
└── config.toml                                            # [functions.walking-route] verify_jwt = false

shared/src/commonMain/kotlin/com/gallr/shared/
├── map/WalkingLegs.kt             # WalkingLegKey, WalkingLegBook, RoutedLegEstimator, walkingMinutes, legsToRequest
├── map/Polyline.kt                # decodePolyline
├── map/NeighborhoodRoutePlanner.kt  # + retime(); RouteStopSchedule.fitsHours
├── data/network/WalkingRouteApiClient.kt (+ dto/WalkingRouteDto.kt)
└── repository/WalkingRouteRepository.kt (+ Impl)
shared/src/commonTest/...          # PolylineTest, WalkingLegsTest, PlannerRetimeTest, WalkingRouteApiClientTest, repository test

composeApp/src/commonMain/kotlin/com/gallr/app/
├── ui/route/RouteMap.kt           # solid/dashed leg sources, compact credit, expand control, onExpand
├── ui/route/RouteMapFullScreen.kt # new overlay: interactive map, fit route, close, credits, back handler
├── ui/route/RouteMapPresentation.kt # leg split, full-screen labels, route description
├── ui/route/RoutePlannerScreen.kt, ui/route/composer/RouteComposerScreen.kt,
│   ui/route/publicroutes/PublicRoutePreviewScreen.kt   # host the overlay
├── viewmodel/LocalDiscoveryViewModel.kt, PersonalRouteComposerViewModel.kt, PublicRoutesViewModel.kt  # resolve + re-evaluate
├── share/RouteShareCard.kt        # distance from RoutedLegEstimator
└── App.kt                         # construct WalkingRouteRepository, pass to the three ViewModels
composeApp/src/commonTest/..., composeApp/src/iosTest/.../RouteMapFullScreenTest.kt

DESIGN.md                          # Route map panel: walking line, dashed estimate, credit, expand, full-screen map
```

**Structure Decision**: The existing KMP-plus-Supabase layout. The new Edge Function follows the
`promoted-nearby`/`mobile-analytics` shape: anonymous, origin allow-list, kill switch, component-scoped secret
key, service-only RPCs.

## Delivery phases (input to /speckit.tasks)

1. **Full-screen map (US2, no backend):** overlay composable, expand control, credits, rotation off, three screens
   host it, UI tests. Shippable on its own with today's straight lines.
2. **Backend:** migration and pgTAP 067; Edge Function with both adapters, quotas, kill switch, Deno tests; README
   and runbook (secrets, deploy order).
3. **Shared:** decoder, book, estimator, batching, `retime`, client, repository, tests.
4. **App wiring (US1, US3):** three ViewModels resolve and re-evaluate; solid/dashed legs; disclosure rule
   (decision 3); share card distance; planner `fitsHours` conflict label.
5. **US4 checks:** tests asserting no position-only waypoint is sent and nothing position-keyed is stored.
6. **Polish:** DESIGN.md (owner approval), full verification gate, uncached iOS link, manual pass on both platforms
   with a seeded local stack and served function, CHANGELOG at release.

## Risks

- **HeiGIT declines storage, or changes the free plan:** switch to OSRM by configuration (R2); a host is needed then.
- **OSM footway gaps in Seoul:** spot-check gallery legs; a no-path leg falls back to the dashed estimate.
- **maplibre-compose gestures inside a route screen:** the overlay sits above the scrolling list, so gestures never
  reach it, but this must still be verified on both platforms (map change gate).
- **Planner retime turns a planned stop into a conflict:** shown honestly with `fitsHours = false`; stops are never
  reshuffled. Expected to be rare, because real distances are usually close to the old 1.25 × estimate.
- **Migration number collision with PR #351 (092):** this feature uses `20261014130000`/`067`; re-check before merge.

## Complexity Tracking

| Addition | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Two provider adapters (openrouteservice and OSRM) | openrouteservice's storage terms are unconfirmed (R1); the owner requires a free, long-lived, swappable provider | One adapter would make a forced switch an emergency rewrite of the function under time pressure |
| `WalkingLegBook` + `RoutedLegEstimator` instead of an async estimator | Keeps the planner's synchronous permutation search offline and unchanged (R5) | A suspend estimator would put network calls inside selection and change which stops are chosen |
