# Research: UI Polish and Uniform Theme Across Tabs

**Feature**: 015-ui-polish-uniformity
**Date**: 2026-03-24

## R1: Standardized Tab Header Style

**Decision**: Use `labelLarge` (13sp, medium weight, letter-spaced) for all tab section headers. Remove the Map screen's `titleLarge` usage.

**Rationale**: The Featured tab already uses `labelLarge` for its "FEATURED" / "추천" header. This is the gallr design language — understated, uppercase, editorial. The Map tab's `titleLarge` (24sp, bold) is visually heavy and inconsistent. The List tab currently has no header text (the segmented control serves as navigation), which is fine — the segmented control replaces the need for a separate header.

**Alternatives considered**:
- Use `titleLarge` everywhere — too heavy for the reductionist design; would clash with card title sizes.
- Use `titleMedium` as a middle ground — still heavier than the current Featured header and introduces a third style.

## R2: Hardcoded Spacing Remediation

**Decision**: Replace all hardcoded dp values in MapScreen and GallrNavigationBar with existing GallrSpacing tokens. Mapping:

| Hardcoded Value | Replacement Token | Usage |
|-----------------|-------------------|-------|
| 16.dp (padding) | GallrSpacing.screenMargin | Screen edge padding |
| 12.dp (spacer/vertical) | GallrSpacing.sm (8dp) | Use sm — 12dp is not a grid multiple; 8dp maintains the 8pt grid |
| 10.dp (button padding) | GallrSpacing.sm (8dp) | Internal button padding |
| 2.dp (spacer) | GallrSpacing.xs (4dp) | Minimal gap — use nearest grid token |
| 14.dp (nav label) | GallrSpacing.md (16dp) | Nav bar label padding — round up to grid |
| 4.dp (divider gap) | GallrSpacing.xs | Already defined |

**Rationale**: The 8pt grid system (GallrSpacing) uses multiples of 4dp: 4, 8, 16, 24, 32, 48. Values like 10dp, 12dp, 14dp are off-grid. Snapping them to the nearest grid value (8dp or 16dp) creates visual rhythm and consistency.

**Alternatives considered**:
- Add new tokens for 10dp, 12dp, 14dp — violates YAGNI and the 8pt grid principle.
- Leave hardcoded values as-is — defeats the purpose of having a design token system.

## R3: Typography Hierarchy for Exhibition Metadata

**Decision**: Establish a consistent metadata typography progression:

| Element | Card View | Detail View | Map Dialog |
|---------|-----------|-------------|------------|
| Exhibition name | titleMedium | headlineMedium | titleLarge |
| Venue name | labelMedium (uppercase) | labelLarge (uppercase) | labelMedium (uppercase) |
| Dates | labelMedium | labelLarge | labelMedium |
| City/Region | labelSmall | labelMedium | — |

**Rationale**: The detail screen intentionally uses larger typography (it has more space and is the "zoomed in" view). The card and map dialog share the same compact context so they should match. The current card uses `titleLarge` for exhibition names which is the same as the detail screen's usage — this should be `titleMedium` in the card to create a clear hierarchy between card and detail.

**Alternatives considered**:
- Make card and detail use identical sizes — loses the hierarchical progression and makes the detail feel no different from the card.
- Use completely different scales — confusing; users won't recognize the same information.
