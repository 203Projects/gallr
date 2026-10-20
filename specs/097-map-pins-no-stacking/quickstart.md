# Quickstart: verifying map pins without stacking

## Automated

```bash
./gradlew composeApp:testAndroidHostTest --tests "com.gallr.app.ui.tabs.map.*"
./gradlew composeApp:ktlintCheck composeApp:allTests
./gradlew androidApp:ktlintCheck composeApp:testAndroidHostTest androidApp:lintDebug androidApp:assembleDebug
```

## Manual (Android emulator and iOS simulator, local stack)

1. Open the Map tab: the city view shows count pins as before (for example "6" over Hannam).
2. Tap the "6" pin: the map zooms to Hannam and shows six pins, each with its own cover; none overlap; no sheet opens.
3. Exhibitions at one gallery show around that gallery's place, one of them on it.
4. Tap each moved pin: its own exhibition opens.
5. Zoom out with − until the pins merge into count pins again; zoom in with + and they separate at the same places.
6. Pan at neighbourhood zoom: the pins move with the map and keep their arrangement; nothing flickers once still.
7. Save one exhibition among moved pins: its ring turns orange and it draws on top.
8. Switch to "내 전시": the same rules apply to saved pins.
9. With TalkBack / VoiceOver: moved pins read their exhibition names; a count pin reads "전시 N개 · 확대".
10. Open a route map (composer, preview, full screen): numbered stop pins are unchanged.
