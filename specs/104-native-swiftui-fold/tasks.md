# Tasks

- [x] Add and test retained Kotlin state observation and disposal for the SwiftUI application boundary.
- [x] Wire existing Kotlin startup, repositories, view models, notification services, deep links, and app lifecycle into the SwiftUI entry point.
- [x] Implement native catalogue, details, account, diary, and routes using the existing Kotlin contracts.
- [x] Exercise selection and navigation across compact and wide layouts, including device rotation and keyboard changes.
- [x] Run the shared and Android gates and build the SwiftUI app for Simulator and device.

## Verification

- The observer test failed before the bridge function existed and passed after state emission and cancellation were implemented.
- The native module linked for arm64 Simulator Debug and arm64 device Release. The renderer dependency gate passed for both targets, and the complete Swift target type-checked for iOS 16.
- After the framework split, Kotlin lint passed for both application modules. The Android host suites passed 904 Compose and 973 Shared tests. Android lint and Debug APK assembly passed.
- The Kotlin simulator suites passed 1,007 Compose and 969 Shared tests. The renderer-free iPhone and iPad Simulator apps both passed the route draft journey, which adds a fixture stop, leaves the composer, and reopens the draft under its saved name. The iPad compact-to-expanded fixture preserved the active catalogue selection.
- The uncompressed Debug Simulator app measured 51 MiB. The first SwiftUI bundle with Compose still linked measured 103 MiB. The unsigned Release device app measured 35 MiB, and its executable measured 33,307,768 bytes. The Release Kotlin static archive measured 52,451,784 bytes, compared with 210,258,904 bytes for the Compose-linked archive. The native executables had zero Compose runtime, Skiko, and MapLibre symbol matches under `nm`.
- The staging preflight's shell syntax and network-free environment regression passed with the new iOS paths protected.

- The application code at `e9c16fcd` passed a clean Debug Simulator build-for-testing and an unsigned arm64 Release device build with Xcode 27.2. The device bundle included four native font files.
- On the iOS 26.5 iPhone and iPad simulators, the fixture tests covered independent tab trails, rotation, route draft persistence, a private diary note saved and reopened after app relaunch, language and theme changes, and anonymous sign-in validation. The wider-window test passed on iPad and was skipped on iPhone. It resized the same fixture view from compact to expanded and back, checked the list and detail frames while expanded, and retained the same exhibition selection. Geometry tests exercised the production calculations for vertical and horizontal divisions and camera insets.
- Opening a synthetic APNs-format payload from Notification Center on a disposable iOS 26.5 iPhone simulator opened the exact fixture exhibition in the native app. Four shared mock-transport tests passed for the PKCE callback. Live APNs delivery and sign-in against an OAuth provider were outside these checks.
- The iPhone Duo device type was available in Xcode, but the iOS 27.2 Duo runtime (build `24B91016s`) was unavailable locally. Its download required 8.32 GB and additional staging space. Only the iOS 26.5 runtime was installed, and no paired iPhone Duo was available. The iPad viewport and geometry tests cover layout behavior programmatically. Actual Duo poses and hardware remain unverified.
- The map cluster pinch, tap, and exhibition-selection test passed independently on each simulator. The simultaneous suite runs reached the same map state but timed out injecting a pinch on both devices. The standalone reruns passed with the same binaries.
- Both live-data iPhone screenshot journeys passed separately from the fixtures. The preview visited Home, the curator’s note, List, and Map. The store capture generated all five numbered images. These checks depend on the production catalogue and a published curator note.
