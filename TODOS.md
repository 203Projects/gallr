# TODOS

Last updated: 2026-10-10. Revalidate external service and release status before
acting on older operational entries.

This file is the authoritative open-work list. Unchecked boxes in completed or
superseded specifications are historical execution records unless an item below
links back to them explicitly.

## Rollout Queue — Integrated, Not Yet Activated

### Local discovery, aggregate analytics, and explainable recommendations

Four completed specifications are now integrated into `develop`:

- `071-local-discovery-intelligence`: deterministic, private on-device
  recommendations and neighborhood route planning.
- `072-mobile-product-analytics`: aggregate-only mobile analytics with a bounded
  offline queue, Supabase ingestion, disclosure, and user/release gates.
- `073-local-discovery-experience`: the mobile For You and neighborhood-route
  presentation.
- `074-explainable-art-recommendations`: reviewed artist/art metadata and
  bilingual evidence for personalized recommendations.

PRs #246–#253 and #259 landed bottom-up on 2026-09-03 with green automated
checks. Repository integration does not authorize applying the metadata or
analytics migrations, deploying `mobile-analytics`, enabling analytics
collection, changing hosted configuration, or releasing new mobile builds.

Roll out through staging in contract order: apply the migrations; deploy the
disabled function with an environment-specific component secret; verify Admin,
Gallery, canonical-v2, legacy fallback, mobile analytics-disabled behavior, and
recommendation evidence; then make a separate production enablement decision.
Keep both mobile analytics release flags and `MOBILE_ANALYTICS_ENABLED` false
until disclosure, user preference, and staged aggregate evidence are approved.

## P1 — Post-Launch

### Push Notifications
Weekly "N new exhibitions near you" push via FCM (Android) + APNs (iOS). Primary retention mechanism. Needs a reviewed server-side scheduler and delivery worker; do not revive the retired Apps Script pipeline. Depends on basic analytics being in place.
- Effort: M (human) → S (CC: ~1 day)
- Context: Design doc identifies retention as key initiative. Without a trigger, users forget to open the app.
- Gate: Stage the aggregate analytics rollout above before designing the
  notification scheduler so delivery can be measured without introducing a
  second identity or event pipeline.

### Close My Gallr physical-device validation

Automated, simulator, disposable Auth/Data API, and hosted-branch isolation
evidence is complete. The remaining release evidence is a signed-in physical-
device account-isolation pass plus hands-on VoiceOver gesture and spoken-pacing
validation.

- Source: `specs/060-my-gallr-guest-archive/tasks.md` T022 and
  `specs/064-my-gallr-account-sync/tasks.md` T008.
- Do not mark these complete from simulator or accessibility-tree inspection
  alone; the remaining checks explicitly require a physical device and human
  listening/interaction.

### Auth state must outlive the composition that the route ViewModels watch

**What:** Build the app's `AuthState` `StateFlow` where the retained ViewModels live (the activity/host, or each ViewModel from `AuthRepository`), not in `App()` with `remember`.

**Why:** `App.kt` creates `authStateFlow` with `remember`, while the composer, 내 동선, 추천 동선 and the older sync ViewModels are retained in the activity's ViewModelStore. After any configuration change the manifest does not handle (uiMode such as scheduled dark mode, font scale, locale, multi-window), a new flow is created and the retained ViewModels keep watching the old one, so sign-in or sign-out is never seen until the process restarts.

**Context:** Found by the ship red-team review of 089 (2026-10-10). The pattern predates routes (social layer commit `d7aead0b`) and affects every ViewModel that takes `authState`; `RetainedResource` made the retention explicit. Start in `App.kt` (`authStateFlow`) and `MainActivity.kt`; add a test that recreates the owner and then signs in.

**Effort:** M (CC: ~1 hour)
**Priority:** P1
**Depends on:** None

### Opening-hours parser must not mark misread formats as COMPLETE

**What:** Split hours segments on commas that separate day/time groups, mark a segment PARTIAL when it holds more than one time range or day tokens from words such as 평일/주말/휴관일/그 외, and match Korean day characters only as standalone tokens; add these strings to `specs/089-personal-routes/contracts/opening-hours-parity.json` so the Kotlin parser and `web/api/_lib/opening-hours.js` stay in step.

**Why:** "평일 10:00-18:00" parses as Sunday only with completeness COMPLETE, so Mon–Sat count as known closed; "Tue-Sat 10:00-12:00, 13:00-18:00" closes every day at noon; "화-일 10:00-18:00 휴관일: 월요일" opens Monday. The planner then drops venues as closed, the composer and preview show ClosedOnPlannedDay and the shared route page prints wrong hours.

**Context:** Found by the ship red-team review of 089 (2026-10-10) by running the JS port in `web/api/_lib/opening-hours.js`; `shared/src/commonMain/kotlin/com/gallr/shared/hours/OpeningHoursParser.kt` is the source (spec 088). None of these formats are in the 90-case parity fixture.

**Effort:** M (CC: ~1.5 hours)
**Priority:** P1
**Depends on:** None

### Compose UI semantics tests for the route screens

**What:** Decide on and add a Compose UI test harness (Robolectric + compose-ui-test on the Android host, or the multiplatform ui-test), then write the spec 089 T126 semantics tests: one merged Button node per 내 동선 and 추천 동선 row, menu item labels, consent dialog focus, 200% font scale.

**Why:** The repo has no UI test harness, so the accessibility findings of the routes design review (merged rows, custom actions, focus return after the consent dialog) were checked only by reading code and the emulator tree.

**Context:** Deferred from plan: `specs/089-personal-routes/tasks.md` T126 (owner decision on 2026-10-10 to keep it open rather than add the harness inside the routes PR). Start in `gradle/libs.versions.toml` and `composeApp/src/androidHostTest`.

**Effort:** M (CC: ~2 hours)
**Priority:** P1
**Depends on:** owner decision on the harness

### Admin route review: show where a queue selection went and subordinate Revoke

**What:** Make a queue selection visible (scroll or focus to the preview, or a selected-row indicator; or show details beside the queue), and give Revoke less prominence than Approve while keeping its confirmation.

**Why:** With a long queue, opening a route appears to do nothing because the preview renders below the whole list, and the irreversible Revoke shares Approve's black-button weight in the same action group.

**Context:** Codex design voice during the ship review of 089 (2026-10-10), `admin/src/components/RouteModerationWorkspace.tsx`. Opening a reported route before Dismiss/Unlist was done in the routes PR (an Open action per report row).

**Effort:** S (CC: ~30 min)
**Priority:** P2
**Depends on:** None

## P2 — Routes follow-ups

### Debounce the route name draft writes

**What:** Keep the typed name in field state and persist it to the draft DataStore debounced (300–500 ms) and on focus loss, save, share and back.

**Why:** Every keystroke in the composer rewrites the whole app Preferences DataStore, wakes every `dataStore.data` collector and re-runs the route evaluation, which is visible as jank on long names.

**Context:** Ship performance review of 089 (2026-10-10): `RouteComposerScreen.kt` `onValueChange` → `PersonalRouteComposerViewModel.rename` → `DataStorePersonalRouteDraftRepository.rename`.

**Effort:** S (CC: ~30 min)
**Priority:** P2
**Depends on:** None

### Load the route lists lazily and once per save

**What:** Load 추천 동선 on the first route-sheet showing instead of in the ViewModel's `init`; let the composer take its listing row from 내 동선's loaded list so one save causes one `list_my_personal_routes` read instead of three; ignore the `isSaved` true→false transition.

**Why:** Every cold start runs the heaviest public query although 추천 동선 is only visible inside the Map route sheet, and each save triggers three list reads.

**Context:** Ship performance review of 089 (2026-10-10): `App.kt` ViewModel creation, `PublicRoutesViewModel.init`, `PersonalRouteComposerViewModel.refreshListing` and the `ListingReadKey` collector, `MyRoutesViewModel`'s saved-revision collector.

**Effort:** M (CC: ~1 hour)
**Priority:** P2
**Depends on:** None

### Keep route evaluation out of the App root and memoise the catalogue map

**What:** Expose only the booleans the App root needs (`shareReady`, replace-confirm, route count, one-off requests) as distinct flows or move their effects into a child composable; derive the `exhibitionsById` map once per catalogue and memoise `parseOpeningHours` by hours string; `remember` the picker's filter and map and the MapLibre GeoJSON sources; keep the route map outside the lazy list so it is not recreated on scroll-back.

**Why:** Every composer state emission re-executes the ~1,100-line App root content, rebuilds a map of the whole catalogue and re-parses each stop's hours; the native map is torn down and rebuilt when the list scrolls past it.

**Context:** Ship performance review of 089 (2026-10-10): `App.kt` collectAsState at the root, `PersonalRouteComposerViewModel`/`PublicRoutesViewModel` `associateBy`, `RouteEvaluator.evaluate`, `RoutePickerSheet.kt`, `RouteComposerScreen.kt` map item, `RouteMap.kt`.

**Effort:** M (CC: ~2 hours)
**Priority:** P2
**Depends on:** None

### Measure list_public_routes before 추천 동선 is promoted

**What:** Run `EXPLAIN (ANALYZE, BUFFERS)` on `list_public_routes` with a few thousand seeded routes; if needed, compute eligibility set-based in the query (join stops and the catalogue once, GROUP BY route) instead of calling `route_first_shared_day` per approved route, and aggregate the 30-day copies with one grouped join.

**Why:** The function ranks every approved route on every call before applying LIMIT 10, is granted to anon and runs at every cold start and sheet opening.

**Context:** Ship performance and data-migration reviews of 089 (2026-10-10), `supabase/migrations/20261010150000_public_route_ranking.sql`. The partial indexes on `listing_state` were added in the routes PR; `scripts/sample-routes` can seed data.

**Effort:** S (CC: ~45 min)
**Priority:** P2
**Depends on:** the routes migrations applied to staging

### Close the route test gaps named by the ship review

**What:** Make `FakePersonalRouteDraftRepository` follow `DataStorePersonalRouteDraftRepository`'s contract (replace/clear/detach/undo/move semantics) or run the ViewModel suites on the real repository over an in-memory DataStore; add cases for the own-route copy guard, the stale `resumeCopy` branches (expired, edited draft, other route, no preview), the listing in-flight guard and failed-withdraw retry, the web handler's 405/400/event-failure paths, `route-data.js` catalogue and author reads, `loadMine` with an empty result, Admin approving again after a stale answer and the Unlist button on the preview, and make `RetainedResourceTest` exercise `MainActivity`'s wiring.

**Why:** These paths are reachable by users but have no test, and the draft fake's drift means the route ViewModel suites verify behaviour production does not have.

**Context:** Ship testing specialist on 089 (2026-10-10); each item names its file. Several listing and report branches were covered in the routes PR itself.

**Effort:** M (CC: ~2 hours)
**Priority:** P2
**Depends on:** None

### Purge the route page cache when a route is revoked

**What:** Purge the Vercel cache for `/route/{id}` (or shorten `s-maxage`) when staff revoke a route, and send the page language in the share link (`?lang=`) so the CDN key carries it.

**Why:** A revoked route stays publicly served for up to about two minutes after the takedown, and the first reader's language is served to the other language's readers for the cache window (`Vary: Accept-Language` added in the routes PR mitigates the second).

**Context:** Ship security and api-contract reviews of 089 (2026-10-10), `web/api/_lib/route-handler.js` `CACHE_PAGE`, `RouteShareOrchestrator` link format.

**Effort:** S (CC: ~30 min)
**Priority:** P2
**Depends on:** None

## P3 — Technical Debt

### Full Analytics Dashboard
Turn the aggregate counters from `072-mobile-product-analytics` into a useful
operator dashboard for discovery, recommendation, route, and intent rates.

- Effort: M (CC: ~1 day after the analytics stack is integrated and staged).
- Start with the planned Supabase SQL views/queries. Evaluate an external
  dashboard only after the first-party aggregates and privacy boundaries are
  proven insufficient.
- Do not report unique users, sessions, cross-visit funnels, or retention: the
  aggregate-only event model intentionally has no stable person/device identity.

### Route-shaped link preview image for shared personal routes

**What:** Render a route-shaped Open Graph PNG (numbered stops joined by a line, plus the route name) for each shared route page, cached by route id and revision.

**Why:** Personal routes v1 previews use the first still-published stop's cover, so a shared route reads like a single exhibition in KakaoTalk and iMessage.

**Context:** Deferred by the personal routes engineering review (D20, 2026-10-08; design doc `docs/designs/2026-10-07-personal-routes-design.md`, Open Question 2). Link scrapers do not render SVG. Start from the route page's inline SVG in its Vercel Function. Build only if the recipient-open rate (shared routes opened within 7 days) is low after launch.

**Effort:** M (CC: ~1 hour)
**Priority:** P3
**Depends on:** Personal routes v1 shipped and its recipient-open numbers.

### Abuse limits on personal route writes and page counts

**What:** Add a per-account cap on saved personal routes (for example 100) and a per-route daily ceiling on route page-count increments.

**Why:** v1 has no limit on routes per account, and the page-count database function is callable by anyone holding the publishable key, so spam routes or inflated opens could skew the recipient-open metric.

**Context:** Deferred by the personal routes engineering review (D21, 2026-10-08; design doc `docs/designs/2026-10-07-personal-routes-design.md`, decisions D5 and D14). Page counts are labelled unauthenticated traffic. Start in `save_personal_route` and `record_route_page_event`, with pgTAP cases for each limit.

**Effort:** S (CC: ~20 min)
**Priority:** P3
**Depends on:** v1 traffic showing spam routes or skewed page counts.

### Guard public route ranking against throwaway accounts

**What:** Count or flag saves from very new accounts in the 090 public route ranking.

**Why:** Saves rank the public list and any signed-in account counts once, so one person with throwaway accounts could promote a route.

**Context:** Raised by the public routes engineering review (Section 1 #4, D21, 2026-10-08; design doc `docs/designs/2026-10-08-public-routes-design.md` §Ranking). Pre-approval and launch volume make it unlikely early. Start in `list_public_routes_impl` and `route_saves`, with pgTAP cases.

**Effort:** S (CC: ~45 min)
**Priority:** P3
**Depends on:** 090 launched and saves concentrated on a few new accounts.

### Full public route list with keyset paging

**What:** Add a '모두 보기' screen listing every eligible public route with stable keyset paging over the ranking.

**Why:** 090 shows only the top 10 inline, so routes ranked 11 and lower are unreachable in the app.

**Context:** Deferred by the public routes engineering review (D1, D22, 2026-10-08). Start from `list_public_routes(p_limit)`. Offset paging skips or repeats rows as saves change the order (office-hours reviewer R2-8), so page by (save count, approval time, id).

**Effort:** M (CC: ~1-2 hours)
**Priority:** P3
**Depends on:** more than about 30 eligible listed routes.

### Weekday-hours check for listed routes

**What:** Require at least one shared date on which every stop's parsed opening hours are open, treating unknown hours as open.

**Why:** The 090 shared date window (D15) can still list a route whose only common days are a gallery's closing days.

**Context:** Deferred by the public routes engineering review (D23, 2026-10-08). The 089 opening-hours parser and its JS port (`web/api/_lib/opening-hours.js`) are the starting point; hours text is often partial.

**Effort:** M (CC: ~2 hours)
**Priority:** P4
**Depends on:** listed routes found unwalkable because of weekday closures.

### Cap route screen width on tablets

**What:** One maximum content width (for example 600dp, centred) for the Map route sheet, the 089 composer and the 090 public route preview.

**Why:** No route screen has a maximum width today, so on tablets stop rows and bottom-bar buttons run edge to edge and text lines get very long.

**Pros:** One DESIGN.md token fixes all three screens at once.

**Cons:** Needs a DESIGN.md decision on the value and a tablet visual pass on Android and iPad.

**Context:** Found by the public routes design review (D28, D30, 2026-10-08). 089 and 090 were designed phone-first and DESIGN.md has no tablet layout rules; 090 keeps the existing full-width behaviour.

**Effort:** S (CC: ~30 minutes plus a device pass)
**Priority:** P4
**Depends on:** best after 090 lands so the preview is included.

### Simplification pass on the routes code

**What:** `MyRoutesViewModel.openOwn` should take a route id instead of fabricating a placeholder summary; merge `PersonalRouteApiClient.publicRpc`/`rpc` into one call with a `requireAuth` flag and reuse `idBody`; reuse `web/scripts/lib/site-date.js` `seoulDateIso` in `route-verdict.js`; route Admin `revoke`/`decide` through `callRoute`; drop the unused `listPublic(limit)` parameter; share `clockLabel`, `AppLanguage.pick` and the English month table between the route presentation files; make the composer's `routeRepository` non-null; consider `node:util` `parseArgs` in `scripts/sample-routes`.

**Why:** About 60 lines of duplicated or speculative structure flagged as advisory by the ship simplification specialist; none is a defect.

**Context:** Ship review of 089 (2026-10-10); each item names its file and line in the review record.

**Effort:** S (CC: ~45 min)
**Priority:** P3
**Depends on:** None

### Route page: keep district labels inside the diagram, and sentence-case the composer notes

**What:** Anchor district labels inward near the right edge of the route SVG (`web/api/_lib/route-page.js` `renderDrawing`), and use one casing for the composer's message line (sentence case for explanatory notes such as the copied note and the public note, per DESIGN.md "uppercase labels, sentence-case explanations").

**Why:** Long district names clip for eastern stops; the composer mixes an uppercase sentence with a sentence-case warning in the same slot.

**Context:** Codex design voice and the design specialist during the ship review of 089 (2026-10-10).

**Effort:** S (CC: ~20 min)
**Priority:** P3
**Depends on:** None

## Deferred Product Inputs — Not Work-Ready

- Reconsider the square `G` mark only in a deliberate product-wide brand
  project, not as an isolated flow tweak (`design-qa.md`).
- Add gallery logos only after the canonical catalogue owns verified logo
  assets; do not synthesize monograms (`design-qa.md`).
- Routine dependency updates remain owned by Dependabot PRs and are not product
  roadmap items.
