# Tasks: UI Polish and Uniform Theme Across Tabs

**Input**: Design documents from `/specs/015-ui-polish-uniformity/`
**Prerequisites**: plan.md, spec.md, research.md, quickstart.md

**Tests**: Not requested. Visual inspection only.

**Organization**: Tasks grouped by user story for independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **composeApp common**: `composeApp/src/commonMain/kotlin/com/gallr/app/`

---

## Phase 1: Setup

**Purpose**: No project setup needed. This feature modifies existing files only.

- [x] T0*1 Checkout `015-ui-polish-uniformity` branch from `develop`

---

## Phase 2: Foundational

**Purpose**: No foundational blocking work. All changes are independent per user story.

N/A — proceed directly to user stories.

---

## Phase 3: User Story 1 — Uniform Tab Headers (Priority: P1) MVP

**Goal**: All tab headers use the same typography and spacing so switching tabs feels cohesive.

**Independent Test**: Switch between Featured, List, and Map tabs — headers use identical text style and vertical position.

### Implementation for User Story 1

- [x] T0*2 [US1] Change Map screen header from `titleLarge` to `labelLarge` and replace hardcoded `padding(horizontal = 16.dp, vertical = 12.dp)` with `padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.sm)` in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapScreen.kt`
- [x] T0*3 [US1] Verify Featured screen header already uses `labelLarge` with `padding(horizontal = GallrSpacing.screenMargin, vertical = GallrSpacing.sm)` — no changes expected in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/featured/FeaturedScreen.kt`

**Checkpoint**: Featured and Map tabs have matching header style. List tab uses segmented control as its header (intentionally different pattern, not a section label).

---

## Phase 4: User Story 2 — Consistent Spacing Using Design Tokens (Priority: P2)

**Goal**: All hardcoded dp values in UI screens replaced with GallrSpacing tokens.

**Independent Test**: Search for hardcoded `.dp` values in UI screen files — only theme definition files should contain dp literals.

### Implementation for User Story 2

- [x] T0*4 [P] [US2] Replace all remaining hardcoded dp values in MapScreen: `padding(horizontal = 16.dp)` → `GallrSpacing.screenMargin`, `Spacer(Modifier.height(12.dp))` → `GallrSpacing.sm`, `padding(16.dp)` → `GallrSpacing.screenMargin` in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapScreen.kt`
- [x] T0*5 [P] [US2] Replace MapModeButton internal `padding(vertical = 10.dp)` with `GallrSpacing.sm` in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapScreen.kt`
- [x] T0*6 [P] [US2] Replace GallrNavigationBar label `padding(vertical = 14.dp)` with `GallrSpacing.md` in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/GallrNavigationBar.kt`
- [x] T0*7 [P] [US2] Replace bottom sheet `Spacer(Modifier.height(2.dp))` with `GallrSpacing.xs` in MapScreen bottom sheet section in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapScreen.kt`

**Checkpoint**: Zero hardcoded spacing values in MapScreen and GallrNavigationBar. All use GallrSpacing tokens.

---

## Phase 5: User Story 3 — Consistent Typography Hierarchy (Priority: P2)

**Goal**: Exhibition name, venue, dates use a consistent typography progression across card, detail, and map dialog.

**Independent Test**: View exhibition card, tap into detail, check map dialog — typography follows the defined hierarchy (card=titleMedium, detail=headlineMedium, dialog=titleLarge for names).

### Implementation for User Story 3

- [x] T0*8 [US3] Change exhibition name from `titleLarge` to `titleMedium` in ExhibitionCard in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/ExhibitionCard.kt`
- [x] T0*9 [US3] Verify ExhibitionDetailScreen uses `headlineMedium` for name, `labelLarge` for venue/dates — confirm matches hierarchy in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/detail/ExhibitionDetailScreen.kt`
- [x] T0*10 [US3] Verify Map marker dialog uses `titleLarge` for name, `labelMedium` for venue/dates — confirm matches card proportions in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapScreen.kt`

**Checkpoint**: Typography hierarchy is consistent: card (compact) → dialog (compact) → detail (expanded).

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final validation across both platforms and themes.

- [x] T0*11 Build and verify on Android device — check all tabs, card, detail, map dialog
- [x] T0*12 Build and verify on iOS device — check all tabs, card, detail, map dialog
- [x] T0*13 Verify dark mode renders correctly after all changes on both platforms
- [x] T0*14 Run quickstart.md acceptance test scenarios (all 6)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **US1 (Phase 3)**: Can start immediately — header changes only
- **US2 (Phase 4)**: Can start immediately — spacing token replacements only
- **US3 (Phase 5)**: Can start immediately — typography changes only
- **Polish (Phase 6)**: Depends on all user stories complete

### User Story Dependencies

- **US1, US2, US3**: All independent — can run in parallel (different concerns, mostly different code areas)
- Note: T002, T004, T005, T007 all modify MapScreen.kt — if run sequentially within MapScreen changes, they combine naturally

### Parallel Opportunities

- T004 + T005 + T007: All MapScreen spacing changes (same file but different sections)
- T006: GallrNavigationBar (independent file)
- T008: ExhibitionCard (independent file)
- T009: ExhibitionDetailScreen (independent file, read-only verify)
- T010: MapScreen dialog (verify only)

---

## Parallel Example: Spacing Fixes (US2)

```bash
# All spacing fixes can be batched together since they're in different sections:
Task: "Replace hardcoded dp in MapScreen header/mode buttons/spacers"
Task: "Replace hardcoded dp in GallrNavigationBar label padding"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete T001: Branch setup
2. Complete T002-T003: Uniform tab headers
3. **STOP and VALIDATE**: Switch between tabs — headers match
4. Deploy/demo if ready

### Full Delivery (Recommended — small scope)

1. Complete all user stories (T002-T010) — total ~30 min of changes
2. Complete polish (T011-T014)
3. All changes are low-risk token replacements — ship together

---

## Notes

- [P] tasks = different files or independent code sections
- US2 and US3 modify some of the same files as US1 (MapScreen) — recommend batching MapScreen changes together
- All changes are backwards-compatible — no API or behavior changes
- Verify both light AND dark theme after changes
