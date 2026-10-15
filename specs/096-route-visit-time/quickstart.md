# Quickstart: verifying "choose when you're going"

## Automated

```bash
./gradlew shared:allTests --tests "com.gallr.shared.route.*"
./gradlew composeApp:testAndroidHostTest --tests "com.gallr.app.ui.route.*" --tests "com.gallr.app.viewmodel.*"
./gradlew shared:ktlintCheck shared:allTests composeApp:ktlintCheck composeApp:allTests
./gradlew androidApp:ktlintCheck composeApp:testAndroidHostTest androidApp:lintDebug androidApp:assembleDebug
```

## Manual (Android emulator and iOS simulator, local stack)

1. After 18:00, open the composer with a route whose venues close at 18:00: the summary reports stops that won't work.
2. Open "방문 시간", tap 내일, then 13:00 (three taps): the sheet closes, the reference line reads "10.17(토) 13:00 기준" (the date, research R6) and
   the stops show tomorrow-afternoon visit windows with no warning.
3. Choose a Monday when a venue is closed: that stop reads "! 휴관일" and the route stays on Monday.
4. Add, remove and reorder stops: the chosen time stays. Leave the composer and return: it stays.
5. Choose "지금": the route reads exactly as before this feature.
6. Open a public route, choose a time: only the preview changes; reopen it and it starts at "지금".
7. Switch to English: day names, times and labels follow.
8. With a screen reader: the row reads its label; chips read as selectable options with their day or time.
