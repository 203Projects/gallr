# Tasks: Dark Theme with System Setting Toggle

**Input**: Design documents from `/specs/014-dark-theme/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, quickstart.md

**Tests**: Not explicitly requested in feature spec. Test tasks omitted.

**Organization**: Tasks grouped by user story for independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **shared module**: `shared/src/commonMain/kotlin/com/gallr/shared/`
- **composeApp common**: `composeApp/src/commonMain/kotlin/com/gallr/app/`
- **Android entry**: `composeApp/src/androidMain/kotlin/com/gallr/app/`
- **iOS entry**: `composeApp/src/iosMain/kotlin/com/gallr/app/`

---

## Phase 1: Setup

**Purpose**: No new project setup needed. This feature adds to existing codebase.

- [x] T001 Checkout `014-dark-theme` branch from `develop`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Create ThemeMode enum and ThemeRepository in shared module — required by all user stories.

**CRITICAL**: No user story work can begin until this phase is complete.

- [x] T002 [P] Create ThemeMode enum (LIGHT, DARK, SYSTEM) in `shared/src/commonMain/kotlin/com/gallr/shared/data/model/ThemeMode.kt`
- [x] T003 [P] Create ThemeRepository interface (observeThemeMode, setThemeMode) in `shared/src/commonMain/kotlin/com/gallr/shared/repository/ThemeRepository.kt`
- [x] T004 Create ThemeRepositoryImpl backed by DataStore Preferences in `shared/src/commonMain/kotlin/com/gallr/shared/repository/ThemeRepositoryImpl.kt` — follow LanguageRepositoryImpl pattern, key = `"theme_mode"`, default = SYSTEM
- [x] T005 Create `gallrDarkColorScheme()` function using `darkColorScheme()` in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/theme/GallrColors.kt` — colors per data-model.md palette table

**Checkpoint**: Foundation ready — ThemeMode model, repository, and dark color scheme exist.

---

## Phase 3: User Story 1 — System Theme Follows Device Setting (Priority: P1) MVP

**Goal**: App automatically follows device light/dark mode setting. Default behavior with zero user configuration.

**Independent Test**: Toggle device between light and dark mode — app theme updates accordingly without restart.

### Implementation for User Story 1

- [x] T006 [US1] Update `GallrTheme` composable to accept `ThemeMode` and `isSystemInDarkTheme()` and apply correct color scheme in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/theme/GallrTheme.kt`
- [x] T007 [US1] Add `ThemeRepository` parameter to `TabsViewModel` constructor, expose `themeMode: StateFlow<ThemeMode>` and `setThemeMode(mode: ThemeMode)` in `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt`
- [x] T008 [US1] Update `App` composable to collect `themeMode` from ViewModel and pass to `GallrTheme` in `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`
- [x] T009 [P] [US1] Update `MainActivity.kt` to instantiate `ThemeRepositoryImpl` and pass to `App` in `composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt`
- [x] T010 [P] [US1] Update `MainViewController.kt` to instantiate `ThemeRepositoryImpl` and pass to `App` in `composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt`

**Checkpoint**: App follows device theme by default. No UI to change theme yet, but System mode works.

---

## Phase 4: User Story 2 — Manual Theme Selection via Settings (Priority: P2)

**Goal**: User can select Light, Dark, or System from the settings gear dropdown. Selection persists.

**Independent Test**: Open gear menu, select "Dark", verify app switches. Kill and reopen — dark theme persists.

### Implementation for User Story 2

- [x] T011 [US2] Add theme selection submenu (Light / Dark / System with current selection indicator) to the settings dropdown in `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`
- [x] T012 [US2] Wire theme selection menu items to `viewModel.setThemeMode()` calls and verify immediate theme switch in `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`

**Checkpoint**: Users can manually select theme. Preference persists across launches.

---

## Phase 5: User Story 3 — Dark Theme Visual Quality (Priority: P1)

**Goal**: All screens and components render correctly in dark mode with proper contrast and accent visibility.

**Independent Test**: Navigate all tabs, open detail screen, use filters — all elements legible and visually consistent in dark mode.

### Implementation for User Story 3

- [x] T013 [US3] Verify and fix ExhibitionCard borders and text contrast in dark mode in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/ExhibitionCard.kt`
- [x] T014 [P] [US3] Verify and fix GallrNavigationBar active indicator and label colors in dark mode in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/GallrNavigationBar.kt`
- [x] T015 [P] [US3] Verify and fix ListScreen segmented control, filter chips, city chips, and country dropdown in dark mode in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/list/ListScreen.kt`
- [x] T016 [P] [US3] Verify and fix MapScreen mode toggle buttons and marker dialog in dark mode in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapScreen.kt`
- [x] T017 [P] [US3] Verify and fix ExhibitionDetailScreen text, back button, and bookmark icon in dark mode in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/detail/ExhibitionDetailScreen.kt`
- [x] T018 [US3] Verify and fix settings dropdown background, border, and text contrast in dark mode in `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`
- [x] T019 [US3] Verify and fix GallrEmptyState message text and CTA button in dark mode in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/GallrEmptyState.kt`

**Checkpoint**: All screens pass visual inspection in both light and dark themes.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final validation across both platforms.

- [x] T020 Build and test on Android device — verify all 8 acceptance scenarios from quickstart.md
- [x] T021 Build and test on iOS device — verify all 8 acceptance scenarios from quickstart.md
- [x] T022 Verify no flash of wrong theme on cold launch (both platforms)
- [x] T023 Run quickstart.md full validation checklist

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Setup — BLOCKS all user stories
- **US1 (Phase 3)**: Depends on Foundational — core theme wiring
- **US2 (Phase 4)**: Depends on US1 — needs theme state in ViewModel
- **US3 (Phase 5)**: Depends on US1 — needs dark scheme active to verify visuals
- **Polish (Phase 6)**: Depends on all user stories complete

### User Story Dependencies

- **US1 (P1)**: Can start after Foundational (Phase 2) — independently testable
- **US2 (P2)**: Depends on US1 (needs ViewModel theme state) — independently testable after US1
- **US3 (P1)**: Depends on US1 (needs dark mode rendering) — can run in parallel with US2

### Within Each User Story

- Models before services
- Repository before ViewModel
- ViewModel before UI
- Core wiring before platform entry points

### Parallel Opportunities

- T002 + T003: ThemeMode enum and ThemeRepository interface (different files)
- T005: Can run in parallel with T003/T004 (different file: GallrColors.kt)
- T009 + T010: Android and iOS entry points (different files)
- T013-T017: Visual fixes across different screen files (all parallelizable)

---

## Parallel Example: Foundational Phase

```bash
# Launch enum and interface in parallel:
Task: "Create ThemeMode enum in shared/.../data/model/ThemeMode.kt"
Task: "Create ThemeRepository interface in shared/.../repository/ThemeRepository.kt"

# Then sequentially:
Task: "Create ThemeRepositoryImpl in shared/.../repository/ThemeRepositoryImpl.kt"
Task: "Create gallrDarkColorScheme() in composeApp/.../ui/theme/GallrColors.kt"
```

## Parallel Example: Visual Quality (US3)

```bash
# All screen verification tasks in parallel:
Task: "Verify ExhibitionCard in dark mode"
Task: "Verify GallrNavigationBar in dark mode"
Task: "Verify ListScreen in dark mode"
Task: "Verify MapScreen in dark mode"
Task: "Verify ExhibitionDetailScreen in dark mode"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (ThemeMode, ThemeRepository, dark color scheme)
3. Complete Phase 3: US1 (system theme wiring)
4. **STOP and VALIDATE**: Toggle device theme — app follows automatically
5. Deploy/demo if ready

### Incremental Delivery

1. Setup + Foundational → Foundation ready
2. US1 → System theme works → Deploy/Demo (MVP!)
3. US2 → Manual theme toggle in settings → Deploy/Demo
4. US3 → Visual polish across all screens → Deploy/Demo
5. Polish → Platform verification → Final release

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- US3 (Visual Quality) tasks are largely "verify and fix" — actual code changes depend on what's broken
- The dark color scheme values are specified in data-model.md
- GallrAccent (#FF5400) remains unchanged — do NOT create dark variant
- Commit after each phase checkpoint
