# Splash Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a branded cold-launch splash screen on Android and iOS that respects saved theme, holds for 1.5s minimum + until exhibition data is ready, hard-caps at 3s, then fades out.

**Architecture:** Native platform splash (Android `SplashScreen` API + theme-qualified resources; iOS `LaunchScreen.storyboard`) holds until DataStore-resolved theme is ready, then a Compose overlay at top zIndex takes over with three independent gates (data-ready, min-time, hard-cap). Overlay dismisses itself with a 200ms fade.

**Tech Stack:** Kotlin 2.1.20, Compose Multiplatform 1.8.0, `androidx.core:core-splashscreen` (Android), `LaunchScreen.storyboard` (iOS), kotlinx-coroutines-test (TDD).

**Spec:** `docs/superpowers/specs/2026-04-25-splash-screen-design.md`

---

## File Structure

**New files:**

```
composeApp/src/commonMain/kotlin/com/gallr/app/splash/
├── SplashController.kt        State machine, three gates, virtual-time testable
├── SplashOverlay.kt           Composable: full-screen Box + logo, AnimatedVisibility
└── SplashLogoSize.kt          expect val splashLogoDp: Dp (per-platform)

composeApp/src/androidMain/kotlin/com/gallr/app/splash/
└── SplashLogoSize.android.kt  actual = 192.dp

composeApp/src/iosMain/kotlin/com/gallr/app/splash/
└── SplashLogoSize.ios.kt      actual = 72.dp

composeApp/src/commonTest/kotlin/com/gallr/app/splash/
└── SplashControllerTest.kt    7 unit tests with runTest virtual time

composeApp/src/androidMain/res/values/
├── colors.xml                 (modify — add splash bg + tint colors)
└── themes.xml                 (new — define Theme.Gallr.Splash)

composeApp/src/androidMain/res/values-night/
└── themes.xml                 (new — dark variant of Theme.Gallr.Splash)

composeApp/src/androidMain/res/drawable/
└── splash_logo.xml            (new — vector drawable arch-pin logo)

iosApp/iosApp/
├── LaunchScreen.storyboard    (new)
└── Assets.xcassets/SplashLogo.imageset/  (new — bundle existing logo PNG)
```

**Modified files:**

```
composeApp/src/commonMain/kotlin/com/gallr/app/App.kt
  - Add splashController: SplashController parameter
  - Wire featuredState collection → splashController.markDataReady() on
    first non-Loading
  - Wire themeRepository.observeThemeMode().first() → markThemeReady()
  - Add SplashOverlay at top zIndex inside root Box

composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt
  - installSplashScreen() before super.onCreate()
  - Create SplashController, wire setKeepOnScreenCondition, controller.start()
  - if savedInstanceState != null: controller.skipSplash()
  - Pass into App()

composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt
  - Create SplashController in factory
  - controller.start()
  - Pass into App()

composeApp/src/androidMain/AndroidManifest.xml
  - Set application theme (or activity theme) to Theme.Gallr.Splash

composeApp/build.gradle.kts
  - Add androidx.core:core-splashscreen dependency to androidMain

gradle/libs.versions.toml
  - Add core-splashscreen version + library entries

iosApp/iosApp/Info.plist
  - Set UILaunchStoryboardName = LaunchScreen
```

---

## Task 1: Add core-splashscreen dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `composeApp/build.gradle.kts`

- [ ] **Step 1: Add version + library to version catalog**

Edit `gradle/libs.versions.toml`. Under `[versions]` add:

```toml
core-splashscreen = "1.0.1"
```

Under `[libraries]` (keep alphabetical with existing entries; add a new section if needed):

```toml
# Splash Screen
androidx-core-splashscreen = { module = "androidx.core:core-splashscreen", version.ref = "core-splashscreen" }
```

- [ ] **Step 2: Reference the library from composeApp's androidMain**

Open `composeApp/build.gradle.kts`. Find the `androidMain.dependencies` block. Add:

```kotlin
implementation(libs.androidx.core.splashscreen)
```

- [ ] **Step 3: Verify Gradle sync**

Run: `./gradlew :composeApp:dependencies --configuration androidDebugCompileClasspath | grep -i splashscreen`
Expected: line containing `androidx.core:core-splashscreen:1.0.1`

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml composeApp/build.gradle.kts
git commit -m "build: add androidx.core:core-splashscreen for splash API"
```

---

## Task 2: Define SplashLogoSize (commonMain expect + per-platform actuals)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gallr/app/splash/SplashLogoSize.kt`
- Create: `composeApp/src/androidMain/kotlin/com/gallr/app/splash/SplashLogoSize.android.kt`
- Create: `composeApp/src/iosMain/kotlin/com/gallr/app/splash/SplashLogoSize.ios.kt`

- [ ] **Step 1: Write the commonMain expect declaration**

Create `composeApp/src/commonMain/kotlin/com/gallr/app/splash/SplashLogoSize.kt`:

```kotlin
package com.gallr.app.splash

import androidx.compose.ui.unit.Dp

/**
 * Size of the gallr arch-pin logo on the Compose splash overlay.
 *
 * Per-platform — sized to match what the native splash actually renders so
 * the native → Compose hand-off is pixel-perfect (no logo jump). Android
 * SplashScreen API enforces ~192dp; iOS storyboard authors at 72dp.
 */
expect val splashLogoDp: Dp
```

- [ ] **Step 2: Write the Android actual**

Create `composeApp/src/androidMain/kotlin/com/gallr/app/splash/SplashLogoSize.android.kt`:

```kotlin
package com.gallr.app.splash

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

actual val splashLogoDp: Dp = 192.dp
```

- [ ] **Step 3: Write the iOS actual**

Create `composeApp/src/iosMain/kotlin/com/gallr/app/splash/SplashLogoSize.ios.kt`:

```kotlin
package com.gallr.app.splash

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

actual val splashLogoDp: Dp = 72.dp
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :composeApp:compileKotlinIosSimulatorArm64 :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gallr/app/splash/SplashLogoSize.kt \
        composeApp/src/androidMain/kotlin/com/gallr/app/splash/SplashLogoSize.android.kt \
        composeApp/src/iosMain/kotlin/com/gallr/app/splash/SplashLogoSize.ios.kt
git commit -m "feat(splash): add platform-matched SplashLogoSize expect/actual"
```

---

## Task 3: SplashController — happy path test (theme + data + min-time)

**Files:**
- Create: `composeApp/src/commonTest/kotlin/com/gallr/app/splash/SplashControllerTest.kt`
- Create: `composeApp/src/commonMain/kotlin/com/gallr/app/splash/SplashController.kt`

This task **enables commonTest for composeApp** if not already present, then writes the first test in TDD style.

- [ ] **Step 1: Verify composeApp commonTest dependencies are present**

Run: `grep -A 20 "commonTest" composeApp/build.gradle.kts`
Expected: a `commonTest.dependencies { ... }` block with `kotlin.test` + `kotlinx.coroutines.test`. If MISSING, add it now (mirroring `shared/build.gradle.kts`):

```kotlin
commonTest.dependencies {
    implementation(libs.kotlin.test)
    implementation(libs.kotlinx.coroutines.test)
}
```

If you added it, run `./gradlew :composeApp:tasks` to confirm the test tasks appear.

- [ ] **Step 2: Write the failing test**

Create `composeApp/src/commonTest/kotlin/com/gallr/app/splash/SplashControllerTest.kt`:

```kotlin
package com.gallr.app.splash

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SplashControllerTest {

    @Test
    fun `happy path - theme then data ready, min time gates dismissal`() = runTest {
        val controller = SplashController(
            minVisibleMs = 1500,
            hardCapMs = 3000,
            scope = TestScope(coroutineContext),
        )
        controller.start()

        assertTrue(controller.isVisible.value, "visible at t=0")

        advanceTimeBy(200)
        controller.markThemeReady()
        controller.markDataReady()
        assertTrue(controller.isVisible.value, "still visible at t=200ms (min not elapsed)")

        advanceTimeBy(1300)  // total = 1500
        assertFalse(controller.isVisible.value, "dismisses at min-time gate")
    }
}
```

- [ ] **Step 3: Run the test, expect it to fail (compile error: SplashController not defined)**

Run: `./gradlew :composeApp:allTests`
Expected: FAIL with "Unresolved reference: SplashController"

- [ ] **Step 4: Write SplashController to make the test pass**

Create `composeApp/src/commonMain/kotlin/com/gallr/app/splash/SplashController.kt`:

```kotlin
package com.gallr.app.splash

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SplashController(
    private val minVisibleMs: Long = 1500,
    private val hardCapMs: Long = 3000,
    private val scope: CoroutineScope,
) {
    private val themeReady = MutableStateFlow(false)
    private val dataReady = MutableStateFlow(false)
    private val minTimeElapsed = MutableStateFlow(false)
    private val hardCapElapsed = MutableStateFlow(false)
    private val skipped = MutableStateFlow(false)

    val isVisible: StateFlow<Boolean> = combine(
        themeReady, dataReady, minTimeElapsed, hardCapElapsed, skipped,
    ) { values ->
        val theme = values[0]
        val data = values[1]
        val min = values[2]
        val cap = values[3]
        val skip = values[4]
        if (skip) return@combine false
        !((theme && data && min) || cap)
    }.stateIn(scope, SharingStarted.Eagerly, true)

    fun start() {
        scope.launch { delay(minVisibleMs); minTimeElapsed.value = true }
        scope.launch { delay(hardCapMs); hardCapElapsed.value = true }
    }

    fun markThemeReady() { themeReady.value = true }
    fun markDataReady() { dataReady.value = true }
    fun skipSplash() { skipped.value = true }
}
```

- [ ] **Step 5: Run the test, expect PASS**

Run: `./gradlew :composeApp:allTests --tests "com.gallr.app.splash.SplashControllerTest.happy path - theme then data ready, min time gates dismissal"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonTest/kotlin/com/gallr/app/splash/SplashControllerTest.kt \
        composeApp/src/commonMain/kotlin/com/gallr/app/splash/SplashController.kt \
        composeApp/build.gradle.kts
git commit -m "feat(splash): add SplashController with three-gate state machine"
```

---

## Task 4: SplashController — slow data, hard cap, fast error, idempotency

**Files:**
- Modify: `composeApp/src/commonTest/kotlin/com/gallr/app/splash/SplashControllerTest.kt`

- [ ] **Step 1: Write the additional failing tests**

Append to `SplashControllerTest.kt` (inside the class, after the existing `@Test`):

```kotlin
@Test
fun `slow data - data gate is the bottleneck`() = runTest {
    val controller = SplashController(
        minVisibleMs = 1500,
        hardCapMs = 3000,
        scope = TestScope(coroutineContext),
    )
    controller.start()
    controller.markThemeReady()

    advanceTimeBy(1500)
    assertTrue(controller.isVisible.value, "still visible — data not ready")

    advanceTimeBy(500)  // total 2000
    controller.markDataReady()
    assertFalse(controller.isVisible.value, "dismisses immediately when data arrives after min")
}

@Test
fun `hard cap dismisses at 3s regardless of state`() = runTest {
    val controller = SplashController(
        minVisibleMs = 1500,
        hardCapMs = 3000,
        scope = TestScope(coroutineContext),
    )
    controller.start()
    // Theme never marked ready
    advanceTimeBy(2999)
    assertTrue(controller.isVisible.value, "still visible at 2999ms")
    advanceTimeBy(1)  // total 3000
    assertFalse(controller.isVisible.value, "hard cap dismisses at exactly 3000ms")
}

@Test
fun `fast error - markDataReady on Error path also dismisses`() = runTest {
    // Simulates: featuredState becomes Error at t=200ms
    val controller = SplashController(
        minVisibleMs = 1500,
        hardCapMs = 3000,
        scope = TestScope(coroutineContext),
    )
    controller.start()
    controller.markThemeReady()
    advanceTimeBy(200)
    controller.markDataReady()  // caller treats Error == data-ready
    assertTrue(controller.isVisible.value, "min not elapsed yet")
    advanceTimeBy(1300)
    assertFalse(controller.isVisible.value, "dismisses at 1500ms via min-time gate")
}

@Test
fun `idempotent - duplicate markDataReady calls do not crash`() = runTest {
    val controller = SplashController(
        minVisibleMs = 1500,
        hardCapMs = 3000,
        scope = TestScope(coroutineContext),
    )
    controller.start()
    controller.markThemeReady()
    controller.markDataReady()
    controller.markDataReady()
    controller.markDataReady()
    advanceTimeBy(1500)
    assertFalse(controller.isVisible.value)
}

@Test
fun `skipSplash flips visibility to false immediately`() = runTest {
    val controller = SplashController(
        minVisibleMs = 1500,
        hardCapMs = 3000,
        scope = TestScope(coroutineContext),
    )
    controller.start()
    assertTrue(controller.isVisible.value)
    controller.skipSplash()
    assertFalse(controller.isVisible.value, "skipSplash is immediate")
}
```

- [ ] **Step 2: Run all SplashController tests, expect all to pass**

Run: `./gradlew :composeApp:allTests --tests "com.gallr.app.splash.SplashControllerTest*"`
Expected: 6 tests PASS

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonTest/kotlin/com/gallr/app/splash/SplashControllerTest.kt
git commit -m "test(splash): cover slow-data, hard-cap, fast-error, idempotency, skip"
```

---

## Task 5: SplashOverlay composable

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gallr/app/splash/SplashOverlay.kt`

- [ ] **Step 1: Write SplashOverlay**

Create `composeApp/src/commonMain/kotlin/com/gallr/app/splash/SplashOverlay.kt`:

```kotlin
package com.gallr.app.splash

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.zIndex
import gallr.composeapp.generated.resources.Res
import gallr.composeapp.generated.resources.logo
import org.jetbrains.compose.resources.painterResource

/**
 * Full-screen splash overlay. Renders the arch-pin logo on a theme-aware
 * background. Fades out (200ms) when controller.isVisible becomes false.
 * Sits at zIndex Float.MAX_VALUE so it covers the entire app while visible.
 */
@Composable
fun SplashOverlay(
    controller: SplashController,
    modifier: Modifier = Modifier,
) {
    val visible by controller.isVisible.collectAsState()

    AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.fadeIn(animationSpec = tween(0)),
        exit = fadeOut(animationSpec = tween(200)),
        modifier = modifier.zIndex(Float.MAX_VALUE),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(Res.drawable.logo),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                modifier = Modifier.size(splashLogoDp),
            )
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :composeApp:compileKotlinIosSimulatorArm64 :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gallr/app/splash/SplashOverlay.kt
git commit -m "feat(splash): add SplashOverlay composable with fade-out exit"
```

---

## Task 6: Wire splash into App.kt

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`

- [ ] **Step 1: Add SplashController parameter and wire gates**

Open `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`. Find the `App(` function signature near the top. Add the parameter and necessary imports.

At the top of the file with the other imports, add:

```kotlin
import com.gallr.app.splash.SplashController
import com.gallr.app.splash.SplashOverlay
import kotlinx.coroutines.flow.first
```

Then in the function signature, add `splashController: SplashController,` (place it adjacent to the other repository params — order doesn't matter functionally):

```kotlin
fun App(
    exhibitionRepository: ExhibitionRepository,
    eventRepository: EventRepository,
    localBookmarkRepository: BookmarkRepositoryImpl,
    cloudBookmarkRepository: CloudBookmarkRepository,
    authRepository: AuthRepository,
    profileRepository: ProfileRepository,
    thoughtRepository: ThoughtRepository,
    languageRepository: LanguageRepository,
    themeRepository: ThemeRepository,
    supabaseClient: SupabaseClient,
    splashController: SplashController,
) {
```

- [ ] **Step 2: Wire markThemeReady on first DataStore emit**

Inside `App(...)`, near the existing `LaunchedEffect(authState) { ... }` block, add a new effect to mark theme ready on the first emission of the saved theme. Place this AFTER the `viewModel` and `currentThemeMode` declarations:

```kotlin
androidx.compose.runtime.LaunchedEffect(Unit) {
    // First emit from DataStore — by definition the saved value (or default)
    themeRepository.observeThemeMode().first()
    splashController.markThemeReady()
}
```

- [ ] **Step 3: Wire markDataReady on first non-Loading featuredState**

Add another `LaunchedEffect` block after the previous one:

```kotlin
androidx.compose.runtime.LaunchedEffect(Unit) {
    viewModel.featuredState
        .first { it !is com.gallr.app.viewmodel.ExhibitionListState.Loading }
    splashController.markDataReady()
}
```

- [ ] **Step 4: Mount SplashOverlay at top of root Box**

Find the existing root `Box(modifier = Modifier.fillMaxSize())` (around line 150 — wraps `AnimatedContent` and `CompositionLocalProvider`). Inside that `Box`, AS THE LAST CHILD (so it's drawn on top), add:

```kotlin
SplashOverlay(controller = splashController)
```

The structure should look like:

```kotlin
Box(modifier = Modifier.fillMaxSize()) {
    // ... existing AnimatedContent and content ...

    SplashOverlay(controller = splashController)
}
```

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew :composeApp:compileKotlinIosSimulatorArm64 :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gallr/app/App.kt
git commit -m "feat(splash): wire SplashController/Overlay into App composable"
```

---

## Task 7: Add Android splash theme + colors + drawable resources

**Files:**
- Modify: `composeApp/src/androidMain/res/values/colors.xml`
- Create: `composeApp/src/androidMain/res/values/themes.xml`
- Create: `composeApp/src/androidMain/res/values-night/themes.xml`
- Create: `composeApp/src/androidMain/res/drawable/splash_logo.xml`

- [ ] **Step 1: Read existing colors.xml**

Run: `cat composeApp/src/androidMain/res/values/colors.xml`
Expected: existing color definitions (review them so you don't duplicate names).

- [ ] **Step 2: Append splash colors to colors.xml**

Edit `composeApp/src/androidMain/res/values/colors.xml`. Inside the existing `<resources>` element, add (right before `</resources>`):

```xml
    <!-- Splash screen — light theme -->
    <color name="splash_background_light">#FFFFFFFF</color>
    <color name="splash_logo_tint_light">#FF000000</color>

    <!-- Splash screen — dark theme -->
    <color name="splash_background_dark">#FF121212</color>
    <color name="splash_logo_tint_dark">#FFE0E0E0</color>
```

- [ ] **Step 3: Create the splash logo vector drawable**

Create `composeApp/src/androidMain/res/drawable/splash_logo.xml`. We want the existing arch-pin logo as a vector. Re-use the same path data as the existing `logo` resource — find it first:

Run: `find composeApp -name "logo.xml" -not -path "*/build/*"`

If the existing logo is a vector (`logo.xml`), copy its `<path>` data into `splash_logo.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="?attr/splashLogoTint"
        android:pathData="<COPY_FROM_logo.xml>"/>
</vector>
```

If the existing `logo` is a PNG/raster, instead create a vector from the design system reference. Use the path data from the existing logo asset; if unavailable, copy the existing `logo` resource to `splash_logo.xml` verbatim (keeping its fillColor attribute), then update `fillColor` to `?attr/splashLogoTint` so it picks up the theme's tint color.

If `?attr/splashLogoTint` is not how the existing system tints icons, fall back to the simpler approach: **two separate drawables** (`splash_logo_light.xml` for light, `splash_logo_dark.xml` for dark) and reference each from the corresponding theme.

For this plan, assume the simpler two-drawable approach. Create `splash_logo_light.xml` and `splash_logo_dark.xml` instead, each a copy of the existing logo's vector data with `android:fillColor` set to `@color/splash_logo_tint_light` and `@color/splash_logo_tint_dark` respectively.

- [ ] **Step 4: Create values/themes.xml (light variant)**

Create `composeApp/src/androidMain/res/values/themes.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.Gallr.Splash" parent="Theme.SplashScreen">
        <item name="windowSplashScreenBackground">@color/splash_background_light</item>
        <item name="windowSplashScreenAnimatedIcon">@drawable/splash_logo_light</item>
        <item name="postSplashScreenTheme">@style/Theme.Gallr</item>
    </style>

    <!-- Post-splash theme used by Compose. Inherits Material 3 base. -->
    <style name="Theme.Gallr" parent="android:Theme.Material.Light.NoActionBar">
        <item name="android:windowLightStatusBar">true</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
    </style>
</resources>
```

- [ ] **Step 5: Create values-night/themes.xml (dark variant)**

Create `composeApp/src/androidMain/res/values-night/themes.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.Gallr.Splash" parent="Theme.SplashScreen">
        <item name="windowSplashScreenBackground">@color/splash_background_dark</item>
        <item name="windowSplashScreenAnimatedIcon">@drawable/splash_logo_dark</item>
        <item name="postSplashScreenTheme">@style/Theme.Gallr</item>
    </style>

    <style name="Theme.Gallr" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowLightStatusBar">false</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
    </style>
</resources>
```

- [ ] **Step 6: Build to verify resources compile**

Run: `./gradlew :composeApp:compileDebugResources`
Expected: BUILD SUCCESSFUL. If a `Theme.SplashScreen` parent isn't resolved, confirm `androidx.core.splashscreen` is on the classpath (Task 1).

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/androidMain/res/
git commit -m "feat(splash): add Android splash theme + colors + logo drawables"
```

---

## Task 8: Wire AndroidManifest to use the splash theme

**Files:**
- Modify: `composeApp/src/androidMain/AndroidManifest.xml`

- [ ] **Step 1: Read current manifest**

Run: `cat composeApp/src/androidMain/AndroidManifest.xml`
Expected output: locate the `<application>` element and any `<activity>` declarations. Note any existing `android:theme` attributes.

- [ ] **Step 2: Set theme to Theme.Gallr.Splash**

Find the `<application ... android:theme="...">` attribute. Replace its theme value with `@style/Theme.Gallr.Splash`. If no `android:theme` attribute exists, add one:

```xml
<application
    ...
    android:theme="@style/Theme.Gallr.Splash">
```

If MainActivity itself has an `android:theme` attribute, remove that override OR set it to the same `@style/Theme.Gallr.Splash` so the splash theme applies.

- [ ] **Step 3: Build to verify**

Run: `./gradlew :composeApp:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/androidMain/AndroidManifest.xml
git commit -m "feat(splash): apply Theme.Gallr.Splash in AndroidManifest"
```

---

## Task 9: Wire SplashController into MainActivity

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt`

- [ ] **Step 1: Add imports + installSplashScreen + controller construction**

Open `composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt`. Add the imports near the top:

```kotlin
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.gallr.app.splash.SplashController
```

In `onCreate(savedInstanceState: Bundle?)`, BEFORE `super.onCreate(savedInstanceState)`, add:

```kotlin
val splash = installSplashScreen()
val splashController = SplashController(scope = lifecycleScope)
splash.setKeepOnScreenCondition { !splashController.themeReadyValue() }
splashController.start()
if (savedInstanceState != null) splashController.skipSplash()
```

- [ ] **Step 2: Expose `themeReadyValue()` on SplashController**

`themeReady` is private. We need a non-suspending readable accessor. Open `composeApp/src/commonMain/kotlin/com/gallr/app/splash/SplashController.kt` and add:

```kotlin
fun themeReadyValue(): Boolean = themeReady.value
```

- [ ] **Step 3: Pass splashController into App() call**

In `MainActivity.onCreate`, find the `App(...)` call. Add the parameter:

```kotlin
App(
    exhibitionRepository = exhibitionRepository,
    eventRepository = eventRepository,
    localBookmarkRepository = localBookmarkRepository,
    cloudBookmarkRepository = cloudBookmarkRepository,
    authRepository = authRepository,
    profileRepository = profileRepository,
    thoughtRepository = thoughtRepository,
    languageRepository = languageRepository,
    themeRepository = themeRepository,
    supabaseClient = supabaseClient,
    splashController = splashController,
)
```

- [ ] **Step 4: Build & install**

Run: `./gradlew :composeApp:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt \
        composeApp/src/commonMain/kotlin/com/gallr/app/splash/SplashController.kt
git commit -m "feat(splash): wire SplashController into Android MainActivity"
```

---

## Task 10: Wire SplashController into iOS MainViewController + LaunchScreen storyboard

**Files:**
- Modify: `composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt`
- Create: `iosApp/iosApp/LaunchScreen.storyboard`
- Create: `iosApp/iosApp/Assets.xcassets/SplashLogo.imageset/Contents.json` (+ logo PNGs)
- Modify: `iosApp/iosApp/Info.plist`

- [ ] **Step 1: Wire controller in MainViewController.kt**

Open `composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt`. Add imports:

```kotlin
import com.gallr.app.splash.SplashController
import kotlinx.coroutines.MainScope
```

Inside the `MainViewController(...)` factory function, before the `App(...)` call, add:

```kotlin
val splashController = SplashController(scope = MainScope())
splashController.start()
// iOS native splash dismisses on first frame; mark theme ready immediately
// for compatibility — the App-side LaunchedEffect will re-mark idempotently.
```

(Note: iOS storyboard auto-dismisses on first Compose frame regardless. The native → Compose hand-off cannot be held on iOS — accepted limitation per spec §4.)

Pass into `App(...)`:

```kotlin
App(
    // ... existing parameters ...
    splashController = splashController,
)
```

- [ ] **Step 2: Add logo image asset to iOS asset catalog**

Locate the existing logo PNG (or vector) in the iOS app or compose-resources. The simplest path: copy the existing `composeApp/src/commonMain/composeResources/drawable/logo.*` (or wherever the logo lives) into `iosApp/iosApp/Assets.xcassets/SplashLogo.imageset/`.

Create `iosApp/iosApp/Assets.xcassets/SplashLogo.imageset/Contents.json`:

```json
{
  "images": [
    { "idiom": "universal", "filename": "splash_logo.png", "scale": "1x" },
    { "idiom": "universal", "filename": "splash_logo@2x.png", "scale": "2x" },
    { "idiom": "universal", "filename": "splash_logo@3x.png", "scale": "3x" }
  ],
  "info": { "author": "xcode", "version": 1 }
}
```

If you only have one resolution available, populate just `1x`; iOS will scale.

- [ ] **Step 3: Create LaunchScreen.storyboard**

Create `iosApp/iosApp/LaunchScreen.storyboard`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<document type="com.apple.InterfaceBuilder3.CocoaTouch.Storyboard.XIB" version="3.0" toolsVersion="22155" targetRuntime="iOS.CocoaTouch" propertyAccessControl="none" useAutolayout="YES" launchScreen="YES" useTraitCollections="YES" useSafeAreas="YES" colorMatched="YES" initialViewController="01J-lp-oVM">
    <device id="retina6_12" orientation="portrait" appearance="light"/>
    <dependencies>
        <plugIn identifier="com.apple.InterfaceBuilder.IBCocoaTouchPlugin" version="22131"/>
        <capability name="Safe area layout guides" minToolsVersion="9.0"/>
        <capability name="documents saved in the Xcode 8 format" minToolsVersion="8.0"/>
    </dependencies>
    <scenes>
        <scene sceneID="EHf-IW-A2E">
            <objects>
                <viewController id="01J-lp-oVM" sceneMemberID="viewController">
                    <view key="view" contentMode="scaleToFill" id="Ze5-6b-2t3">
                        <rect key="frame" x="0.0" y="0.0" width="393" height="852"/>
                        <autoresizingMask key="autoresizingMask" widthSizable="YES" heightSizable="YES"/>
                        <subviews>
                            <imageView clipsSubviews="YES" userInteractionEnabled="NO" contentMode="scaleAspectFit" image="SplashLogo" translatesAutoresizingMaskIntoConstraints="NO" id="logo">
                                <rect key="frame" x="160.5" y="390" width="72" height="72"/>
                            </imageView>
                        </subviews>
                        <viewLayoutGuide key="safeArea" id="6Tk-OE-BBY"/>
                        <color key="backgroundColor" red="1" green="1" blue="1" alpha="1" colorSpace="custom" customColorSpace="sRGB"/>
                        <constraints>
                            <constraint firstItem="logo" firstAttribute="centerX" secondItem="Ze5-6b-2t3" secondAttribute="centerX" id="cx"/>
                            <constraint firstItem="logo" firstAttribute="centerY" secondItem="Ze5-6b-2t3" secondAttribute="centerY" id="cy"/>
                            <constraint firstAttribute="width" secondItem="logo" relation="equal" constant="72" id="w"/>
                            <constraint firstAttribute="height" secondItem="logo" relation="equal" constant="72" id="h"/>
                        </constraints>
                    </view>
                </viewController>
                <placeholder placeholderIdentifier="IBFirstResponder" id="iYj-Kq-Ea1" sceneMemberID="firstResponder"/>
            </objects>
            <point key="canvasLocation" x="0" y="0"/>
        </scene>
    </scenes>
    <resources>
        <image name="SplashLogo" width="72" height="72"/>
    </resources>
</document>
```

- [ ] **Step 4: Wire LaunchScreen in Info.plist**

Open `iosApp/iosApp/Info.plist`. Add (or update) the `UILaunchStoryboardName` key:

```xml
<key>UILaunchStoryboardName</key>
<string>LaunchScreen</string>
```

Remove any existing `UILaunchScreen` dictionary entries that conflict.

- [ ] **Step 5: Add LaunchScreen.storyboard to Xcode project**

Open `iosApp/iosApp.xcodeproj` in Xcode. Drag `LaunchScreen.storyboard` into the iosApp target group. Ensure "Target Membership" is iosApp.

- [ ] **Step 6: Build for iOS simulator**

Run: `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64`
Expected: BUILD SUCCESSFUL

Then in Xcode: Product → Build (⌘B). Expect zero storyboard warnings.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt \
        iosApp/iosApp/LaunchScreen.storyboard \
        iosApp/iosApp/Assets.xcassets/SplashLogo.imageset/ \
        iosApp/iosApp/Info.plist
git commit -m "feat(splash): wire SplashController + LaunchScreen.storyboard for iOS"
```

---

## Task 11: Manual verification on Android (light + dark, fast + slow)

This is non-code QA. The plan is here so the implementer doesn't skip it.

- [ ] **Step 1: Light mode cold launch**

```
adb shell settings put secure ui_night_mode 1   # force light
adb uninstall com.gallr.app
./gradlew :composeApp:installDebug
adb shell am start -W com.gallr.app/.MainActivity
```

Expected:
- Native splash visible immediately on app icon tap (white bg, black logo)
- No system white flash before native splash
- Compose overlay takes over with no logo position/size jump
- Featured tab populated when overlay fades out (200ms fade)
- Total cold launch ≈ 1.5s on a fast device with cached data

- [ ] **Step 2: Dark mode cold launch**

```
adb shell settings put secure ui_night_mode 2   # force dark
adb shell am force-stop com.gallr.app
adb shell am start -W com.gallr.app/.MainActivity
```

Expected:
- Native splash visible (`#121212` bg, `#E0E0E0` logo)
- Compose overlay matches; no theme flip flash
- Smooth fade out

- [ ] **Step 3: Slow connection**

Use `adb shell svc data disable` then re-enable, or use Charles/Network Link Conditioner @ "Edge".

```
adb shell svc data disable
adb shell svc wifi disable
adb shell am force-stop com.gallr.app
adb shell am start -W com.gallr.app/.MainActivity
```

Expected:
- Splash holds past 1.5s
- Splash hard-caps at 3s
- Main UI shows error/loading state (data not ready)

- [ ] **Step 4: Background → foreground**

```
adb shell am start -W com.gallr.app/.MainActivity
# wait for splash to dismiss
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
# wait 5s
adb shell am start -W com.gallr.app/.MainActivity
```

Expected: NO splash on second start (warm restore).

- [ ] **Step 5: Force-quit + re-open**

```
adb shell am force-stop com.gallr.app
adb shell am start -W com.gallr.app/.MainActivity
```

Expected: Splash appears again (cold launch).

- [ ] **Step 6: Rotation during splash**

Hard to time manually; instead test rotation immediately AFTER splash dismisses. Rotate device → splash does NOT reappear.

- [ ] **Step 7: Document any logo size/position discrepancy**

If the native splash's logo position ≠ Compose overlay's logo position by more than ~1dp, file a follow-up to revisit `splashLogoDp.android = 192.dp` constant. Capture side-by-side screenshots in the PR description.

- [ ] **Step 8: No commit needed (QA only)**

If issues discovered, commit fixes inline with descriptive messages.

---

## Task 12: Manual verification on iOS (light + dark, fast + slow)

- [ ] **Step 1: Light mode cold launch (simulator)**

```
xcrun simctl boot "iPhone 16 Pro"
xcrun simctl ui booted appearance light
# Build & run from Xcode: ⌘R
```

Expected:
- Storyboard renders white bg + 72dp logo immediately
- Compose overlay applies correct light theme
- Smooth transition; no flash
- Featured tab populated when overlay dismisses

- [ ] **Step 2: Dark mode cold launch (simulator)**

```
xcrun simctl ui booted appearance dark
# Force quit, re-launch
xcrun simctl uninstall booted com.gallr.app   # if needed
# Build & run from Xcode
```

Expected:
- Storyboard renders white briefly (storyboards can't be theme-aware on iOS — documented limitation)
- Compose overlay applies dark theme on first frame (`#121212` bg, `#E0E0E0` logo)
- ONE-TIME first-launch flash from white storyboard → dark overlay; subsequent launches read warm DataStore in <10ms and the flash is imperceptible

- [ ] **Step 3: Physical device cold launch**

Repeat steps 1+2 on a physical iPhone. Verify same behavior.

- [ ] **Step 4: Slow connection (Network Link Conditioner)**

Settings → Developer → Network Link Conditioner → Enable → Edge profile.

Force-quit app, re-launch.

Expected:
- Splash holds past 1.5s
- Hard-caps at 3s
- Main UI shows loading/error state when overlay dismisses

- [ ] **Step 5: Background → foreground**

App active → swipe up to home (don't kill) → wait 5s → tap app icon.

Expected: NO splash (warm restore — Compose state preserved).

- [ ] **Step 6: Force-quit + re-open**

Swipe up app from app switcher (force-quit) → tap app icon.

Expected: Splash appears again (cold launch).

- [ ] **Step 7: No commit needed (QA only)**

---

## Self-review checklist

- [ ] Spec §3 (Decisions): all 4 covered — Q1 fast-error in Task 4 test, Q2 pixel-perfect in Task 2 (`splashLogoDp`), Q3 theme-loaded gate in Task 9 (`setKeepOnScreenCondition`), Logo entrance skipped (no fadeIn).
- [ ] Spec §4 architecture diagram realized in Tasks 3 + 5 + 6 + 9 + 10.
- [ ] Spec §5 file list matches plan File Structure section above.
- [ ] Spec §7 edge cases covered: data fail (Task 4 fast-error test), slow data (Task 4 + 11.3), hard cap (Task 4 + 11.3), background restore (Task 11.4 + 12.5), force-quit (Task 11.5 + 12.6), config change (Task 9 step 1 `savedInstanceState` check), iOS first-launch flash (acknowledged in Task 12.2).
- [ ] Spec §8 testing: `SplashController` unit tests cover all 6+ paths; manual Android + iOS verification scripted.
- [ ] No "TBD"/"TODO"/"add appropriate" placeholders in any task body.
- [ ] All types referenced consistently: `SplashController`, `SplashOverlay`, `splashLogoDp`, `themeReadyValue()`, `markThemeReady()`, `markDataReady()`, `skipSplash()`, `start()`, `isVisible`.
