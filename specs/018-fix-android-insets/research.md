# Research: Fix Android System Bar Insets and Display Cutout Handling

**Feature**: 018-fix-android-insets
**Date**: 2026-03-26

## Research Question: What is the recommended approach for edge-to-edge on Compose Multiplatform targeting Android 15 (SDK 35)?

### Decision: Enable edge-to-edge via `enableEdgeToEdge()` in MainActivity

**Rationale**: Starting with `androidx.activity:activity-compose:1.8.0+` (project uses 1.9.3), `enableEdgeToEdge()` is the canonical one-call API. On Android 15 (targetSdk 35), edge-to-edge is enforced by the system, so the app must explicitly handle insets regardless. `enableEdgeToEdge()` configures:
- Transparent status bar
- Transparent/translucent navigation bar with scrim
- Proper `WindowInsetsController` setup for light/dark status bar icons

**Alternatives considered**:

| Alternative | Why Rejected |
|-------------|-------------|
| `WindowCompat.setDecorFitsSystemWindows(window, false)` + manual bar colors | More verbose; `enableEdgeToEdge()` wraps this with better defaults |
| Per-screen manual inset handling without edge-to-edge | Does not meet the spec's edge-to-edge visual requirement (US3); also won't work correctly on SDK 35 where edge-to-edge is mandatory |
| XML theme attributes (`android:statusBarColor`, `android:navigationBarColor`) | Deprecated approach for Compose apps; `enableEdgeToEdge()` supersedes it |

## Research Question: Where should window inset padding be applied — Scaffold level or per-screen?

### Decision: Use Scaffold's `contentWindowInsets` parameter combined with existing `innerPadding`

**Rationale**: The app already correctly passes `innerPadding` from Scaffold to each screen via `Modifier.padding(innerPadding)`. By default, Material3 Scaffold in Compose consumes `WindowInsets.systemBars` and includes them in the `innerPadding` values. Once edge-to-edge is enabled, Scaffold's default behavior will automatically account for status bar and navigation bar insets in the padding it provides to content. The top bar and bottom bar are already positioned by Scaffold.

The key is:
1. Enable edge-to-edge in MainActivity (Android-specific)
2. Scaffold's default `contentWindowInsets` will handle the rest
3. No per-screen changes needed since screens already apply `innerPadding`

**Alternatives considered**:

| Alternative | Why Rejected |
|-------------|-------------|
| Apply `Modifier.systemBarsPadding()` to each screen individually | Duplicates what Scaffold already provides via `innerPadding`; violates DRY |
| Wrap entire App composable in `Modifier.safeDrawingPadding()` | Would double-pad with Scaffold's built-in inset handling |
| Custom inset-aware wrapper composable | Over-engineering for this use case; Scaffold handles it |

## Research Question: How should status bar icon colors adapt to light/dark theme?

### Decision: Use `enableEdgeToEdge()` with `SystemBarStyle` based on theme, called from a Compose side-effect

**Rationale**: `enableEdgeToEdge()` accepts `statusBarStyle` and `navigationBarStyle` parameters. Since the app supports light/dark/system theme modes, the status bar icons need to be dark-on-light for light theme and light-on-dark for dark theme. This can be done by re-calling `enableEdgeToEdge()` with appropriate `SystemBarStyle` from a `DisposableEffect` or `LaunchedEffect` when the theme changes. This is the pattern recommended by the official Android documentation.

**Alternatives considered**:

| Alternative | Why Rejected |
|-------------|-------------|
| `WindowInsetsControllerCompat.isAppearanceLightStatusBars` | Works but requires accessing the Activity from Compose; `enableEdgeToEdge()` approach is more declarative |
| Fixed light status bar icons only | Breaks readability on dark theme backgrounds |

## Research Question: Does the Compose Multiplatform (KMP) shared code need changes, or is this Android-only?

### Decision: Primarily Android-only changes; minimal shared code adjustment

**Rationale**:
- `enableEdgeToEdge()` is Android-specific → goes in `MainActivity.kt`
- Scaffold in `App.kt` (shared code) already handles `innerPadding` correctly
- Scaffold's default `contentWindowInsets` uses `WindowInsets.systemBars` which is already cross-platform in Compose Multiplatform
- On iOS, safe area handling is managed separately by the platform
- The only shared code concern: ensure the Scaffold is not overriding `contentWindowInsets` to `WindowInsets(0, 0, 0, 0)` (it's not — it uses defaults)

**Conclusion**: The fix is entirely in `MainActivity.kt` and `AndroidManifest.xml`. No shared code changes required.

## Research Question: Does the GallrNavigationBar need inset padding for the gesture navigation area?

### Decision: No changes needed — Scaffold handles it

**Rationale**: When `GallrNavigationBar` is placed in Scaffold's `bottomBar` slot, Scaffold automatically reserves space for it and includes navigation bar insets in the `innerPadding`. The bottom bar itself is drawn above the navigation bar inset area by default. Scaffold adds the navigation bar inset padding below the `bottomBar` content, ensuring the gesture area is accounted for.

If the bottom bar needs to extend its background behind the navigation bar for visual continuity, that would require `Modifier.navigationBarsPadding()` on the bar's internal content — but this is a polish detail, not a correctness issue.
