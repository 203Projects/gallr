# Tasks: Exhibition Card Image Background

**Input**: Design documents from `/specs/019-card-image-background/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, quickstart.md

**Tests**: No test tasks — this is a UI-only change with fixed design tokens. Platform UI layers are exempt per constitution. Visual verification described per story.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

All changes are in the shared Compose UI layer:
- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/ExhibitionCard.kt` — primary file

---

## Phase 1: Setup

**Purpose**: No setup needed — no new files, dependencies, or project structure changes required. Coil 3.1.0 and `coverImageUrl` already exist.

*(Phase skipped — all infrastructure is already in place)*

---

## Phase 2: Foundational (Structural Refactor)

**Purpose**: Replace `Surface` with `Box` to enable image layering. This structural change MUST complete before any user story work because all three stories depend on the `Box`-based layout.

**Why this is foundational**: `Surface` cannot host background images. The `Box` refactor is a prerequisite for image rendering (US1), fallback styling (US2), and scrim press feedback (US3).

- [x] T001 Replace `Surface` wrapper with `Box` + `Modifier.border(1.dp, colorScheme.outline, RectangleShape)` + `Modifier.clip(RectangleShape)` in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/ExhibitionCard.kt`. Move `pointerInput` modifier to the `Box`. Preserve existing content `Row` with padding inside the `Box`. Verify card renders identically to before (same border, same padding, same press behavior) — this is a pure structural refactor with no visual change.

**Checkpoint**: Card looks and behaves exactly as before. No visual diff. The `Box` structure now allows layering image and scrim beneath content.

---

## Phase 3: User Story 1 — Browse Image-Backed Exhibition Cards (Priority: P1) MVP

**Goal**: Display the exhibition's cover image as a full-bleed background with scrim overlay on cards that have a `coverImageUrl`.

**Independent Test**: Open Featured or List tab; cards with `coverImageUrl` show the installation image behind text with a semi-transparent scrim. Toggle dark/light mode to verify correct scrim color and text colors. Scroll through cards of varying title lengths to confirm image crops correctly.

### Implementation for User Story 1

- [x] T002 [US1] Add `AsyncImage` (from `coil3.compose.AsyncImage`) as the first child inside the `Box`, using `ContentScale.Crop`, `Modifier.matchParentSize()`, and `exhibition.coverImageUrl` as the model. Only render `AsyncImage` when `coverImageUrl` is non-null. Track image load success via a `var imageLoaded` state flag using `onSuccess`/`onError` callbacks in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/ExhibitionCard.kt`

- [x] T003 [US1] Add scrim overlay `Box` as the second child (between `AsyncImage` and content `Row`), using `Modifier.matchParentSize().background(scrimColor)`. Scrim color: `Color.Black.copy(alpha = 0.45f)` in dark mode, `Color.White.copy(alpha = 0.50f)` in light mode. Only render scrim when `imageLoaded` is true in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/ExhibitionCard.kt`

- [x] T004 [US1] Branch text color logic based on `hasImage` (derived from `imageLoaded` flag). When image is present: primary text = `Color.White` (dark) / `Color.Black` (light) at full opacity; secondary text = `Color.White.copy(alpha = 0.70f)` (dark) / `Color.Black.copy(alpha = 0.65f)` (light); divider = `Color.White.copy(alpha = 0.25f)` (dark) / `Color.Black.copy(alpha = 0.20f)` (light). When no image: preserve existing `animateColorAsState` invert behavior unchanged in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/ExhibitionCard.kt`

- [x] T005 [US1] Update `BookmarkButton` tint color for image cards: unfilled = `Color.White.copy(alpha = 0.40f)` (dark) / `Color.Black.copy(alpha = 0.30f)` (light); filled = `GallrAccent.activeIndicator` (#FF5400) unchanged. Pass the correct `tintColor` based on `hasImage` flag in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/ExhibitionCard.kt`

**Checkpoint**: Image-backed cards show full-bleed image + scrim + readable text in both dark and light mode. Non-image cards still work (will be refined in US2). Both Featured and List tabs display correctly since they share `ExhibitionCard`.

---

## Phase 4: User Story 2 — Graceful Fallback for Cards Without Images (Priority: P1)

**Goal**: Cards without images (null URL or failed load) render with `surfaceVariant` background to subtly differentiate them from image-backed cards.

**Independent Test**: Load exhibitions where some have null `coverImageUrl`. Verify those cards show `surfaceVariant` background (not plain `background`). Disable network and verify image load failures also fall back to `surfaceVariant`. Compare image and non-image cards side by side — difference should be subtle but noticeable.

### Implementation for User Story 2

- [x] T006 [US2] Set the `Box` background color to `MaterialTheme.colorScheme.surfaceVariant` when `hasImage` is false (covers both null URL and failed image load). When `hasImage` is true, the `Box` background can be transparent (image fills the space). This replaces the current `backgroundColor` animated color for the non-image path in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/ExhibitionCard.kt`

- [x] T007 [US2] Verify `onError` callback on `AsyncImage` sets `imageLoaded = false`, ensuring failed loads trigger the `surfaceVariant` fallback. Ensure no error icon, placeholder, or spinner is shown — Coil's default error/loading composables should be set to null or empty in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/ExhibitionCard.kt`

**Checkpoint**: Cards with null `coverImageUrl` show `surfaceVariant` background. Cards where image fails to load also show `surfaceVariant`. No broken image states, spinners, or error indicators anywhere.

---

## Phase 5: User Story 3 — Press Feedback on Image Cards (Priority: P2)

**Goal**: Long-pressing an image card darkens the scrim instead of inverting the background. Non-image cards retain existing press behavior.

**Independent Test**: Long-press an image card in dark mode — scrim should darken from 45% to 68%. Long-press in light mode — scrim should darken from 50% to 72%. Long-press a non-image card — existing background invert animation should still work.

### Implementation for User Story 3

- [x] T008 [US3] Animate scrim alpha between normal and pressed states using `animateFloatAsState` (or `animateColorAsState`). Dark mode: `0.45f` → `0.68f` on press. Light mode: `0.50f` → `0.72f` on press. Use the existing `isPressed` state and `tween(GallrMotion.pressDurationMs)` animation spec. Only apply scrim-darken behavior when `hasImage` is true in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/ExhibitionCard.kt`

- [x] T009 [US3] For non-image cards (`hasImage == false`): preserve the existing animated background-invert behavior using the current `animateColorAsState` for `backgroundColor` and `contentColor`. The press state should work exactly as it does today for these cards in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/ExhibitionCard.kt`

**Checkpoint**: Press feedback is visually distinct between image and non-image cards. Image cards: scrim darkens. Non-image cards: background inverts. Both animate at 100ms.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final cleanup and verification across both platforms and modes

- [x] T010 Remove any unused imports introduced during refactoring (e.g., old `Surface` import if no longer used) in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/ExhibitionCard.kt`
- [x] T011 Run quickstart.md verification checklist: image cards on Featured tab, image cards on List tab, no-image cards, dark/light mode toggle, press state on both card types, variable title lengths, bookmark icon colors

---

## Dependencies & Execution Order

### Phase Dependencies

- **Foundational (Phase 2)**: No dependencies — can start immediately. BLOCKS all user stories.
- **User Story 1 (Phase 3)**: Depends on Phase 2 (Box refactor)
- **User Story 2 (Phase 4)**: Depends on Phase 3 (needs `hasImage` flag and `AsyncImage` with `onError`)
- **User Story 3 (Phase 5)**: Depends on Phase 3 (needs scrim overlay and `hasImage` flag)
- **Polish (Phase 6)**: Depends on all user stories complete

### User Story Dependencies

- **User Story 1 (P1)**: Depends on Foundational only. This is the MVP.
- **User Story 2 (P1)**: Depends on US1 (uses `imageLoaded` state and `onError` from US1's `AsyncImage`)
- **User Story 3 (P2)**: Depends on US1 (uses scrim overlay and `hasImage` flag from US1). Can run in parallel with US2.

### Within Each User Story

- All tasks are in the same file, so no [P] parallel markers within stories
- Tasks within a story are sequential (each builds on the previous)

### Parallel Opportunities

- **US2 and US3 can run in parallel** after US1 completes (both depend on US1 but not on each other)
- Within-story parallelism: none (single file)

---

## Parallel Example: After User Story 1

```text
# After US1 (Phase 3) completes, these two stories can start in parallel:
Story: "US2 — Fallback styling (T006, T007)"
Story: "US3 — Press feedback (T008, T009)"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 2: Foundational (Surface → Box refactor)
2. Complete Phase 3: User Story 1 (image + scrim + text colors)
3. **STOP and VALIDATE**: Test on both Android and iOS, both dark and light mode
4. Cards with images now show the full visual upgrade

### Incremental Delivery

1. Phase 2 → Box refactor (no visual change, pure structural)
2. Phase 3 → US1: Image backgrounds visible → **Validate MVP**
3. Phase 4 → US2: Fallback cards polished with surfaceVariant → **Validate**
4. Phase 5 → US3: Press feedback refined → **Validate**
5. Phase 6 → Cleanup and full verification

---

## Notes

- All 11 tasks modify a single file: `ExhibitionCard.kt`
- No new dependencies, no new files, no build configuration changes
- The `hasImage` flag (derived from `imageLoaded` state) is the central branching condition
- `isSystemInDarkTheme()` determines which scrim color/alpha set to use
- Commit after each phase checkpoint for clean rollback points
