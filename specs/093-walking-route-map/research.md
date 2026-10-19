# Research: Walking routes and a full-screen route map (093)

Sources were checked on 2026-10-14. Code paths are from `develop` at `62ac855f`.

## R1. Routing provider: openrouteservice Standard, behind our own contract

**Decision**: Start with openrouteservice (HeiGIT) `foot-walking` on the free Standard plan, called only from the
`walking-route` Edge Function at `https://api.heigit.org/openrouteservice/v2/directions/foot-walking/json`. Hide it
behind our contract (contracts/walking-route.md) and an adapter interface. An OSRM adapter is built and tested in the
same change, so the provider can be switched by configuration.

**Findings**:
- **Limits:** Standard plan is 2,000 Directions requests a day and 40 a minute, 50 waypoints per request, free
  ([plans](https://account.heigit.org/info/plans), [restrictions](https://openrouteservice.org/restrictions/)).
- **Endpoint change:** `api.openrouteservice.org` is deprecated and shuts down on 2–6 Nov 2026. Only the
  `api.heigit.org` base URL is used ([forum 7912](https://ask.openrouteservice.org/t/7912),
  [8013](https://ask.openrouteservice.org/t/8013)).
- **Commercial use:** allowed on Standard according to a staff reply on 2026-10-12
  ([forum 8079](https://ask.openrouteservice.org/t/8079)). That is a forum reply, not contract text.
- **Results licence:** "Results obtained from openrouteservice … are licensed under CC-BY-SA 4.0." Permanent
  storage of route geometry in a commercial app is **not confirmed**; HeiGIT answered a similar question only with
  "contact us" ([forum 8057](https://ask.openrouteservice.org/t/8057), [ToS](https://account.heigit.org/info/tos)).
- **Personal data:** the ToS forbid sending personal data, and HeiGIT will not say whether coordinates count.
- **Availability:** foot-walking was down 26–28 Feb 2026 for a graph rebuild and has been stable since
  ([forum 7843](https://ask.openrouteservice.org/t/7843)). Maintenance is announced for 2026-10-23.
- **Korea:** served from the global OSM graph; no known gaps. Seoul footway quality still needs a spot check during
  implementation.

**Consequences for the design**:
1. **Before release, written confirmation from HeiGIT that storing results is allowed.** If they decline, the
   function stays switched off and every leg keeps the dashed estimate (R2); the app is unaffected.
2. **No visitor position is sent to openrouteservice** (R3).
3. Show the required credit "© openrouteservice by HeiGIT | Data © OpenStreetMap contributors" (R7).

**Alternatives considered**: TMAP or Kakao walking APIs (paid beyond free quotas, ruled out by the owner);
public OSRM or FOSSGIS servers (1 request per second, "no heavy usage", not for production); Google (no walking
directions in Korea).

## R2. Fallback: the dashed estimate (owner decision 2026-10-15)

**Decision (owner, 2026-10-15)**: Only free options. If openrouteservice cannot be used, the function stays
switched off (`WALKING_ROUTE_ENABLED` unset) and every leg keeps today's dashed on-device estimate. Self-hosting a
router is ruled out because it needs a paid server; TMAP is ruled out because its free tier forbids using stored
results after 24 hours, which breaks "compute once and save".

**Kept from the earlier plan**: an OSRM adapter (`GET /route/v1/foot/{lng,lat;…}?overview=full&geometries=polyline&steps=false`,
per-leg `legs[i].distance`) behind the same interface, with contract tests on recorded responses. It costs nothing
while unused and lets a free OSRM service be used later if one with suitable terms appears.

**Findings**: Geofabrik `south-korea-latest.osm.pbf` is 289 MB. By linear scaling, a foot profile needs about 2–4 GB
of RAM to build and 1–2 GB to serve, so a 4–8 GB VM is enough (estimated, not measured). OSRM is BSD-2-Clause and
released monthly. Its `foot.lua` is close to shortest-path at 5 km/h. Valhalla (MIT) has sidewalk-aware pedestrian
costing; GraphHopper is Apache-2.0. Self-hosting removes the storage, share-alike and personal-data questions,
leaving only ODbL attribution, but costs a small server and monthly data refreshes, so the owner ruled it out on
2026-10-15.

## R3. The leg from the visitor's position stays an on-device estimate

**Decision (owner, 2026-10-14)**: Only venue-to-venue legs are
routed. The leg from the visitor's current location to the first stop keeps today's on-device estimate and is drawn
dashed. The visitor's position never leaves the device.

**Rationale**:
- The openrouteservice ToS forbid personal data.
- The privacy notice (`web/privacy.html`) says the device processes location only to centre the map and show nearby
  exhibitions. Routing the first leg would need a notice change and a review of the App Store and Play disclosures.
- A walk from where the visitor stands is short and the least important leg. Venue-to-venue legs are what make a
  route trustworthy, and they are what readers of shared and public routes see.

**Spec impact**: US4 and FR-001/FR-004 become "the visitor's position is never sent". The starting leg stays dashed
with estimate wording. The contract keeps an optional position-only first waypoint so the decision can be reversed
if a free router whose terms allow it is ever used (R2).

**Alternative**: Route the first leg through a self-hosted router only. This needs a paid server, which the owner
ruled out (R2), and the privacy notice update.

## R4. Walking minutes are the app's own pace over the path distance

**Decision**: minutes = ceil(path distance / 4.5 km/h). The provider's duration is not used.

**Rationale**: openrouteservice and OSRM both assume 5 km/h. Using our 4.5 km/h, which visitors already see today,
keeps times stable when the provider changes and suits an unhurried gallery walk. Only the distance becomes real.

## R5. Fitting asynchronous routes into synchronous route timing

**Decision**: Keep `RouteLegEstimator` synchronous. Add a `WalkingLegBook` that is filled asynchronously and a
`RoutedLegEstimator` that reads it (data-model.md). Planner, composer, preview and share card keep their current
structure. They re-evaluate when legs arrive.

**Rationale**: The planner calls the estimator hundreds of times when it compares orderings, so it must stay
synchronous and offline. The route is retimed after selection (`NeighborhoodRoutePlanner.retime`), so walking paths
never change which stops are chosen (FR-006).

**Alternative rejected**: Making the estimator `suspend` and routing during selection. That would mean network calls
inside the permutation search and a provider bill per candidate.

## R6. Full-screen map as a screen-local overlay

**Decision**: Each of the three route screens wraps its content in a `Box` and shows `RouteMapFullScreen` on top when
expanded. It has a back handler, close and fit-route controls, and `MapInteractions` with rotation off
(`camera { rotate { … } }`), plus `maxPitch = 0`. It is not a navigation destination and not a `Dialog`.

**Rationale**: Navigation is state-driven with no back stack, so a destination would recompose the screen beneath
and lose its state (FR-015). A platform `Dialog` puts the MapLibre surface in a second window, which is a known
source of surface issues on Android. Route screens are already full-screen destinations outside the tab bar, so an
overlay covers everything.

**Library check**: maplibre-compose 0.20.0 `LineLayer` has `dasharray`, for the dashed estimated legs.
`MapInteractions.Builder.camera { pan / zoom / rotate / pitch }` controls gestures. The map version is unchanged, but
the route map changes, so the CLAUDE.md map gate applies: run the uncached iOS link and open a route map on both
platforms.

## R7. Credits on both maps

**Decision**: The full-screen map shows "© OpenFreeMap · © OpenStreetMap · © openrouteservice by HeiGIT" bottom-left,
styled like the Map tab's credit, naming each routing service whose path is on the map: every routed leg carries its
`provider` (also when served from the cache, which stores it), so a route drawn by OSRM reads "Routing © OSRM" and a
mixed route names both. The credit follows the paths shown, never the configured provider. Tapping it opens
openstreetmap.org/copyright.
The small panel gains a compact "© OpenStreetMap" credit at its bottom-left edge.

**Rationale**: ODbL requires attribution "on or next to the map" without interaction
([OSMF guidelines](https://osmfoundation.org/wiki/Licence/Attribution_Guidelines)). The panel shows OpenFreeMap/OSM
tiles today with no credit, an existing gap this feature closes. Showing route results requires the HeiGIT credit
under their ToS.

## R8. Cache, quotas and abuse

**Decision**: Undirected, position-keyed venue-to-venue cache, written only when both ends match the current
catalogue coordinates. Postgres-backed provider budget: 30 calls a minute and 1,500 a day, under the free 40 and
2,000. A per-source hourly quota keyed by an HMAC of the ingress address, as `mobile-analytics` does. Origin
allow-list `app://gallr` and a kill switch, as `promoted-nearby` does.

**Rationale**: Catalogue verification stops a caller from planting arbitrary coordinates in the shared cache.
Hashing follows the reviewed mobile-analytics privacy posture. Expected volume (hundreds to low thousands of new
pairs a month) sits far under the budget once the cache warms (SC-003).

## R9. Migration numbering and order

**Decision**: Migration `20261014130000_walking_route_cache.sql` and pgTAP `067_walking_route_cache.test.sql`,
leaving `20261014120000` and `066` to the open PR for spec 092. Both are additive. The database ships before the
function, and the function before the app. Function secrets (`ORS_API_KEY`, `WALKING_ROUTE_HASH_SECRET`) are set
through the existing runbook, never committed, and deploying them is a separately authorized operation.
