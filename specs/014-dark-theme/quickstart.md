# Quickstart: Dark Theme with System Setting Toggle

**Feature**: 014-dark-theme
**Date**: 2026-03-24

## Implementation Order

1. **ThemeMode enum** → `shared/.../data/model/ThemeMode.kt`
2. **ThemeRepository interface** → `shared/.../repository/ThemeRepository.kt`
3. **ThemeRepositoryImpl** → `shared/.../repository/ThemeRepositoryImpl.kt`
4. **Dark color scheme** → `composeApp/.../ui/theme/GallrColors.kt`
5. **GallrTheme update** → `composeApp/.../ui/theme/GallrTheme.kt`
6. **ViewModel update** → `composeApp/.../viewmodel/TabsViewModel.kt`
7. **Settings menu update** → `composeApp/.../App.kt`
8. **Platform entry points** → `MainActivity.kt`, `MainViewController.kt`

## Verification Steps

### 1. Build verification
```bash
./gradlew :shared:compileKotlinIosSimulatorArm64
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
./gradlew :composeApp:assembleDebug
```

### 2. Theme switching test
- Open app → should follow device theme (System default)
- Open gear menu → select "Dark" → app switches to dark immediately
- Select "Light" → app switches to light immediately
- Select "System" → app follows device setting
- Kill and reopen app → theme preference persists

### 3. Visual inspection (both themes)
- Featured tab: cards, text, accent colors
- List tab: segmented control, city chips, filter chips, country dropdown
- Map tab: mode toggle buttons, marker dialog
- Detail screen: all text, back button, bookmark icon
- Settings dropdown: menu background, text, dividers

### 4. Contrast verification
- Dark theme: verify text (#E0E0E0) on background (#121212) is clearly legible
- Dark theme: verify accent (#FF5400) stands out on dark surfaces
- Dark theme: verify card borders (#404040) are visible on dark background

## Acceptance Test Scenarios

| # | Scenario | Steps | Expected |
|---|----------|-------|----------|
| 1 | System default follows device | Set device to dark mode, launch app | App renders in dark theme |
| 2 | Manual dark override | Select "Dark" in settings | App switches to dark regardless of device |
| 3 | Manual light override | Select "Light" in settings | App switches to light regardless of device |
| 4 | Persistence | Select "Dark", kill app, reopen | App opens in dark theme |
| 5 | System follow | Select "System", toggle device theme | App follows device toggle |
| 6 | All screens dark | Navigate all tabs in dark mode | All elements visible and legible |
| 7 | Filter chips dark | Use filter chips in dark mode | Selected/unselected states clearly distinct |
| 8 | Settings dropdown dark | Open gear menu in dark mode | Menu background and text have proper contrast |
