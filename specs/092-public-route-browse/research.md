# Research: Public route browse screen (092)

Each decision resolves an open point in the plan's Technical Context. The code paths cited were read on
`develop` at `62ac855f` (2026-10-14).

## R1. One visibility rule for the list and the browse screen

**Decision**: The new server read filters routes with the existing
`content_private.route_first_shared_day(route_id, today) is not null`, the same predicate that
`list_public_routes_impl`, `get_listed_route`, `save_public_route_impl` and `report_route_impl` use
(`supabase/migrations/20261010150000_public_route_ranking.sql`). The browse read is a new function. The
recorded migration and `list_public_routes` are left untouched.

**Rationale**: FR-001, FR-014 and SC-004 require exactly 089's rules. Reusing the one predicate, rather than
copying its conditions, keeps both reads aligned if that function is ever replaced. Recorded migrations are
immutable (CLAUDE.md, memory "Recorded migrations are immutable").

**Alternatives considered**: Raising `list_public_routes`'s 10-row cap and adding an offset. Rejected: that
changes an existing contract used by the Home rail, the Profile section and the planner sheet (FR-014), and
it still has no district filter or newest sort. A Postgres view over eligible routes: rejected because the
predicate depends on "today" in Seoul, which a view cannot take as a parameter without session state.

## R2. Paging: offset with a fixed tie-break and client de-duplication

**Decision**: `browse_public_routes(p_sort, p_district, p_limit, p_offset)` returns at most `p_limit`
(capped at 50, default 20) rows and a `has_more` flag, computed by reading `limit + 1` rows. Orders:
- `popular`: `copy_count_30d desc, listing_decided_at desc, id asc`. This is the exact order of
  `list_public_routes`.
- `newest`: `listing_decided_at desc, id asc`.

The app appends each page and drops any id it already holds (FR-008).

**Rationale**: The eligible set is small: tens to low hundreds of routes, each needing staff approval. The
popular rank depends on 30-day copy counts that change between requests, so keyset paging on that rank
would not be stable either. Offset paging plus de-duplication on the client never shows a route twice.
Against an unchanging list it never skips a route either (SC-003). The fixed `id` tie-break makes the order
deterministic (US2 scenario 3).

**Alternatives considered**: Keyset on `(copy_count_30d, listing_decided_at, id)`: the cursor goes stale as
soon as a copy is counted, and it adds an encoded cursor to the contract. Fetching every eligible route at
once and paging on the device: simplest, but the payload grows without bound. It would also send every
route's covers and authors to readers who look at only one screen.

## R3. Covers come from the server row

**Decision**: Each browse row carries `cover_urls`. These are up to three non-blank `cover_image_url`
values from `exhibition_catalog_v2` for the route's stops, in stop order, duplicates removed. Stops without
a cover are skipped (spec assumption).

**Rationale**: An eligible route has every stop in the catalogue by definition (R1), so its covers can be
read in the same query. The Home rail gets covers by calling `get_listed_route` once per route, plus the
catalogue (`HomeViewModel.publicRoutes`). That costs 20 extra calls for every browse page.

**Alternatives considered**: Returning `stop_exhibition_ids` and resolving covers against the catalogue held
in `ExhibitionListState`, like `homeRoutes()`. This works too, but it ties the browse ViewModel to the full
catalogue flow, and covers would appear only after the catalogue has loaded.

## R4. District chips

**Decision**: `list_public_route_districts()` returns one entry per distinct `personal_route_stops.region_ko`
that appears on any eligible route. Each entry has `district_ko`, `district_en` (`min(region_en)` for that
Korean name) and `route_count`. Entries are ordered by `route_count desc, district_ko asc`. Blank regions are
excluded. The filter compares `region_ko` (FR-006, FR-007, US3 scenario 4). Both reads take today's Seoul
date the same way `list_public_routes` does.

**Rationale**: Korean is the canonical value, already stored on every stop snapshot. English is display text
only. Ordering by route count puts the useful districts first, and the name tie-break keeps the order stable.

**Alternatives considered**: Deriving chips only from the loaded pages on the device: the chip list would
grow as the reader scrolls, and districts on unloaded pages would be missed. Using the catalogue's district
vocabulary: it would show chips that match no route.

## R5. Navigation and returning from the preview

**Decision**: Add `AppDestination.PublicRouteBrowse(source)` and `showPublicRouteBrowse(source)`. Rows open
the preview through the existing `publicRoutesViewModel.openPreview(summary)` plus
`navigation.showPublicRoute(id)`. `showPublicRoute` already records the previous destination, so back from
the preview returns to the browse screen. The new `PublicRouteBrowseViewModel` is created at the App level
(like `publicRoutesViewModel`) and keeps the query, the loaded rows and a saved scroll anchor
(`firstVisibleItemIndex`, offset). The screen restores the anchor when it re-enters composition (FR-011).

**Rationale**: Navigation is state-driven in `App.kt` with no back stack (CLAUDE.md), so a screen's
`rememberSaveable` state is lost when another destination replaces it. A ViewModel that lives as long as the
App composition survives the trip to the preview.

**Alternatives considered**: Making the preview an overlay on top of the browse screen: that would change
the preview's back handling, which the Home rail and Profile entry points share. Folding paging into
`PublicRoutesViewModel`: that class already owns the top-ten list, the preview, copy and report (~450
lines). Paging is a separate responsibility (CLAUDE.md "one primary responsibility").

## R6. Profile entry point when nothing is listed

**Decision**: `PublicRoutesSection` gains an optional `onSeeAll: (() -> Unit)?`. When it is non-null, the
heading row shows "전체 보기" in every state. In the `Hidden` (empty) state the section then renders its
heading, the see-all action and one `labelSmall` line, "아직 공유된 동선이 없어요" / "NO SHARED ROUTES YET",
instead of nothing. The Profile tab passes `onSeeAll`. The route planner sheet passes nothing and is
unchanged (spec assumption). On the Home rail, `RoutesRail` passes the existing `SectionHeader(action, onAction)`
parameters, so no new component is needed.

**Rationale**: FR-003 and SC-006 need a permanent entry point. Making it optional keeps the planner sheet's
spec-089 behaviour intact (FR-014).

## R7. Sort and filter controls

**Decision**: Use the existing `GallrFilterChip` (`ui/tabs/list/ListScreenComponents.kt`), moved to
`ui/components` because a second screen now uses it. There are two rows: the sort chips (인기 / 최신,
exactly one selected) and a horizontally scrolling district row starting with 전체. Selected chips use the
`activeIndicator` fill that DESIGN.md already allows for "selected filter chips". Everything else is
monochrome with 0dp corners.

**Rationale**: This reuses an approved, accessible component and colour role. Moving a shared component to
`ui/components` follows the module map.

## R8. Analytics for FR-017

**Decision**: This gets its own optional phase (P3), because it touches the aggregate analytics pipeline. It
consists of:
- A new `AnalyticsSurface.ROUTE_BROWSE` (`route_browse`).
- `public_routes_viewed` may carry an optional `surface` drawn from `featured` (Home), `my_gallr`
  (Profile), `map` (planner sheet) or `route_browse`.
- A new event, `public_route_copied`, with a required `surface` and nothing else.

Delivering it needs a new migration that replaces `service_record_mobile_analytics` and the event-name
constraint, plus matching validation in `supabase/functions/mobile-analytics/handler.ts`, with Deno and
pgTAP coverage. As with spec 089, no route id, author or reader is ever recorded.

**Rationale**: SC-005 compares how often readers copy, by entry point. `route_saves` counts copies but not
where they came from, and changing `save_public_route`'s signature for analytics would mix two concerns. The
pipeline stays inactive until `MOBILE_ANALYTICS_ENABLED` is turned on, so SC-005 becomes measurable only
after that separate activation.

**Alternatives considered**: Adding a `source` column to `route_saves`: it changes a transactional table and
RPC for a counting need. Dropping FR-017: possible if the owner prefers to ship sooner. The phase is
self-contained and can be cut without touching US1–US4.

## R9. Delivery order and deployment

**Decision**: The database migration ships first. It is additive: new functions only, and grants for `anon`
and `authenticated`. The app release follows. Older app builds never call the new functions. The analytics
phase deploys its migration before the Edge Function, as spec 089 did (`20261010170000`). The function
deploy is a separately authorized operation (CLAUDE.md, Supabase gotchas).

**Rationale**: Additive SQL lets the database ship ahead of the app with no compatibility window to manage.
