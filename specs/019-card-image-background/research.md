# Research: Exhibition Card Image Background

**Feature**: 019-card-image-background
**Date**: 2026-03-26

## Research Summary

No NEEDS CLARIFICATION items existed in the Technical Context. All technology choices are pre-existing in the project. Research focused on verifying assumptions and identifying the optimal implementation approach.

---

## R1: Surface vs Box for Image Background

**Decision**: Replace `Surface` with `Box` + `Modifier.border()` + `Modifier.clip()`

**Rationale**: `Surface` does not support placing an image behind its content — it only accepts a flat `color` parameter. A `Box` allows stacking `AsyncImage` → scrim `Box` → content `Row` as three layers using natural z-ordering. The 1dp border is preserved via `Modifier.border(1.dp, outline, RectangleShape)`.

**Alternatives considered**:
- Wrapping `Surface` in a `Box` with image underneath → adds unnecessary nesting; `Surface` already clips content and would obscure the image
- Using `Canvas` for custom drawing → over-engineered for a simple overlay pattern

---

## R2: Coil AsyncImage Error Handling

**Decision**: Use Coil's built-in error handling with `onError` callback to set a local state flag

**Rationale**: Coil 3.1.0's `AsyncImage` supports `onError` and `onSuccess` callbacks. When `coverImageUrl` is non-null but the image fails to load, we detect this via `onError` and render the fallback `surfaceVariant` background. The existing detail screen already uses `AsyncImage` with `ContentScale.Crop` — same pattern applies here.

**Alternatives considered**:
- `SubcomposeAsyncImage` with loading/error slots → heavier; spec explicitly says no placeholders/spinners
- `rememberAsyncImagePainter` with state checking → more boilerplate for the same result

---

## R3: Scrim Implementation

**Decision**: Single `Box` overlay with `Modifier.matchParentSize()` and animated alpha `Color`

**Rationale**: The scrim is a flat, full-card overlay (not a gradient) for MVP. A `Box` with `background(scrimColor)` layered between the image and content is the simplest approach. The scrim alpha animates between normal and pressed states using the existing `animateColorAsState` pattern already in the card.

**Alternatives considered**:
- `drawBehind` modifier on content → doesn't layer correctly with the image
- Gradient scrim (top-to-bottom) → explicitly deferred to post-MVP

---

## R4: Color Strategy for Image vs Non-Image Cards

**Decision**: Branch color logic based on `hasImage` boolean (derived from `coverImageUrl != null` AND image loaded successfully)

**Rationale**: When an image is present, text colors become fixed values (white in dark mode, black in light mode) with specific alpha values per the design spec. When no image, the existing animated invert behavior is preserved. The `hasImage` flag determines which color set to use.

**Key color mappings for image cards**:
- Dark mode: scrim = `Color.Black.copy(alpha = 0.45f)`, pressed = `0.68f`
- Light mode: scrim = `Color.White.copy(alpha = 0.50f)`, pressed = `0.72f`
- Primary text: `Color.White` (dark) / `Color.Black` (light) — full opacity
- Secondary text: `Color.White.copy(alpha = 0.70f)` (dark) / `Color.Black.copy(alpha = 0.65f)` (light)
- Divider: `Color.White.copy(alpha = 0.25f)` (dark) / `Color.Black.copy(alpha = 0.20f)` (light)
- Bookmark unfilled: `Color.White.copy(alpha = 0.40f)` (dark) / `Color.Black.copy(alpha = 0.30f)` (light)

---

## R5: Fallback Background for No-Image Cards

**Decision**: Use `MaterialTheme.colorScheme.surfaceVariant` as background for cards without images

**Rationale**: The spec requires a subtle visual differentiator between image-backed and non-image cards. `surfaceVariant` is already defined in both light (`#F5F5F5` / OffWhite) and dark (`#2C2C2C`) color schemes, providing a subtle but noticeable distinction from the standard `background` color. This requires no new color tokens.

**Alternatives considered**:
- Adding a faint dashed border → more complex to implement; spec settled on `surfaceVariant` background
- New color token → unnecessary when existing theme token serves the purpose
