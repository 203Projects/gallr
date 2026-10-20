# Quickstart: verifying the always-visible tab bar

## 1. Automated

```bash
./gradlew composeApp:testAndroidHostTest --tests "com.gallr.app.AppNavigationStateTest"
./gradlew composeApp:iosSimulatorArm64Test --tests "com.gallr.app.TabNavigationShellTest"
./gradlew composeApp:ktlintCheck composeApp:allTests
./gradlew androidApp:ktlintCheck composeApp:testAndroidHostTest androidApp:lintDebug androidApp:assembleDebug
```

## 2. Manual, on the Android emulator and the iOS simulator

1. **Bar everywhere (US1).** From Home open an exhibition, its gallery, an event, an editor, For You and a
   collection; from MY open the route composer, a public route preview, settings and sign-in. On each: the bar is
   visible, the owning tab is highlighted, and the last item of the screen scrolls fully above the bar.
2. **Action bars (FR-010).** In the composer and the public route preview, the 저장 / 공유 and 복사 bars sit directly
   above the tab bar with no gap and no overlap.
3. **Each tab remembers (US2).** Open an exhibition from Home and scroll halfway; tap 지도, then 홈: same exhibition,
   same scroll. Type a route name in the composer without saving, switch tabs and back: the name is still there.
4. **Re-tap (FR-006).** Two screens deep in 목록, tap 목록: its top screen. Tap it again: nothing changes.
5. **Back (US3).** Home → exhibition → gallery: back, back reaches Home's top. Android system back at a tab's top
   behaves as before this feature.
6. **Sign-in (FR-008).** Signed out, try to save a route in the composer: sign-in opens over the composer in the same
   tab; cancel returns to the composer, and signing in returns to it with the save carried on.
7. **Keyboard (US4).** Focus the composer's name field, the stop search and the sign-in fields: the bar is hidden while
   the keyboard is open and returns when it closes.
8. **Full-screen map (FR-012).** Expand a route map: it fills the space above the bar; close and fit route work.
9. **Covers (FR-001).** Open a bottom sheet, a dialog and the profile picture crop: each may cover the bar.
10. **Large text.** At the largest font size repeat steps 1 and 2.
