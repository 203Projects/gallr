# Feature Specification: Mobile Map UI Regressions

**Feature Branch**: `shin/057-mobile-map-ui-regressions`
**Created**: 2026-08-12
**Status**: Complete
**Input**: Restore full-height iOS rendering and correct MapLibre pin visuals, grouping, and gesture arbitration after the 1.8.0 migration.

## User Scenarios & Testing

### User Story 1 — Use the full iPhone viewport (Priority: P1)

As an iPhone visitor, I see Gallr fill the physical screen while headers and controls still respect
the status bar, home indicator, and keyboard.

**Why this priority**: The current double safe-area inset visibly shrinks every screen and makes the
entire app feel letterboxed.

**Independent Test**: Launch the app on a modern iPhone simulator and confirm that the host view
extends edge-to-edge while Compose places the top app bar and bottom navigation inside the system
safe areas exactly once.

**Acceptance Scenarios**:

1. **Given** an iPhone with a Dynamic Island and home indicator, **when** Gallr launches, **then** the
   Compose canvas fills the screen and content is inset from system UI only once.
2. **Given** the Featured or Map tab, **when** the bottom navigation is shown, **then** its background
   extends to the bottom edge and its labels remain above the home indicator.
3. **Given** an Android device, **when** Gallr launches, **then** its existing safe-area behavior is
   unchanged.

---

### User Story 2 — Read and select unambiguous map pins (Priority: P1)

As a map visitor, I see one pin per venue coordinate, with a count for simultaneous exhibitions,
and selecting a pin does not flash an unrelated grey rectangle.

**Why this priority**: The migration introduced visually confusing stacked pins and default Compose
ripples that make the map look unfinished.

**Independent Test**: Render exhibitions at identical and nearby coordinates, then verify identical
coordinates form one counted marker, nearby coordinates remain separate at every zoom, and taps
produce no rectangular indication.

**Acceptance Scenarios**:

1. **Given** two exhibitions with exactly equal valid coordinates, **when** the map renders, **then**
   one pin with a numeric count is shown and selecting it opens the existing overlap sheet.
2. **Given** two exhibitions at different coordinates that appear close on screen, **when** the map
   renders at any zoom, **then** they remain independent pins.
3. **Given** any exhibition pin, **when** it is tapped, **then** no rectangular Material indication
   appears; the resulting navigation or sheet is the feedback.
4. **Given** a coordinate group, **when** every exhibition in it is saved, **then** its pin uses the
   saved orange treatment; otherwise it remains black.

---

### User Story 3 — Pinch the map through its markers (Priority: P1)

As a map visitor, I can begin a two-finger pinch on top of one or more exhibition pins without the
pins stealing either pointer from MapLibre.

**Why this priority**: Pin-dense central Seoul currently makes the primary map gesture unreliable.

**Independent Test**: On Android and iOS, place a finger over a pin, add a second finger on the map,
and verify the camera zoom changes without opening the pin or overlap sheet.

**Acceptance Scenarios**:

1. **Given** a finger lands on a visible pin, **when** a second finger begins a pinch, **then** the map
   receives the multi-touch gesture and changes zoom.
2. **Given** a single stationary pointer taps a pin, **when** it lifts without becoming a gesture,
   **then** the exhibition or overlap sheet opens once.
3. **Given** a screen-reader user, **when** a pin is focused, **then** its localized label and action
   remain available even if visual rendering moves into a MapLibre-owned layer.

### Edge Cases

- Coordinate values may be missing, non-finite, or outside geographic bounds and must remain omitted.
- Equal coordinates can contain saved and unsaved exhibitions; mixed groups are black.
- A coordinate group can contain more than nine exhibitions and must display the complete count.
- MapLibre may suppress colliding labels; the marker remains selectable and its exhibitions remain
  available through the overlap sheet.
- Safe-area values change with rotation, call status, hotspot status, and keyboard presentation.
- Map rendering or projection can be temporarily unavailable while the style loads.

## Requirements

### Functional Requirements

- **FR-001**: The iOS host MUST size the Compose view to the full container and delegate status-bar,
  home-indicator, and keyboard avoidance to the Compose UI.
- **FR-002**: Safe-area insets MUST be applied exactly once on iOS and MUST NOT change Android layout.
- **FR-003**: Pin grouping MUST be based on exact source latitude/longitude equality and MUST be
  independent of screen projection, pan, and zoom.
- **FR-004**: A same-coordinate group MUST render as one pin with a numeric count and MUST open the
  existing localized overlap sheet.
- **FR-005**: Saved color MUST remain orange only when every exhibition represented by the marker is
  saved; mixed and unsaved markers MUST remain black.
- **FR-006**: Exhibition pin selection MUST NOT show a default ripple or rectangular indication.
- **FR-007**: Pin hit testing MUST allow MapLibre to arbitrate multi-pointer gestures on Android and
  iOS while preserving single-tap selection.
- **FR-008**: Pin labels, group descriptions, and selection actions MUST remain available to
  assistive technologies in Korean and English.
- **FR-009**: Map chrome controls remain Compose controls and retain their existing indications.
- **FR-010**: The current-location marker color and shape MUST remain unchanged pending a separate
  design decision.
- **FR-011**: UI changes MUST follow `DESIGN.md`, including sharp surfaces, the one orange accent,
  and minimum 44dp touch targets.

### Key Entities

- **Exhibition coordinate group**: One exact geographic position and the ordered exhibitions located
  at it, with derived count, localized label, and all-saved state.
- **Visual pin feature**: A MapLibre-renderable point carrying the stable coordinate-group identity
  needed to resolve a map-engine click back to its exhibitions.

## Success Criteria

### Measurable Outcomes

- **SC-001**: A modern iPhone screenshot shows the Compose canvas spanning 100% of screen height,
  with top and bottom system insets consumed once.
- **SC-002**: Automated tests prove exact-coordinate grouping is stable across arbitrary projected
  positions and zoom levels.
- **SC-003**: Every multi-exhibition coordinate displays one marker and its full integer count.
- **SC-004**: Manual Android and iOS checks confirm a pinch beginning over a pin changes map zoom in
  five consecutive attempts without opening pin content.
- **SC-005**: Existing single-tap navigation, overlap-sheet, saved-color, and accessible-label
  behavior continues to work.

## Out of Scope

- Changing the current-location marker to blue or introducing another accent color.
- Removing or redesigning the My Exhibitions tab.
- Screen-space clustering of different venues.
- Changes to map zoom buttons, recenter-button feedback, base-map style, or catalogue coordinates.
