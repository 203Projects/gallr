# Website Fluid Redesign — Design Spec

**Date:** 2026-05-07
**Status:** Draft, awaiting user review
**Scope:** `web/` — gallr presentation website
**Owner:** hanshin

## Problem

After the 036-website-editorial-redesign shipped, the site reads as a confident editorial spread on desktop but breaks down on mobile in three ways:

1. **Type is too large on small screens.** `--type-display: clamp(3.5rem, 11vw, 9rem)` floors at 56px on a 360px phone — the headline alone consumes most of the first viewport.
2. **Sections feel disconnected ("segmented") on mobile.** Every section carries a 4px black top border, a 96px flat vertical padding, and its own eyebrow + thick rule treatment. On a 375px phone this reads as five disconnected sealed boxes stacked vertically.
3. **Now Showing grid jumps awkwardly** at the 1024px breakpoint (2-up directly to 4-up) with no tablet step, leaving the 768–1023px band feeling either too sparse or too cramped.

Plus two known content problems:

4. Local builds and any build without `SUPABASE_URL` + `SUPABASE_ANON_KEY` fall through to 12 monochrome SVG placeholders that read as obviously fake.
5. Footer email is `hello@gallr.app`, should be `hello@gallrmap.com`.

## Goal

A fluid responsive system from 360px to 1920px+ where:
- Typography scales smoothly via `clamp()` with mobile-honest floors (no 56px headlines on phones).
- Vertical rhythm scales with screen size — sections breathe in proportion, not absolute.
- Section borders/rules stay editorial on desktop but soften on mobile so the page reads as one continuous flow.
- Now Showing grid steps cleanly at 2 / 3 / 4 columns.
- Images on every build context come from real Supabase data (live fetch in production, curated real-image seed everywhere else).
- Contact email is correct.

The visual DNA stays the same: monochrome editorial, neo-grotesque sans, single orange accent, 0px radius, kinetic type. Direction A from brainstorming ("Refined Editorial").

## Non-goals

- Re-thinking section structure (Hero, Features, Now Showing, Downloads, About, Footer order is preserved).
- Re-thinking visual identity (no new fonts, no new colors, no new brand voice).
- Korean-forward vs English-forward content reshuffle (already done in 035).
- Image-first hero treatment (rejected; would re-open settled design questions).
- The mobile/desktop apps — out of scope.

---

## Design

### 1. Fluid type & spacing system

Replace step-tiered breakpoints with one fluid curve from 360px to ~1440px using `clamp(min, vw + rem, max)`.

**New type tokens** (`web/styles/tokens.css`):

```css
--type-display:    clamp(2.25rem, 4.5vw + 1rem, 7.5rem);     /* 36→120px */
--type-display-sm: clamp(1.75rem, 3vw + 1rem, 4.5rem);       /* 28→72px  */
--type-headline:   clamp(1.5rem,  2vw + 0.875rem, 3rem);     /* 24→48px  */
--type-body-lg:    clamp(1rem,    0.5vw + 0.875rem, 1.25rem); /* 16→20px */
--type-body:       1rem;       /* fixed — readability anchor */
--type-eyebrow:    0.6875rem;  /* fixed — meta scale */
```

The `vw + rem` form is preferred over pure `vw` because:
- Gentler slope avoids absurd sizes at extreme viewports.
- The `rem` term respects user font-size preferences (a11y).

**At 360px** (smallest target): display ≈ 36px, headline ≈ 24px. **At 1280px**: display ≈ 73px, headline ≈ 39px. **At 1920px**: display ≈ 102px, headline ≈ 49px (so the `--type-headline` cap of 48px begins to bite around the desktop range; the display cap of 120px only triggers past ~2300px viewports). The caps exist as guardrails for ultra-wide, not as a normal-range constraint.

**New spacing tokens** (replacing the current static scale):

```css
--space-xs:  4px;
--space-sm:  clamp(6px,  1vw, 10px);
--space-md:  clamp(12px, 2vw, 20px);
--space-lg:  clamp(20px, 3vw, 40px);
--space-xl:  clamp(36px, 5vw, 72px);
--space-2xl: clamp(56px, 8vw, 112px);
--space-3xl: clamp(80px, 12vw, 180px);
--space-4xl: clamp(120px, 16vw, 240px);

--page-padding-x: clamp(16px, 4vw, 64px);
```

The fluid `--page-padding-x` replaces the three media-query overrides currently at `tokens.css:95-105`.

### 2. Section rhythm

**Unified section padding:**

```css
.section,
.hero, .features, .now-showing, .downloads, .about {
  padding-block: var(--space-3xl);   /* 80→180px */
}
```

Replaces every per-section `padding: var(--space-2xl) 0` (currently a flat 96px). Sections tighten on phones and breathe on desktop.

**Mobile border softening:**

```css
.now-showing,
.about {
  border-top: var(--border-hairline);  /* 1px */
}
@media (min-width: 768px) {
  .now-showing,
  .about {
    border-top: var(--border-section); /* 4px */
  }
}
```

The Hero→Features and Downloads inversion borders stay thick at all sizes (those breaks are intentional).

**Eyebrow-row collapse on mobile.** The pattern repeated across Features, Now Showing, About, Downloads — eyebrow + date row → thick rule → headline — eats half a phone viewport before content arrives. On mobile the eyebrow collapses inline above the headline; on desktop it restores to a separate row.

```css
.feature-block__meta,
.now-showing__meta,
.about__meta,
.downloads__meta {
  display: contents;          /* mobile: children flow naturally */
}
@media (min-width: 768px) {
  .feature-block__meta,
  .now-showing__meta,
  .about__meta,
  .downloads__meta {
    display: flex;            /* desktop: full editorial row */
    justify-content: space-between;
    align-items: baseline;
    border-bottom: var(--border-hairline);
    padding-bottom: var(--space-sm);
    margin-bottom: var(--space-xl);
  }
}
```

The Hero keeps its full editorial frame (eyebrow row + thick rule) at all viewports — it's the front door.

### 3. CTA pair

A new `.cta-pair` class replaces both `.hero__ctas` and `.downloads__ctas` layout rules:

```css
.cta-pair {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--space-sm);
}
@media (min-width: 480px) {
  .cta-pair { grid-template-columns: 1fr 1fr; }
}
```

The 480px breakpoint is the natural spot — that's where two CTAs first fit comfortably side-by-side. Below 480px they stack full-width.

The two `<a>` elements inside keep their existing `.hero__cta` / `.downloads__cta` styling (border, padding, magnetic hover behavior). This is a layout-only change.

### 4. Now Showing grid

Replace the 2-up → 4-up cliff with a 3-tier step:

```css
.now-showing__grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--space-lg);
}
@media (min-width: 640px)  { .now-showing__grid { grid-template-columns: repeat(3, 1fr); } }
@media (min-width: 1024px) { .now-showing__grid { grid-template-columns: repeat(4, 1fr); } }
```

12 tiles divides cleanly into 2 / 3 / 4 columns (6 / 4 / 3 rows), so no orphan tiles at any viewport.

The hero marquee already has its own scroll mechanics — leaving as-is. (Future improvement: reduce visible tile count on mobile from 8 to 4-5 by adjusting `flex-basis` for `.hero__marquee-tile` so the eye reads them as a strip rather than a parade. Tracked but not in scope here unless trivial during the rhythm pass.)

### 5. Image pipeline

Three pieces:

**5.1 Confirm Vercel env vars.** Production builds need `SUPABASE_URL` + `SUPABASE_ANON_KEY` set in Vercel project settings. The implementation plan includes a verification step before merge: a one-shot `gh api` call or Vercel dashboard screenshot confirming both are set. Without them, every production build silently uses the seed.

**5.2 New `npm run refresh-seed` script** — `web/scripts/refresh-seed.js`. Reads a hand-curated anchor list, fetches matching exhibitions from Supabase, fills remaining slots from a venue allowlist, writes the result to `web/scripts/showcase-seed.json`.

Anchor file (`web/scripts/seed-anchors.json`):

```json
{
  "anchors": [
    { "id": "abc-123" },
    { "title_ko": "한국 단색화의 계보" }
  ],
  "fillVenues": [
    "Leeum Museum of Art",
    "MMCA Seoul",
    "Amorepacific Museum of Art",
    "Kukje Gallery",
    "PKM Gallery"
  ],
  "targetCount": 12
}
```

Behavior:
1. Fetches anchors first by `id` or `title_ko` exact match.
2. Fills remaining slots from `fillVenues` with currently-running shows (`opening_date <= today AND closing_date >= today`), ordered by closing date ascending, deduped against anchors.
3. Errors loudly (non-zero exit) if Supabase is unreachable or returns < `targetCount` total. Refresh is an explicit human action — never silently falls back.
4. Writes JSON in the same shape as `fetch-showcase.js` produces (`{ fetchedAt, source: "seed-curated", exhibitions: [...] }`).

The user provides the anchor IDs/titles after this spec; not part of the implementation plan itself.

`refresh-seed` is **not** part of `npm run build`. It runs manually whenever the curated set should be refreshed. The usual `npm run build` keeps using `fetch-showcase.js` (live Supabase or seed fallback).

**5.3 Delete the SVG placeholders.** After the first `refresh-seed` run produces a real-image seed:
- Delete `web/public/showcase/seed-*.svg` (12 files)
- Delete `web/scripts/generate-placeholder-svgs.js`

Any local dev / preview / test from that point forward sees real artwork.

**5.4 Image-error safety net.** Real Supabase URLs occasionally fail (deleted asset, CDN hiccup). A broken `<img>` icon is uglier than a thoughtful empty state. New rule for showcase images:

```html
<img
  src="{{ ex.coverImageUrl }}"
  alt="{{ ex.titleKo }}, {{ ex.venueKo }}"
  loading="lazy"
  data-fallback-title="{{ ex.titleKo }}"
  onerror="this.parentElement.classList.add('grid-tile__image-wrap--missing')"
/>
```

```css
.grid-tile__image-wrap--missing img { opacity: 0; }
.grid-tile__image-wrap--missing::after {
  content: attr(data-fallback-title);
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-md);
  font-family: var(--font-meta);
  font-size: var(--type-eyebrow);
  letter-spacing: var(--type-eyebrow-tracking);
  text-transform: uppercase;
  text-align: center;
  color: var(--color-ink);
  background: var(--color-paper-alt);
}
```

The same treatment is applied to feature-block figures and hero marquee tiles.

### 6. Email

`web/_includes/base.html:77` — change `mailto:hello@gallr.app` to `mailto:hello@gallrmap.com` (link text and `href`).

### 7. Architecture & file changes

| File | Change |
|---|---|
| `web/styles/tokens.css` | Replace type & spacing tokens with fluid `clamp()` versions; remove the three `@media` overrides on `--page-padding-x`. |
| `web/styles/main.css` | Apply unified `padding-block` to all sections; soften mobile borders on Now Showing + About; collapse eyebrow-rows on mobile; new `.cta-pair` class; new 3-tier `.now-showing__grid`; new `.grid-tile__image-wrap--missing` styles. |
| `web/_includes/base.html` | Email change; ensure `data-fallback-title` + `onerror` attributes wherever showcase images render. |
| `web/_includes/hero.html` | Apply `.cta-pair` to `.hero__ctas`; add `data-fallback-title`/`onerror` to marquee tiles. |
| `web/_includes/features.html` | Add `data-fallback-title`/`onerror` to feature figures. |
| `web/_includes/now-showing.html` | Add `data-fallback-title`/`onerror` to grid tiles. |
| `web/_includes/downloads.html` | Apply `.cta-pair` to `.downloads__ctas`. |
| `web/scripts/refresh-seed.js` | New — curated seed builder. |
| `web/scripts/seed-anchors.json` | New — user-controlled anchor list. |
| `web/scripts/showcase-seed.json` | Regenerated by `refresh-seed` to contain real Supabase image URLs. |
| `web/scripts/generate-placeholder-svgs.js` | Delete. |
| `web/public/showcase/seed-*.svg` | Delete (12 files). |
| `web/package.json` | Add `"refresh-seed": "node scripts/refresh-seed.js"` script. |
| `web/tests/type-scale.spec.ts` | New — Playwright type-size guards. |
| `web/tests/section-rhythm.spec.ts` | New — Playwright border/spacing guards. |
| `web/tests/refresh-seed.test.js` | New — unit test for the curated seed builder. |
| `web/tests/baseline/*` | New — visual regression baselines at 4 viewports. |

---

## Testing & verification

### Automated

**1. Visual regression** at four viewports (`360×800`, `414×896`, `820×1180`, `1440×900`). Full-page + per-section screenshots stored in `web/tests/baseline/`. Playwright `toHaveScreenshot()` with `maxDiffPixels: 50` to absorb font-rendering variation.

**2. Type-scale guards** (`web/tests/type-scale.spec.ts`). At 360px viewport, computed font-size of:
- `.hero__headline` ≤ 40px
- `.feature-block__headline` ≤ 32px
- `.about__headline` ≤ 32px
- `.downloads__headline` ≤ 36px

These are the regression net for "fonts too large in some places."

**3. Section rhythm guard** (`web/tests/section-rhythm.spec.ts`). At 375px viewport:
- `.now-showing` and `.about` `border-top-width` is `1px`
- At 768px viewport, both are `4px`

**4. Image fallback test** (Playwright). Navigate with route interception forcing 404 on one tile; assert the tile shows the title text and `--missing` class is applied.

**5. `refresh-seed` unit test** (`web/tests/refresh-seed.test.js`). Mocked Supabase fetch fixture; assert output has 12 entries, anchors first, all `coverImageUrl` start with `https://`. Errors when fetch fails or count < target.

**6. Existing pa11y suite must continue to pass** at WCAG 2.1 AA — the rewrite cannot regress accessibility.

### Manual QA gate

Before merge to `develop`, one human pass on real devices:
- iPhone (Safari) at 375px and 414px
- Android (Chrome) at 360px and 412px
- Desktop Chrome, Safari, Firefox at 1440px

Checklist:
- [ ] Hero headline does not overflow at 360px
- [ ] CTA pair stacks below 480px, pairs above
- [ ] No section reads as a "boxed-off" island on mobile
- [ ] Real Supabase images load in production (not SVG seed)
- [ ] `mailto:hello@gallrmap.com` opens correctly
- [ ] Vercel deployment shows live exhibition images
- [ ] No layout shift / FOUT regression vs current site

---

## Order of work (preview for the implementation plan)

1. **Tokens.** New fluid type + spacing tokens. Site looks slightly different but every section still works.
2. **Section rhythm.** Unified `padding-block`, mobile border softening, eyebrow-row collapse.
3. **CTA pair.** New `.cta-pair` class applied to Hero and Downloads.
4. **Now Showing grid.** 3-tier step at 640px / 1024px.
5. **Image safety net.** `data-fallback-title` + `onerror` markup, `.grid-tile__image-wrap--missing` styles.
6. **`refresh-seed` script + anchor file.** Manual run produces a real-image seed.
7. **Delete SVG placeholders + the generator script.**
8. **Email update.**
9. **Test suite.** Visual regression baselines + type-scale guard + rhythm guard + refresh-seed unit test + image-fallback test.
10. **Vercel env-var verification + manual QA pass.**

Each step is its own commit. Visual regression baselines are taken after step 4 and updated only by intentional design changes thereafter.

---

## Open questions for follow-up

These don't block this spec but should be tracked:

1. **Anchor exhibition list** — user to provide 4–6 IDs or `title_ko` values from the Google sheet for `seed-anchors.json` before step 6.
2. **Vercel env-var status** — verify `SUPABASE_URL` and `SUPABASE_ANON_KEY` are set in Vercel before relying on production live-fetch.
3. **Hero marquee tile count on mobile** — leaving as-is for now. If the rhythm pass shows it still feels like "a parade," reduce visible count via `flex-basis`. Tracked, not blocking.

---

## Out of scope (explicitly)

- The mobile/desktop apps (no Compose/SwiftUI changes).
- Korean/English content reshuffle (already shipped in 035).
- New brand assets, fonts, or color additions.
- Analytics / instrumentation changes.
- The map filter, bookmark UI, or anything app-side.
