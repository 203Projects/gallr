# gallr iOS Host

This guide applies to `iosApp/`. Read the root [`CLAUDE.md`](../CLAUDE.md) first and read
[`DESIGN.md`](../DESIGN.md) before changing SwiftUI, the launch screen, screenshots, assets, or any
other visual behavior.

`iosApp` is a thin native host. Product UI, ViewModels, repositories, and reusable behavior belong
in the KMP modules. Swift owns application startup, Xcode configuration, bundle values, the Compose
bridge, native assets, UI-test screenshots, and App Store archive/export wiring.

## Build and verification

Resolve the MapLibre Swift package before a cold Gradle iOS build:

```bash
xcodebuild -resolvePackageDependencies -project iosApp/iosApp.xcodeproj
./gradlew :composeApp:compileKotlinIosSimulatorArm64
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO build
```

The command-line host gate is arm64-only because the project no longer defines an `iosX64` KMP
target. Build and run the `iosApp` scheme in Xcode for host, Info.plist, signing, asset,
launch-screen, or native integration changes. For shared framework changes, also run the relevant
KMP tests and link task from the root guide. Screenshot work uses the lanes documented in
`fastlane/Fastfile`:

```bash
cd iosApp
fastlane screenshots
```

Xcode Cloud runs `ci_scripts/ci_post_clone.sh` to provide JDK 17 when the selected image does not
include Java. Keep the script executable. The Kotlin/Native build phase first uses an installed JDK
17 and otherwise resolves the Homebrew `openjdk@17` formula installed by that script. Validate shell
changes with `sh -n iosApp/ci_scripts/ci_post_clone.sh` and a Release device build before starting a
new hosted archive.

## Host and configuration boundaries

- Keep `ContentView` and `iOSApp` as composition/bridge code. Do not move portable business rules or
  screen state into Swift to work around a shared-module design issue.
- MapLibre resolves through Xcode SPM; Gradle cinterop discovers its xcframework from Xcode
  Cloud's `CI_DERIVED_DATA_PATH`, with the standard local DerivedData directory as fallback. Do not
  replace the package with an unreviewed binary or hardcode a machine-specific DerivedData path.
- `Info.plist` reads catalogue and Supabase values from Xcode build settings. Use
  `GALLR_SUPABASE_PUBLISHABLE_KEY`; `GALLR_SUPABASE_ANON_KEY` is a lower-priority migration fallback.
  Debug uses the legacy reader; Release uses `canonical-v2`. A staging canary must override URL,
  publishable key, and reader together so it cannot mix environments.
- Public Supabase configuration may be bundled; secret/service-role keys may not. Obtain any
  credential-dependent value from the matching 1Password item and never place signing secrets,
  provider secrets, or private keys in the project file, plist, Swift source, logs, or screenshots.
- Keep `MARKETING_VERSION` synchronized with root `VERSION` and increment
  `CURRENT_PROJECT_VERSION` through the release workflow. Do not casually change bundle identifiers,
  development team, entitlements, signing style, or export method.

## Release boundary

`fastlane archive` builds and exports an App Store Connect archive without uploading it. Creating an
archive does not authorize upload, screenshot publication, TestFlight distribution, App Review
submission, certificate/profile replacement, or production configuration changes. Those are
separate approved actions. Preserve the existing registered signing identity and use the checked-in
`ExportOptions-AppStore.plist`; diagnose an archive failure from the current Xcode/Xcode Cloud log
before changing signing configuration.
