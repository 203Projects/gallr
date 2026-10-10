# Implementation Plan: Routes (personal and public)

**Branch**: `089-personal-routes` | **Date**: 2026-10-08 (public routes added the same day) | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/089-personal-routes/spec.md`

## Summary

Authors compose a named route of 2–10 exhibitions on the device, reorder it with live timing and hours verdicts, save and publish it, and share a monochrome card plus a link. Recipients read a server-rendered page at gallrmap.com/route/{id} without an account and walk it with per-stop Naver Map directions. Staff can revoke routes from Admin. The recipient-open rate is measurable from launch day.

The technical approach is settled in [`docs/designs/2026-10-07-personal-routes-design.md`](../../docs/designs/2026-10-07-personal-routes-design.md) and consolidated in [research.md](research.md):

- **Shared (KMP)**: a timeline extracted from the planner, a pure `RouteEvaluator` with eight verdicts and the reference-day rule, a revisioned DataStore draft with atomic operations and a bound pending action, and a Ktor ApiClient behind `PersonalRouteRepository`.
- **App**: composer and list ViewModels with one share orchestrator, Reorderable drag with edge auto-scroll, a picker, "동선에 추가", a monochrome share card and a three-state location permission.
- **Database**: route and stop tables with RLS, definer-rights functions for save, publish, delete, revoke, moderation read and page counts, and a recipient-loop view.
- **Web**: a Vercel Function page and event endpoint in `web/`, a JavaScript hours port held to a shared parity file.
- **Admin**: a look-up, preview and revoke page.

**Public routes (User Stories 7–11, combined 2026-10-08).** Authors list saved, published routes from 내 동선 with a consent dialog; editors are approved at once and other authors wait for staff review in Admin, where decisions bind to the reviewed revision. Readers browse 추천 동선 in the Map route sheet (top three, expanding to ten, ranked by 30-day copies of the approved version), open a read-only preview judged for the first day every stop is open, copy a route into their draft (counted once, resumed after sign-in) and report it. Settled in [`docs/designs/2026-10-08-public-routes-design.md`](../../docs/designs/2026-10-08-public-routes-design.md) and consolidated in [research.md](research.md) P1–P12:

- **Database**: listing columns on `personal_routes`, `route_saves` and `route_reports`, author/reader/staff functions with ranking and eligibility computed only in SQL ([contracts/public-routes-functions.md](contracts/public-routes-functions.md)); the read-path fix (`get_published_route`, T075) is already done.
- **Shared (KMP)**: repository extensions, listing and public-route domain models, guarded `copyIntoDraft`, `PendingKind.COPY`, one analytics event.
- **App**: `PublicRoutesViewModel`, the 추천 동선 section, preview, report sheet, consent dialog, 내 동선 listing actions and status lines, the composer warning.
- **Admin**: review queue and reports views in the route workspace.
- **Web**: unchanged; the route page stays read-only (constitution VII).

## Technical Context

**Language/Version**: Kotlin Multiplatform and Compose Multiplatform (versions from `gradle/libs.versions.toml`); SQL (Postgres via Supabase); Node.js 22.23.1 for `web/` (root `.node-version`); TypeScript/React for `admin/`
**Primary Dependencies**: existing: kotlinx-datetime, kotlinx-serialization, Ktor client, DataStore 1.2.1, JetBrains lifecycle ViewModel, MapLibre Compose (route map panel), Coil; new: `sh.calvin.reorderable` (KMP, current stable release) in `composeApp` commonMain. Web: Vercel Functions (Node runtime) inside the Eleventy project; no new npm runtime dependency planned.
**Storage**: Supabase Postgres tables `personal_routes` (plus listing columns), `personal_route_stops`, `route_page_daily`, `route_saves`, `route_reports` and a view; one DataStore entry for the device draft
**Testing**: kotlin-test + kotlinx-coroutines-test in `commonTest`; Android host tests for the parity file (hours in `shared`, status labels in `composeApp`); pgTAP and a two-session concurrency script; `deno task test` for `mobile-analytics`; Node tests, accessibility test and Playwright in `web/`; Vitest in `admin/`; emulator and simulator walkthroughs
**Target Platform**: Android and iOS apps; gallrmap.com on Vercel; Admin at admin.gallrmap.com; Supabase staging then production
**Project Type**: Mobile app (KMP) plus web page, staff web app and database
**Performance Goals**: evaluation of 10 stops per edit is instant on device (≤ 11 legs); route page uncached render under 1 s with one embedded route read plus two lookups; bursts absorbed by a 60 s edge cache (E-D13)
**Constraints**: public routes add no new dependency; ranking and eligibility only in SQL (P3); listing, copying and reporting only in the app (constitution VII); no service key in `web/` (E-D2, E-D5); stop snapshots only from the catalogue (E-D7); never reorder or drop stops; "모두 열림" only when every stop is open (RO1); publish only on an explicit share of the shown version (RO2); DESIGN.md accent rules unchanged (DR-D21)
**Scale/Scope**: about 70–80 live exhibitions; routes of 2–10 stops; unknown route volume at launch; at most 10 public routes per list read (target 30 approved live routes by week four); 7 user-facing surfaces for personal routes plus 4 for public routes (section, preview, report sheet, consent dialog) and 2 Admin views

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Status |
|---|---|---|
| I. Spec-First | `spec.md` with six prioritized stories and acceptance scenarios precedes this plan | PASS |
| II. Test-First | Every shared path starts from a failing test: timeline equality, evaluator fixed-clock cases, draft operations, repository error mapping, orchestrator; pgTAP before migration code; page handler tests before the handler | PASS |
| III. Simplicity & YAGNI | One new dependency (Reorderable) justified by required edge auto-scroll on two platforms (RR4); one draft, one share orchestrator, no likes/feeds/reader | PASS (dependency logged below) |
| IV. Incremental Delivery | Stories ship in order: compose (US1) is usable offline alone; save/share (US2) adds the database; recipient page (US3) adds web; list (US4), moderation (US5) and measurement (US6) follow | PASS |
| V. Observability | Route operations log through `AppLog.tagged(...)` with snake_case operation names and no ids or content (`route_save_failed`, `route_draft_decode_failed`, `route_pending_action_expired`); the web function logs status and timing without route content | PASS |
| VI. Shared-First | Evaluator, timeline, draft model and operations, repository interfaces, ApiClient and DTOs in `shared/commonMain`; ViewModels, orchestrator and Compose UI in `composeApp/commonMain`; only the location permission status and share sheet in platform source sets as thin adapters; `web/` and `admin/` stay independent artifacts (hours port parity enforced by a shared fixture file, not shared code) | PASS |
| Quality: KMP dependency check | Reorderable supports Compose Multiplatform including iOS | PASS |
| Quality: public interfaces documented | Contracts in `contracts/`; KDoc at definitions | PASS |
| VII. Mobile-First (v1.2.0) | Routes are composed, saved, listed, copied and reported only in the app; the web renders a single shared route read-only and gains no list, copy count or action; Admin and the gallery workspace are operator tools | PASS |

Public routes (User Stories 7–11), same gate:

| Principle | Check | Status |
|---|---|---|
| I. Spec-First | Stories 7–11, FR-040–FR-064 and SC-010–SC-017 in `spec.md`, with SC-012 thresholds set by the owner | PASS |
| II. Test-First | pgTAP for every listing transition, eligibility date case and ranking rule before the migration; repository, `copyIntoDraft` and `PublicRoutesViewModel` tests before code; Vitest before the Admin views; analytics handler test before the allow-list change | PASS |
| III. Simplicity & YAGNI | No new dependency, screen or tab; preview reuses `RouteMap`, stop rows and `RouteEvaluator`; no provenance column; no list screen or paging; no auto-hide | PASS |
| IV. Incremental Delivery | Section hides while no route is approved, so database and Admin can ship first; listing (US7) and review (US8) before browsing (US9) and copying (US10); measurement (US11) last | PASS |
| V. Observability | `AppLog.tagged("PublicRoutes")` with `public_routes_load_failed`, `public_route_copy_failed`, `route_listing_request_failed`, `route_report_failed`, exception type only | PASS |
| VI. Shared-First | Domain models, repository, draft copy and failure mapping in `shared/commonMain`; ranking in SQL; wording in `composeApp` presentation functions; no platform code | PASS |
| VII. Mobile-First | All public-route actions in the app; web unchanged and read-only (FR-062) | PASS |

Post-design re-check (after Phase 1, including public routes): unchanged, all PASS. The data model keeps all business rules in shared code or database functions; no rule lives only in a composable or a platform adapter.

## Project Structure

### Documentation (this feature)

```text
specs/089-personal-routes/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── database-functions.md
│   ├── route-page.md
│   ├── parity-file.md
│   └── shared-route-api.md
├── checklists/requirements.md
└── tasks.md              # /speckit.tasks
```

### Source Code (repository root)

```text
shared/src/commonMain/kotlin/com/gallr/shared/
├── map/                        # extracted route timeline (internal), planner refactor
├── route/                      # PersonalRoute, stops, RouteEvaluator, verdicts, draft model
├── data/network/               # PersonalRouteApiClient + DTOs
└── repository/                 # PersonalRouteRepository(+Impl), PersonalRouteDraftRepository (DataStore)
shared/src/commonTest/…          # evaluator, timeline, draft, repository tests
shared/src/androidHostTest/…     # opening-hours parity host test and file generator
composeApp/src/androidHostTest/… # status-label parity host test

composeApp/src/commonMain/kotlin/com/gallr/app/
├── ui/route/composer/          # composer, picker sheet, itinerary rows, summary, share card
├── ui/tabs/map/                # 내 동선 section; LocationPermissionState (three-state)
├── ui/detail/                  # "동선에 추가"
└── viewmodel/                  # PersonalRouteComposerViewModel, MyRoutesViewModel, RouteShareOrchestrator
composeApp/src/{androidMain,iosMain}/…  # location permission status adapters

supabase/migrations/            # personal routes migration (tables, RLS, functions, view)
supabase/tests/database/        # pgTAP suite
supabase/tests/personal_routes_concurrency.sh
supabase/functions/mobile-analytics/   # three new event names

web/api/                        # route page + events Vercel Function
web/vercel.json                 # rewrites
web/tests/                      # handler, verdict, accessibility, parity, Playwright

admin/src/                      # route moderation page, repository (+ in-memory), i18n strings

specs/089-personal-routes/contracts/opening-hours-parity.json

# Public routes (User Stories 7–11)
supabase/migrations/            # public routes migration after 20261010130000 (listing, saves, reports, functions)
supabase/tests/database/        # public routes pgTAP suite
shared/.../route/               # RouteListingState, RouteListingBlocker, PublicRouteSummary, PublicRoute, report/decline reasons
shared/.../repository/          # PersonalRouteRepository(+Impl) extensions; PersonalRouteDraftRepository.copyIntoDraft
shared/.../data/network/        # PersonalRouteApiClient: listing, public list, copy, report calls + DTOs
shared/.../analytics/           # public_routes_viewed
composeApp/.../ui/route/publicroutes/  # PublicRoutesSection, PublicRoutePreviewScreen, RouteReportSheet, presentation
composeApp/.../ui/route/composer/      # MyRoutesSection listing menu + status lines, ListingConsentDialog, composer warning
composeApp/.../viewmodel/       # PublicRoutesViewModel; MyRoutesViewModel and PersonalRouteComposerViewModel extensions
admin/src/                      # RouteModerationWorkspace queue and reports views, AdminRouteRepository extensions
supabase/functions/mobile-analytics/   # public_routes_viewed allow-list
DESIGN.md                       # new pattern entries and Decisions Log row
```

**Structure Decision**: Existing repository layout; no new module. Work splits into the lanes recorded in the design document: Lane A shared then composeApp (timeline → evaluator → draft and repository → ViewModels and UI), Lane B database then web, Lane C Admin after the migration contract, Lane D analytics events. The location permission adapter lands separately from other Map tab edits. Public routes follow the design's lanes: B (migration: listing state, then copies/reports/ranking), C (shared then composeApp, starting on fakes), D (Admin after the listing-state functions), E (analytics event); lane A (read-path fix) is done (T075).

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| New dependency `sh.calvin.reorderable` | Edge auto-scroll while dragging on Android and iOS is required (DR-D26, RR4) | Hand-built gesture plus auto-scroll is the riskiest composer code on two platforms (about 1.5 days of human work) |
| Server function inside the Eleventy web project | Supabase Edge Functions cannot serve HTML (R1) | Static pages cannot carry per-route Open Graph tags for chat previews |
| Native location adapters without a test written first | The three-state permission (RO6) depends on platform APIs that host tests cannot drive | The composer branch is unit-tested in `commonTest`; the native states are checked by hand on the emulator and simulator, as RO6 accepted |
| Duplicate hours reader in JavaScript | Constitution VI forbids web depending on the shared module | Shared code is not allowed; drift is controlled by one parity file read by both test suites |
