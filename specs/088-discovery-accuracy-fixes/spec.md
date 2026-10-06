# Feature Specification: Local discovery accuracy fixes

**Feature Branch**: `088-discovery-accuracy-fixes`
**Created**: 2026-10-02
**Status**: Draft
**Input**: User description: "Local discovery accuracy fixes (step 1 of the discovery rethink). Fix four measured accuracy defects in the on-device recommender and neighborhood route planner, without changing the on-device/no-hosted-model boundary from specs 071/073/074 and without new UI flows: (1) For You routes are location-blind and capped at six recommendations; (2) text-similarity evidence fires for most catalogue pairs because of shared venue boilerplate; (3) taste uses only the single best anchor per signal; (4) routes ignore parseable opening hours. Deterministic shared logic with test coverage, including a fixture reproducing the measured defects. No analytics allowlist changes, no schema migration, no hosted model."

## Context

Measured on the production catalogue on 2026-10-02 (71 current or upcoming exhibitions):

- A three-stop For You route from Hannam can choose from 2 of the 32 exhibitions open within the route radius, because route candidates are limited to six recommendations ranked without the route origin. Seongsu (1 of 14) and Cheongdam (2 of 19) fail the same way.
- 56% of all exhibition pairs pass the "similar text" threshold. The median exhibition counts as similar to 51 of the other 70. The strongest pairs share institutional boilerplate rather than artistic content.
- Only 5 exhibitions list artists and 4 list art terms, so the text signal and its noise dominate personal matching. Each candidate is scored against a single best saved or visited exhibition per signal.
- 61 exhibitions list opening hours as free text and 60 of them follow a small number of regular patterns. 44 of the 58 venues parsed with the most common pattern are closed on Mondays. Routes never use this information; they attach a blanket "hours unverified" warning instead.

This feature keeps the product boundary of specs 071, 073 and 074: recommendation and route inference run on the device, use no hosted model, send no taste or location data off the device, and never use paid promotion.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - For You route works where the visitor is (Priority: P1)

A visitor opens the route planner on the map around Hannam, chooses For You and three stops, and builds the route. Today the route almost always reports that there are not enough exhibitions, even though dozens are open nearby. After this change the planner picks stops from every eligible exhibition near the map center, favouring the visitor's interests first and filling the rest with good nearby choices.

**Why this priority**: The For You route is a headline feature that currently fails in most neighbourhoods. Fixing it turns a dead end into a working route.

**Independent Test**: Build For You routes from the measured neighbourhood centers against the catalogue fixture, with and without saved history, and confirm a complete route is returned whenever enough open venues exist within the radius.

**Acceptance Scenarios**:

1. **Given** a visitor with no saves, visits or follows and at least three distinct open venues within the route radius, **When** they build a three-stop For You route, **Then** a complete three-stop route is returned.
2. **Given** a visitor whose saved and visited history matches some nearby exhibitions, **When** they build a For You route, **Then** exhibitions with a personal match are chosen ahead of nearby exhibitions without one, subject to the route still being walkable within the radius.
3. **Given** a visitor has saved an exhibition that is open and within the radius, **When** they build a For You route, **Then** that saved exhibition is eligible as a stop and is presented as saved rather than with an inferred taste reason.
4. **Given** a visitor has visited an exhibition, **When** they build a For You route, **Then** that exhibition is not chosen as a stop.
5. **Given** the For You recommendations screen, **When** it is shown, **Then** it still lists at most six exhibitions and still excludes saved and visited exhibitions.

---

### User Story 2 - Routes never send visitors to a closed venue (Priority: P1)

A visitor builds a route on a Monday afternoon. Today the route can include galleries that are closed all day or that close before the visitor could arrive. After this change, venues whose listed hours show them closed on the visit date are left out, stops are ordered so each is reached while open, and stops whose hours cannot be read are clearly marked as unverified.

**Why this priority**: A route to a locked door is the most damaging wrong answer the planner can give, and the data to prevent it already exists for most venues.

**Independent Test**: Run the planner against the catalogue fixture for each day of the week and a range of start times, and confirm no stop is closed on the visit date or reached after its closing time.

**Acceptance Scenarios**:

1. **Given** a venue whose listed hours show it closed on the visit day, **When** a route is built for that day, **Then** the venue is not a stop in any mode, including Saved.
2. **Given** a route built at a given start time, **When** the stops are ordered, **Then** every stop's estimated arrival plus the planned visit time ends at or before that stop's closing time, allowing for walking time between stops.
3. **Given** a stop opens later than the visitor would arrive, **When** the route is built, **Then** the route accounts for the wait before the visit begins.
4. **Given** a stop whose hours are missing or cannot be read, **When** the route is shown, **Then** that stop alone carries the unverified-hours disclosure, and a route whose stops all have readable hours does not show the route-level unverified-hours warning.
5. **Given** too few venues are open for the rest of the visit date, **When** the route is built, **Then** the shortage message says how many nearby venues were left out because they are closed, so the visitor understands why.

---

### User Story 3 - Taste reasons mean something (Priority: P2)

A visitor who saved several painting exhibitions opens For You. Today almost every card can claim to be "similar to" something they saved, because shared wording about museums and venues counts as similarity, and five related saves count no more than one. After this change, text similarity is only claimed for genuinely close exhibitions, and repeated interest across several saves raises matching exhibitions in the ranking.

**Why this priority**: Explanations that fire for everything teach visitors to ignore them. This is less urgent than routes that fail outright, but it directly affects trust in For You.

**Independent Test**: Using the catalogue fixture, measure the share of exhibition pairs treated as text-similar and check ranking against a small set of saved-history scenarios with known expected matches.

**Acceptance Scenarios**:

1. **Given** two exhibitions at the same venue whose descriptions share only the venue's standard wording, **When** one is saved, **Then** the other is not presented as text-similar on account of that wording.
2. **Given** two exhibitions with substantially shared artistic content, such as the same artist or subject described in both, **When** one is saved, **Then** the other may still be presented as similar.
3. **Given** a visitor saved three exhibitions that share an artist or art term and one exhibition that does not, **When** recommendations are ranked, **Then** a candidate matching the shared artist or term ranks above an otherwise equivalent candidate matching only the single unrelated save.
4. **Given** any recommendation, **When** its reasons are shown, **Then** it shows at most two reasons and each reason names a real saved, visited or followed source that supports it.
5. **Given** a visitor with no history, **When** For You is shown, **Then** no reason claims a taste match.

### Edge Cases

- Hours that list times but no days, for example "12pm - 7pm", are treated as open every day at those times and the stop is marked as unverified.
- Hours with several lines, such as different closing times on weekends, use the times that apply to the visit day.
- Notes that mention public holidays or one-off closures, such as "Closed on 9/25", are not modelled. A stop on such a day is treated according to its regular weekly hours.
- A venue that closes later on certain evenings uses the regular closing time unless the evening is listed in a form the parser understands.
- Hours written in 24-hour form with an en dash, with "·" separators, or ending in "Closed Monday" are read the same way as the common 12-hour form.
- Times are interpreted in Korea time for Korean venues, regardless of the visitor's device time zone.
- When a route is built late in the day and nothing nearby is still open long enough for a visit, the planner returns a shortage that names closed venues as the cause, rather than a route to closed venues.
- Several exhibitions at one venue still count as one stop, and the venue's hours apply to all of them.
- An exhibition without coordinates is still excluded without failing the route.
- If every saved exhibition is closed on the visit date, a Saved route reports the shortage with the closed count.

## Requirements *(mandatory)*

### Functional Requirements

**For You routes**

- **FR-001**: For You route stop selection MUST consider every eligible exhibition within the route radius, not only the recommendations shown on the For You screen.
- **FR-002**: For You route ranking MUST use the route origin for proximity.
- **FR-003**: For You routes MUST prefer exhibitions with personal evidence (saved-history, visited-history or followed-gallery matches) over exhibitions without it, and MUST fill remaining stops with eligible nearby exhibitions so that a route is returned whenever enough distinct open venues exist.
- **FR-004**: Saved exhibitions MUST be eligible stops in For You routes and MUST be presented as saved, not with an inferred taste reason. Visited exhibitions MUST NOT be chosen.
- **FR-005**: The For You recommendations screen MUST keep its current behaviour: at most six exhibitions, saved and visited exhibitions excluded, and no use of the visitor's location.

**Opening hours**

- **FR-006**: The system MUST read each exhibition's free-text hours into weekly opening times per weekday, supporting at least the patterns present in the current catalogue: 12-hour time ranges with day ranges on the same or the next line, 24-hour ranges with day ranges, several weekday groups with different times, and explicit "Closed <day>" statements.
- **FR-007**: Reading hours MUST be deterministic, MUST never fail a route, and MUST classify each exhibition's hours as known, partially known (times without days) or unknown. A partially known reading MUST NOT be treated as a closure on any day.
- **FR-008**: In every route mode, the planner MUST exclude venues whose known hours show them closed on the visit date.
- **FR-009**: The planner MUST order and validate stops from a start time so that, using the existing walking estimates and planned visit time, each stop with known hours is reached and finished within its opening time on the visit date, including any wait for opening.
- **FR-010**: When the visit date is today, the start time MUST be the current time in the venue's local time zone. Later visit dates MUST start at the earliest opening time among candidate stops.
- **FR-011**: Stops with partially known or unknown hours MUST each be marked as unverified. The route-level unverified-hours warning MUST appear only when at least one stop is unverified.
- **FR-012**: When a route cannot be completed, the shortage result MUST report how many otherwise eligible venues were excluded as closed, and the existing shortage message MUST mention them.

**Recommendation quality**

- **FR-013**: Text similarity MUST NOT be driven by wording shared across a venue's exhibitions or by wording that appears across most of the catalogue.
- **FR-014**: The text-similarity threshold MUST be set so that, on the catalogue fixture, no more than 10% of exhibition pairs count as text-similar.
- **FR-015**: Personal evidence of the same kind (artist, art term, text) MUST combine across all matching saved or visited exhibitions. Each additional match MUST add less than the previous one, and the combined contribution of each kind MUST stay bounded so that one kind cannot outweigh all others.
- **FR-016**: Each recommendation MUST show at most two reasons, and each reason MUST name a real supporting source. Where several sources support a reason, the strongest is named.
- **FR-017**: A visitor with no saved, visited or followed history MUST NOT see a taste-match reason.
- **FR-022**: Recommendations supported by personal evidence (saved-history, visited-history or
  followed-gallery matches) MUST rank ahead of recommendations supported only by editorial, proximity or
  timing signals, so that a visitor's own history is what orders the For You list.

**Boundaries**

- **FR-018**: All ranking, hours reading and route planning MUST run on the device, with no hosted model, no network call, no new permission and no persistent taste profile.
- **FR-019**: Results MUST stay deterministic for the same catalogue, history, origin, visit date and start time, with stable tie-breaking.
- **FR-020**: Analytics events, their allowlisted fields and the catalogue schema MUST stay unchanged.
- **FR-021**: Paid promotion MUST remain outside organic ranking and routes.

### Key Entities

- **Weekly opening hours**: For one exhibition's venue, the opening and closing time for each weekday that the listing makes known, plus whether the reading is complete, partial or unknown. Derived from the existing free-text hours; never stored.
- **Route stop schedule**: For each stop, the estimated arrival, the time the visit begins (after any wait), the time it ends, the applicable closing time, and whether the hours are verified.
- **Route shortage**: The requested stop count, the number of usable venues, and the number excluded because they are closed on the visit date.
- **Taste evidence**: The combined strength of each kind of personal match for a candidate, together with the strongest supporting source used for the visible reason.
- **Catalogue fixture**: A test snapshot of the published catalogue, plus scenario histories and origins, that reproduces the measured defects.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On the catalogue fixture, a three-stop For You route with no history succeeds from the Hannam, Seongsu and Cheongdam centers on a weekday when they have at least three open venues, compared with 0 of 3 today.
- **SC-002**: Across the fixture, every day of the week and start times from 10:00 to 17:00, no route includes a stop closed on the visit date, and no stop with known hours is finished after its closing time.
- **SC-003**: All but at most one of the fixture exhibitions with listed hours (68 in the 2026-10-03 snapshot) are read as complete or partial hours, with every reading matching a hand-checked expected value.
- **SC-004**: The share of fixture exhibition pairs treated as text-similar falls from 56% to 10% or less.
- **SC-005**: In every fixture taste scenario, the expected best match ranks in the top three For You results.
- **SC-006**: Every shown reason in the fixture scenarios points to a real supporting source, and no-history scenarios show no taste-match reason.
- **SC-007**: Building a route on the fixture completes without perceptible delay, under one second, on a mid-range phone.
- **SC-008**: No new network request, permission, stored profile, analytics field or schema change appears in the change.

## Assumptions

- Visit duration (45 minutes per stop), route radius (5 km), stop count (2–5), walking estimate and map-center origin stay as defined in spec 073.
- The planner continues to build routes for today. Starting from "now" means a late-evening route can honestly report that nothing is open; offering another day is a later feature.
- Public holidays and one-off closures are not modelled in this feature.
- Venue hours apply to all exhibitions at that venue.
- The catalogue fixture is built from published, public catalogue fields only and contains no user data.
- No visible flow changes. Copy changes are limited to the existing shortage message, the per-stop unverified-hours label and the saved label on route stops.
- Better catalogue metadata (artists and art terms for most exhibitions) is a separate later feature. This feature improves accuracy within the metadata that exists today.
