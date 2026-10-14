# Feature Specification: Walking routes and a full-screen route map

**Feature Branch**: `093-walking-route-map`
**Created**: 2026-10-14
**Status**: Draft
**Input**: User description: "Walking routes and a full-screen route map. Today the route map panel draws straight lines between stops and travel time is an offline estimate (straight-line distance × 1.25 at 4.5 km/h), so routes do not look trustworthy, and the panel is a static picture with no zoom. Owner decisions: walking only (no transit), no paid map API and no live/real-time service; compute the walking path and time once when a route is created or its stops/order change, save it, and read the saved copy afterwards; the map panel stays a static picture in the scrolling page but tapping it opens a full-screen interactive route map (pinch zoom, pan, close, re-centre on the route). … Out of scope: public transit, live traffic or real-time arrivals, turn-by-turn navigation, offline map tiles, changing stop selection/ordering logic."

## Context

A route in gallr is two to ten exhibitions in order: built by the route planner, composed by a visitor in the personal route composer (spec 089), or listed publicly and opened in its preview. Each shows a small map panel and a summary with distance and walking time.

Two things make that panel hard to trust today. Its line runs straight from stop to stop, through buildings and across the river. Its distance and time come from an offline guess: the straight-line distance plus a quarter, walked at 4.5 km/h. The panel is also a still picture, so a visitor can't zoom in to see where a stop is.

The owner asked for real walking paths and a map that can be explored, under three constraints decided on 2026-10-14:

- **Walking only.** No public transit, no live traffic, no real-time data.
- **Free and long-lived.** No paid map service. Paths come from free OpenStreetMap-based routing, on a provider that can be replaced without an app update.
- **Computed once.** A path and time are worked out when a route is built or its stops change, then saved and reused. Opening a route later does not ask again.

Premises:

- Walking times stay estimates of walking at a normal pace, but along real streets. The wording keeps saying so ("예상", "약").
- A guess is never presented as a real path. When a leg can't be routed, the app says so by how that leg is drawn and worded.
- Stop selection and order are unchanged: the planner and the author still decide which stops and in what order. Only the path between them, its distance and its time change.
- Mobile first (constitution Principle VII): this changes the app's route screens. The public web's shared-route page is not part of this feature.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See the real walking path between stops (Priority: P1)

A visitor builds a route of three galleries in 성수동 and 한남동. The map panel draws the line along the streets and bridges they would actually walk, and the summary shows the walking distance and time along that path.

**Why this priority**: This is what makes a route trustworthy. Without it, the full-screen map would only enlarge a wrong picture.

**Independent Test**: Build a route whose stops are on opposite sides of the Han River or of a large block, and confirm the line follows streets and a bridge, and that the distance and time are those of that path, not of a straight line.

**Acceptance Scenarios**:

1. **Given** a route with two or more stops, **When** the route is built (planner), composed or edited (composer), or opened (public preview), **Then** each leg's line follows walkable streets from one stop to the next, and from the starting location to the first stop when one is used.
2. **Given** the walking path of each leg, **When** the summary and stop rows are shown, **Then** distance and walking time are those of the walking paths and keep their estimate wording ("약", "예상").
3. **Given** walking times from the paths, **When** the visit schedule is worked out, **Then** arrival times, waits, and open/closed verdicts use those times; a stop that no longer fits its opening hours is flagged in the existing way, and stops are never reordered or dropped.
4. **Given** the composer, **When** the author adds, removes or reorders a stop, **Then** only the legs that changed are worked out again, and the map and summary update when they arrive.
5. **Given** a route whose paths were already worked out, **When** anyone opens it again (the author later, or a reader of a public route), **Then** the same paths are shown without waiting on a new calculation.
6. **Given** the same route, **When** its distance appears on the summary, the stop rows and the share card, **Then** each place shows the same distance.

---

### User Story 2 - Explore the route on a full-screen map (Priority: P1)

The visitor taps the map panel. A full-screen map opens on the whole route. They pinch to zoom in on the second stop to see which side of the street it is on, pan along the path, tap "fit route" to see everything again, and close the map to return to the route exactly where they were.

**Why this priority**: The owner's original request; the panel is too small to read a route.

**Independent Test**: On each of the three route screens, tap the panel, zoom, pan, re-fit, close, and confirm the screen beneath is unchanged.

**Acceptance Scenarios**:

1. **Given** the route planner result, the personal route composer, or a public route preview, **When** the visitor taps the map panel (or its expand control), **Then** a full-screen route map opens fitted to the whole route.
2. **Given** the full-screen map, **When** the visitor pinches, double-taps or drags, **Then** the map zooms and pans; rotation and tilt stay off so north is always up.
3. **Given** the visitor has moved the map, **When** they tap "fit route", **Then** the map returns to show the whole route.
4. **Given** the full-screen map, **When** the visitor taps close or uses the system back gesture, **Then** they return to the screen they came from with its scroll position and state unchanged.
5. **Given** the full-screen map, **When** it is shown, **Then** it carries the same numbered cover pins, line style and starting-point marker as the panel, plus the map and routing data credits.
6. **Given** the map panel in the scrolling page, **When** the visitor scrolls over it, **Then** the page scrolls as it does today; the panel itself never captures zoom or pan.

---

### User Story 3 - Know when a path is only an estimate (Priority: P2)

The visitor is offline when they build a route. The route still appears with today's estimates, but legs that couldn't be routed are drawn dashed and the summary says the distance is approximate. When they come back online and open the route again, the real paths appear.

**Why this priority**: Honesty about estimates is a standing product rule. Without it, a failed lookup would silently look like a real path.

**Independent Test**: With routing unavailable, build a route and confirm dashed legs and approximate wording; restore routing, reopen, and confirm solid legs and walking-path numbers.

**Acceptance Scenarios**:

1. **Given** a leg whose walking path cannot be obtained (offline, routing service down or over its limit, no walkable path found), **When** the route is shown, **Then** that leg is drawn as a dashed straight segment and its distance and time come from today's estimate.
2. **Given** at least one estimated leg, **When** the summary is shown, **Then** it carries the existing approximate-distance disclosure; when every leg is a walking path, that disclosure is not shown.
3. **Given** the route is being worked out, **When** the visitor is waiting, **Then** the route is shown at once with estimates (dashed) and changes to walking paths when they arrive, without blocking the screen.
4. **Given** a leg that failed, **When** the route is opened again later and routing is available, **Then** that leg is worked out again.

---

### User Story 4 - Keep the visitor's location private (Priority: P2)

A visitor starts a route "from my current location". The first leg is walked from where they stand, but where they stand is not kept anywhere.

**Why this priority**: Location is personal data; the saved paths are shared between all visitors.

**Independent Test**: Build a route from the current location, then inspect what the service stored and logged, and confirm it holds only venue-to-venue paths.

**Acceptance Scenarios**:

1. **Given** a route that starts from the visitor's location, **When** its first leg is worked out, **Then** that leg is not saved for reuse and the location is not written to any log.
2. **Given** saved paths, **When** they are reused, **Then** they only ever join two exhibition venues.

---

### Edge Cases

- Two consecutive stops in the same building or at the same point: no line is drawn for that leg, and its time is zero, as today.
- A walking path far longer than the straight line (no bridge nearby, a closed area in between): the path and its time are shown as they are, without capping.
- No walkable path between two points (for example a venue placed on an island or in a closed facility): the leg falls back to the dashed estimate.
- A route with more stops than one request can carry: the legs are worked out in more than one request, still once per leg.
- A venue that moves (its coordinates are corrected): paths to and from the old position are not reused for the new one.
- The routing service is replaced: paths already saved stay valid, and new legs come from the new service, with no app update.
- A stop has no map location: it has no leg, as today.
- The full-screen map is opened while paths are still being worked out: it shows the dashed estimates and updates in place when the paths arrive.
- The device is rotated while the full-screen map is open: the map keeps showing the route.
- The visitor taps the panel twice quickly: one full-screen map opens.

## Requirements *(mandatory)*

### Functional Requirements

**Walking paths**

- **FR-001**: For every leg of a route (starting location to first stop, then each stop to the next), the app MUST obtain a walking path along walkable ways, with its distance and walking time.
- **FR-002**: Walking paths MUST be requested when a route's stops or order are set: a planner route is built, a composer stop is added, removed or reordered, a saved or public route is opened. They MUST NOT be requested again while the visitor merely views, scrolls, or opens the full-screen map.
- **FR-003**: A walking path between two exhibition venues MUST be saved once and reused for every later request between the same two venue positions, for all visitors.
- **FR-004**: A leg that starts at the visitor's current location MUST NOT be saved for reuse, and the location MUST NOT be written to logs, analytics or saved paths.
- **FR-005**: Distance and walking time shown anywhere for a route (summary, stop rows, schedule, share card) MUST come from the walking paths when available and be identical across those places.
- **FR-006**: The visit schedule (arrival, wait, visit window, open/closed verdict) MUST be recalculated with the walking times. Stop selection and order MUST NOT change because of them.
- **FR-007**: When a leg's walking path cannot be obtained, the app MUST use the current estimate for that leg, draw it as a dashed straight segment, and show the existing approximate-distance disclosure on the summary.
- **FR-008**: A route MUST be shown immediately with estimates while walking paths are being obtained, and update in place when they arrive, without blocking interaction.
- **FR-009**: A leg that fell back to an estimate MUST be retried the next time the route's paths are requested.
- **FR-010**: The routing service MUST be free of charge for the expected volume, use open map data, and be replaceable without an app update; credentials for it MUST stay on the server.
- **FR-011**: Requests for walking paths MUST be rate-limited per caller and overall, like the existing address lookup, and work for visitors without an account.

**Full-screen map**

- **FR-012**: The map panel on the route planner result, the personal route composer, and the public route preview MUST open a full-screen route map when tapped, and show an expand control that does the same.
- **FR-013**: The full-screen map MUST allow zoom (pinch, double-tap) and pan, keep north up (no rotation or tilt), and open fitted to the whole route.
- **FR-014**: The full-screen map MUST offer a "fit route" control that returns to the whole route, and a close control; system back MUST also close it.
- **FR-015**: Closing the full-screen map MUST return to the originating screen with its state and scroll position unchanged.
- **FR-016**: The full-screen map MUST draw the same numbered cover pins, starting-point marker and line styles (solid walking paths, dashed estimates) as the panel, and MUST show the map-data and routing-data credits.
- **FR-017**: The map panel MUST remain a non-interactive picture inside the scrolling page, so scrolling over it scrolls the page.

**General**

- **FR-018**: All new copy MUST be in Korean and English; the screens MUST follow DESIGN.md (monochrome apart from covers, 0dp corners, existing type roles). DESIGN.md's "Route map panel" section MUST be updated to describe the walking line, the dashed estimate, the expand control and the full-screen map.
- **FR-019**: The full-screen map's controls MUST be reachable and announced by screen readers, and the map MUST carry a description of the route (number of stops, total walking distance).
- **FR-020**: No public web page or web flow changes in this feature.

### Key Entities

- **Walking leg**: the path from one point to the next: its line, walking distance, walking time, and whether it is a walking path or an estimate.
- **Saved walking path**: a walking leg between two exhibition venue positions, kept for reuse by every visitor. Never joins a visitor's own location.
- **Route walking plan**: a route's ordered legs, and from them the total distance, total walking time and the visit schedule.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For routes within Seoul, at least 95% of legs between exhibition venues are shown as walking paths rather than estimates when the service is reachable.
- **SC-002**: Opening a route whose venue-to-venue paths were already worked out shows its walking paths within 1 second on a typical mobile connection; a new route shows them within 3 seconds.
- **SC-003**: After a short warm-up, at least 90% of venue-to-venue path requests are answered from saved paths, so the routing service's free limit is never reached in normal use.
- **SC-004**: No stored record or log contains a visitor's current location, verified by inspecting the saved paths and service logs after routes built from the current location.
- **SC-005**: On all three route screens, a visitor can open the full-screen map, zoom to a single stop and return to the whole route in under 10 seconds, and closing it returns to the same place in 100% of checks.
- **SC-006**: When routing is unavailable, 100% of routes still appear, with every estimated leg dashed and the approximate-distance disclosure shown.

## Assumptions

- The routing service is free OpenStreetMap-based walking routing (openrouteservice's free plan first, with self-hosted OSRM as the fallback). Its commercial-use terms and the current availability of its walking profile are confirmed during planning; if either fails, the self-hosted option is used and the app is unchanged.
- "The same venue position" means the same coordinates within a few metres; a venue whose coordinates are corrected is a new position.
- One request carries up to six points (a starting location and five stops); longer routes use more requests.
- The route share card shows the same walking distance as the composer when its paths are available, and today's estimate otherwise.
- The visitor's current location is sent to the routing service to route the first leg. The privacy notice and store disclosures are checked during planning and updated if they don't already cover this.
- Existing route data (saved personal routes, listings, copies) needs no change; paths are worked out when routes are next opened.
- Out of scope: public transit, live traffic or real-time arrivals, turn-by-turn navigation, offline map tiles, the public web's shared-route page, and any change to how stops are chosen or ordered.
