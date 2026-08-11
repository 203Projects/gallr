# Feature Specification: Personal Exhibition Map

**Feature Branch**: `053-personal-exhibition-map`
**Created**: 2026-08-09
**Status**: Draft
**Input**: Open the Map tab directly on a functional Seoul exhibition map with titled location pins,
using numbered overlap groups only when dense labels cannot remain readable.

## User Scenarios & Testing

### User Story 1 — Explore Seoul exhibitions immediately (Priority: P1)

As a visitor, I land directly on the Seoul venue map so I can begin exploring exact exhibition
locations without passing through a country overview.

**Why this priority**: The abstract map is the feature's primary identity and must remain useful for
discovery before personal history or photos are added.

**Independent Test**: Open the Map tab with a populated catalogue and select an exhibition pin from
the immediately visible Seoul map.

**Acceptance Scenarios**:

1. **Given** the user opens the Map tab, **when** its first frame is shown, **then** the Seoul map is
   visible without a country-selection step or back affordance to a country overview.
2. **Given** the Seoul scope is visible, **when** the user pans or zooms, **then** every readable
   in-view exhibition is represented by its own titled location pin without a district panel.
3. **Given** multiple exhibition markers or titles overlap too heavily to remain readable, **when**
   the map renders, **then** they collapse into a numbered overlap group whose selection opens a list
   of the exact exhibitions in that group.
4. **Given** a numbered overlap group, **when** zooming or panning creates enough visual separation,
   **then** it automatically resolves back into independently titled exhibition pins.
5. **Given** no personal state exists, **when** the map opens, **then** catalogue exhibitions remain
   discoverable as unexplored rather than disappearing.

---

### User Story 2 — Find exhibitions near my current location (Priority: P1)

As a visitor, I can use my current location to open the relevant city scope and see exhibitions
ranked by real distance while retaining the abstract visualization.

**Why this priority**: Nearby discovery is a core capability of the existing map and must not be
lost when the Naver map surface is replaced.

**Independent Test**: Supply a fixed Seoul coordinate, activate Near Me, and verify the resolved
scope, highlighted dots, distances, ordering, and external-navigation action.

**Acceptance Scenarios**:

1. **Given** location permission is granted and a cached location exists, **when** Near Me is
   activated, **then** Gallr opens the matching supported city scope and lists nearby exhibitions
   from nearest to farthest.
2. **Given** the abstract map displays an approximate user position, **when** distances are shown,
   **then** every distance is calculated from original latitude/longitude rather than visual dot
   positions.
3. **Given** location permission is denied or no cached fix exists, **when** Near Me is activated,
   **then** the map remains usable and offers city search without inventing a location.
4. **Given** an exhibition is selected from nearby results, **when** the user requests directions,
   **then** Gallr hands the exact venue coordinate to an appropriate external map application.

---

### User Story 3 — Mark an exhibition as visited (Priority: P1)

As a visitor, I can log an exhibition visit with a date and optional note so visited exhibitions
become part of my private exhibition diary.

**Why this priority**: Visited versus unexplored is the principal personal value of the new map.

**Independent Test**: Mark an exhibition visited without a photo and verify its state on the All
and Visited maps, its diary record, and persistence after the exhibition closes.

**Acceptance Scenarios**:

1. **Given** an unvisited exhibition, **when** a visit is confirmed, **then** its map state changes
   to visited and it appears in the Visited view.
2. **Given** a visited exhibition later closes or leaves the current catalogue, **when** the diary
   is opened, **then** its recorded identity, venue, date, and map position remain available.
3. **Given** an anonymous visitor, **when** a visit is logged, **then** it is stored locally and the
   user is not forced to create an account.
4. **Given** the user later authenticates, **when** local visit migration succeeds, **then** the
   visit becomes available from the user's private cloud record without duplication.

---

### User Story 4 — Log a visit from a photo (Priority: P2)

As a visitor, I can take or choose a photo and let Gallr suggest the venue and exhibitions active on
the capture date, while retaining final control over the match.

**Why this priority**: Photo matching makes visit logging memorable and fast, but manual visit
logging must work independently when metadata is missing or permission is denied.

**Independent Test**: Provide photos with valid metadata, missing metadata, and ambiguous nearby
venues, then verify candidate ranking, confirmation, fallback, and privacy behavior.

**Acceptance Scenarios**:

1. **Given** a photo has location and capture-date metadata, **when** it is selected, **then** Gallr
   proposes nearby venues and exhibitions whose schedules include that date.
2. **Given** multiple exhibitions or venues plausibly match, **when** suggestions are shown, **then**
   Gallr requires an explicit user selection before creating a visit.
3. **Given** metadata is unavailable or redacted, **when** the photo is selected, **then** the user
   can choose an exhibition manually or mark the visit without a photo.
4. **Given** a photo is attached, **when** the visit is saved, **then** exact source coordinates are
   discarded, the uploaded derivative contains no location metadata, and the photo is private by
   default.

---

### User Story 5 — Review saved exhibitions (Priority: P2)

As a visitor, I can switch between All and My Exhibitions so I can identify saved exhibitions on the
map and focus on them when planning a visit. Visited history is deferred to Profile.

**Why this priority**: The filters turn a static map into a repeatable planning and diary surface.

**Independent Test**: Seed saved and unsaved Seoul exhibitions, verify saved pins are orange in All,
and verify My Exhibitions shows only the saved set.

**Acceptance Scenarios**:

1. **Given** saved exhibitions, **when** My Exhibitions is selected, **then** only saved exhibitions
   are shown and every pin uses the orange saved treatment.
2. **Given** saved and unsaved exhibitions, **when** All is selected, **then** saved pins are orange
   and all other pins are solid black.
3. **Given** visit history exists, **when** the Map tab is opened, **then** no Visited mode is shown;
   its future presentation belongs in Profile.

### Edge Cases

- A photograph can have no GPS, no capture date, redacted metadata, edited metadata, or coordinates
  far outside Korea.
- Dense gallery buildings can contain several venues or simultaneous exhibitions at effectively the
  same coordinate.
- One venue can host multiple exhibitions with overlapping schedules.
- A user can be outside every currently supported map scope.
- Location permission can be denied, restricted, revoked, or granted without a cached fix.
- The catalogue can contain incomplete coordinates; those exhibitions remain accessible from lists
  but cannot be represented or ranked geographically.
- An exhibition can close, be unpublished, or be removed after a visit was recorded.
- Local-to-cloud migration can be interrupted or retried.
- A scope can contain no exhibitions, hundreds of exhibitions, or long bilingual labels.
- Dark mode, reduced motion, screen readers, and large text must not make map state inaccessible.

## Requirements

### Functional Requirements

- **FR-001**: The Map tab MUST open directly in the supported Seoul city scope while retaining one
  shared, data-driven scope model for future city support.
- **FR-002**: The initial release MUST support South Korea and Seoul; future world/country additions
  are explicitly out of scope but MUST NOT require replacing the scope or renderer contracts.
- **FR-003**: The current Seoul UI MUST present exhibitions directly without a district selector,
  district summary, city-title row, or country intermediary; reusable scope data MAY remain internal.
- **FR-004**: The map MUST expose All Exhibitions (`전체 전시`) and My Exhibitions (`내 전시`)
  modes with consistent bilingual labels.
  Visited exhibition history is reserved for a future Profile experience.
- **FR-005**: Every non-overlapping exhibition MUST use a solid filled location pin with a white
  center dot and show its localized exhibition title on one line beneath the marker. Titles MUST
  render directly over the map without a background container. Saved pins MUST use `#FF5400`; all
  other pins MUST be black. A compact icon-and-text legend without a background container MUST
  identify the orange pin as My Exhibitions.
  When marker/title overlap makes the titles unreadable, only the colliding exhibitions MUST be
  replaced by a numbered overlap group. Selecting that group MUST open a localized list of its exact
  exhibitions, and the group MUST resolve back to individual titled pins as zoom or pan separates it.
- **FR-006**: Geographic background dots MUST be visually distinct from semantic exhibition or
  aggregate marks and MUST NOT imply nonexistent exhibitions.
- **FR-007**: Exhibition and user coordinates MUST remain the source of truth for scope resolution,
  display, distance, and navigation; visual collision offsets MUST never replace source coordinates.
- **FR-008**: Near Me MUST resolve the user's supported country/city/district, highlight nearby
  results, and return results ordered by real geodesic distance.
- **FR-009**: The abstract user-location indicator MUST be described as approximate and MUST remain
  visually distinct from exhibition state.
- **FR-010**: Location permission MUST be requested only when a location-dependent action is invoked;
  denying permission MUST NOT block manual map exploration or city search.
- **FR-011**: Exact street navigation MUST be delegated to an external map using the exhibition's
  original coordinate.
- **FR-012**: Users MUST be able to create, update, and delete a private visit without attaching a
  photo or publishing a thought.
- **FR-013**: Visit state MUST be independent of bookmarks and public/moderated thoughts.
- **FR-014**: A visit MUST retain enough exhibition and venue snapshot data to remain intelligible
  after the live catalogue entry changes or disappears.
- **FR-015**: Anonymous visits MUST be local-first and MUST support idempotent migration to the
  authenticated user's private cloud data.
- **FR-016**: Photo metadata reading MUST be best-effort and MUST never be required to log a visit.
- **FR-017**: Photo matching MUST rank candidates using capture date, real distance, and catalogue
  schedules, then require explicit user confirmation.
- **FR-018**: Exact source photo coordinates MUST NOT be persisted after confirmation.
- **FR-019**: Any uploaded visit photo MUST be re-encoded without EXIF/location metadata and stored
  in owner-only private storage by default.
- **FR-020**: Visit notes and private photos MUST NOT appear in public thoughts, public profiles, or
  gallery-owner surfaces without a separate future opt-in feature.
- **FR-021**: Dot geometry MUST render as one batched canvas/path surface per scope, not one Compose
  node per background dot.
- **FR-022**: Every visual map state MUST have an equivalent accessible list/summary and screen-reader
  semantics; color alone MUST NOT communicate state.
- **FR-023**: Loading, empty, unavailable-location, partial-coordinate, offline, upload-failure, and
  migration-failure states MUST preserve existing local content and offer a retry or manual path.
- **FR-024**: Significant scope, nearby, visit, metadata, upload, and migration failures MUST emit
  structured logs without photo bytes, exact private coordinates, notes, or other sensitive content.
- **FR-025**: UI implementation MUST follow `DESIGN.md`, including sharp corners, established type and
  spacing tokens, monochrome surfaces, sanctioned motion, and accent restrictions.

### Key Entities

- **MapScope**: A country, city, or district identity with parent, localized labels, bounds, and a
  reference to display geometry.
- **DotMapGeometry**: Presentation-only background dots and projection metadata for one scope.
- **ScopeAggregate**: Saved, visited, unexplored, active, and historical counts for a child scope.
- **ExhibitionVisit**: A private user record containing visit time, exhibition identity, optional
  note, match source, and an immutable exhibition/venue snapshot.
- **VisitPhoto**: An optional private, sanitized image derivative owned by one visit.
- **PhotoMetadata**: Ephemeral capture time and coordinate input used for matching and discarded after
  confirmation.
- **NearbyExhibition**: An exhibition plus real calculated distance from the user's source coordinate.

## Success Criteria

### Measurable Outcomes

- **SC-001**: A user can open an exhibition from the default Seoul map in no more than one selection
  after entering the Map tab.
- **SC-002**: With a cached location, Near Me displays a correctly ordered first result and supported
  city scope within two seconds on a representative release device and normal network.
- **SC-003**: Distance-order tests produce the same ordering on Android and iOS for fixed coordinates.
- **SC-004**: A manual visit can be completed in no more than three actions after opening Log Visit.
- **SC-005**: Every ambiguous photo match requires confirmation; zero visits are created solely from
  automatic metadata inference.
- **SC-006**: Automated privacy tests confirm no EXIF GPS remains in uploaded visit derivatives and no
  user can read another user's visit rows or objects.
- **SC-007**: A recorded visit remains readable after its catalogue exhibition is unavailable.
- **SC-008**: Country and city background geometry renders without creating a semantic/accessibility
  node for every dot and maintains smooth interaction on representative Android and iOS devices.
- **SC-009**: All map modes and actions are operable with screen-reader navigation through the
  equivalent list/summary surface.

## Out of Scope

- World scope or non-Korean country geometry in the initial release.
- Background photo-library scanning or automatic visit creation.
- Continuous/background location tracking or walking-route recording.
- Public photo sharing, social visit feeds, reactions, or gallery access to private visits.
- Turn-by-turn navigation inside Gallr.
- Achievement percentages based on a permanently fixed total exhibition count.

## Approved Visual References

- [South Korea country scope](assets/korea-country-scope.png)
- [Seoul All scope](assets/seoul-all-scope.png)
- [Seoul Near Me state](assets/seoul-near-me.png)
- [Photo visit matching](assets/log-visit-photo-match.png)
- [Visited diary state](assets/seoul-visited-diary.png)
