# Data model: Public route browse screen (092)

No table changes. The feature reads existing rows: `public.personal_routes`, `public.personal_route_stops`,
`public.route_saves`, `public.profiles` and `public.exhibition_catalog_v2`. It adds two read functions
(see [contracts/browse-rpc.md](contracts/browse-rpc.md)).

## Shared domain (`shared/src/commonMain/kotlin/com/gallr/shared/route/`)

### `PublicRouteSort` (new enum)

| Value | Wire | Order |
|-------|------|-------|
| `POPULAR` | `popular` | 30-day copies of the approved version desc, approval time desc, id asc |
| `NEWEST` | `newest` | approval time desc, id asc |

The default is `POPULAR`.

### `PublicRouteDistrict` (new)

| Field | Type | Rule |
|-------|------|------|
| `nameKo` | String | Non-blank; the filter key |
| `nameEn` | String | Display only; falls back to `nameKo` when blank |
| `routeCount` | Int | ≥ 1 |

`localizedName(language)` follows the existing `localizedName()` pattern.

### `PublicRouteBrowseQuery` (new)

| Field | Type | Rule |
|-------|------|------|
| `sort` | `PublicRouteSort` | Default `POPULAR` |
| `districtKo` | String? | null = 전체 |

### `BrowsedPublicRoute` (new)

| Field | Type | Rule |
|-------|------|------|
| `summary` | `PublicRouteSummary` | Existing entity, unchanged |
| `coverUrls` | List<String> | 0–3, in stop order, non-blank, distinct |

### `PublicRoutePage` (new)

| Field | Type | Rule |
|-------|------|------|
| `routes` | List<`BrowsedPublicRoute`> | ≤ requested limit |
| `hasMore` | Boolean | True when at least one more row exists after this page |

### Pure rules (new, `PublicRouteBrowse.kt`)

- `appendPage(loaded: List<BrowsedPublicRoute>, page: PublicRoutePage): List<BrowsedPublicRoute>` keeps the
  loaded order and appends only rows whose `summary.id` is not already loaded (FR-008).
- `nextOffset(pagesLoaded: Int, pageSize: Int)`: the offset is the number of rows *requested* (pages × page size),
  not the number kept, so a dropped duplicate does not make the next page re-read the same window.
- `resolveDistrict(selected: String?, districts: List<PublicRouteDistrict>): String?` returns null when the
  selected district is no longer offered (edge case: the filter falls back to 전체).

## Repository (`PersonalRouteRepository`, extended)

```text
suspend fun browsePublic(query: PublicRouteBrowseQuery, offset: Int, limit: Int = PUBLIC_ROUTE_PAGE_SIZE): Result<PublicRoutePage>
suspend fun listPublicDistricts(): Result<List<PublicRouteDistrict>>
const val PUBLIC_ROUTE_PAGE_SIZE = 20
```

These follow the catalogue/route convention: `Result<T>` with `runCatching` at the repository boundary. One
malformed row is dropped, not fatal, as in `listPublic`.

## DTOs (`shared/.../data/network/dto/PersonalRouteDto.kt`)

- `PublicRoutePageDto(routes: List<JsonElement>, has_more: Boolean)`. Rows decode as
  `PublicRouteSummaryDto` plus `cover_urls: List<String> = emptyList()` into `BrowsedPublicRouteDto`. A row
  that fails to decode is skipped.
- `PublicRouteDistrictDto(district_ko, district_en, route_count)`. A blank `district_ko` row is skipped.

## App state (`composeApp/.../viewmodel/PublicRouteBrowseViewModel.kt`)

```text
PublicRouteBrowseUiState(
  query: PublicRouteBrowseQuery,
  districts: List<PublicRouteDistrict>,          // empty while loading or after a failed districts read
  list: BrowseListState,
  today: LocalDate,                              // Seoul; drives the "{M}월 {D}일부터" label
  scrollAnchor: ScrollAnchor,                    // index + offset restored after the preview (FR-011)
)

sealed BrowseListState
  Loading                                        // first page in flight → placeholder rows
  FirstPageError                                 // → error + 다시 시도
  Empty(filtered: Boolean)                       // filtered=false → composer CTA; true → clear-filter action
  Loaded(routes, hasMore, appending: Boolean, appendFailed: Boolean)
```

### Transitions

| From | Event | To |
|------|-------|----|
| any | `open()` from an entry point, or the query changed | `Loading`, then `Loaded` / `Empty` / `FirstPageError` |
| `Loaded(hasMore=true)` | `loadMore()` near the end | `Loaded(appending=true)`, then appended or `appendFailed=true` |
| `Loaded(appendFailed)` | `retryMore()` | `Loaded(appending=true)` |
| `FirstPageError` | `retry()` | `Loading` |
| any | `selectSort(s)` / `selectDistrict(d)` / `clearDistrict()` | `Loading` from offset 0; scroll anchor reset |
| `Loaded` | open a preview, then come back | unchanged; scroll anchor restored |

`loadMore()` is ignored while a page is already in flight or `hasMore` is false, so a fast fling can't
request the same offset twice. Reopening the screen from an entry point reloads from the first page and
keeps the query (spec assumption).
