# gallrmap.com Multi-Page Catalog — Design Spec

**Date:** 2026-05-07
**Status:** Draft, awaiting user review
**Scope:** `web/` — gallrmap.com presentation site
**Owner:** hanshin
**Predecessor specs (all merged today):**
- `2026-05-07-website-korean-forward-redesign-design.md` (PR #44)
- `2026-05-07-website-editorial-redesign-design.md` (PR #46)
- `2026-05-07-website-fluid-redesign-design.md` (PR #47)

**Inspiration source:** `~/Downloads/gallr_redesign/` — four desktop mockups (Home, Discover Exhibitions, Exhibition Details, Your Map) plus an `architectural_reductionism/DESIGN.md` token sheet.

---

## Problem

The just-shipped editorial + fluid redesign turned `gallrmap.com` into a confident single-page brochure. The four redesign mockups push further: they describe a **real exhibitions catalog** — with discovery filtering, per-exhibition detail pages, and a city map of every running exhibition — not a brochure.

A first-time visitor today can read about gallr but cannot browse a single specific exhibition without downloading the app. That is a large, unnecessary friction point: the data already lives in Supabase, the build pipeline already pulls from it, and the design system already has every component shape this catalog needs.

## Goal

Expand `gallrmap.com` from one page to five static routes, all built from Supabase at build time:

- `/` — home (existing editorial single page, lightly extended)
- `/exhibitions/` — discovery catalog with status + city filters
- `/exhibitions/[slug]/` — one static page per exhibition
- `/map/` — every current/upcoming exhibition on a Naver map
- `/about/` — about page (extracted from the home `about` section)

`/privacy/` and `/coming-soon/` stay where they are.

## Non-goals

- **No mobile app changes.** Web-only spec.
- **No new SSG.** Eleventy stays. No Astro migration.
- **No replacement of the just-merged editorial / fluid / Korean-forward work.** This spec builds on those tokens, components, and bilingual conventions.
- **No EN/KO toggle UI.** The Korean-primary + muted-English stacked pattern shipped in PR #44 is the bilingual model. We extend it to new pages, not replace it.
- **No web saves / bookmarks / accounts.** Web is discovery + conversion to mobile. Saves remain mobile-app-only.
- **No carousels, modals, or animation libraries.** Existing motion primitives only.
- **No localStorage state, no service worker, no PWA.** Out of scope.
- **No Korean web fonts.** System Korean fallback (Apple SD Gothic Neo / Malgun Gothic) is already in use; no payload increase here.
- **No `/ko/` URL prefix.** Single URL tree. Korean is the rendered default.
- **No multi-image gallery on detail pages.** Single hero image only — keeps the Apps Script pipeline change small (see "Data layer" below).

## Architecture

The existing build pipeline:

```
npm run build
├─ scripts/copy-fonts.js          (existing)
├─ scripts/fetch-showcase.js      (existing — currently fetches 12 random exhibitions)
└─ eleventy build
```

becomes:

```
npm run build
├─ scripts/copy-fonts.js          (existing, unchanged)
├─ scripts/fetch-exhibitions.js   (NEW — fetches the full exhibitions table)
└─ eleventy build
   ├─ /index.html                 (existing home, lightly extended)
   ├─ /exhibitions/index.html     (NEW — Discover, all rows, client-side filter)
   ├─ /exhibitions/[slug]/        (NEW — one HTML file per exhibition via 11ty pagination)
   ├─ /map/index.html             (NEW — Naver Maps + sidebar list)
   ├─ /about/index.html           (NEW — extracted About content)
   ├─ /privacy/index.html         (existing)
   └─ /coming-soon/index.html     (existing)
```

`scripts/fetch-showcase.js` is **kept for now** — the existing home `now-showing` section reads from `_data/showcase.json`. Long-term it can be folded into `_data/exhibitions.json`, but that's a follow-up, not part of this spec.

## Data layer

### Supabase schema additions

Strictly additive. **Implementation discovered substantial pre-existing state on 2026-05-07:** `description_ko`, `description_en`, and `is_featured` already existed (spec `012-bilingual-data-pipeline` and prior editorial work). The implementation adapts to existing names rather than introducing parallel columns.

Migration applied (only the genuinely-missing column):

```sql
ALTER TABLE exhibitions ADD COLUMN IF NOT EXISTS ticket_url text;
```

(An additional `featured` column was added then dropped on 2026-05-07 once `is_featured` was discovered. See decision log below.)

Final column shape relevant to this spec:

- **`description_ko` / `description_en`** (pre-existing, unchanged) — `text NOT NULL DEFAULT ''`. The detail page renders `description_ko` as primary; if `description_en` is non-empty it appears beneath in muted secondary type (Korean-forward bilingual pattern). **If both are empty strings, the "About this exhibition" block is omitted entirely.** Empty-string check, not null check.
- **`ticket_url`** (new) — nullable text. Tickets/RSVP link, venue-managed, language-irrelevant. The Tickets button only renders when the value is non-null and non-empty. **Interim state:** `gas/SyncExhibitions.gs` does not yet sync this column — `KNOWN_COLUMNS` would need `ticket_url` added. Until then, `ticket_url` is always null in production and the Tickets button never renders. Acceptable v1; gas/ extension is a follow-up when ticket data exists to populate.
- **`is_featured`** (pre-existing) — `boolean NOT NULL DEFAULT false`. Already populated via the editorial Sheet workflow (gas/ syncs it). Drives the home hero's "Featured Exhibition" overlay link. Exactly one row should be `true`. The build script logs a warning if zero or multiple are found and falls back to *most recently opened current exhibition* (zero) or *first match by `id` ascending* (multiple) — deterministic across builds, never breaks deploys on editorial mistakes.

**Decision log (2026-05-07):** original spec called for a new `featured` column. Discovered `is_featured` already existed and is already synced from the editorial Sheet. Dropped `featured` to avoid the editorial team managing two flags for the same concept. All downstream references use `is_featured`.

### RLS

The `exhibitions` table needs anon `SELECT` for the build to read it from CI without a service-role key. **Action item for the implementation plan:** confirm the policy exists; if not, add:

```sql
CREATE POLICY exhibitions_anon_read ON exhibitions
  FOR SELECT TO anon USING (true);
```

(Existing `fetch-showcase.js` already calls Supabase with the anon key in production, so the policy is presumably already in place — implementation must verify, not assume.)

### Apps Script (gas/)

Add four columns to the source Google Sheet: `description_ko`, `description_en`, `ticket_url`, `featured`. The existing sync upserts by column-name-keyed object, so new columns flow through without code changes — but the implementation plan must verify against the actual gas/ code, not assume.

### `scripts/fetch-exhibitions.js`

New build-time script. Fetches **all** rows (not just 12), enriches, and writes `web/_data/exhibitions.json`. Mirrors the existing `fetch-showcase.js` patterns:

- **Same env-var contract.** `SUPABASE_URL` + `SUPABASE_ANON_KEY`. Production builds fail loudly when missing (same guard `5eb0aea` introduced).
- **Seed fallback.** When env vars are absent (local dev), copies a curated `scripts/exhibitions-seed.json` so the site builds offline. The existing `refresh-seed.js` pattern is reused — one anchor entry per fixture exhibition.
- **Enrichment per row:**
  - `slug = slugify(name_en ?? name_ko) + "-" + id.slice(0, 4)`. The 4-char id suffix guarantees uniqueness without collision logic.
  - `status` ∈ `current | opening_soon | closing_soon | closed`, computed from `opening_date` / `closing_date` using the same date logic as the mobile app's spec 022. Centralized in `web/scripts/lib/status.js` with a unit test.
- **Ordering** by `opening_date DESC`. Stable across builds.

### Eleventy pagination → static detail pages

```js
// web/exhibitions/exhibition.11ty.js
exports.data = {
  layout: "base.html",
  pagination: { data: "exhibitions", size: 1, alias: "exhibition" },
  permalink: (data) => `/exhibitions/${data.exhibition.slug}/`,
};
```

One HTML file per exhibition, generated at build time. The discovery page (`/exhibitions/index.html`) renders all cards in one document and toggles them via client-side filter (no per-filter HTML files — the dataset is hundreds, not tens of thousands; client-side toggle is fine).

The map page (`/map/index.html`) injects exhibitions as a JSON island for the Naver-map client script:

```html
<script type="application/json" id="exhibitions-data">
  [{"id":"...","slug":"...","name_ko":"...","lat":37.5,"lng":127.0,"status":"current"}]
</script>
```

## Pages

### `/` — Home

Stays as it is (PR #46/#47 just shipped this). One **single, additive** change:

- The hero featured-exhibition image overlay (the small black bar that currently reads `FEATURED EXHIBITION ↗` over the right-column image) becomes a real link to the `is_featured = true` exhibition's detail page. If no row is featured, the overlay falls back to the most recently opened current exhibition. If `_data/exhibitions.json` is empty (seed-empty edge case), the link points to `/exhibitions/`.

The home page does **not** gain new sections in this spec. The existing hero, features, now-showing, downloads, about, footer all stay.

### `/exhibitions/` — Discover

Mockup ref: `gallr_discover_exhibitions/screen.png`.

**Layout** (≥ 768px): 12-col grid. Sticky filter sidebar (2 cols) + card grid (10 cols, 2 columns of cards). Below 768px the sidebar collapses into a `FILTERS ⌄` bar that opens a full-screen sheet (the `Sheet` component from the inventory below).

**Filter sidebar:**

- `필터` / `FILTERS` eyebrow (label-bold uppercase, Korean-forward stacked).
- **Status group:** vertical link list — `전체 / 진행 중 / 오픈 예정 / 종료 임박 / 종료됨` (`ALL / CURRENT / OPENING SOON / CLOSING SOON / CLOSED`). Active item is the inverted black-fill block (white text). Status reuses the same accent rule as `now-showing`: `종료 임박` and `오픈 임박` get the orange accent badge, others stay monochrome.
- **City group:** vertical link list of distinct cities derived at build time from `SELECT DISTINCT city`. Inverted-active treatment.

**Filter mechanics:**

- Filters live in the URL as query params (`?status=current&city=seoul`). On page load a small client script reads the URL and toggles card `display`. Filter clicks update the URL via `history.replaceState` and re-toggle.
- No per-filter HTML files. One `/exhibitions/index.html` for all combinations.

**Card grid:**

- `ExhibitionCard` component, default variant. Bigger spec card variant for the *first* card if its row has `is_featured = true`.
- Empty state when zero matches: centered Korean-forward "조건에 맞는 전시가 없습니다." / muted "No exhibitions match your filters." with a `필터 초기화` / `RESET FILTERS` text button.

**No pagination.** Hundreds of rows; client-side toggle is fast enough. If the dataset crosses ~2000 rows in the future, revisit.

### `/exhibitions/[slug]/` — Detail

Mockup ref: `gallr_exhibition_details/screen.png`.

**Layout** (≥ 768px): 12-col. Hero image (7 cols) + info panel (5 cols). The mockup shows two thumbnail images below the hero — **dropped** to keep the schema single-image. The space becomes whitespace.

**Info panel** (top to bottom):

1. Display-lg title — `name_ko` primary, `name_en` muted beneath (Korean-forward stacked).
2. Body-lg artist name (single field — most exhibitions don't have a separate `artist` column; reuse what's present, fall back to omitting the line if absent).
3. 24px stack.
4. Two-up metadata grid: `갤러리` / `GALLERY` eyebrow + venue name (Korean-forward stacked) ⋅ `일정` / `DATES` eyebrow + date range.
5. Two-up metadata grid: `위치` / `LOCATION` eyebrow + address ⋅ city label.
6. 24px stack.
7. `전시 소개` / `ABOUT THE EXHIBITION` eyebrow + body prose, Korean-forward stacked (`description_ko` primary, `description_en` muted beneath when non-empty). Block omitted entirely if both descriptions are empty strings.
8. 24px stack.
9. **Action stack:**
   - Primary: orange-fill `앱에서 보기` / `Get the App` (full width). Reserves the only orange CTA on the page.
   - Secondary row: outlined `공유` / `Share` (Web Share API + clipboard fallback) ⋅ black-fill `티켓 →` / `Tickets →` (only renders when `ticket_url` is present, opens in new tab).
   - Tertiary: text-only `길찾기 →` / `Get Directions →` — deep-links to the Naver Map URL scheme on Korean mobile UAs (`nmap://route/...`), falls back to `https://map.naver.com/?...` on web.

**Mobile (< 768px):** stacks vertically. Action stack becomes a fixed-bottom sticky bar (`StickyMobileCta`) so the orange CTA is always visible without scrolling.

### `/map/` — All exhibitions on a map

Mockup ref: `gallr_your_map/screen.png`. Per the brainstorming Q4: this is *all current + upcoming* exhibitions, not a personal saved list.

**Layout** (≥ 768px): two columns at fixed total height = `100vh - top-nav - footer`. Sidebar (320px fixed) + map (flex). Both scroll independently.

**Sidebar list:**

- Eyebrow `전시 / EXHIBITIONS · {count}`.
- Vertical list, one row per exhibition: status chip + title (titleMedium) + venue (label-sm uppercase). The mockup's bookmark icon is omitted — there are no saves on web, and a non-functional icon teaches the wrong thing.
- Active row = inverted black block; clicking pans the map and highlights the corresponding pin.
- Hovering / clicking a map pin activates the corresponding sidebar row and scrolls it into view.

**What's plotted:** every exhibition with `status` ∈ `{current, opening_soon, closing_soon}`. Closed exhibitions are excluded.

**Map:**

- Naver Maps JS SDK v3, loaded via `<script>` with the public client ID. The ID is necessarily public on a static site; security comes from Naver console's referrer allowlist.
- Custom HTML markers (Naver supports HTML overlays). Default pin: 12×12px black square. Active pin: orange `#FF5400` square with a black-fill label-bold tooltip above showing the exhibition title — matches the mockup.
- Initial bounds: `fitBounds` to all visible pins.
- Map container has `filter: grayscale(1) contrast(1.05)` to push the tile rendering toward monochrome (Naver doesn't expose full custom raster styles).

**Mobile (< 768px):** map fills the viewport. Sidebar becomes a draggable bottom sheet (50% rest height, drag up to expand). Same active-state syncing.

**Filters on `/map`:** none in v1. The map's job is geographic discovery, not catalog filtering.

### `/about/` — About

Extract the existing `about` include into its own page. Same content, same Korean-forward bilingual treatment, same tokens. The home page's existing About section stays in place; this is a duplicate destination so the top-nav `ABOUT` link goes to a real URL instead of an anchor.

(Optional follow-up not part of this spec: replace the home About section with a `→ 더 알아보기 / Learn more` link to `/about/`. Defer until it's clear the duplication actually bothers anyone.)

## Component inventory

**Reuse from the just-merged work:**
- `base.html`, the editorial type/spacing tokens, the Korean-forward bilingual stacked text pattern, `now-showing` card style, button variants, footer, top nav.

**New (used across two or more new pages):**
- `ExhibitionCard` — card primitive used by Discover (default + featured variants), eventually Now Showing (refactor follow-up).
- `StatusChip` — `진행 중 / 오픈 예정 / 종료 임박 / 종료됨` plus EN equivalents. Outlined default, inverted active.
- `MetaPair` — uppercase eyebrow + value, Korean-forward. Heavy use on detail page.
- `FilterList` — used for both Status and City filter groups on Discover.
- `Sheet` — full-screen mobile overlay used by mobile filter drawer.
- `StickyMobileCta` — fixed-bottom orange "앱에서 보기" bar on detail page below 768px.

**Page-specific (not extracted as components):**
- `MapView` initialization lives in `web/scripts/map.js`. Loaded only on `/map/`.
- Hero featured-exhibition link is an inline change to `_includes/hero.html`, not a new component.

### File layout

```
web/
  _includes/
    base.html                  (existing, unchanged structurally)
    hero.html                  (existing, +featured-exhibition link)
    features.html              (existing, unchanged)
    now-showing.html           (existing, unchanged for v1)
    downloads.html             (existing, unchanged)
    about.html                 (existing — also referenced by /about/)
    components/                (NEW)
      exhibition-card.html
      status-chip.html
      meta-pair.html
      filter-list.html
      sheet.html
      sticky-mobile-cta.html
  exhibitions/
    index.html                 (NEW — Discover)
    exhibition.11ty.js         (NEW — pagination → /exhibitions/[slug]/)
  map/
    index.html                 (NEW)
  about/
    index.html                 (NEW — wraps existing about.html include)
  styles/
    tokens.css                 (existing, unchanged)
    main.css                   (existing, +new component styles appended)
  scripts/
    fetch-exhibitions.js       (NEW)
    refresh-exhibitions-seed.js (NEW — mirrors refresh-seed.js)
    exhibitions-seed.json      (NEW — fallback fixture)
    seed-anchors.json          (existing)
    lib/
      status.js                (NEW — date → status, shared by build + tests)
      slug.js                  (NEW — slugify + id-suffix)
  client/                      (NEW — runtime JS, copied to /js/ at build)
    filter.js                  (Discover client-side filter)
    map.js                     (Naver SDK + pin/list sync)
    share.js                   (Web Share API + clipboard fallback)
```

CSS for new components is appended to `main.css` (the existing convention) rather than split into per-component files. Splitting is a follow-up if `main.css` becomes unwieldy.

## Bilingual content (extending the Korean-forward pattern)

The pattern shipped in PR #44 is unchanged:

- HTML root stays `<html lang="ko">`.
- Korean copy renders as the dominant reading layer.
- English copy follows in `<span class="bi-en" lang="en">` (or block-level `<p class="bi-en" lang="en">` for prose), styled in muted secondary type.
- Per-exhibition content is rendered as both `_ko` (primary) and `_en` (muted, only when the field is non-null).
- Hero `<h1>` stays Korean-only — the lone exception per the existing pattern.

**No EN/KO toggle.** Brainstorming Q6 settled on a toggle, but that conflicts with the just-shipped Korean-forward pattern. Confirmed in the post-discovery checkpoint to keep what shipped.

## Testing

Existing test scaffolding (`web/tests/*`, Playwright + pa11y) extends naturally:

**Unit (Node):**
- `tests/status.test.js` — pin date-boundary logic for the four status values against fixed dates.
- `tests/slug.test.js` — `slug.js` produces stable, URL-safe, collision-free slugs for the seed fixtures.
- `tests/exhibitions-seed.test.js` — `exhibitions-seed.json` validates against the schema the build expects (mirrors `tests/refresh-seed.test.js`).

**Build integration:**
- `tests/build.test.js` extension — assert that for a fixture set of N exhibitions, `dist/exhibitions/[slug]/index.html` exists for each, `dist/exhibitions/index.html` and `dist/map/index.html` exist, and no internal link 404s within `dist/`.

**Visual regression (Playwright):**
- One screenshot per page per breakpoint (375 / 768 / 1280 / 1920) — five pages × four breakpoints. Reuses the four-viewport pattern from `ad70a78`.
- One additional screenshot per page after toggling a representative filter on Discover.

**Accessibility (pa11y):**
- Every new route added to the existing pa11y run. Hard-fail on AA violations.

**Naver Maps under test:**
- Playwright `route("**/maps.js", ...)` returns a stub exposing `window.naver.maps` with no-op constructors. Map page tests assert sidebar list rendered + click-handlers wired, not actual tile rendering.

**Test data discipline:**
- Build tests read fixtures from `tests/fixtures/exhibitions.json`, never from live Supabase. Avoids the trap that bit grapplay's `vi.importActual` (saved memory).

## Acceptance criteria

The spec is "done" when:

1. All five new+existing routes (`/`, `/exhibitions/`, `/exhibitions/[slug]/`, `/map/`, `/about/`) render statically with no JS errors in console at desktop and mobile.
2. Korean-forward bilingual pattern is consistent across all new pages (matches the convention shipped in PR #44).
3. Discover filters (status × city) update card visibility without page reload; URL reflects active filters.
4. A detail page renders all required fields and gracefully omits the description block when both `_ko` and `_en` are empty strings, and the Tickets button when `ticket_url` is null or empty.
5. Map page shows pins for every exhibition with `status` ∈ `{current, opening_soon, closing_soon}` (i.e., excludes `closed`); pin ↔ sidebar bidirectional sync works.
6. Lighthouse mobile: Performance ≥ 90, Accessibility ≥ 95, SEO ≥ 95 on `/`, `/exhibitions/`, a representative `/exhibitions/[slug]/`, and `/about/`. The `/map/` page is allowed Performance ≥ 80 because of the Naver SDK.
7. `npm test` passes in CI (build + unit + Playwright + pa11y).
8. Mobile app builds and runs unchanged (sanity check — no shared code crosses the boundary).
9. The existing home page renders identically to today, save for the featured-exhibition overlay now linking to a real detail URL.

## Risks

- **Naver SDK referrer allowlist** — `gallrmap.com` (and any preview domains used for staging) must be added in the Naver console *before* deploy or the map breaks silently for visitors. Implementation plan must include this as an explicit pre-deploy step.
- **Supabase RLS** — anon `SELECT` on `exhibitions` is assumed to exist. Implementation plan must verify, not assume.
- **`is_featured` flag editorial discipline** — exactly one row should be flagged for the home hero. (`is_featured` is also used elsewhere in the editorial workflow; the home hero just consumes whichever single row is currently flagged.) Build script logs (does not fail) on zero or multi-flagged states. Watch the build log after each Sheet update.
- **Description schema growth** — adding `description_ko / _en` to every exhibition is editorial work, not engineering. Until the data is populated for most rows, detail pages will frequently render without an "About" section. Acceptable; the pattern degrades gracefully.
- **Naver mobile deep-link availability** — `nmap://` works only when the Naver Map app is installed. The web fallback (`https://map.naver.com/?...`) covers everyone else. No fingerprinting; emit both via a single attempt + fallback link.

## Sequencing (informs the implementation plan)

1. **Foundation** — `lib/status.js` + `lib/slug.js` with unit tests. New components scaffolded with stub markup.
2. **Data layer** — Supabase migration + RLS verification + Apps Script columns + `fetch-exhibitions.js` + seed + seed test.
3. **Discover + Detail** — these share the most components; build them together.
4. **Map** — Naver SDK integration, sidebar sync, mobile bottom-sheet.
5. **About + featured-link wiring on home** — small, low-risk.
6. **Test suite expansion** — add new routes to Playwright + pa11y; add fixture-based build test.
7. **Manual QA + deploy** — Naver allowlist, Vercel env vars, single deploy, no canary (no user state to migrate).

Rollback: `vercel rollback` to the prior deployment. The Supabase columns are additive and stay; the mobile app is unaffected.
