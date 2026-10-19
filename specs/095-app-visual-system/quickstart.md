# Quickstart: verifying the Home style across the app

## 1. Automated

```bash
./gradlew composeApp:testAndroidHostTest --tests "com.gallr.app.ui.theme.*" --tests "com.gallr.app.ui.tabs.map.MapStyleLanguageTest"
./gradlew composeApp:ktlintCheck composeApp:allTests
./gradlew androidApp:ktlintCheck composeApp:testAndroidHostTest androidApp:lintDebug androidApp:assembleDebug
```

## 2. Visual pass (Android emulator and iOS simulator, light and dark)

For each screen, compare with Home: cards and tiles 16dp with clipped pictures, small pieces 12dp, captions on
pictures white on a black scrim, Home-style headings, the accent only in its three roles.

1. 목록: exhibition cards (with and without a cover), filter chips, search field, gallery results, promoted band.
2. 지도: zoom / recentre controls, scope panel, overlapping-exhibitions sheet; map labels in Korean, then English.
3. MY: account invitation, visit and followed-gallery rows, 동선 section, add-visits and add-galleries screens.
4. Exhibition page: buttons, add-to-route, thoughts, curator note chips, gallery counter (square pager, rounded pill).
5. Gallery page: panels, cover placeholder, follow / alert buttons, alert sheet.
6. Event page: hero caption on the scrim (square hero), venue chip.
7. For You, a collection, the editor pages.
8. Route composer, planner, public route preview: stop cards, map panel, full-screen map controls, sheets, dialogs,
   snackbars.
9. Sign-in, profile, edit profile, pending thoughts, settings and its dialogs.
10. Largest font size on screens 1, 4 and 8: nothing clipped by a corner.
11. Exports: share an exhibition and a route; the images look exactly as before.
