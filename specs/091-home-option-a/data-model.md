# Data model: Home tab visual system v2 (Option A)

Decision identifiers (R1–R10) refer to [research.md](research.md). Nothing here is stored on the
server; every entity is in-memory app state or a design token.

## Design tokens (`composeApp/commonMain`, `ui/theme/HomeVisualTokens.kt`)

| Token | Value | Used by | DESIGN.md |
|---|---|---|---|
| `cornerRadius` | 16.dp | hero, curator's eye, rail cards, route cards, collection tiles, counter tile | Home visual system v2 |
| `bandStart` | 0.45f of the cover height, or the caption's top minus 30% of the cover when the caption is taller (as the hero wash today) | caption bands | same |
| `bandBlurRadius` | 24.dp | caption bands (Haze progressive mask, R1) | same |
| `bandDarkeningFloor` | 0.70f alpha of the theme `background` at the caption's top edge, held to the bottom | caption bands (R7) | same |
| `tintAlpha` | 0.10f | poster tint on card surface and band (R2) | same |
| `barBlurRadius` | 20.dp | tab bar on the Home tab (R1) | same |
| `barTintAlpha` | 0.72f of the theme `background` | tab bar on the Home tab | same |
| `barFallback` | solid theme `background` (today's bar) | tab bar where blur is unavailable (R6) | same |
| `dotSize` / `dotSpacing` | 6.dp / 4.dp | rail dots (R4) | same |
| `dotLimit` | 10 cards | rails that show dots (R4) | same |

Scope: read only by `ui/tabs/home/*` and by `GallrNavigationBar` when the Home tab is selected.
Every other shape in the app stays `RoundedCornerShape(0.dp)` / `RectangleShape`.

## `HomeBlurMode` (pure decision, `ui/graphics/HomeBlur.kt`)

| Input | Type | Source |
|---|---|---|
| `blurAvailable` | Boolean | Haze's availability for the platform and API level (Android 12+, iOS) |
| `effectFailed` | Boolean | set for the session after a shader or effect failure was logged |

| Output | Meaning |
|---|---|
| `BLUR` | progressive bands and backdrop bar |
| `WASH` | existing gradient wash on bands, solid bar |

`decide(blurAvailable, effectFailed) = if (blurAvailable && !effectFailed) BLUR else WASH`. Reduced
motion does not change the mode; it only removes animated changes (R8).

## `PosterTint` (`ui/graphics/PosterTint.kt`)

| Field | Type | Rules |
|---|---|---|
| `coverUrl` | String | cache key for the session |
| `state` | `Absent` / `Extracting` / `Tinted(color)` / `Failed` | `Absent` when the card has no cover; `Failed` after the extractor threw (logged once per URL) |
| `color` | `Color` | the palette's dominant tone (`PosterPalette.fromRgba` on a 32×32 downsample), applied at `tintAlpha` |

Transitions: `Absent → Extracting` when Coil reports success; `Extracting → Tinted | Failed`. A card
renders identically in `Absent`, `Extracting` and `Failed` (no tint), so the tint never gates paint.

## `HomeFeed.routes` (`shared/commonMain`, `home/HomeFeed.kt`)

| Field | Type | Rules |
|---|---|---|
| `routes` | `List<PublicRouteSummary>` | the ranking's first `limit` (3) rows in ranking order; empty when none are listed or the read failed; default `emptyList()` so existing constructors compile |

`routesRail(summaries: List<PublicRouteSummary>, limit: Int = 3): List<PublicRouteSummary>` keeps
order, drops nothing the ranking returned, truncates to `limit`; `limit <= 0` yields an empty list.

## Routes rail card (presentation, `ui/tabs/home/RoutesRail.kt`)

| Field | Source | Copy |
|---|---|---|
| title | `PublicRouteSummary.name` | as is, two lines |
| author line | `authorDisplayName` or the unknown-author copy; `isEditor` adds the existing editor mark | `hanshin` / `에디터 · hanshin` |
| meta line | `stopCount`, `firstDistrictKo/En`, `lastDistrictKo/En` | KO `3곳 · 종로구 → 용산구`, EN `3 stops · Jongno → Yongsan` |
| placeholder | no image; `surfaceVariant` block carrying the stop count as `displaySmall` | monochrome, no accent |
| tap | `onRouteTap(summary, index)` → `PublicRoutesViewModel.openPreview(summary)` + `navigation.showPublicRoute()` (existing path) | |

Section copy: title `지금 주목받는 동선` / `ROUTES GETTING ATTENTION`; subtitle
`최근 30일에 가장 많이 담긴 동선` / `MOST COPIED IN THE LAST 30 DAYS`.

## Rail dots state (`ui/tabs/home/RailDots.kt`)

| Input | Type |
|---|---|
| `count` | Int, number of cards (dots shown only when `1 < count <= dotLimit`) |
| `highlighted` | Int, from `highlightedCard(firstVisibleIndex, firstVisibleOffsetPx, itemWidthPx, spacingPx, count)` |

`highlightedCard` returns `firstVisibleIndex` when the first visible card's hidden part is at most half
the spacing, otherwise `firstVisibleIndex + 1`, clamped to `count - 1`.

## Revision 2 (2026-10-12)

- `HomeRoute(summary: PublicRouteSummary, stopCoverUrls: List<String>)` replaces `PublicRouteSummary` in `HomeFeed.routes`; `shownCovers` is the first four, `hiddenStopCount` the stops beyond them.
- `HomeCollectionKind.MOST_VIEWED` (key `most-viewed`): the server's order, limited to the visible catalogue, at most ten, from three.
- `ScrimBand(fadeEdge, captionEdge)` describes a caption's scrim as fractions of the cover height.
- `PosterTint`, `PosterTintCache` and `HomeBlurMode` no longer exist.
- Database: no new table. `public.list_most_viewed_exhibition_ids(p_days, p_limit) returns setof text` reads `content.mobile_analytics_daily`.
- `HomeSituation(key, titleKo, titleEn, tagsKo, tagsEn, termIds)` and `HOME_SITUATIONS` (five, fixed order); `HomeCollectionKind.SITUATION` with `HomeCollection.tagsKo/tagsEn`.
- `VisitTag { FREE_ADMISSION("visit:free-admission"), FAMILY("visit:family") }` and `Exhibition.visitTags: Set<VisitTag>`, parsed from the `visit` entries of `art_terms`; `HomeCollectionKind.FREE_ADMISSION`; `HomeSituation.visitTags`.
- Database: `content.art_taxonomy_category` gains `visit`; `content.art_taxonomy_terms` gains `visit:free-admission` and `visit:family`. No new table or column.
