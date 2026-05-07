# Quickstart: UI Polish and Uniform Theme Across Tabs

**Feature**: 015-ui-polish-uniformity
**Date**: 2026-03-24

## Implementation Order

1. **MapScreen spacing** — replace all hardcoded dp values with GallrSpacing tokens
2. **MapScreen header** — change `titleLarge` to `labelLarge` to match Featured tab
3. **GallrNavigationBar** — replace hardcoded 14.dp with GallrSpacing.md
4. **ExhibitionCard** — change exhibition name from `titleLarge` to `titleMedium`
5. **Visual verification** — check all screens in light and dark mode on both platforms

## Verification Steps

### 1. Build verification
```bash
./gradlew :composeApp:compileKotlinIosSimulatorArm64
./gradlew :composeApp:assembleDebug
```

### 2. Tab header consistency
- Open Featured tab → note header style
- Switch to Map tab → header should use same style
- Switch to List tab → segmented control serves as header (no separate header needed)

### 3. Spacing audit
- Search codebase for hardcoded dp values in UI screen files
- Confirm all use GallrSpacing tokens

### 4. Typography hierarchy
- View exhibition card → note name, venue, date sizes
- Tap into detail → name should be larger (headlineMedium), venue/dates proportionally larger (labelLarge)
- Open map dialog → metadata should match card sizes

### 5. Dark mode check
- Toggle to dark theme
- Navigate all tabs — verify no visual regressions

## Acceptance Test Scenarios

| # | Scenario | Steps | Expected |
|---|----------|-------|----------|
| 1 | Header match | Compare Featured and Map headers | Same typography style |
| 2 | No hardcoded spacing | Search for `\.dp` in screen files | Only in GallrSpacing.kt and theme files |
| 3 | Card name size | View card then detail | Card = titleMedium, Detail = headlineMedium |
| 4 | Venue consistency | Compare card and map dialog venue text | Both use labelMedium uppercase |
| 5 | Dark mode | Toggle dark mode, check all tabs | No visual regressions |
| 6 | Nav bar spacing | View bottom nav | Consistent padding from token system |
