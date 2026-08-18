# Feature Specification: Readable Zoomed-Out Map Pins

**Feature Branch**: `shin/058-map-pin-clustering`
**Created**: 2026-08-13
**Status**: Complete
**Input**: Restore the 1.8.0 zoom-dependent multi-location marker behavior after 1.8.1 made zoomed-out exhibition titles overlap, while making multi-location and single-location pins visually distinct.

## User Scenarios & Testing

### User Story 1 — Read the map while zoomed out (Priority: P1)

As a map visitor, I can zoom out without exhibition titles becoming an unreadable block.

**Independent Test**: Project several exhibition locations close together, then verify they become
one visual group at the 1.8.0 proximity threshold and separate again after zooming in.

**Acceptance Scenarios**:

1. **Given** different locations whose projected pins are nearly coincident, **when** the map is
   zoomed out, **then** they render as one multi-location marker without individual titles.
2. **Given** those locations after the map is zoomed in, **when** their projected pins are farther
   apart than the grouping threshold, **then** each renders as a single-location marker.
3. **Given** visible single-location markers whose captions would collide, **when** labels are laid
   out, **then** MapLibre may suppress a caption instead of drawing captions on top of each other.

### User Story 2 — Distinguish a group from one location (Priority: P1)

As a map visitor, I can tell at a glance whether tapping a marker opens one exhibition or a list of
nearby exhibitions.

**Independent Test**: Render a singleton and a projected group together and confirm that the group
uses a stacked-pin glyph with a numeric count while the singleton uses one pin and its caption.

**Acceptance Scenarios**:

1. **Given** one exhibition at a visual location, **when** it renders, **then** it uses one pin and a
   collision-aware localized caption.
2. **Given** two or more exhibitions in a visual group, **when** it renders, **then** it uses stacked
   pins plus the complete exhibition count and no caption.
3. **Given** a multi-location marker containing at least one saved exhibition, **when** it renders,
   **then** its foreground pin uses the saved orange treatment while its rear pin remains black.
4. **Given** a multi-location marker containing no saved exhibitions, **when** it renders, **then**
   both stacked pins remain black.
5. **Given** a multi-location marker, **when** it is selected, **then** the existing overlap sheet
   opens with every represented exhibition.

### User Story 3 — Keep map gestures reliable (Priority: P1)

As a map visitor, I can begin a pinch over a marker without the marker stealing the gesture.

**Independent Test**: Confirm visual markers remain MapLibre-owned layers and only pointer-transparent
accessibility targets remain in Compose.

**Acceptance Scenarios**:

1. **Given** a pointer starts over a marker, **when** a second pointer begins a pinch, **then**
   MapLibre receives the gesture.
2. **Given** a single tap on a marker, **when** MapLibre resolves it as a click, **then** the marker's
   exhibition or overlap sheet opens once.
3. **Given** assistive technology, **when** a marker receives focus, **then** its localized single or
   grouped description remains available with a 44dp target.

## Requirements

### Functional Requirements

- **FR-001**: Visual grouping MUST use the 1.8.0 screen-space proximity threshold of 16dp.
- **FR-002**: Group membership MUST be recalculated from projected positions as the camera changes.
- **FR-003**: Grouping MUST be transitive so a chain of nearby pins produces one deterministic group.
- **FR-004**: A visual group MUST retain every exhibition in catalogue order.
- **FR-005**: A multi-location marker MUST use a stacked-pin glyph, numeric count, and no title.
- **FR-006**: A singleton MUST use one pin and a localized title that participates in MapLibre
  collision placement rather than forcing overlap.
- **FR-007**: Pin visuals and click handling MUST remain in MapLibre layers so multi-pointer gesture
  arbitration from 1.8.1 is preserved.
- **FR-008**: A singleton MUST use saved orange only when its exhibition is saved. A multi-location
  marker MUST use saved orange on its foreground pin when at least one represented exhibition is
  saved; its rear pin MUST remain black.
- **FR-009**: Marker actions and localized accessibility descriptions MUST remain available through
  pointer-transparent 44dp Compose semantics targets.
- **FR-010**: The UI MUST follow `DESIGN.md`: monochrome markers, saved-state orange only, no shadow
  or rounded container decoration.

## Success Criteria

- **SC-001**: Automated tests prove separate, grouped, and transitively grouped projected pins.
- **SC-002**: At a zoom where central Seoul locations project within 16dp, one counted stacked marker
  replaces their individual marker captions.
- **SC-003**: Single-marker captions never opt out of MapLibre collision handling.
- **SC-004**: Existing map click, overlap-sheet, saved-color, accessibility, zoom, and recenter
  behavior remains intact.

## Out of Scope

- Changing catalogue coordinates or merging venues in stored data.
- Changing the current-location marker, map controls, base-map style, or map tabs.
- Adding a second accent color.
