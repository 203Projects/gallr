# gallr Website — Editorial Redesign

**Date:** 2026-05-07
**Status:** Approved (sections 1–5)
**Predecessor spec:** `2026-05-07-website-korean-forward-redesign-design.md` (Korean-forward bilingual pattern landed in PR #44)

## Problem

The current marketing site at `gallrmap.com` (Eleventy static site under `web/`) faithfully obeys the brand's *Brutally Minimal / Reductionist Monochrome* spec — but the result reads as **empty**, not **confident**. Type is small (hero caps at `4.5rem`), section headings are eyebrow-sized labels, sections lack architectural separation, and there is no motion. The page does not look like the gallery wall the brand promises; it looks like a default template.

This redesign keeps the brand's hard rules — 0px radius, single accent (`#FF5400`), no shadows, no gradients, monochrome base — and uses **typography, structure, real exhibition imagery, and restrained motion** to give the site presence and weight.

## Non-goals

- Mobile app changes (this is a web-only spec).
- New brand vocabulary (no new fonts, no new accent colors, no rounded corners).
- Backend / API changes (build-time read-only fetch only; no new tables, no schema changes).
- Replacing Eleventy. Same SSG, same build pipeline, same hosting target (Vercel).
- Internationalization beyond the existing Korean-primary / muted-English pattern landed in PR #44.

## Aesthetic North Star

**"Editorial gallery publication, not marketing landing page."** References:

- Dia Art Foundation site (heavy black rules, oversized type, generous space).
- Apple's editorial product pages (kinetic type, slow scroll motion, restraint).
- Letterboxd (confident editorial type pairing, status badges as the only color).
- Massimo Vignelli's Unimark posters (eyebrow + index + thick rule + hierarchy).

The brand's existing rule — *"the accent is a signal, not a theme"* — is reinforced. Orange appears in **only two places** on the new page:

1. The kinetic word in the hero (`쉬운 / 빠른 / 정확한`).
2. Time-sensitive status badges on real exhibitions (`종료 임박` / `오픈 임박`).

Everything else is black, white, or `--color-ink-secondary` (`#525252`).

## Architecture

Static site, Eleventy 3.x, Nunjucks templates. Exhibition imagery is fetched from Supabase **at build time** by a Node script (`scripts/fetch-showcase.js`), written to `web/_data/showcase.json`, and consumed by Nunjucks during SSG. **No runtime JavaScript shipped except the motion primitives** (~150 lines vanilla JS, no framework).

```
┌───────────────────────────────────────────────────────────┐
│  npm run build                                            │
│  ├─ scripts/copy-fonts.js          (existing)             │
│  ├─ scripts/fetch-showcase.js      (NEW)                  │
│  │    ├─ if env SUPABASE_URL + ANON_KEY: fetch live data  │
│  │    └─ else: copy scripts/showcase-seed.json            │
│  │    → writes web/_data/showcase.json                    │
│  └─ eleventy build                                        │
│       └─ consumes showcase.json in hero/features/grid     │
└───────────────────────────────────────────────────────────┘
```

The site stays fully static; nothing runs in the browser to fetch exhibitions. Image URLs in the public Supabase `exhibition-images` bucket are CDN-cached.

## Data: Showcase Fetch

### Source

Supabase `exhibitions` table, public `exhibition-images` storage bucket. Read-only via the public anon key.

### Query

```
GET https://{SUPABASE_URL}/rest/v1/exhibitions
  ?select=id,title_ko,title_en,venue_ko,venue_en,opening_date,closing_date,cover_image_url
  &cover_image_url=not.is.null
  &opening_date=lte.{today}
  &closing_date=gte.{today}
  &limit=40
Headers:
  apikey: {SUPABASE_ANON_KEY}
  Authorization: Bearer {SUPABASE_ANON_KEY}
```

After fetching, randomly select 12 (Fisher–Yates with a seed = today's date so each daily build is stable; rebuilds within a day produce the same set).

### Output: `web/_data/showcase.json`

```json
{
  "fetchedAt": "2026-05-07T00:00:00Z",
  "source": "supabase" | "seed",
  "exhibitions": [
    {
      "id": "...",
      "titleKo": "...", "titleEn": "...",
      "venueKo": "...", "venueEn": "...",
      "openingDate": "2026-...", "closingDate": "2026-...",
      "coverImageUrl": "https://...supabase.co/storage/v1/object/public/exhibition-images/...",
      "status": "ongoing" | "closing-soon" | "opening-soon",
      "statusLabelKo": "종료 임박" | null
    },
    ...12 entries
  ]
}
```

`status` (evaluated in this order, first match wins):

1. `closing-soon` if `closing_date` is within 7 days of today (today ≤ closing_date ≤ today+7).
2. `opening-soon` if `opening_date` is within 7 days **after** today (today < opening_date ≤ today+7) — note: opening-soon exhibitions are NOT yet running, so they are excluded from the showcase fetch's `opening_date <= today` filter and will not appear at all unless the filter is loosened. **Decision:** the showcase fetch keeps the strict `opening_date <= today` filter (only currently-running exhibitions). Therefore in practice `opening-soon` never appears in the rendered set; the field exists for future use when we extend the fetch to include upcoming exhibitions. **Implementer: emit the field, but do not write template branches that assume `opening-soon` exhibitions exist.**
3. `ongoing` otherwise.

Precedence: `closing-soon` wins over `opening-soon` if both windows overlap (1-day exhibitions opening today and closing today are flagged `closing-soon`).

`statusLabelKo`:
- `closing-soon` → `"종료 임박"`
- `opening-soon` → `"오픈 임박"`
- `ongoing` → `null`

Only non-null labels render an orange accent badge.

### Fallback

If `SUPABASE_URL` or `SUPABASE_ANON_KEY` env vars are absent, OR if the fetch fails (network error, non-200 response), the script copies `scripts/showcase-seed.json` to `web/_data/showcase.json`. The seed is committed: 12 hand-picked currently-running exhibitions with real cover image URLs from the public bucket. The site always builds. CI logs warn when the fallback is used.

### Anon-key handling

- The **public anon key** (RLS-restricted, designed for client use) is configured at build time via `SUPABASE_ANON_KEY` env var on Vercel and on developer machines.
- The current `local.properties` value labeled `supabase.anon.key` is actually a **service_role JWT** (verified via JWT decode: `"role":"service_role"`). That is a separate security concern (out of scope for this PR) — flagged in the project_followups memory and the PR description. **This spec does not use that key.** A new anon key must be issued or located before Vercel is configured.
- The seed-fallback path means the redesign can ship locally and to preview without the anon key being configured.

## Build Output

Same Eleventy output as today (`web/dist/`). New artifacts:

- `web/_data/showcase.json` (gitignored — generated at build time)
- `web/_data/site.json` (NEW — committed; site-wide constants like `liveCountLabel: "1,200+"`)
- `web/scripts/main.js` (NEW — motion primitives, ~150 lines)
- `web/scripts/showcase-seed.json` (NEW — committed; build-time fallback dataset)
- Existing `web/styles/main.css` extended.

## Section 1 — Global System Upgrades

### New / updated tokens (`web/styles/tokens.css`)

```css
:root {
  /* Type scale (NEW — replaces flat scale) */
  --type-display:    clamp(3.5rem, 11vw, 9rem);   /* hero */
  --type-headline:   clamp(2rem, 5vw, 4rem);      /* section openers */
  --type-eyebrow:    0.6875rem;                   /* labels, indices */
  --type-eyebrow-tracking: 0.2em;
  --type-body-lg:    clamp(1.125rem, 1.6vw, 1.375rem);
  --type-body:       1rem;
  --type-meta:       0.75rem;

  /* Spacing (NEW additions) */
  --space-3xl: 160px;
  --space-4xl: 240px;

  /* Layout (UPDATED) */
  --max-width: 1280px;       /* was 960px */

  /* Borders (NEW use of existing token + one addition) */
  --rule-thick: var(--border-section);  /* 4px solid black, already defined */
  --rule-hairline: var(--border-hairline);

  /* Motion (NEW) */
  --ease-gallery: cubic-bezier(0.16, 1, 0.3, 1);
  --duration-fast: 200ms;
  --duration-med:  500ms;
  --duration-slow: 800ms;
}
```

### Motion primitives (`web/scripts/main.js`, NEW)

Vanilla JS, no dependencies. Single file, deferred load. Wrapped in `if (matchMedia('(prefers-reduced-motion: reduce)').matches) return;` at the top — when reduced motion is preferred, the script does nothing and elements stay in their final state via CSS.

- **`data-reveal`**: IntersectionObserver fades + 24px upward slide on entry. 600ms, `--ease-gallery`. `rootMargin: 0px 0px -10% 0px` so reveal triggers slightly before fully in view.
- **`data-reveal-stagger`**: same as `data-reveal`, but children stagger 80ms.
- **`data-marquee`**: clones content once, animates a single track from `translateX(0)` to `translateX(-50%)` over `data-duration` (default 40s) on a `requestAnimationFrame` loop. Pauses on hover via `data-paused` attribute.
- **`data-kinetic`**: cycles through child `<span>` elements with a 400ms vertical crossfade (current slides up + fades out, next slides up + fades in). Interval 2400ms. Pauses on hover.
- **`data-magnetic`**: on `mousemove` within a 60px radius, translates the element up to 6px toward the cursor, eased. Resets on `mouseleave`. Disabled on touch devices.

**Hidden-state contract:** CSS sets `opacity: 0; transform: translateY(24px)` on `[data-reveal]:not(.is-revealed)`. Three paths to the visible final state:

1. **JS enabled, motion allowed**: IntersectionObserver adds `.is-revealed`, CSS transitions opacity + transform.
2. **JS enabled, reduced-motion preferred**: `main.js` early-return path **first immediately adds `.is-revealed` to all `[data-reveal]` elements on the page** (a single `document.querySelectorAll(...).forEach(el => el.classList.add('is-revealed'))`), THEN returns. Elements appear instantly in final state, no transitions (because `@media (prefers-reduced-motion: reduce)` zeroes transition durations).
3. **JS disabled** (`<noscript>`): a `<noscript><style>[data-reveal] { opacity: 1 !important; transform: none !important; }</style></noscript>` block in `<head>` forces final state.

This contract guarantees no element ever stays invisible.

### Sticky header (`web/_includes/base.html` + `main.css`)

- `position: sticky; top: 0`. Background `transparent` initially.
- After 80px scroll: background becomes `--color-paper` with `--rule-hairline` bottom border. Wordmark stays. A small "다운로드" text link slides in from the right (200ms). 1px `--color-accent` progress bar at the very bottom of the header tracks `scrollY / scrollHeight`.
- Reduced motion: instant transitions.

### Files touched in Section 1

- `web/styles/tokens.css` — add new tokens
- `web/styles/main.css` — global type, header, motion CSS
- `web/scripts/main.js` — NEW
- `web/_includes/base.html` — wire up `<script defer src="/scripts/main.js"></script>`, add `<noscript>` styles

## Section 2 — Hero (`web/_includes/hero.html`)

### Layout (full viewport, ~92vh)

```
┌─────────────────────────────────────────────────────────┐
│ FEATURED ◦ NOW SHOWING                       2026 / 05  │  eyebrow row
│═════════════════════════════════════════════════════════│  4px thick rule
│                                                         │
│   내 주변                                                │
│   전시를 발견하는                                         │
│   가장 [쉬운 / 빠른 / 정확한] 방법                         │  kinetic word
│                                                         │
│   The easiest way to discover                          │
│   exhibitions near you                                 │
│                                                         │
│   ─────────────────                                     │  hairline divider
│                                                         │
│   App Store →    Google Play →                          │  magnetic links
│                                                         │
│─────────────────────────────────────────────────────────│
│ [img] [img] [img] [img] [img] [img] [img] [img] →→→     │  marquee strip
│  NOW SHOWING IN SEOUL · 1,200+ 전시 · 매일 업데이트       │
└─────────────────────────────────────────────────────────┘
```

### Detail

- **Eyebrow row** (`hero__meta`): left `FEATURED ◦ NOW SHOWING`, right `2026 / 05` (current YYYY / MM, computed at SSG time via Nunjucks `{{ "now" | date: "YYYY / MM" }}`). `--type-eyebrow` size, `--type-eyebrow-tracking`. 4px thick rule below.
- **Headline** (`hero__headline`): h1, `--type-display`, weight 700, line-height 1.05, letter-spacing -0.02em. Three lines, manually broken via `<br>` (Korean line breaks have no soft-break rules; manual is correct).
- **Kinetic word** (`hero__kinetic`): the third line contains `<span class="hero__kinetic" data-kinetic>` wrapping three `<span>` children: `쉬운`, `빠른`, `정확한`. Color: `--color-accent` (this is the only orange-text instance on the page). Falls back to first word as static when JS disabled.
- **English subhead** (`hero__subhead`): `--type-body-lg`, `--color-ink-secondary`, max-width 28ch. Two lines.
- **Hairline divider** (`hero__rule`): 1px solid `--color-ink`, width 6rem. On hero reveal, animates `transform: scaleX(0)` → `scaleX(1)` over 200ms, `transform-origin: left`.
- **CTAs** (`hero__ctas`): two `<a>` elements. NOT `.btn`. Style: `--type-body-lg`, weight 500, no border, no fill, underlined on hover (animated underline draws left-to-right, 200ms). `→` arrow translates `translateX(8px)` on hover. `data-magnetic` on each. Touch devices: no magnetic, underline is solid.
- **Marquee strip** (`hero__marquee`, full-bleed): 8 random exhibitions from `showcase.exhibitions`. Each tile: 140px × 180px, `<img>` with `--color-paper-alt` background placeholder, `border: var(--border-ink)`, no caption. `data-marquee data-duration="40s"`. Reduced motion: stops, shows static row of 4.
- **Status caption** (`hero__count`): below marquee, `--color-paper` background, hairline rule above. `--type-eyebrow` text. Renders the literal string `NOW SHOWING IN SEOUL · 1,200+ 전시 · 매일 업데이트`. The `1,200+` figure is a **hardcoded marketing number** — not derived from `showcase.exhibitions.length` (which is 12, the showcase sample size). The number lives in `web/_data/site.json` (NEW) under `liveCountLabel: "1,200+"` so we can update it in one place when the catalog grows.

### First-paint reveal sequence

| T (ms) | Element | Effect |
|--------|---------|--------|
| 0 | Eyebrow row + thick rule | Visible immediately (no animation) |
| 200 | Headline line 1 | reveal |
| 320 | Headline line 2 | reveal |
| 440 | Headline line 3 | reveal (kinetic begins at T+2400ms) |
| 700 | English subhead | reveal |
| 900 | Hairline divider | scaleX 0→1 |
| 1100 | CTAs | reveal-stagger |
| 1300 | Bottom strip | reveal |

All gated by reduced-motion.

## Section 3 — Features as Gallery Wall (`web/_includes/features.html`)

### Structure

Three feature blocks, full-width, **no grid** (the current 3-column grid is replaced). Each block is full-width with a 4px thick rule between them. Inside each block, a 2-column layout (text left, image right) at desktop; stacks at mobile.

### Layout (per block)

```
┌─────────────────────────────────────────────────────────┐
│ FEATURE No. 01 / DISCOVERY                       ↗      │  eyebrow row
│─────────────────────────────────────────────────────────│  hairline rule
│                                                         │
│  내 근처                            ┌──────────────┐    │
│  전시 찾기                          │              │    │
│                                    │   [REAL      │    │
│  Find exhibitions                  │    EXHIBITION│    │
│  near you                          │    IMAGE]    │    │
│                                    │              │    │
│  지금 진행 중이거나 오픈 예정인       │              │    │
│  전시를 한눈에 확인하세요…          │              │    │
│                                    └──────────────┘    │
│                                    리움: 소장품 특별전   │
│                                    리움미술관 · 종료 임박 │  ← orange badge
│                                                         │
└─────────────────────────────────────────────────────────┘
═══════════════════════════════════════════════════════════  4px thick rule between blocks
```

### Detail

- **Eyebrow row** (`feature__meta`): `FEATURE No. 01 / DISCOVERY` left (`--type-eyebrow`), arrow `↗` right (decorative).
- **Headline** (`feature__headline`): h3, `--type-headline`, weight 700, Korean.
- **English subhead** (`feature__subhead`): `--type-body-lg`, `--color-ink-secondary`.
- **Body** (`feature__body`): `--type-body`, max 38ch.
- **Image card** (`feature__image-card`): `<figure>` with real exhibition image (random pick from showcase data, 3 different exhibitions across the 3 features).
  - `<img>`: aspect-ratio 4 / 5, `object-fit: cover`, `border: var(--border-ink)`.
  - `<figcaption>`: title (Korean) + venue (Korean) + status badge.
  - Status badge: `<span class="status-badge status-badge--accent">` for `closing-soon`/`opening-soon` (orange text, no fill — `--type-eyebrow` size). Hidden if `status === "ongoing"`.
- **Reveal**: image uses `clip-path: inset(0 0 100% 0)` → `inset(0 0 0% 0)` over 600ms `--ease-gallery` (top-down clip-path wipe). Text uses `data-reveal-stagger`.
- **Hover**: image stays still. Caption underline draws left-to-right under the title (200ms).

### Files touched in Section 3

- `web/_includes/features.html` — rewrite layout
- `web/_includes/card-mockup.html` — DELETE (no longer used)
- `web/_data/features.js` — strip `mockup` fields (no longer rendered); keep KO/EN headline + description
- `web/styles/main.css` — feature block styles, status badge

## Section 4 — "Now Showing" Grid + Downloads

### Section 4a — "Now Showing" grid (NEW, `web/_includes/now-showing.html`)

Inserted between Features and Downloads.

```
┌─────────────────────────────────────────────────────────┐
│ NOW SHOWING                                       2026/05│
│═════════════════════════════════════════════════════════│  4px thick rule
│ [img]  [img]  [img]  [img]                              │
│ title  title  title  title                              │
│ venue  venue  venue  venue                              │
│ dates  dates  dates  dates                              │
│                                                         │
│ [img]  [img]  [img]  [img]                              │  8-up grid
│ title  title  title  title                              │
│ venue  venue  venue  venue                              │
│ dates  dates  dates  dates                              │
│─────────────────────────────────────────────────────────│  hairline rule
│        앱에서 1,200개 더 보기  →                         │  text link, magnetic
└─────────────────────────────────────────────────────────┘
```

- **Grid**: 4 columns desktop (≥1024px), 2 columns tablet (≥640px), 2 columns mobile.
- **Tile**: image (aspect 4/5, `object-fit: cover`, 1px border), title (`titleMedium` equivalent: 1.125rem, weight 500), venue (`--type-eyebrow`, secondary color), date range (`--type-meta`, secondary color). Status badge top-right corner of image (absolute) for `closing-soon`/`opening-soon`.
- **Source**: same 8 exhibitions as the hero marquee (single fetch in `showcase.json`, two presentations).
- **Reveal**: grid items stagger across rows, 60ms each. Top-down.
- **Hover**: image scales `1.03` over 400ms, title underlines.
- **CTA**: `앱에서 {liveCountLabel}개 더 보기 →` — text link, magnetic, links to App Store on iOS user agent and Google Play otherwise (UA sniff at click time, vanilla JS in `main.js`). `liveCountLabel` is the same `web/_data/site.json` value used in the hero (`"1,200+"` — single source of truth).

### Section 4b — Downloads (`web/_includes/downloads.html`)

Inverted palette: black background, white text. **Inverts the page palette for one section** — gallery wall flipped to black wall.

```
┌─────────────────────────────────────────────────────────┐
│ DOWNLOAD                                                │
│═════════════════════════════════════════════════════════│  4px thick rule (white)
│                                                         │
│  지금                                                    │
│  내려받기.                                                │  big display
│                                                         │
│  Available for iPhone and Android.                     │  white-muted subhead
│  Free to download.                                     │
│                                                         │
│  ─────────                                              │  short hairline (white)
│                                                         │
│  App Store →    Google Play →                           │  magnetic, white
│                                                         │
└─────────────────────────────────────────────────────────┘
```

- Background: `--color-ink` (black).
- Text: `--color-paper` (white) for primary, `#A0A0A0` (use existing `surfaceVariant` from DESIGN.md dark mode — value lifted directly into `--color-ink-on-dark-secondary` token added in tokens.css) for muted.
- Padding: `--space-3xl` top + bottom.
- Headline: `--type-display`, two lines.
- CTAs: same magnetic underlined `→` pattern as hero, white. **No orange button.** (The previous `#downloads .btn--primary` override is removed.)

### Files touched in Section 4

- `web/_includes/now-showing.html` — NEW
- `web/_includes/downloads.html` — rewrite
- `web/index.html` — add `{% include "now-showing.html" %}` between features and downloads
- `web/styles/tokens.css` — add `--color-ink-on-dark-secondary: #A0A0A0`
- `web/styles/main.css` — grid styles, downloads inversion, remove `#downloads .btn--primary` rules

## Section 5 — About + Footer

### Section 5a — About (`web/_includes/about.html`)

```
┌─────────────────────────────────────────────────────────┐
│ MISSION                                          gallr  │  eyebrow row
│═════════════════════════════════════════════════════════│  4px thick rule
│                                                         │
│  좋은 전시를                                              │
│  너무 늦게 알게 되는 일,                                   │  display headline
│  이제는 없도록.                                           │
│                                                         │
│  gallr는 국내 기관부터 갤러리, 대안공간까지                  │  body, large
│  도시의 전시를 한곳에 모았습니다.                           │
│                                                         │
│  gallr is the easiest way to discover exhibitions      │  english body, muted
│  in your city. Great shows come and go, and sometimes  │
│  people find out too late. From major institutions to  │
│  independent spaces, we bring the full picture          │
│  together in one place.                                 │
│                                                         │
│─────────────────────────────────────────────────────────│  hairline rule
└─────────────────────────────────────────────────────────┘
```

- Eyebrow row: `MISSION` left, small wordmark `gallr` right (decorative anchor).
- Headline: h2, `--type-display` (slightly smaller — `clamp(2.5rem, 7vw, 5rem)` via a one-off `--type-display-sm` token).
- Body: `--type-body-lg`, max 56ch.
- English: `<p class="bi-en">` (existing utility) — but at body-lg-relative size.

### Section 5b — Footer (rebuilt, in `web/_includes/base.html`)

```
┌─────────────────────────────────────────────────────────┐
│─────────────────────────────────────────────────────────│  hairline rule
│                                                         │
│ gallr            다운로드          회사            연락   │  4 columns
│ Discover         App Store         About           email │
│ exhibitions.     Google Play       Privacy         Insta │
│                                                         │
│─────────────────────────────────────────────────────────│  hairline rule
│ © 2026 gallr                              Made in Seoul │
└─────────────────────────────────────────────────────────┘
```

- 4 columns desktop, stacks to single column at <640px.
- Each column: heading `--type-eyebrow`, list items `--type-body`, links underlined on hover.
- Bottom row: `--type-meta`, `--color-ink-secondary`. Hairline rules above and between.

### Files touched in Section 5

- `web/_includes/about.html` — rewrite
- `web/_includes/base.html` — footer rewrite
- `web/styles/tokens.css` — add `--type-display-sm`
- `web/styles/main.css` — about + footer styles

## Visual / motion summary table

| Element | Motion | Duration | Easing | Reduced-motion fallback |
|---------|--------|----------|--------|------------------------|
| Hero kinetic word | vertical crossfade cycle | 400ms each, 2400ms interval | `--ease-gallery` | static, first word only |
| Hero hairline | scaleX 0 → 1 | 200ms | `--ease-gallery` | static, scaleX 1 |
| Hero CTAs | magnetic + underline draw | 200ms | linear | none — static, underline solid |
| Hero marquee | translateX loop | 40s | linear | static row of 4 |
| Header progress bar | scaleX scroll-tracked | continuous | linear | hidden |
| Header nav swap | fade-in | 200ms | `--ease-gallery` | instant |
| Feature image | clip-path inset wipe | 600ms | `--ease-gallery` | instant final state |
| Feature text | reveal-stagger | 600ms / 80ms stagger | `--ease-gallery` | instant final state |
| Now Showing grid | reveal-stagger | 600ms / 60ms stagger | `--ease-gallery` | instant final state |
| Now Showing tile hover | scale 1.03 + underline | 400ms | `--ease-gallery` | none |
| About headline | reveal | 600ms | `--ease-gallery` | instant final state |
| All `data-reveal` | opacity 0→1 + translateY 24px→0 | 600ms | `--ease-gallery` | instant final state |

## Accessibility

- **Reduced motion** (`@media (prefers-reduced-motion: reduce)`): all motion CSS disabled, JS early-returns. Final states preserved (text visible, marquee static, kinetic shows first word only).
- **Keyboard**: all interactive elements remain `<a>` tags with visible focus rings (existing `--color-ink` 3px outline). Magnetic effect does not alter tab order.
- **Screen readers**: kinetic word uses `aria-live="off"` (the cycling decoration is not announced); a visually-hidden `<span class="sr-only">방법</span>` after the kinetic span provides a stable accessible name. Marquee is wrapped in `aria-label="현재 진행 중인 전시"` and individual images have `alt="{titleKo}, {venueKo}"`.
- **Color contrast**: existing tokens already meet WCAG AA. Black-section downloads: `--color-ink-on-dark-secondary: #A0A0A0` on `#000000` = 7.2:1 (passes AA Large and AAA). Verified in plan task.
- **pa11y**: must pass with zero violations on `/`. Existing CI check stays green.

## Performance

- **Above-the-fold images**: hero marquee `<img>` tags use `loading="eager"`, `fetchpriority="high"` for the first 4. Remaining 4 + features + grid use `loading="lazy"`.
- **Image format**: rely on Supabase storage URLs as-is (JPEG/WebP whatever's uploaded). Add `<picture>` with `srcset` once we have a transform pipeline — out of scope for this PR.
- **JS bundle**: `main.js` < 5KB minified. No framework. Deferred load.
- **Build time**: showcase fetch adds ~500ms to `npm run build` when live; instant when seed-fallback. Acceptable.
- **LCP target**: hero headline (CSS-rendered text, no image dependency). Should remain <1s on Vercel.

## Acceptance criteria

1. Site builds with `npm run build` both with and without `SUPABASE_URL` + `SUPABASE_ANON_KEY` env vars set.
2. When env vars absent, `web/_data/showcase.json` matches `scripts/showcase-seed.json` content; build logs "showcase fallback: seed".
3. When env vars present, `web/_data/showcase.json.source === "supabase"`, `exhibitions.length === 12`, all entries have non-null `coverImageUrl`, all entries are currently running (opening_date ≤ today ≤ closing_date).
4. Hero renders eyebrow row, oversized headline, kinetic word, English subhead, hairline divider, two `→` CTAs, marquee strip with 8 images, count caption.
5. Three feature blocks render with eyebrow + index, oversized headline, English subhead, body, real exhibition image with caption + status badge (when applicable). Separated by 4px thick rules.
6. "Now Showing" grid renders 8 tiles with title/venue/date/status. CTA at bottom links to platform-appropriate store.
7. Downloads section is full-bleed black with white type and two `→` CTAs.
8. About section uses display-sm headline, body-lg paragraphs, KO + EN.
9. Footer is 4 columns with bottom copyright row.
10. Sticky header transitions to opaque after 80px scroll, shows progress bar.
11. All Playwright visual acceptance tests pass: hero structure, kinetic span exists, marquee container exists with N children, feature blocks count = 3, status badges render correctly, now-showing grid count = 8, downloads section is black, footer has 4 columns.
12. `pa11y http://localhost:8080/` reports zero violations.
13. `prefers-reduced-motion: reduce` disables all motion (verified in Playwright with `forced-colors: active` proxy or direct media query injection).
14. Mobile viewport (320px) shows no horizontal scroll, all sections legible, CTAs tappable (44px min).
15. Bundle: `dist/scripts/main.js` ≤ 5KB minified.
16. Lighthouse Performance ≥ 90 on `/` (mobile profile, simulated 4G).

## Out of scope (separate PRs)

- Replacing the leaked service_role key in `local.properties` with a proper anon key. Tracked as project_followups (memory).
- Page-level meta (`<title>`, `<meta description>`, OG image) localization to Korean — still flagged from PR #44.
- Image transforms / responsive `srcset` (`<picture>` with multiple resolutions). Add when Supabase image transformation is configured.
- Light/dark mode for the marketing page. The black Downloads section is a *deliberate inversion within a single section*, not a theme switch.
- Detail pages on the marketing site (linking exhibition tiles to detail URLs). Currently tiles are decorative + the grid CTA links to the app stores.
