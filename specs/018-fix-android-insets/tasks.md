# Tasks: Fix Android System Bar Insets and Display Cutout Handling

**Input**: Design documents from `/specs/018-fix-android-insets/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md

**Tests**: No test tasks — platform UI configuration with manual device/emulator verification.

**Organization**: 3 user stories mapped to implementation tasks. US1 and US2 are delivered by the same foundational change (enableEdgeToEdge), while US3 adds visual polish.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Phase 1: User Story 1 & 2 - Content Not Obscured by System Bars (Priority: P1, P2) 🎯 MVP

**Goal**: All app content is visible and not overlapped by the status bar (top) or system navigation bar (bottom) on all Android devices.

**Independent Test**: Build and install on an Android emulator with gesture navigation. Verify the top app bar text is fully visible below the status bar. Verify the bottom navigation items are fully tappable and not overlapped by the gesture navigation area. Test on both a notched device profile and a standard device profile.

### Implementation

- [x] T001 [US1] [US2] Enable edge-to-edge mode by adding `enableEdgeToEdge()` call before `setContent {}` in `composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt` — import `androidx.activity.enableEdgeToEdge` and call it as the first line of `onCreate()` after `super.onCreate()`
- [x] T002 [US1] [US2] Add display cutout handling by adding `android:windowLayoutInDisplayCutoutMode="shortEdges"` attribute to the `<activity>` element in `composeApp/src/androidMain/AndroidManifest.xml`

**Checkpoint**: Build app, install on Android emulator. Verify:
- Top bar content (logo, settings icon) is fully visible below the status bar
- Bottom navigation items are tappable without overlapping the gesture/navigation bar area
- Content on a notched device emulator profile is not obscured by the notch
- Scrollable lists show the last item fully visible above the navigation bar

---

## Phase 2: User Story 3 - Theme-Aware System Bar Styling (Priority: P3)

**Goal**: Status bar icons (clock, battery) use appropriate light/dark colors based on the app's current theme, and the system bars are transparent for edge-to-edge visual appearance.

**Independent Test**: Open the app in light theme — verify status bar icons are dark. Switch to dark theme — verify status bar icons are light. Check that the app background extends behind both system bars.

### Implementation

- [x] T003 [US3] Add theme-aware system bar styling in `composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt` — inside `setContent {}`, after the theme is resolved (isDarkTheme), add a `DisposableEffect(isDarkTheme)` that calls `enableEdgeToEdge(statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { isDarkTheme }, navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { isDarkTheme })` to update system bar icon colors when theme changes. Import `androidx.activity.SystemBarStyle` and `android.graphics.Color`.

**Checkpoint**: Build app. Toggle between light/dark/system themes via settings menu. Verify:
- Light theme: status bar icons are dark, navigation bar scrim is light
- Dark theme: status bar icons are light, navigation bar scrim is dark
- System bars are transparent and app background extends behind them

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (US1 + US2)**: No dependencies — can start immediately
  - T001 and T002 are independent files and can run in parallel [P]
- **Phase 2 (US3)**: Depends on T001 (enableEdgeToEdge must be in place before adding theme-aware styling)

### Parallel Opportunities

- T001 and T002 modify different files (MainActivity.kt vs AndroidManifest.xml) and can be executed in parallel

---

## Parallel Example: Phase 1

```bash
# Launch both tasks in parallel (different files):
Task T001: "Enable edge-to-edge in composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt"
Task T002: "Add cutout mode in composeApp/src/androidMain/AndroidManifest.xml"
```

---

## Implementation Strategy

### MVP First (US1 + US2)

1. Execute T001 + T002 (parallel): Enable edge-to-edge + cutout handling
2. **VALIDATE**: Build and test on emulator — content not obscured by system bars
3. This alone resolves the core user-reported issue

### Full Delivery

1. Complete Phase 1 (T001 + T002) → System bar insets handled
2. Complete Phase 2 (T003) → Theme-aware bar styling
3. **VALIDATE**: Test light/dark/system theme toggling on emulator

---

## Notes

- Only 2 files are modified: `MainActivity.kt` and `AndroidManifest.xml`
- No shared (commonMain) code changes needed — Scaffold already handles `innerPadding` correctly
- `enableEdgeToEdge()` is available in `androidx.activity:activity-compose:1.9.3` (already a dependency)
- On Android 15 (targetSdk 35), edge-to-edge is enforced by the system — this fix ensures the app handles it properly rather than relying on system defaults
