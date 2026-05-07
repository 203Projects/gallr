# Tasks: Opening Time Display

**Input**: Design documents from `/specs/025-opening-time-display/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md

**Tests**: Included — constitution requires Test-First for shared module logic (Principle II).

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup

**Purpose**: Database schema change for new field

- [x] T001 Create Supabase migration in supabase/migrations/005_add_opening_time.sql — `ALTER TABLE exhibitions ADD COLUMN IF NOT EXISTS opening_time TEXT;`

---

## Phase 2: Foundational (Data Pipeline)

**Purpose**: Wire the opening_time field through DTO, domain model, and sync script. MUST complete before any user story work.

**CRITICAL**: No user story work can begin until this phase is complete.

- [x] T002 [P] Add `openingTime` field (`@SerialName("opening_time") val openingTime: String? = null`) to ExhibitionDto in shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/ExhibitionDto.kt
- [x] T003 [P] Add `openingTime: String? = null` field to Exhibition domain model in shared/src/commonMain/kotlin/com/gallr/shared/data/model/Exhibition.kt and update the `toDomain()` mapping in ExhibitionDto.kt to pass it through
- [x] T004 [P] Add `'opening_time'` to the `KNOWN_COLUMNS` array in gas/SyncExhibitions.gs (after `'reception_date'`). No special parsing needed — value passes through as string.

**Checkpoint**: Data pipeline complete — opening_time flows from Sheet → Supabase → DTO → Domain model

---

## Phase 3: User Story 1 — View opening time on exhibition detail (Priority: P1) MVP

**Goal**: When an exhibition has both a reception date and an opening time, the label displays the time appended (e.g., "Opening today, 5 PM").

**Independent Test**: View exhibition detail page for an exhibition with both reception_date and opening_time populated. Label should show time appended.

### Tests for User Story 1

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [x] T005 [US1] Write unit tests for receptionDateLabel with time in shared/src/commonTest/kotlin/com/gallr/shared/ReceptionDateLabelTest.kt — test all states (today, tomorrow, weekday, past date) with openingTime = "5 PM". Expected labels: "Opening today, 5 PM", "Opening tomorrow, 5 PM", "Opening Saturday, 5 PM", "Opening Apr 5, 5 PM". Include Korean locale variants. Note: receptionDateLabel currently lives in composeApp; the test may need the function extracted or tested in composeApp/commonTest.

### Implementation for User Story 1

- [x] T006 [US1] Refactor `receptionDateLabel()` in composeApp/src/commonMain/kotlin/com/gallr/app/ui/detail/ExhibitionDetailScreen.kt — add `openingTime: String? = null` parameter. When non-null and non-blank, append `, $openingTime` to the returned label string. Also extract the `today` computation as a parameter (default `Clock.System.todayIn(...)`) to enable unit testing.
- [x] T007 [US1] Update the call site in ExhibitionDetailScreen.kt (around line 166) to pass `exhibition.openingTime` to the updated `receptionDateLabel()` function

**Checkpoint**: Labels with opening time display correctly for all date states

---

## Phase 4: User Story 2 — Graceful fallback when no opening time exists (Priority: P1)

**Goal**: Exhibitions without opening time display labels identically to current behavior (zero regressions).

**Independent Test**: View exhibition detail page for an exhibition with reception_date but no opening_time. Label should match current behavior exactly.

### Tests for User Story 2

- [x] T008 [US2] Add fallback tests to shared/src/commonTest/kotlin/com/gallr/shared/ReceptionDateLabelTest.kt — test all states with openingTime = null and openingTime = "". Verify labels are identical to current behavior (no comma, no trailing space). Also test: openingTime present but receptionDate null → no label shown.

### Implementation for User Story 2

- [x] T009 [US2] Verify implementation from T006 handles null/blank openingTime correctly — the `if (openingTime != null && openingTime.isNotBlank())` guard should already cover this. No additional code changes expected; this phase validates the fallback path.

**Checkpoint**: All existing exhibitions without opening_time display labels identically to before

---

## Phase 5: User Story 3 — Data entry for opening times (Priority: P2)

**Goal**: Content managers can enter opening times in the spreadsheet and have them sync to the app.

**Independent Test**: Enter a time value in the Google Sheet, trigger sync, verify the time appears in Supabase and subsequently in the app label.

### Tests for User Story 3

- [x] T010 [US3] Add DTO deserialization test to shared/src/commonTest/kotlin/com/gallr/shared/ExhibitionDtoTest.kt — test JSON with `"opening_time": "5 PM"` deserializes to `openingTime = "5 PM"`, and JSON without `opening_time` field deserializes to `openingTime = null`

### Implementation for User Story 3

- [x] T011 [US3] Implementation already complete from Phase 2 (T002-T004). Verify end-to-end: add test data to Google Sheet, run sync, confirm opening_time appears in Supabase, confirm app displays label correctly. Follow quickstart.md verification steps.

**Checkpoint**: Full data pipeline verified end-to-end

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final validation and cleanup

- [x] T012 [P] Run full test suite (`./gradlew :shared:allTests`) and verify no regressions
- [x] T013 Verify ExhibitionCard in composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/ExhibitionCard.kt does NOT show opening time (out of scope per spec — detail page only)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 (migration must exist before sync works). T002, T003, T004 are parallelizable.
- **US1 (Phase 3)**: Depends on Phase 2 completion. T005 (tests) before T006-T007 (implementation).
- **US2 (Phase 4)**: Depends on Phase 3 (US1 creates the function; US2 validates the fallback path). T008 before T009.
- **US3 (Phase 5)**: Depends on Phase 2 (data pipeline). Can run in parallel with US1/US2 for DTO test (T010). End-to-end verification (T011) depends on all phases.
- **Polish (Phase 6)**: Depends on all user stories complete.

### User Story Dependencies

- **US1 (P1)**: Depends on Foundational (Phase 2) only
- **US2 (P1)**: Depends on US1 (shares the same function implementation)
- **US3 (P2)**: Data pipeline from Phase 2; DTO test (T010) independent of US1/US2

### Parallel Opportunities

- T002, T003, T004 can all run in parallel (different files)
- T010 (US3 DTO test) can run in parallel with Phase 3 work
- T012, T013 can run in parallel

---

## Parallel Example: Phase 2

```bash
# Launch all foundational tasks together (different files):
Task: "Add openingTime to ExhibitionDto.kt"
Task: "Add openingTime to Exhibition.kt and toDomain()"
Task: "Add opening_time to KNOWN_COLUMNS in SyncExhibitions.gs"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (migration)
2. Complete Phase 2: Foundational (DTO, model, sync)
3. Complete Phase 3: User Story 1 (tests + label logic)
4. **STOP and VALIDATE**: Test US1 independently — label shows time when available
5. Deploy/demo if ready

### Incremental Delivery

1. Setup + Foundational → Data pipeline ready
2. Add US1 → Labels show time → Test independently (MVP!)
3. Add US2 → Verify no regressions → Test independently
4. Add US3 → End-to-end data entry verified → Test independently
5. Polish → Full test suite passes

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Constitution Principle II (Test-First) applies to shared module logic tests
- The receptionDateLabel() function is in composeApp but may need extraction to shared/ for proper unit testing
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
