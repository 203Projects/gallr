# Quickstart: Comprehensive UI Improvements and Polish

**Feature**: 016-ui-improvements
**Date**: 2026-03-24

## Implementation Order

1. **SkeletonCard component** — new reusable loading placeholder
2. **Pull-to-refresh** — FeaturedScreen, ListScreen, MapScreen
3. **Image placeholder** — ExhibitionDetailScreen AsyncImage
4. **Back gesture + transitions** — App.kt AnimatedContent + BackHandler
5. **Search bar** — ListScreen + ViewModel searchQuery
6. **Settings menu** — expand to show all theme options with checkmarks
7. **Localized dates** — Exhibition date formatting in shared/
8. **Error messages** — enhance error text in ViewModel/screens
9. **Remove detail lang toggle** — ExhibitionDetailScreen cleanup
10. **Accessibility labels** — audit all icons across all screens

## Verification Steps

### Build
```bash
./gradlew :composeApp:compileKotlinIosSimulatorArm64
./gradlew :composeApp:assembleDebug
```

### Per-Story Verification

| Story | Test Steps |
|-------|-----------|
| US1 Loading | Slow network: skeleton cards visible; pull down on each tab; detail image shows gray placeholder |
| US2 Gestures | Tap card → smooth transition; swipe back on detail; switch tabs → fade animation |
| US3 Search | Type in search bar on List tab; results filter live; combine with city filter |
| US4 Settings | Open gear → see all 3 theme options; current has ✓; tap to change |
| US5 Dates | Switch to EN: "Mar 19 – May 10, 2026"; switch to KO: "2026.03.19 – 2026.05.10" |
| US6 Errors | Airplane mode → specific network error; empty My List → guidance text |
| US7 A11y | Enable VoiceOver/TalkBack → all buttons announced; verify contrast |
| US8 Cleanup | Detail screen → no EN/KO toggle; settings → language toggle works |

## Acceptance Test Scenarios

| # | Scenario | Expected |
|---|----------|----------|
| 1 | Pull-to-refresh on Featured | Refresh indicator appears, data reloads |
| 2 | Pull-to-refresh on List | Refresh indicator appears, data reloads |
| 3 | Pull-to-refresh on Map | Refresh indicator appears, map pins reload |
| 4 | Skeleton loading | 2-3 pulsing gray cards during initial load |
| 5 | Image placeholder | Gray 16:9 box while cover image loads |
| 6 | Back swipe | Swipe from left edge returns from detail |
| 7 | Tab fade | Switching tabs has subtle fade transition |
| 8 | Search filter | Type "gallery" → only matching exhibitions shown |
| 9 | Search + city | Select Seoul + type "gallery" → filtered by both |
| 10 | Theme checkmarks | Settings shows ✓ next to active theme |
| 11 | Korean dates | Cards show "2026.03.19 – 2026.05.10" |
| 12 | English dates | Cards show "Mar 19 – May 10, 2026" |
| 13 | Network error | Shows "Check your internet connection" |
| 14 | Screen reader | All buttons announced with meaningful labels |
| 15 | No lang toggle on detail | Detail top bar shows only back + bookmark |
