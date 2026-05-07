# Feature Specification: Exhibition Card Image Background

**Feature Branch**: `019-card-image-background`
**Created**: 2026-03-26
**Status**: Draft
**Input**: User description: "Add installation view image as exhibition card background with scrim overlay for visual context"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Browse Image-Backed Exhibition Cards (Priority: P1)

A user opens the app and browses exhibitions in either the Featured or List tab. Each exhibition card that has a cover image displays that image as a full-bleed background behind the card content. A semi-transparent scrim overlay ensures all text (title, venue, city, date) remains clearly readable. The user gets immediate visual context about each exhibition without tapping into the detail view.

**Why this priority**: This is the core value proposition of the feature. Without image backgrounds, the feature has no reason to exist. It directly addresses the problem of flat, text-only cards lacking visual engagement.

**Independent Test**: Can be fully tested by loading any exhibition with a cover image and verifying the image fills the card, scrim is visible, and all text is readable. Delivers the primary visual upgrade.

**Acceptance Scenarios**:

1. **Given** an exhibition has a cover image URL, **When** the card renders on the Featured tab, **Then** the image fills the entire card area (cropped to fit, no letterboxing) with a scrim overlay, and all text is readable
2. **Given** an exhibition has a cover image URL, **When** the card renders on the List tab, **Then** the same image background and scrim treatment is applied identically
3. **Given** the app is in dark mode, **When** an image card renders, **Then** the scrim is black at 45% opacity and all text is white
4. **Given** the app is in light mode, **When** an image card renders, **Then** the scrim is white at 50% opacity and all text is black
5. **Given** a card has varying content height (1-line vs 2-line title), **When** the card renders, **Then** the image scales and crops to match the card's actual height without clipping issues

---

### User Story 2 - Graceful Fallback for Cards Without Images (Priority: P1)

A user browses exhibitions where some cards have no cover image (null URL or failed network load). These cards render with a subtly distinct solid background (surface variant) that differentiates them from image-backed cards at a glance. There is no broken image state, no placeholder spinner, and no visual regression from the current experience.

**Why this priority**: Equal to P1 because a broken fallback would degrade the existing experience. Users must never see an error state or broken layout when an image is unavailable.

**Independent Test**: Can be fully tested by loading an exhibition with no cover image URL and verifying the card renders with a surface variant background, all text is readable, and the card border is preserved.

**Acceptance Scenarios**:

1. **Given** an exhibition has no cover image URL (null), **When** the card renders, **Then** it displays with a surface variant background color that is subtly distinct from image-backed cards
2. **Given** an exhibition's cover image fails to load (network error), **When** the card renders, **Then** it falls back silently to the same surface variant solid background — no error message, spinner, or broken image icon
3. **Given** cards with and without images appear in the same list, **When** the user scrolls, **Then** the visual distinction between the two types is subtle but noticeable

---

### User Story 3 - Press Feedback on Image Cards (Priority: P2)

A user long-presses an image-backed exhibition card. Instead of the current background-invert animation, the scrim darkens to provide clear press feedback while keeping the image visible underneath. This gives tactile visual confirmation of the interaction without jarring color inversions.

**Why this priority**: Press feedback is important for interaction quality but is secondary to the core visual display. The app remains fully functional without custom press feedback.

**Independent Test**: Can be fully tested by long-pressing an image card and verifying the scrim darkens appropriately in both dark and light mode.

**Acceptance Scenarios**:

1. **Given** a user is viewing an image-backed card in dark mode, **When** they long-press the card, **Then** the scrim darkens from 45% to 68% black opacity
2. **Given** a user is viewing an image-backed card in light mode, **When** they long-press the card, **Then** the scrim darkens from 50% to 72% white opacity
3. **Given** a user is viewing a card without an image, **When** they long-press the card, **Then** the existing press behavior is preserved (no scrim-based feedback applies)

---

### Edge Cases

- What happens when the cover image URL is null? Card renders with surface variant solid background — no broken state
- What happens when the image fails to load due to network error? Falls back silently to surface variant background, same as null URL
- What happens with a very long exhibition title (2+ lines)? Card height grows; image crops to match the taller card
- What happens with very short content (1-line title)? Card is shorter; image still fills and crops correctly
- What happens with a very dark image in dark mode? The dark scrim + white text combination ensures readability regardless of image content
- What happens with a very bright/white image in light mode? The white scrim + black text combination ensures readability regardless of image content
- What happens to the bookmark icon on image cards? Bookmark heart remains orange when saved; unfilled icon uses semi-transparent white (dark mode) or black (light mode) — no change in behavior
- What happens to the card border? The 1dp card border is preserved in both image and non-image states

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST display the exhibition's cover image as a full-bleed background on the card, cropped to fill the card bounds without letterboxing
- **FR-002**: System MUST apply a semi-transparent scrim overlay on top of the image to ensure text readability
- **FR-003**: In dark mode, the scrim MUST be black at 45% opacity in normal state and 68% opacity in pressed state
- **FR-004**: In light mode, the scrim MUST be white at 50% opacity in normal state and 72% opacity in pressed state
- **FR-005**: All primary text (title, date) MUST be fully opaque — white in dark mode, black in light mode
- **FR-006**: All secondary text (venue, city) MUST use reduced opacity — white at 70% in dark mode, black at 65% in light mode
- **FR-007**: The hairline divider MUST use white at 25% opacity in dark mode, black at 20% opacity in light mode
- **FR-008**: The unfilled bookmark icon MUST use white at 40% opacity in dark mode, black at 30% opacity in light mode; the filled bookmark icon MUST remain orange (#FF5400) unchanged
- **FR-009**: System MUST apply the image background treatment to exhibition cards on both the Featured tab and the List tab
- **FR-010**: When cover image URL is null, the card MUST render with a surface variant background that is subtly distinct from image-backed cards
- **FR-011**: When an image fails to load (network error), the card MUST fall back silently to the same surface variant background as null-image cards — no error indicators shown
- **FR-012**: The 1dp card border MUST be preserved on all cards regardless of whether an image is present
- **FR-013**: Press state on image cards MUST darken the scrim (not invert the background) to provide interaction feedback
- **FR-014**: Press behavior on non-image cards MUST remain unchanged from current behavior
- **FR-015**: Text contrast on image cards MUST meet WCAG AA standards (minimum 4.5:1 contrast ratio) — the specified scrim opacities are pre-validated for this

### Key Entities

- **Exhibition**: Existing entity; the relevant attribute is the cover image URL (may be null) which determines whether a card shows an image background or solid fallback
- **Card Display Mode**: Two visual states — image-backed (with scrim) and solid-background (surface variant fallback) — determined by availability of cover image

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All exhibition cards with a cover image display the image as a full-bleed background with scrim overlay — 100% of image-backed cards render correctly
- **SC-002**: No visual regressions on cards where the cover image is unavailable — fallback cards render with surface variant background identical across both tabs
- **SC-003**: Text on image-backed cards passes WCAG AA contrast requirements (4.5:1 minimum) in both dark and light mode
- **SC-004**: Press feedback on image cards visibly darkens the scrim without any background inversion artifacts
- **SC-005**: Image cards and non-image cards coexist seamlessly in the same list with no layout shifts, broken states, or loading indicators
- **SC-006**: Feature works consistently on both supported platforms (Android and iOS) with no platform-specific visual discrepancies

## Assumptions

- The cover image URL field already exists on the exhibition data model and is populated for some exhibitions
- Image loading infrastructure is already configured and working in the app for both platforms
- The exhibition card component is shared between Featured and List tabs (changes apply to both automatically)
- The specified scrim opacity values have been validated by design for WCAG AA compliance
- Web platform is out of scope — this feature applies to the mobile app only
- No changes are needed to the exhibition detail screen
- No new image upload or management capabilities are required (data pipeline concern)
- Post-MVP enhancements (crossfade animation, gradient scrim) are explicitly deferred
