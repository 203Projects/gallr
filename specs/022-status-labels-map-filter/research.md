# Research: Status Labels & Map Pin Filtering

**Feature**: 022-status-labels-map-filter
**Date**: 2026-04-02

## R1: Status Computation Placement

**Decision**: Create a new `ExhibitionStatus` enum and `exhibitionStatus()` pure function in `shared/src/commonMain/kotlin/com/gallr/shared/data/model/ExhibitionStatus.kt`.

**Rationale**: Constitution Principle VI mandates all business logic lives in the `shared` module. A pure function taking `openingDate`, `closingDate`, and `today` as parameters is testable without mocking Clock, reusable across all UI surfaces, and has zero side effects.

**Alternatives considered**:
- Extension function on `Exhibition` — would work but can't be reused for `ExhibitionMapPin` without duplication. A standalone function accepting dates is more flexible.
- Extension functions on both `Exhibition` and `ExhibitionMapPin` — violates DRY; same logic duplicated.
- ViewModel-computed status — pushes business logic into the UI layer; violates Shared-First principle.

## R2: "Closing Soon" Threshold

**Decision**: An exhibition is "closing soon" when `closingDate - today` is in the range `[0, 3]` days (inclusive) AND `openingDate <= today`.

**Rationale**: Matches the feature request specification (260329). The opening date check prevents showing "Closing Soon" for exhibitions that haven't started yet (those show "Upcoming" instead).

**Alternatives considered**:
- 7-day threshold — too broad; dilutes urgency signal.
- Configurable threshold — YAGNI; no current need for runtime configuration.

## R3: ExhibitionMapPin Status Support

**Decision**: The `exhibitionStatus()` function accepts `openingDate: LocalDate` and `closingDate: LocalDate` parameters directly, making it usable for both `Exhibition` and `ExhibitionMapPin` (which already has both fields).

**Rationale**: Both data classes have `openingDate` and `closingDate`. Accepting raw dates avoids coupling the function to either type.

**Alternatives considered**:
- Adding an interface `HasDateRange` to both classes — over-engineering for a single function; rejected per YAGNI.

## R4: Map Pin Filtering for Ended Exhibitions

**Decision**: Add `.filter { it.closingDate >= today }` to both `myListMapPins` and `allMapPins` StateFlow builders in `TabsViewModel`, before the `.mapNotNull { it.toMapPin(lang) }` call.

**Rationale**: The `filteredExhibitions` flow already applies this filter (line 171). The map pin flows were missed. The fix is a single-line addition to each flow, matching the existing pattern exactly.

**Alternatives considered**:
- Filtering in MapScreen composable — pushes filtering logic into UI; ViewModel is the correct place.
- Filtering in the repository layer — would affect all consumers; map filtering should be a presentation concern.

## R5: Status Label Placement on Detail Page

**Decision**: Insert the status label after the date range (line 146) and before the reception date label (line 148) in `ExhibitionDetailScreen.kt`. This places it immediately below the date range, visually consistent with the card layout.

**Rationale**: The status label is contextually tied to the date range (it describes the exhibition's temporal status). The reception date label is a separate, independent piece of information about a specific event.

**Alternatives considered**:
- After the reception date label — less intuitive; status should be adjacent to the dates it describes.
- As a badge/chip instead of text — inconsistent with the detail page's text-heavy layout style.
