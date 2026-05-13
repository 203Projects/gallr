# Mobile Web Nav Visibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface the three primary nav links (전시 EXHIBITIONS · 지도 MAP · 소개 ABOUT) in the gallrmap.com header on mobile viewports (≤640px) without adding a hamburger or any runtime JS.

**Architecture:** Pure CSS change. Replace the existing `@media (max-width: 640px) { .site-nav { display: none } }` rule in `web/styles/main.css` with a compact, right-aligned, no-divider mobile presentation of the same three links. Below 421px, show Korean labels only; from 421–640px, show both Korean and English (matching desktop). No HTML, no JS, no template changes.

**Tech Stack:** Eleventy 3.x static site, hand-written CSS (`web/styles/main.css`), Playwright 1.49 for visual regression. No build step changes.

**Spec:** [docs/2026-05-13-mobile-web-nav-visible-design.md](../../2026-05-13-mobile-web-nav-visible-design.md)

---

## File Structure

| File | Action | Purpose |
|---|---|---|
| `web/tests/mobile-nav.test.ts` | Create | Playwright test verifying `.site-nav` is visible at 375px and 414px on mobile, hidden vs. visible state, label visibility at each breakpoint, and that `.site-header__cta` stays hidden on mobile |
| `web/styles/main.css` | Modify (line 1097–1100, ~15 lines net added) | Replace the mobile hide-rule with a compact mobile presentation |
| `playwright.config.ts` | Modify | Add `mobile-nav.test.ts` to the `chromium-mobile` project's `testMatch` regex |

No other files. No HTML template touched.

---

## Branching

This work happens on a feature branch off `develop` (per gallr branching rules). Land via a PR back into `develop`; `main` is deploy-only.

- [ ] **Step 0a: Create feature branch off develop**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git checkout develop
git pull origin develop
git checkout -b feat/mobile-web-nav-visible
```

Expected: branch `feat/mobile-web-nav-visible` checked out, working tree clean except for any pre-existing untracked files.

---

## Task 1: Write the failing Playwright test for mobile nav visibility

**Files:**
- Create: `web/tests/mobile-nav.test.ts`
- Modify: `web/playwright.config.ts` (add to `chromium-mobile` testMatch)

The `chromium-mobile` project in `playwright.config.ts` already runs with Pixel 5 device emulation and JS enabled — we add our test to that project. We use explicit `setViewportSize` calls per test (mirroring the pattern in `tests/hero-layout.test.ts`) so we can pin exact widths.

- [ ] **Step 1: Create the test file**

Create `web/tests/mobile-nav.test.ts` with the exact contents:

```typescript
import { test, expect, type Page } from "@playwright/test";

// On gallrmap.com, the three primary nav links (전시 EXHIBITIONS,
// 지도 MAP, 소개 ABOUT) were hidden entirely on viewports ≤640px.
// This test pins them visible on mobile, with two presentation tiers:
//   - ≤420px: Korean labels only (the .bi-en spans are hidden)
//   - 421–640px: bilingual (both Korean and English visible)
// The desktop floating CTA (다운로드) must stay hidden on mobile.

async function isVisible(page: Page, selector: string): Promise<boolean> {
  const locator = page.locator(selector).first();
  return await locator.isVisible();
}

async function displayValue(page: Page, selector: string): Promise<string> {
  return await page.locator(selector).first().evaluate((el) => {
    return getComputedStyle(el).display;
  });
}

test.describe("mobile site nav visibility", () => {
  test("at 375px: nav links visible, English half hidden, CTA hidden", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 800 });
    await page.goto("/");

    // The nav container itself is visible.
    expect(await isVisible(page, ".site-nav")).toBe(true);

    // All three links are visible.
    const links = page.locator(".site-nav__link");
    await expect(links).toHaveCount(3);
    for (let i = 0; i < 3; i++) {
      expect(await links.nth(i).isVisible()).toBe(true);
    }

    // English half is hidden via display: none on .bi-en at this width.
    expect(await displayValue(page, ".site-nav .bi-en")).toBe("none");

    // The desktop floating CTA stays hidden on mobile.
    expect(await displayValue(page, ".site-header__cta")).toBe("none");
  });

  test("at 414px: bilingual labels visible, CTA still hidden", async ({ page }) => {
    await page.setViewportSize({ width: 414, height: 800 });
    await page.goto("/");

    expect(await isVisible(page, ".site-nav")).toBe(true);
    // 414px is below the 421px breakpoint that restores English; the
    // English half should still be hidden here.
    expect(await displayValue(page, ".site-nav .bi-en")).toBe("none");
    expect(await displayValue(page, ".site-header__cta")).toBe("none");
  });

  test("at 480px: bilingual labels visible, CTA still hidden", async ({ page }) => {
    await page.setViewportSize({ width: 480, height: 800 });
    await page.goto("/");

    expect(await isVisible(page, ".site-nav")).toBe(true);
    // 480px is above 421px — English half restored.
    expect(await displayValue(page, ".site-nav .bi-en")).toBe("inline");
    expect(await displayValue(page, ".site-header__cta")).toBe("none");
  });

  test("at 640px: still mobile presentation (bilingual, no pipe dividers, no CTA)", async ({ page }) => {
    await page.setViewportSize({ width: 640, height: 800 });
    await page.goto("/");

    expect(await isVisible(page, ".site-nav")).toBe(true);
    expect(await displayValue(page, ".site-nav .bi-en")).toBe("inline");
    expect(await displayValue(page, ".site-header__cta")).toBe("none");

    // Pipe dividers ('|') between links are dropped on mobile.
    // The pseudo-element ::before content for adjacent nav links is "none".
    const dividerContent = await page.locator(".site-nav__link").nth(1).evaluate((el) => {
      return getComputedStyle(el, "::before").content;
    });
    expect(dividerContent).toBe("none");
  });
});
```

- [ ] **Step 2: Wire the test into the chromium-mobile Playwright project**

Edit `web/playwright.config.ts`. The current `chromium-mobile` block is:

```typescript
{
  // Mobile viewport tests for the fluid redesign — type scale,
  // section rhythm, CTA pair stacking, grid column count.
  name: "chromium-mobile",
  testMatch: /(type-scale|section-rhythm|cta-pair|now-showing-grid|image-fallback|hero-layout)\.test\.ts/,
  use: { ...devices["Pixel 5"], javaScriptEnabled: true },
},
```

Replace it with:

```typescript
{
  // Mobile viewport tests for the fluid redesign — type scale,
  // section rhythm, CTA pair stacking, grid column count, and mobile nav.
  name: "chromium-mobile",
  testMatch: /(type-scale|section-rhythm|cta-pair|now-showing-grid|image-fallback|hero-layout|mobile-nav)\.test\.ts/,
  use: { ...devices["Pixel 5"], javaScriptEnabled: true },
},
```

The only change is adding `|mobile-nav` inside the regex group and updating the comment.

- [ ] **Step 3: Build the site so Playwright has something to serve**

Run from `web/`:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web
npm run build
```

Expected: Eleventy completes, `dist/` exists, no errors. If the fetch-showcase script needs Supabase env vars and fails, fall back to: `SKIP_SHOWCASE=1 npm run build` if that env switch exists, otherwise run `npx @11ty/eleventy` directly to skip the fetch scripts (an Eleventy-only build is enough for header markup since the header is in `_includes/base.html` and doesn't depend on showcase data).

If the build hard-fails without showcase data (it has in the past on empty curated sets), build with only Eleventy:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web
node scripts/copy-fonts.js && npx @11ty/eleventy
```

Verify `dist/index.html` exists and contains the `.site-nav` markup:

```bash
grep -c 'class="site-nav"' dist/index.html
```

Expected: `1` (or higher if it appears on multiple pages we don't care — just non-zero).

- [ ] **Step 4: Run the new test to verify it fails**

Run from `web/`:

```bash
npx playwright test mobile-nav --project=chromium-mobile
```

Expected: **All 4 tests FAIL**. The first three fail because `.site-nav` is `display: none` at mobile widths under the current CSS. The fourth fails because the `::before` pseudo-element's `content` value is `"|"` (a literal pipe character with quotes), not `"none"`.

If the test errors with "test not found" or "no tests matched", the testMatch regex edit in Step 2 was wrong — re-check the regex.

- [ ] **Step 5: Commit the failing test**

```bash
git add web/tests/mobile-nav.test.ts web/playwright.config.ts
git commit -m "test: add failing mobile nav visibility test

Pins three breakpoints: 375px (Korean-only), 414px (still Korean-only,
below 421 cutoff), 480px and 640px (bilingual). Verifies CTA stays
hidden and pipe dividers are dropped on mobile.

Currently fails because .site-nav { display: none } on ≤640px."
```

---

## Task 2: Update mobile CSS to make the nav visible

**Files:**
- Modify: `web/styles/main.css` (replace the `@media (max-width: 640px)` block at line 1097)

The current block (lines 1097–1100):

```css
@media (max-width: 640px) {
  .site-nav { display: none; } /* hamburger menu out of scope; nav links hidden on small */
  .site-header__inner { justify-content: space-between; }
}
```

- [ ] **Step 1: Replace the mobile media query block**

In `web/styles/main.css`, replace the four-line block at line 1097 with:

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

/* Restore the English half once there's more horizontal room. */
@media (min-width: 421px) and (max-width: 640px) {
  .site-nav .bi-en { display: inline; }
}
```

- [ ] **Step 2: Rebuild the site so Playwright picks up the new CSS**

```bash
cd /Users/hanshin/Documents/Projects/gallr/web
node scripts/copy-fonts.js && npx @11ty/eleventy
```

Expected: build succeeds. `dist/styles/main.css` (or wherever Eleventy copies it — check passthrough config if needed) reflects the new rules.

Sanity check:

```bash
grep -A 2 "Compact bilingual nav" dist/styles/main.css
```

Expected: the new rule appears in the built CSS. If the CSS isn't in `dist/`, check `web/.eleventy.js` or `web/eleventy.config.js` for the passthroughCopy config — there's an existing mechanism, since the desktop site works.

- [ ] **Step 3: Run the mobile-nav tests to verify they pass**

```bash
npx playwright test mobile-nav --project=chromium-mobile
```

Expected: **All 4 tests PASS**.

- [ ] **Step 4: Run the full mobile test suite to check for regressions**

```bash
npx playwright test --project=chromium-mobile
```

Expected: all mobile tests pass — `type-scale`, `section-rhythm`, `cta-pair`, `now-showing-grid`, `image-fallback`, `hero-layout`, and the new `mobile-nav`. None of those tests touch `.site-nav` or `.site-header__cta` (we are reusing the same selectors and only changing their visibility), so they should not regress.

- [ ] **Step 5: Run the full Playwright suite to check for desktop regressions**

```bash
npx playwright test
```

Expected: all projects pass. The desktop nav rendering is unchanged (the new `.site-nav { font-size: 0.6875rem }` and `flex: 0 0 auto` rules live inside the `max-width: 640px` media query and don't apply at desktop widths).

If any non-mobile-nav test fails, investigate before continuing — do not "fix" by relaxing the test.

- [ ] **Step 6: Commit the CSS change**

```bash
git add web/styles/main.css
git commit -m "fix(web): show nav links on mobile

Replaces the .site-nav { display: none } rule on ≤640px with a
compact right-aligned presentation. Korean-only below 421px,
bilingual at 421–640px. Drops pipe dividers and the floating
다운로드 CTA on mobile to make room.

No HTML/JS change. Desktop layout unchanged."
```

---

## Task 3: Visual sweep at the documented breakpoints

The spec calls out five hand-checked breakpoints. Playwright covers four of them functionally; we still want eyes on the layout to catch wrapping, crowding, and contrast issues the tests can't see.

- [ ] **Step 1: Start the Eleventy dev server**

```bash
cd /Users/hanshin/Documents/Projects/gallr/web
npm run dev
```

Expected: server starts on `http://localhost:8080` (Eleventy default — confirm the URL it prints).

- [ ] **Step 2: Step through breakpoints in browser devtools**

Open `http://localhost:8080/` in Chrome with devtools device emulation. For each width, load `/`, `/exhibitions/`, `/map/`, and `/about/` and confirm the criteria below. (The four pages share the same `_includes/base.html` header, so one issue on one page is an issue everywhere.)

| Width | Expected presentation | Confirm |
|---|---|---|
| 320px | Korean labels only (`전시 · 지도 · 소개`), no dividers, single row | Logo + 3 links on one row, no wrap, no horizontal overflow |
| 375px | Korean labels only, single row | Logo + 3 links on one row, no wrap |
| 421px | Bilingual labels visible | English half (`EXHIBITIONS`, `MAP`, `ABOUT`) reappears next to each Korean label |
| 640px | Still mobile presentation: bilingual, no pipes, right-aligned, CTA hidden | One row, no pipe dividers |
| 641px | Desktop nav reappears: centered, with pipe dividers between links | Desktop layout with `\|` dividers visible |

On each, also confirm:

- All three links are clickable and route to the correct destination.
- The active-page link (`aria-current="page"`) shows the accent-color treatment (load `/exhibitions/` and confirm the EXHIBITIONS link is highlighted).
- No `<body>` or `.site-header__inner` horizontal scrollbar.
- Korean glyphs render correctly (no `□` boxes or fallback fonts).

- [ ] **Step 3: Stop the dev server**

Ctrl+C in the terminal running `npm run dev`.

- [ ] **Step 4: If anything in the sweep failed, fix it before continuing**

The most likely failure mode is crowding at 320px. If Korean labels still wrap there, the lowest-impact fix is to reduce `--space-sm` between links to `--space-xs` only inside the `@media (max-width: 420px)` range. Add this rule below the new mobile block in `main.css`:

```css
@media (max-width: 420px) {
  .site-nav { gap: var(--space-xs); }
}
```

Re-run `npx @11ty/eleventy`, re-load 320px, confirm. Commit as a separate `fix(web): tighten nav gap at narrowest viewport` commit if needed.

If logo + nav still crowd at 320px even with tighter gap, the next-cheapest move is hiding the `.site-logo__wordmark` (keeping the pin mark) below 360px. Add:

```css
@media (max-width: 359px) {
  .site-logo__wordmark { display: none; }
}
```

This is held in reserve in the spec; only apply if visual sweep shows real crowding. Commit separately.

- [ ] **Step 5: If visual sweep passed cleanly, no further commits in this task**

Just move to Task 4.

---

## Task 4: Open the PR back into develop

- [ ] **Step 1: Push the feature branch**

```bash
cd /Users/hanshin/Documents/Projects/gallr
git push -u origin feat/mobile-web-nav-visible
```

- [ ] **Step 2: Open the PR**

```bash
gh pr create --base develop --title "fix(web): show nav links on mobile" --body "$(cat <<'EOF'
## Summary
- Replaces \`.site-nav { display: none }\` at ≤640px with a compact right-aligned mobile presentation
- Korean labels only below 421px, bilingual at 421–640px (matching desktop content)
- Drops pipe dividers and the floating 다운로드 CTA on mobile to make room
- No HTML, no JS, no template change — one CSS block

## Why
Mobile visitors had no header nav on \`/exhibitions/\`, \`/map/\`, \`/about/\`, or any exhibition detail page. The original "hamburger out of scope" decision left the nav hidden indefinitely. Three short links don't justify a hamburger; an inline compact nav is the minimum change that fixes discoverability.

## Test plan
- [ ] CI: \`npx playwright test --project=chromium-mobile\` green (includes new \`mobile-nav.test.ts\`)
- [ ] CI: full \`npx playwright test\` green — no desktop regressions
- [ ] Manual: visual sweep at 320 / 375 / 421 / 640 / 641 px on \`/\`, \`/exhibitions/\`, \`/map/\`, \`/about/\` (see plan Task 3)
- [ ] Manual: active-link styling still works on \`/exhibitions/\`
- [ ] Manual: Korean glyphs render correctly across breakpoints

Spec: \`docs/2026-05-13-mobile-web-nav-visible-design.md\`
Plan: \`docs/superpowers/plans/2026-05-13-mobile-web-nav-visible.md\`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: PR URL printed. Return it.

- [ ] **Step 3: Confirm the PR's base branch is develop, not main**

Per gallr's branching rules, `main` is deployment-only. The PR must target `develop`. Verify in the PR URL output (it'll show `<owner>/<repo>:develop ← <owner>/<repo>:feat/mobile-web-nav-visible`).

- [ ] **Step 4: Done**

Once the PR is open and CI is green, the implementation is complete. Promotion to `main` happens later through the separate develop → main PR gate (per project memory, `main` is reached only via PR — never fast-forward).

---

## Self-Review

**Spec coverage check:**

| Spec section | Plan task |
|---|---|
| Replace the ≤640px hide-rule | Task 2 Step 1 |
| Korean-only below 421px, bilingual 421–640px | Task 2 Step 1 (two media queries); Task 1 tests both regions |
| Drop pipe dividers on mobile | Task 2 Step 1 (`content: none`); Task 1 Step 1 (640px test asserts `::before` content is `none`) |
| Hide `다운로드` CTA on mobile | Task 2 Step 1 (`.site-header__cta { display: none }`); Task 1 tests assert CTA display value |
| No HTML/JS/template change | Plan touches only `main.css`, `mobile-nav.test.ts`, `playwright.config.ts` — no template files in File Structure table |
| Manual visual sweep at 320/375/421/640/641px | Task 3 Step 2 |
| Playwright assertion (optional) at 375px | Task 1 covers this and goes further (4 breakpoints) |
| Active-page link styling unchanged | Task 3 Step 2 confirms on `/exhibitions/` |
| Fallback: tighten gap below 420px if crowding | Task 3 Step 4 |
| Fallback: hide logo wordmark below 360px if crowding | Task 3 Step 4 |
| Single CSS-only change, ~15 lines net | Task 2 Step 1 — matches |

All spec sections have at least one corresponding task step.

**Placeholder scan:** none found. Every code block is complete; every command has an expected outcome.

**Type/selector consistency:** `.site-nav`, `.site-nav__link`, `.site-nav .bi-en`, `.site-header__cta`, `.site-header__inner`, `.site-nav__link + .site-nav__link::before` — used consistently across the test file (Task 1), CSS edit (Task 2), and self-review references. The 421px breakpoint is consistent in both prose and the second media query.

One small caveat to flag: the build command in Task 2 Step 2 (`npx @11ty/eleventy`) assumes the CSS is passed through to `dist/` via Eleventy's passthroughCopy. If `dist/styles/main.css` doesn't get refreshed, the sanity-check `grep` in Step 2 will catch it and the executor should run a full `npm run build` instead. Both paths are accounted for in the Step 2 instructions.
