# Mobile web nav — make EXHIBITIONS / MAP / ABOUT visible

**Date:** 2026-05-13
**Scope:** `web/` (Eleventy static site, gallrmap.com)
**Status:** Design, ready for plan

## Problem

On gallrmap.com, the desktop header shows three primary nav links — `전시 EXHIBITIONS · 지도 MAP · 소개 ABOUT` — between the `gallr` logo and the floating `다운로드` CTA. On mobile (≤640px) the nav is hidden entirely. The current rule:

```css
/* web/styles/main.css:1097-1100 */
@media (max-width: 640px) {
  .site-nav { display: none; } /* hamburger menu out of scope; nav links hidden on small */
  .site-header__inner { justify-content: space-between; }
}
```

Result: mobile visitors can only reach Exhibitions / Map / About via in-page links from the homepage, and have no header navigation at all on `/exhibitions/`, `/map/`, `/about/`, or any exhibition detail page. This is the project's largest discoverability gap on mobile.

## Goal

Surface the same three primary destinations in the header on mobile, with no runtime JS, no hamburger, and no regressions to the desktop layout.

## Non-goals

- No hamburger menu, drawer, or any JS-driven open/close.
- No bottom tab bar.
- No new destinations — same three links the desktop nav already has.
- No change to the `다운로드` CTA behavior (it stays hidden until scroll and only on desktop).
- No copy changes to the Korean or English labels themselves.

## Approach

**Inline compact nav.** Keep `.site-nav` visible on all viewport widths. On mobile, shrink the typography, tighten the gap, drop the pipe dividers, and let the bilingual labels stack horizontally with the logo. No new markup — pure CSS.

### Layout at each breakpoint

**Desktop (≥641px) — unchanged.**

```
[ ○ gallr ]      [ 전시 EXHIBITIONS | 지도 MAP | 소개 ABOUT ]      [ 다운로드 ]
   logo                          centered nav                      floating CTA
```

**Mobile (≤640px) — new.**

```
[ ○ gallr ]   [ 전시 EXHIBITIONS  지도 MAP  소개 ABOUT ]
   logo            right-aligned nav, no dividers
```

The `다운로드` CTA is already hidden on mobile (it's `opacity: 0` until `.is-stuck`, and even when stuck the existing layout already absorbs it). We hide it on mobile to free horizontal room for the nav.

### CSS changes

In `web/styles/main.css`, replace the existing mobile rule (around line 1097):

```css
@media (max-width: 640px) {
  .site-nav { display: none; } /* hamburger menu out of scope; nav links hidden on small */
  .site-header__inner { justify-content: space-between; }
}
```

with:

```css
@media (max-width: 640px) {
  /* Compact bilingual nav — single row, right-aligned, no pipe dividers. */
  .site-nav {
    flex: 0 0 auto;
    gap: var(--space-sm);
    justify-content: flex-end;
  }
  .site-nav .bi-en {
    /* Hide the English half on the narrowest screens to guarantee a single row. */
    display: none;
  }
  .site-nav__link + .site-nav__link::before {
    content: none; /* drop pipe dividers — gap is the separator on mobile */
  }
  .site-header__cta { display: none; } /* reclaim horizontal room; CTA stays desktop-only */
  .site-header__inner { gap: var(--space-md); }
}

/* Restore English half once we have more room (between phone and tablet). */
@media (min-width: 421px) and (max-width: 640px) {
  .site-nav .bi-en { display: inline; }
}
```

**Sizing rationale (verified visually, not yet in code):**

- Font size stays at `var(--type-eyebrow)` (0.6875rem ≈ 11px), inherited from the existing desktop `.site-nav__link` rule. We don't need a smaller mobile-only size — the eyebrow scale already reads tight.
- At 375px (iPhone SE / 12 mini width), the three Korean labels `전시 · 지도 · 소개` at eyebrow size with `var(--space-sm)` gap fit comfortably next to the logo with room to spare.
- At 414px+ (most modern phones), the bilingual form `전시 EXHIBITIONS  지도 MAP  소개 ABOUT` fits in a single row.

### No HTML changes

`web/_includes/base.html` is untouched. The same three `<a class="site-nav__link">` elements that render on desktop render on mobile — only their visual presentation changes.

### Active-link styling

The existing `aria-current="page"` rule (`main.css:1085-1089`) already paints the active link with the accent color and an underline. That works as-is on mobile.

## Components touched

| File | Change |
|---|---|
| `web/styles/main.css` | Replace the `@media (max-width: 640px)` block at line 1097; add a second nested media query for 421–640px to bring back the English half. ~15 lines net. |

No JS, no template, no data, no fonts. One file.

## Data flow

N/A — pure presentational change.

## Error handling / edge cases

- **Very narrow viewports (<375px).** The 11px Korean-only form fits down to ~320px. We don't aim for anything narrower; that's below iPhone SE (1st gen) and not a real target. If something breaks at 280px, that's acceptable.
- **Long page titles bumping into the nav.** The header is sticky and lives above content; nothing in the header reflows based on page content. No collision risk.
- **iOS Safari rubber-band scroll.** Header is `position: sticky`. Unchanged.
- **Korean font fallback.** The labels already use the site's default stack; no new glyph requirements.
- **Reduced motion.** No animations added; existing sticky-header transitions are untouched.
- **High contrast / focus-visible.** The existing `.site-nav__link` focus styles (`a:focus-visible` at line 1050) carry over.

## Testing

**Manual visual sweep** at the standard breakpoints — open the dev server and step through:

- 320px (iPhone SE 1st gen) — Korean labels only, no dividers, single row.
- 375px (iPhone SE 2nd/3rd gen, iPhone 12 mini) — Korean labels only, single row.
- 421px (just past the 420 cutoff) — bilingual labels visible, single row.
- 640px (just below the desktop cutoff) — bilingual, still mobile presentation.
- 641px (desktop floor) — desktop nav reappears, centered, with pipes.
- 1024px+ — desktop nav unchanged.

On each, confirm:
- All three links are visible and reachable.
- Active-page link is highlighted (load `/exhibitions/`, `/map/`, `/about/`).
- No horizontal overflow on `<body>` or `.site-header__inner`.
- Logo + nav share the row cleanly with no wrapping.

**Playwright check (optional, low-cost):** add one assertion to an existing mobile-viewport test (if any in `web/tests/`) that `.site-nav` is visible at 375px width on the homepage. If no mobile test fixture exists yet, skip — manual verification is enough for a CSS-only change of this size.

**Accessibility regression:** the existing `pa11y` task (if wired in CI) should pass unchanged — we're only changing visibility and sizing, not semantics.

## Rollout

Single CSS change. Ship via `develop` → PR → preview deploy → review on real devices → merge.

## Risks

- **Logo + nav crowding on the narrowest phones.** Mitigated by the Korean-only fallback below 421px.
- **Visual regression on the homepage hero.** The header is sticky and floats over the hero; visibility of the nav links could clash with bright hero imagery. The existing `.site-header.is-stuck` rule already handles scrolled state with a `--color-paper` background; the unstuck state stays transparent. On mobile, the nav text will sit over the hero. If contrast becomes a problem, we can add a subtle backdrop-blur or `--color-paper` background to the unstuck mobile header in a follow-up — out of scope for this fix.
- **iOS Safari sticky-header bugs.** Already in production for desktop; no new risk.

## Alternatives considered

- **Hamburger menu.** Requires JS for open/close, focus trap, and ESC handling. Three short links don't justify the surface area, and the site otherwise ships zero runtime JS for navigation.
- **Bottom tab bar.** Conflicts with iOS Safari's bottom chrome, covers content, and introduces a new component pattern not present elsewhere. Overkill.
- **Hide the logo wordmark on mobile, keep just the pin mark.** Was on the table to free horizontal room. Not needed once we drop the CTA and the dividers — the bilingual nav fits at 414px+ with the wordmark intact. Held in reserve as a future tightening if needed.

## Out of scope / follow-ups

- Hamburger or drawer pattern for any future fourth/fifth destination.
- A dedicated mobile-specific subnav for filtering inside `/exhibitions/` or `/map/`.
- Bringing the `다운로드` CTA back to mobile (currently desktop-only because of room; could revisit as a smaller icon-only variant).
