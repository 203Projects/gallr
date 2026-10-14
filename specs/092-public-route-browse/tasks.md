---
description: "Task list for 092 public route browse screen"
---

# Tasks: Public route browse screen (동선 둘러보기)

**Input**: Design documents from `/specs/092-public-route-browse/`
**Prerequisites**: plan.md, spec.md, research.md (R1–R9), data-model.md, contracts/browse-rpc.md, quickstart.md

**Tests**: Required. The constitution's Principle II (Test-First) applies, and the plan's Constitution Check
lists the suites. Every test task must be written and seen to fail before the implementation task that
follows it.

**Organization**: Tasks are grouped by user story. US1 is the MVP; US2–US4 add to the same screen; the
analytics phase is optional (research R8) and can be cut without touching US1–US4.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1–US4 from spec.md; AN = optional analytics phase (FR-017)
- Paths are relative to the repository root. In a worktree, prefix Gradle with
  `ANDROID_HOME=$HOME/Library/Android/sdk`.

---

## Phase 1: Setup

**Purpose**: Confirm the migration lineage and free the shared chip component.

- [X] T001 Run `node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs` and confirm `20261014120000` / pgTAP `066` are still free on `origin/develop` (`git fetch origin develop && git ls-tree origin/develop supabase/migrations supabase/tests/database`); if taken, choose the next free timestamp/number and update plan.md and contracts/browse-rpc.md.
- [X] T002 [P] Move `GallrFilterChip` (and only it) from `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/list/ListScreenComponents.kt` to the new `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/GallrFilterChip.kt`, keep it `internal` and unchanged, and update the imports in `ui/tabs/list/ListScreen.kt` and `ui/route/composer/RoutePickerSheet.kt`; run `./gradlew composeApp:ktlintCheck composeApp:compileKotlinIosSimulatorArm64`.

---

## Phase 2: Foundational (blocks every user story)

**Purpose**: The server reads, shared types, paging rules and data access that every story uses.

### Database (contract: contracts/browse-rpc.md)

- [X] T003 Write pgTAP `supabase/tests/database/066_public_route_browse.test.sql`, covering every bullet under "Database tests" in contracts/browse-rpc.md. Use 054_public_route_ranking.test.sql as the fixture pattern: approved, published, revoked, declined, ended-stop, missing-stop and no-shared-day routes; copies in and outside 30 days; two stops in one district; one stop with blank `region_ko`. Run it in an isolated scratch stack and confirm it fails because the functions do not exist.
- [X] T004 Create `supabase/migrations/20261014120000_public_route_browse.sql` (single `begin; … commit;`, `create or replace`, idempotent). It adds:
  - `content_private.browse_public_routes_impl(p_sort text, p_district text, p_limit integer, p_offset integer, p_today date) returns jsonb`. It filters by `content_private.route_first_shared_day(id, p_today) is not null` and optional `exists` on `personal_route_stops.region_ko = p_district`. Popular order is `copy_count_30d desc, listing_decided_at desc, id asc`; newest is `listing_decided_at desc, id asc`. `copy_count_30d` is computed as in `list_public_routes_impl`. Read `limit+1` rows to set `has_more`. Clamp limit to 1..50 (default 20) and offset to ≥0. An unknown sort raises `22023 route_browse_invalid_sort`. Row JSON uses the same keys as `list_public_routes` plus `cover_urls`: up to 3 distinct non-blank `exhibition_catalog_v2.cover_image_url` in stop order.
  - `content_private.list_public_route_districts_impl(p_today date) returns jsonb`: distinct non-blank `region_ko` over eligible routes, `min(region_en)`, `count(distinct route_id)`, ordered by `route_count desc, district_ko asc`.
  - The `public.browse_public_routes(p_sort text default 'popular', p_district text default null, p_limit integer default 20, p_offset integer default 0)` and `public.list_public_route_districts()` wrappers. Both are `security definer`, `stable`, `set search_path = ''`, and pass `(now() at time zone 'Asia/Seoul')::date`.
  - `comment on function` for each new function.
  - Revoke both impls from `public, anon, authenticated`; revoke the wrappers from `public`; grant execute on the wrappers to `anon, authenticated`.

  Then run all pgTAP suites (066 plus 054–056 unchanged) until they are green.

### Shared domain and data (data-model.md)

- [X] T005 [P] Write `shared/src/commonTest/kotlin/com/gallr/shared/route/PublicRouteBrowseTest.kt`, which fails first. It covers:
  - `appendPage` keeps the loaded order and drops ids already loaded.
  - `nextOffset` counts rows requested, not rows kept.
  - `resolveDistrict` returns null when the selected district is absent.
  - `PublicRouteSort` wire values are `popular` / `newest`.
  - `PublicRouteDistrict.localizedName` falls back to Korean when the English name is blank.
- [X] T006 [P] Extend `shared/src/commonTest/kotlin/com/gallr/shared/data/network/PublicRouteApiClientTest.kt`, which fails first. It covers:
  - The `browse_public_routes` request body carries `p_sort`/`p_district`/`p_limit`/`p_offset`, and omits `p_district` when null.
  - The page decodes rows plus `cover_urls` (missing → empty) and `has_more`.
  - A malformed row is skipped while the others are kept.
  - `list_public_route_districts` decodes, and blank `district_ko` rows are skipped.
  - The bearer token is sent only when signed in, matching `listPublic`.
- [X] T007 [P] Extend `shared/src/commonTest/kotlin/com/gallr/shared/repository/PublicRouteRepositoryTest.kt` (fails first): `browsePublic` and `listPublicDistricts` return `Result.success` on success and `Result.failure` when the source throws, without crashing.
- [X] T008 Add `PublicRouteSort`, `PublicRouteDistrict`, `PublicRouteBrowseQuery`, `BrowsedPublicRoute` and `PublicRoutePage` to `shared/src/commonMain/kotlin/com/gallr/shared/route/PublicRoute.kt`, and create `shared/src/commonMain/kotlin/com/gallr/shared/route/PublicRouteBrowse.kt` with `appendPage`, `nextOffset` and `resolveDistrict` (KDoc states the de-duplication and offset invariants). T005 must pass.
- [X] T009 Add `PublicRoutePageDto`, `BrowsedPublicRouteDto` (`PublicRouteSummaryDto` fields + `@SerialName("cover_urls") coverUrls: List<String> = emptyList()`) and `PublicRouteDistrictDto` with `toDomain()` in `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/PersonalRouteDto.kt`.
- [X] T010 Add `browsePublic(query, offset, limit)` and `listPublicDistricts()` to `shared/src/commonMain/kotlin/com/gallr/shared/data/network/PersonalRouteApiClient.kt` (and its source interface), using `publicRpc` and per-row `runCatching` decoding as in `listPublic`. T006 must pass.
- [X] T011 Add `browsePublic(query: PublicRouteBrowseQuery, offset: Int, limit: Int = PUBLIC_ROUTE_PAGE_SIZE): Result<PublicRoutePage>`, `listPublicDistricts(): Result<List<PublicRouteDistrict>>` and `const val PUBLIC_ROUTE_PAGE_SIZE = 20` to `shared/src/commonMain/kotlin/com/gallr/shared/repository/PersonalRouteRepository.kt`. Implement them in `PersonalRouteRepositoryImpl.kt` with the existing `call("…")` wrapper, using operation names `public_routes_browse` and `public_route_districts`. T007 must pass.
- [X] T012 Add browse and district fakes to `composeApp/src/commonTest/kotlin/com/gallr/app/viewmodel/FakePersonalRouteRepository.kt`: a scripted list of pages or failures per `(query, offset)`, a recorded call log, and a districts result. Also add no-op overrides to any other `PersonalRouteRepository` test doubles so `./gradlew shared:allTests composeApp:compileTestKotlinIosSimulatorArm64` compiles.
- [X] T013 Checkpoint: `./gradlew shared:ktlintCheck shared:allTests` green.

---

## Phase 3: User Story 1 — Browse every listed route (Priority: P1) 🎯 MVP

**Goal**: 전체 보기 on the Home rail and the Profile 추천 동선 section opens 동선 둘러보기. It lists every eligible route by popularity in pages of 20 with covers; tapping a row opens the existing preview, and back restores the list.

**Independent Test**: With 45 eligible routes in the fake (and on a local stack), open from Home and from Profile, scroll to the end, and confirm 45 unique rows in popular order. Open one, copy or go back, and confirm the list and scroll position are unchanged. This works signed out.

### Tests for User Story 1 (write first, confirm they fail)

- [X] T014 [P] [US1] Write `composeApp/src/commonTest/kotlin/com/gallr/app/viewmodel/PublicRouteBrowseViewModelTest.kt`. Keep a subscriber alive with `backgroundScope.launch { vm.state.collect {} }`. It covers:
  - `open(source)` loads offset 0 with `POPULAR`/all districts.
  - `loadMore()` requests offset 20, then 40, and stops when `hasMore` is false.
  - Concurrent `loadMore()` calls make one request.
  - Duplicate ids across pages are dropped.
  - The scroll anchor is saved and restored.
  - Reopening reloads from offset 0 and keeps the query.
  - `today` is the Seoul date from an injected clock.
- [X] T015 [P] [US1] Extend `composeApp/src/commonTest/kotlin/com/gallr/app/PublicRouteNavigationTest.kt`:
  - `showPublicRouteBrowse(source)` sets `AppDestination.PublicRouteBrowse`.
  - `showPublicRoute` from the browse screen, then `returnFromPublicRoute()`, returns to the browse screen.
  - Back from the browse screen returns to the tab it was opened from.
- [X] T016 [P] [US1] Extend `composeApp/src/commonTest/kotlin/com/gallr/app/ui/route/publicroutes/PublicRoutesPresentationTest.kt` with KO/EN copy for the screen title, the 전체 보기 / SEE ALL action, the Profile empty note "아직 공유된 동선이 없어요" / "NO SHARED ROUTES YET", and the row content description (name + `publicRouteRowLine`).
- [X] T017 [P] [US1] Write `composeApp/src/iosTest/kotlin/com/gallr/app/ui/route/publicroutes/PublicRouteBrowseScreenTest.kt` with `androidx.compose.ui.test.v2.runComposeUiTest`:
  - The rows render with their merged description.
  - Tapping a row invokes `onOpenRoute` with that summary.
  - Scrolling to the last row invokes `onLoadMore`.
  - Back invokes `onBack`.

  Run it with `./gradlew composeApp:iosSimulatorArm64Test` and confirm it fails.

### Implementation for User Story 1

- [X] T018 [US1] Create `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/PublicRouteBrowseViewModel.kt`:
  - Constructor: `PersonalRouteRepository`, `Clock`, background dispatcher.
  - State: a private `MutableStateFlow<PublicRouteBrowseUiState>` exposed via `asStateFlow()`, with `BrowseListState` per data-model.md.
  - Events: `open(source)`, `loadMore()` and `saveScrollAnchor(index, offset)`.
  - Uses `appendPage`/`nextOffset`.
  - Logging through `AppLog.tagged("PublicRouteBrowse")` with `browse_page_load_failed` (exception type only).
  - A `viewModelFactory { initializer { } }` factory.

  T014 must pass.
- [X] T019 [US1] Add `data class PublicRouteBrowse(val source: PublicRouteBrowseSource)` to `AppDestination`. Add `enum class PublicRouteBrowseSource { HOME, PROFILE }`, `showPublicRouteBrowse(source)` (remembering the back destination) and `returnFromPublicRouteBrowse()` in `composeApp/src/commonMain/kotlin/com/gallr/app/AppNavigationState.kt`. T015 must pass.
- [X] T020 [P] [US1] Create `composeApp/src/commonMain/kotlin/com/gallr/app/ui/route/publicroutes/PublicRouteBrowsePresentation.kt`: KO/EN strings (title, see-all, empty note, row description) and `browseRowCovers(route)` (≤3). Reuse `publicRouteRowLine` from `PublicRoutesPresentation.kt`. T016 must pass.
- [X] T021 [US1] Create `composeApp/src/commonMain/kotlin/com/gallr/app/ui/route/publicroutes/PublicRouteBrowseScreen.kt`:
  - A route-level `PublicRouteBrowseRoute(viewModel, language, onOpenRoute, onBack)` and a stateless `PublicRouteBrowseScreen(state, language, onOpenRoute, onLoadMore, onBack, onScrollAnchor)`.
  - Header: back arrow + `labelLarge` title.
  - A `LazyColumn` of rows: `titleSmall` name (max 2 lines), `labelSmall` `onSurfaceVariant` row line, then up to three 48dp square Coil covers 4dp apart with 0dp corners (`nativeSupabaseImageUrl`). An `outlineVariant` hairline. The whole row is one button with merged semantics.
  - `loadMore` fires when the last 5 items become visible.
  - The `LazyListState` is initialised from and saved to the scroll anchor.
  - No orange and no 16dp corners (DESIGN.md).

  T017 must pass.
- [X] T022 [US1] Add an optional `onSeeAll: (() -> Unit)? = null` to `PublicRoutesSectionRoute`/`PublicRoutesSection` in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/route/publicroutes/PublicRoutesSection.kt`. When it is set: the heading row shows a right-aligned 전체 보기 `TextButton` (`RectangleShape`). In the `Hidden` state, show the heading, the action and the `labelSmall` empty note instead of nothing. When it is null, behaviour is unchanged (planner sheet).
- [X] T023 [US1] Add `onSeeAllRoutes: () -> Unit` to `HomeScreen` and pass it to `RoutesRail`. That function forwards `action`/`onAction` to its `SectionHeader`, with KO "전체 보기" / EN "SEE ALL" added to `HomePresentation.kt`'s copy. Files: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/home/HomeScreen.kt`, `RoutesRail.kt`, `HomePresentation.kt`. Extend `composeApp/src/commonTest/kotlin/com/gallr/app/ui/tabs/home/RoutesRailPresentationTest.kt` for the copy.
- [X] T024 [US1] Wire everything in `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`:
  - Create `publicRouteBrowseViewModel` beside `publicRoutesViewModel`.
  - Pass `onSeeAllRoutes = { navigation.showPublicRouteBrowse(HOME) }` to `HomeScreen`.
  - Pass `onSeeAll = { navigation.showPublicRouteBrowse(PROFILE) }` to the Profile tab's `PublicRoutesSectionRoute`; the planner sheet's call stays without it.
  - Render `AppDestination.PublicRouteBrowse` with `PlatformBackHandler(navigation::returnFromPublicRouteBrowse)` and `LaunchedEffect(destination) { viewModel.open(source) }`.
  - Make rows call `publicRoutesViewModel.openPreview(summary)` then `navigation.showPublicRoute(summary.id)`.
  - Add the destination to the analytics `when` (null surface until the analytics phase).
- [ ] T025 [US1] Checkpoint: `./gradlew composeApp:ktlintCheck composeApp:allTests` green. Check on the iOS simulator and an Android emulator against a local stack seeded with ≥25 routes: quickstart §4 steps 1, 2, 4 and 5 (the populated Profile path).

---

## Phase 4: User Story 2 — Sort by popular or newest (Priority: P2)

**Goal**: 인기 / 최신 chips. Changing the sort reloads from the top. Order is stable.

**Independent Test**: With fake routes whose copy counts and approval times disagree, switch the sort and confirm each order and that paging restarts at offset 0.

- [X] T026 [P] [US2] Extend `PublicRouteBrowseViewModelTest.kt` (fails first): `selectSort(NEWEST)` requests offset 0 with `newest`, clears loaded rows, resets the scroll anchor and ignores late results of the previous query (the in-flight load is cancelled). Selecting the current sort does nothing.
- [X] T027 [P] [US2] Extend `PublicRouteBrowseScreenTest.kt` (fails first): two sort chips, exactly one selected; the selected state is announced; tapping 최신 invokes `onSelectSort(NEWEST)`.
- [X] T028 [US2] Implement `selectSort` in `PublicRouteBrowseViewModel.kt` (cancelling the previous load `Job`), add the KO/EN chip labels to `PublicRouteBrowsePresentation.kt`, and render the sort chip row with `GallrFilterChip` under the header in `PublicRouteBrowseScreen.kt`. T026 and T027 must pass.

---

## Phase 5: User Story 3 — Filter by district (Priority: P2)

**Goal**: A district chip row (전체 + districts with routes); selecting one limits rows to routes passing through it.

**Independent Test**: Fake districts and filtered pages. Select 성수동 and confirm the request carries `p_district=성수동` and the rows; tap it again or pick 전체 to clear. English shows English names but filters on the Korean key.

- [X] T029 [P] [US3] Extend `PublicRouteBrowseViewModelTest.kt` (fails first). It covers:
  - `open` loads districts once per open.
  - `selectDistrict("성수동")` reloads from offset 0 with that district.
  - Selecting the selected district or `clearDistrict()` clears it.
  - After a reload where the district is gone, `resolveDistrict` falls back to all.
  - A failed districts read leaves an empty chip row (logged as `browse_districts_load_failed`) while rows still load.
- [X] T030 [P] [US3] Extend `PublicRouteBrowseScreenTest.kt` (fails first): the district row starts with 전체, scrolls horizontally, uses `localizedName(language)`, and tapping a chip invokes `onSelectDistrict(nameKo)`.
- [X] T031 [US3] Implement the district load, `selectDistrict` and `clearDistrict` in `PublicRouteBrowseViewModel.kt`. Render the scrolling `GallrFilterChip` district row under the sort chips in `PublicRouteBrowseScreen.kt`. Add the 전체 / ALL label to `PublicRouteBrowsePresentation.kt`. T029 and T030 must pass.

---

## Phase 6: User Story 4 — Loading, error and empty states (Priority: P3)

**Goal**: Placeholders, a first-page error with retry, an end-of-list retry, an empty state with a composer action, and a filtered empty state with a clear action.

**Independent Test**: Script first-page failure, later-page failure, empty and filtered-empty responses in the fake, and confirm each state and its action.

- [X] T032 [P] [US4] Extend `PublicRouteBrowseViewModelTest.kt` (fails first). It covers:
  - `Loading` → `FirstPageError` → `retry()` reloads the same query.
  - A later-page failure keeps rows and sets `appendFailed`; `retryMore()` requests the same offset.
  - An empty unfiltered result gives `Empty(filtered=false)`; an empty filtered result gives `Empty(filtered=true)`.
- [X] T033 [P] [US4] Extend `PublicRoutesPresentationTest.kt`/a new `PublicRouteBrowsePresentationTest.kt` (fails first) for the KO/EN copy: the empty title, "동선 만들기" / "MAKE A ROUTE", "{district}을/를 지나는 동선이 없어요" with the correct Korean particle after a vowel or consonant, and "필터 지우기" / "CLEAR FILTER". Reuse the existing error and retry copy (`publicRoutesLoadFailedMessage`, `myRoutesRetryLabel`).
- [X] T034 [P] [US4] Extend `PublicRouteBrowseScreenTest.kt` (fails first): placeholder rows while loading, the error + retry, the end-of-list retry, the empty-state composer action invoking `onMakeRoute`, and the filtered-empty clear action invoking `onClearDistrict`.
- [X] T035 [US4] Implement `retry`, `retryMore` and the empty states in `PublicRouteBrowseViewModel.kt`; render 089-style skeleton rows, `GallrErrorMessage`, the end-of-list retry and both empty states in `PublicRouteBrowseScreen.kt`; add the copy to `PublicRouteBrowsePresentation.kt`. Wire `onMakeRoute = navigation::showRouteComposer` in `App.kt`. T032–T034 must pass.
- [X] T036 [US4] Accessibility pass on `PublicRouteBrowseScreen.kt`: header as `heading()`, chips with `Role.Tab`/selected `stateDescription` per the existing `GallrFilterChip` semantics, 48dp minimum touch targets, and reduce-motion respected for any crossfade (`isReduceMotionOrScreenReaderActive`). Verify with VoiceOver and TalkBack (quickstart §4.8).

---

## Phase 7: Optional analytics (FR-017, research R8) [AN]

**Purpose**: Count browse-screen views and copies by entry point. Cut the whole phase if the owner prefers; no other phase depends on it.

- [ ] T037 [AN] Write pgTAP `supabase/tests/database/067_public_route_browse_analytics.test.sql`, which fails first. It checks that:
  - `public_routes_viewed` is accepted with and without `surface` in `featured|my_gallr|map|route_browse`.
  - `public_route_copied` is accepted with exactly `{surface}`.
  - Extra keys, an unknown surface, or a route id are rejected with `mobile_analytics_event_invalid`.

  Use `056_public_routes_viewed_event.test.sql` as the pattern.
- [ ] T038 [AN] Create `supabase/migrations/20261014120100_public_route_browse_analytics.sql`. Replace the `content.mobile_analytics_daily` event-name constraint to add `public_route_copied`, and `create or replace` `public.service_record_mobile_analytics`. Start from its latest definition in `20261010170000_public_routes_viewed_event.sql` (confirm no later migration redefined it) with the R8 rules. T037 and 056 must pass.
- [ ] T039 [P] [AN] Extend `supabase/functions/mobile-analytics/handler_test.ts` (fails first), then update `handler.ts`'s validator for the optional `surface` on `public_routes_viewed` and the new `public_route_copied`; run `deno test` in `supabase/functions/mobile-analytics`.
- [ ] T040 [P] [AN] Extend `shared/src/commonTest/kotlin/com/gallr/shared/analytics/MobileAnalyticsEventTest.kt` and `MobileAnalyticsEventFactoryTest.kt` (fail first). Then add `AnalyticsSurface.ROUTE_BROWSE` (`route_browse`), `MobileAnalyticsEventName.PUBLIC_ROUTE_COPIED`, an optional `surface` on `publicRoutesViewed`, and `publicRouteCopied(surface)` in `shared/src/commonMain/kotlin/com/gallr/shared/analytics/MobileAnalyticsEvent.kt` and `MobileAnalyticsEventFactory.kt`.
- [ ] T041 [AN] Extend `composeApp/src/commonTest/kotlin/com/gallr/app/analytics/MobileAnalyticsTrackerTest.kt`, `PublicRoutesViewModelTest.kt` and `PublicRouteBrowseViewModelTest.kt` (fail first).
  - Extend `RouteAnalytics` with `publicRoutesViewed(rowsShown, surface)` and `publicRouteCopied(surface)`, and implement them in `composeApp/src/commonMain/kotlin/com/gallr/app/analytics/MobileAnalyticsTracker.kt`.
  - The browse ViewModel records a view (rows on screen, capped at 10) once per open.
  - `PublicRoutesViewModel.openPreview` takes the opening surface, and a counted copy records `publicRouteCopied(surface)`.
  - Pass the surface from Home (`featured`), Profile (`my_gallr`), the planner (`map`) and the browse screen (`route_browse`) in `App.kt`.
- [ ] T042 [AN] Release note for operators in `supabase/functions/mobile-analytics/README.md`: apply migration `20261014120100` before deploying the function. The deploy is a separately authorized step.

---

## Phase 8: Polish & cross-cutting

- [X] T043 [P] Add a "Route browse screen (added 2026-10-14)" subsection after "Route map panel" in `DESIGN.md` (header, chips, row with 48dp covers, states, Profile section see-all and empty note), plus a changelog-table row. Get owner approval of this text before merging (CLAUDE.md: DESIGN.md is the visual source of truth).
- [X] T044 Run the full verification contract for the touched surfaces:
  - `./gradlew shared:ktlintCheck shared:allTests composeApp:ktlintCheck composeApp:allTests androidApp:ktlintCheck composeApp:testAndroidHostTest androidApp:lintDebug androidApp:assembleDebug`
  - pgTAP (066, and 067 if the AN phase is delivered, plus 054–056)
  - The migration lineage validator
  - `git diff --check`
- [ ] T045 Manual check on both platforms per quickstart §4 (all eight steps), including signed out and in English.
- [ ] T046 Add the `CHANGELOG.md` "Added" entry and the CLAUDE.md "Recent Changes" line at release time, with the release bump of `VERSION`/`versionCode`/`versionName` per CLAUDE.md "Release & feature workflow".

---

## Dependencies & execution order

- **Setup (T001–T002)** comes first. T002 is independent of T001.
- **Foundational (T003–T013)** blocks every story. Inside it:
  - T003 → T004.
  - T005/T006/T007 run in parallel, then T008 → T009 → T010 → T011 → T012 → T013.
  - The database (T003–T004) and the Kotlin track (T005–T013) can run in parallel.
- **US1 (T014–T025)** depends on Foundational. Its tests T014–T017 run in parallel; then T018 → T019 → (T020 ∥ T022 ∥ T023) → T021 → T024 → T025.
- **US2 (T026–T028)** and **US3 (T029–T031)** depend on US1's ViewModel and screen (T018, T021). They touch the same two files, so run them one after the other (US2 then US3), not in parallel.
- **US4 (T032–T036)** depends on US1. It can follow US2/US3 in either order, but it edits the same files, so it runs sequentially.
- **Analytics (T037–T042)** depends on US1 (T018, T024) only:
  - T037 → T038.
  - T039 and T040 run in parallel with the database work.
  - T041 needs T040.
- **Polish (T043–T046)** comes last. T043 can start as soon as US1's visuals are settled.

## Parallel examples

```text
# Foundational, two tracks at once
Track A: T003 (pgTAP 066) → T004 (migration)
Track B: T005 ∥ T006 ∥ T007 (failing shared tests) → T008 → T009 → T010 → T011 → T012

# US1 tests together
T014 (ViewModel test) ∥ T015 (navigation test) ∥ T016 (copy test) ∥ T017 (iOS UI test)

# US1 implementation after T018/T019
T020 (presentation) ∥ T022 (Profile section) ∥ T023 (Home rail)

# Analytics
T037 → T038 (database)  ∥  T039 (Edge Function)  ∥  T040 (shared events) → T041
```

## Implementation strategy

1. **MVP**: Setup + Foundational + US1 (T001–T025). Readers can reach and page every listed route from Home
   and Profile, open any of them and copy it. Stop and validate (T025) before going further.
2. **Increment 2**: US2 sort, then US3 district filter. Each is validated by its independent test.
3. **Increment 3**: US4 states and the accessibility pass.
4. **Optional**: the analytics phase, if the owner keeps FR-017.
5. **Release**: Polish. The database migration ships before the app release (additive; research R9). The
   analytics Edge Function deploy is a separate authorized step.
