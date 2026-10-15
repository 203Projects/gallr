# Research: Map pins without stacking

Grounded in `ui/tabs/map/SeoulDistrictMap.kt` (`SeoulExhibitionMap`, `ExhibitionPinLayers`,
`groupNearlyCoincidentPins`, `AccessibleExhibitionPinTarget`, `ExhibitionOverlapSheet`), `ui/map/LogoPinPainter.kt`
(pin geometry), `ui/tabs/map/UserLocation.kt` (opening zoom 11.8, 12.5 on the visitor) and
`ui/tabs/map/MapNearbyPresentation.kt` (overlap sheet helpers).

## How pins work today

Every frame the map projects each exhibition's place to screen pixels, joins pins within 28dp into one group
(union-find, transitive), and feeds one GeoJSON feature per group to the symbol layers at the group's centroid
(converted back to a map position). A group draws one cover and a count badge; tapping it opens
`ExhibitionOverlapSheet`. Accessible targets are Compose boxes over the same screen positions. Exhibitions at one
address always share a group because their projected distance is 0 at every zoom.

## R1. Spread from zoom 13; group below it

**Decision**: `PIN_SPREAD_FROM_ZOOM = 13.0`. At or above it pins are spread (R2) and never grouped; below it the 28dp
grouping stays as today.

**Rationale**: At Seoul's latitude MapLibre shows about 62 100 / 2^z metres per dp. Zoom 13 is about 7.6 m/dp, so a
400dp-wide phone shows about 3 km, the spec's neighbourhood. The opening views (11.8 city, 12.5 around the visitor)
stay below it, so the first view still shows count pins (spec Assumptions).

**Alternatives considered**: a zoom-independent rule (spread whenever a group has at most N members) gives
different results for the same place depending on how crowded its surroundings are; spreading at every zoom was
declined by the owner.

## R2. Spreading: a brick lattice around each overlapping group

**Decision**: a pure function arranges pins in screen space:

1. **Overlap test.** Two pins overlap when their tips are inside the ellipse `(dx / 52dp)² + (dy / 56dp)² < 1`.
   The pin image is 50.7 × 54dp with the tip at the bottom; its ring is 50.7dp across including the white edge and
   its centre is 28.7dp above the tip, so a pin directly below another needs about 54dp, one beside it about 51dp.
   52 × 56 adds a hairline of air.
2. **Components.** Pins are joined into components by that test (union-find, as today). A pin in no overlap is a
   component of one and stays exactly on its place (FR-002).
3. **Placement.** A component of two or more is placed on a brick lattice centred at the members' centroid: rows
   56dp apart, columns 52dp apart, odd rows shifted by 26dp. Neighbouring slots then pass the overlap test
   (same row: 52dp; next row: (26, 56) → 0.25 + 1.0 ≥ 1). Slots are taken nearest the centroid first. Pins are
   assigned greedily by distance from their own real place to a slot; ties go to the slot nearer the centroid, then
   the lower slot index, then the exhibition id. Several exhibitions at one place therefore keep one pin on the place
   and the rest around it (FR-003).
4. **Collisions between components.** After placement, any two pins of different components that overlap merge
   their components, which are placed again together. This repeats until nothing overlaps; it ends because each pass
   reduces the number of components.

Eight exhibitions at one place take the centre, its six neighbours (52dp or about 62dp away) and one slot 78dp away:
all within two pin widths (SC-004).

**Stability (FR-009)**: the arrangement depends only on the projected positions, the ids and the fixed sizes, and
the lattice is anchored to the centroid, so panning moves the whole arrangement with the map and the same view always
gives the same result. It changes only while the zoom changes.

**Alternatives considered**: a force-directed layout (iterative, not deterministic across frames, can oscillate); a
spiral around the place (fine for one address, but two nearby groups need a merge step anyway and the lattice packs
tighter); MapLibre's own symbol collision (it hides colliding icons instead of moving them).

## R3. Where the code lives (constitution VI)

**Decision**: the arrangement is a pure function in `composeApp/src/commonMain/.../ui/tabs/map/MapPinArrangement.kt`,
beside today's `groupNearlyCoincidentPins`, tested in `composeApp/src/commonTest`.

**Rationale**: it arranges marks in screen pixels for one map view; it is presentation geometry with no domain rule
(no exhibition, venue or date logic), like the grouping it replaces. Domain inputs stay in `shared` (exhibition
coordinates). Moving pixel layout into `shared` would put UI units into the domain layer.

## R4. Tapping a count pin zooms in

**Decision**: `countPinCameraTarget(members, currentZoom, viewport)` returns the centre (the members' centroid) and a
zoom: the zoom that fits the members' current screen extent into 60% of the map view, held between 13.0 and 14.0.
Exhibitions all at one place have no extent, so the zoom is 13.0 (spec US2 scenario 2). The map animates there with
the existing `animateCamera(CameraUpdate(center, zoom))`; the overlap sheet is not opened.

**Rationale**: the 14.0 cap keeps the neighbourhood around the group in view instead of diving to street level. Within
`MAP_MAX_ZOOM`.

**Known limit**: a chain of count-pin members longer than the screen at zoom 13 would leave some members off screen.
With a 28dp joining distance this needs more than a dozen pins in a line and does not occur in the catalogue; the
visitor can pan.

## R5. Accessible targets

**Decision**: targets follow the drawn (moved) positions. A single pin's target stays 104dp wide (pin and title) when
no other pin is within 104dp horizontally, otherwise 52dp, the pin's own width, so neighbouring targets never overlap.
A count pin reads "전시 N개 · 확대" / "N EXHIBITIONS · ZOOM IN" and zooms in on activation (FR-008).

## R6. The overlap sheet is retired from the Map tab

**Decision**: remove `ExhibitionOverlapSheet` and its helpers `sortOverlapExhibitionsByDistance`, `overlapSheetTitle`
and `overlapMetadata` (with their tests) once count pins zoom in; nothing else uses them (CLAUDE.md: remove replaced
code).

## R7. What does not change

Pin artwork, ring colours, the saved draw order, the count badge, title placement rules, the map style, camera limits,
route maps (they draw their own numbered pins in `ui/route/RouteMap.kt`), the "내 전시" filter (it passes fewer
exhibitions to the same map), and the free map stack (no new dependency).
