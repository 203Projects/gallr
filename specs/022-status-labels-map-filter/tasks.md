# Tasks: Status Labels & Map Pin Filtering

**Input**: Design documents from `/specs/022-status-labels-map-filter/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, quickstart.md

**Tests**: Test tasks are included per Constitution Principle II (Test-First, NON-NEGOTIABLE) for the shared status function.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3, US4)
- Include exact file paths in descriptions

## Path Conventions

- **Shared module**: `shared/src/commonMain/kotlin/com/gallr/shared/data/model/`
- **Shared tests**: `shared/src/commonTest/kotlin/com/gallr/shared/data/model/`
- **ComposeApp UI**: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/`
- **ViewModel**: `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/`

---

## Phase 1: Setup

**Purpose**: No setup needed — this feature uses existing project structure and dependencies with no new libraries.

_(No tasks — skip to Phase 2)_

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Create the shared `ExhibitionStatus` enum and pure status function that ALL user stories depend on.

**CRITICAL**: No user story work can begin until this phase is complete.

### Tests (Test-First per Constitution Principle II)

> **Write these tests FIRST, ensure they FAIL before implementation**

- [x] T001 Write unit tests for `exhibitionStatus()` in `shared/src/commonTest/kotlin/com/gallr/shared/data/model/ExhibitionStatusTest.kt` — cover all 4 status values: UPCOMING (openingDate > today), CLOSING_SOON (openingDate <= today AND closingDate in [today, today+3]), ACTIVE (running, >3 days to close), ENDED (closingDate < today). Also test priority: upcoming exhibition closing in 2 days should return UPCOMING not CLOSING_SOON. Test `label(lang)` returns correct bilingual strings for UPCOMING/CLOSING_SOON and null for ACTIVE/ENDED.

### Implementation

- [x] T002 Create `ExhibitionStatus` enum with values UPCOMING, CLOSING_SOON, ACTIVE, ENDED and `label(lang: AppLanguage): String?` function (returns "Upcoming"/"오픈 예정" for UPCOMING, "Closing Soon"/"종료 예정" for CLOSING_SOON, null for ACTIVE/ENDED) in `shared/src/commonMain/kotlin/com/gallr/shared/data/model/ExhibitionStatus.kt`
- [x] T003 Create `exhibitionStatus(openingDate: LocalDate, closingDate: LocalDate, today: LocalDate): ExhibitionStatus` pure function in the same file. Logic: if openingDate > today → UPCOMING; if closingDate < today → ENDED; if closingDate <= today.plus(3, DateTimeUnit.DAY) → CLOSING_SOON; else → ACTIVE
- [x] T004 Run `./gradlew :shared:allTests` to verify all T001 tests pass

**Checkpoint**: Shared status function is implemented and tested. All user stories can now proceed.

---

## Phase 3: User Story 1 — Closing Soon Badge on Exhibition Cards (Priority: P1) + User Story 4 — Hide Ended Exhibitions from Map (Priority: P1)

**Goal**: Add "Closing Soon" badge to exhibition cards and filter ended exhibition pins from the map.

**Independent Test**: Browse Featured/List tabs — exhibitions closing within 3 days show orange "Closing Soon" badge. Open map — no pins for ended exhibitions.

### Implementation

- [x] T005 [US1] Update `ExhibitionCard.kt` at `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/ExhibitionCard.kt` — replace the existing `if (exhibition.openingDate > today)` block (lines 212-221) with logic that calls `exhibitionStatus(exhibition.openingDate, exhibition.closingDate, today)` and displays the label from `status.label(lang)` when non-null. Import `ExhibitionStatus`, `exhibitionStatus`, `DateTimeUnit`, and `plus` from the shared module.
- [x] T006 [P] [US4] Update `TabsViewModel.kt` at `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/TabsViewModel.kt` — add `.filter { it.closingDate >= Clock.System.todayIn(TimeZone.currentSystemDefault()) }` before `.mapNotNull` in both `myListMapPins` (line 204) and `allMapPins` (line 213) StateFlow builders.

**Checkpoint**: Cards show "Closing Soon" badge. Map hides ended exhibition pins in both "All" and "My List" modes.

---

## Phase 4: User Story 2 — Status Labels on Map Pin Cards (Priority: P2)

**Goal**: Show "Closing Soon" / "Upcoming" status labels in map pin popups and bottom sheet lists.

**Independent Test**: Tap a map pin for a closing-soon exhibition — popup shows status label below date range. Tap a multi-pin cluster — each item in the bottom sheet shows its status label.

### Implementation

- [x] T007 [US2] Add a `pinStatusText()` helper function at the end of `MapScreen.kt` at `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapScreen.kt` — takes `openingDate: LocalDate`, `closingDate: LocalDate`, `lang: AppLanguage`, returns `String?`. Uses `exhibitionStatus()` and returns `status.label(lang)`.
- [x] T008 [US2] Add status label in the single-pin `AlertDialog` text block (after line 156, after the date range Text) — call `pinStatusText(pin.openingDate, pin.closingDate, lang)` and if non-null, display as `Text` with `GallrAccent.activeIndicator` color and `labelMedium` style. Add necessary imports: `GallrAccent`, `ExhibitionStatus`, `exhibitionStatus`, `Clock`, `TimeZone`, `todayIn`.
- [x] T009 [US2] Add status label in the multi-pin `ModalBottomSheet` LazyColumn items (after line 230, after the date range Text) — same pattern as T008, calling `pinStatusText()` for each pin.

**Checkpoint**: Map pin dialogs and bottom sheets show status labels. Cards and map filtering from Phase 3 still work.

---

## Phase 5: User Story 3 — Status Label on Exhibition Detail Page (Priority: P3)

**Goal**: Show "Closing Soon" / "Upcoming" status label on the exhibition detail page below the date range.

**Independent Test**: Navigate to the detail page of a closing-soon or upcoming exhibition — status label appears in orange below the date range, above the reception date label (if present).

### Implementation

- [x] T010 [US3] Add status label in `ExhibitionDetailScreen.kt` at `composeApp/src/commonMain/kotlin/com/gallr/app/ui/detail/ExhibitionDetailScreen.kt` — after the date range Text (line 146), compute `exhibitionStatus(exhibition.openingDate, exhibition.closingDate, today)` where `today = Clock.System.todayIn(TimeZone.currentSystemDefault())`, get `status.label(lang)`, and if non-null, add `Spacer(Modifier.height(GallrSpacing.sm))` + `Text(text = label, style = labelMedium, color = GallrAccent.activeIndicator)`. Insert before the reception date block (line 148). Import `exhibitionStatus`, `ExhibitionStatus`.

**Checkpoint**: All 4 user stories complete. Status labels consistent across cards, map, and detail page.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final validation across all surfaces.

- [x] T011 Run full test suite: `./gradlew :shared:allTests` to confirm shared module tests pass
- [x] T012 Visual verification: build and run the app on Android (`./gradlew :composeApp:installDebug`), check all surfaces in both Korean and English, light and dark themes

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: Skipped — no setup needed
- **Phase 2 (Foundational)**: No dependencies — start immediately. BLOCKS all user stories.
- **Phase 3 (US1 + US4)**: Depends on Phase 2 completion. US1 and US4 are bundled because US4 is a 1-line change per flow.
- **Phase 4 (US2)**: Depends on Phase 2 completion. Can run in parallel with Phase 3.
- **Phase 5 (US3)**: Depends on Phase 2 completion. Can run in parallel with Phase 3 and 4.
- **Phase 6 (Polish)**: Depends on all user story phases.

### User Story Dependencies

- **US1 (Closing Soon on Cards)**: Depends only on Phase 2 (shared status function)
- **US2 (Map Pin Labels)**: Depends only on Phase 2 (shared status function)
- **US3 (Detail Page Label)**: Depends only on Phase 2 (shared status function)
- **US4 (Hide Ended Pins)**: Depends only on Phase 2 (no dependency on status function, just date filter)

All user stories are **independently implementable** after Phase 2.

### Parallel Opportunities

- T005 and T006 can run in parallel (different files)
- T007, T008, T009 are sequential (same file)
- T010 can run in parallel with all Phase 3 and 4 tasks (different file)
- Phases 3, 4, 5 can all run in parallel with each other

---

## Parallel Example: After Phase 2

```text
# These can all run in parallel (different files):
T005 [US1] Update ExhibitionCard.kt — closing soon badge
T006 [US4] Update TabsViewModel.kt — filter ended map pins
T010 [US3] Update ExhibitionDetailScreen.kt — detail page status label

# Then sequentially (same file):
T007 [US2] Add pinStatusText() helper to MapScreen.kt
T008 [US2] Add status label to single-pin AlertDialog in MapScreen.kt
T009 [US2] Add status label to multi-pin BottomSheet in MapScreen.kt
```

---

## Implementation Strategy

### MVP First (User Story 1 + 4)

1. Complete Phase 2: Foundational (T001-T004)
2. Complete Phase 3: US1 + US4 (T005-T006)
3. **STOP and VALIDATE**: Cards show "Closing Soon", map hides ended pins
4. Deploy/demo if ready

### Incremental Delivery

1. Phase 2 → Shared status function ready
2. Phase 3 (US1 + US4) → Cards + map filtering → Test → Deploy
3. Phase 4 (US2) → Map pin status labels → Test → Deploy
4. Phase 5 (US3) → Detail page status label → Test → Deploy
5. Phase 6 → Final polish and full verification

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Constitution Principle II: Tests (T001) must be written and FAIL before implementation (T002-T003)
- All status logic flows through the single `exhibitionStatus()` function for consistency (FR-011)
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
