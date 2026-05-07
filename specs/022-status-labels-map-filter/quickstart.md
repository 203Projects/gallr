# Quickstart: Status Labels & Map Pin Filtering

**Feature**: 022-status-labels-map-filter
**Date**: 2026-04-02

## What This Feature Does

Adds "Closing Soon" / "종료 예정" badges to exhibition cards, map pin popups, and the detail page. Also hides ended exhibitions from the map view.

## Key Files

| File | Change |
|------|--------|
| `shared/.../model/ExhibitionStatus.kt` | NEW — enum + pure status function |
| `shared/.../model/ExhibitionStatusTest.kt` | NEW — unit tests |
| `composeApp/.../components/ExhibitionCard.kt` | Add closing-soon badge branch (lines 212-221) |
| `composeApp/.../tabs/map/MapScreen.kt` | Add status labels to dialog + bottom sheet |
| `composeApp/.../detail/ExhibitionDetailScreen.kt` | Add status label after date range (line 146) |
| `composeApp/.../viewmodel/TabsViewModel.kt` | Filter ended pins from map flows (lines 200-215) |

## Implementation Order

1. **ExhibitionStatus.kt + tests** (shared module) — write tests first, then implement
2. **TabsViewModel.kt** — add date filter to map pin flows (1-line change each)
3. **ExhibitionCard.kt** — add closing-soon branch to existing label logic
4. **MapScreen.kt** — add status labels to single-pin dialog and multi-pin sheet
5. **ExhibitionDetailScreen.kt** — add status label between date range and reception label

## How to Test

```bash
# Run shared module unit tests
./gradlew :shared:cleanAllTests :shared:allTests

# Run Android app (visual verification)
./gradlew :composeApp:installDebug

# Run iOS app (visual verification)
# Open in Xcode → iosApp.xcodeproj → Run on simulator
```

## Status Logic Quick Reference

```
if openingDate > today       → UPCOMING ("오픈 예정" / "Upcoming")
if closingDate < today       → ENDED (hidden)
if closingDate <= today + 3  → CLOSING_SOON ("종료 예정" / "Closing Soon")
else                         → ACTIVE (no label)
```
