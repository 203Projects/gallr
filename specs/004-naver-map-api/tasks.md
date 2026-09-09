# Tasks: Interactive Map with Exhibition Pins

> **Status: Superseded historical plan.** The embedded Naver Maps implementation
> and its credentials were retired by the later personal-map/provider-removal
> work. The current application uses the MapLibre-based architecture documented
> in `CLAUDE.md`. Do not execute the unchecked Naver SDK, credential, marker, or
> verification tasks below.

**Input**: Design documents from `/specs/004-naver-map-api/`
**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/ ✅, quickstart.md ✅

**Tests**: Not explicitly requested — verification tasks (manual run & check) are included per story checkpoint instead of automated tests. Platform UI layers are exempt from the Test-First mandate per Constitution Principle II.

**Organization**: Tasks are grouped by user story. Each story (P1→P3) is independently verifiable. US1 is the MVP — a real map with pins. US2 and US3 are verification-only phases (no new code) that confirm the existing `MapScreen.kt` integration works with the real map.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1–US3)

---

## Phase 1: Setup (Build Configuration)

**Purpose**: Wire Naver Maps SDKs into the build system — nothing compiles without this.

- [x] T001 Add Naver Maps Maven repository to `settings.gradle.kts` inside `dependencyResolutionManagement.repositories`: `maven("https://repository.map.naver.com/archive/maven")`
- [x] T002 Add version aliases to `gradle/libs.versions.toml`: `naver-map-sdk = "3.23.0"` and `naver-map-compose = "1.8.1"` under `[versions]`; add `naver-map-sdk = { module = "com.naver.maps:map-sdk", version.ref = "naver-map-sdk" }` and `naver-map-compose = { module = "io.github.fornewid:naver-map-compose", version.ref = "naver-map-compose" }` under `[libraries]`
- [x] T003 Add Android dependencies to `composeApp/build.gradle.kts` `androidMain.dependencies` block: `implementation(libs.naver.map.sdk)` and `implementation(libs.naver.map.compose)`
- [x] T004 Create `composeApp/src/nativeInterop/cinterop/NMapsMap.def` with content: `language = Objective-C`, `modules = NMapsMap`, `package = NMapsMap`
- [x] T005 Configure iOS cinterop in `composeApp/build.gradle.kts`: for each of `iosX64()`, `iosArm64()`, `iosSimulatorArm64()` add a `compilations.getByName("main")` block with `val NMapsMap by cinterops.creating { definitionFile.set(project.file("src/nativeInterop/cinterop/NMapsMap.def")) }` — add this inside the existing `listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach` block

---

## Phase 2: Foundational (SDK Credentials & iOS Framework)

**Purpose**: Register the NCP Key ID and link the Naver Maps iOS framework — MUST complete before any platform implementation.

**⚠️ CRITICAL**: iOS cinterop requires the `NMapsMap.xcframework` to be present in the Xcode build products before Gradle can process it. T007 must complete (and an initial Xcode build run) before T005 cinterop will resolve.

- [x] T006 Add NCP Key ID to `composeApp/src/androidMain/AndroidManifest.xml` inside `<application>`: `<meta-data android:name="com.naver.maps.map.NCP_KEY_ID" android:value="dkd2c8bh63" />`
- [ ] T007 Add Naver Maps iOS SDK via SPM to `iosApp/iosApp.xcodeproj`: in Xcode open **File → Add Package Dependencies…**, enter `https://github.com/navermaps/SPM-NMapsMap`, select latest release, add `NMapsMap` to the `iosApp` target — then do one Xcode build to resolve the framework so cinterop can find it
- [x] T008 Add `NMFAuthManager` init to `iosApp/iosApp/iOSApp.swift`: add `import NMapsMap` and an `init()` to the `iOSApp` App struct calling `NMFAuthManager.shared().ncpKeyId = "dkd2c8bh63"`
- [ ] T009 Verify build gates: run `./gradlew :composeApp:assembleDebug` (Android compiles with Naver deps) and `./gradlew :composeApp:iosSimulatorArm64Binaries` (iOS cinterop resolves `NMapsMap`); both must succeed before US1 implementation begins

**Checkpoint**: Build system ready — Naver Maps SDKs linked on both platforms, credentials in place.

---

## Phase 3: User Story 1 — Real Interactive Map (Priority: P1) 🎯 MVP

**Goal**: Replace the green placeholder on both platforms with a live, pannable/zoomable Naver Map that renders exhibition pins.

**Independent Test**: Launch the app on Android emulator and iOS simulator; navigate to the Map tab; confirm: (1) no green background, (2) no "Map SDK: TBD" text, (3) real map tiles render, (4) at least one black dot/marker appears per exhibition that has coordinates.

### Implementation for User Story 1

- [x] T010 [US1] Replace `composeApp/src/androidMain/kotlin/com/gallr/app/ui/tabs/map/MapView.android.kt` stub with a `NaverMap` composable implementation: set `cameraPositionState` to Seoul (lat `37.5665`, lng `126.9780`, zoom `10.0`); iterate `pins` and render one `Marker` per pin at `LatLng(pin.latitude, pin.longitude)` with `captionText = pin.name`; wire `Marker.onClick = { onMarkerTap(pin); true }`
- [x] T011 [US1] Replace `composeApp/src/iosMain/kotlin/com/gallr/app/ui/tabs/map/MapView.ios.kt` stub with a `UIKitView` + `NMFMapView` implementation: `factory` block creates `NMFMapView()` and sets camera to Seoul zoom 10 via `NMFCameraUpdate`; `update` block clears all existing markers then iterates `pins` — for each creates `NMFMarker()`, sets `position = NMGLatLng(pin.latitude, pin.longitude)`, sets `touchHandler` lambda to call `onMarkerTap(pin)`, assigns `mapView` to attach; set `UIKitInteropProperties(interactionMode = UIKitInteropInteractionMode.NonCooperative)`
- [x] T012 [US1] Build and run on Android emulator: `./gradlew :composeApp:assembleDebug` — BUILD SUCCESSFUL, libnavermap.so packaged ✓: `./gradlew :composeApp:assembleDebug` then install and launch; open Map tab — confirm real map renders, pins visible, no placeholder text
- [ ] T013 [US1] Build and run on iOS simulator (iPhone 16 Pro `2A8E55FD-BCF4-43E3-83DD-124D895308A0`): build via Xcode then launch; open Map tab — confirm real map renders, pins visible, no placeholder text

**Checkpoint**: US1 complete — gallr Map tab shows a live Naver Map with exhibition pins on both platforms. This is a shippable MVP.

---

## Phase 4: User Story 2 — Filtered Pins View (Priority: P2)

**Goal**: Confirm the existing FILTERED/ALL toggle in `MapScreen.kt` correctly drives the real map's pin count (no new code required — the `MapView` `actual` already receives whichever list `MapScreen` passes).

**Independent Test**: With both platforms showing the real map (US1 done): apply any filter on the List tab; switch to Map tab with FILTERED mode — pin count must match filtered exhibition count. Switch to ALL — all geolocated exhibitions appear.

### Implementation for User Story 2

- [ ] T014 [US2] Verify FILTERED mode on Android: apply a filter that reduces the exhibition list, switch to Map tab, confirm FILTERED mode shows fewer pins than ALL mode and the count matches the filtered list count; toggle back to ALL and confirm all geolocated exhibitions appear
- [ ] T015 [P] [US2] Verify FILTERED mode on iOS simulator: same verification as T014 but on the iOS simulator
- [ ] T016 [US2] Verify empty-filter edge case on both platforms: apply a filter that matches zero exhibitions; confirm Map tab in FILTERED mode shows zero pins and the "No exhibitions match the current filters." text is visible (existing `MapScreen.kt` logic)

**Checkpoint**: US2 complete — filter integration confirmed working with real map on both platforms.

---

## Phase 5: User Story 3 — Tap Pin for Exhibition Details (Priority: P3)

**Goal**: Confirm the `onMarkerTap` callback is wired correctly in both `actual` implementations so tapping a pin shows the correct exhibition dialog.

**Independent Test**: Tap a specific pin on Android and iOS; the dialog that appears must show the exhibition name, venue name, and date range for that exact pin — not a different pin.

### Implementation for User Story 3

- [ ] T017 [US3] Verify pin tap on Android: tap at least 3 different pins; confirm each dialog shows the correct `name`, `venueName`, and `openingDate–closingDate` for the tapped pin; dismiss each dialog and confirm map is interactive again
- [ ] T018 [P] [US3] Verify pin tap on iOS simulator: same verification as T017; confirm `NMFOverlayTouchHandler` correctly identifies which `ExhibitionMapPin` was tapped by index/reference and passes it to `onMarkerTap`
- [ ] T019 [US3] Verify overlapping pins edge case: if two or more exhibitions share the same venue (same lat/lng), confirm tapping that location opens a dialog for one of them without a crash

**Checkpoint**: US3 complete — all three user stories verified. Full feature is functional end-to-end on both platforms.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Design system alignment and error resilience.

- [ ] T020 Replace default Naver Maps red teardrop marker with a monochrome black square marker on Android in `MapView.android.kt`: set `Marker.icon` to a black `OverlayImage` or use `MarkerIcons.BLACK`
- [ ] T021 [P] Replace default marker with monochrome black marker on iOS in `MapView.ios.kt`: set `NMFMarker().iconImage` to `NMFOverlayImage(name: "")` or set the marker's `iconTintColor` to `UIColor.black`
- [ ] T022 [P] Add auth error logging on Android in `MapView.android.kt`: implement `NaverMapSdk.OnAuthFailedListener` and log the error via `android.util.Log.e` with tag `"NaverMaps"`; display a `Text("Map unavailable")` fallback if auth fails
- [ ] T023 [P] Add auth error handling on iOS in `MapView.ios.kt`: implement `NMFAuthManagerDelegate` on a local coordinator object; log auth errors via `print("[NaverMaps] auth error: \(error)")`; show fallback text if auth fails
- [ ] T024 Validate `quickstart.md` steps end-to-end: follow each step in `specs/004-naver-map-api/quickstart.md` from a clean checkout; confirm `./gradlew :composeApp:assembleDebug` and Xcode build both succeed without manual intervention beyond the steps listed

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies — start immediately; T004 and T005 can run in parallel after T001–T003
- **Phase 2 (Foundational)**: Depends on Phase 1 — BLOCKS all user stories; T006, T007, T008 can run in parallel; T009 must run after T006–T008
- **Phase 3 (US1)**: Depends on Phase 2 completion; T010 and T011 can run in parallel; T012 and T013 can run in parallel after T010/T011
- **Phase 4 (US2)**: Depends on Phase 3 completion (real map must render); T014, T015, T016 can run in parallel
- **Phase 5 (US3)**: Depends on Phase 3 (tap handler wired in T010/T011); T017, T018, T019 can run in parallel after T010/T011
- **Phase 6 (Polish)**: Depends on all user story phases complete; T020–T023 can all run in parallel

### User Story Dependencies

- **US1 (P1)**: Depends only on Phase 2 — this is the core implementation
- **US2 (P2)**: Depends on US1 (real map must render for filter verification to be meaningful) — no new code
- **US3 (P3)**: Depends on US1 (tap handler wired in T010/T011) — no new code; verification only

---

## Parallel Opportunities

### Phase 1

```
T001 (settings.gradle.kts)
T002 (libs.versions.toml)
T003 (build.gradle.kts Android deps) — after T002
T004 (NMapsMap.def)                  — parallel with T001–T003
T005 (iOS cinterop config)           — after T004
```

### Phase 2

```
T006 (AndroidManifest NCP key)       — parallel
T007 (Xcode SPM + first build)       — parallel
T008 (iOSApp.swift NMFAuthManager)   — parallel after T007
T009 (build gate verification)       — after T006–T008
```

### US1 (Phase 3)

```
T010 (MapView.android.kt)  \
T011 (MapView.ios.kt)      /  parallel — different files
T012 (Android verify)      — after T010
T013 (iOS verify)          — after T011
```

### US2 + US3 (Phases 4–5) — can run in parallel after US1

```
T014 (Android filter verify)  \
T015 (iOS filter verify)      |  all parallel — verification only
T016 (empty filter edge case) |
T017 (Android tap verify)     |
T018 (iOS tap verify)         |
T019 (overlapping pins)       /
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Build Configuration (T001–T005)
2. Complete Phase 2: Foundational (T006–T009) — CRITICAL gate
3. Complete Phase 3: US1 implementation (T010–T013)
4. **STOP and VALIDATE**: Real map visible on both platforms with exhibition pins
5. The MVP is deployable: live map replaces placeholder

### Incremental Delivery

1. Phase 1 + 2 → Build system ready
2. + US1 → Live map deployed → MVP ✅
3. + US2 → Confirm filter integration works
4. + US3 → Confirm tap-for-details works
5. + Phase 6 → Monochrome markers + error handling → Production-ready

### Parallel Team Strategy (if two developers available after Phase 2)

```
Developer A: T010 (MapView.android.kt) → T012 (Android verify) → T014/T017 (Android verification)
Developer B: T011 (MapView.ios.kt)     → T013 (iOS verify)     → T015/T018 (iOS verification)
Meet at Phase 6: Polish together
```

---

## Notes

- T007 (Xcode SPM) is a manual step — requires Xcode UI interaction; cannot be scripted
- The cinterop (T005) requires the NMapsMap framework to be already resolved by Xcode/SPM before Gradle can process it — run an Xcode build first after T007
- T010 and T011 are the only implementation tasks — all other tasks are build config, credential injection, or verification
- The `MapScreen.kt`, `MapView.kt` (expect), `TabsViewModel`, and all `shared/` files are untouched
- All filtering logic (`filteredMapPins`, `allMapPins`) already works — US2 and US3 are purely verification phases
- Client ID `dkd2c8bh63` is safe to embed in AndroidManifest and iOSApp.swift (equivalent to a Google Maps API key — restrict it to `com.gallr.app` in the Naver Cloud Platform console)
