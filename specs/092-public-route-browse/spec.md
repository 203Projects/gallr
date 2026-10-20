# Feature Specification: Public route browse screen (동선 둘러보기)

**Feature Branch**: `092-public-route-browse`
**Created**: 2026-10-14
**Status**: Draft
**Input**: User description: "Public route browse screen (동선 둘러보기). Today other people's listed routes (추천 동선) appear only as at most 10 popularity-ranked entries in three places (Home 지금 주목받는 동선 rail, Profile tab 추천 동선 section, route planner sheet), each hidden when empty, with no full list, filtering, or paging. Add a dedicated mobile browse screen reached from a 전체 보기 action on the Home routes rail header and on the Profile 추천 동선 section (which always shows its header so the feature is discoverable even with zero listed routes); sort 인기/최신, district filter, later-start label, paging 20 at a time, loading/error/empty states; tapping a row opens the existing public route preview; an additive server read with the same visibility rules as today's list. Out of scope: walking-route geometry and full-screen interactive map, map-based browsing, new bottom-nav tab, theme filters."

## Context

Spec 089 introduced public routes: an author lists a saved, published route; staff approve it in Admin; readers see it in 추천 동선, open a read-only preview, copy it into their own draft and report it. That release deliberately left out "a separate routes-feed screen" and showed only the ten most-copied routes. Since then the list has moved: the Map tab no longer opens the route sheet (owner decision, 2026-10-11), so routes are reached from the Home tab's 지금 주목받는 동선 rail (spec 091), the Profile tab's 추천 동선 section and the route planner sheet. Each of these hides itself when nothing is listed, and none offers more than ten routes.

The owner has now asked for a place where readers can explore and use routes made by other people. This feature adds that place: one dedicated, app-only screen listing every live listed route, with sorting, a district filter and paging, opened from the two places readers already meet routes. It supersedes 089's "no separate routes-feed screen" premise. All of 089's listing, review, visibility, preview, copy and report behaviour is unchanged and is reused as is.

Premises:

- Mobile first (constitution Principle VII): browsing is app-only. The public web keeps showing a single shared route at its link; no web list is added.
- Only routes that are live under 089's rules are shown: approved, published, not revoked, every stop still in the catalogue and not ended, and at least one day on which every stop is running.
- The existing ranking is the 인기 (popular) order: copies of the currently approved version in the last 30 days, then the most recently approved first.
- Reading needs no account, as in 089.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Browse every listed route (Priority: P1)

A visitor looking for ideas for a day out taps 전체 보기 next to 지금 주목받는 동선 on the Home tab (or next to 추천 동선 on the Profile tab). A full screen titled 동선 둘러보기 opens with every live listed route, most popular first. They scroll; more routes load as they reach the end. They tap one and land in the familiar route preview, where they can read its stops and copy it into 내 동선.

**Why this priority**: This is the feature: without a complete, reachable list, readers can see at most ten routes and often none.

**Independent Test**: With 45 eligible listed routes, open the screen from the Home rail, scroll to the end and confirm all 45 appear once each, in popularity order, and that tapping a row opens its preview with copy and report working as before.

**Acceptance Scenarios**:

1. **Given** the Home tab shows the 지금 주목받는 동선 rail, **When** the reader taps 전체 보기 in its header, **Then** the 동선 둘러보기 screen opens sorted by 인기.
2. **Given** the Profile tab, **When** the reader looks at 추천 동선, **Then** its header and a 전체 보기 action are shown whether or not any routes are listed, and tapping 전체 보기 opens the 동선 둘러보기 screen.
3. **Given** eligible routes, **When** the screen opens, **Then** each row shows the route name, its first and last stop districts, its number of stops, the author (or the 에디터 label for an active editor), the 30-day copy count when above zero, and the covers of up to its first three stops.
4. **Given** a route on which every stop is open together only from a later date, **When** its row is shown, **Then** the row adds that date in the existing format ("{M}월 {D}일부터" / "FROM {date}").
5. **Given** more than 20 eligible routes, **When** the reader scrolls near the end of the loaded rows, **Then** the next 20 load and append without duplicating or skipping any route, until every route is shown.
6. **Given** a row, **When** the reader taps it, **Then** the existing public route preview opens for that route, and copy, report and "내 동선에서 열기" for the reader's own route behave exactly as in spec 089.
7. **Given** the reader returns from a preview, **When** the browse screen reappears, **Then** it keeps its sort, filter, loaded rows and scroll position.
8. **Given** a visitor without an account, **When** they open the screen, **Then** they can browse it and open previews.

---

### User Story 2 - Sort by popular or newest (Priority: P2)

A regular visitor who has already seen the popular routes switches the screen to 최신 to see what was approved most recently.

**Why this priority**: Popular order keeps showing the same routes to returning readers; newest order gives new routes a chance to be seen. Valuable, but the list works without it.

**Independent Test**: With routes whose copy counts and approval times disagree, switch between 인기 and 최신 and confirm each order.

**Acceptance Scenarios**:

1. **Given** the screen, **When** it opens, **Then** the 인기 sort is selected and the order matches the existing ranking: 30-day copies of the approved version descending, then approval time descending.
2. **Given** the reader selects 최신, **When** the list reloads, **Then** routes are ordered by approval time, newest first, and paging restarts from the top.
3. **Given** two routes with identical sort values, **When** they are ordered, **Then** their relative order is stable between loads and across pages.

---

### User Story 3 - Filter by district (Priority: P2)

A visitor who will be in 성수동 on Saturday picks the 성수동 chip to see only routes that pass through it.

**Why this priority**: District is the most practical way to narrow routes for a day out. It needs the list to exist first.

**Independent Test**: With routes in several districts, choose one district and confirm that only routes with at least one stop in it are listed, and that the chip list shows only districts that some live route passes through.

**Acceptance Scenarios**:

1. **Given** the screen, **When** it loads, **Then** a row of district chips is shown, beginning with 전체 (all), listing each district that at least one currently shown route has a stop in, each district once.
2. **Given** the reader selects a district, **When** the list reloads, **Then** it shows only routes with at least one stop in that district, in the selected sort order, and paging restarts from the top.
3. **Given** a district is selected, **When** the reader selects 전체 or taps the selected chip again, **Then** the filter is cleared.
4. **Given** the language is English, **When** chips are shown, **Then** each district uses its English name, and the filter matches the same district as in Korean.

---

### User Story 4 - Clear states while loading, failing or empty (Priority: P3)

A visitor on a slow connection sees placeholders while the list loads; on a failed load they get a retry; when nothing is listed they are invited to make and publish a route of their own.

**Why this priority**: Required for a trustworthy screen, but it rides on the stories above.

**Independent Test**: Simulate slow, failing and empty responses for the first page and for a later page, and confirm each state.

**Acceptance Scenarios**:

1. **Given** the first page is loading, **When** the screen is shown, **Then** placeholder rows are shown in place of the list.
2. **Given** the first page fails, **When** the screen is shown, **Then** it shows the existing "추천 동선을 불러오지 못했어요" message with 다시 시도, which retries the same sort and filter.
3. **Given** a later page fails, **When** the reader reaches the end of the loaded rows, **Then** the loaded rows stay and a retry is offered at the end of the list.
4. **Given** no live route is listed at all, **When** the screen is shown, **Then** it shows an empty state explaining that no routes are shared yet, with an action that opens the route composer to make one.
5. **Given** a district filter leaves no routes, **When** the list is shown, **Then** it says no routes pass through that district and offers to clear the filter.

---

### Edge Cases

- A route stops being eligible (unlisted, revoked, a stop ends) between loading the list and opening it: the existing preview's unavailable handling applies, and the row disappears on the next reload.
- A route becomes eligible or its copy count changes while the reader is paging: pages may shift; the reader must never see the same route twice in one loaded list (a repeated route is dropped from the later page).
- A route passes through the same district at more than one stop: it is listed once under that district.
- A stop has no district recorded: the route still appears under 전체 and under its other stops' districts.
- The selected district no longer has any live route after a reload: the filter falls back to 전체 and the chip disappears.
- A route with a later start date and a route open today are both shown; the later-start label is the only difference.
- The reader changes app language while on the screen: rows and chips re-render in the new language without losing the filter.
- Very long route names or author names: truncated to two lines, as in the existing 추천 동선 rows.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The app MUST provide a 동선 둘러보기 (EN: BROWSE ROUTES) screen listing every route that is eligible under spec 089's public-listing rules (approved, published, not revoked, every stop listed in the catalogue and not ended, a shared running day exists).
- **FR-002**: The Home tab's 지금 주목받는 동선 rail header MUST offer a 전체 보기 (EN: SEE ALL) action that opens the screen.
- **FR-003**: The Profile tab's 추천 동선 section MUST always show its header with a 전체 보기 action that opens the screen, including when no route is listed; the section's rows and loading/error behaviour otherwise stay as in spec 089.
- **FR-004**: Each row MUST show the route name, first and last stop districts, stop count, author display name or 에디터 label, the 30-day copy count when above zero, the later-start date when the first shared running day is after today, and covers of up to the first three stops when they have covers.
- **FR-005**: The screen MUST offer two sorts, 인기 (default; 30-day copies of the approved version descending, then approval time descending) and 최신 (approval time descending), each with a deterministic tie-break so order is stable across loads and pages.
- **FR-006**: The screen MUST offer district filter chips: 전체 plus every district through which at least one eligible route passes, each listed once, in a stable order, shown in the reader's language.
- **FR-007**: Selecting a district MUST limit the list to eligible routes with at least one stop in that district.
- **FR-008**: The list MUST load 20 routes at a time and load the next 20 as the reader nears the end, until all eligible routes are shown; no route may appear twice in one loaded list.
- **FR-009**: Changing the sort or the district MUST restart the list from the first page.
- **FR-010**: Tapping a row MUST open the existing public route preview for that route; all preview behaviour (copy, report, own-route handling, sign-in resumption, unavailable route) stays as in spec 089.
- **FR-011**: Returning from a preview MUST restore the screen's sort, filter, loaded rows and scroll position.
- **FR-012**: The screen MUST show placeholder rows while the first page loads, an error with retry when the first page fails, a retry at the end of the list when a later page fails, an empty state with an action to open the route composer when nothing is listed, and a "no routes in this district" message with a clear-filter action when a filter leaves nothing.
- **FR-013**: The screen MUST be usable without an account.
- **FR-014**: The existing 추천 동선 list used by the Home rail, the Profile section and the route planner sheet MUST keep its current content and ranking.
- **FR-015**: All copy MUST be provided in Korean and English, and the screen MUST follow DESIGN.md: monochrome, 0dp corners (the Home tab's 16dp corners do not apply here), the 8pt grid and existing typography roles.
- **FR-016**: Every row, chip and action MUST be reachable and announced by screen readers; a row MUST be read as one description, as existing 추천 동선 rows are.
- **FR-017**: The app MUST record, through the existing route analytics, that the browse screen was opened and from where (Home or Profile), and attribute copies made from previews opened on this screen to it, without recording personal data.
- **FR-018**: No public web page, link or web flow is added for browsing routes.

### Key Entities

- **Listed route summary**: an eligible public route as a reader sees it in a list: name, stop count, first and last districts (KO/EN), author display name, editor status, 30-day copy count, first shared running day, approval time, and the covers of its first stops. The same entity as 089's 추천 동선 row, plus covers.
- **Browse query**: the reader's current sort (인기 or 최신), optional district, and how far the list has been loaded.
- **District option**: a district (KO/EN names) that at least one eligible route has a stop in.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every eligible listed route can be reached from the Home or Profile tab in at most two taps plus scrolling; in a test catalogue of 45 eligible routes, all 45 are reachable.
- **SC-002**: The first page of routes appears within 2 seconds on a typical mobile connection in Seoul, and each following page within 1 second of reaching the end of the list.
- **SC-003**: Across sort, filter and paging, no route appears twice and no eligible route is skipped in a full scroll, in 100% of automated checks.
- **SC-004**: The browse screen never shows a route that 089's rules would hide from 추천 동선, in 100% of automated checks.
- **SC-005**: Within four weeks of release, the share of readers who open a route preview and then copy it is reported separately for previews opened from the browse screen, so the owner can compare it with the Home rail and Profile section.
- **SC-006**: With no listed routes, a first-time reader can still find the 동선 둘러보기 screen from the Profile tab and reach the route composer from its empty state.

## Assumptions

- "District" means the region recorded on each route stop (the same value 089 shows as the first and last district), in Korean and English.
- Up to three stop covers per row is enough to picture a route without crowding a list row; stops without a cover are skipped rather than shown as blank tiles.
- The existing Home rail keeps showing only when the home feed has routes; its 전체 보기 action exists only when the rail is shown. Profile is the always-available entry point.
- The route planner sheet keeps its 추천 동선 section without a 전체 보기 action; two entry points are enough and the planner is a task-focused flow.
- Paging uses the order at the time each page is requested; changing the sort or filter, or reopening the screen, reloads from the first page.
- The browse screen's server read is additive; the existing top-ten read and its consumers are unchanged.
- Out of scope: walking-route geometry and a full-screen interactive route map (a separate, later feature that will also apply to this screen's previews), map-based browsing, a new bottom navigation tab, theme or keyword filters, likes, author profiles and notifications.
