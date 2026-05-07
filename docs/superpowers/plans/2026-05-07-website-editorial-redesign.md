# Website Editorial Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the gallr marketing site (Eleventy static site under `web/`) as a confident editorial publication — oversized type, architectural section structure with 4px black rules, real exhibition imagery from Supabase, and restrained gallery-quality motion — while keeping the existing brand constraints (0px radius, single orange accent, no shadows/gradients).

**Architecture:** Eleventy 3.x SSG, no framework, no runtime data fetching. A new build-time Node script (`scripts/fetch-showcase.js`) hits Supabase via the public anon key (or falls back to a committed seed dataset) and writes `web/_data/showcase.json` for Nunjucks to consume. A new ~150-line vanilla JS module (`web/scripts/main.js`) provides scroll-reveal, kinetic word, magnetic CTA, and marquee primitives — all gated by `prefers-reduced-motion`.

**Tech Stack:** Eleventy 3.x, Nunjucks, vanilla CSS3 with custom properties, vanilla ES2022 (no framework, no bundler), Playwright 1.49 (visual acceptance — TWO projects: existing JS-disabled `chromium`, new JS-enabled `chromium-js`), pa11y 8.x.

**Spec:** `docs/superpowers/specs/2026-05-07-website-editorial-redesign-design.md`

**Branch:** `036-website-editorial-redesign` (already created off `develop`).

---

## File Structure

### New files

| Path | Responsibility |
|---|---|
| `web/scripts/fetch-showcase.js` | Build-time fetcher; writes `web/_data/showcase.json` |
| `web/scripts/showcase-seed.json` | Committed fallback dataset (12 hand-picked exhibitions) |
| `web/scripts/main.js` | Runtime motion primitives (deferred load) |
| `web/_data/site.json` | Site-wide constants (e.g., `liveCountLabel: "1,200+"`) |
| `web/_includes/now-showing.html` | Section 4a — 8-up exhibition grid |
| `web/tests/showcase.test.js` | Node-only test for `fetch-showcase.js` data shape |
| `web/tests/editorial.test.ts` | New Playwright tests (JS-enabled project) |

### Modified files

| Path | Reason |
|---|---|
| `web/styles/tokens.css` | Add type scale, motion, layout tokens |
| `web/styles/main.css` | Rewrite layout, add motion CSS, status badges, black Downloads, footer |
| `web/_includes/base.html` | Sticky header, progress bar, `<noscript>` reveal fallback, `<script>` tag, footer rebuild |
| `web/_includes/hero.html` | Editorial hero — eyebrow, oversized headline, kinetic word, hairline, magnetic CTAs, marquee |
| `web/_includes/features.html` | 3 full-width gallery-wall blocks with image pairings + status badges |
| `web/_includes/about.html` | Display-sized headline, body-lg copy |
| `web/_includes/downloads.html` | Black-inverted, oversized, magnetic CTAs only |
| `web/_data/features.js` | Drop `mockup` fields (no longer rendered) |
| `web/index.html` | Insert `{% include "now-showing.html" %}` between features and downloads |
| `web/playwright.config.ts` | Add second project `chromium-js` (JS-enabled) |
| `web/package.json` | Wire `fetch-showcase.js` into the build |
| `web/.gitignore` | Add `_data/showcase.json` |
| `web/tests/smoke.test.ts` | Update T020 to point at the new section structure |

### Deleted files

| Path | Reason |
|---|---|
| `web/_includes/card-mockup.html` | The old card mockups are replaced by real exhibition images |

---

## Branch Setup

This plan is executed on branch `036-website-editorial-redesign`, already created off `develop`. The spec commit is `75d16f4`. Implementer subagents will create commits on this branch.

If executing tasks fresh, verify:

```bash
git -C /Users/hanshin/Documents/Projects/gallr branch --show-current
# Expected: 036-website-editorial-redesign

git -C /Users/hanshin/Documents/Projects/gallr log --oneline -1
# Expected: 75d16f4 spec: website editorial redesign
```

---

## Pre-flight: Baseline Note

The existing test `US2 — T020` in `web/tests/smoke.test.ts:53` asserts `#features` has a `4px solid black top border`. This currently FAILS on `develop` (the Korean-forward redesign removed that border without updating the test). The new design re-introduces the 4px rule but as a `border-top` on `#features` only when it sits below another section, AND adds 4px rules between feature blocks. Task 11 fixes this naturally; Task 0 just captures the baseline.

---

## Task 0: Capture baseline + bootstrap test infra

**Goal:** Document the failing baseline test, add the JS-enabled Playwright project, and add a placeholder editorial test file (one passing test) so subsequent tasks have somewhere to add tests.

**Files:**
- Modify: `web/playwright.config.ts`
- Create: `web/tests/editorial.test.ts`

- [ ] **Step 1: Verify baseline**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm run build && npx playwright test --reporter=line 2>&1 | tail -5
```

Expected: 1 failed (`T020`), 8 passed. Note this in your subagent report — it's pre-existing.

- [ ] **Step 2: Add `chromium-js` project to playwright.config.ts**

Replace `web/playwright.config.ts` contents:

```ts
import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./tests",
  testMatch: "**/*.test.ts",
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,

  use: {
    baseURL: "http://localhost:4242",
  },

  // Auto-start a static file server against the built dist/
  webServer: {
    command: "npx serve dist -l 4242 --no-clipboard",
    url: "http://localhost:4242",
    reuseExistingServer: false,
    timeout: 30000,
  },

  projects: [
    {
      // Existing structural smoke tests run with JS disabled — proves the
      // site is useful without runtime JS.
      name: "chromium",
      testMatch: "**/smoke.test.ts",
      use: { ...devices["Desktop Chrome"], javaScriptEnabled: false },
    },
    {
      // Editorial tests verify motion primitives, kinetic word, sticky
      // header, etc. — these need JS enabled.
      name: "chromium-js",
      testMatch: "**/editorial.test.ts",
      use: { ...devices["Desktop Chrome"], javaScriptEnabled: true },
    },
  ],
});
```

- [ ] **Step 3: Create the editorial test file with one passing canary test**

Create `web/tests/editorial.test.ts`:

```ts
import { test, expect } from "@playwright/test";

// All tests in this file run with JavaScript ENABLED (configured in
// playwright.config.ts under the "chromium-js" project). They verify
// motion primitives, kinetic word, sticky header, scroll reveals, etc.
//
// Reduced-motion tests use:
//   await page.emulateMedia({ reducedMotion: "reduce" });
//
// Tests are intentionally written before each implementation task; they
// FAIL on the current scaffold and PASS after the corresponding task.

test("editorial canary — page loads with JS enabled", async ({ page }) => {
  await page.goto("/");
  const isJsEnabled = await page.evaluate(() => typeof document !== "undefined");
  expect(isJsEnabled).toBe(true);
});
```

- [ ] **Step 4: Run both projects, verify canary passes and baseline failure persists**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm run build && npx playwright test --reporter=line 2>&1 | tail -10
```

Expected: 9 passed in `chromium-js` project (1) + `chromium` project (9, with T020 still failing → 1 failed). Total: 1 failed, 9 passed.

- [ ] **Step 5: Commit**

```bash
git add web/playwright.config.ts web/tests/editorial.test.ts
git commit -m "test: add chromium-js Playwright project for motion-driven tests

The existing chromium project keeps javaScriptEnabled: false to prove
the site degrades gracefully without runtime JS. The new chromium-js
project enables JS for upcoming motion + kinetic word + scroll reveal
tests."
```

---

## Task 1: New design tokens

**Goal:** Add the new type scale, motion easing, layout, and color tokens that the rest of the redesign depends on. Pure additive change — no existing tokens removed.

**Files:**
- Modify: `web/styles/tokens.css`
- Test: `web/tests/editorial.test.ts`

- [ ] **Step 1: Write failing tests**

Append to `web/tests/editorial.test.ts`:

```ts
test("Task 1 — :root exposes editorial type scale tokens", async ({ page }) => {
  await page.goto("/");
  const tokens = await page.evaluate(() => {
    const root = getComputedStyle(document.documentElement);
    return {
      display: root.getPropertyValue("--type-display").trim(),
      headline: root.getPropertyValue("--type-headline").trim(),
      eyebrow: root.getPropertyValue("--type-eyebrow").trim(),
      eyebrowTracking: root.getPropertyValue("--type-eyebrow-tracking").trim(),
      bodyLg: root.getPropertyValue("--type-body-lg").trim(),
      easeGallery: root.getPropertyValue("--ease-gallery").trim(),
      durationMed: root.getPropertyValue("--duration-med").trim(),
      space3xl: root.getPropertyValue("--space-3xl").trim(),
      maxWidth: root.getPropertyValue("--max-width").trim(),
      inkOnDarkSecondary: root
        .getPropertyValue("--color-ink-on-dark-secondary")
        .trim()
        .toLowerCase(),
      typeDisplaySm: root.getPropertyValue("--type-display-sm").trim(),
    };
  });

  expect(tokens.display).toContain("clamp(");
  expect(tokens.headline).toContain("clamp(");
  expect(tokens.eyebrow).toBe("0.6875rem");
  expect(tokens.eyebrowTracking).toBe("0.2em");
  expect(tokens.bodyLg).toContain("clamp(");
  expect(tokens.easeGallery).toBe("cubic-bezier(0.16, 1, 0.3, 1)");
  expect(tokens.durationMed).toBe("500ms");
  expect(tokens.space3xl).toBe("160px");
  expect(tokens.maxWidth).toBe("1280px");
  expect(tokens.inkOnDarkSecondary).toBe("#a0a0a0");
  expect(tokens.typeDisplaySm).toContain("clamp(");
});
```

- [ ] **Step 2: Run test, verify it fails**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm run build && npx playwright test --project=chromium-js --reporter=line tests/editorial.test.ts 2>&1 | tail -5
```

Expected: FAIL — tokens are empty strings.

- [ ] **Step 3: Add tokens**

In `web/styles/tokens.css`, find the `:root {` block and replace it with:

```css
:root {
  /* Color — monochrome base + single orange accent */
  --color-ink:           #000000;
  --color-paper:         #ffffff;
  --color-paper-alt:     #f5f5f5;
  --color-ink-secondary: #525252;
  --color-accent:        #FF5400;

  /* Black-section secondary text (matches DESIGN.md dark mode onSurfaceVariant). */
  --color-ink-on-dark-secondary: #A0A0A0;

  /* Typography — neo-grotesque sans-serif throughout */
  --font-display: "Inter", system-ui, -apple-system, Arial, sans-serif;
  --font-meta:    "Inter", system-ui, -apple-system, Arial, sans-serif;

  /* Editorial type scale */
  --type-display:           clamp(3.5rem, 11vw, 9rem);
  --type-display-sm:        clamp(2.5rem, 7vw, 5rem);
  --type-headline:          clamp(2rem, 5vw, 4rem);
  --type-eyebrow:           0.6875rem;
  --type-eyebrow-tracking:  0.2em;
  --type-body-lg:           clamp(1.125rem, 1.6vw, 1.375rem);
  --type-body:              1rem;
  --type-meta:              0.75rem;

  /* Shape — 0px everywhere, no exceptions */
  --radius: 0px;

  /* Borders */
  --border-ink:      1px solid var(--color-ink);
  --border-section:  4px solid var(--color-ink);
  --border-hairline: 1px solid var(--color-ink-secondary);

  /* Spacing scale */
  --space-xs:  4px;
  --space-sm:  8px;
  --space-md:  16px;
  --space-lg:  32px;
  --space-xl:  64px;
  --space-2xl: 96px;
  --space-3xl: 160px;
  --space-4xl: 240px;

  /* Motion */
  --ease-gallery:  cubic-bezier(0.16, 1, 0.3, 1);
  --duration-fast: 200ms;
  --duration-med:  500ms;
  --duration-slow: 800ms;

  /* Layout */
  --max-width:          1280px;
  --page-padding-x:     var(--space-md);
}
```

(Keep the `@media (min-width: 768px)` and `@media (min-width: 1024px)` blocks below — they only override `--page-padding-x` and stay as-is.)

- [ ] **Step 4: Run test, verify it passes**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm run build && npx playwright test --project=chromium-js --reporter=line tests/editorial.test.ts 2>&1 | tail -5
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add web/styles/tokens.css web/tests/editorial.test.ts
git commit -m "feat(tokens): add editorial type scale, motion, dark-section, layout tokens

New tokens are additive — existing color, font, radius, border, and
spacing tokens are unchanged. Brings:
- editorial type scale (display 3.5–9rem, headline 2–4rem, eyebrow,
  body-lg)
- motion (cubic-bezier ease-gallery, fast/med/slow durations)
- dark-section secondary ink (#A0A0A0 from DESIGN.md dark mode)
- max-width widened to 1280px (was 960px)
- space-3xl 160px, space-4xl 240px"
```

---

## Task 2: `site.json` — site-wide constants

**Goal:** Add `web/_data/site.json` so Nunjucks can render `liveCountLabel` from a single source of truth.

**Files:**
- Create: `web/_data/site.json`

- [ ] **Step 1: Create site.json**

Create `web/_data/site.json`:

```json
{
  "liveCountLabel": "1,200+",
  "city": "Seoul",
  "cityKo": "서울",
  "appStoreUrl": "https://apps.apple.com/kr/app/gallr-%EA%B0%A4%EB%9F%AC-%EC%A0%84%EC%8B%9C-%EC%A0%95%EB%B3%B4/id6760855059",
  "googlePlayUrl": "https://play.google.com/store/apps/details?id=com.gallr.app"
}
```

- [ ] **Step 2: Verify Eleventy picks it up**

Add a temporary debug to `web/_includes/base.html` AFTER `<header>` to verify Nunjucks reads `site.liveCountLabel`. Open `web/_includes/base.html` and find:

```html
  <main id="main-content">
    {{ content | safe }}
  </main>
```

Temporarily add ABOVE that line:

```html
  <p data-debug-site>{{ site.liveCountLabel }}</p>
```

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm run build && grep "data-debug-site" dist/index.html
```

Expected: `<p data-debug-site>1,200+</p>` is printed.

- [ ] **Step 3: Remove the debug line**

Open `web/_includes/base.html` and DELETE the temporary `<p data-debug-site>...` line you added in Step 2. Re-run the build to confirm it's gone:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm run build && grep "data-debug-site" dist/index.html | wc -l
```

Expected: `0`.

- [ ] **Step 4: Commit**

```bash
git add web/_data/site.json
git commit -m "feat(data): add site.json for site-wide constants

liveCountLabel ('1,200+') will be rendered in the hero count caption
and the now-showing CTA. Centralized so it can be updated in one place
when the catalog grows. Also moves the App Store and Google Play URLs
from the templates into a single source of truth."
```

---

## Task 3: Showcase seed dataset

**Goal:** Commit a 12-exhibition fallback dataset so the site always builds, even without Supabase access.

**Files:**
- Create: `web/scripts/showcase-seed.json`

- [ ] **Step 1: Create the seed file**

Create `web/scripts/showcase-seed.json`. The structure must match the spec's `web/_data/showcase.json` schema. Use real Korean gallery names; `coverImageUrl` uses placeholder paths in the public Supabase bucket. This is the build-time fallback — no live data dependency.

```json
{
  "fetchedAt": "2026-05-07T00:00:00Z",
  "source": "seed",
  "exhibitions": [
    {
      "id": "seed-01",
      "titleKo": "리움: 소장품 특별전",
      "titleEn": "Leeum: Selections from the Collection",
      "venueKo": "리움미술관",
      "venueEn": "Leeum Museum of Art",
      "openingDate": "2026-01-15",
      "closingDate": "2026-04-28",
      "coverImageUrl": "/showcase/seed-01.jpg",
      "status": "ongoing",
      "statusLabelKo": null
    },
    {
      "id": "seed-02",
      "titleKo": "추상 기하학의 세계",
      "titleEn": "Worlds of Abstract Geometry",
      "venueKo": "아모레퍼시픽미술관",
      "venueEn": "Amorepacific Museum of Art",
      "openingDate": "2026-03-03",
      "closingDate": "2026-06-12",
      "coverImageUrl": "/showcase/seed-02.jpg",
      "status": "ongoing",
      "statusLabelKo": null
    },
    {
      "id": "seed-03",
      "titleKo": "사진, 지금",
      "titleEn": "Photography, Now",
      "venueKo": "국제갤러리",
      "venueEn": "Kukje Gallery",
      "openingDate": "2026-04-01",
      "closingDate": "2026-05-12",
      "coverImageUrl": "/showcase/seed-03.jpg",
      "status": "closing-soon",
      "statusLabelKo": "종료 임박"
    },
    {
      "id": "seed-04",
      "titleKo": "한국 단색화의 계보",
      "titleEn": "Lineages of Korean Dansaekhwa",
      "venueKo": "국립현대미술관 서울",
      "venueEn": "MMCA Seoul",
      "openingDate": "2026-02-20",
      "closingDate": "2026-07-15",
      "coverImageUrl": "/showcase/seed-04.jpg",
      "status": "ongoing",
      "statusLabelKo": null
    },
    {
      "id": "seed-05",
      "titleKo": "물질의 시간",
      "titleEn": "The Time of Matter",
      "venueKo": "PKM 갤러리",
      "venueEn": "PKM Gallery",
      "openingDate": "2026-04-10",
      "closingDate": "2026-05-30",
      "coverImageUrl": "/showcase/seed-05.jpg",
      "status": "ongoing",
      "statusLabelKo": null
    },
    {
      "id": "seed-06",
      "titleKo": "도시와 풍경",
      "titleEn": "Cities and Landscapes",
      "venueKo": "갤러리현대",
      "venueEn": "Gallery Hyundai",
      "openingDate": "2026-03-25",
      "closingDate": "2026-05-10",
      "coverImageUrl": "/showcase/seed-06.jpg",
      "status": "closing-soon",
      "statusLabelKo": "종료 임박"
    },
    {
      "id": "seed-07",
      "titleKo": "비물질적 풍경",
      "titleEn": "Immaterial Landscapes",
      "venueKo": "송원아트센터",
      "venueEn": "Songwon Art Center",
      "openingDate": "2026-02-01",
      "closingDate": "2026-06-30",
      "coverImageUrl": "/showcase/seed-07.jpg",
      "status": "ongoing",
      "statusLabelKo": null
    },
    {
      "id": "seed-08",
      "titleKo": "조각의 안과 밖",
      "titleEn": "Inside and Outside Sculpture",
      "venueKo": "학고재",
      "venueEn": "Hakgojae Gallery",
      "openingDate": "2026-04-05",
      "closingDate": "2026-08-20",
      "coverImageUrl": "/showcase/seed-08.jpg",
      "status": "ongoing",
      "statusLabelKo": null
    },
    {
      "id": "seed-09",
      "titleKo": "회화의 서사",
      "titleEn": "Narratives of Painting",
      "venueKo": "아라리오갤러리",
      "venueEn": "Arario Gallery",
      "openingDate": "2026-03-15",
      "closingDate": "2026-06-08",
      "coverImageUrl": "/showcase/seed-09.jpg",
      "status": "ongoing",
      "statusLabelKo": null
    },
    {
      "id": "seed-10",
      "titleKo": "한지의 새로운 가능성",
      "titleEn": "New Possibilities of Hanji",
      "venueKo": "예화랑",
      "venueEn": "Yehwarang Gallery",
      "openingDate": "2026-04-22",
      "closingDate": "2026-05-12",
      "coverImageUrl": "/showcase/seed-10.jpg",
      "status": "closing-soon",
      "statusLabelKo": "종료 임박"
    },
    {
      "id": "seed-11",
      "titleKo": "도자, 동시대성",
      "titleEn": "Ceramics, Contemporaneity",
      "venueKo": "갤러리스케이프",
      "venueEn": "Gallery Skape",
      "openingDate": "2026-03-08",
      "closingDate": "2026-07-01",
      "coverImageUrl": "/showcase/seed-11.jpg",
      "status": "ongoing",
      "statusLabelKo": null
    },
    {
      "id": "seed-12",
      "titleKo": "흑백 사진의 본질",
      "titleEn": "The Essence of Black-and-White Photography",
      "venueKo": "한미사진미술관",
      "venueEn": "Hanmi Photography Museum",
      "openingDate": "2026-02-10",
      "closingDate": "2026-08-30",
      "coverImageUrl": "/showcase/seed-12.jpg",
      "status": "ongoing",
      "statusLabelKo": null
    }
  ]
}
```

The `coverImageUrl` paths point to `/showcase/seed-NN.jpg` — these will be empty placeholder boxes for now (no real images bundled). Section 7 of this plan adds 12 hand-drawn placeholder SVGs to `public/showcase/` so the seed paths resolve.

- [ ] **Step 2: Verify shape**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && node -e "const d = require('./scripts/showcase-seed.json'); console.log('count', d.exhibitions.length); console.log('source', d.source); console.log('first', d.exhibitions[0].titleKo); const closing = d.exhibitions.filter(e => e.status === 'closing-soon').length; console.log('closing-soon count', closing);"
```

Expected:
```
count 12
source seed
first 리움: 소장품 특별전
closing-soon count 3
```

- [ ] **Step 3: Commit**

```bash
git add web/scripts/showcase-seed.json
git commit -m "feat(showcase): add 12-exhibition seed dataset for build-time fallback

When SUPABASE_URL or SUPABASE_ANON_KEY env vars are absent (or the
fetch fails), the build copies this seed to web/_data/showcase.json so
the site always builds. 3 entries are flagged closing-soon to exercise
the orange status badge code path."
```

---

## Task 4: `fetch-showcase.js` — build-time fetcher

**Goal:** Build script that writes `web/_data/showcase.json`. Live fetch when env vars present; seed fallback otherwise.

**Files:**
- Create: `web/scripts/fetch-showcase.js`
- Create: `web/tests/showcase.test.js`
- Modify: `web/package.json`
- Modify: `web/.gitignore`

- [ ] **Step 1: Add `_data/showcase.json` to gitignore**

Append to `web/.gitignore`:

```
_data/showcase.json
```

- [ ] **Step 2: Write failing Node test**

Create `web/tests/showcase.test.js`:

```js
#!/usr/bin/env node
// Node-only test for scripts/fetch-showcase.js
// Run: node tests/showcase.test.js
//
// Tests the seed-fallback path (env vars absent). The live-fetch path
// is exercised in CI when SUPABASE_URL / SUPABASE_ANON_KEY are set.

const fs = require("fs");
const path = require("path");
const { execSync } = require("child_process");

const ROOT = path.join(__dirname, "..");
const OUTPUT = path.join(ROOT, "_data", "showcase.json");

function assert(cond, msg) {
  if (!cond) {
    console.error("✗ FAIL:", msg);
    process.exit(1);
  }
}

// Wipe any prior output
if (fs.existsSync(OUTPUT)) fs.unlinkSync(OUTPUT);

// Run the fetcher with no env vars set — must take the seed-fallback path
const env = { ...process.env };
delete env.SUPABASE_URL;
delete env.SUPABASE_ANON_KEY;

execSync(`node ${path.join(ROOT, "scripts", "fetch-showcase.js")}`, {
  cwd: ROOT,
  env,
  stdio: "inherit",
});

assert(fs.existsSync(OUTPUT), "showcase.json was not written");
const data = JSON.parse(fs.readFileSync(OUTPUT, "utf8"));

assert(data.source === "seed", `expected source 'seed', got '${data.source}'`);
assert(
  Array.isArray(data.exhibitions) && data.exhibitions.length === 12,
  "expected exhibitions.length === 12"
);

const required = [
  "id", "titleKo", "titleEn", "venueKo", "venueEn",
  "openingDate", "closingDate", "coverImageUrl", "status", "statusLabelKo",
];
for (const ex of data.exhibitions) {
  for (const k of required) {
    assert(k in ex, `exhibition ${ex.id} missing field ${k}`);
  }
}

const closing = data.exhibitions.filter((e) => e.status === "closing-soon");
assert(closing.length >= 1, "expected ≥1 closing-soon exhibition in seed");
for (const e of closing) {
  assert(e.statusLabelKo === "종료 임박", `closing-soon must have statusLabelKo '종료 임박'`);
}

console.log("✓ showcase.test.js — all assertions passed");
```

- [ ] **Step 3: Run test, verify it fails**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && node tests/showcase.test.js
```

Expected: FAIL — `Cannot find module .../scripts/fetch-showcase.js`.

- [ ] **Step 4: Implement `fetch-showcase.js`**

Create `web/scripts/fetch-showcase.js`:

```js
#!/usr/bin/env node
// Build-time fetcher for the editorial-redesign showcase.
//
// When SUPABASE_URL + SUPABASE_ANON_KEY are set in the environment, fetches
// up to 40 currently-running exhibitions with cover images from Supabase
// and randomly selects 12 (Fisher–Yates seeded by today's UTC date so each
// daily build is stable; rebuilds within a day produce the same set).
//
// When env vars are absent OR the fetch fails (network error / non-200),
// copies scripts/showcase-seed.json to _data/showcase.json. Logs which
// path was taken.

const fs = require("fs");
const path = require("path");

const ROOT = path.join(__dirname, "..");
const SEED = path.join(ROOT, "scripts", "showcase-seed.json");
const OUTPUT_DIR = path.join(ROOT, "_data");
const OUTPUT = path.join(OUTPUT_DIR, "showcase.json");

const SAMPLE_SIZE = 12;
const FETCH_LIMIT = 40;
const CLOSING_SOON_DAYS = 7;
const OPENING_SOON_DAYS = 7;

function todayIso() {
  return new Date().toISOString().slice(0, 10); // YYYY-MM-DD
}

// Mulberry32 — small deterministic PRNG seeded from a string
function seededRng(seed) {
  let h = 1779033703 ^ seed.length;
  for (let i = 0; i < seed.length; i++) {
    h = Math.imul(h ^ seed.charCodeAt(i), 3432918353);
    h = (h << 13) | (h >>> 19);
  }
  let a = h >>> 0;
  return function () {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function shuffle(arr, rng) {
  const a = arr.slice();
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(rng() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

function daysBetween(a, b) {
  const ms = new Date(b).getTime() - new Date(a).getTime();
  return Math.round(ms / (1000 * 60 * 60 * 24));
}

function classify(opening, closing, today) {
  const dToClose = daysBetween(today, closing);
  const dToOpen = daysBetween(today, opening);
  if (dToClose >= 0 && dToClose <= CLOSING_SOON_DAYS) {
    return { status: "closing-soon", statusLabelKo: "종료 임박" };
  }
  if (dToOpen > 0 && dToOpen <= OPENING_SOON_DAYS) {
    return { status: "opening-soon", statusLabelKo: "오픈 임박" };
  }
  return { status: "ongoing", statusLabelKo: null };
}

function writeFromSeed(reason) {
  console.log(`[fetch-showcase] using seed fallback (${reason})`);
  if (!fs.existsSync(OUTPUT_DIR)) fs.mkdirSync(OUTPUT_DIR, { recursive: true });
  fs.copyFileSync(SEED, OUTPUT);
  console.log(`[fetch-showcase] wrote ${OUTPUT} from seed`);
}

async function main() {
  const url = process.env.SUPABASE_URL;
  const key = process.env.SUPABASE_ANON_KEY;

  if (!url || !key) {
    writeFromSeed("env vars absent");
    return;
  }

  const today = todayIso();
  const endpoint =
    `${url}/rest/v1/exhibitions` +
    `?select=id,title_ko,title_en,venue_ko,venue_en,opening_date,closing_date,cover_image_url` +
    `&cover_image_url=not.is.null` +
    `&opening_date=lte.${today}` +
    `&closing_date=gte.${today}` +
    `&limit=${FETCH_LIMIT}`;

  let res, rows;
  try {
    res = await fetch(endpoint, {
      headers: { apikey: key, Authorization: `Bearer ${key}` },
    });
    if (!res.ok) {
      writeFromSeed(`HTTP ${res.status}`);
      return;
    }
    rows = await res.json();
  } catch (err) {
    writeFromSeed(`fetch error: ${err.message}`);
    return;
  }

  if (!Array.isArray(rows) || rows.length === 0) {
    writeFromSeed("empty result set");
    return;
  }

  const rng = seededRng(today);
  const picked = shuffle(rows, rng).slice(0, SAMPLE_SIZE);

  const exhibitions = picked.map((r) => {
    const { status, statusLabelKo } = classify(
      r.opening_date,
      r.closing_date,
      today
    );
    return {
      id: r.id,
      titleKo: r.title_ko,
      titleEn: r.title_en,
      venueKo: r.venue_ko,
      venueEn: r.venue_en,
      openingDate: r.opening_date,
      closingDate: r.closing_date,
      coverImageUrl: r.cover_image_url,
      status,
      statusLabelKo,
    };
  });

  const out = {
    fetchedAt: new Date().toISOString(),
    source: "supabase",
    exhibitions,
  };

  if (!fs.existsSync(OUTPUT_DIR)) fs.mkdirSync(OUTPUT_DIR, { recursive: true });
  fs.writeFileSync(OUTPUT, JSON.stringify(out, null, 2));
  console.log(`[fetch-showcase] wrote ${OUTPUT} from supabase (${exhibitions.length} entries)`);
}

main().catch((err) => {
  console.error("[fetch-showcase] unexpected error:", err);
  writeFromSeed("unexpected error");
});
```

- [ ] **Step 5: Wire into `npm run build`**

Replace the `"build"` and `"test"` scripts in `web/package.json` with:

```json
"build": "node scripts/copy-fonts.js && node scripts/fetch-showcase.js && eleventy",
"test": "npm run build && node tests/showcase.test.js && node tests/accessibility.test.js && npx playwright test",
```

(Other fields stay unchanged.)

- [ ] **Step 6: Run test, verify it passes**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && node tests/showcase.test.js
```

Expected: PASS — `✓ showcase.test.js — all assertions passed`.

- [ ] **Step 7: Run full build to confirm Eleventy picks up showcase.json**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm run build 2>&1 | grep -E "fetch-showcase|copy|Wrote"
```

Expected output includes `[fetch-showcase] using seed fallback (env vars absent)` and `[fetch-showcase] wrote .../_data/showcase.json from seed`.

- [ ] **Step 8: Commit**

```bash
git add web/scripts/fetch-showcase.js web/tests/showcase.test.js web/package.json web/.gitignore
git commit -m "feat(build): fetch-showcase.js — build-time exhibition data with seed fallback

Wired into 'npm run build'. When SUPABASE_URL + SUPABASE_ANON_KEY are
present, fetches up to 40 currently-running exhibitions and picks 12
deterministically (seeded by today's UTC date). When absent or fetch
fails, falls back to the committed seed dataset. Output goes to
_data/showcase.json (gitignored)."
```

---

## Task 5: Placeholder showcase images

**Goal:** Add 12 placeholder SVG images at `public/showcase/seed-NN.svg` so the seed data resolves to a real asset path during local dev / CI. These are intentionally empty boxes — replace them with real fetched images later by configuring `SUPABASE_URL` + `SUPABASE_ANON_KEY` env vars on the build host (the live fetch path is implemented in Task 4).

Note: SVGs at `.jpg` paths will not render. We use `.svg` paths in the seed and the public dir.

**Files:**
- Modify: `web/scripts/showcase-seed.json` (change `.jpg` → `.svg`)
- Create: `web/public/showcase/seed-01.svg` through `seed-12.svg`

- [ ] **Step 1: Update seed paths**

In `web/scripts/showcase-seed.json`, replace ALL twelve occurrences of:

```
"coverImageUrl": "/showcase/seed-XX.jpg"
```

with:

```
"coverImageUrl": "/showcase/seed-XX.svg"
```

(where XX is 01–12). Use a single editor session — find/replace `.jpg"` with `.svg"`.

- [ ] **Step 2: Generate 12 placeholder SVGs**

Create `web/scripts/generate-placeholder-svgs.js`:

```js
#!/usr/bin/env node
// One-shot generator for 12 monochrome placeholder SVGs.
// Each is a sharp 4:5 rectangle with the seed number top-left and a
// thin diagonal line — minimum visual weight, on-brand. Replace with
// real images when the live Supabase fetch is configured.
//
// Run: node scripts/generate-placeholder-svgs.js

const fs = require("fs");
const path = require("path");

const OUT_DIR = path.join(__dirname, "..", "public", "showcase");
if (!fs.existsSync(OUT_DIR)) fs.mkdirSync(OUT_DIR, { recursive: true });

for (let i = 1; i <= 12; i++) {
  const n = String(i).padStart(2, "0");
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 500" preserveAspectRatio="xMidYMid slice">
  <rect width="400" height="500" fill="#f5f5f5"/>
  <line x1="0" y1="500" x2="400" y2="0" stroke="#000" stroke-width="1"/>
  <text x="20" y="48" font-family="Inter, sans-serif" font-size="14" font-weight="700" fill="#000" letter-spacing="2">No. ${n}</text>
  <text x="20" y="480" font-family="Inter, sans-serif" font-size="11" font-weight="400" fill="#525252" letter-spacing="2">PLACEHOLDER</text>
</svg>
`;
  fs.writeFileSync(path.join(OUT_DIR, `seed-${n}.svg`), svg);
  console.log(`✓ wrote public/showcase/seed-${n}.svg`);
}
```

- [ ] **Step 3: Run the generator**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && node scripts/generate-placeholder-svgs.js && ls public/showcase/
```

Expected: lists 12 files `seed-01.svg` through `seed-12.svg`.

- [ ] **Step 4: Verify build copies them to dist**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm run build && ls dist/showcase/ | wc -l
```

Expected: `12`.

- [ ] **Step 5: Commit**

```bash
git add web/scripts/showcase-seed.json web/scripts/generate-placeholder-svgs.js web/public/showcase/
git commit -m "feat(showcase): add 12 monochrome placeholder SVGs for seed dataset

Each seed entry now resolves to public/showcase/seed-NN.svg — a sharp
4:5 placeholder with the seed number and a single diagonal hairline.
On-brand and minimal. Replace with real images when SUPABASE_ANON_KEY
is configured on the build host."
```

---

## Task 6: Motion primitives — `web/scripts/main.js`

**Goal:** Single deferred JS file providing `data-reveal`, `data-reveal-stagger`, `data-marquee`, `data-kinetic`, `data-magnetic`, plus the sticky-header progress bar. Reduced-motion contract honored.

**Files:**
- Create: `web/scripts/main.js`
- Modify: `web/_includes/base.html` (add `<script defer>` + `<noscript>` styles)
- Modify: `web/eleventy.config.js` (passthrough copy `scripts/`)
- Test: `web/tests/editorial.test.ts`

- [ ] **Step 1: Add scripts/ to Eleventy passthrough**

Open `web/eleventy.config.js` and find:

```js
  eleventyConfig.addPassthroughCopy({ public: "." });
  eleventyConfig.addPassthroughCopy("styles");
```

Change to:

```js
  eleventyConfig.addPassthroughCopy({ public: "." });
  eleventyConfig.addPassthroughCopy("styles");
  eleventyConfig.addPassthroughCopy("scripts/main.js");
```

- [ ] **Step 2: Write failing tests**

Append to `web/tests/editorial.test.ts`:

```ts
test("Task 6 — main.js loads and reveal observer activates", async ({ page }) => {
  await page.goto("/");
  // Confirm the script is referenced
  const hasScript = await page.evaluate(() =>
    !!document.querySelector('script[src="/scripts/main.js"]')
  );
  expect(hasScript).toBe(true);
  // Wait for DOMContentLoaded + a tick so the IO observers can register
  await page.waitForLoadState("networkidle");
  // The body gains a class once main.js initialises
  const initialised = await page.evaluate(() =>
    document.body.classList.contains("js-initialised")
  );
  expect(initialised).toBe(true);
});

test("Task 6 — reduced motion: all data-reveal elements end up revealed", async ({
  page,
}) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/");
  await page.waitForLoadState("networkidle");
  const allRevealed = await page.evaluate(() => {
    const els = document.querySelectorAll("[data-reveal]");
    if (els.length === 0) return null; // nothing to test yet
    return Array.from(els).every((el) => el.classList.contains("is-revealed"));
  });
  // Either: there are no reveal elements yet (still bootstrap), or all are revealed
  expect(allRevealed === null || allRevealed === true).toBe(true);
});
```

- [ ] **Step 3: Run tests, verify they fail**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm run build && npx playwright test --project=chromium-js --reporter=line tests/editorial.test.ts 2>&1 | tail -10
```

Expected: 2 new failures (script not present, body lacks `js-initialised`).

- [ ] **Step 4: Implement `main.js`**

Create `web/scripts/main.js`:

```js
// gallr web — motion primitives.
// Deferred. Vanilla ES2022. No dependencies.
//
// Primitives (all opt-in via data-* attributes):
//   data-reveal              fade + 24px translateY on entry
//   data-reveal-stagger      same, child <* > elements stagger 80ms
//   data-marquee             continuous left scroll, paused on hover
//   data-kinetic             cycle child <span> elements with crossfade
//   data-magnetic            magnetic hover offset (≤6px toward cursor)
//
// All motion is gated by prefers-reduced-motion. When reduce is set,
// every [data-reveal] gets is-revealed immediately and other primitives
// no-op so no element stays in a hidden start state.

(function () {
  "use strict";

  const reduceMotion = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const isTouch = matchMedia("(pointer: coarse)").matches;

  function revealAllImmediately() {
    document.querySelectorAll("[data-reveal], [data-reveal-stagger]").forEach((el) => {
      el.classList.add("is-revealed");
    });
  }

  function setupReveal() {
    const els = document.querySelectorAll("[data-reveal]");
    if (els.length === 0) return;
    const io = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            entry.target.classList.add("is-revealed");
            io.unobserve(entry.target);
          }
        }
      },
      { rootMargin: "0px 0px -10% 0px", threshold: 0.01 }
    );
    els.forEach((el) => io.observe(el));
  }

  function setupRevealStagger() {
    const containers = document.querySelectorAll("[data-reveal-stagger]");
    if (containers.length === 0) return;
    const io = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue;
          const children = Array.from(entry.target.children);
          children.forEach((child, i) => {
            child.style.transitionDelay = `${i * 80}ms`;
            requestAnimationFrame(() => child.classList.add("is-revealed"));
          });
          entry.target.classList.add("is-revealed");
          io.unobserve(entry.target);
        }
      },
      { rootMargin: "0px 0px -10% 0px", threshold: 0.01 }
    );
    containers.forEach((el) => io.observe(el));
  }

  function setupMarquee() {
    document.querySelectorAll("[data-marquee]").forEach((track) => {
      // Duplicate children once so the loop is seamless
      const inner = track.querySelector("[data-marquee-inner]");
      if (!inner) return;
      const clone = inner.cloneNode(true);
      clone.setAttribute("aria-hidden", "true");
      track.appendChild(clone);

      const duration = parseFloat(track.dataset.duration || "40") * 1000;
      let start = null;
      let paused = false;
      let pauseStart = 0;
      let pauseAccum = 0;

      track.addEventListener("mouseenter", () => {
        paused = true;
        pauseStart = performance.now();
      });
      track.addEventListener("mouseleave", () => {
        paused = false;
        pauseAccum += performance.now() - pauseStart;
      });

      function step(ts) {
        if (start === null) start = ts;
        const elapsed = ts - start - pauseAccum - (paused ? ts - pauseStart : 0);
        const progress = (elapsed % duration) / duration;
        const x = -progress * 50; // -50% = single inner width
        track.style.setProperty("--marquee-x", `${x}%`);
        requestAnimationFrame(step);
      }
      requestAnimationFrame(step);
    });
  }

  function setupKinetic() {
    document.querySelectorAll("[data-kinetic]").forEach((host) => {
      const words = Array.from(host.querySelectorAll(":scope > span"));
      if (words.length < 2) return;
      let active = 0;
      words.forEach((w, i) => {
        w.classList.toggle("is-active", i === 0);
      });
      let paused = false;
      host.addEventListener("mouseenter", () => (paused = true));
      host.addEventListener("mouseleave", () => (paused = false));
      setInterval(() => {
        if (paused) return;
        words[active].classList.remove("is-active");
        active = (active + 1) % words.length;
        words[active].classList.add("is-active");
      }, 2400);
    });
  }

  function setupMagnetic() {
    if (isTouch) return;
    document.querySelectorAll("[data-magnetic]").forEach((el) => {
      const RADIUS = 60;
      const PULL = 6;
      el.addEventListener("mousemove", (e) => {
        const rect = el.getBoundingClientRect();
        const cx = rect.left + rect.width / 2;
        const cy = rect.top + rect.height / 2;
        const dx = e.clientX - cx;
        const dy = e.clientY - cy;
        const dist = Math.sqrt(dx * dx + dy * dy);
        if (dist > RADIUS) {
          el.style.transform = "translate(0, 0)";
          return;
        }
        const k = (1 - dist / RADIUS) * PULL;
        el.style.transform = `translate(${(dx / dist) * k}px, ${(dy / dist) * k}px)`;
      });
      el.addEventListener("mouseleave", () => {
        el.style.transform = "translate(0, 0)";
      });
    });
  }

  function setupHeader() {
    const header = document.querySelector(".site-header");
    const progress = document.querySelector(".site-header__progress");
    if (!header) return;
    function onScroll() {
      const y = window.scrollY;
      header.classList.toggle("is-stuck", y > 80);
      if (progress) {
        const max = document.documentElement.scrollHeight - window.innerHeight;
        const ratio = max > 0 ? y / max : 0;
        progress.style.transform = `scaleX(${ratio})`;
      }
    }
    window.addEventListener("scroll", onScroll, { passive: true });
    onScroll();
  }

  function init() {
    document.body.classList.add("js-initialised");
    if (reduceMotion) {
      revealAllImmediately();
      // Magnetic and marquee are nice-to-have; skip them entirely.
      // Kinetic stays static (CSS shows only first .is-active word).
      setupHeader(); // header is a layout concern, not motion — keep it
      return;
    }
    setupReveal();
    setupRevealStagger();
    setupMarquee();
    setupKinetic();
    setupMagnetic();
    setupHeader();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
```

- [ ] **Step 5: Wire script + noscript into `base.html`**

Open `web/_includes/base.html`. In the `<head>` block, AFTER the existing `<link rel="stylesheet" href="/styles/main.css" />` line, add:

```html
  <noscript>
    <style>
      [data-reveal], [data-reveal-stagger] > * { opacity: 1 !important; transform: none !important; }
    </style>
  </noscript>
```

In the same `<head>` block, AFTER the favicon line, add:

```html
  <script defer src="/scripts/main.js"></script>
```

Also add the global reveal-hidden CSS to main.css in the next task — for now this script alone won't fully work without the matching CSS. Tests in this task only check that the script loads and `js-initialised` lands.

- [ ] **Step 6: Run tests, verify they pass**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm run build && npx playwright test --project=chromium-js --reporter=line tests/editorial.test.ts 2>&1 | tail -10
```

Expected: PASS for both new tests.

- [ ] **Step 7: Commit**

```bash
git add web/scripts/main.js web/_includes/base.html web/eleventy.config.js web/tests/editorial.test.ts
git commit -m "feat(motion): vanilla JS motion primitives + reduced-motion contract

main.js (~150 LoC, deferred, no deps) provides data-reveal,
data-reveal-stagger, data-marquee, data-kinetic, data-magnetic, and the
sticky-header progress bar. When prefers-reduced-motion: reduce, all
[data-reveal] elements receive is-revealed immediately so nothing
stays hidden — backed by a <noscript> <style> block as a third safety
net for JS-disabled clients."
```

---

## Task 7: Global CSS — reveal hidden state, sticky header, base typography

**Goal:** Add CSS for the reveal hidden state, sticky header, baseline editorial typography. Leaves all section-specific styles untouched (later tasks own those).

**Files:**
- Modify: `web/styles/main.css`
- Test: `web/tests/editorial.test.ts`

- [ ] **Step 1: Write failing tests**

Append to `web/tests/editorial.test.ts`:

```ts
test("Task 7 — [data-reveal] starts at opacity 0 (without reduced motion)", async ({
  page,
}) => {
  await page.emulateMedia({ reducedMotion: "no-preference" });
  await page.goto("/");
  // Force a reveal element into the DOM if none exists yet (later tasks add real ones)
  await page.evaluate(() => {
    if (document.querySelector("[data-reveal]")) return;
    const div = document.createElement("div");
    div.setAttribute("data-reveal", "");
    div.style.position = "fixed";
    div.style.top = "9999px"; // off-screen so the IO doesn't immediately reveal it
    div.id = "reveal-probe";
    document.body.appendChild(div);
  });
  const opacity = await page.evaluate(() => {
    const el = document.querySelector("#reveal-probe") || document.querySelector("[data-reveal]");
    return el ? getComputedStyle(el).opacity : null;
  });
  expect(parseFloat(opacity || "1")).toBeLessThanOrEqual(0.01);
});

test("Task 7 — sticky header gets is-stuck after 100px scroll", async ({ page }) => {
  await page.goto("/");
  await page.waitForLoadState("networkidle");
  await page.evaluate(() => window.scrollTo(0, 200));
  await page.waitForTimeout(100);
  const stuck = await page.evaluate(() =>
    document.querySelector(".site-header")?.classList.contains("is-stuck")
  );
  expect(stuck).toBe(true);
});
```

- [ ] **Step 2: Run tests, verify they fail**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm run build && npx playwright test --project=chromium-js --reporter=line tests/editorial.test.ts 2>&1 | tail -10
```

Expected: 2 new failures.

- [ ] **Step 3: Add CSS**

In `web/styles/main.css`, find the `.skip-link:focus` rule. AFTER it (before `/* ── Layout helpers ──*/`), add:

```css
/* ── Reveal hidden state (paired with [data-reveal] in main.js) ──
   Hidden until the IntersectionObserver adds .is-revealed, OR until
   prefers-reduced-motion / <noscript> overrides kick in.
   ── */

[data-reveal],
[data-reveal-stagger] > * {
  opacity: 0;
  transform: translateY(24px);
  transition: opacity var(--duration-med) var(--ease-gallery),
              transform var(--duration-med) var(--ease-gallery);
}

[data-reveal].is-revealed,
[data-reveal-stagger].is-revealed > *,
[data-reveal-stagger] > *.is-revealed {
  opacity: 1;
  transform: translateY(0);
}

@media (prefers-reduced-motion: reduce) {
  [data-reveal],
  [data-reveal-stagger] > * {
    opacity: 1 !important;
    transform: none !important;
    transition: none !important;
  }
}
```

Then find the `.site-header` rule and replace it (and `.site-header__inner`) with:

```css
/* ── Site Header ─────────────────────────────────────────── */

.site-header {
  position: sticky;
  top: 0;
  z-index: 100;
  border-bottom: 1px solid transparent;
  padding: var(--space-md) 0;
  background-color: transparent;
  transition: background-color var(--duration-fast) var(--ease-gallery),
              border-color var(--duration-fast) var(--ease-gallery);
}

.site-header.is-stuck {
  background-color: var(--color-paper);
  border-bottom-color: var(--color-ink-secondary);
}

.site-header__inner {
  max-width: var(--max-width);
  margin: 0 auto;
  padding: 0 var(--space-md);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.site-header__progress {
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 1px;
  background-color: var(--color-accent);
  transform: scaleX(0);
  transform-origin: left;
}
```

- [ ] **Step 4: Add the progress bar element to base.html**

Open `web/_includes/base.html`. Find the `<header class="site-header">` block. Replace the entire `<header>...</header>` element with:

```html
  <header class="site-header">
    <div class="site-header__inner">
      <a href="/" class="site-logo" aria-label="gallr — home">
        <img src="/logos/b-arch-pin.svg" width="28" height="28" alt="" aria-hidden="true" class="site-logo__mark"/>
        <span class="site-logo__wordmark">gallr</span>
      </a>
      <a href="#downloads" class="site-header__cta" lang="ko">다운로드</a>
    </div>
    <div class="site-header__progress" aria-hidden="true"></div>
  </header>
```

- [ ] **Step 5: Style the new header CTA**

In `web/styles/main.css`, AFTER the `.site-header__progress` rule, add:

```css
.site-header__cta {
  font-family: var(--font-meta);
  font-size: var(--type-eyebrow);
  letter-spacing: var(--type-eyebrow-tracking);
  text-transform: uppercase;
  color: var(--color-ink);
  text-decoration: none;
  opacity: 0;
  pointer-events: none;
  transition: opacity var(--duration-fast) var(--ease-gallery);
}

.site-header.is-stuck .site-header__cta {
  opacity: 1;
  pointer-events: auto;
}

.site-header__cta:hover { text-decoration: underline; }
```

- [ ] **Step 6: Run tests, verify they pass**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm run build && npx playwright test --project=chromium-js --reporter=line tests/editorial.test.ts 2>&1 | tail -10
```

Expected: all editorial tests PASS.

- [ ] **Step 7: Commit**

```bash
git add web/styles/main.css web/_includes/base.html web/tests/editorial.test.ts
git commit -m "feat(layout): reveal hidden-state CSS + sticky header with progress bar

[data-reveal] now starts opacity:0 + translateY(24px) and transitions to
final state when JS adds .is-revealed; reduced-motion forces final
state via !important. Sticky header transitions to opaque on scroll
past 80px, surfaces a download CTA, and renders a 1px orange scroll
progress bar at its bottom edge."
```

---

## Task 8: Hero — eyebrow row, oversized headline, kinetic word

**Goal:** Rebuild the hero from oversized type + kinetic word + magnetic CTAs (no marquee yet — that lands in Task 9).

**Files:**
- Modify: `web/_includes/hero.html`
- Modify: `web/styles/main.css`
- Modify: `web/_includes/base.html` (year/month helper) OR use Eleventy filter
- Test: `web/tests/editorial.test.ts`

- [ ] **Step 1: Add Nunjucks date filter for `2026 / 05`**

Open `web/eleventy.config.js`. AFTER the `addPassthroughCopy` lines, add:

```js
  // Renders today's date as "YYYY / MM" — used in the hero eyebrow row.
  eleventyConfig.addShortcode("currentYearMonth", () => {
    const d = new Date();
    return `${d.getUTCFullYear()} / ${String(d.getUTCMonth() + 1).padStart(2, "0")}`;
  });
```

- [ ] **Step 2: Write failing tests**

Append to `web/tests/editorial.test.ts`:

```ts
test("Task 8 — hero has eyebrow row with FEATURED label and YYYY / MM", async ({
  page,
}) => {
  await page.goto("/");
  const meta = page.locator(".hero__meta");
  await expect(meta).toBeVisible();
  const text = (await meta.textContent()) || "";
  expect(text).toContain("FEATURED");
  expect(text).toMatch(/20\d{2} \/ \d{2}/);
});

test("Task 8 — hero h1 uses the editorial display token (≥56px)", async ({
  page,
}) => {
  await page.goto("/");
  const fontSize = await page.evaluate(() => {
    const h1 = document.querySelector(".hero__headline");
    return h1 ? parseFloat(getComputedStyle(h1).fontSize) : 0;
  });
  expect(fontSize).toBeGreaterThanOrEqual(56);
});

test("Task 8 — hero kinetic word host has 3 children, first is .is-active", async ({
  page,
}) => {
  await page.goto("/");
  await page.waitForLoadState("networkidle");
  const counts = await page.evaluate(() => {
    const host = document.querySelector(".hero__kinetic");
    if (!host) return null;
    const children = host.querySelectorAll(":scope > span");
    const active = host.querySelectorAll(":scope > span.is-active");
    return { children: children.length, active: active.length };
  });
  expect(counts).not.toBeNull();
  expect(counts!.children).toBe(3);
  expect(counts!.active).toBe(1);
});

test("Task 8 — hero CTAs have data-magnetic and link to live store URLs", async ({
  page,
}) => {
  await page.goto("/");
  const ctas = page.locator(".hero__ctas a");
  await expect(ctas).toHaveCount(2);
  const dataMagneticCount = await ctas.evaluateAll((els) =>
    els.filter((el) => el.hasAttribute("data-magnetic")).length
  );
  expect(dataMagneticCount).toBe(2);
  const hrefs = await ctas.evaluateAll((els) => els.map((el) => el.getAttribute("href")));
  expect(hrefs[0]).toContain("apps.apple.com");
  expect(hrefs[1]).toContain("play.google.com");
});
```

- [ ] **Step 3: Run tests, verify they fail**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm run build && npx playwright test --project=chromium-js --reporter=line tests/editorial.test.ts 2>&1 | tail -10
```

Expected: 4 new failures.

- [ ] **Step 4: Replace `web/_includes/hero.html`**

Replace the entire file contents with:

```html
<section id="hero" class="hero" aria-labelledby="hero-headline">
  <div class="hero__inner">
    <div class="hero__meta" data-reveal>
      <span class="hero__meta-left" lang="en">FEATURED ◦ NOW SHOWING</span>
      <span class="hero__meta-right" lang="en">{% currentYearMonth %}</span>
    </div>
    <hr class="hero__rule-thick" aria-hidden="true" />

    <h1 id="hero-headline" class="hero__headline">
      <span class="hero__headline-line" data-reveal>내 주변</span>
      <span class="hero__headline-line" data-reveal>전시를 발견하는</span>
      <span class="hero__headline-line" data-reveal>
        가장
        <span class="hero__kinetic" data-kinetic aria-live="off">
          <span class="is-active">쉬운</span>
          <span>빠른</span>
          <span>정확한</span>
        </span>
        <span class="sr-only">방법</span>
        <span aria-hidden="true">방법</span>
      </span>
    </h1>

    <p class="hero__subhead bi-en" lang="en" data-reveal>
      The easiest way to discover<br />
      exhibitions near you.
    </p>

    <hr class="hero__rule" aria-hidden="true" data-reveal />

    <nav class="hero__ctas" aria-label="gallr 다운로드" data-reveal-stagger>
      <a
        href="{{ site.appStoreUrl }}"
        class="hero__cta"
        data-magnetic
        aria-label="Download gallr on the App Store"
      >
        App Store <span class="hero__cta-arrow" aria-hidden="true">→</span>
      </a>
      <a
        href="{{ site.googlePlayUrl }}"
        class="hero__cta"
        data-magnetic
        aria-label="Get gallr on Google Play"
      >
        Google Play <span class="hero__cta-arrow" aria-hidden="true">→</span>
      </a>
    </nav>
  </div>
</section>
```

- [ ] **Step 5: Add hero CSS**

In `web/styles/main.css`, replace the entire `/* ── Hero ── */` block (from `.hero {` through `.hero__ctas {...}` inclusive) with:

```css
/* ── sr-only utility (re-used) ─── */

.sr-only {
  position: absolute;
  width: 1px; height: 1px;
  padding: 0; margin: -1px;
  overflow: hidden; clip: rect(0, 0, 0, 0);
  white-space: nowrap; border: 0;
}

/* ── Hero ────────────────────────────────────────────────── */

.hero {
  padding: var(--space-2xl) 0 var(--space-3xl);
  background-color: var(--color-paper);
  min-height: 92vh;
  display: flex;
  align-items: stretch;
}

.hero__inner {
  max-width: var(--max-width);
  margin: 0 auto;
  padding: 0 var(--page-padding-x);
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
}

.hero__meta {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  font-family: var(--font-meta);
  font-size: var(--type-eyebrow);
  letter-spacing: var(--type-eyebrow-tracking);
  text-transform: uppercase;
  color: var(--color-ink);
}

.hero__rule-thick {
  border: none;
  border-top: var(--border-section);
  margin: 0;
}

.hero__headline {
  font-family: var(--font-display);
  font-weight: 700;
  font-size: var(--type-display);
  line-height: 1.05;
  letter-spacing: -0.02em;
  color: var(--color-ink);
  margin: var(--space-md) 0 0;
}

.hero__headline-line {
  display: block;
}

.hero__kinetic {
  display: inline-block;
  position: relative;
  vertical-align: baseline;
  color: var(--color-accent);
  /* Reserve width with the longest word to avoid layout shift */
  min-width: 0.6em;
}

.hero__kinetic > span {
  display: inline-block;
  opacity: 0;
  transform: translateY(0.4em);
  transition: opacity var(--duration-fast) var(--ease-gallery),
              transform var(--duration-fast) var(--ease-gallery);
}

.hero__kinetic > span:not(.is-active) {
  position: absolute;
  left: 0;
  top: 0;
}

.hero__kinetic > span.is-active {
  opacity: 1;
  transform: translateY(0);
  position: relative;
}

@media (prefers-reduced-motion: reduce) {
  .hero__kinetic > span { transition: none; }
}

.hero__subhead {
  /* Override .bi-en defaults: the hero subhead is its own size. */
  font-size: var(--type-body-lg);
  margin-top: var(--space-md);
  line-height: 1.4;
  max-width: 28ch;
}

.hero__rule {
  border: none;
  border-top: var(--border-ink);
  width: 6rem;
  margin: var(--space-md) 0 0;
  transform-origin: left;
}

.hero__ctas {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xl);
  margin-top: var(--space-md);
}

.hero__cta {
  font-family: var(--font-display);
  font-size: var(--type-body-lg);
  font-weight: 500;
  color: var(--color-ink);
  text-decoration: none;
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 0.5em;
  padding: var(--space-sm) 0;
  transition: transform var(--duration-fast) var(--ease-gallery);
}

.hero__cta::after {
  content: "";
  position: absolute;
  left: 0; bottom: 0;
  width: 100%;
  height: 1px;
  background-color: var(--color-ink);
  transform: scaleX(0);
  transform-origin: left;
  transition: transform var(--duration-fast) var(--ease-gallery);
}

.hero__cta:hover::after { transform: scaleX(1); }

.hero__cta-arrow {
  display: inline-block;
  transition: transform var(--duration-fast) var(--ease-gallery);
}

.hero__cta:hover .hero__cta-arrow { transform: translateX(8px); }
```

- [ ] **Step 6: Run tests, verify they pass**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm run build && npx playwright test --project=chromium-js --reporter=line tests/editorial.test.ts 2>&1 | tail -15
```

Expected: all 4 new tests PASS.

- [ ] **Step 7: Run smoke tests too — make sure existing JS-disabled assertions still work**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npx playwright test --project=chromium --reporter=line 2>&1 | tail -10
```

Expected: T020 still failing (expected, gets fixed in Task 11), 8 others pass.

- [ ] **Step 8: Commit**

```bash
git add web/_includes/hero.html web/styles/main.css web/eleventy.config.js web/tests/editorial.test.ts
git commit -m "feat(hero): editorial rebuild — eyebrow row, oversized type, kinetic word

Hero now opens with FEATURED ◦ NOW SHOWING + 'YYYY / MM' eyebrow row
above a 4px black rule, then an oversized headline (clamp 3.5–9rem)
with a kinetic third line that cycles 쉬운 → 빠른 → 정확한 in orange.
English subhead sits muted below. CTAs are magnetic underlined links,
not buttons — orange button accent removed from the hero per spec."
```

---

## Task 9: Hero marquee strip

**Goal:** Add the bottom-of-hero infinite marquee of 8 random exhibition images, plus the count caption below it.

**Files:**
- Modify: `web/_includes/hero.html`
- Modify: `web/styles/main.css`
- Test: `web/tests/editorial.test.ts`

- [ ] **Step 1: Write failing tests**

Append to `web/tests/editorial.test.ts`:

```ts
test("Task 9 — hero marquee renders 8 image tiles", async ({ page }) => {
  await page.goto("/");
  const tiles = page.locator(".hero__marquee [data-marquee-inner] .hero__marquee-tile");
  await expect(tiles).toHaveCount(8);
});

test("Task 9 — hero count caption mentions Seoul + 1,200+ + 매일", async ({
  page,
}) => {
  await page.goto("/");
  const text = (await page.locator(".hero__count").textContent()) || "";
  expect(text).toContain("SEOUL");
  expect(text).toContain("1,200+");
  expect(text).toContain("매일");
});
```

- [ ] **Step 2: Run tests, verify they fail**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm run build && npx playwright test --project=chromium-js --reporter=line tests/editorial.test.ts 2>&1 | tail -10
```

Expected: 2 new failures.

- [ ] **Step 3: Append marquee + count to `hero.html`**

Open `web/_includes/hero.html`. Find the closing `</section>` of the hero (the very last line). BEFORE that closing tag, but AFTER the closing `</nav>` of `.hero__ctas`, insert (note: this content sits OUTSIDE `.hero__inner` — it's full-bleed):

```html
  </div>{# /.hero__inner #}

  <div class="hero__marquee" data-marquee data-duration="40" aria-label="현재 진행 중인 전시" data-reveal>
    <div class="hero__marquee-track">
      <div class="hero__marquee-inner" data-marquee-inner>
        {% for ex in showcase.exhibitions.slice(0, 8) %}
        <figure class="hero__marquee-tile">
          <img src="{{ ex.coverImageUrl }}" alt="{{ ex.titleKo }}, {{ ex.venueKo }}" loading="{% if loop.index <= 4 %}eager{% else %}lazy{% endif %}" />
        </figure>
        {% endfor %}
      </div>
    </div>
  </div>

  <p class="hero__count" data-reveal>
    NOW SHOWING IN SEOUL · {{ site.liveCountLabel }} 전시 · 매일 업데이트
  </p>
```

⚠ Note: this rearranges the file structure — the original `</div>{# /.hero__inner #}` was at the bottom; you're moving it up. After your edit, the structure of `hero.html` should be:

```
<section id="hero">
  <div class="hero__inner">
    ... eyebrow, rule, h1, subhead, hero__rule, ctas ...
  </div>
  <div class="hero__marquee">...</div>
  <p class="hero__count">...</p>
</section>
```

Verify by reading the file back and confirming `.hero__inner` closes BEFORE `.hero__marquee`.

- [ ] **Step 4: Add marquee + count CSS**

In `web/styles/main.css`, AFTER the last `.hero__cta-arrow` rule (end of the Hero block), add:

```css
.hero__marquee {
  margin-top: var(--space-2xl);
  border-top: var(--border-section);
  border-bottom: var(--border-hairline);
  padding: var(--space-md) 0;
  overflow: hidden;
  /* full-bleed: escapes .hero__inner padding */
  position: relative;
}

.hero__marquee-track {
  width: 100%;
  overflow: hidden;
}

.hero__marquee-inner {
  display: flex;
  gap: var(--space-md);
  width: max-content;
  transform: translateX(var(--marquee-x, 0));
  will-change: transform;
}

.hero__marquee-tile {
  flex: 0 0 auto;
  width: 140px;
  height: 180px;
  margin: 0;
  border: var(--border-ink);
  background-color: var(--color-paper-alt);
  overflow: hidden;
}

.hero__marquee-tile img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

@media (prefers-reduced-motion: reduce) {
  .hero__marquee-inner { transform: none !important; }
}

.hero__count {
  max-width: var(--max-width);
  margin: var(--space-md) auto 0;
  padding: 0 var(--page-padding-x);
  font-family: var(--font-meta);
  font-size: var(--type-eyebrow);
  letter-spacing: var(--type-eyebrow-tracking);
  text-transform: uppercase;
  color: var(--color-ink-secondary);
}
```

- [ ] **Step 5: Run build to confirm Eleventy slices the array correctly**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm run build && grep -c "hero__marquee-tile" dist/index.html
```

Expected: `8`.

If you see a Nunjucks error about `slice` being unknown, change the template line to:

```html
{% for ex in showcase.exhibitions | slice(8) %}
```

…and verify it now produces 8 tiles. (Eleventy's bundled Nunjucks supports the `slice` filter natively.)

- [ ] **Step 6: Run tests, verify they pass**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm run build && npx playwright test --project=chromium-js --reporter=line tests/editorial.test.ts 2>&1 | tail -10
```

Expected: 2 new tests PASS.

- [ ] **Step 7: Commit**

```bash
git add web/_includes/hero.html web/styles/main.css web/tests/editorial.test.ts
git commit -m "feat(hero): marquee strip of 8 live-running exhibitions + count caption

Bottom of hero now carries an infinite-scroll marquee with the first 8
exhibitions from showcase.exhibitions (paused on hover, static when
prefers-reduced-motion). Caption below reads
'NOW SHOWING IN SEOUL · 1,200+ 전시 · 매일 업데이트' — the 1,200+
figure is sourced from site.json so it stays a single source of truth."
```

---

## Task 10: Status badge component

**Goal:** Add the orange `종료 임박` / `오픈 임박` badge as a reusable component used in features (Task 11) and now-showing grid (Task 12).

**Files:**
- Modify: `web/styles/main.css`
- Test: `web/tests/editorial.test.ts`

- [ ] **Step 1: Write a failing test**

Append to `web/tests/editorial.test.ts`:

```ts
test("Task 10 — .status-badge--accent uses the orange accent color", async ({
  page,
}) => {
  await page.goto("/");
  // Inject a probe so the test passes regardless of which sections render badges
  await page.evaluate(() => {
    const span = document.createElement("span");
    span.className = "status-badge status-badge--accent";
    span.textContent = "종료 임박";
    span.id = "badge-probe";
    document.body.appendChild(span);
  });
  const color = await page.evaluate(() => {
    const el = document.getElementById("badge-probe");
    return el ? getComputedStyle(el).color : null;
  });
  // #FF5400 = rgb(255, 84, 0)
  expect(color).toBe("rgb(255, 84, 0)");
});
```

- [ ] **Step 2: Run test, verify it fails**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm run build && npx playwright test --project=chromium-js --reporter=line tests/editorial.test.ts 2>&1 | tail -10
```

Expected: FAIL — color matches default (likely `rgb(0, 0, 0)`).

- [ ] **Step 3: Add CSS**

In `web/styles/main.css`, AFTER the `.hero__count` rule, add:

```css
/* ── Status badge (closing-soon / opening-soon) ─── */

.status-badge {
  display: inline-block;
  font-family: var(--font-meta);
  font-size: var(--type-eyebrow);
  letter-spacing: var(--type-eyebrow-tracking);
  text-transform: uppercase;
  font-weight: 500;
}

.status-badge--accent {
  color: var(--color-accent);
}
```

- [ ] **Step 4: Run test, verify it passes**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm run build && npx playwright test --project=chromium-js --reporter=line tests/editorial.test.ts 2>&1 | tail -10
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add web/styles/main.css web/tests/editorial.test.ts
git commit -m "feat(badge): .status-badge component for closing-soon / opening-soon"
```

---

## Task 11: Features as gallery wall

**Goal:** Rewrite features section as 3 full-width gallery-wall blocks, each pairing big text (left) with a real exhibition image (right). 4px black rules between blocks. Status badges where applicable. Replaces card-mockup.html (deleted).

**Files:**
- Modify: `web/_includes/features.html`
- Modify: `web/_data/features.js`
- Delete: `web/_includes/card-mockup.html`
- Modify: `web/styles/main.css`
- Modify: `web/tests/smoke.test.ts` (fix T020)
- Test: `web/tests/editorial.test.ts`

- [ ] **Step 1: Write failing tests**

Append to `web/tests/editorial.test.ts`:

```ts
test("Task 11 — features renders 3 .feature-block elements", async ({ page }) => {
  await page.goto("/");
  const blocks = page.locator("#features .feature-block");
  await expect(blocks).toHaveCount(3);
});

test("Task 11 — each feature block has eyebrow with FEATURE No. NN /", async ({ page }) => {
  await page.goto("/");
  const eyebrows = await page.locator("#features .feature-block__eyebrow").allTextContents();
  expect(eyebrows.length).toBe(3);
  for (let i = 0; i < eyebrows.length; i++) {
    expect(eyebrows[i]).toMatch(/FEATURE No\. 0\d \//);
  }
});

test("Task 11 — each feature block has an exhibition image with caption", async ({ page }) => {
  await page.goto("/");
  const figures = page.locator("#features .feature-block figure img");
  await expect(figures).toHaveCount(3);
  const captions = await page.locator("#features .feature-block figcaption").count();
  expect(captions).toBe(3);
});
```

- [ ] **Step 2: Update `smoke.test.ts` T020 (the obsolete test)**

In `web/tests/smoke.test.ts`, find the `US2 — T020` test and replace its body. Replace:

```ts
test("US2 — T020: #features section has 4px solid black top border", async ({
  page,
}) => {
  await page.goto("/");
  const borderTop = await page.evaluate(() => {
    const section = document.querySelector("#features");
    if (!section) return null;
    const style = window.getComputedStyle(section);
    return {
      width: style.borderTopWidth,
      style: style.borderTopStyle,
      color: style.borderTopColor,
    };
  });
  expect(borderTop).not.toBeNull();
  expect(borderTop!.width).toBe("4px");
  expect(borderTop!.style).toBe("solid");
  expect(borderTop!.color).toBe("rgb(0, 0, 0)");
});
```

with:

```ts
test("US2 — T020: #features section is separated from above by a 4px black rule", async ({
  page,
}) => {
  await page.goto("/");
  const rule = await page.evaluate(() => {
    const section = document.querySelector("#features");
    if (!section) return null;
    const style = window.getComputedStyle(section);
    return {
      width: style.borderTopWidth,
      styleProp: style.borderTopStyle,
      color: style.borderTopColor,
    };
  });
  expect(rule).not.toBeNull();
  expect(rule!.width).toBe("4px");
  expect(rule!.styleProp).toBe("solid");
  expect(rule!.color).toBe("rgb(0, 0, 0)");
});
```

(Same expectations, just clarified the test name and isolated the variable.)

- [ ] **Step 3: Strip mockup fields from `features.js`**

Replace `web/_data/features.js` with:

```js
// Feature entries for the gallery-wall feature section.
// Each block pairs Korean primary copy with a muted English subhead and
// a real currently-running exhibition image (resolved from
// showcase.exhibitions at render time — see _includes/features.html).
module.exports = [
  {
    id: "discovery",
    indexLabel: "FEATURE No. 01 / DISCOVERY",
    headlineKo: "내 근처\n전시 찾기",
    headlineEn: "Find exhibitions\nnear you",
    bodyKo:
      "지금 진행 중이거나 오픈 예정인 전시를 한눈에 확인하세요. 추천 전시, 에디터 픽, 그리고 이번 주 오픈·종료 전시를 큐레이션합니다.",
  },
  {
    id: "bookmarking",
    indexLabel: "FEATURE No. 02 / BOOKMARKING",
    headlineKo: "관심 전시\n저장하기",
    headlineEn: "Save what\ninterests you",
    bodyKo:
      "마음에 드는 전시를 저장해 나만의 리스트를 만들어보세요. 저장한 전시는 오프라인에서도 언제든 확인할 수 있어요.",
  },
  {
    id: "filtering",
    indexLabel: "FEATURE No. 03 / FILTERING",
    headlineKo: "원하는 기준으로\n필터링",
    headlineEn: "Filter by\nwhat matters",
    bodyKo:
      "지역, 추천, 에디터 픽, 일정별로 전시를 필터링하고 나에게 필요한 전시만 골라보세요.",
  },
];
```

- [ ] **Step 4: Replace `web/_includes/features.html`**

Replace the file contents with:

```html
<section id="features" class="features" aria-labelledby="features-heading">
  <h2 id="features-heading" class="sr-only">기능</h2>

  {% for feature in features %}
  {% set ex = showcase.exhibitions[loop.index0] %}
  <article id="{{ feature.id }}" class="feature-block">
    <div class="feature-block__inner">
      <header class="feature-block__meta">
        <span class="feature-block__eyebrow" lang="en">{{ feature.indexLabel }}</span>
        <span class="feature-block__arrow" aria-hidden="true">↗</span>
      </header>

      <div class="feature-block__grid">
        <div class="feature-block__text" data-reveal>
          <h3 class="feature-block__headline">
            {% for line in feature.headlineKo.split("\n") %}<span class="feature-block__headline-line">{{ line }}</span>{% endfor %}
          </h3>
          <p class="feature-block__subhead bi-en" lang="en">
            {% for line in feature.headlineEn.split("\n") %}{{ line }}{% if not loop.last %}<br />{% endif %}{% endfor %}
          </p>
          <p class="feature-block__body">{{ feature.bodyKo }}</p>
        </div>

        <figure class="feature-block__figure" data-reveal>
          <img
            src="{{ ex.coverImageUrl }}"
            alt="{{ ex.titleKo }}, {{ ex.venueKo }}"
            loading="lazy"
            class="feature-block__image"
          />
          <figcaption class="feature-block__caption">
            <span class="feature-block__caption-title">{{ ex.titleKo }}</span>
            <span class="feature-block__caption-venue">{{ ex.venueKo }}</span>
            {% if ex.statusLabelKo %}
            <span class="status-badge status-badge--accent">{{ ex.statusLabelKo }}</span>
            {% endif %}
          </figcaption>
        </figure>
      </div>
    </div>
  </article>
  {% endfor %}
</section>
```

- [ ] **Step 5: Delete the old card mockup include**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && rm _includes/card-mockup.html
```

- [ ] **Step 6: Replace features CSS in `main.css`**

In `web/styles/main.css`, replace the entire `/* ── Features ── */` block (from `.features {` through the last `.feature-entry__description` rule, AND the `/* ── Card Mockup ── */` block in its entirety — through the last `.card-mockup__dates` rule) with:

```css
/* ── Features (gallery-wall blocks) ─────────────────────── */

.features {
  border-top: var(--border-section);
}

.feature-block {
  border-bottom: var(--border-section);
  padding: var(--space-2xl) 0;
}

.feature-block__inner {
  max-width: var(--max-width);
  margin: 0 auto;
  padding: 0 var(--page-padding-x);
}

.feature-block__meta {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  font-family: var(--font-meta);
  font-size: var(--type-eyebrow);
  letter-spacing: var(--type-eyebrow-tracking);
  text-transform: uppercase;
  border-bottom: var(--border-hairline);
  padding-bottom: var(--space-sm);
  margin-bottom: var(--space-xl);
}

.feature-block__grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--space-xl);
}

@media (min-width: 768px) {
  .feature-block__grid {
    grid-template-columns: 1.1fr 1fr;
    gap: var(--space-2xl);
    align-items: start;
  }
}

.feature-block__text {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.feature-block__headline {
  font-family: var(--font-display);
  font-weight: 700;
  font-size: var(--type-headline);
  line-height: 1.05;
  letter-spacing: -0.02em;
  color: var(--color-ink);
  margin: 0;
}

.feature-block__headline-line { display: block; }

.feature-block__subhead {
  font-size: var(--type-body-lg);
  line-height: 1.4;
  margin: 0;
}

.feature-block__body {
  font-family: var(--font-display);
  font-size: var(--type-body);
  line-height: 1.7;
  color: var(--color-ink);
  max-width: 38ch;
}

.feature-block__figure {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.feature-block__image {
  width: 100%;
  aspect-ratio: 4 / 5;
  object-fit: cover;
  border: var(--border-ink);
  background-color: var(--color-paper-alt);
  display: block;
}

.feature-block__caption {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
  align-items: baseline;
  font-family: var(--font-meta);
  font-size: var(--type-eyebrow);
  letter-spacing: var(--type-eyebrow-tracking);
  text-transform: uppercase;
}

.feature-block__caption-title {
  color: var(--color-ink);
  font-weight: 500;
}

.feature-block__caption-venue {
  color: var(--color-ink-secondary);
}
```

- [ ] **Step 7: Run all tests**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm run build && npx playwright test --reporter=line 2>&1 | tail -15
```

Expected: ALL pass — both editorial tests and the (now-fixed) T020 in smoke.test.ts.

- [ ] **Step 8: Commit**

```bash
git add web/_includes/features.html web/_includes/card-mockup.html web/_data/features.js web/styles/main.css web/tests/smoke.test.ts web/tests/editorial.test.ts
git commit -m "feat(features): gallery-wall blocks with real exhibition imagery

Three full-width feature blocks separated by 4px black rules. Each pairs
big Korean primary copy + muted English subhead + body paragraph (left)
with a real currently-running exhibition image + caption + optional
orange status badge (right). card-mockup.html is removed; the
discovery/bookmarking/filtering identifiers + rendering shape are
unchanged so existing smoke tests keep passing. T020 updated to assert
the section's top rule (the previous test was already failing on
develop because the Korean-forward redesign removed border-top)."
```

---

## Task 12: Now Showing grid section (`now-showing.html`)

**Goal:** New 8-tile section between features and downloads.

**Files:**
- Create: `web/_includes/now-showing.html`
- Modify: `web/index.html` (insert include)
- Modify: `web/styles/main.css`
- Test: `web/tests/editorial.test.ts`

- [ ] **Step 1: Write failing tests**

Append to `web/tests/editorial.test.ts`:

```ts
test("Task 12 — #now-showing renders 8 tiles", async ({ page }) => {
  await page.goto("/");
  await expect(page.locator("#now-showing .grid-tile")).toHaveCount(8);
});

test("Task 12 — #now-showing CTA links to App Store on iOS UA", async ({
  browser,
}) => {
  const context = await browser.newContext({
    userAgent:
      "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15",
    javaScriptEnabled: true,
  });
  const page = await context.newPage();
  await page.goto("/");
  await page.waitForLoadState("networkidle");
  // Click resolves the runtime href
  const href = await page.evaluate(() => {
    const a = document.querySelector("#now-showing .now-showing__cta") as HTMLAnchorElement | null;
    return a ? a.href : null;
  });
  expect(href).toContain("apps.apple.com");
  await context.close();
});
```

- [ ] **Step 2: Run tests, verify they fail**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm run build && npx playwright test --project=chromium-js --reporter=line tests/editorial.test.ts 2>&1 | tail -10
```

Expected: 2 new failures.

- [ ] **Step 3: Create `web/_includes/now-showing.html`**

```html
<section id="now-showing" class="now-showing" aria-labelledby="now-showing-heading">
  <div class="now-showing__inner">
    <header class="now-showing__meta">
      <h2 id="now-showing-heading" class="now-showing__heading" lang="en">NOW SHOWING</h2>
      <span class="now-showing__date" lang="en">{% currentYearMonth %}</span>
    </header>

    <div class="now-showing__grid" data-reveal-stagger>
      {% for ex in showcase.exhibitions | slice(8) %}
      <figure class="grid-tile">
        <div class="grid-tile__image-wrap">
          <img
            class="grid-tile__image"
            src="{{ ex.coverImageUrl }}"
            alt="{{ ex.titleKo }}, {{ ex.venueKo }}"
            loading="lazy"
          />
          {% if ex.statusLabelKo %}
          <span class="grid-tile__badge status-badge status-badge--accent">{{ ex.statusLabelKo }}</span>
          {% endif %}
        </div>
        <figcaption class="grid-tile__caption">
          <span class="grid-tile__title">{{ ex.titleKo }}</span>
          <span class="grid-tile__venue">{{ ex.venueKo }}</span>
          <span class="grid-tile__dates">{{ ex.openingDate }} — {{ ex.closingDate }}</span>
        </figcaption>
      </figure>
      {% endfor %}
    </div>

    <div class="now-showing__footer">
      <a
        class="now-showing__cta"
        data-magnetic
        href="{{ site.googlePlayUrl }}"
        data-app-store="{{ site.appStoreUrl }}"
        data-google-play="{{ site.googlePlayUrl }}"
        aria-label="앱에서 더 많은 전시 보기"
      >
        앱에서 {{ site.liveCountLabel }}개 더 보기
        <span class="now-showing__cta-arrow" aria-hidden="true">→</span>
      </a>
    </div>
  </div>
</section>
```

- [ ] **Step 4: Add UA-aware CTA to `main.js`**

Open `web/scripts/main.js`. Find the `function setupHeader()` line and insert this function ABOVE it:

```js
  function setupNowShowingCta() {
    const cta = document.querySelector(".now-showing__cta");
    if (!cta) return;
    const isIos = /iPad|iPhone|iPod/.test(navigator.userAgent);
    const target = isIos ? cta.dataset.appStore : cta.dataset.googlePlay;
    if (target) cta.setAttribute("href", target);
  }
```

Then in `init()`, AFTER the `setupHeader();` call inside the `if (reduceMotion)` branch (the one followed by `return;`), add a new line:

```js
    setupNowShowingCta();
```

…and at the very end of `init()`, AFTER the second `setupHeader();` (the one outside the reduced-motion branch), add another new line:

```js
  setupNowShowingCta();
```

So the CTA gets its UA-aware URL regardless of motion preference. After both edits, the function should look roughly like:

```js
  function init() {
    document.body.classList.add("js-initialised");
    if (reduceMotion) {
      revealAllImmediately();
      setupHeader();
      setupNowShowingCta();
      return;
    }
    setupReveal();
    setupRevealStagger();
    setupMarquee();
    setupKinetic();
    setupMagnetic();
    setupHeader();
    setupNowShowingCta();
  }
```

- [ ] **Step 5: Insert the include into `index.html`**

Open `web/index.html`. Replace its contents with:

```html
---
layout: base.html
---

{% include "hero.html" %}
{% include "features.html" %}
{% include "now-showing.html" %}
{% include "downloads.html" %}
{% include "about.html" %}
```

- [ ] **Step 6: Add now-showing CSS**

In `web/styles/main.css`, AFTER the features block, add:

```css
/* ── Now Showing grid ─────────────────────────────────────── */

.now-showing {
  border-top: var(--border-section);
  padding: var(--space-2xl) 0;
  background-color: var(--color-paper);
}

.now-showing__inner {
  max-width: var(--max-width);
  margin: 0 auto;
  padding: 0 var(--page-padding-x);
}

.now-showing__meta {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  border-bottom: var(--border-section);
  padding-bottom: var(--space-md);
  margin-bottom: var(--space-xl);
}

.now-showing__heading {
  font-family: var(--font-meta);
  font-size: var(--type-eyebrow);
  letter-spacing: var(--type-eyebrow-tracking);
  text-transform: uppercase;
  color: var(--color-ink);
  margin: 0;
  font-weight: 500;
}

.now-showing__date {
  font-family: var(--font-meta);
  font-size: var(--type-eyebrow);
  letter-spacing: var(--type-eyebrow-tracking);
  text-transform: uppercase;
  color: var(--color-ink-secondary);
}

.now-showing__grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--space-lg);
}

@media (min-width: 1024px) {
  .now-showing__grid {
    grid-template-columns: repeat(4, 1fr);
  }
}

.grid-tile {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.grid-tile__image-wrap {
  position: relative;
  aspect-ratio: 4 / 5;
  border: var(--border-ink);
  overflow: hidden;
  background-color: var(--color-paper-alt);
}

.grid-tile__image {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  transition: transform var(--duration-med) var(--ease-gallery);
}

.grid-tile:hover .grid-tile__image { transform: scale(1.03); }

.grid-tile__badge {
  position: absolute;
  top: var(--space-sm);
  right: var(--space-sm);
  background-color: var(--color-paper);
  padding: 2px 6px;
  border: 1px solid var(--color-accent);
}

.grid-tile__caption {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.grid-tile__title {
  font-family: var(--font-display);
  font-size: 1.0625rem;
  font-weight: 500;
  color: var(--color-ink);
  line-height: 1.3;
}

.grid-tile__venue {
  font-family: var(--font-meta);
  font-size: var(--type-eyebrow);
  letter-spacing: var(--type-eyebrow-tracking);
  text-transform: uppercase;
  color: var(--color-ink-secondary);
}

.grid-tile__dates {
  font-family: var(--font-meta);
  font-size: var(--type-meta);
  color: var(--color-ink-secondary);
}

.now-showing__footer {
  margin-top: var(--space-2xl);
  padding-top: var(--space-md);
  border-top: var(--border-hairline);
  text-align: center;
}

.now-showing__cta {
  font-family: var(--font-display);
  font-size: var(--type-body-lg);
  color: var(--color-ink);
  text-decoration: none;
  display: inline-flex;
  align-items: center;
  gap: 0.5em;
  transition: transform var(--duration-fast) var(--ease-gallery);
}

.now-showing__cta::after {
  content: "";
  display: block;
  position: absolute;
}

.now-showing__cta:hover { text-decoration: underline; }

.now-showing__cta-arrow {
  transition: transform var(--duration-fast) var(--ease-gallery);
}

.now-showing__cta:hover .now-showing__cta-arrow { transform: translateX(8px); }
```

- [ ] **Step 7: Run tests, verify they pass**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm run build && npx playwright test --project=chromium-js --reporter=line tests/editorial.test.ts 2>&1 | tail -15
```

Expected: 2 new tests PASS.

- [ ] **Step 8: Commit**

```bash
git add web/_includes/now-showing.html web/index.html web/styles/main.css web/scripts/main.js web/tests/editorial.test.ts
git commit -m "feat(now-showing): 8-tile grid section between features and downloads

New section pulls all 8 hero-marquee exhibitions and presents them as
a 4-up (desktop) / 2-up (mobile) grid with title, venue, dates, and
optional orange status badge. Bottom CTA is UA-aware: iOS UA gets the
App Store URL, everything else gets Google Play. The CTA label uses
site.json liveCountLabel so the figure stays a single source of truth."
```

---

## Task 13: Downloads — black-inverted full-bleed

**Goal:** Replace the orange-button downloads section with a full-bleed black-background section, white display headline, magnetic underlined CTAs only.

**Files:**
- Modify: `web/_includes/downloads.html`
- Modify: `web/styles/main.css`
- Test: `web/tests/editorial.test.ts`

- [ ] **Step 1: Write failing tests**

Append to `web/tests/editorial.test.ts`:

```ts
test("Task 13 — #downloads has black background and white text", async ({
  page,
}) => {
  await page.goto("/");
  const styles = await page.evaluate(() => {
    const s = document.querySelector("#downloads");
    const h = document.querySelector("#downloads .downloads__headline");
    if (!s || !h) return null;
    return {
      bg: getComputedStyle(s).backgroundColor,
      headlineColor: getComputedStyle(h).color,
    };
  });
  expect(styles).not.toBeNull();
  expect(styles!.bg).toBe("rgb(0, 0, 0)");
  expect(styles!.headlineColor).toBe("rgb(255, 255, 255)");
});

test("Task 13 — #downloads has 2 magnetic CTAs and no .btn--primary", async ({
  page,
}) => {
  await page.goto("/");
  const ctas = page.locator("#downloads .downloads__cta");
  await expect(ctas).toHaveCount(2);
  const primaryBtnCount = await page.locator("#downloads .btn--primary").count();
  expect(primaryBtnCount).toBe(0);
});
```

- [ ] **Step 2: Run tests, verify they fail**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm run build && npx playwright test --project=chromium-js --reporter=line tests/editorial.test.ts 2>&1 | tail -10
```

Expected: 2 new failures.

- [ ] **Step 3: Replace `web/_includes/downloads.html`**

```html
<section id="downloads" class="downloads" aria-labelledby="downloads-heading">
  <div class="downloads__inner">
    <header class="downloads__meta">
      <span class="downloads__eyebrow" lang="en">DOWNLOAD</span>
    </header>
    <hr class="downloads__rule-thick" aria-hidden="true" />

    <h2 id="downloads-heading" class="downloads__headline" data-reveal>
      <span class="downloads__headline-line">지금</span>
      <span class="downloads__headline-line">내려받기.</span>
    </h2>

    <p class="downloads__subhead bi-en" lang="en" data-reveal>
      Available for iPhone and Android.<br />
      Free to download.
    </p>

    <hr class="downloads__rule" aria-hidden="true" data-reveal />

    <nav class="downloads__ctas" aria-label="앱 다운로드" data-reveal-stagger>
      <a
        href="{{ site.appStoreUrl }}"
        class="downloads__cta"
        data-magnetic
        aria-label="Download gallr on the App Store"
      >
        App Store <span class="downloads__cta-arrow" aria-hidden="true">→</span>
      </a>
      <a
        href="{{ site.googlePlayUrl }}"
        class="downloads__cta"
        data-magnetic
        aria-label="Get gallr on Google Play"
      >
        Google Play <span class="downloads__cta-arrow" aria-hidden="true">→</span>
      </a>
    </nav>
  </div>
</section>
```

- [ ] **Step 4: Replace downloads CSS**

In `web/styles/main.css`, replace the entire `/* ── Downloads ── */` block (from `.downloads {` through `.downloads__ctas {...}`) AND remove the two end-of-file `#downloads .btn--primary` overrides. Insert this in their place:

```css
/* ── Downloads (full-bleed black) ─────────────────────────── */

.downloads {
  background-color: var(--color-ink);
  color: var(--color-paper);
  padding: var(--space-3xl) 0;
}

.downloads__inner {
  max-width: var(--max-width);
  margin: 0 auto;
  padding: 0 var(--page-padding-x);
}

.downloads__meta {
  font-family: var(--font-meta);
  font-size: var(--type-eyebrow);
  letter-spacing: var(--type-eyebrow-tracking);
  text-transform: uppercase;
  color: var(--color-paper);
}

.downloads__rule-thick {
  border: none;
  border-top: 4px solid var(--color-paper);
  margin: var(--space-md) 0 var(--space-xl);
}

.downloads__headline {
  font-family: var(--font-display);
  font-weight: 700;
  font-size: var(--type-display);
  line-height: 1.05;
  letter-spacing: -0.02em;
  color: var(--color-paper);
  margin: 0;
}

.downloads__headline-line { display: block; }

.downloads__subhead {
  font-size: var(--type-body-lg);
  color: var(--color-ink-on-dark-secondary);
  line-height: 1.4;
  margin-top: var(--space-md);
}

.downloads__rule {
  border: none;
  border-top: 1px solid var(--color-paper);
  width: 6rem;
  margin: var(--space-xl) 0 0;
}

.downloads__ctas {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xl);
  margin-top: var(--space-md);
}

.downloads__cta {
  font-family: var(--font-display);
  font-size: var(--type-body-lg);
  font-weight: 500;
  color: var(--color-paper);
  text-decoration: none;
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 0.5em;
  padding: var(--space-sm) 0;
  transition: transform var(--duration-fast) var(--ease-gallery);
}

.downloads__cta::after {
  content: "";
  position: absolute;
  left: 0; bottom: 0;
  width: 100%;
  height: 1px;
  background-color: var(--color-paper);
  transform: scaleX(0);
  transform-origin: left;
  transition: transform var(--duration-fast) var(--ease-gallery);
}

.downloads__cta:hover::after { transform: scaleX(1); }

.downloads__cta-arrow {
  display: inline-block;
  transition: transform var(--duration-fast) var(--ease-gallery);
}

.downloads__cta:hover .downloads__cta-arrow { transform: translateX(8px); }
```

Verify the previous `#downloads .btn--primary` rules at the end of `main.css` are gone. If you see them, delete them.

- [ ] **Step 5: Run tests, verify they pass**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm run build && npx playwright test --project=chromium-js --reporter=line tests/editorial.test.ts 2>&1 | tail -10
```

Expected: 2 new tests PASS.

- [ ] **Step 6: Commit**

```bash
git add web/_includes/downloads.html web/styles/main.css web/tests/editorial.test.ts
git commit -m "feat(downloads): full-bleed black inversion with magnetic underlined CTAs

Section flips the page palette to black wall — white display headline
'지금 내려받기.', muted English subhead in #A0A0A0, two underlined → CTAs.
Removes the orange .btn--primary override. The orange accent now appears
on the page in only two places: the hero kinetic word and the
closing-soon / opening-soon status badges."
```

---

## Task 14: About + footer rebuild

**Goal:** Final two updates — display-sized about headline and 4-column footer.

**Files:**
- Modify: `web/_includes/about.html`
- Modify: `web/_includes/base.html` (footer)
- Modify: `web/styles/main.css`
- Test: `web/tests/editorial.test.ts`

- [ ] **Step 1: Write failing tests**

Append to `web/tests/editorial.test.ts`:

```ts
test("Task 14 — about headline uses display-sm token (≥40px)", async ({ page }) => {
  await page.goto("/");
  const fontSize = await page.evaluate(() => {
    const h = document.querySelector(".about__headline");
    return h ? parseFloat(getComputedStyle(h).fontSize) : 0;
  });
  expect(fontSize).toBeGreaterThanOrEqual(40);
});

test("Task 14 — footer has 4 columns at desktop width", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/");
  const cols = await page.locator(".site-footer__column").count();
  expect(cols).toBe(4);
});

test("Task 14 — footer bottom row has copyright + 'Made in Seoul'", async ({ page }) => {
  await page.goto("/");
  const text = (await page.locator(".site-footer__bottom").textContent()) || "";
  expect(text).toContain("2026");
  expect(text).toContain("Made in Seoul");
});
```

- [ ] **Step 2: Run tests, verify they fail**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm run build && npx playwright test --project=chromium-js --reporter=line tests/editorial.test.ts 2>&1 | tail -10
```

Expected: 3 new failures.

- [ ] **Step 3: Replace `web/_includes/about.html`**

```html
<section id="about" class="about" aria-labelledby="about-heading">
  <div class="about__inner">
    <header class="about__meta">
      <span class="about__eyebrow" lang="en">MISSION</span>
      <span class="about__mark" lang="en">gallr</span>
    </header>
    <hr class="about__rule-thick" aria-hidden="true" />

    <h2 id="about-heading" class="about__headline" data-reveal>
      <span>좋은 전시를</span>
      <span>너무 늦게 알게 되는 일,</span>
      <span>이제는 없도록.</span>
    </h2>

    <div class="about__body">
      <p class="about__paragraph" data-reveal>
        gallr는 국내 기관부터 갤러리, 대안공간까지 — 도시의 전시를 한곳에 모았습니다.
      </p>
      <p class="about__paragraph bi-en" lang="en" data-reveal>
        gallr is the easiest way to discover exhibitions in your city.
        Great shows come and go, and sometimes people find out too late.
        From major institutions to independent spaces, we bring the full
        picture together in one place.
      </p>
    </div>
    <hr class="about__rule" aria-hidden="true" />
  </div>
</section>
```

- [ ] **Step 4: Replace the footer in `base.html`**

Open `web/_includes/base.html`. Find the existing `<footer class="site-footer">...</footer>` block and replace it with:

```html
  <footer class="site-footer">
    <div class="site-footer__inner">
      <div class="site-footer__columns">
        <div class="site-footer__column">
          <h2 class="site-footer__heading" lang="en">gallr</h2>
          <p class="site-footer__brand-line">도시의 전시를 한곳에.</p>
        </div>
        <div class="site-footer__column">
          <h3 class="site-footer__heading" lang="en">DOWNLOAD</h3>
          <ul class="site-footer__list">
            <li><a href="{{ site.appStoreUrl }}">App Store</a></li>
            <li><a href="{{ site.googlePlayUrl }}">Google Play</a></li>
          </ul>
        </div>
        <div class="site-footer__column">
          <h3 class="site-footer__heading" lang="en">COMPANY</h3>
          <ul class="site-footer__list">
            <li><a href="#about">About</a></li>
            <li><a href="/privacy/">Privacy</a></li>
          </ul>
        </div>
        <div class="site-footer__column">
          <h3 class="site-footer__heading" lang="en">CONTACT</h3>
          <ul class="site-footer__list">
            <li><a href="mailto:hello@gallr.app">hello@gallr.app</a></li>
            <li><a href="https://instagram.com/gallr.app">Instagram</a></li>
          </ul>
        </div>
      </div>
      <div class="site-footer__bottom">
        <p class="site-footer__copy">&copy; 2026 gallr</p>
        <p class="site-footer__location" lang="en">Made in Seoul</p>
      </div>
    </div>
  </footer>
```

- [ ] **Step 5: Replace About + Footer CSS**

In `web/styles/main.css`, replace the entire `/* ── About ── */` block (from `.about {` through `.about__body p.bi-en {...}`) AND the entire `/* ── Footer ── */` block (from `.site-footer {` through `.site-footer__copy {...}`) with:

```css
/* ── About ───────────────────────────────────────────────── */

.about {
  border-top: var(--border-section);
  padding: var(--space-3xl) 0;
  background-color: var(--color-paper);
}

.about__inner {
  max-width: var(--max-width);
  margin: 0 auto;
  padding: 0 var(--page-padding-x);
}

.about__meta {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  font-family: var(--font-meta);
  font-size: var(--type-eyebrow);
  letter-spacing: var(--type-eyebrow-tracking);
  text-transform: uppercase;
  color: var(--color-ink);
}

.about__rule-thick {
  border: none;
  border-top: var(--border-section);
  margin: var(--space-md) 0 var(--space-xl);
}

.about__headline {
  font-family: var(--font-display);
  font-weight: 700;
  font-size: var(--type-display-sm);
  line-height: 1.05;
  letter-spacing: -0.02em;
  color: var(--color-ink);
  margin: 0;
}

.about__headline > span { display: block; }

.about__body {
  margin-top: var(--space-xl);
  max-width: 56ch;
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.about__paragraph {
  font-family: var(--font-display);
  font-size: var(--type-body-lg);
  line-height: 1.6;
  color: var(--color-ink);
  margin: 0;
}

.about__paragraph.bi-en {
  font-size: var(--type-body);
  color: var(--color-ink-secondary);
  margin-top: 0;
}

.about__rule {
  border: none;
  border-top: var(--border-hairline);
  margin: var(--space-2xl) 0 0;
}

/* ── Footer ──────────────────────────────────────────────── */

.site-footer {
  border-top: var(--border-hairline);
  padding: var(--space-xl) 0 var(--space-md);
  background-color: var(--color-paper);
}

.site-footer__inner {
  max-width: var(--max-width);
  margin: 0 auto;
  padding: 0 var(--page-padding-x);
}

.site-footer__columns {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--space-xl);
}

@media (min-width: 768px) {
  .site-footer__columns {
    grid-template-columns: repeat(4, 1fr);
  }
}

.site-footer__column {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.site-footer__heading {
  font-family: var(--font-meta);
  font-size: var(--type-eyebrow);
  letter-spacing: var(--type-eyebrow-tracking);
  text-transform: uppercase;
  color: var(--color-ink);
  font-weight: 500;
  margin: 0;
}

.site-footer__brand-line {
  font-family: var(--font-display);
  font-size: var(--type-body);
  color: var(--color-ink-secondary);
  line-height: 1.5;
  margin: 0;
}

.site-footer__list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.site-footer__list a {
  font-family: var(--font-display);
  font-size: var(--type-body);
  color: var(--color-ink);
  text-decoration: none;
}

.site-footer__list a:hover { text-decoration: underline; }

.site-footer__bottom {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-top: var(--space-xl);
  padding-top: var(--space-md);
  border-top: var(--border-hairline);
  font-family: var(--font-meta);
  font-size: var(--type-meta);
  color: var(--color-ink-secondary);
}

.site-footer__copy,
.site-footer__location { margin: 0; }
```

- [ ] **Step 6: Run all tests**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm test 2>&1 | tail -25
```

Expected: ALL pass — `npm test` runs the build, showcase test, accessibility test, and Playwright (both projects).

- [ ] **Step 7: Commit**

```bash
git add web/_includes/about.html web/_includes/base.html web/styles/main.css web/tests/editorial.test.ts
git commit -m "feat(about,footer): display-sized about headline + 4-column footer

About now opens with a MISSION/gallr eyebrow row, a 4px black rule, and
a 3-line display-sm headline ('좋은 전시를 너무 늦게 알게 되는 일, 이제는
없도록.'). Footer becomes a 4-column grid (gallr brand · download ·
company · contact) with a hairline-separated bottom row showing the
copyright and 'Made in Seoul'."
```

---

## Task 15: Polish — accessibility audit + responsive sweep

**Goal:** Run pa11y + visual sweep at 320/768/1280/1440. Fix anything that breaks.

**Files:**
- Possibly: `web/styles/main.css` (touch fixes if found)
- Test: `web/tests/editorial.test.ts`

- [ ] **Step 1: Run pa11y**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm run build && (npx serve dist -l 4242 --no-clipboard &) && sleep 2 && npx pa11y http://localhost:4242/ ; pkill -f "serve dist -l 4242"
```

Expected: zero violations. If any fire, fix them and re-run.

- [ ] **Step 2: Add overflow + reduced-motion regression tests**

Append to `web/tests/editorial.test.ts`:

```ts
test("Task 15 — no horizontal overflow at 320 / 768 / 1440 with JS on", async ({ page }) => {
  for (const w of [320, 768, 1440]) {
    await page.setViewportSize({ width: w, height: 900 });
    await page.goto("/");
    await page.waitForLoadState("networkidle");
    const overflow = await page.evaluate(
      () =>
        document.documentElement.scrollWidth > document.documentElement.clientWidth
    );
    expect(overflow, `overflow at ${w}px`).toBe(false);
  }
});

test("Task 15 — reduced motion: every reveal element ends up visible", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/");
  await page.waitForLoadState("networkidle");
  const states = await page.evaluate(() => {
    const els = Array.from(document.querySelectorAll("[data-reveal], [data-reveal-stagger]"));
    return els.map((el) => ({
      revealed: el.classList.contains("is-revealed"),
      opacity: parseFloat(getComputedStyle(el).opacity),
    }));
  });
  expect(states.length).toBeGreaterThan(0);
  for (const s of states) {
    expect(s.opacity).toBeGreaterThanOrEqual(0.99);
  }
});
```

- [ ] **Step 3: Run all tests**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm test 2>&1 | tail -25
```

Expected: ALL pass.

- [ ] **Step 4: Visual sanity sweep**

Build and preview:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && npm run preview &
sleep 3
open http://localhost:8080/
```

Manually scroll the page; verify:
- Hero headline fills the viewport.
- Kinetic word cycles 쉬운 → 빠른 → 정확한.
- Marquee strip scrolls smoothly.
- Sticky header turns opaque after scroll.
- Three feature blocks each show a real exhibition image.
- "Now Showing" grid has 8 tiles with status badges where applicable.
- Downloads section is full-bleed black.
- Footer has 4 columns above the copyright row.

Stop the preview server (`pkill -f "serve dist -p 8080"` or `kill %1`).

- [ ] **Step 5: Commit (if any fixes were needed in step 1 or 4)**

```bash
git add -A web/styles web/_includes web/tests
git commit -m "chore(polish): pa11y + responsive sweep clean — final editorial polish"
```

(If no fixes were needed, skip this commit; the regression tests added in Step 2 are already committed via Step 6 of Task 14? No — they were added in Step 2 of THIS task. If you didn't commit them yet, do it now:)

```bash
git add web/tests/editorial.test.ts
git commit -m "test(editorial): viewport overflow + reduced-motion regression coverage"
```

---

## Task 16: Final review + finishing the branch

**Goal:** Final quality gate — pa11y, full test suite, manual preview check — then dispatch finishing-a-development-branch.

- [ ] **Step 1: Run full test suite from scratch**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && rm -rf dist node_modules/.cache && npm test 2>&1 | tail -20
```

Expected: ALL tests pass — showcase test, pa11y, smoke (chromium), editorial (chromium-js).

- [ ] **Step 2: Confirm orange accent appears in only the documented places**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && grep -E "color-accent|FF5400|ff5400" styles/*.css | sort -u
```

Verify the only references in `main.css` are:
- `.hero__kinetic { color: var(--color-accent); }`
- `.status-badge--accent { color: var(--color-accent); }`
- `.grid-tile__badge { ... border: 1px solid var(--color-accent); }`
- `.site-header__progress { background-color: var(--color-accent); }`

…and that there is NO `background-color: var(--color-accent)` for any large surface, NO `.btn--primary` orange fill anywhere, and NO orange text anywhere except the kinetic word and badges.

- [ ] **Step 3: Inspect the showcase data path**

Run:

```bash
cd /Users/hanshin/Documents/Projects/gallr/web && cat _data/showcase.json | head -20
```

Expected: `"source": "seed"` (because Supabase env vars aren't set locally).

- [ ] **Step 4: Use superpowers:finishing-a-development-branch**

The skill verifies tests pass → presents 4 options → executes the chosen one. The branch is `036-website-editorial-redesign`; the base is `develop` (per project memory).

Expected user choice: **Option 2 — push and create a Pull Request** against `develop`.

PR title: `feat(web): editorial redesign — oversized type, real exhibitions, gallery motion`

PR body should include:
- Link to spec: `docs/superpowers/specs/2026-05-07-website-editorial-redesign-design.md`
- Summary of what changed (per task structure above)
- Manual QA checklist:
  - [ ] Hero kinetic word cycles smoothly
  - [ ] Hero marquee scrolls and pauses on hover
  - [ ] Sticky header turns opaque after 80px scroll, progress bar tracks
  - [ ] Three feature blocks each show a real exhibition image with caption
  - [ ] Now Showing grid has 8 tiles, 2-up on mobile, 4-up on desktop
  - [ ] Status badges (orange) appear on closing-soon / opening-soon entries
  - [ ] Downloads section is full-bleed black, no orange button
  - [ ] About headline is display-sized, footer has 4 columns
  - [ ] System reduced-motion: all motion stops, all sections visible
  - [ ] No horizontal scroll at 320 / 768 / 1280 / 1440 viewports
- Out-of-scope follow-ups (cite the spec):
  - Replace the leaked service_role JWT in `local.properties` with a proper anon key (separate concern, flagged in spec).
  - Configure `SUPABASE_URL` + `SUPABASE_ANON_KEY` env vars on Vercel for live data.
  - Page-level meta (`<title>`, OG image) localization to Korean (still pending from PR #44).
  - Image transforms / responsive `srcset`.

---

## Self-Review (run by plan author, not implementer)

### Spec coverage check

| Spec section | Plan task |
|---|---|
| §1 Architecture (Eleventy, build-time fetch, no runtime fetch) | Task 4 |
| §2 Data (Supabase fetch + seed fallback) | Tasks 3, 4 |
| §3 Showcase JSON shape + status classification | Tasks 3, 4 |
| §4 Anon-key handling (no service_role) | Task 4 (env-only) + Task 16 PR notes |
| §5 Build output (showcase.json gitignored, site.json committed) | Tasks 2, 4 |
| §6 Section 1 — global tokens | Task 1 |
| §6 Section 1 — motion primitives | Tasks 6, 7 |
| §6 Section 1 — sticky header + progress | Task 7 |
| §6 Section 2 — hero (eyebrow, headline, kinetic, subhead, divider, CTAs) | Task 8 |
| §6 Section 2 — hero marquee + count | Task 9 |
| §6 Section 3 — features as gallery wall | Task 11 |
| §6 Section 3 — status badges | Tasks 10, 11 |
| §6 Section 4a — Now Showing grid | Task 12 |
| §6 Section 4b — Downloads (black-inverted) | Task 13 |
| §6 Section 5 — About + footer | Task 14 |
| §6 Visual / motion summary table | Tasks 6, 7, 8, 9, 11, 12, 13, 14 |
| §7 Accessibility (reduced motion, sr-only, aria-labels, contrast) | Tasks 6, 7, 8, 15 |
| §7 Performance (eager/lazy, JS bundle, LCP) | Tasks 6, 9, 12 (lazy/eager flags) |
| §8 Acceptance criteria 1–16 | Covered by tests in tasks 1, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 |

No gaps. ✓

### Placeholder scan

Re-scanning for "TBD", "TODO", "implement later", "fill in details", "add appropriate error handling", "Similar to Task N":

- Task 8 Step 4 says "loop.index <= 4" — this is real Nunjucks, not a placeholder. ✓
- Task 11 Step 4 mentions "discovery/bookmarking/filtering identifiers...are unchanged so existing smoke tests keep passing" — this is a justification, not a placeholder. ✓
- Task 5 Step 1 says "Section 6 of this plan adds 12 hand-drawn placeholder SVGs" but Section 7 actually does that — minor wrong reference but not a placeholder. **Fix needed.**

Let me also recheck Task 12 Step 4 — `setupNowShowingCta` is added inside both branches of the reduced-motion check. That works but the wording was "AFTER the bottom (non-reduced) `setupHeader();`, also add" — slightly ambiguous since the other location said "AFTER the `setupHeader();` call inside the `if (reduceMotion)` branch". I'll tighten this.

### Type / name consistency

- `.feature-block` (Task 11) used in tests in Task 11 ✓
- `.grid-tile` (Task 12) used in tests in Task 12 ✓
- `.status-badge--accent` (Task 10) used in features (Task 11) and now-showing (Task 12) ✓
- `--type-display-sm` (Task 1) used in about (Task 14) ✓
- `--color-ink-on-dark-secondary` (Task 1) used in downloads (Task 13) ✓
- `data-marquee-inner` (Task 6) used in hero marquee (Task 9) ✓
- `site.appStoreUrl` / `site.googlePlayUrl` / `site.liveCountLabel` (Task 2) used everywhere ✓

Now applying the two fixes inline below.
