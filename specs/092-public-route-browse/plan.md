# Implementation Plan: Public route browse screen (동선 둘러보기)

**Branch**: `092-public-route-browse` | **Date**: 2026-10-14 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/092-public-route-browse/spec.md`

## Summary

Add an app-only 동선 둘러보기 screen that lists every live listed route. It offers 인기/최신 sorting, a district
filter and pages of 20. It is reached from "전체 보기" on the Home rail and on the Profile tab's 추천 동선 section,
which now always shows its header. Rows open the existing spec-089 preview, so copy and report work unchanged.

The server gains two additive read RPCs: `browse_public_routes` and `list_public_route_districts`. Both use
the same `route_first_shared_day` eligibility predicate as `list_public_routes`, which itself stays unchanged.
Each row carries up to three stop covers, so no per-route reads are needed.

On the app side:
- `shared` gets the domain types, the repository and API client methods, and the pure paging rules.
- A new `PublicRouteBrowseViewModel` owns the query, the pages and a scroll anchor.
- The screen reuses `GallrFilterChip` and the existing row line.

The FR-017 analytics are an optional final phase, because they change the aggregate analytics pipeline.

## Technical Context

**Language/Version**: Kotlin 2.x Multiplatform (versions from `gradle/libs.versions.toml`); SQL (Postgres 17 on Supabase); TypeScript on Deno (analytics phase only)
**Primary Dependencies**: Compose Multiplatform, JetBrains lifecycle ViewModel, Ktor client, kotlinx.serialization, kotlinx.datetime, Coil 3. No new dependency.
**Storage**: Supabase Postgres, read-only for this feature. No table or column changes.
**Testing**: kotlin-test + kotlinx-coroutines-test in `commonTest`; Compose UI tests in `composeApp/src/iosTest` (`androidx.compose.ui.test.v2.runComposeUiTest`); pgTAP; Deno test (analytics phase)
**Target Platform**: Android and iOS apps; Supabase (database functions)
**Project Type**: Mobile app plus backend (KMP `shared` / `composeApp`, `supabase/`)
**Performance Goals**: first page visible within 2 s on a typical Seoul mobile connection; following pages within 1 s (SC-002); one RPC per page and one for districts
**Constraints**: same visibility as `list_public_routes` (SC-004); no duplicate rows (SC-003); usable without an account; DESIGN.md monochrome with 0dp corners outside Home; no web surface
**Scale/Scope**: tens to low hundreds of eligible routes (each needs staff approval); 1 new screen, 1 ViewModel, 2 RPCs, 2 entry-point changes

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Gate | Status |
|-----------|------|--------|
| I. Spec-First | `spec.md` approved and committed (`84a17f20`) before planning | PASS |
| II. Test-First | pgTAP 066, shared `PublicRouteBrowseTest` / API client tests and `PublicRouteBrowseViewModelTest` are written and fail before each implementation task; the iOS UI test covers row → preview | PASS (enforced in tasks) |
| III. Simplicity & YAGNI | No new tables, dependencies or base classes. Offset paging instead of cursors (R2). Covers in the row instead of per-route reads (R3). The existing chip component and row line are reused. Analytics is a separable phase (R8) | PASS |
| IV. Incremental Delivery | US1 (browse + entry points) ships alone. US2 (sort), US3 (district) and US4 (states) layer on the same screen. Analytics can be cut | PASS |
| V. Observability | Repository failures log through `AppLog.tagged("PublicRouteBrowse")` with operations `browse_page_load_failed` and `browse_districts_load_failed`, exception type only, no ids or query text | PASS |
| VI. Shared-First | Sort, query, page and district types, paging rules, DTOs and repository live in `shared/commonMain`. The ViewModel only orchestrates. UI renders state | PASS |
| VII. Mobile-First | App path: Home and Profile → 동선 둘러보기 → preview → copy, entirely in the app. No web page, link or flow is added (FR-018), and popular routes stay app-only | PASS |

**Post-design re-check (after Phase 1)**: unchanged; all gates PASS. No Complexity Tracking entries.

## Project Structure

### Documentation (this feature)

```text
specs/092-public-route-browse/
├── plan.md              # This file
├── research.md          # Phase 0 decisions R1–R9
├── data-model.md        # Domain, DTO, repository and UI state
├── quickstart.md        # Verification steps
├── contracts/
│   └── browse-rpc.md    # RPC and analytics contract, database test list
├── checklists/
│   └── requirements.md
└── tasks.md             # Created by /speckit.tasks
```

### Source Code (repository root)

```text
supabase/
├── migrations/20261014120000_public_route_browse.sql            # browse_public_routes, list_public_route_districts (+ content_private impls, grants)
├── migrations/20261014120100_public_route_browse_analytics.sql  # optional phase: event constraint + service_record_mobile_analytics
├── functions/mobile-analytics/handler.ts (+ handler_test.ts)    # optional phase
└── tests/database/066_public_route_browse.test.sql              # (+ 067 for the optional analytics phase)

shared/src/commonMain/kotlin/com/gallr/shared/
├── route/PublicRoute.kt                     # + PublicRouteSort, PublicRouteDistrict, PublicRouteBrowseQuery, BrowsedPublicRoute, PublicRoutePage
├── route/PublicRouteBrowse.kt               # new: appendPage, nextOffset, resolveDistrict
├── repository/PersonalRouteRepository.kt    # + browsePublic, listPublicDistricts, PUBLIC_ROUTE_PAGE_SIZE
├── repository/PersonalRouteRepositoryImpl.kt
├── data/network/PersonalRouteApiClient.kt   # + two publicRpc calls
├── data/network/dto/PersonalRouteDto.kt     # + page, row-with-covers and district DTOs
└── analytics/MobileAnalyticsEvent.kt        # optional phase: ROUTE_BROWSE surface, PUBLIC_ROUTE_COPIED event
shared/src/commonTest/kotlin/com/gallr/shared/
├── route/PublicRouteBrowseTest.kt
├── data/network/PublicRouteApiClientTest.kt # extended
└── repository/PublicRouteRepositoryTest.kt  # extended

composeApp/src/commonMain/kotlin/com/gallr/app/
├── AppNavigationState.kt                    # + AppDestination.PublicRouteBrowse(source), showPublicRouteBrowse, return
├── App.kt                                   # wire ViewModel, destination, entry points; preview back returns to browse
├── viewmodel/PublicRouteBrowseViewModel.kt  # new
├── ui/route/publicroutes/PublicRouteBrowseScreen.kt        # new: header, sort + district chips, paged rows, states
├── ui/route/publicroutes/PublicRouteBrowsePresentation.kt  # new: KO/EN copy, cover list, empty/filter copy
├── ui/route/publicroutes/PublicRoutesSection.kt            # + optional onSeeAll; empty heading when it is set
├── ui/tabs/home/RoutesRail.kt + HomeScreen.kt              # header action 전체 보기
├── ui/components/GallrFilterChip.kt                        # moved from ui/tabs/list/ListScreenComponents.kt
└── ui/profile/ProfileTab.kt                                # passes onSeeAll through the routes slot
composeApp/src/commonTest/kotlin/com/gallr/app/
├── viewmodel/PublicRouteBrowseViewModelTest.kt
├── viewmodel/FakePersonalRouteRepository.kt                # + browse fakes
├── PublicRouteNavigationTest.kt                            # extended: browse → preview → back
└── ui/route/publicroutes/PublicRoutesPresentationTest.kt   # extended
composeApp/src/iosTest/kotlin/com/gallr/app/ui/route/publicroutes/PublicRouteBrowseScreenTest.kt

DESIGN.md        # new "Route browse screen" subsection under the route sections; changelog row
CHANGELOG.md     # Added entry at release time
```

**Structure Decision**: This is the existing KMP mobile-plus-Supabase layout. No new module. Server changes
are additive migrations with a matching pgTAP suite. Domain and data code go in `shared`, orchestration in a
new `composeApp` ViewModel, and rendering in `ui/route/publicroutes`, beside the existing preview and section.

## Design notes (for DESIGN.md, owner review before implementation of the screen)

- **Header**: back arrow and "동선 둘러보기" / "BROWSE ROUTES" in `labelLarge`; under it, the `labelSmall`
  `onSurfaceVariant` note "공개된 동선 {n}개" when the count is known from the first page and less than 20,
  omitted otherwise.
- **Controls**: the sort chips 인기 · 최신 (one always selected), then a horizontally scrolling district row
  starting with 전체. Both use `GallrFilterChip`: selected in the `activeIndicator` fill, unselected with a
  1dp `outlineVariant` border, 0dp corners, `sm` padding.
- **Row**: the route name in `titleSmall` (two lines max), then the existing 089 line in `labelSmall`
  `onSurfaceVariant` (`publicRouteRowLine`, which already includes "{M}월 {D}일부터"). Below that, up to three
  48dp square covers, 4dp apart, 0dp corners, full colour. Hairline `outlineVariant` divider. The whole row
  is one button with one merged description.
- **States**: five 089-style placeholder rows; the existing error copy with 다시 시도; an end-of-list
  retry; an empty state ("아직 공유된 동선이 없어요" plus a text button "동선 만들기" that opens the composer);
  a filtered empty state ("{district}을 지나는 동선이 없어요" plus "필터 지우기").
- **Profile section**: the heading row gains a right-aligned "전체 보기" text button. When nothing is listed,
  the heading, the button and the one-line empty note show instead of hiding the section.
- No orange outside selected chips; no Home 16dp corners on this screen.

## Delivery phases (input to /speckit.tasks)

1. **Foundation**: migration + pgTAP 066 (test first); shared types, pure rules, DTOs, API client and
   repository methods with tests; move `GallrFilterChip`.
2. **US1 (P1)**: browse ViewModel (popular, all districts, paging, row → preview, scroll anchor), screen,
   destination, Home and Profile entry points (Profile header always shown), navigation tests, iOS UI test.
3. **US2 (P2)**: sort chips and the reset-on-change rule.
4. **US3 (P2)**: districts read, chip row, filter, fallback when a district disappears.
5. **US4 (P3)**: placeholder, first-page error, end-of-list retry, the two empty states, accessibility pass.
6. **Analytics (P3, optional, R8)**: migration + pgTAP 067, Edge Function validation + Deno tests, Kotlin
   event and surface, ViewModel calls.
7. **Polish**: DESIGN.md subsection and changelog row; manual check on both platforms (quickstart §4);
   CHANGELOG entry at release.

## Risks

- **Empty production list.** If no routes are listed yet, the screen shows its empty state. That is by
  design (SC-006), but the owner may want editors to seed routes before release. The listed count should be
  checked in Admin.
- **Migration number collision with parallel branches** (066/067, `20261014120000`). Rename before merging
  if `develop` has moved on. Never rename a migration after it has been applied anywhere.
- **Analytics needs a separately authorized Edge Function deploy** and stays inactive until
  `MOBILE_ANALYTICS_ENABLED` is turned on.

## Complexity Tracking

No constitution violations to justify.
