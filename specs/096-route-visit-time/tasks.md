# Tasks: Choose when you're going

**Input**: Design documents from `/specs/096-route-visit-time/`
**Prerequisites**: plan.md, spec.md, research.md (R1–R7), data-model.md, contracts/visit-time-control.md, quickstart.md

**Tests**: Required (constitution, Test-First). Each test task is written and seen failing before its implementation.

**Organization**: by user story. US2 (wording) is already done. US1 (choose a time) is the MVP; US3 (keep it) follows.

Paths: `shared/` = `shared/src/commonMain/kotlin/com/gallr/shared/`, `sharedTest/` =
`shared/src/commonTest/kotlin/com/gallr/shared/`, `app/` = `composeApp/src/commonMain/kotlin/com/gallr/app/`,
`appTest/` = `composeApp/src/commonTest/kotlin/com/gallr/app/`, `iosUiTest/` =
`composeApp/src/iosTest/kotlin/com/gallr/app/`.

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Setup

- [X] T001 Record the baseline: `./gradlew shared:allTests composeApp:testAndroidHostTest` counts on this branch (no
  code change).

---

## Phase 2: Foundational (blocks US1 and US3)

**Purpose**: the planning rule and the options, in `shared` (research R1, R2).

- [X] T002 [P] Write `sharedTest/route/RouteVisitTimeTest.kt` (fails first): `visitTimeOptions(now)` lists today and the
  next 13 days; start times are 08:00–21:00 in 30-minute steps; today lists only steps strictly after now (none after
  21:00); a time on the step boundary (13:00 at 13:00) is not offered; dates cross month and year ends correctly.
- [X] T003 [P] Extend `sharedTest/route/RouteEvaluatorTest.kt` (fails first) with a fixed clock (Thursday 22:00 Seoul):
  - `visitTime = Saturday 13:00` without an origin: plannedDay Saturday, reason `CHOSEN`, first stop's visit 13:00–13:45,
    later stops follow the legs, a stop open until 18:00 is `Open`;
  - with an origin: the walk ends at 13:00, `departure` is 13:00 minus the first leg's minutes;
  - a stop whose venue is closed that day is `ClosedOnPlannedDay` and the plan stays on that day;
  - a stop whose exhibition opens after that day is `NotYetOpen`, one that ended is `Ended`;
  - a first stop opening at 14:00 makes the timeline wait (visit 14:00–14:45);
  - `visitTime = null` gives the same evaluation as before for every existing case (keep the existing assertions).
- [X] T004 Add `shared/route/RouteVisitTime.kt` (`RouteVisitTime`, `VisitTimeOptions`, `visitTimeOptions`) to pass T002.
- [X] T005 Add `PlannedDayReason.CHOSEN` in `shared/route/RouteStopVerdict.kt` and the `visitTime` parameter in
  `shared/route/RouteEvaluator.kt` (research R1: skip `planDay`, anchor at the chosen start, show the departure
  whenever an origin is given). Passes T003. Check `sharedTest/route/PlannerEvaluatorParityTest.kt` still passes.

**Checkpoint**: `./gradlew shared:allTests --tests "com.gallr.shared.route.*"` passes.

---

## Phase 3: User Story 1 - Check a route for the day I'm going (Priority: P1) 🎯 MVP

**Goal**: the composer and the preview offer the control and check the route for the chosen time.

**Independent Test**: quickstart steps 1–3, 5–7.

### Tests for User Story 1

- [X] T006 [P] [US1] Write `appTest/ui/route/VisitTimePresentationTest.kt` (fails first): `visitTimeLabel` gives
  "지금" / "NOW", "오늘 13:00" / "TODAY 13:00", "내일 13:00" / "TOMORROW 13:00", "10.18(토) 13:00" / "SAT 10.18 13:00";
  day chip labels "지금", "오늘", "내일", "토 10.18" / "SAT 10.18"; the empty-today message.
- [X] T007 [P] [US1] Extend `appTest/ui/route/composer/RouteComposerPresentationTest.kt` (fails first): for a `CHOSEN`
  evaluation, `composerReferenceDayLine` reads "10.18(토) 13:00 기준" / "SAT 10.18 · FROM 13:00", followed by
  " · 12:41 출발" / " · LEAVE 12:41" when there is a departure.
- [X] T008 [P] [US1] Extend `appTest/viewmodel/PersonalRouteComposerViewModelTest.kt` (fails first):
  `chooseVisitTime(Saturday 13:00)` re-evaluates with it (state `visitTime`, evaluation `CHOSEN`, statuses for Saturday);
  `chooseVisitTime(null)` returns to the "now" evaluation; the state exposes `visitTimeOptions` for the test clock.
- [X] T009 [P] [US1] Extend `appTest/viewmodel/PublicRoutesViewModelTest.kt` (fails first): `chooseVisitTime` on an open
  preview re-evaluates it for that time; opening another route starts at "now"; without a choice the first-shared-day
  behaviour (DD21) is unchanged.
- [X] T010 [P] [US1] Write `iosUiTest/ui/route/VisitTimeSheetTest.kt` (fails first): tapping "지금" reports null; tapping
  "내일" then "13:00" reports tomorrow 13:00 (the day tap alone reports nothing); the selected chips are marked
  selected; a day with no times shows the empty message; the row reads its label.

### Implementation for User Story 1

- [X] T011 [US1] Add `app/ui/route/VisitTime.kt`: `visitTimeLabel`, chip labels, `VisitTimeRow`, `VisitTimeSheet`
  per contracts/visit-time-control.md (local chip with `GallrFilterChip`'s look until #351 lands; 44dp targets,
  `Role.RadioButton`, accent only on the selected chip). Passes T006 and T010.
- [X] T012 [US1] Extend `composerReferenceDayLine` in `app/ui/route/composer/RouteComposerPresentation.kt` for `CHOSEN`.
  Passes T007.
- [X] T013 [US1] Composer state and event in `app/viewmodel/PersonalRouteComposerViewModel.kt`: `visitTime`,
  `visitTimeOptions`, `chooseVisitTime`, passed to `RouteEvaluator.evaluate`. Passes T008 (in-memory for now; T017
  persists it).
- [X] T014 [US1] Preview state and event in `app/viewmodel/PublicRoutesViewModel.kt` (research R4). Passes T009.
- [X] T015 [US1] Show `VisitTimeRow` under the summary and `VisitTimeSheet` on tap in
  `app/ui/route/composer/RouteComposerScreen.kt` and `app/ui/route/publicroutes/PublicRoutePreviewScreen.kt`.

**Checkpoint**: quickstart steps 1–3 and 5–7 on the Android emulator.

---

## Phase 4: User Story 2 - Read why a stop won't work (Priority: P1)

- [X] T016 [US2] Verdict wording "관람 어려움" / "모두 관람 가능" / "관람 가능 · N곳 운영 시간 미확인" in
  `app/ui/route/composer/RouteComposerPresentation.kt`, tests in `RouteComposerPresentationTest.kt`, DESIGN.md example
  (commit `09cd7fdb`).

---

## Phase 5: User Story 3 - Keep my plan while I edit (Priority: P2)

**Goal**: the composer's choice survives edits, leaving the screen and restarts, per draft (research R3).

**Independent Test**: quickstart step 4.

- [X] T017 [P] [US3] Write `sharedTest/repository/DataStoreRouteVisitTimeRepositoryTest.kt` (fails first, following
  `DataStorePersonalRouteDraftRepositoryTest`'s DataStore setup): set and read back a choice for a draft; null clears
  it; a malformed stored value reads as null; the persisted key name is `route_visit_time`.
- [X] T018 [P] [US3] Extend `appTest/viewmodel/PersonalRouteComposerViewModelTest.kt` (fails first): a stored choice for
  the current draft is applied on start; one for another draft is ignored; adding, removing and reordering keep it; a
  stored day before today is cleared and `ComposerMessage.VisitTimePassed` is shown once.
- [X] T019 [US3] Add `shared/repository/RouteVisitTimeRepository.kt` and
  `shared/repository/DataStoreRouteVisitTimeRepository.kt` (private `ROUTE_VISIT_TIME_KEY`, value
  `draftId|yyyy-MM-dd|HH:mm`, read failures logged as `route_visit_time_read_failed`). Passes T017.
- [X] T020 [US3] Use the repository in `PersonalRouteComposerViewModel` (constructor + factory) and wire it in
  `app/App.kt`, `androidApp/src/main/kotlin/com/gallr/app/MainActivity.kt` and
  `composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt` beside the draft repository; add the passed-day
  message text in `RouteComposerPresentation.kt`. Passes T018.

**Checkpoint**: quickstart step 4 on the emulator.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T021 [P] DESIGN.md → Route composer: the visit time row and sheet (labels, chips, selected accent, three-tap
  path) and a decisions-log row for 2026-10-15. Owner approval needed for DESIGN.md changes.
- [X] T022 [P] Add a Recent Changes line for 096 in `CLAUDE.md`.
- [X] T023 Gates: `./gradlew shared:ktlintCheck shared:allTests composeApp:ktlintCheck composeApp:allTests` and
  `./gradlew androidApp:ktlintCheck composeApp:testAndroidHostTest androidApp:lintDebug androidApp:assembleDebug`.
- [ ] T024 Quickstart manual steps 1–8 on the Android emulator and the iOS simulator; record results here.

## Results (2026-10-16)

- Baseline (T001): shared 646, composeApp 599 Android host tests, all passing.
- Gates (T023): shared 662 and composeApp 614 Android host tests pass; iOS simulator tests pass (shared 660,
  composeApp 614, including `VisitTimeSheetTest`); ktlint (shared, composeApp, androidApp), `androidApp:lintDebug`
  and `androidApp:assembleDebug` pass.
- Android emulator, local stack (Fri 2026-10-16, 00:31):
  - Step 2: preview row "방문 시간 · 지금 ›"; the sheet lists 지금 (selected), 오늘, 내일 and dated chips with half-hour
    times; 내일 → 13:00 closes it, the reference reads "10.17(토) 13:00 기준", the row "방문 시간 · 내일 13:00" and
    visits 13:00–13:45, 14:08–14:53.
  - Composer: a time two days ahead reads "10.18(일) 15:00 기준" and "방문 시간 · 10.18(일) 15:00"; visits start 15:00.
  - Step 4: reordering (맨 위로) keeps the time; force-stopping the app and reopening the route keeps it.
  - Step 6: the copied route opened in the composer at "지금"; the preview kept its own choice while open.
- Not run by hand: steps 1 and 3 (the local catalogue has no opening hours, so every stop reads "운영 시간 미확인";
  covered by the `RouteEvaluatorTest` chosen-time cases), steps 5, 7, 8 (covered by the evaluator's `visitTime = null`
  cases, `VisitTimePresentationTest` and `VisitTimeSheetTest`), and the iOS simulator by hand.

---

## Dependencies & Execution Order

- Setup → Foundational (T002–T005) → US1 (T006–T015) → US3 (T017–T020) → Polish (T021–T024). US2 is done.
- T004 before T006/T011 (labels use `RouteVisitTime`); T005 before T007/T008/T009.
- T013 before T018/T020 (US3 extends the composer state).

### Parallel opportunities

- T002 ∥ T003; T006 ∥ T007 ∥ T008 ∥ T009 ∥ T010; T017 ∥ T018; T021 ∥ T022.
- T013 and T014 touch different view models and can run together after their tests.

## Parallel Example: User Story 1

```text
Task: "T006 [US1] VisitTimePresentationTest in appTest/ui/route/"
Task: "T008 [US1] composer view model tests in appTest/viewmodel/PersonalRouteComposerViewModelTest.kt"
Task: "T009 [US1] preview view model tests in appTest/viewmodel/PublicRoutesViewModelTest.kt"
```

## Implementation Strategy

1. Foundational: the rule and the options in `shared`, fully tested.
2. US1: the control in the composer and the preview (in-memory choice). Stop and validate: this is the MVP.
3. US3: keep the composer's choice per draft on the device.
4. Polish: DESIGN.md, CLAUDE.md, gates, both platforms.

## Notes

- Commit after each checkpoint.
- The visit time never goes into `PersonalRouteDraft`, a save request, a share payload or analytics (FR-008, FR-009).
