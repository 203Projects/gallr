# Research: Home tab visual system v2 (Option A)

Decisions for [plan.md](plan.md). Each entry states the decision, why, and what was considered.

## R1. Blur: Haze for both the caption bands and the tab bar

**Decision**: add `dev.chrisbanes.haze:haze` to `composeApp/commonMain`. The Home `LazyColumn`
content is a `hazeSource`; the navigation bar on the Home tab applies `hazeEffect` with the theme
`background` at the token alpha as its tint; each cover-bearing card marks its cover as a source
and draws its caption on a child with a progressive (vertical) haze mask plus the poster tint.
Haze reports whether blur is available; where it is not, the card draws the existing
`grainWashBrush` fade and the bar stays the solid `Surface` it is today.
**Rationale**: one library, one API on both platforms, progressive masks and tints built in, and a
documented blur-availability switch, so the fallback is a boolean rather than a second rendering
path. It runs on Android 12+ (RenderEffect) and on iOS through Skia, which is exactly the matrix
the grain wash already uses.
**Alternatives considered**: `Modifier.blur` on a second copy of the cover under a gradient mask
(works for bands, doubles image draws, cannot blur the backdrop under the bar); a custom AGSL/SkSL
Gaussian (two passes, large code, no backdrop access); a solid bar everywhere (loses the Option A
look the owner chose).

## R2. Tint: dominant colour from the loaded cover, downsampled, cached

**Decision**: when Coil reports a successful cover load, read the result as an `ImageBitmap`,
downsample to 32×32, read RGBA pixels with the common `readPixels`, run
`PosterPalette.fromRgba` and keep the palette's dominant tone as the tint, mixed into the card and
band at `tintAlpha`. A `PosterTintCache` keyed by cover URL lives for the ViewModel's lifetime so a
card recomposed later needs no second extraction. The tint is applied only to the band and the
card surface, never to text, icons, the heart or the status label.
**Rationale**: reuses the share card's extractor and its scan-safe tone rules; 1,024 pixels keep the
work under a millisecond; the card is correct before the tint arrives, so first paint never waits.
**Alternatives considered**: a server-side colour column (out of scope: no new catalogue fields);
per-frame sampling in a shader (cost on every frame for a static value).

## R3. Geometry: one radius token, clipped covers, borders inside the clip

**Decision**: `HomeVisualTokens.cornerRadius = 16.dp`; every Home card, tile and the counter tile
use `RoundedCornerShape(cornerRadius)` for clip, border and press indication; covers are clipped by
the same shape so the band's edge follows the corner. The 1dp `outline` border stays. Nothing
outside the `ui/tabs/home` package and the bar's Home mode reads the token.
**Rationale**: a single value keeps the owner's decision scoped and reversible; borders inside the
clip avoid the hairline artefacts a border drawn outside a rounded clip produces.
**Alternatives considered**: a theme-wide shape change (rejected by the 2026-10-10 decision for other
tabs, which stands); different radii per card size (the prototype uses one).

## R4. Rail dots: a pure function over the list state

**Decision**: `highlightedCard(firstVisibleIndex, firstVisibleOffsetPx, viewportStartPx, itemWidthPx,
spacingPx, count)` returns the index of the first card whose leading edge is inside the viewport by
at least half the spacing; `RailDots(count, highlighted)` draws `count` 6dp dots (4dp apart) under the
rail, the highlighted one `onBackground`, the rest `outlineVariant`, no animation. Rails with more
than ten cards show no dots. The dot row has no semantics of its own: the card's merged node already
announces `N / M`.
**Rationale**: the arithmetic is testable without Compose; dots are a hint, not a control.
**Alternatives considered**: `HorizontalPager` for rails (changes scrolling feel and peeking);
animated dot morphs (motion the system does not sanction).

## R5. Routes rail: the public ranking's first three, read with the feed

**Decision**: `HomeFeed.routes: List<PublicRouteSummary>` is filled by `HomeViewModel` from
`PersonalRouteRepository.listPublic(limit = 3)` once per ViewModel, combined with the other inputs
off the main thread like the curator notes; `routesRail(summaries, limit = 3)` in `shared` keeps the
ranking's order, drops nothing it returns (the ranking already excludes unlisted routes) and
truncates to the limit. A failed read logs `home_routes_load_failed` and yields an empty list, so
the section hides. Each card shows the name, `authorDisplayName` (or the existing unknown-author
copy), `N곳 · 첫 구 → 마지막 구`, and a stop-count sized monochrome placeholder in place of a cover,
since routes have no image.
**Rationale**: the ranking, its visibility rules and the preview exist (spec 089); Home only adds a
third place that shows them. The read joins the feed so the page settles in one load.
**Alternatives considered**: reading from `PublicRoutesViewModel`'s list (that ViewModel is scoped to
the Map route sheet and holds expand/preview state Home does not need); a new RPC with route covers
(out of scope).

## R6. Fallback matrix

| Platform | Blur | Bands | Tab bar | Corners, tint |
|---|---|---|---|---|
| Android 12+ | Haze on | progressive blur + darkening + tint | backdrop blur | yes |
| Android 11 and earlier | Haze off | existing gradient wash + tint | solid `background` | yes |
| iOS (Skia) | Haze on | progressive blur + darkening + tint | backdrop blur | yes |
| Any, shader or effect failure at runtime | treated as off for the session | gradient wash | solid | yes |

`HomeBlurMode.decide(blurAvailable, reduceMotion)` is the single decision point and is unit tested;
reduced motion does not disable blur (blur is static) but disables every animated change.

## R7. Contrast floor

**Decision**: the band darkens from 0 at its start (45% of the cover height, moving with the caption
as the hero wash does today) to the theme's wash colour at alpha 0.70 at the caption's top edge and
holds it to the bottom; the text colour is `onBackground`. With the wash colour equal to the theme
`background`, 0.70 alpha over the worst cover (white in the dark theme, black in the light theme)
yields 5.3:1 and 10:1 for `onBackground` text; 0.55 was tried first and fails the dark-theme white
cover at 4.1:1, which `HomeVisualTokensTest` now guards. The value is a token so it can rise if a
fixture cover disproves it on screen.
**Rationale**: a floor, not a scale, so pale covers under the light theme never produce white on
pale; the number is checked once by screenshot sampling (quickstart) and guarded as a token test.
**Alternatives considered**: per-cover adaptive darkening (variable look, more sampling).

## R8. Motion

**Decision**: no new motion. Blur and tint are static; dots switch instantly; the press state of a
card deepens the band's darkening as the hero's wash does today (instant under reduced motion).

## R9. Analytics

**Decision**: a route card tap calls the same preview-open path the Map route sheet uses, so the
existing public-route preview event fires with its existing dimensions; the rail records no
impressions (impression events are typed for exhibitions). No new event or dimension.

## R10. DESIGN.md

**Decision**: a "Home visual system v2 (Option A, 2026-10-11)" subsection under the Home tab section
lists the tokens with values (corner radius 16dp; band start 0.45, blur radius 24dp, darkening floor
0.70; tint alpha 0.10; tab bar blur 20dp with `background` at 0.72, solid fallback), states the Home
only scope and the fallback matrix, and the Decisions Log gains a 2026-10-11 row superseding the
rounded-corner and translucent-surface rejection of 2026-10-10 for the Home tab only.

## Revision 2 (2026-10-12)

- **R1, R2, R6 (blur, Haze, fallback) are withdrawn.** Decision: a black scrim at 0.55 alpha held under the caption. Rationale: the owner did not need glass; a scrim has one code path on every device, a contrast floor that can be tested without a device (white on a scrimmed white cover is 4.74:1), and no dependency. Haze 2.x also requires Compose 1.12, which the map library cannot run on. Alternative considered: keeping blur behind a flag; rejected as two visual systems to maintain.
- **R3, R8 (poster tint) are withdrawn.** At 0.10 alpha it was not visible in device screenshots and it cost an image sampling pass per cover.
- **R11: most viewed.** Decision: a `security definer` function over `content.mobile_analytics_daily` returning ids only, ordered by `exhibition_opened` counts over seven Seoul days, with a floor of three opens. Rationale: the aggregates already exist and carry no visitor identity; returning an ordering without counts keeps traffic figures private. Alternatives: a materialised ranking table refreshed by cron (more moving parts for a list of twelve ids), or counting impressions too (measures what Home already shows, a feedback loop).
- **R12: route card covers.** Decision: read each shown route's stops with the existing `get_listed_route` (three stable reads) and resolve covers from the catalogue the app already holds. Alternative: extend `list_public_routes` to return stop ids; rejected because the 089 migrations are under review in another pull request.
- **R13: iOS Map crash.** Root cause and the hold are recorded in spec.md and CLAUDE.md; the lasting fix is the map library migration (TODOS.md).
