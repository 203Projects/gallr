# Tasks: Reductionist Design System

> **Status: Implemented historical plan.** The design-system implementation
> shipped in commit `562acf3`. The remaining unchecked box records an original
> manual acceptance pass whose evidence was not captured; it is not an active
> roadmap item. Current visual work follows `DESIGN.md` and its affected-surface
> verification contract.

**Input**: Design documents from `/specs/005-reductionist-design-system/`
**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, quickstart.md ✅

**Tests**: No test tasks generated — this feature is a UI-layer visual update with no new shared-module logic. Acceptance is via visual inspection per quickstart.md and SC-001–SC-007.

**Organization**: Tasks grouped by user story for independent delivery.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: User story this task belongs to
- All paths are relative to repository root

---

## Phase 1: Setup (Font Assets)

**Purpose**: Obtain and place Inter TTF files — required before any typography work can begin.

- [x] T001 Download Inter static TTF files (Regular 400, Medium 500, Bold 700) from https://fonts.google.com/specimen/Inter and add to `composeApp/src/commonMain/composeResources/font/` as `Inter_Regular.ttf`, `Inter_Medium.ttf`, `Inter_Bold.ttf`

**Checkpoint**: All three TTF files present in `composeResources/font/` — Phase 2 can begin.

---

## Phase 2: Foundational (Token Updates — Blocks All User Stories)

**Purpose**: Extend the base token objects so every user story's components have the tokens they need.

**⚠️ CRITICAL**: No user story component work can begin until T002 and T003 are complete.

- [x] T002 [P] Add accent token and three semantic role aliases (`ctaPrimary`, `activeIndicator`, `interactionFeedback`) to `composeApp/src/commonMain/kotlin/com/gallr/app/ui/theme/GallrColors.kt`
- [x] T003 [P] Slim `composeApp/src/commonMain/kotlin/com/gallr/app/ui/theme/GallrMotion.kt` — remove `staggeredRevealMs`, `staggerDelayMs`, and `slideDistanceDp` constants; retain only `pressResponseMs = 100`

**Checkpoint**: `GallrColors` has orange tokens, `GallrMotion` has no stagger constants — user story phases can now proceed.

---

## Phase 3: User Story 1 — Editorial Identity on Launch (Priority: P1) 🎯 MVP

**Goal**: All three tabs render with neo-grotesque Inter typography, strict monochrome palette, sharp corners, and zero decorative elements — immediately visible on first launch.

**Independent Test**: Launch the app; open Featured, List, and Map tabs in sequence. Confirm: only Inter typeface visible (no serif or monospaced), no orange anywhere in resting state, no shadows, no gradients, no rounded corners. All content-zone separators are whitespace only.

### Implementation for User Story 1

- [x] T004 [US1] Replace all font family references in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/theme/GallrTypography.kt` with Inter (Regular 400, Medium 500, Bold 700) per the type scale in data-model.md; remove PlayfairDisplay and SourceSerif4 references
- [x] T005 [US1] Update `composeApp/src/commonMain/kotlin/com/gallr/app/ui/theme/GallrTheme.kt` to wire the updated `GallrTypography` and verify `GallrColors` monochrome tokens are correctly applied to MaterialTheme text styles
- [x] T006 [P] [US1] Update exhibition card composable in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/` — apply Inter-based `titleLarge`/`bodyMedium`/`labelSmall` typography styles; confirm zero border radius and no shadow/elevation
- [x] T007 [P] [US1] Audit Featured tab screen in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/` — remove any decorative dividers, ornamental rules, or background fills between content zones; replace with `Spacer` using `GallrSpacing.xl` (add spacing constant as needed, or hardcode 32.dp until T013)
- [x] T008 [P] [US1] Audit List tab screen in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/` — same decoration removal as T007; confirm section header uses `labelLarge` Inter style

**Checkpoint**: Launch app — US1 independently testable. All three tabs show Inter typeface, monochrome only, no decoration.

---

## Phase 4: User Story 2 — Active State and Navigation Feedback (Priority: P2)

**Goal**: Active tab indicator and selected filter chips display #FF5400 immediately on activation; all other elements remain strictly monochrome.

**Independent Test**: Tap each tab — active tab indicator turns orange; all others are monochrome. Tap a filter chip on the List tab — it activates in orange; all others remain monochrome. No other orange elements visible anywhere.

### Implementation for User Story 2

- [x] T009 [US2] Update bottom navigation bar composable in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/` — set selected tab indicator to `GallrColors.activeIndicator`; unselected tabs use `GallrColors.grayText`
- [x] T010 [US2] Update filter chip composable (or inline in List tab) in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/` — set `selectedContainerColor` to `GallrColors.activeIndicator`, `selectedLabelColor` to `GallrColors.white`; unselected chips use `GallrColors.black` outline / `GallrColors.white` fill

**Checkpoint**: Navigation and filter chips show orange active state; US1 visual identity unaffected.

---

## Phase 5: User Story 3 — Primary CTA Clarity (Priority: P2)

**Goal**: Primary CTA (bookmark or primary action button) is the sole orange element on any given screen. Pressed state responds via opacity shift within 100ms. Disabled states are monochrome and subdued.

**Independent Test**: Open exhibition detail or the screen with a primary action. Confirm: primary CTA is the only orange-colored element. Tap and hold — button visibly darkens/shifts opacity within 100ms. Disable the action (if applicable) — control turns gray.

### Implementation for User Story 3

- [x] T011 [US3] Update primary CTA button (bookmark button and any primary action) in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/` — set default `containerColor` to `GallrColors.ctaPrimary`, `contentColor` to `GallrColors.white`; pressed state uses `GallrColors.interactionFeedback` at 0.7 alpha via `interactiveSource` or `indication`
- [x] T012 [US3] Update disabled states for CTA button and all interactive controls in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/` — set `disabledContainerColor` to `GallrColors.disabled` at 0.4 alpha, `disabledContentColor` to `GallrColors.grayText`; confirm no orange appears in disabled state
- [x] T013 [P] [US3] Audit all screens in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/` — confirm no secondary or tertiary controls use any orange token; replace any accidental accent usage with monochrome equivalents

**Checkpoint**: Primary CTAs are orange; all secondary controls are monochrome; disabled states are clearly subdued.

---

## Phase 6: User Story 4 — Grid-Driven Exhibition Layout (Priority: P3)

**Goal**: All cards and layout zones snap to an 8pt grid with consistent, generous spacing. Whitespace is the sole zone separator. Typographic scale (size + weight) is the only hierarchy signal within cards.

**Independent Test**: View Featured or List tab — cards align to a consistent column grid with equal gutters; spacing between and within cards matches the 8pt grid. No decorative lines, bands, or fills separate sections.

### Implementation for User Story 4

- [x] T014 [US4] Create `composeApp/src/commonMain/kotlin/com/gallr/app/ui/theme/GallrSpacing.kt` with all 8pt grid tokens per data-model.md (`xs=4.dp`, `sm=8.dp`, `md=16.dp`, `lg=24.dp`, `xl=32.dp`, `xxl=48.dp`, `gutterWidth=8.dp`, `screenMargin=16.dp`)
- [x] T015 [US4] Apply `GallrSpacing` tokens to screen edge padding in Featured tab — `Modifier.padding(horizontal = GallrSpacing.screenMargin)` on content containers in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/`
- [x] T016 [P] [US4] Apply `GallrSpacing` tokens to exhibition card internal padding and inter-card spacing in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/` — card padding `GallrSpacing.md`, item gap `GallrSpacing.lg`
- [x] T017 [P] [US4] Apply `GallrSpacing` tokens to List tab screen margins and section spacers in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/`; replace any hardcoded `Dp` values with GallrSpacing references

**Checkpoint**: All three tabs use GallrSpacing tokens; no hardcoded `Dp` values in layout code; grid visually consistent.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Remove any residual stagger/slide animation code, replace any hardcoded spacing remainders, validate the full acceptance criteria from quickstart.md.

- [x] T018 [P] Search all composables in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/` for usages of removed `GallrMotion` constants (`staggeredRevealMs`, `staggerDelayMs`, `slideDistanceDp`) and replace with direct, instant composition or `AnimatedVisibility` with zero duration
- [x] T019 [P] Replace any hardcoded color literals (`Color(0xFF...)`) in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/` with the appropriate `GallrColors` token
- [ ] T020 Run the quickstart.md acceptance criteria — open all 3 tabs, verify SC-001 through SC-007 pass; document any failures as issues ⚠️ MANUAL STEP — requires T001 (Inter fonts) to build

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies — start immediately
- **Phase 2 (Foundational)**: Depends on Phase 1 (fonts must exist before referencing them in typography)
- **Phase 3 (US1)**: Depends on Phase 2 completion — T004 requires Inter TTF (T001) and GallrColors monochrome tokens (T002)
- **Phase 4 (US2)**: Depends on Phase 2 completion — requires accent tokens (T002)
- **Phase 5 (US3)**: Depends on Phase 2 completion — requires ctaPrimary/interactionFeedback tokens (T002)
- **Phase 6 (US4)**: Depends on Phase 3 completion (typography applied) — GallrSpacing is independent but layout polish builds on US1 visuals
- **Phase 7 (Polish)**: Depends on all story phases complete

### User Story Dependencies

- **US1 (P1)**: After Phase 2 — no dependency on US2/US3/US4
- **US2 (P2)**: After Phase 2 — no dependency on US1/US3/US4
- **US3 (P2)**: After Phase 2 — no dependency on US1/US2/US4
- **US4 (P3)**: After Phase 3 (recommended) — GallrSpacing is independent but visual coherence requires Inter typography from US1

### Parallel Opportunities

- **Phase 2**: T002 and T003 are fully parallel (different files)
- **Phase 3**: T006, T007, T008 are parallel after T004+T005 complete
- **Phase 4**: T009 and T010 are parallel
- **Phase 5**: T011, T012, T013 — T011 and T013 are parallel; T012 depends on T011
- **Phase 6**: T015, T016, T017 are parallel after T014; T014 must complete first
- **Phase 7**: T018 and T019 are parallel

---

## Parallel Example: User Story 1

```
# After T004 + T005 complete (GallrTypography + GallrTheme updated):
Parallel:
  T006 — Update ExhibitionCard composable (components/)
  T007 — Audit Featured tab decoration (tabs/)
  T008 — Audit List tab decoration (tabs/)
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1: Add Inter font files
2. Phase 2: Add accent tokens + slim GallrMotion
3. Phase 3: Update GallrTypography → GallrTheme → Card + Tab audits
4. **STOP and VALIDATE**: Launch app — all tabs show Inter, monochrome, no decoration
5. US1 is shippable independently

### Incremental Delivery

1. Setup + Foundational → fonts in place, color tokens ready
2. US1 → Inter typography + monochrome identity → **visual MVP**
3. US2 → Orange active states on navigation + filters
4. US3 → Orange primary CTA, state clarity
5. US4 → Grid spacing codified in GallrSpacing
6. Polish → remove residuals, validate all criteria

---

## Notes

- No test tasks generated — this is a visual design system update; acceptance via quickstart.md criteria
- All changes are in `composeApp/commonMain` — zero `shared/` module changes
- Do NOT use orange for anything other than `ctaPrimary`, `activeIndicator`, `interactionFeedback` tokens
- Do NOT delete existing serif/mono TTF font files in this branch (deferred to future cleanup)
- Commit after each checkpoint to enable incremental review
