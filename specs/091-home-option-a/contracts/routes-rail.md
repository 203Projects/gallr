# Contract: 지금 주목받는 동선 rail (revision 2, 2026-10-12)

## Shared (`shared/commonMain`, `home/RoutesRail.kt`, `home/HomeFeed.kt`)

```kotlin
const val HOME_ROUTES_LIMIT = 3
const val HOME_ROUTE_COVER_LIMIT = 4

data class HomeRoute(val summary: PublicRouteSummary, val stopCoverUrls: List<String> = emptyList()) {
    val shownCovers: List<String>      // the first HOME_ROUTE_COVER_LIMIT covers
    val hiddenStopCount: Int           // summary.stopCount minus shown covers; 0 when no cover shows
}

fun routesRail(summaries: List<PublicRouteSummary>, limit: Int = HOME_ROUTES_LIMIT): List<PublicRouteSummary>
fun homeRoutes(
    summaries: List<PublicRouteSummary>,
    stopExhibitionIds: Map<String, List<String>>,
    exhibitions: List<Exhibition>,
    limit: Int = HOME_ROUTES_LIMIT,
): List<HomeRoute>
```

`HomeFeed.routes: List<HomeRoute>`. `buildHomeFeed(…, routes, routeStops)` keeps the ranking's order,
truncates to three, and resolves each route's stop covers in stop order from the visible catalogue;
a stop that left the catalogue or has no cover is skipped, and a cover shows once.

## ViewModel (`composeApp/commonMain`, `viewmodel/HomeViewModel.kt`)

- Reads `listPublic(limit = HOME_ROUTES_LIMIT)` once per ViewModel; a failure logs
  `home_routes_load_failed` and hides the rail.
- For each shown route, reads `loadPublicStops(id)` in parallel (three reads at most, a stable
  function with no side effects). A failed or empty read logs `home_route_stops_load_failed` when it
  failed and leaves that card without covers; it never hides the card.

## UI (`ui/tabs/home/RoutesRail.kt`)

Compact 262dp cards (16dp padding, 16dp corners, 1dp `outlineVariant`): the maker line with the copy
count on the trailing edge when above zero, the name, `N곳 · 첫 구 → 마지막 구`, then up to four
40dp stop covers and `+N`. One merged semantics node: section, position, name, maker, copies, stop
count, districts. `onRouteTap(summary)` opens the existing public route preview through
`PublicRoutesViewModel.openPreview` and `navigation.showPublicRoute`.

## Analytics

No new event. Opening the preview fires the existing public-route preview event.

## Dependencies and release order

`list_public_routes` and `get_listed_route` (spec 089) must be applied to the hosted project before
an app build with this rail ships; until then the read fails and the rail hides.
