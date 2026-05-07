# Design Spec: Default Language Korean + Profile Photo UX

**Date**: 2026-04-14
**Features**: Default Language Korean (P1), Profile Photo UX Improvements (P1)
**Status**: Approved

---

## Feature 1: Default Language Korean

### Problem

First-launch users on non-Korean-locale devices see the app in English. gallr is a Korea-focused app; Korean should be the universal default.

### Current Behavior

- `LanguageRepositoryImpl` accepts a `systemLanguage: () -> AppLanguage` lambda
- On first launch (no saved preference), it calls `systemLanguage()` which checks device locale
- If device locale is Korean -> KO, otherwise -> EN
- ViewModel initial state is already `AppLanguage.KO`

### Design

Hardcode `AppLanguage.KO` as the fallback. Remove the `systemLanguage` lambda entirely.

**Changes:**

1. **`LanguageRepositoryImpl.kt`** (shared module)
   - Remove `systemLanguage` constructor parameter
   - Change DataStore fallback from `systemLanguage()` to `AppLanguage.KO`

2. **`MainActivity.kt`** (Android)
   - Remove locale-detection lambda from `LanguageRepositoryImpl` constructor call

3. **`MainViewController.kt`** (iOS)
   - Remove locale-detection lambda from `LanguageRepositoryImpl` constructor call

**Behavior after change:**

| Scenario | Result |
|----------|--------|
| First launch, no saved preference | Korean |
| First launch, Korean device locale | Korean |
| First launch, English device locale | Korean |
| Existing user with English saved | English (preserved) |
| Existing user with Korean saved | Korean (preserved) |
| DataStore read failure | Korean (fallback) |
| User clears app data | Korean (no saved preference) |

### Out of Scope

- Adding new languages beyond Korean and English
- OS locale auto-detection for analytics

---

## Feature 2: Profile Photo UX Improvements

### Problem

In `EditProfileScreen.kt`:
1. A camera emoji ("camera") overlays the profile photo circle — violates Reductionist design system (no emoji UI elements)
2. The photo circle itself is the clickable tap target for the photo picker — non-standard UX
3. The "change photo / Change Photo" text label is passive (not clickable) — users expect it to be the action target

### Current Behavior

- Avatar circle (72dp) has `.clickable { pickImage() }` modifier (line 134)
- Camera emoji overlay rendered at bottom-right of circle (lines 171-187)
- "change photo / Change Photo" is a plain `Text` composable (lines 191-198)

### Design

Three changes in `EditProfileScreen.kt`:

1. **Delete camera emoji overlay** — remove the entire `Box` composable (lines 171-187) containing the emoji
2. **Remove `.clickable` from avatar circle** — the `Box` at line 129 keeps its shape/background but loses the click handler. Remove the `semantics { contentDescription }` related to "change photo" action since it's no longer interactive.
3. **Convert "change photo" Text to a clickable TextButton** — wrap the existing bilingual label in a `TextButton` that calls `pickImage()`. Style: `bodySmall`, consistent with app's interactive text patterns.

**Behavior after change:**

| Action | Result |
|--------|--------|
| Tap profile photo circle | No action (display-only) |
| Tap "change photo" button | Opens photo picker |
| Permission denied after tap | System dialog shown, no crash |
| No photo set | Placeholder shown; button still functional |
| Upload in progress | Button disabled (existing `isUploadingAvatar` guard) |

### Out of Scope

- Icon badge on photo circle (post-MVP consideration)
- Photo cropping/resizing
- Avatar fallback/initials redesign

---

## Testing Strategy

### Feature 1
- Unit test: `LanguageRepositoryImpl` returns `AppLanguage.KO` when no preference is saved (no lambda needed)
- Unit test: saved preference (`EN` or `KO`) is respected over default

### Feature 2
- UI test: camera emoji is not rendered in the composable tree
- UI test: tapping avatar circle does not trigger photo picker
- UI test: tapping "change photo" button triggers photo picker
- UI test: button is disabled during upload
