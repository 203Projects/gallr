# Tasks

- [x] Add and test retained Kotlin state observation and disposal for the SwiftUI application boundary.
- [x] Wire existing Kotlin startup, repositories, view models, notification services, deep links, and app lifecycle into the SwiftUI entry point.
- [x] Implement native catalogue, details, account, diary, and routes using the existing Kotlin contracts.
- [ ] Exercise selection and navigation across compact and wide layouts, including device rotation and keyboard changes.
- [x] Run the shared and Android gates and build the SwiftUI app for Simulator and device.

## Verification

- The observer test failed before the bridge function existed and passed after state emission and cancellation were implemented.
- The native module linked for arm64 Simulator Debug and arm64 device Release. The renderer dependency gate passed for both targets, and the complete Swift target type-checked for iOS 16.
- Xcode built the native Debug Simulator app and the unsigned Release device app. After the framework split, Kotlin lint passed for both application modules. The Compose and Shared Android host suites, Android lint, and Debug APK assembly passed.
- The draft creation and reopening journey passed on iPhone and iPad Simulator before the framework switch. It adds a fixture catalogue stop, leaves the composer, and reopens the draft under its saved name.
- The uncompressed Debug Simulator app measured 51 MiB. The first SwiftUI bundle with Compose still linked measured 103 MiB. The unsigned Release device app measured 35 MiB with a 32 MiB executable. The Release Kotlin static archive measured 52,451,784 bytes, compared with 210,258,904 bytes for the Compose-linked archive. The native executables had zero Compose runtime, Skiko, and MapLibre symbol matches under `nm`. The application bundle retains four native fonts.
