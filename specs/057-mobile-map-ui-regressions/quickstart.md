# Quickstart: Mobile Map UI Regressions

## Automated checks

```bash
./gradlew composeApp:testAndroidHostTest --tests \
  'com.gallr.app.ui.tabs.map.SeoulDistrictMapDataTest'
./gradlew composeApp:ktlintCheck composeApp:allTests
./gradlew androidApp:ktlintCheck androidApp:lintDebug androidApp:assembleDebug
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:iosAppUITests/MapInteractionTests/testPinOriginPinchArbitration test
```

## iOS viewport check

1. Build and run `iosApp` on a modern iPhone simulator.
2. Capture Featured and Map screenshots.
3. Confirm the Compose background reaches both physical screen edges.
4. Confirm the top app bar clears the status area once and the bottom labels clear the home
   indicator once.

## Map interaction check

On Android and iOS:

1. Open Map with a catalogue containing an exact-coordinate pair and two merely nearby venues.
2. Confirm the exact pair is one counted marker and nearby venues remain separate while zooming.
3. Single-tap each marker and confirm the detail or overlap sheet opens once without a grey box.
4. Begin a pinch with one finger centered over a marker; repeat five times and confirm every gesture
   changes zoom without opening marker content.
5. With a screen reader active, focus a single and grouped marker and invoke their actions.
