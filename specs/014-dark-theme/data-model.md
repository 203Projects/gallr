# Data Model: Dark Theme with System Setting Toggle

**Feature**: 014-dark-theme
**Date**: 2026-03-24

## Entities

### ThemeMode (NEW)

Represents the user's theme preference. Lives in `shared/` module.

| Field | Type | Description |
|-------|------|-------------|
| LIGHT | enum value | Always use light theme |
| DARK | enum value | Always use dark theme |
| SYSTEM | enum value | Follow device setting (default) |

**Validation**: Only these three values are valid. Unknown stored values fall back to SYSTEM.

**Storage**: Persisted as a string (`"light"`, `"dark"`, `"system"`) in DataStore Preferences under key `"theme_mode"`.

## Repository Interfaces

### ThemeRepository (NEW)

| Method | Signature | Description |
|--------|-----------|-------------|
| observeThemeMode | `fun observeThemeMode(): Flow<ThemeMode>` | Emits current theme preference, updates on change |
| setThemeMode | `suspend fun setThemeMode(mode: ThemeMode)` | Persists theme preference |

**Implementation**: `ThemeRepositoryImpl` backed by DataStore Preferences. Same pattern as `LanguageRepositoryImpl`.

## Color Schemes

### gallrColorScheme() (EXISTING — unchanged)

Current light color scheme. No modifications needed.

### gallrDarkColorScheme() (NEW)

Dark variant using `darkColorScheme()` from Material3:

```
background     = #121212
onBackground   = #E0E0E0
surface        = #1E1E1E
onSurface      = #E0E0E0
surfaceVariant = #2C2C2C
onSurfaceVariant = #A0A0A0
primary        = #E0E0E0
onPrimary      = #121212
outline        = #404040
outlineVariant = #333333
```

### GallrAccent (EXISTING — unchanged)

The accent tokens (#FF5400) remain identical for both themes. No dark variant needed.

## State Flow

```
DataStore("theme_mode")
  → ThemeRepositoryImpl.observeThemeMode()
    → TabsViewModel.themeMode: StateFlow<ThemeMode>
      → App composable
        → GallrTheme(themeMode, isSystemDark)
          → MaterialTheme(colorScheme = light or dark)
```

The `isSystemInDarkTheme()` composable is evaluated inside `GallrTheme` and combined with the user's `ThemeMode` preference to resolve the effective color scheme.

## Dependency Injection Changes

### MainViewController.kt (iOS)

Add `ThemeRepository` parameter, same as existing `LanguageRepository` pattern.

### MainActivity.kt (Android)

Add `ThemeRepository` instantiation, same as existing `LanguageRepository` pattern.

### TabsViewModel

Add `ThemeRepository` to constructor. Expose `themeMode: StateFlow<ThemeMode>` and `setThemeMode(mode: ThemeMode)`.
