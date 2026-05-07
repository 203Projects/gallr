# Feature Specification: UI Polish and Uniform Theme Across Tabs

**Feature Branch**: `015-ui-polish-uniformity`
**Created**: 2026-03-24
**Status**: Draft
**Input**: User description: "do full code review on gallr project to establish good architecture and following good coding practice and polish ui. three tabs seem to have different size header and size themes. Update so that texts and relevant parts will have uniform ui theme."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Uniform Tab Headers (Priority: P1)

A user navigating between the three main tabs (Featured, List, Map) sees consistent header sizing, typography, and spacing. Currently, the Featured tab uses a small label-style header, the List tab has no section header, and the Map tab uses a large title-style header. After this update, all tabs present their section headers with the same text style, size, and vertical spacing, creating a cohesive feel when switching between views.

**Why this priority**: The header mismatch is the most visually jarring inconsistency. It's immediately noticeable when switching tabs and makes the app feel unfinished.

**Independent Test**: Can be tested by switching between all three tabs and visually confirming headers use identical typography, padding, and alignment.

**Acceptance Scenarios**:

1. **Given** the user is on the Featured tab, **When** they look at the section header, **Then** it uses the same text style and spacing as the List and Map tab headers.
2. **Given** the user switches from Featured to List to Map, **When** they compare the top content area, **Then** the vertical position where content begins is the same across all tabs.
3. **Given** the app is in both light and dark mode, **When** headers are displayed, **Then** they use the same themed colors consistently.

---

### User Story 2 - Consistent Spacing Using Design Tokens (Priority: P2)

A developer reviewing the codebase finds that all spacing values reference the established design token system rather than hardcoded pixel values. Currently, the Map screen uses hardcoded values (16.dp, 12.dp, 10.dp) while the Featured and List screens correctly use spacing tokens. After this update, all screens use the defined spacing tokens, making the design system authoritative and future changes to spacing propagate uniformly.

**Why this priority**: Hardcoded values undermine the design token system. While less visible to users than header inconsistency, this creates maintenance burden and risks further drift as features are added.

**Independent Test**: Can be tested by searching the codebase for hardcoded dp values in UI files and confirming all spacing references use the token system.

**Acceptance Scenarios**:

1. **Given** the Map screen, **When** a developer inspects the padding values, **Then** all spacing references use the established token system instead of hardcoded values.
2. **Given** any UI component file, **When** padding or spacing is applied, **Then** it uses design tokens rather than arbitrary pixel values.

---

### User Story 3 - Consistent Typography Hierarchy for Exhibition Data (Priority: P2)

A user viewing an exhibition card and then tapping into its detail screen sees a logical progression of typography. Currently, venue names use different text sizes in the card vs the detail screen, and date/metadata styling varies between screens. After this update, there is a clear, documented typography pairing rule: exhibition names, venue names, dates, and metadata use consistent styles across card and detail views.

**Why this priority**: Inconsistent metadata sizing creates a subtle but real sense of visual dissonance. Users build expectations from the card that should carry through to the detail view.

**Acceptance Scenarios**:

1. **Given** an exhibition card shows the venue name in one text style, **When** the user taps into the detail view, **Then** the venue name uses a proportionally appropriate style from the same hierarchy.
2. **Given** the card shows date/metadata in a specific style, **When** the same information appears in the detail screen, **Then** it uses a consistent style from the established hierarchy.
3. **Given** the Map marker dialog shows exhibition metadata, **When** compared to the card and detail, **Then** metadata typography follows the same rules.

---

### Edge Cases

- What happens when text is very long (e.g., exhibition name wraps to multiple lines)? Headers and content must handle wrapping gracefully without breaking alignment.
- What happens on different screen sizes (phones vs tablets)? The spacing token system must produce visually appropriate results on all devices.
- What happens in both light and dark themes? All fixed elements must remain legible in both themes.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: All three tab screens (Featured, List, Map) MUST use the same text style for their primary section headers.
- **FR-002**: All spacing and padding in UI screens MUST reference the established design token system. No hardcoded pixel values for layout spacing.
- **FR-003**: Exhibition name, venue name, date, and metadata MUST follow a consistent typography hierarchy across card, detail, and map dialog views.
- **FR-004**: The navigation bar label padding MUST use a spacing token from the design system rather than an arbitrary value.
- **FR-005**: Divider styling (thickness, color) MUST be consistent across all screens that use dividers.
- **FR-006**: No visual regressions — all existing screens MUST continue to render correctly in both light and dark themes after changes.
- **FR-007**: The design token system MUST be the single source of truth for all spacing values used in the app's UI layer.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Zero hardcoded spacing values (dp literals) in any UI screen file — 100% design token usage for layout spacing.
- **SC-002**: All three tab headers use identical typography and padding — verifiable by visual comparison.
- **SC-003**: Exhibition metadata (venue, dates) uses the same typography token in card view, detail view, and map dialog.
- **SC-004**: Zero visual regressions in either light or dark theme across all screens after changes.
- **SC-005**: All changes pass build verification on both Android and iOS platforms.

## Assumptions

- The existing design token system (GallrSpacing, GallrTypography) is sufficient and does not need new tokens added — only consistent usage of existing tokens is needed. If a commonly used value (e.g., 12.dp, 10.dp) is not represented by an existing token, the nearest token should be used or a new token may be added to the system.
- This is a UI-only change with no data model, networking, or business logic modifications.
- The Map screen's hardcoded values were likely introduced during rapid development and are not intentional design decisions.
- The detail screen's larger typography for venue/dates is intentional hierarchy (detail shows more emphasis) — but the tokens used should still come from a consistent progression (e.g., card uses labelMedium → detail uses labelLarge, not an arbitrary mix).
