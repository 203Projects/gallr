# Splash Screen — Design Spec

**Date:** 2026-04-25
**Status:** Design (approved, pending implementation plan)
**Source brief:** `260424-splash-screen-p2.md`
**Priority:** P2

---

## 1. Problem

gallr currently shows no branded launch experience — the app jumps directly into the main UI as it loads. This misses a brand moment at the point of highest user attention (first open) and on slower connections the app can feel unfinished as content streams in after first render.

## 2. Outcome

On cold launch, the user sees a branded splash matching their saved theme (light: white bg + black logo; dark: `#121212` bg + `#E0E0E0` logo) with the arch-pin gallr logo centered. The splash holds for at least 1.5s and until exhibition data is ready, with a 3s hard cap. The transition is consistent across Android and iOS.

## 3. Decisions locked from brainstorm

| Question | Decision |
|---|---|
| Q1 — Fast error treatment | `Error` is "data-ready" (option B). Splash dismisses at 1.5s min if `featuredState !is Loading`. Hard cap 3s only matters if state stays `Loading`. |
| Q2 — Native → Compose hand-off fidelity | **Pixel-perfect, per platform** (option A). Compose overlay logo size matches what the native splash actually renders on each platform — *not* a single cross-platform size. |
| Q3 — Theme-flash prevention | **Native splash holds until theme loads** (option A). Android via `SplashScreen.setKeepOnScreenCondition`; Android resource qualifiers `values-night/`. iOS storyboard is white-only by necessity (storyboards can't be theme-aware) — Compose overlay applies the resolved theme on its first frame. |
| Logo entrance animation (Nice to Have) | **Skipped for v1.** Would create visible difference between native splash (logo at 100% opacity) and Compose overlay (fading from 0%). If desired later, must apply to both surfaces. |

## 4. Architecture

```
[Native splash]
  Android: SplashScreen API, theme-qualified bg + icon, holds until themeReady
  iOS:     LaunchScreen.storyboard, white bg + arch-pin logo (light only)
       │
       │  Hand-off when themeReady = true
       ▼
[Compose splash overlay]
  - Full-screen Box at zIndex Float.MAX_VALUE
  - Reads ThemeMode from DataStore (already resolved by hand-off)
  - Renders arch-pin logo at platform-matched size/position
  - Owns three gates: dataReady, minTimeElapsed, hardCapElapsed
  - Dismisses self with AnimatedVisibility(fadeOut 200ms)
       │
       │  Dismissal when (themeReady AND dataReady AND ≥1.5s) OR ≥3s
       ▼
[Main UI]  Featured tab, populated, no loading flash
```

### Two timing gates

- **Theme-loaded gate** — blocks the **native → Compose** hand-off. Theme loads in <50ms (warm DataStore read), so this is invisible after first install.
- **Data-ready + min-time + hard-cap gates** — blocks the **Compose overlay → main UI** transition. The 1.5s minimum and 3s cap apply here.

### State ownership

`SplashController` (commonMain) owns the lifecycle of one cold launch. Created in `MainActivity.onCreate` / `MainViewController()`, passed into `App()`, eligible for GC after dismissal animation completes (~3.2s max from launch).

```kotlin
class SplashController(
    private val minVisibleMs: Long = 1500,
    private val hardCapMs: Long = 3000,
    private val clock: Clock = Clock.System,
    private val scope: CoroutineScope,
) {
    private val themeReady = MutableStateFlow(false)
    private val dataReady = MutableStateFlow(false)
    private val minTimeElapsed = MutableStateFlow(false)
    private val hardCapElapsed = MutableStateFlow(false)

    val isVisible: StateFlow<Boolean> = combine(
        themeReady, dataReady, minTimeElapsed, hardCapElapsed
    ) { theme, data, min, cap ->
        !((theme && data && min) || cap)
    }.stateIn(scope, SharingStarted.Eagerly, true)

    fun start() {
        scope.launch { delay(minVisibleMs); minTimeElapsed.value = true }
        scope.launch { delay(hardCapMs);    hardCapElapsed.value = true }
    }

    fun markThemeReady() { themeReady.value = true }
    fun markDataReady()  { dataReady.value = true }
}
```

### Cold-launch-only guarantee

The controller is created fresh in Activity / iOS app entry. Background → foreground does NOT recreate it. Android config-change recreation is suppressed via `if (savedInstanceState != null) controller.skipSplash()` in `MainActivity.onCreate`.

## 5. Components & files

**New files:**

```
composeApp/src/commonMain/kotlin/com/gallr/app/splash/
├── SplashController.kt
└── SplashOverlay.kt

composeApp/src/androidMain/res/values/themes.xml         (modify — add splash theme)
composeApp/src/androidMain/res/values-night/themes.xml   (new)
composeApp/src/androidMain/res/drawable/splash_logo.xml  (new — vector arch-pin)
composeApp/src/androidMain/res/values/colors.xml         (modify — splash bg colors)

iosApp/iosApp/LaunchScreen.storyboard                    (new)
iosApp/iosApp/Assets.xcassets/SplashLogo.imageset/       (new)
iosApp/iosApp/Info.plist                                 (modify — UILaunchStoryboardName)

composeApp/src/commonMain/kotlin/com/gallr/app/splash/
└── SplashLogoSize.kt        expect val splashLogoDp: Dp
                             android = 192.dp (matches Android 12+ default
                                       windowSplashScreenAnimatedIcon size)
                             ios     = 72.dp  (matches storyboard we author)
```

**Modified files:**

```
composeApp/src/commonMain/kotlin/com/gallr/app/App.kt
  - Accept SplashController parameter
  - Wrap existing root Box in another Box; SplashOverlay overlaid at top zIndex
  - Wire featuredState collection → splashController.markDataReady() on
    first non-Loading state
  - Wire themeRepository first-real-emit → markThemeReady()

composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt
  - installSplashScreen() before super.onCreate()
  - Create SplashController; setKeepOnScreenCondition { !controller.themeReady.value }
  - if (savedInstanceState != null) controller.skipSplash()
  - controller.start()
  - Pass controller to App()

composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt
  - Create SplashController in factory
  - controller.start()
  - Pass to App()
  - Storyboard handles native splash; no Kotlin-side native handling
```

### Logo sizing — platform-matched, intentional asymmetry

The brief asks for "72dp logo". Android's `SplashScreen` API enforces a specific icon size based on device window insets (~192dp effective on most phones for the default windowed splash). Compose-side logo size is a per-platform constant matching what the native splash actually renders, **not** a single cross-platform value. iOS authors the storyboard ourselves at 72dp; Compose iOS matches.

This asymmetry is the cost of pixel-perfect hand-off (Q2 decision). Document in the implementation plan; verify by side-by-side device screenshots during QA.

## 6. Data flow

**Cold launch:**

```
MainActivity.onCreate
  ↓
  installSplashScreen() — system splash visible immediately
  ↓
  Create SplashController; controller.start() — timers begin
  ↓
  setKeepOnScreenCondition { !themeReady } — native splash held
  ↓
setContent { App(splashController = ...) }
  ↓
  themeRepository.observeThemeMode() — first real emit
    → controller.markThemeReady()
    → native splash dismisses, Compose overlay takes over (already mounted)
  ↓
  featuredState collection — first non-Loading state
    → controller.markDataReady()
  ↓
  When (themeReady AND dataReady AND ≥1.5s) OR ≥3s:
    isVisible flips false
    → AnimatedVisibility fades out 200ms
    → Main UI revealed
```

**Background → foreground (no splash):**

The Activity is not destroyed; `SplashController` from previous launch is still alive but in dismissed state. App composition resumes with `isVisible = false`. No splash.

## 7. Edge cases

| Scenario | Behavior |
|---|---|
| Data fetch fails fast (200ms) | `Error` triggers `markDataReady()` (Q1). Splash holds the full 1.5s min, then dismisses to error state. |
| Data fetch slow (>1.5s) | Splash holds until `Success`/`Error` arrives, then dismisses. |
| Data fetch slow (>3s) | Hard cap dismisses splash at 3s; main UI shows loading state. |
| App restored from background | No splash (controller not recreated). |
| Force-quit + re-open | Treated as cold launch — new controller, splash appears. |
| Android config change (rotation) during splash | `savedInstanceState != null` → `controller.skipSplash()`. Main UI shows immediately on rotated configuration. |
| Theme load fails (DataStore exception) | `themeReady` never flips → hard cap takes over at 3s. Main UI inherits whatever default theme renders. (Highly unlikely; documented for completeness.) |
| iOS dark-mode user, very first launch | Storyboard shows white briefly; Compose overlay applies dark theme. Single first-launch flash; subsequent launches read warm DataStore in <10ms. Documented limitation. |

## 8. Testing

### Unit tests — `SplashController` (commonMain, with `runTest` virtual time)

1. Happy path: `markThemeReady()` → `markDataReady()` at t=200ms → advance to t=1500ms → `isVisible` flips false at 1500ms.
2. Slow data: `markThemeReady()` → t=1500ms (still visible) → `markDataReady()` at t=2000ms → flips false at 2000ms.
3. Hard cap: `markThemeReady()` → never `markDataReady()` → t=3000ms → flips false.
4. Fast error: `markDataReady()` for both `Success` and `Error` paths — verify by calling at t=200ms, splash dismisses at 1500ms.
5. Theme never ready: hard cap still dismisses at 3000ms.
6. Idempotent: multiple `markDataReady()` calls — no error, no state oscillation.
7. `skipSplash()` (config change): immediately flips `isVisible` to false.

### Manual verification

- **Android cold launch (light + dark)** — fresh install, `adb shell am start -W ...` per theme. Verify zero system white flash; logo position/size matches between native splash and Compose overlay (no jump); 200ms fade-out; Featured tab populated on dismissal.
- **iOS cold launch (light + dark)** — Xcode + simulator + physical device. Storyboard renders white + 72dp logo; Compose overlay applies dark theme cleanly when saved theme is dark; smooth fade.
- **Slow connection** — Network Link Conditioner @ Edge: splash holds past 1.5s, hard-caps at 3s.
- **Background → foreground** — 5min background, return — no re-splash. Repeated rapid cycles — no re-splash.
- **Force-quit → re-open** — splash reappears.
- **Android config change** — rotate during splash → splash gone after rotation. Rotate after dismissal → splash does not reappear.

### Out of scope for testing

- No instrumented Espresso/XCUITest splash tests (too brittle for fade animations).
- No A/B testing.

## 9. Out of scope (v1)

- Logo entrance animation (Nice to Have in brief; rejected for v1 per architecture rationale).
- Custom illustrations or animated logos.
- Onboarding / walkthrough.
- Any user interaction on splash.
- Web build (mobile-only feature).

## 10. Success metrics

- Zero visible flash of unloaded app UI on cold launch.
- 1.5s consistent brand minimum across Android + iOS.
- Always dismisses within 3s, regardless of network state.
- Theme correct on Compose overlay's first frame on subsequent launches (warm DataStore).

## 11. Open questions for implementation phase

- Exact Android icon size to match in Compose overlay — must measure on Pixel + Samsung devices. Spec assumes 192dp; verify.
- Whether `windowSplashScreenAnimatedIcon` requires PNG fallback for Android <12. Spec assumes vector drawable works on 12+; older devices fall back to system default.
