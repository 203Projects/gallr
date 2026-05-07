# Research: Dark Theme with System Setting Toggle

**Feature**: 014-dark-theme
**Date**: 2026-03-24

## R1: Compose Multiplatform Dark Theme Detection

**Decision**: Use `isSystemInDarkTheme()` from `androidx.compose.foundation` to detect device dark mode setting.

**Rationale**: This is the standard Compose API for detecting system theme. It works on both Android (via `Configuration.uiMode`) and iOS (via `UITraitCollection.userInterfaceStyle`) in Compose Multiplatform 1.8.0. No platform-specific code needed.

**Alternatives considered**:
- Manual platform `expect/actual` for dark mode detection — unnecessary since Compose Multiplatform already provides the cross-platform composable.
- Reading iOS `UIScreen.traitCollection` via cinterop — overly complex, not needed.

## R2: Dark Color Palette Design

**Decision**: Use Material dark surface baseline (#121212) with the following palette:

| Token | Light | Dark |
|-------|-------|------|
| background | #FFFFFF | #121212 |
| onBackground | #000000 | #E0E0E0 |
| surface | #FFFFFF | #1E1E1E |
| surfaceVariant | #F5F5F5 | #2C2C2C |
| onSurfaceVariant | #525252 | #A0A0A0 |
| outline | #000000 | #404040 |
| outlineVariant | #E5E5E5 | #333333 |
| accent | #FF5400 | #FF5400 (unchanged) |

**Rationale**: Follows Material Design 3 dark theme guidelines. Near-black (#121212) instead of pure black reduces eye strain and allows elevation to be expressed through lighter surfaces. The accent #FF5400 has 5.2:1 contrast ratio on #121212 (passes WCAG AA). Text at #E0E0E0 on #121212 gives 13.3:1 contrast (exceeds WCAG AAA).

**Alternatives considered**:
- Pure black (#000000) background — causes harsh contrast on OLED, "ink pool" effect, and prevents surface elevation.
- Desaturated accent for dark mode — unnecessary; #FF5400 already provides excellent contrast on dark backgrounds.

## R3: Theme Preference Persistence

**Decision**: Store theme preference as a string in DataStore Preferences using key `"theme_mode"` with values `"light"`, `"dark"`, `"system"`. Follow the exact same pattern as the existing `LanguageRepositoryImpl`.

**Rationale**: DataStore is already used for language preference and bookmarks. Reusing the same mechanism keeps the codebase consistent with zero new dependencies.

**Alternatives considered**:
- SharedPreferences (Android) / UserDefaults (iOS) via expect/actual — violates Shared-First principle and introduces platform divergence.
- In-memory only (no persistence) — fails FR-003 (must persist across launches).

## R4: Theme Flash Prevention on Launch

**Decision**: Set the initial theme based on the persisted preference before the first frame renders. The `GallrTheme` composable reads the theme state from the ViewModel, which initializes from DataStore. Since DataStore emits the cached value synchronously on first read (from disk cache), there should be no flash.

**Rationale**: DataStore preferences are small and load quickly. The first emission from `dataStore.data` uses the in-memory cache if available. Combined with the "System" default, the initial render will match the device theme even before DataStore finishes loading.

**Alternatives considered**:
- Splash screen to mask loading — overengineered for a single preference read.
- Platform-specific window background color — adds platform code for marginal benefit.

## R5: Settings Menu Integration

**Decision**: Add a theme submenu to the existing gear dropdown in the top app bar. Display as three options: "Light" / "Dark" / "System" with a checkmark or indicator on the currently selected option.

**Rationale**: The settings dropdown already exists with language toggle and privacy policy. Adding theme selection here follows the established UI pattern. Three options is simple and standard (matches iOS Settings and Android system UI patterns).

**Alternatives considered**:
- Dedicated settings screen — overengineered for 3 options; violates Simplicity principle.
- Cycle-through button (tap to cycle Light → Dark → System) — less discoverable, users can't see all options at once.
