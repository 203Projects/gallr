# Research: Personal routes

All technical unknowns were resolved before this plan, in the office-hours design and three reviews recorded in [`docs/designs/2026-10-07-personal-routes-design.md`](../../docs/designs/2026-10-07-personal-routes-design.md). This file consolidates them as decisions. Identifiers: E-D (first engineering review), DR-D (design review), RR and RO (engineering re-review and its outside voice). No NEEDS CLARIFICATION remains.

## R1. Where the public route page is served

- **Decision**: A Vercel Function inside `web/` renders `/route/{id}` server-side, reached by an internal rewrite in `web/vercel.json`. It reads with the publishable key under row-level security (E-D2).
- **Rationale**: Supabase documents that an Edge Function GET returning `text/html` is rewritten to `text/plain` ([development tips](https://supabase.com/docs/guides/functions/development-tips.md), [HTTP methods](https://supabase.com/docs/guides/functions/http-methods)), so a function-served page would show raw markup and no link preview. gallrmap.com already deploys on Vercel; the web project holds only the publishable key (`web/tests/supabase-public-api-key.test.js`).
- **Alternatives considered**: Supabase Edge Function page (broken by the rewrite); Edge Function JSON plus client rendering (scrapers get no Open Graph tags); a probe first (declined, documented limitation).

## R2. Where page events are received and written

- **Decision**: The same Vercel Function accepts the page's own POSTs (same origin, no CORS) and calls a definer-rights database function `record_route_page_event(route_id, event, shared)` with the publishable key (E-D1, E-D5). Events are `route_page_opened` and `route_page_started`.
- **Rationale**: No service key in the web project; one deployable; the function accepts two names and published, unrevoked routes only. Counts are labelled unauthenticated traffic (first-review outside finding O7, confirmed under E-D5; E-D14).
- **Alternatives considered**: A separate `record-route-event` Edge Function (needs an Origin allow-list the fallback breaks); a service key in Vercel (larger blast radius).

## R3. Timing in the author's order

- **Decision**: Extract the planner's private schedule (`NeighborhoodRoutePlanner.kt`, `private class RouteSchedule`) into one internal timeline in `com.gallr.shared.map` that reports fit per stop. The planner keeps rejecting misfits; `RouteEvaluator` maps them to verdicts and adds run-date checks with a `NotYetOpen` verdict (E-D3, E-D15).
- **Rationale**: One arithmetic for planner and composer, so a route copied from the planner shows the same times; the planner's existing suite guards the refactor.
- **Alternatives considered**: Duplicate arithmetic in the evaluator (drift).

## R4. Stop snapshots and labels

- **Decision**: The app sends ordered exhibition ids under an app-chosen route uuid; `save_personal_route` copies names, venue names, coordinates, `region_ko`, `region_en` and `city_ko` from `exhibition_catalog_v2`. A no-longer-published id is accepted only if the route already holds it; otherwise a distinct error lists blocked ids. Ids without coordinates are rejected (E-D7, E-D17, E-D18, RR2, RO5).
- **Rationale**: Prevents forged public content; district is reliable data (`Exhibition.regionKo` is the 구; no 동 field exists).
- **Alternatives considered**: Client-supplied snapshot (forgeable); parsing 동 from road-name addresses (unreliable).

## R5. Save semantics

- **Decision**: `save_personal_route` locks the route row (`select … for update`), last write wins; it returns the stored route (id, revision `updated_at`, name, ordered snapshots). Links carry `?s=share&v={revision}` as a cache key (E-D12, E-D16).
- **Rationale**: Avoids the delete-then-insert race without a conflict-retry path (the August 2026 Admin revision-conflict storm argues against retries).
- **Alternatives considered**: Optimistic revision rejection (needs conflict UI and must never auto-retry).

## R6. Draft model on the device

- **Decision**: One DataStore draft exposed as a `Flow`, with a revision counter bumped on every change, the owner account id, the remote route id, and an optional pending action (kind, created time, draft id, revision). All writers use atomic operations that enforce the 10-stop and duplicate rules, including a guarded undo. Save responses apply only to the draft id and revision they were sent for (E-D8, RR1, RR3, RO2, RO3, RO4).
- **Rationale**: Survives OAuth round trips and process death, prevents overwrites between detail page, picker and composer, and never publishes unapproved content. Mirrors `MyGallrAccountSync.flush`, which keeps mutations added during a request.
- **Alternatives considered**: In-memory pending action; composer-held copy reloaded on resume.

## R7. Authenticated calls from the app

- **Decision**: A Ktor ApiClient with a bearer token from the session, behind `PersonalRouteRepository` (`Result<T>` boundary), following `MyGallrAccountApiClient` (`rest/v1/rpc/sync_my_gallr_archive`).
- **Rationale**: Existing authenticated RPC pattern; CLAUDE.md keeps ApiClients separate from repositories.
- **Alternatives considered**: supabase-kt Postgrest RPC (no existing use for writes in `shared`).

## R8. Drag and reorder

- **Decision**: `sh.calvin.reorderable` in `composeApp` commonMain for long-press drag, edge auto-scroll and `animateItem`; the ⋯ move menu and screen-reader announcements are custom (RR4, DR-D26).
- **Rationale**: The library supports Compose Multiplatform including iOS with built-in edge auto-scroll ([klibs.io](https://klibs.io/project/Calvin-LL/Reorderable)). Constitution Quality Standards require KMP compatibility for new dependencies; this one is KMP-native.
- **Alternatives considered**: Hand-written `detectDragGesturesAfterLongPress` plus custom auto-scroll.

## R9. Hours reading on the web

- **Decision**: A JavaScript port of `parseOpeningHours` in `web/`, held to a checked-in JSON parity file (21 golden cases, 68 catalogue hours strings, and the status-label table) asserted by Android host tests (`shared` for hours, `composeApp` for labels) and the web test (E-D10, re-review Section 2). The page computes today's verdict and a better day within seven days under the "all means all" rule (DR-D3, RR5, RO1).
- **Rationale**: Constitution VI keeps web artifacts independent of the shared module, so parity is enforced by a shared fixture instead of shared code.
- **Alternatives considered**: Hand-copied 21 cases (silent drift).

## R10. Analytics

- **Decision**: Recipient loop from `personal_routes.published_at` and `route_page_daily` via an aggregate SQL view (E-D14). Author events `route_draft_started`, `route_published`, `route_shared` join the 072 pipeline and read as unavailable until its activation (E-D6).
- **Rationale**: The deciding metric works on launch day without the inactive pipeline.

## R11. Caching and failures of the page

- **Decision**: Success `public, s-maxage=60, stale-while-revalidate=60`; 404 `s-maxage=60`; read failure or 3-second timeout returns a 503 page with `no-store`; event POSTs `no-store`. Route and stops are read in one embedded request (E-D13 for caching, E-D19 for the single read).
- **Rationale**: Burst protection for chat shares; takedowns visible within about two minutes; one consistent snapshot per page.

## R12. Location permission

- **Decision**: Extend `LocationPermissionState` (today only `isGranted` and `request`) to report granted, can-ask or denied-permanently, refreshed on resume; the composer requests location only on demand and hides the button when permanently denied (DR-D30, RR6, RO6).
- **Rationale**: The approved behaviour is not implementable with a Boolean.

## R13. Moderation

- **Decision**: Staff-only `revoke_personal_route` and a staff-only read `get_route_for_moderation`, both guarded by `content.staff_members`, used by a new look-up, preview and revoke page in the Admin workspace (E-D4, DR-D13).

## R14. Visual design

- **Decision**: Follow the design review: itinerary rows with hairlines, verdict-first summary, black standard buttons (no new orange), orange selection bar in the picker, `interactionFeedback` border on the held row, monochrome share card, "방문 순서" schematic on the page, Naver Map links, capped single column on wide screens, explicit web accessibility rules (DR-D3–DR-D31). DESIGN.md gains entries for the new patterns.

# Public routes (User Stories 7–11, combined 2026-10-08)

Settled in [`docs/designs/2026-10-08-public-routes-design.md`](../../docs/designs/2026-10-08-public-routes-design.md). Identifiers: R1–R17 (its engineering review ledger; D-numbers in that document are the same decisions as asked), DD1–DD22 and D24–D28 (its design review). Numbered P1–P12 here to avoid clashing with the 089 R-numbers above. No NEEDS CLARIFICATION remains; SC-012 thresholds were set by the owner (50% exposure, 3% copy rate).

## P1. Reading a shared route without enumeration

- **Decision**: Readers fetch one route by id through `get_published_route(p_id)`; anon has no table privilege and no policy lets another account read a published route (R1). Implemented as tasks.md T075.
- **Rationale**: 089 as first written let anyone with the publishable key list every published route and its owner, which contradicts link-only sharing and the consent premise of listing.
- **Alternatives considered**: document the exposure (breaks the premise); fix later (ships the hole).

## P2. Listing state on the route row

- **Decision**: `personal_routes` gains `listing_state` (`unlisted`, `requested`, `approved`, `declined`, `removed`) with request/decision timestamps, decider and decline reason/note. Visibility is derived, not stored (design "Listing lifecycle"). No author "unpublish" transition (R2).
- **Rationale**: one row per route keeps the owner functions' locking (089 E-D12) and lets the edit reset happen inside `save_personal_route_impl`'s transaction (R10).
- **Alternatives considered**: a separate listings table (second lock, harder reset); storing visibility (goes stale when a stop ends).

## P3. Ranking and eligibility in the database only

- **Decision**: `list_public_routes(p_limit)` (anon-callable) wraps `content_private.list_public_routes_impl(p_limit, p_today)`; it returns at most `p_limit` (10) eligible routes ranked by copies of the current approved version in the last 30 days, newer approval first on ties, with the 에디터 label. The app shows the received order; Kotlin holds row labels only (R3, R14).
- **Rationale**: one source of truth; `p_today` makes the Seoul-date rules testable in pgTAP.
- **Alternatives considered**: a shared Kotlin ranking with a parity test (duplicated rule).

## P4. Eligibility rules

- **Decision**: eligible = approved, published, unrevoked, every stop's exhibition present in `exhibition_catalog_v2` (R4) and running or upcoming in Seoul, and `max(today, latest opening_date) <= earliest closing_date` (R12). The first shared day is returned so rows and the preview can show "{M}월 {D}일부터" / "기준".
- **Rationale**: a reader must be able to walk every stop on some day; a vanished exhibition must not be recommended.
- **Alternatives considered**: list with ended stops flagged (misleads readers).

## P5. Editor status

- **Decision**: `request_route_listing` auto-approves only when the caller has an active `content.editor_memberships` row at request time; the label is computed live from active membership (R5).
- **Rationale**: staff authority and editor status come from server-owned membership (docs/account-identity-and-access.md), never from the client.
- **Alternatives considered**: snapshot the label at approval (a former editor keeps a false badge).

## P6. Staff decisions bound to a revision

- **Decision**: `decide_route_listing(p_id, p_decision, p_reason, p_note, p_expected_revision)` raises `route_listing_stale` when `updated_at` differs; Admin reloads with "내용이 바뀌었어요 · 다시 확인해 주세요" (R11).
- **Rationale**: an author edit during review must not inherit an approval of earlier content.
- **Alternatives considered**: last write wins (approves unseen content).

## P7. Copies counted per approved version

- **Decision**: `route_saves(route_id, account_id, approved_at, created_at)` unique on `(route_id, account_id, approved_at)`; `approved_at` is the route's `listing_decided_at` when copied. `save_public_route(p_id)` inserts (idempotent), records nothing when the caller owns the route, and raises `route_not_listed` when the route is not eligible (R9, R14).
- **Rationale**: a re-approved route starts a fresh count, so an edit cannot inherit a rank earned by different content.
- **Alternatives considered**: count per route forever (rank survives arbitrary edits).

## P8. Copy into the draft only if it is still the confirmed draft

- **Decision**: `PersonalRouteDraftRepository.copyIntoDraft(name, stops, expectedDraftId, expectedRevision)` applies in one DataStore update only when the draft id and revision still match; otherwise returns a mismatch the preview reports as "초안이 바뀌었어요 · 다시 시도". The copy gets a new route id and no owner; there is no `source_route_id` (R13, R7). Order: replace confirm → `save_public_route` → `copyIntoDraft` (R9).
- **Rationale**: the count is recorded before the draft changes, so an offline failure never changes the draft and a retry never double-counts.
- **Alternatives considered**: seed first, count later (uncounted copies on failure); provenance column (unneeded; `route_saves` records copies).

## P9. Reports

- **Decision**: `route_reports(route_id, account_id, reason, created_at, resolved_at, resolution)` with a partial unique index on `(route_id, account_id)` where unresolved; `report_route` refuses the owner; reports never hide a route; `resolve_route_reports` (staff) dismisses or upholds (upholding unlists to `removed`) (R16, DD10, DD11).
- **Rationale**: avoids brigading through auto-hide; staff stay the decision point.
- **Alternatives considered**: hide after three reports (cut in the eng review).

## P10. Where it lives in the app

- **Decision**: a 추천 동선 section in the Map tab route sheet below 내 동선 (capped at three, "모두 보기"), top three rows expanding in place to the ten fetched; a read-only preview screen reusing `RouteMap`, the composer's stop rows and `RouteEvaluator.evaluate` with `now` at the start of the first shared day when that is after today (DD1–DD3, DD21). Listing controls live on the 내 동선 row menu with a consent dialog (R6, DD17). New state is owned by a `PublicRoutesViewModel`; the composer's warning comes from `PersonalRouteComposerViewModel` state (DD15).
- **Rationale**: owner's placement choice; preview reuse keeps verdict wording identical to the composer.
- **Alternatives considered**: a separate list screen with paging (cut, R16 scope D1); a fifth tab.

## P11. Sign-in resume for copy

- **Decision**: a COPY pending action alongside 089's SAVE/SHARE: a signed-out copy stores the route id, returns to the same preview after sign-in and re-runs the copy flow from the confirm step; cancelling sign-in changes nothing (DD18).
- **Rationale**: reuses `AppNavigationState.showSignIn`/`returnFromSignIn` and the pending-action pattern.
- **Alternatives considered**: return without resuming (readers lose the copy).

## P12. Measurement and platform

- **Decision**: a `public_routes_viewed` mobile analytics event (dimension `rows_shown` 1–10, no ids) added to the event constraint and the `mobile-analytics` handler (R15). Listing, copying and reporting exist only in the app; the web route page stays read-only and shows no copy count or list (constitution VII, FR-062).
- **Rationale**: exposure and interest need section views; route-sheet opens already arrive as `surface_viewed` with surface `map` and entry point `route` (`App.kt`, `AppDestination.RoutePlanner`), so only one event is new. The platform rule is the owner's 2026-10-08 decision.
- **Alternatives considered**: average-saves trigger (replaced by R15).
