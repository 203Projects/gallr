# Map: Center on User's Location — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the user opens the Map tab with location permission already granted, center the camera on their cached location if it's inside Korea; otherwise fall back to Seoul (current behavior).

**Architecture:** Add a `commonMain` `expect fun rememberLastKnownCoordinates(enabled: Boolean): Coordinates?` with platform actuals reading the cached fix from `FusedLocationProviderClient.lastLocation` (Android) and `CLLocationManager.location` (iOS). Add a pure `isInsideKorea(lat, lng)` helper in the `shared` module's util package. `MapScreen` decides the initial camera target and passes it to `MapView` through a new `initialCenter: Coordinates?` parameter. To avoid a "Seoul → user-location" jump, `MapScreen` defers composing `MapView` for up to 300ms while the cached fix resolves.

**Tech Stack:** Kotlin Multiplatform 2.1.20, Compose Multiplatform 1.8.0, Naver Map SDK 3.23.0 + naver-map-compose 1.8.1, FusedLocationProviderClient (`play-services-location`), CoreLocation (iOS).

**Spec:** `specs/034-map-center-on-user-location/spec.md`

---

## File Structure

**Created:**
- `shared/src/commonMain/kotlin/com/gallr/shared/util/KoreaBounds.kt` — pure `isInsideKorea(lat, lng)` helper
- `shared/src/commonTest/kotlin/com/gallr/shared/util/KoreaBoundsTest.kt` — unit tests
- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/UserLocation.kt` — `Coordinates` data class, `expect rememberLastKnownCoordinates`, `rememberMapReadiness` helper
- `composeApp/src/androidMain/kotlin/com/gallr/app/ui/tabs/map/UserLocation.android.kt` — Android actual using `FusedLocationProviderClient`
- `composeApp/src/iosMain/kotlin/com/gallr/app/ui/tabs/map/UserLocation.ios.kt` — iOS actual using `CLLocationManager`

**Modified:**
- `gradle/libs.versions.toml` — add `play-services-location` and `kotlinx-coroutines-play-services` library aliases
- `composeApp/build.gradle.kts` — add the two new Android dependencies
- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapView.kt` — add `initialCenter: Coordinates?` parameter to `expect fun MapView`
- `composeApp/src/androidMain/kotlin/com/gallr/app/ui/tabs/map/MapView.android.kt` — use `initialCenter` in `rememberCameraPositionState`
- `composeApp/src/iosMain/kotlin/com/gallr/app/ui/tabs/map/MapView.ios.kt` — use `initialCenter` in the `factory` block
- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapScreen.kt` — wire `rememberLastKnownCoordinates`, `isInsideKorea`, `rememberMapReadiness`, and 300ms-deferred render

---

## Task 1: Add Korea bounding-box helper (TDD, in `shared` module)

**Files:**
- Create: `shared/src/commonTest/kotlin/com/gallr/shared/util/KoreaBoundsTest.kt`
- Create: `shared/src/commonMain/kotlin/com/gallr/shared/util/KoreaBounds.kt`

- [ ] **Step 1: Write the failing tests**

Create `shared/src/commonTest/kotlin/com/gallr/shared/util/KoreaBoundsTest.kt`:

```kotlin
package com.gallr.shared.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KoreaBoundsTest {
    @Test fun seoulIsInsideKorea() {
        assertTrue(isInsideKorea(37.5665, 126.9780))
    }

    @Test fun busanIsInsideKorea() {
        assertTrue(isInsideKorea(35.1796, 129.0756))
    }

    @Test fun jejuIsInsideKorea() {
        assertTrue(isInsideKorea(33.4996, 126.5312))
    }

    @Test fun tokyoIsOutsideKorea() {
        assertFalse(isInsideKorea(35.6762, 139.6503))
    }

    @Test fun newYorkIsOutsideKorea() {
        assertFalse(isInsideKorea(40.7128, -74.0060))
    }

    @Test fun southOfBoundIsOutside() {
        assertFalse(isInsideKorea(32.9, 127.0))
    }

    @Test fun northOfBoundIsOutside() {
        assertFalse(isInsideKorea(39.0, 127.0))
    }

    @Test fun westOfBoundIsOutside() {
        assertFalse(isInsideKorea(35.0, 124.5))
    }

    @Test fun eastOfBoundIsOutside() {
        assertFalse(isInsideKorea(35.0, 132.0))
    }

    @Test fun southWestEdgeIsInclusive() {
        assertTrue(isInsideKorea(33.0, 124.6))
    }

    @Test fun northEastEdgeIsInclusive() {
        assertTrue(isInsideKorea(38.9, 131.9))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :shared:jvmTest --tests "com.gallr.shared.util.KoreaBoundsTest"`

(If `jvmTest` task is not present in this project, run `./gradlew :shared:allTests` instead — KoreaBoundsTest is in commonTest so it executes on every target.)

Expected: COMPILATION FAILURE — `Unresolved reference: isInsideKorea`.

- [ ] **Step 3: Write the implementation**

Create `shared/src/commonMain/kotlin/com/gallr/shared/util/KoreaBounds.kt`:

```kotlin
package com.gallr.shared.util

/**
 * Approximate bounding box for South Korea.
 * Used to decide whether a cached device location is "in Korea" enough to
 * be a sensible initial map center, rather than falling back to Seoul.
 *
 * Bounds are inclusive on all four sides.
 */
fun isInsideKorea(lat: Double, lng: Double): Boolean =
    lat in 33.0..38.9 && lng in 124.6..131.9
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shared:allTests`

Expected: PASS — all 11 KoreaBoundsTest assertions pass.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gallr/shared/util/KoreaBounds.kt \
        shared/src/commonTest/kotlin/com/gallr/shared/util/KoreaBoundsTest.kt
git commit -m "feat(util): add isInsideKorea bounding-box helper"
```

---

## Task 2: Add Android Play Services dependencies

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `composeApp/build.gradle.kts`

- [ ] **Step 1: Add version + library aliases to the version catalog**

In `gradle/libs.versions.toml`, under `[versions]`, add:

```toml
play-services-location = "21.3.0"
```

Under `[libraries]`, add (group with the other AndroidX/Google entries, e.g., right after the `kotlinx-coroutines-android` entry):

```toml
play-services-location = { module = "com.google.android.gms:play-services-location", version.ref = "play-services-location" }
kotlinx-coroutines-play-services = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-play-services", version.ref = "kotlinx-coroutines" }
```

(The coroutines bridge reuses the existing `kotlinx-coroutines` version `1.9.0`.)

- [ ] **Step 2: Add the dependencies to composeApp**

In `composeApp/build.gradle.kts`, in the `androidMain.dependencies` block (currently around the line `implementation(libs.naver.map.sdk)`), add two lines:

```kotlin
androidMain.dependencies {
    implementation(compose.preview)
    implementation(libs.activity.compose)
    implementation(libs.datastore.preferences.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)   // NEW
    implementation(libs.play.services.location)             // NEW
    implementation(libs.naver.map.sdk)
    implementation(libs.naver.map.compose)
    implementation(libs.coil.network.okhttp)
}
```

- [ ] **Step 3: Verify the dependencies resolve**

Run: `./gradlew :composeApp:dependencies --configuration releaseRuntimeClasspath | grep -E "play-services-location|coroutines-play-services"`

Expected output (versions may differ on patches):
```
+--- com.google.android.gms:play-services-location:21.3.0
+--- org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0
```

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml composeApp/build.gradle.kts
git commit -m "build(android): add play-services-location for cached fix lookup"
```

---

## Task 3: Add `Coordinates` data class and `expect rememberLastKnownCoordinates` (commonMain)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/UserLocation.kt`

- [ ] **Step 1: Create the file**

Create `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/UserLocation.kt`:

```kotlin
package com.gallr.app.ui.tabs.map

import androidx.compose.runtime.Composable

/**
 * A geographic coordinate pair. Used as the initial-camera target for [MapView].
 */
data class Coordinates(val latitude: Double, val longitude: Double)

/**
 * Returns the device's last-known location as cached by the OS, or null if:
 *   - [enabled] is false (typically because permission has not been granted)
 *   - the OS has no cached fix
 *   - the platform call has not yet resolved (the returned value flips from
 *     null to non-null on a later recomposition)
 *   - the platform call failed
 *
 * No fresh GPS fix is requested. This is intentionally a cached-only read so
 * the Map tab can open without animation or visible delay.
 */
@Composable
expect fun rememberLastKnownCoordinates(enabled: Boolean): Coordinates?
```

- [ ] **Step 2: Verify it compiles in commonMain (the build will fail later because no actuals exist yet — that's expected)**

Run: `./gradlew :composeApp:compileKotlinMetadata` (this compiles only the common source set, no platforms)

Expected: SUCCESS for this command. The full Android/iOS build will fail until Tasks 4 and 5 land — that is fine.

(If `compileKotlinMetadata` is not available, skip this step; the platform builds in Tasks 4–5 will catch any issues.)

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/UserLocation.kt
git commit -m "feat(map): declare expect rememberLastKnownCoordinates and Coordinates"
```

---

## Task 4: Implement `rememberLastKnownCoordinates` for Android

**Files:**
- Create: `composeApp/src/androidMain/kotlin/com/gallr/app/ui/tabs/map/UserLocation.android.kt`

- [ ] **Step 1: Write the actual**

Create `composeApp/src/androidMain/kotlin/com/gallr/app/ui/tabs/map/UserLocation.android.kt`:

```kotlin
package com.gallr.app.ui.tabs.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.tasks.await

@Composable
actual fun rememberLastKnownCoordinates(enabled: Boolean): Coordinates? {
    val context = LocalContext.current
    var coords by remember { mutableStateOf<Coordinates?>(null) }

    LaunchedEffect(enabled) {
        if (!enabled) {
            coords = null
            return@LaunchedEffect
        }
        coords = runCatching {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val location = client.lastLocation.await()
            location?.let { Coordinates(it.latitude, it.longitude) }
        }.getOrNull()
    }
    return coords
}
```

Notes:
- `runCatching { ... }.getOrNull()` swallows `SecurityException` (race with permission revocation), `ApiException` (Play Services missing), and any null returns.
- `LaunchedEffect(enabled)` re-keys on the permission flag — when permission flips from false to true mid-session, the effect re-runs with the latest value.

- [ ] **Step 2: Verify the Android target compiles**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`

Expected: SUCCESS (no `unresolved reference` errors).

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/gallr/app/ui/tabs/map/UserLocation.android.kt
git commit -m "feat(map/android): implement rememberLastKnownCoordinates via FusedLocationProvider"
```

---

## Task 5: Implement `rememberLastKnownCoordinates` for iOS

**Files:**
- Create: `composeApp/src/iosMain/kotlin/com/gallr/app/ui/tabs/map/UserLocation.ios.kt`

- [ ] **Step 1: Write the actual**

Create `composeApp/src/iosMain/kotlin/com/gallr/app/ui/tabs/map/UserLocation.ios.kt`:

```kotlin
package com.gallr.app.ui.tabs.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreLocation.CLLocationManager

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberLastKnownCoordinates(enabled: Boolean): Coordinates? {
    val manager = remember { CLLocationManager() }
    var coords by remember { mutableStateOf<Coordinates?>(null) }

    LaunchedEffect(enabled) {
        if (!enabled) {
            coords = null
            return@LaunchedEffect
        }
        coords = manager.location?.coordinate?.useContents {
            Coordinates(latitude, longitude)
        }
    }
    return coords
}
```

Notes:
- `CLLocationManager.location` is a synchronous property; returns `nil` if no cached fix or if the manager has not yet been authorized.
- The existing `LocationPermission.ios.kt` already requests `requestWhenInUseAuthorization()` from `MapScreen`'s `LaunchedEffect`. By the time this composable runs with `enabled=true`, the OS has the cached value (or doesn't, → null → Seoul fallback).
- `useContents` is required because `coordinate` is a C struct (`CLLocationCoordinate2D`).

- [ ] **Step 2: Verify the iOS target compiles**

Run: `./gradlew :composeApp:compileKotlinIosSimulatorArm64`

Expected: SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/iosMain/kotlin/com/gallr/app/ui/tabs/map/UserLocation.ios.kt
git commit -m "feat(map/ios): implement rememberLastKnownCoordinates via CLLocationManager"
```

---

## Task 6: Add `rememberMapReadiness` helper (commonMain)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/UserLocation.kt`

- [ ] **Step 1: Append the helper to UserLocation.kt**

Add the following at the bottom of `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/UserLocation.kt`:

```kotlin
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Returns `true` once it is safe to compose the map for the first time.
 *
 * Use this to defer composing [MapView] briefly while [rememberLastKnownCoordinates]
 * resolves, so the camera can be initialized at the user's location instead of
 * jumping from Seoul to the user's location after the first frame.
 *
 * Returns `true` immediately when:
 *   - [permissionGranted] is false (no point waiting; we will use the Seoul fallback), OR
 *   - [coordsResolved] is true (cached coords are available now)
 *
 * Otherwise returns `false` until [timeoutMillis] elapse, after which it returns
 * `true` regardless of whether coords arrived. The Seoul fallback handles the
 * timeout case gracefully.
 */
@Composable
fun rememberMapReadiness(
    permissionGranted: Boolean,
    coordsResolved: Boolean,
    timeoutMillis: Long = 300L,
): Boolean {
    if (!permissionGranted || coordsResolved) return true

    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(permissionGranted) {
        timedOut = false
        delay(timeoutMillis)
        timedOut = true
    }
    return timedOut
}
```

(Also: move the `@Composable` import already at the top — no change needed since it already imports `androidx.compose.runtime.Composable`. The new imports above must be merged into the existing import block at the top of the file, not duplicated.)

For clarity, the final import block at the top of `UserLocation.kt` should be:

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`

Expected: SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/UserLocation.kt
git commit -m "feat(map): add rememberMapReadiness for first-frame camera deferral"
```

---

## Task 7: Add `initialCenter` parameter to `expect MapView`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapView.kt`

- [ ] **Step 1: Update the expect signature**

In `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapView.kt`, change the `expect fun MapView` declaration (currently lines 42–48 of the file as of `develop @ 538102b`):

Before:
```kotlin
@Composable
expect fun MapView(
    locations: List<MapLocation>,
    onLocationTap: (MapLocation) -> Unit,
    modifier: Modifier = Modifier,
    enableUserLocation: Boolean = false,
)
```

After:
```kotlin
@Composable
expect fun MapView(
    locations: List<MapLocation>,
    onLocationTap: (MapLocation) -> Unit,
    modifier: Modifier = Modifier,
    enableUserLocation: Boolean = false,
    initialCenter: Coordinates? = null,
)
```

- [ ] **Step 2: Verify common compiles (platforms will fail; that's expected — Tasks 8–9 fix them)**

Run: `./gradlew :composeApp:compileKotlinMetadata` if available; otherwise skip and proceed to Task 8.

- [ ] **Step 3: Do not commit yet — Tasks 7, 8, 9 form an atomic signature change that is committed in Task 9**

Reason: committing an `expect` change without matching `actual` updates leaves `develop` un-buildable on every platform, which violates the always-green-trunk practice. We commit all three together at the end of Task 9.

---

## Task 8: Update Android `MapView` to honor `initialCenter`

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/gallr/app/ui/tabs/map/MapView.android.kt`

- [ ] **Step 1: Update the actual signature and camera setup**

In `composeApp/src/androidMain/kotlin/com/gallr/app/ui/tabs/map/MapView.android.kt`:

Add a parameter to the `actual fun MapView` declaration (around line 67 of the current file):

Before:
```kotlin
@OptIn(ExperimentalNaverMapApi::class)
@Composable
actual fun MapView(
    locations: List<MapLocation>,
    onLocationTap: (MapLocation) -> Unit,
    modifier: Modifier,
    enableUserLocation: Boolean,
) {
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition(SEOUL, INITIAL_ZOOM)
    }
    // ...
```

After:
```kotlin
@OptIn(ExperimentalNaverMapApi::class)
@Composable
actual fun MapView(
    locations: List<MapLocation>,
    onLocationTap: (MapLocation) -> Unit,
    modifier: Modifier,
    enableUserLocation: Boolean,
    initialCenter: Coordinates?,
) {
    val cameraPositionState = rememberCameraPositionState {
        val target = initialCenter
            ?.let { LatLng(it.latitude, it.longitude) }
            ?: SEOUL
        position = CameraPosition(target, INITIAL_ZOOM)
    }
    // ...
```

The rest of the file (icon cache, properties, NaverMap composable, marker rendering) is unchanged.

- [ ] **Step 2: Verify the Android target compiles**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`

Expected: SUCCESS.

- [ ] **Step 3: Do not commit yet — finish Task 9 first**

---

## Task 9: Update iOS `MapView` to honor `initialCenter`, then commit Tasks 7–9 together

**Files:**
- Modify: `composeApp/src/iosMain/kotlin/com/gallr/app/ui/tabs/map/MapView.ios.kt`

- [ ] **Step 1: Update the actual signature and factory block**

In `composeApp/src/iosMain/kotlin/com/gallr/app/ui/tabs/map/MapView.ios.kt`:

Add a parameter to the `actual fun MapView` declaration (around line 110):

Before:
```kotlin
@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
@Composable
actual fun MapView(
    locations: List<MapLocation>,
    onLocationTap: (MapLocation) -> Unit,
    modifier: Modifier,
    enableUserLocation: Boolean,
) {
    // ...
    UIKitView(
        factory = {
            val screenBounds = UIScreen.mainScreen.bounds
            val mapView = NMFMapView(frame = screenBounds)
            val target = NMGLatLng.latLngWithLat(SEOUL_LAT, lng = SEOUL_LNG)
            val cameraPosition = NMFCameraPosition.cameraPosition(target, zoom = INITIAL_ZOOM)
            mapView.moveCamera(NMFCameraUpdate.cameraUpdateWithPosition(cameraPosition))
            // ...
```

After:
```kotlin
@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
@Composable
actual fun MapView(
    locations: List<MapLocation>,
    onLocationTap: (MapLocation) -> Unit,
    modifier: Modifier,
    enableUserLocation: Boolean,
    initialCenter: Coordinates?,
) {
    // ...
    UIKitView(
        factory = {
            val screenBounds = UIScreen.mainScreen.bounds
            val mapView = NMFMapView(frame = screenBounds)
            val target = initialCenter
                ?.let { NMGLatLng.latLngWithLat(it.latitude, lng = it.longitude) }
                ?: NMGLatLng.latLngWithLat(SEOUL_LAT, lng = SEOUL_LNG)
            val cameraPosition = NMFCameraPosition.cameraPosition(target, zoom = INITIAL_ZOOM)
            mapView.moveCamera(NMFCameraUpdate.cameraUpdateWithPosition(cameraPosition))
            // ...
```

The rest of the `factory` block (`positionMode`, `mapRef`, container creation), the `update` block, and the `NMFMapContainerView` class are unchanged.

- [ ] **Step 2: Verify the iOS target compiles**

Run: `./gradlew :composeApp:compileKotlinIosSimulatorArm64`

Expected: SUCCESS.

- [ ] **Step 3: Verify both platforms compile together**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64`

Expected: SUCCESS for both.

- [ ] **Step 4: Commit Tasks 7, 8, 9 atomically**

```bash
git add composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapView.kt \
        composeApp/src/androidMain/kotlin/com/gallr/app/ui/tabs/map/MapView.android.kt \
        composeApp/src/iosMain/kotlin/com/gallr/app/ui/tabs/map/MapView.ios.kt
git commit -m "feat(map): plumb initialCenter through MapView expect/actual"
```

---

## Task 10: Wire everything together in `MapScreen`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapScreen.kt`

- [ ] **Step 1: Add the new imports**

In `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapScreen.kt`, add these to the existing import block (sorted alphabetically with the rest):

```kotlin
import androidx.compose.foundation.background
import com.gallr.shared.util.isInsideKorea
```

(`Box`, `Modifier.weight`, `Modifier.fillMaxSize`, `MaterialTheme`, `mutableStateOf`, etc. are already imported.)

- [ ] **Step 2: Replace the `MapView(...)` call site**

Inside `MapScreen`'s existing `Column`, locate the current `MapView(...)` call (around line 131 of the file as of `develop @ 538102b`):

Before:
```kotlin
            MapView(
                locations = locations,
                onLocationTap = { location ->
                    if (location.count == 1) {
                        selectedPin = location.pins.first()
                    } else {
                        selectedLocation = location
                    }
                },
                modifier = Modifier.weight(1f),
                enableUserLocation = locationPermission.isGranted,
            )
```

After:
```kotlin
            val cachedCoords = rememberLastKnownCoordinates(
                enabled = locationPermission.isGranted,
            )
            val initialCenter = cachedCoords?.takeIf {
                isInsideKorea(it.latitude, it.longitude)
            }
            val mapReady = rememberMapReadiness(
                permissionGranted = locationPermission.isGranted,
                coordsResolved = cachedCoords != null,
            )

            if (mapReady) {
                MapView(
                    locations = locations,
                    onLocationTap = { location ->
                        if (location.count == 1) {
                            selectedPin = location.pins.first()
                        } else {
                            selectedLocation = location
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enableUserLocation = locationPermission.isGranted,
                    initialCenter = initialCenter,
                )
            } else {
                // Placeholder matches map background — invisible during the brief
                // (≤300ms) window while the cached fix resolves.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                )
            }
```

- [ ] **Step 3: Verify both platforms compile**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64`

Expected: SUCCESS for both.

- [ ] **Step 4: Run the full unit test suite to make sure nothing regressed**

Run: `./gradlew :shared:allTests :composeApp:testDebugUnitTest`

Expected: PASS (KoreaBoundsTest passes, no regressions in existing tests).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapScreen.kt
git commit -m "feat(map): center on user's location when permission is granted (034)"
```

---

## Task 11: Manual QA on Android

**Prerequisites:** Android device or emulator with Google Play Services. Either an emulator with mock-location set to Seoul (37.5665, 126.9780) or — better — your own dev device.

- [ ] **Step 1: Install the build**

Run: `./gradlew :composeApp:installDebug`

- [ ] **Step 2: QA #1 — first install, deny permission**

1. From a terminal: `adb shell pm clear com.gallr.app` (resets permission state).
2. Open the app → tap Map tab → permission dialog appears → tap **Deny**.
3. Expected: map opens centered on Seoul (37.5665, 126.9780). No crash.

- [ ] **Step 3: QA #2 — first install, grant permission, kill app, reopen**

1. `adb shell pm clear com.gallr.app`
2. Open the app → Map tab → permission dialog → tap **Allow**.
3. Expected this session: map opens on Seoul (first-launch behavior, see spec Q3).
4. Kill the app: `adb shell am force-stop com.gallr.app`
5. Reopen the app → Map tab.
6. Expected: map opens centered on the device's current location (assuming you are in Korea — emulator with `geo fix 126.97 37.57` works).

- [ ] **Step 4: QA #3 — fake an outside-Korea location**

1. With permission still granted, set a US location:
   - Emulator: `adb emu geo fix -74.006 40.7128` (NYC)
   - Or use a fake-GPS app on a real device.
2. Kill and reopen the app → Map tab.
3. Expected: map opens on Seoul (NYC fix is rejected by `isInsideKorea`).

- [ ] **Step 5: QA #4 — pan, switch tabs, return**

1. Pan the map far away (drag to e.g. Busan).
2. Tap Featured tab.
3. Tap Map tab.
4. Expected: map is back at the centered location (or Seoul) — pan position is not preserved (see spec Q5).

- [ ] **Step 6: Note any defects, fix and re-verify**

If any QA step fails, fix in a small follow-up commit and re-run that step.

---

## Task 12: Manual QA on iOS

**Prerequisites:** iOS Simulator (Xcode-installed) or a connected iOS device.

- [ ] **Step 1: Build and run from Xcode** (open `iosApp/iosApp.xcodeproj`, hit Run)

- [ ] **Step 2: QA #1 — first install, deny permission**

1. In Simulator: Device → Erase All Content and Settings (or just delete the app and reinstall).
2. Open the app → Map tab → permission dialog → **Don't Allow**.
3. Expected: map opens on Seoul. No crash.

- [ ] **Step 3: QA #2 — first install, grant permission, kill app, reopen**

1. Delete + reinstall the app.
2. Set the simulator location: Features → Location → Custom Location → Lat 37.5665, Lng 126.9780 (Seoul). Or pick **Apple** for default.
3. Open the app → Map tab → **Allow While Using App**.
4. Expected this session: map opens on Seoul (first-launch behavior).
5. Stop the app from Xcode, then re-launch.
6. Expected: map opens centered on the simulator's set location (if inside Korea bounding box).

- [ ] **Step 4: QA #3 — fake an outside-Korea location**

1. Simulator → Features → Location → Custom Location → Lat 40.7128, Lng -74.0060 (NYC).
2. Kill and reopen the app.
3. Expected: map opens on Seoul.

- [ ] **Step 5: QA #4 — pan, switch tabs, return**

Same as Android Task 11 Step 5.

- [ ] **Step 6: Note any defects, fix and re-verify**

---

## Task 13: Open the PR

- [ ] **Step 1: Push the branch**

```bash
git push -u origin 034-map-center-on-user-location
```

- [ ] **Step 2: Open the PR**

```bash
gh pr create --base develop --title "feat(map): center on user's location when permission is granted (034)" --body "$(cat <<'EOF'
## Summary
- Map tab now centers on the device's cached location when permission has been granted in a previous session and the cached fix is inside the Korea bounding box (33.0–38.9°N, 124.6–131.9°E). Otherwise it falls back to Seoul (current behavior).
- New `expect rememberLastKnownCoordinates(enabled)` reads `FusedLocationProviderClient.lastLocation` (Android) / `CLLocationManager.location` (iOS). No fresh GPS fix is requested.
- New `rememberMapReadiness` defers `MapView` composition for up to 300ms while the cached fix resolves, so the camera initializes correctly on the first frame instead of jumping from Seoul.

## Test plan
- [ ] `./gradlew :shared:allTests` (KoreaBoundsTest passes — 11 cases: Seoul/Busan/Jeju in, Tokyo/NYC out, four edge cases)
- [ ] `./gradlew :composeApp:testDebugUnitTest` (no regressions)
- [ ] Android manual QA (deny → Seoul; grant + kill + reopen → user; fake NYC → Seoul; tab switch → re-centers)
- [ ] iOS manual QA (same four scenarios on simulator)

Spec: `specs/034-map-center-on-user-location/spec.md`
🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3: Confirm CI passes** (if any), then return the PR URL to the user.

---

## Self-Review

**Spec coverage:**
- Desired behavior #1 (granted + inside Korea → user location): Tasks 1, 4, 5, 10. ✅
- Desired behavior #2 (granted + outside Korea → Seoul): Task 1 (rejection logic) + Task 10 (`takeIf`). ✅
- Desired behavior #3 (denied / no fix → Seoul): Tasks 4, 5 (return null), Task 6 (mapReady=true immediately when permission denied), Task 10 (`initialCenter = null` → Seoul fallback in actuals). ✅
- Behavior decision Q1 (cached only): Tasks 4, 5 use `lastLocation` / `.location` — no fresh GPS request. ✅
- Q2 (Korea check in shared/common): Task 1 in `shared` module. ✅
- Q3 (first-launch is Seoul): Task 10 — when `mapReady=true` immediately because `permissionGranted=false`, MapView composes with `initialCenter=null`. After permission granted, factory has already run. ✅
- Q4 (no recency check): Tasks 4, 5 — no time check on the returned location. ✅
- Q5 (re-center on each Map visit): Implicit — `MapScreen` re-composes on tab visit, `rememberLastKnownCoordinates` re-runs, factory captures fresh `initialCenter`. ✅
- Q6 (300ms deferral): Task 6. ✅
- All 5 manual QA scenarios in spec: Tasks 11 + 12. ✅
- Unit tests for `isInsideKorea`: Task 1 (11 cases). ✅
- Dependency adds (`play-services-location`, `kotlinx-coroutines-play-services`): Task 2. ✅

**Placeholder scan:** No `TBD`, `TODO`, `implement later`, `add appropriate error handling` patterns. All code blocks are complete and copy-pasteable.

**Type consistency:**
- `Coordinates(latitude, longitude)` defined in Task 3, used identically in Tasks 4, 5, 8, 9, 10. ✅
- `rememberLastKnownCoordinates(enabled: Boolean): Coordinates?` declared in Task 3, implemented identically in Tasks 4 and 5. ✅
- `isInsideKorea(lat, lng): Boolean` defined in Task 1 (`shared/.../util/`), called identically in Task 10 with `it.latitude, it.longitude`. ✅
- `rememberMapReadiness(permissionGranted, coordsResolved, timeoutMillis = 300L): Boolean` declared in Task 6, called in Task 10 with named args (`timeoutMillis` left at default). ✅
- `initialCenter: Coordinates?` parameter on `expect MapView` in Task 7, matching parameter on Android actual (Task 8) and iOS actual (Task 9). ✅

No issues found.

---

## Execution

After completing all 13 tasks, the PR is open against `develop`. The feature is fully implemented and manually verified on both platforms.
