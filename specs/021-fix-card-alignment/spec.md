# Feature Specification: Fix Exhibition Card Alignment

**Feature Branch**: `021-fix-card-alignment`
**Created**: 2026-03-27
**Status**: Draft
**Input**: User description: "make a ui fix on exhibition card visible on featured and list screen. top of heart should align with exhibition name. Also divider, upcoming and heart should all align on the right end of the card."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Heart Icon Top-Aligns with Exhibition Name (Priority: P1)

A user browses exhibitions on the Featured or List tab. The bookmark heart icon on each card is visually aligned so that its top edge sits at the same vertical position as the first line of the exhibition name text. Currently the heart appears slightly offset due to internal padding in the bookmark button component.

**Why this priority**: The heart-to-name misalignment is the most visible inconsistency on every card. It affects every single card the user sees.

**Independent Test**: Open any screen showing exhibition cards. Visually confirm that the top of the heart icon aligns horizontally with the top of the exhibition name text on every card.

**Acceptance Scenarios**:

1. **Given** an exhibition card with a single-line title, **When** the card renders, **Then** the top edge of the heart icon aligns with the top of the title text
2. **Given** an exhibition card with a two-line title, **When** the card renders, **Then** the heart icon remains top-aligned with the first line of the title
3. **Given** the app is in dark mode, **When** a card renders, **Then** the alignment is identical to light mode
4. **Given** a card with an image background, **When** the card renders, **Then** the alignment is identical to a card without an image

---

### User Story 2 - Divider Extends Full Card Width (Priority: P1)

A user views an exhibition card. The horizontal divider line between the venue/city metadata and the date range extends across the full width of the card's content area, reaching from the left text edge to the right edge where the heart icon sits, rather than stopping short at the text column boundary.

**Why this priority**: The divider currently stops before the heart column, creating an unfinished visual line that looks unintentional.

**Independent Test**: Open any screen showing exhibition cards. Confirm the hairline divider spans the full content width of the card, not just the left text column.

**Acceptance Scenarios**:

1. **Given** an exhibition card on the Featured tab, **When** the card renders, **Then** the divider extends from the left content edge to the right content edge (same width as the full content row)
2. **Given** an exhibition card on the List tab, **When** the card renders, **Then** the divider has the same full-width treatment

---

### User Story 3 - Upcoming Label and Heart Right-Align to Card Edge (Priority: P1)

A user views an upcoming exhibition card. The "Upcoming" / "오픈 예정" orange label and the bookmark heart icon are both right-aligned to the same right edge of the card's content area, creating a clean vertical right margin.

**Why this priority**: When the Upcoming label and heart are at different horizontal positions, the right side of the card looks ragged and unpolished.

**Independent Test**: Open the app with at least one upcoming exhibition visible. Confirm that the right edge of the "Upcoming" label and the right edge of the heart icon align to the same horizontal position.

**Acceptance Scenarios**:

1. **Given** an upcoming exhibition card, **When** the card renders, **Then** the right edge of the "Upcoming" label aligns with the right edge of the heart icon above it
2. **Given** a non-upcoming exhibition card (no label shown), **When** the card renders, **Then** the date range text remains left-aligned and the heart icon remains right-aligned at the card's right edge
3. **Given** the app is in Korean language mode, **When** an upcoming card renders, **Then** the "오픈 예정" label right-aligns identically to the English "Upcoming" label

---

---

### User Story 4 - Toggle Group Height Matches Between List and Map (Priority: P1)

A user switches between the List tab and the Map tab. The "전체 전시 / 내 전시" toggle on the List tab and the "내 전시 / 전체" toggle on the Map tab should have the same visual height and style. Currently the List toggle appears tight/cramped while the Map toggle is taller.

**Why this priority**: Inconsistent toggle heights between tabs makes the app feel unfinished.

**Independent Test**: Switch between List and Map tabs. Compare the toggle group height visually. They should appear identical.

**Acceptance Scenarios**:

1. **Given** the user views the List tab, **When** the toggle group renders, **Then** it has the same height as the Map tab's toggle group
2. **Given** the user views the Map tab, **When** the toggle group renders, **Then** it has the same height as the List tab's toggle group
3. **Given** both toggles are visible in sequence, **When** the user switches tabs, **Then** the style (underline indicator, font weight, colors) is identical

---

### User Story 5 - Compact Search Bar with Magnifier Icon (Priority: P2)

A user views the List tab. The search bar ("전시 검색") is more compact in height than it currently is, and displays a magnifier icon on the right end when the search field is empty, clearly indicating search functionality. When text is entered, the magnifier is replaced by a clear (✕) button.

**Why this priority**: The search bar takes up too much vertical space and lacks a visual affordance for search when empty.

**Independent Test**: Open the List tab. Confirm the search bar is visually compact. Confirm a magnifier icon appears on the right when the field is empty. Enter text and confirm the magnifier is replaced by a ✕ clear button.

**Acceptance Scenarios**:

1. **Given** the search field is empty, **When** the List tab renders, **Then** a magnifier icon is visible at the right end of the search field
2. **Given** the user types text into the search field, **When** text is present, **Then** the magnifier icon is replaced by a ✕ clear button
3. **Given** the search field, **When** the List tab renders, **Then** the search bar height is noticeably reduced compared to the current version

---

### Edge Cases

- What happens with a very long exhibition name (2 lines) and the heart icon? Heart stays top-aligned with the first line, does not center vertically.
- What happens with a very short single-word title? Alignment still holds, heart at top-right.
- What happens on cards without an image background? Same alignment rules apply regardless of image presence.
- What happens with a very long search query? Text scrolls horizontally, magnifier is replaced by ✕, no layout break.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The bookmark heart icon MUST be vertically aligned so its top edge matches the top of the exhibition name text on every card
- **FR-002**: The horizontal divider MUST span the full content width of the card, from the left content margin to the right content margin
- **FR-003**: The "Upcoming" / "오픈 예정" label MUST be right-aligned to the same horizontal position as the bookmark heart icon
- **FR-004**: The alignment fix MUST apply identically on both the Featured tab and the List tab
- **FR-005**: The alignment fix MUST apply identically to cards with image backgrounds and cards with solid backgrounds
- **FR-006**: The alignment fix MUST apply identically in both dark mode and light mode
- **FR-007**: No changes to the card's overall padding, border, or spacing values (only internal element positioning)
- **FR-008**: The toggle group on the List tab MUST have the same height and visual style as the toggle group on the Map tab
- **FR-009**: The search bar on the List tab MUST be reduced in height to be more compact
- **FR-010**: The search bar MUST display a magnifier icon at the right end when the search field is empty
- **FR-011**: The magnifier icon MUST be replaced by a ✕ clear button when text is entered in the search field

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The heart icon's top edge is within 1 pixel of the exhibition name text's top edge on all cards
- **SC-002**: The divider line extends to the same right boundary as the heart icon on all cards
- **SC-003**: The "Upcoming" label's right edge aligns within 1 pixel of the heart icon's right edge
- **SC-004**: Visual consistency is maintained across Featured tab, List tab, dark mode, light mode, image cards, and solid cards (6 combinations, all identical alignment)
- **SC-005**: The List tab toggle and Map tab toggle have identical rendered heights
- **SC-006**: The search bar is visually more compact than the previous version
- **SC-007**: A magnifier icon is visible when the search field is empty, replaced by ✕ when text is present

## Assumptions

- The exhibition card component is shared between Featured and List tabs, so a single fix applies to both screens automatically
- The bookmark heart icon's intrinsic size and tap target remain unchanged
- The overall card padding is not modified
- This is a layout-only fix with no changes to data, colors, animations, or functionality
- The Map tab's TabRow style is the target standard for toggle consistency
