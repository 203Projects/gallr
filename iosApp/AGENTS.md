# gallr iOS Host

This guide applies to `iosApp/`. Read the root [`CLAUDE.md`](../CLAUDE.md) first and read
[`DESIGN.md`](../DESIGN.md) before changing SwiftUI, the launch screen, screenshots, assets, or any
other visual behavior.

`iosApp` owns the native SwiftUI presentation, scene navigation, Xcode configuration, bundle
values, native assets, UI tests, and App Store archive/export wiring. The Kotlin framework owns
ViewModels, repositories, and portable behavior. Swift invokes shared actions and observes state
through `NativeGallrApp` in `iosNativeApp`. That module builds the framework imported in Swift as
`composeApp` and consumes the same application sources as Android.

## Build and verification

The SwiftUI host uses the system MapKit framework. The project has no Swift package dependencies.

```bash
./gradlew :iosNativeApp:verifyRendererFreeFramework :iosNativeApp:compileKotlinIosSimulatorArm64
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO build
```

Run `NativeNavigationTests` on iPhone and iPad. The iPad test resizes the same SwiftUI view from
compact to expanded and back while retaining the selected exhibition. `NativeWindowGeometryTests`
exercise the shared pane calculations for vertical and horizontal divisions and camera insets.
`NativeMyGallrTests` verify local diary persistence, preferences, and anonymous sign-in validation.
These tests and `MapInteractionTests` use the Debug `--native-fixture` catalogue. Store captures
use the regular app and production data.

The command-line host gate is arm64-only because the project no longer defines an `iosX64` KMP
target. Build and run the `iosApp` scheme in Xcode for host, Info.plist, signing, asset,
launch-screen, or native integration changes. For shared framework changes, also run the relevant
KMP tests and link task from the root guide. Store screenshots, the iOS 27 header and search
assets, and app previews come from `store-assets/` (see its `README.md`): `ScreenshotTests` captures
raw UI on two dedicated store simulators and `store-assets/build.mjs` composes the listing set into
`fastlane/screenshots/` for `fastlane upload_screenshots`. The capture step alone:

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

- Keep native presentation files in `iosApp/iosApp/Native/`, which Xcode includes automatically. Scope
  presentation state and navigation to the current scene. Retain shared services once at application
  scope. Cancel a Kotlin state observation when its SwiftUI owner disappears and release screen
  ViewModels when their navigation entries are discarded. Keep repository operations in Kotlin.
- The standard deployment target remains iOS 16. To exercise iPhone Duo full-display behavior,
  build with the iOS 27.1 SDK or later and select a Duo simulator running iOS 27.1 or later in Device
  Hub. Check the outer display, unfolded inner display, and book and tabletop poses while browsing
  the same selection. The older SDK jobs still exercise the native shell without reserved-region APIs.
- MapKit and Core Location own native map gestures and location presentation. Xcode embeds the
  `:iosNativeApp` framework with `embedAndSignAppleFrameworkForXcode`. The Kotlin framework ships
  without Compose, Skiko, or MapLibre. The Xcode resource phase includes the shared font files.
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
