# 034 — Map: center on user's location

**Status:** Design
**Priority:** p2
**Source:** `260425-map-center-on-user-location-p2.md`
**Date:** 2026-04-25

## Problem

The MAP tab always opens centered on Seoul (37.5665, 126.9780) even when the user has already granted location permission on a previous app launch. The initial camera position should reflect the user's actual location when available and reasonable.

`MapScreen.kt:69–72` requests location permission on first composition. Both platform `MapView` implementations initialize the camera to Seoul regardless of whether permission was granted:

- Android: `MapView.android.kt:74–76` — `CameraPosition(SEOUL, INITIAL_ZOOM)`
- iOS: `MapView.ios.kt:125–127` — hardcoded Seoul coordinates passed to `NMFCameraUpdate`

`enableUserLocation` is already passed to `MapView` but only enables the location dot — it never moves the initial camera.

## Desired behavior

1. **Permission granted + cached fix available** → center on user's cached location at `INITIAL_ZOOM`, regardless of country.
2. **Permission denied / undetermined / no cached fix** → fall back to Seoul.

### Why no Korea check?

An earlier draft of this spec gated the user-location centering on a Korea bounding-box check, falling back to Seoul for any fix outside South Korea. That was rejected during iOS QA: the gallr dataset is currently Korea-only but is expected to expand to international galleries, and a user in Tokyo or NYC with the app installed (whether traveling or living abroad) would have a worse experience being teleported back to Seoul than being centered on their actual location, even when no nearby exhibitions exist *yet*. The country check was deleted in commit `d80f233`.

When the international dataset lands, a follow-up can refine the fallback (e.g., center on the exhibition centroid nearest the user, or on the nearest country with exhibition data) — but the *cached fix* path needs no geography filter.

## Behavior decisions (locked during brainstorm)

| Question | Decision | Rationale |
|---|---|---|
| Coordinate freshness | Cached / last-known fix only — no waiting for fresh GPS | Simplest, no animation/jump UX |
| Geographic filter on cached fix | None — trust any cached fix | Country gating breaks future international expansion (see "Why no Korea check?" above) |
| First-launch UX (permission not yet granted at first MapView composition) | Accept Seoul fallback on first launch; subsequent launches use cached location | Consistent with no-animation rule |
| Cached-fix recency check | None — accept any cached fix | A possibly-stale location is more useful than Seoul |
| Mid-session re-entry into Map tab | Re-center on every Map tab visit | Matches current "first composition decides camera" framing |
| Deferral while waiting for cached fix | Defer `MapView` composition up to ~300ms while `coords` resolve, else Seoul fallback | Avoids "Seoul → user-location" jump; iOS resolves <1ms, Android typically tens of ms |

## Architecture

### New shared abstraction (commonMain)

```kotlin
// composeApp/src/commonMain/.../map/UserLocation.kt
data class Coordinates(val latitude: Double, val longitude: Double)

@Composable
expect fun rememberLastKnownCoordinates(enabled: Boolean): Coordinates?
```

`enabled` lets the caller pass `locationPermission.isGranted`. Returns `null` for permission denied, no cached fix, platform error, or while the platform call is still pending.

### MapView signature change (commonMain)

```kotlin
expect fun MapView(
    locations: List<MapLocation>,
    onLocationTap: (MapLocation) -> Unit,
    modifier: Modifier = Modifier,
    enableUserLocation: Boolean = false,
    initialCenter: Coordinates? = null,   // NEW; null → platform default (Seoul)
)
```

### Wiring in MapScreen

Inside `MapScreen`'s existing `Column { ... }` (the same one currently wrapping `TabRow`, `HorizontalDivider`, the empty-state Text, and `MapView`), replace the current `MapView(...)` call with:

```kotlin
val initialCenter = rememberLastKnownCoordinates(enabled = locationPermission.isGranted)

// Defer MapView composition up to 300ms while waiting for cached coords
val mapReady = rememberMapReadiness(
    permissionGranted = locationPermission.isGranted,
    coordsResolved = initialCenter != null,
)
if (mapReady) {
    MapView(
        locations = locations,
        onLocationTap = { /* unchanged from current */ },
        modifier = Modifier.weight(1f),
        enableUserLocation = locationPermission.isGranted,
        initialCenter = initialCenter,
    )
} else {
    // Placeholder matching map background — invisible "loading" region
    Box(modifier = Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background))
}
```

`rememberMapReadiness` is a new helper in `commonMain` (same file as `UserLocation.kt` or its own `MapReadiness.kt` — implementer's choice). It returns `true` once either `coordsResolved` is true OR `timeoutMillis` has elapsed. If `permissionGranted == false`, returns `true` immediately (no point waiting).

## Platform actuals

### Android — `composeApp/src/androidMain/.../map/UserLocation.android.kt` (new)

```kotlin
@Composable
actual fun rememberLastKnownCoordinates(enabled: Boolean): Coordinates? {
    val context = LocalContext.current
    var coords by remember { mutableStateOf<Coordinates?>(null) }

    LaunchedEffect(enabled) {
        if (!enabled) { coords = null; return@LaunchedEffect }
        coords = runCatching {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val location = client.lastLocation.await()
            location?.let { Coordinates(it.latitude, it.longitude) }
        }.getOrNull()
    }
    return coords
}
```

**Dependencies to verify / add in `composeApp/build.gradle.kts`:**
- `com.google.android.gms:play-services-location` (for `FusedLocationProviderClient`)
- `org.jetbrains.kotlinx:kotlinx-coroutines-play-services` (for `Task.await()`)

### Android — `MapView.android.kt` change

Replace the `rememberCameraPositionState` call:
```kotlin
val cameraPositionState = rememberCameraPositionState {
    position = CameraPosition(
        initialCenter?.let { LatLng(it.latitude, it.longitude) } ?: SEOUL,
        INITIAL_ZOOM,
    )
}
```

### iOS — `composeApp/src/iosMain/.../map/UserLocation.ios.kt` (new)

```kotlin
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberLastKnownCoordinates(enabled: Boolean): Coordinates? {
    val manager = remember { CLLocationManager() }
    var coords by remember { mutableStateOf<Coordinates?>(null) }

    DisposableEffect(enabled) {
        if (!enabled) {
            coords = null
            return@DisposableEffect onDispose { manager.delegate = null }
        }
        val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
            override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
                val location = didUpdateLocations.firstOrNull() as? CLLocation ?: return
                coords = location.coordinate.useContents { Coordinates(latitude, longitude) }
            }
            override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
                // Leave coords null → MapScreen falls back to Seoul.
            }
        }
        manager.delegate = delegate
        manager.requestLocation()
        onDispose { manager.delegate = null }
    }
    return coords
}
```

**Important: Why `requestLocation()` rather than reading `manager.location` directly.** An earlier draft of this spec read the synchronous `CLLocationManager.location` property, expecting it to return the OS's most-recent cached fix. iOS QA proved this assumption wrong: a freshly-created `CLLocationManager` has `.location == nil` until the manager itself has subscribed to location updates (via `requestLocation()` or `startUpdatingLocation()`) — there is no cross-app cached fix the way Android's `FusedLocationProviderClient.lastLocation` provides one. `requestLocation()` returns the OS's cached value immediately when one exists (delivered on the next runloop tick via the delegate, well within the 300ms `rememberMapReadiness` window) and only triggers a fresh GPS query when no cache exists — so for the "permission was granted in a prior session" common case, it behaves like the originally-intended cached read. Fixed in commit `db4d84e`.

No new dependencies.

### iOS — `MapView.ios.kt` change

In the `factory` block:
```kotlin
val target = initialCenter
    ?.let { NMGLatLng.latLngWithLat(it.latitude, lng = it.longitude) }
    ?: NMGLatLng.latLngWithLat(SEOUL_LAT, lng = SEOUL_LNG)
val cameraPosition = NMFCameraPosition.cameraPosition(target, zoom = INITIAL_ZOOM)
```

The `factory` closure captures `initialCenter` at first composition. Subsequent recompositions with different `initialCenter` do **not** re-trigger the factory — by design (camera-jump avoided per Q1).

## Data flow

### Cold launch, permission granted from prior session, user inside Korea

```
1. MapScreen composes
2. rememberLocationPermissionState() → isGranted = true
3. rememberLastKnownCoordinates(enabled=true) launches LaunchedEffect
   ├─ Android: FusedLocationProviderClient.lastLocation.await() → ~10–50ms
   └─ iOS:    CLLocationManager.location property read → <1ms
4. Within 300ms timeout, coords flips to Coordinates(...)
5. isInsideKorea(...) → true → initialCenter = those coords
6. mapReady → true → MapView composes with initialCenter
7. NaverMap factory runs ONCE → camera positioned on user's location
```

### Cold launch, permission not yet granted

```
1. MapScreen composes
2. isGranted = false → permission request fires (existing LaunchedEffect)
3. rememberLastKnownCoordinates(enabled=false) returns null immediately
4. rememberMapReadiness sees permissionGranted=false → mapReady = true immediately
5. MapView composes with initialCenter = null → camera = Seoul
6. User taps "Allow" → isGranted flips → recomposition
7. coords arrives, but MapView factory already ran → camera stays on Seoul this session
8. Next Map tab visit (or next launch) → centered on user
```

## Edge cases & error handling

| Scenario | Behavior |
|---|---|
| Permission denied | `enabled=false` → `coords=null` → Seoul |
| Permission granted, no cached fix | Platform returns null → Seoul |
| Permission granted, cached fix anywhere | User's location, `INITIAL_ZOOM=10.0` |
| Android `lastLocation` throws `SecurityException` | `runCatching` swallows → Seoul |
| Android `lastLocation` task takes >300ms | Timeout fires → Seoul |
| Play Services missing on Android | Task fails → `runCatching` → Seoul |
| iOS `CLLocationManager.location` returns nil | → Seoul |
| User enables permission in Settings, returns to app | Existing iOS delegate flips `isGranted`; on next Map tab visit, centered on them |

No user-facing error UI — all failures degrade silently to Seoul.

## Testing

No new unit tests are required. The pure logic in this feature (a single `?.`-chained expression in `MapScreen`) is too thin to be worth a dedicated test; the platform actuals call OS APIs that are not worth mocking (per the spec's testing philosophy). Coverage is via manual QA on both platforms.

### Manual QA (Android + iOS)

1. Cold install, deny permission → map opens on Seoul.
2. Cold install, grant permission, kill app, reopen → map opens on user's actual location.
3. Set device to a non-Korean location (e.g., NYC) and reopen → map opens on that location (no Korea-only filter).
4. Toggle airplane mode + clear location cache → permission still granted, no cached fix → Seoul.
5. Pan away from current location, switch to Featured tab, back to Map → re-centers on user's location.

## Affected files

**New:**
- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/UserLocation.kt` (declares `Coordinates`, `expect rememberLastKnownCoordinates`, and `rememberMapReadiness`)
- `composeApp/src/androidMain/kotlin/com/gallr/app/ui/tabs/map/UserLocation.android.kt`
- `composeApp/src/iosMain/kotlin/com/gallr/app/ui/tabs/map/UserLocation.ios.kt`

**Modified:**
- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapScreen.kt` — wire `rememberLastKnownCoordinates` + 300ms-deferred render
- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapView.kt` — add `initialCenter: Coordinates?` parameter to `expect fun`
- `composeApp/src/androidMain/kotlin/com/gallr/app/ui/tabs/map/MapView.android.kt` — use `initialCenter` in `rememberCameraPositionState`
- `composeApp/src/iosMain/kotlin/com/gallr/app/ui/tabs/map/MapView.ios.kt` — use `initialCenter` in `factory` block
- `gradle/libs.versions.toml` + `composeApp/build.gradle.kts` — add `play-services-location` and `kotlinx-coroutines-play-services`

## Out of scope

- Fresh GPS fix (vs. cached `lastLocation`).
- Camera animation when permission flips mid-session.
- Persisting camera position across tab switches.
- Recency check on cached fixes.
- A "centroid of nearby exhibitions" fallback when there's no cached fix — deferred to international rollout.
- Recenter-to-me FAB (possible follow-up p3).
