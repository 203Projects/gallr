# Tasks: Comprehensive UI Improvements and Polish

**Input**: Design documents from `/specs/016-ui-improvements/`
**Prerequisites**: plan.md, spec.md, research.md, quickstart.md

**Tests**: Not requested. Visual inspection + screen reader verification.

**Organization**: Tasks grouped by user story (8 stories) for independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **shared module**: `shared/src/commonMain/kotlin/com/gallr/shared/`
- **composeApp common**: `composeApp/src/commonMain/kotlin/com/gallr/app/`

---

## Phase 1: Setup

**Purpose**: No project setup needed. Feature modifies existing codebase.

- [x] T001 Checkout `016-ui-improvements` branch from `develop`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Create shared components used by multiple user stories.

- [x] T002 Create `SkeletonCard` composable — a gray rectangle at card proportions with a pulsing alpha animation (infiniteTransition, alpha 0.08–0.24) in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/SkeletonCard.kt`
- [x] T003 Add `localizedDateRange(lang: AppLanguage): String` extension function to Exhibition — Korean format "YYYY.MM.DD – YYYY.MM.DD", English format "Mon DD – Mon DD, YYYY" — in `shared/src/commonMain/kotlin/com/gallr/shared/data/model/Exhibition.kt`

**Checkpoint**: SkeletonCard and date formatter ready for use across stories.

---

## Phase 3: User Story 1 — Pull-to-Refresh and Loading States (Priority: P1) MVP

**Goal**: Skeleton loading cards during data fetch. Pull-to-refresh on all tabs. Image placeholder on detail screen.

**Independent Test**: Slow network shows skeleton cards. Pull down on any tab refreshes data. Detail cover image shows gray placeholder while loading.

### Implementation for User Story 1

- [x] T004 [US1] Replace `GallrLoadingState` usage in FeaturedScreen with a Column of 3 `SkeletonCard` composables in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/featured/FeaturedScreen.kt`
- [x] T005 [P] [US1] Replace `GallrLoadingState` usage in ListScreen with a Column of 3 `SkeletonCard` composables in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/list/ListScreen.kt`
- [x] T006 [US1] Wrap FeaturedScreen LazyColumn content in `PullToRefreshBox` — call `viewModel.loadFeaturedExhibitions()` on refresh in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/featured/FeaturedScreen.kt`
- [x] T007 [P] [US1] Wrap ListScreen exhibition list content in `PullToRefreshBox` — call `viewModel.loadAllExhibitions()` on refresh in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/list/ListScreen.kt`
- [x] T008 [P] [US1] Wrap MapScreen content in `PullToRefreshBox` — call `viewModel.loadAllExhibitions()` on refresh in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapScreen.kt`
- [x] T009 [US1] Add `placeholder` parameter to `AsyncImage` in ExhibitionDetailScreen — use `ColorPainter(MaterialTheme.colorScheme.surfaceVariant)` as placeholder in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/detail/ExhibitionDetailScreen.kt`
- [x] T010 [US1] Add `isRefreshing` state to TabsViewModel — set true during reload, false on success/failure — expose as `StateFlow<Boolean>` in `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt`

**Checkpoint**: All tabs show skeleton cards during load, support pull-to-refresh, detail image has placeholder.

---

## Phase 4: User Story 2 — Back Gesture and Screen Transitions (Priority: P1)

**Goal**: Smooth transitions between screens. System back gesture works on detail screen. Tab switching has fade animation.

**Independent Test**: Tap card → slide/fade transition to detail. Swipe back → returns. Switch tabs → subtle fade.

### Implementation for User Story 2

- [x] T011 [US2] Add `BackHandler` to detail screen section in App.kt — on back press set `selectedExhibition = null` in `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`
- [x] T012 [US2] Wrap the `when (selectedTab)` block in `AnimatedContent` with `fadeIn(200ms) + fadeOut(200ms)` transition in `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`
- [x] T013 [US2] Wrap the detail screen / main scaffold switch with `AnimatedContent` for a slide or fade transition when `selectedExhibition` changes in `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`

**Checkpoint**: Back gesture works on detail. Tabs fade. Detail entry/exit animated.

---

## Phase 5: User Story 3 — Exhibition Search (Priority: P2)

**Goal**: Search bar on List tab filters exhibitions by name or venue name in real time.

**Independent Test**: Type a name → matching exhibitions shown. Clear → full list restored. Works with city filters.

### Implementation for User Story 3

- [x] T014 [US3] Add `searchQuery: MutableStateFlow<String>` and `setSearchQuery(query: String)` to TabsViewModel in `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt`
- [x] T015 [US3] Include `searchQuery` in the `filteredExhibitions` combine flow — filter exhibitions where `localizedName` or `localizedVenueName` contains query (case-insensitive) in `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt`
- [x] T016 [US3] Add a `TextField` search bar above the country/city row in ListScreen — collect `searchQuery` from ViewModel, call `setSearchQuery` on value change in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/list/ListScreen.kt`
- [x] T017 [US3] Update empty state message to differentiate "no search results" from "no filter results" in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/list/ListScreen.kt`

**Checkpoint**: Search bar visible on List tab. Typing filters exhibitions. Combines with city/category filters.

---

## Phase 6: User Story 4 — Settings Menu Polish (Priority: P2)

**Goal**: Settings dropdown shows all three theme options with checkmark on current selection.

**Independent Test**: Open gear menu → see Light/Dark/System with ✓ on active. Tap to change. Checkmark moves.

### Implementation for User Story 4

- [x] T018 [US4] Replace the single cycling theme menu item with three separate DropdownMenuItems (Light, Dark, System) — prefix active option with "✓ " — close menu on selection in `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`

**Checkpoint**: Settings shows all theme options with visual indicator.

---

## Phase 7: User Story 5 — Localized Date Formatting (Priority: P2)

**Goal**: Dates formatted by language setting across all screens.

**Independent Test**: Switch to EN → "Mar 19 – May 10, 2026". Switch to KO → "2026.03.19 – 2026.05.10".

### Implementation for User Story 5

- [x] T019 [P] [US5] Replace raw `"${exhibition.openingDate} – ${exhibition.closingDate}"` with `exhibition.localizedDateRange(lang)` in ExhibitionCard in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/ExhibitionCard.kt`
- [x] T020 [P] [US5] Replace raw date format with `localizedDateRange(lang)` in ExhibitionDetailScreen in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/detail/ExhibitionDetailScreen.kt`
- [x] T021 [P] [US5] Replace raw date format with `pin.openingDate`/`closingDate` formatted display in MapScreen dialog and bottom sheet — add a `localizedDateRange` helper for ExhibitionMapPin in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapScreen.kt` and `shared/src/commonMain/kotlin/com/gallr/shared/data/model/ExhibitionMapPin.kt`

**Checkpoint**: All date displays use localized format matching active language.

---

## Phase 8: User Story 6 — Enhanced Empty States and Error Messages (Priority: P3)

**Goal**: Error messages differentiate network vs server issues. Empty states have helpful guidance.

**Independent Test**: Airplane mode → "Check your internet connection". Empty My List → guidance text.

### Implementation for User Story 6

- [x] T022 [US6] Update `loadFeaturedExhibitions` and `loadAllExhibitions` error handling to check exception type — use "Check your internet connection" for network errors, "Something went wrong, please try again" for other errors in `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt`
- [x] T023 [US6] Update MapScreen My List empty message to match ListScreen's "No saved exhibitions yet. Bookmark exhibitions to see them here." pattern in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapScreen.kt`

**Checkpoint**: Error messages are specific. Empty states give guidance.

---

## Phase 9: User Story 7 — Accessibility Improvements (Priority: P3)

**Goal**: All interactive elements have meaningful accessibility labels. Text meets contrast standards.

**Independent Test**: Enable screen reader → all buttons announced. Verify contrast.

### Implementation for User Story 7

- [x] T024 [P] [US7] Add `contentDescription` to back button ("Go back") in ExhibitionDetailScreen in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/detail/ExhibitionDetailScreen.kt`
- [x] T025 [P] [US7] Update BookmarkButton to use `contentDescription = if (isBookmarked) "Remove bookmark" else "Add bookmark"` in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/ExhibitionCard.kt`
- [x] T026 [P] [US7] Verify `onSurfaceVariant` colors meet WCAG AA 4.5:1 contrast ratio against both light (#FFFFFF) and dark (#121212) backgrounds — adjust if needed in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/theme/GallrColors.kt`

**Checkpoint**: Screen reader announces all buttons meaningfully. Contrast verified.

---

## Phase 10: User Story 8 — Remove Redundant Language Toggle (Priority: P3)

**Goal**: Detail screen top bar no longer has language toggle. Settings menu is the single location.

**Independent Test**: Open detail → only back button + bookmark visible. Settings menu language toggle still works.

### Implementation for User Story 8

- [x] T027 [US8] Remove the language toggle `IconButton` from ExhibitionDetailScreen top bar actions in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/detail/ExhibitionDetailScreen.kt`
- [x] T028 [US8] Remove `onLanguageToggle` parameter from ExhibitionDetailScreen and its call site in App.kt in `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt` and `composeApp/src/commonMain/kotlin/com/gallr/app/ui/detail/ExhibitionDetailScreen.kt`

**Checkpoint**: Detail screen cleaned up. Language toggle only in settings.

---

## Phase 11: Polish & Cross-Cutting Concerns

**Purpose**: Final validation across both platforms and themes.

- [x] T029 Build and verify on Android device — run all 15 acceptance scenarios from quickstart.md
- [x] T030 Build and verify on iOS device — run all 15 acceptance scenarios from quickstart.md
- [x] T031 Verify dark mode renders correctly for all changes on both platforms
- [x] T032 Verify pull-to-refresh debouncing — multiple rapid pulls don't cause duplicate requests

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: Start immediately
- **Foundational (Phase 2)**: Depends on Setup — BLOCKS US1 (skeleton) and US5 (dates)
- **US1 (Phase 3)**: Depends on Foundational (needs SkeletonCard)
- **US2 (Phase 4)**: Independent — can start after Setup
- **US3 (Phase 5)**: Independent — can start after Setup
- **US4 (Phase 6)**: Independent — can start after Setup
- **US5 (Phase 7)**: Depends on Foundational (needs date formatter)
- **US6 (Phase 8)**: Independent — can start after Setup
- **US7 (Phase 9)**: Independent — can start after Setup
- **US8 (Phase 10)**: Independent — can start after Setup
- **Polish (Phase 11)**: Depends on all stories complete

### User Story Independence

All 8 user stories are independently testable after their dependencies are met. Recommended execution order for a single developer:

1. **Foundational** (T002-T003) — unblocks US1 and US5
2. **US1** (T004-T010) — highest impact, MVP
3. **US2** (T011-T013) — second highest impact
4. **US5** (T019-T021) — quick wins, all parallel
5. **US4** (T018) — single task
6. **US3** (T014-T017) — search feature
7. **US8** (T027-T028) — quick cleanup
8. **US6** (T022-T023) — error messages
9. **US7** (T024-T026) — accessibility
10. **Polish** (T029-T032)

### Parallel Opportunities

- T004 + T005: FeaturedScreen + ListScreen skeleton (different files)
- T006 + T007 + T008: Pull-to-refresh on all 3 tabs (different files)
- T019 + T020 + T021: Date formatting across card, detail, map (different files)
- T024 + T025 + T026: Accessibility fixes (different files)

---

## Implementation Strategy

### MVP First (US1 + US2)

1. Complete Foundational (T002-T003)
2. Complete US1 (T004-T010) — skeleton loading + pull-to-refresh + image placeholder
3. Complete US2 (T011-T013) — back gesture + transitions
4. **STOP and VALIDATE**: Test on both platforms
5. Deploy/demo if ready — biggest UX improvement delivered

### Full Delivery

1. Foundational → US1 → US2 → MVP validated
2. US5 (dates) + US4 (settings) + US3 (search) → deploy
3. US8 (cleanup) + US6 (errors) + US7 (accessibility) → final polish
4. Polish phase → ship

---

## Notes

- [P] tasks = different files, no dependencies
- US3 (search) modifies both ViewModel and ListScreen — keep T014-T015 before T016-T017
- US8 (remove lang toggle) touches both App.kt and DetailScreen — do T027 before T028
- Some tasks modify the same files (App.kt touched by US2, US4, US8) — coordinate carefully
- All `PullToRefreshBox` usage requires `@OptIn(ExperimentalMaterial3Api::class)`
