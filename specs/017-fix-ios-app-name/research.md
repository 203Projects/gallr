# Research: Fix iOS App Display Name

**Feature**: 017-fix-ios-app-name
**Date**: 2026-03-26

## Research Question: How does iOS determine the app name displayed on the device?

### Decision: Add `CFBundleDisplayName` to Info.plist

**Rationale**: iOS uses a priority chain to determine the app name shown on the home screen:

1. `CFBundleDisplayName` (highest priority — user-visible name)
2. `CFBundleName` (fallback — bundle name)
3. Executable name (last resort)

Currently, the project has no `CFBundleDisplayName` set. `CFBundleName` is set to `$(PRODUCT_NAME)`, which resolves to `$(TARGET_NAME)` → "iosApp" (the Xcode target name). Adding `CFBundleDisplayName` with value "gallr" is the correct, minimal fix.

**Alternatives considered**:

| Alternative | Why Rejected |
|-------------|-------------|
| Change `PRODUCT_NAME` build setting to "gallr" | Affects binary name, bundle structure, and framework linking — much broader impact than needed |
| Rename the Xcode target from "iosApp" to "gallr" | Requires updating all target references in .pbxproj, schemes, and potentially CI — high risk for a display-only fix |
| Set `CFBundleName` directly to "gallr" (remove build variable) | Would work but `CFBundleDisplayName` is the Apple-recommended approach for controlling display name independently of bundle name |

## Research Question: Does CFBundleDisplayName need localization?

### Decision: No localization needed

**Rationale**: "gallr" is a brand name, not a translatable word. It should appear identically across all locales. No `InfoPlist.strings` localization files are needed.

**Alternatives considered**:

| Alternative | Why Rejected |
|-------------|-------------|
| Add localized `InfoPlist.strings` for each language | Unnecessary — brand name is universal; adds maintenance burden for no benefit |
