# Research: Mobile Map UI Regressions

## R1 — The iOS issue is double safe-area application

**Decision**: Extend the SwiftUI `ComposeView` through the container safe area and keep Compose's
existing `WindowInsets.safeDrawing`/navigation-bar handling as the single inset owner.

**Evidence**: On an iPhone 17 simulator, the built app renders the Compose root only between the
SwiftUI safe-area boundaries. Inside that reduced frame, Material 3's default Scaffold insets add a
second top safe area and `GallrNavigationBar` adds a second bottom navigation inset. The result
matches the reported blank bands. `ContentView.swift` has used `.ignoresSafeArea(.keyboard)` since
the initial scaffold; the behavior became visible after the 2026-08-11 dependency upgrade from
Compose Multiplatform 1.8.0 to 1.11.1, whose iOS implementation includes `safeDrawing` support and
fixes.

**Rejected alternatives**:

- Removing Compose insets: this would couple every shared screen to the SwiftUI host and make
  Android/iOS behavior diverge.
- Hard-coded top/bottom padding: it would fail across devices, rotation, and transient system UI.

## R2 — Group before projection by exact coordinate

**Decision**: Build coordinate groups from validated `ExhibitionMapPin.position` values before any
screen projection. Project only one representative position per group.

**Evidence**: The current union-find function groups projected candidates within 16dp, so group
membership changes with zoom. Equality of the source `Position` values directly represents the
requested same-venue rule and is deterministic across platforms.

**Rejected alternatives**:

- A geographic epsilon: the request explicitly distinguishes exact catalogue coordinates. An
  epsilon risks merging different addresses and hides upstream data quality issues.
- Retaining screen-space grouping with a smaller radius: membership would still be zoom-dependent.

## R3 — MapLibre layers own gesture arbitration

**Decision**: Prefer MapLibre Compose's `GeoJsonSource` plus clickable `SymbolLayer`/`CircleLayer`
content for visual pins instead of touchable Compose overlays. Keep any needed accessibility nodes
pointer-transparent.

**Evidence**: The installed MapLibre Compose 0.9.0 artifact exposes `rememberGeoJsonSource`,
`SymbolLayer`, `CircleLayer`, feature click handlers, and style image registration. Layer clicks are
resolved by the map engine, allowing its native gesture recognizer to arbitrate taps versus pinches.
The current overlay `Modifier.clickable` nodes are above the map and receive pointer hit tests first.

**Rejected alternatives**:

- Custom pointer-input cancellation: Compose cannot retroactively deliver a pointer stream to the
  underlying native map after an overlay won hit testing, so it is fragile across Android/iOS.
- Keeping clickable overlays with `indication = null`: this removes the ripple but not pointer theft.

## R4 — Location marker remains unchanged

**Decision**: Do not change the user-location marker in this feature.

**Evidence**: The request explicitly leaves blue versus monochrome unresolved, and `DESIGN.md`
currently permits no blue role. A palette change requires an approved design amendment.
