# Research: Comprehensive UI Improvements and Polish

**Feature**: 016-ui-improvements
**Date**: 2026-03-24

## R1: Pull-to-Refresh in Compose Multiplatform

**Decision**: Use `pullToRefresh` modifier from `material3` (available in Compose Multiplatform 1.8.0 via `PullToRefreshBox`).

**Rationale**: Material3's `PullToRefreshBox` wraps content and adds a pull-to-refresh indicator. It's the standard Compose approach, works on both Android and iOS, and requires no additional dependencies.

**Alternatives considered**:
- Custom pull-to-refresh implementation — unnecessary complexity; M3 provides it.
- `accompanist` SwipeRefresh — deprecated in favor of M3's built-in.

## R2: Skeleton Loading / Shimmer Effect

**Decision**: Create a simple `SkeletonCard` composable using `Modifier.background()` with animated alpha. Use a repeating `infiniteTransition` to pulse a gray rectangle between 0.1 and 0.3 alpha.

**Rationale**: Minimal code, no new dependencies. A pulsing gray card is sufficient for the reductionist design — no need for a gradient shimmer library.

**Alternatives considered**:
- Shimmer gradient library (accompanist-placeholder) — adds a dependency for a visual-only effect; YAGNI.
- Static gray boxes — functional but feel static/broken.

## R3: Back Gesture Handling

**Decision**: Use `BackHandler` from `androidx.activity.compose` (available in CMP 1.8.0) to handle system back. For screen transitions, use `AnimatedContent` wrapping the detail/tab content in `App.kt`.

**Rationale**: `BackHandler` is the standard Compose way to intercept back navigation. It works on both platforms in CMP 1.8.0. iOS swipe-back is handled automatically by the navigation container.

**Alternatives considered**:
- Platform-specific back handling via expect/actual — overengineered; `BackHandler` covers both.
- Full navigation library (Voyager, Decompose) — massive dependency for a simple nav structure.

## R4: Search Implementation

**Decision**: Add a `searchQuery: MutableStateFlow<String>` to `TabsViewModel`. Filter exhibitions in `filteredExhibitions` by checking if `name` or `venueName` contains the query (case-insensitive). Add a `TextField` above the filter chips in ListScreen.

**Rationale**: Client-side search is sufficient for ~70 exhibitions. No server endpoint needed. Integrates naturally with the existing `combine` flow in the ViewModel.

**Alternatives considered**:
- Server-side search via Supabase — unnecessary for current data volume; adds latency.
- Dedicated search screen — over-scoped; inline search on the List tab is simpler.

## R5: Tab Transition Animation

**Decision**: Wrap the `when (selectedTab)` block in `App.kt` with `AnimatedContent` using a `fadeIn + fadeOut` transition spec. Keep it subtle (200ms).

**Rationale**: `AnimatedContent` is the standard Compose way to animate content switching. A simple fade avoids directional assumptions (left/right) that might conflict with tab position.

**Alternatives considered**:
- Slide transitions — implies spatial relationship between tabs that doesn't exist.
- Crossfade — `AnimatedContent` with fade is functionally equivalent and more configurable.

## R6: Localized Date Formatting

**Decision**: Add a `fun localizedDateRange(lang: AppLanguage): String` extension function to `Exhibition` in shared/. For Korean: "YYYY.MM.DD – YYYY.MM.DD". For English: "Mon DD – Mon DD, YYYY" (using kotlinx-datetime month names).

**Rationale**: Date formatting belongs in shared/ (business logic). The function takes `AppLanguage` as a parameter and returns a formatted string. No new dependencies — kotlinx-datetime `LocalDate` provides month/day access.

**Alternatives considered**:
- Platform-specific `DateFormatter` via expect/actual — over-complex for two known formats.
- ICU4J library — massive dependency for two date formats.

## R7: Settings Theme Submenu

**Decision**: Replace the cycling theme menu item with three `DropdownMenuItem` entries (Light, Dark, System), each with a "✓" prefix on the currently active option. Wrap in a visual group with a "Theme" header text.

**Rationale**: Shows all options at once — users can see and select directly instead of cycling blindly. Checkmark is the standard pattern for radio-style selection in menus.

**Alternatives considered**:
- Radio buttons — too heavy for a dropdown; checkmarks are more compact.
- Keep cycling but add a subtitle — still not showing all options.

## R8: Image Loading Placeholder

**Decision**: Use Coil's built-in `placeholder` parameter on `AsyncImage`. Pass a `ColorPainter` with `surfaceVariant` color as the placeholder. This shows a gray rectangle at the correct aspect ratio while the image loads.

**Rationale**: Zero additional code — Coil already supports placeholders. Using the theme's `surfaceVariant` ensures it matches both light and dark themes.

**Alternatives considered**:
- Custom loading composable — unnecessary when Coil provides this built-in.
- Blur hash / LQIP — requires server-side image processing; over-scoped.

## R9: Accessibility Labels

**Decision**: Add `contentDescription` to all icons and interactive elements:
- Back button: `contentDescription = "Go back"`
- Settings icon: already has `contentDescription = "Settings"` ✓
- Bookmark button: `contentDescription = if (isBookmarked) "Remove bookmark" else "Add bookmark"`

**Rationale**: Screen readers need text labels for non-text elements. Compose's `contentDescription` maps to platform accessibility APIs automatically.

**Alternatives considered**:
- `semantics { }` modifier — `contentDescription` parameter is simpler for images and icons.
