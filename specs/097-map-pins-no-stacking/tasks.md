# Tasks: Map pins without stacking

**Input**: Design documents from `/specs/097-map-pins-no-stacking/`
**Prerequisites**: plan.md, spec.md, research.md (R1–R7), data-model.md, contracts/map-pins.md, quickstart.md

**Tests**: Required (constitution, Test-First). Each test task is written and seen failing before its implementation.
The map itself cannot be driven from Compose UI tests, so the pure functions carry the automated coverage and the
quickstart carries the visual checks.

**Organization**: by user story. US1 (spread when zoomed in) is the MVP; US2 (count pin zooms in) and US3
(accessibility) follow.

Paths: `map/` = `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/`, `mapTest/` =
`composeApp/src/commonTest/kotlin/com/gallr/app/ui/tabs/map/`.

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Setup

- [X] T001 Record the baseline: `./gradlew composeApp:testAndroidHostTest` count on this branch (no code change).

---

## Phase 2: Foundational (blocks US1 and US2)

**Purpose**: the arrangement rule as a pure function (research R2), with its constants.

- [X] T002 Write `mapTest/MapPinArrangementTest.kt` (fails first) for `arrangeMapPins(candidates, zoom, density)`:
  - two pins 200dp apart at zoom 13 stay exactly on their places, `isMoved = false`;
  - two pins 10dp apart at zoom 13 become two single pins that pass `pinsOverlap` (ellipse 52 × 56dp), each within one
    pin width of its place, and the pair's centroid is unchanged;
  - three pins at one place at zoom 13 give three single pins, one on the place;
  - eight pins at one place at zoom 13 give eight non-overlapping single pins all within 2 × 52dp of the place
    (SC-004), and the arrangement is identical on a second call (FR-009);
  - two groups whose spread pins would overlap each other are merged and arranged together, with no overlap in the
    result;
  - at zoom 12.9 the 10dp pair becomes one `ArrangedMapPin` with two ids at their centroid (today's rule), and at
    zoom 13.0 it is spread;
  - `pinsOverlap` is true for (0, 0) and (51dp, 0), false for (52dp, 0), true for (0, 55dp), false for (26dp, 56dp);
  - an empty input gives an empty result.
- [X] T003 Add `map/MapPinArrangement.kt`: `ArrangedMapPin`, `PIN_SPREAD_FROM_ZOOM = 13.0`, `PIN_SPREAD_COLUMN = 52.dp`,
  `PIN_SPREAD_ROW = 56.dp`, `pinsOverlap`, `arrangeMapPins` (union-find components by the overlap ellipse; brick
  lattice around the centroid, nearest slots first, greedy by distance with the tie-breaks of research R2; merge
  overlapping components and repeat). Below `PIN_SPREAD_FROM_ZOOM` delegate to the existing 28dp grouping. Passes
  T002.

**Checkpoint**: `./gradlew composeApp:testAndroidHostTest --tests "com.gallr.app.ui.tabs.map.MapPinArrangementTest"`
passes.

---

## Phase 3: User Story 1 - See every exhibition in a neighbourhood (Priority: P1) 🎯 MVP

**Goal**: the Map tab draws the arrangement: one pin per exhibition from zoom 13, moved apart where needed.

**Independent Test**: quickstart steps 3–8 (reach neighbourhood zoom with the + button for now).

- [X] T004 [US1] In `map/SeoulDistrictMap.kt`, replace the per-frame `groupNearlyCoincidentPins` call with
  `arrangeMapPins` (pass `mapViewport.zoom`); build `ScreenExhibitionMapPinGroup` from each `ArrangedMapPin`
  (single ids → one-pin groups at the drawn position converted with `positionFromScreenLocation`; several ids → a
  group at the centroid as today). Keep `exactPinGroups` as the pre-projection fallback.
- [X] T005 [US1] Keep `pinFeatureCollection` keyed by the pin's exhibition ids so moved pins keep their own cover,
  saved ring and draw order; confirm the title layer still filters to single pins. Extend
  `mapTest/SeoulDistrictMapDataTest.kt` with a case where two pins at one place at zoom 13 produce two features with
  different ids and covers (fails first, before T004 lands).

**Checkpoint**: quickstart steps 3–8 on the Android emulator.

---

## Phase 4: User Story 2 - Count pins zoom in (Priority: P1)

**Goal**: tapping a count pin animates the camera to its members at zoom 13–14; the overlap sheet is retired.

**Independent Test**: quickstart steps 1–2, 5.

- [X] T006 [P] [US2] Extend `mapTest/MapPinArrangementTest.kt` (fails first) for
  `countPinCameraTarget(memberPoints, currentZoom, viewportWidthPx, viewportHeightPx)`: members all at one point give
  zoom 13.0 at that point; members spanning 10% of the view at zoom 11.8 give zoom 14.0 (capped) at their centroid;
  members spanning 80% of the view at zoom 12.5 give the zoom that fits them into 60% (about 12.5 + log2(0.6 / 0.8),
  then raised to 13.0 because the result must be at least 13.0); the zoom never exceeds `MAP_MAX_ZOOM`.
- [X] T007 [US2] Add `countPinCameraTarget` to `map/MapPinArrangement.kt`. Passes T006.
- [X] T008 [US2] In `map/SeoulDistrictMap.kt`, on a count pin tap (layer click and accessible target) call
  `mapState.animateCamera(CameraUpdate(center = members' centroid position, zoom = target.zoom))` instead of setting
  `selectedOverlapGroup`; remove `selectedOverlapGroup`, `ExhibitionOverlapSheet` and its private composables.
- [X] T009 [US2] Remove `sortOverlapExhibitionsByDistance`, `overlapSheetTitle`, `overlapMetadata`,
  `OverlapExhibitionPresentation` and `englishMonth` (if unused) from `map/MapNearbyPresentation.kt` (renamed `AdaptiveNearbyViewport.kt` once only that remained), and their tests
  from `mapTest/MapNearbyPresentationTest.kt` (research R6). `adaptiveNearbyViewport` stays.

**Checkpoint**: quickstart steps 1–2 and 5 on the Android emulator; `composeApp:testAndroidHostTest` passes.

---

## Phase 5: User Story 3 - Read and reach pins with assistive technology (Priority: P2)

**Goal**: accessible targets follow the drawn positions, never overlap, and count pins say they zoom in.

**Independent Test**: quickstart step 9.

- [X] T010 [P] [US3] Write `mapTest/MapPinTargetPresentationTest.kt` (fails first): `pinTargetLabel` gives the
  exhibition name for a single pin and "전시 3개 · 확대" / "3 EXHIBITIONS · ZOOM IN" for a count pin;
  `pinTargetWidth(isSingle, nearestNeighbourDxPx, density)` gives 104dp for a lone single pin, 52dp for a single pin
  with a neighbour within 104dp horizontally, 52dp for a count pin.
- [X] T011 [US3] Add `pinTargetLabel` and `pinTargetWidth` to `map/MapPinArrangement.kt` (or a small
  `MapPinTargetPresentation.kt`), and use them in `AccessibleExhibitionPinTarget` and its offset in
  `map/SeoulDistrictMap.kt`. Passes T010.

**Checkpoint**: quickstart step 9 with TalkBack on the emulator.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T012 [P] DESIGN.md → Map pins: grouping only below zoom 13; spreading on the 52 × 56dp brick lattice; count pin
  tap zooms in; the "이 주변 전시" sheet retired; a decisions-log row for 2026-10-16. Owner approval needed for
  DESIGN.md changes.
- [X] T013 [P] Add a Recent Changes line for 097 in `CLAUDE.md`.
- [X] T014 Gates: `./gradlew composeApp:ktlintCheck composeApp:allTests` and
  `./gradlew androidApp:ktlintCheck composeApp:testAndroidHostTest androidApp:lintDebug androidApp:assembleDebug`.
- [ ] T015 Quickstart manual steps 1–10 on the Android emulator and the iOS simulator; record results here.

## Results (2026-10-17)

- Baseline (T001): composeApp 599 Android host tests, all passing.
- Gates (T014): composeApp 612 Android host tests pass; ktlint (composeApp, androidApp), `androidApp:lintDebug` and
  `androidApp:assembleDebug` pass. iOS simulator tests pass (composeApp 608).
- Android emulator, local stack:
  - Step 1: the city view shows count pins (3, 3, 5; and 6 once panned).
  - Step 2: tapping the "5" pin zoomed to Hannam/Itaewon; five pins with their own covers, none overlapping, no sheet.
  - Step 3: the "6" pin's three shows at one gallery spread side by side with the middle pin on the place.
  - Step 4: tapping a moved pin opened its exhibition (여름의 끝).
  - Step 5: zooming out with − merged the pins into count pins again.
  - Step 8: 내 전시 shows the same map (no saved exhibitions on the test account, so no pins to spread).
- Not run by hand: steps 6 (pan stability), 7 (saved ring among moved pins), 9 (TalkBack), 10 (route maps untouched by
  this change), and the iOS simulator. Steps 6–7 are covered by the deterministic `MapPinArrangementTest` and the
  unchanged draw-order feature; step 9 by `MapPinTargetPresentationTest`.

---

## Dependencies & Execution Order

- Setup → Foundational (T002–T003) → US1 (T004–T005) → US2 (T006–T009) → US3 (T010–T011) → Polish (T012–T015).
- T003 before T004; T004 before T008 (the tap handler reads the arranged groups).
- T005's test is written before T004 and passes once T004 lands.

### Parallel opportunities

- T006 ∥ T010 (different test files) once T003 exists; T012 ∥ T013.
- T009 can run alongside T008 (different files) once nothing references the sheet.

## Parallel Example: User Story 2

```text
Task: "T006 [US2] countPinCameraTarget tests in mapTest/MapPinArrangementTest.kt"
Task: "T010 [US3] target label and width tests in mapTest/MapPinTargetPresentationTest.kt"
```

## Implementation Strategy

1. Foundational: the arrangement as a pure function, fully tested.
2. US1: draw it on the Map tab. Stop and validate: this is the MVP.
3. US2: count pins zoom in; remove the sheet.
4. US3: accessible targets.
5. Polish: DESIGN.md, CLAUDE.md, gates, both platforms.

## Notes

- Commit after each checkpoint.
- Route maps (`ui/route/RouteMap.kt`) are not touched (FR-010).
- No new dependency (FR-011).
