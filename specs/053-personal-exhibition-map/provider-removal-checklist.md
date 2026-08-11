# Embedded Naver Map Removal Checklist

This checklist covers the mobile embedded SDK only. It does not authorize removing the Eleventy web
map, server-side Naver geocoding, or external Naver Map direction links.

## Current mobile call sites

- Common contract: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapView.kt`
- Android implementation: `composeApp/src/androidMain/kotlin/com/gallr/app/ui/tabs/map/MapView.android.kt`
- iOS implementation: `composeApp/src/iosMain/kotlin/com/gallr/app/ui/tabs/map/MapView.ios.kt`
- Common composition: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapScreen.kt`

## Android removal after US2 verification

- Remove `naver-map-sdk` and `naver-map-compose` aliases/versions from `gradle/libs.versions.toml`.
- Remove both dependencies from `composeApp/build.gradle.kts`.
- Remove the `com.naver.maps.map.NCP_KEY_ID` Android manifest metadata only after no embedded map is
  instantiated. Do not expose or rotate its configured value.

## iOS removal after US2 verification

- Remove every `NMapsMap` cinterop block and DerivedData framework-discovery helper from
  `composeApp/build.gradle.kts`.
- Remove `composeApp/src/nativeInterop/cinterop/NMapsMap.def` and the NMaps-only UIUtilities cinterop
  stub if no other interop consumes it.
- Remove `NMapsMap` imports/auth setup from `iosApp/iosApp/iOSApp.swift`.
- Remove the `SPM-NMapsMap` package/product references from
  `iosApp/iosApp.xcodeproj/project.pbxproj` and refresh `Package.resolved` through Xcode/SPM.

## Documentation/configuration follow-up

- Update the mobile architecture/dependency statements in `README.md` and `CLAUDE.md`.
- Preserve server-only `NAVER_MAPS_API_KEY_ID`/`NAVER_MAPS_API_KEY` documentation for geocoding.
- Preserve web-map Naver SDK documentation unless the web product receives a separate approved change.

## Exit criteria

- Abstract map plus accessible list covers every former embedded-map discovery action.
- Near Me ranks by original latitude/longitude.
- Directions hand the original coordinate to installed Naver Map or a system/web fallback.
- Android assembly and iOS simulator framework compile without Naver SDK/cinterop artifacts.
- Repository search shows no mobile `NMapsMap`, `com.naver.maps.map`, or embedded `MapView` reference.

## Completion — 2026-08-11

- Removed the unused common/Android/iOS embedded `MapView` path and its obsolete grouping tests.
- Removed Android Naver dependencies and manifest metadata.
- Removed iOS NMaps cinterop, compatibility stubs, authentication setup, SPM product, and resolved packages.
- Preserved web Naver Maps, server geocoding, and the future provider-neutral external-navigation task.
- Passed `composeApp:allTests`, `composeApp:assembleDebug`, and
  `composeApp:linkReleaseFrameworkIosSimulatorArm64` with MapLibre as the sole embedded mobile map.
