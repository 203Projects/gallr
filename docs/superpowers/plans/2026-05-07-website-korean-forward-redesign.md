# gallrmap.com Korean-Forward Redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make gallrmap.com Korean-primary with stacked muted English; replace `/coming-soon` placeholders with live App Store + Google Play URLs.

**Architecture:** Content + light-CSS update to the existing Eleventy site under `web/`. Each translatable block renders Korean as primary text with a muted English `<span class="bi-en" lang="en">` (or `<p class="bi-en">` for the About paragraph) stacked beneath. The hero `<h1>` is the lone KO-only exception. One new CSS utility (`.bi-en`); one document attribute change (`<html lang="ko">`); one section-scoped button restyle for the dark download section. No new dependencies, no runtime JavaScript.

**Tech Stack:** Eleventy 3.x, Nunjucks templates, vanilla CSS with custom properties (tokens), Playwright (visual acceptance, JS disabled), pa11y (WCAG AA).

**Spec:** `docs/superpowers/specs/2026-05-07-website-korean-forward-redesign-design.md`

---

## Important deltas from the feature request

The original feature request listed `#999999` as the muted-EN color. The existing codebase already defines `--color-ink-secondary: #525252` (darker, passes WCAG AA more comfortably) and `web/styles/main.css` is the authoritative no-hardcoded-colors file. **This plan uses `var(--color-ink-secondary)`** for `.bi-en`, not `#999999`. If the design lead wants to introduce a new lighter-gray token for bilingual secondary text, that is a separate token change handled outside this plan.

The plan uses `<span class="bi-en" lang="en">` for inline-stacked secondary content (so the bilingual pair lives inside one semantic element) and `<p class="bi-en" lang="en">` for the About section's paragraph-level stacking.

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `web/_includes/base.html` | Document shell, `<html lang>`, `<head>` metadata | Modify: `lang="en"` → `lang="ko"` |
| `web/_data/features.js` | Feature copy + mockup data driving `features.html` | Modify: add `headlineKo`, `descriptionKo`; replace mockup data with KO-only Seoul-gallery fields |
| `web/_includes/hero.html` | Hero section markup | Modify: KO-only `<h1>`; stacked tagline; live store URLs |
| `web/_includes/features.html` | Features section markup, iterates over `features` data | Modify: section heading stacks; entry headline KO-only; entry description stacks |
| `web/_includes/card-mockup.html` | Shared exhibition-card mockup partial | Modify: KO-only fields; `전시` label |
| `web/_includes/downloads.html` | Downloads section markup | Modify: heading + tagline stack; live store URLs |
| `web/_includes/about.html` | About section markup | Modify: section heading stacks; body becomes KO `<p>` + `.bi-en` `<p>` |
| `web/styles/main.css` | All non-token styles | Modify: add `.bi-en` utility; add `.downloads .btn--primary` overrides |
| `web/tests/smoke.test.ts` | Playwright visual acceptance | Modify existing tests to match new copy/links; add bilingual + link assertions |
| `web/coming-soon/index.html` | Placeholder page | **Untouched** (stays on disk as orphan) |

Each file has one clear responsibility. The `.bi-en` utility lives at the top of the section-styles area in `main.css` so it's the first thing any reader sees when scanning section markup.

---

## Conventions used throughout this plan

- **Working directory:** all `npm` and `git` commands assume `cd web/` for npm, repo root for git. Each step that changes directory states it explicitly.
- **Commits:** small and frequent. Each task ends in a single commit.
- **Branch:** `035-website-korean-forward-redesign` (already created off `develop`, with the spec already committed). Do not switch branches.
- **TDD discipline:** the existing Playwright suite asserts the *current* English copy. Per Constitution Principle II (test-first) and the user's TDD memory, we update tests *first* in Task 1 so they fail against the current implementation, then make them pass section by section. This is the correct order — the tests describe the desired end state of the page.

---

## Task 1: Update tests to describe the bilingual end state (red phase)

Rewrite the Playwright suite to assert the new bilingual page. The tests will fail against the current site — that is intentional (red phase). Each subsequent task makes a slice of these tests pass (green phase).

**Files:**
- Modify: `web/tests/smoke.test.ts` (rewrite full file)

- [ ] **Step 1: Replace the test file with the bilingual assertions**

Replace the entire contents of `web/tests/smoke.test.ts` with:

```typescript
import { test, expect } from "@playwright/test";

// All tests run with JavaScript disabled (configured in playwright.config.ts).
// These tests describe the Korean-forward bilingual end state. They are
// expected to FAIL until the corresponding implementation tasks land.

const APP_STORE_URL =
  "https://apps.apple.com/kr/app/gallr-%EA%B0%A4%EB%9F%AC-%EC%A0%84%EC%8B%9C-%EC%A0%95%EB%B3%B4/id6760855059";
const GOOGLE_PLAY_URL =
  "https://play.google.com/store/apps/details?id=com.gallr.app";

const MUTED = "rgb(82, 82, 82)"; // var(--color-ink-secondary) = #525252

// ============================================================
// Document language
// ============================================================

test("document <html> declares lang='ko'", async ({ page }) => {
  await page.goto("/");
  const lang = await page.evaluate(() => document.documentElement.lang);
  expect(lang).toBe("ko");
});

// ============================================================
// Hero — KO-only headline, stacked tagline, live store links
// ============================================================

test("hero <h1> contains the Korean headline and no English copy", async ({
  page,
}) => {
  await page.goto("/");
  const h1 = page.locator("h1");
  await expect(h1).toBeVisible();
  const text = (await h1.textContent())?.trim() ?? "";
  expect(text).toContain("내 주변 전시를 발견하는 가장 쉬운 방법");
  // Hero is the lone KO-only exception — no English allowed in the <h1>.
  expect(text.toLowerCase()).not.toContain("discover");
  expect(text.toLowerCase()).not.toContain("city");
});

test("hero tagline stacks Korean primary with muted English in .bi-en", async ({
  page,
}) => {
  await page.goto("/");
  const tagline = page.locator(".hero__tagline");
  await expect(tagline).toContainText("오픈 예정");
  const enSpan = tagline.locator(".bi-en");
  await expect(enSpan).toContainText("Browse exhibitions opening and closing soon");
  await expect(enSpan).toHaveAttribute("lang", "en");
  const color = await enSpan.evaluate(
    (el) => window.getComputedStyle(el).color,
  );
  expect(color).toBe(MUTED);
});

test("hero CTA hrefs point to the live App Store and Google Play listings", async ({
  page,
}) => {
  await page.goto("/");
  const heroAppStore = page.locator(
    '#hero a[aria-label*="App Store"]',
  );
  const heroGooglePlay = page.locator(
    '#hero a[aria-label*="Google Play"]',
  );
  await expect(heroAppStore).toHaveAttribute("href", APP_STORE_URL);
  await expect(heroGooglePlay).toHaveAttribute("href", GOOGLE_PLAY_URL);
});

// ============================================================
// Features — bilingual section heading, KO entry headlines,
// stacked entry descriptions, KO-only mockup cards
// ============================================================

test("features section heading stacks '기능' with muted 'Features'", async ({
  page,
}) => {
  await page.goto("/");
  const heading = page.locator(".features__heading");
  await expect(heading).toContainText("기능");
  const enSpan = heading.locator(".bi-en");
  await expect(enSpan).toHaveText("Features");
  await expect(enSpan).toHaveAttribute("lang", "en");
});

test("each feature entry has a KO-only headline (no English siblings)", async ({
  page,
}) => {
  await page.goto("/");
  const expected: Record<string, string> = {
    discovery: "내 근처 전시 찾기",
    bookmarking: "관심 전시 저장하기",
    filtering: "원하는 기준으로 필터링",
  };
  for (const [id, koHeadline] of Object.entries(expected)) {
    const headline = page.locator(
      `article#${id} .feature-entry__headline`,
    );
    await expect(headline).toHaveText(koHeadline);
    // Headline must not contain a .bi-en child.
    await expect(headline.locator(".bi-en")).toHaveCount(0);
  }
});

test("each feature description stacks Korean primary with muted English", async ({
  page,
}) => {
  await page.goto("/");
  const ids = ["discovery", "bookmarking", "filtering"];
  for (const id of ids) {
    const desc = page.locator(`article#${id} .feature-entry__description`);
    const en = desc.locator(".bi-en");
    await expect(en).toHaveCount(1);
    await expect(en).toHaveAttribute("lang", "en");
  }
});

test("card mockups render Korean-only fields and use the '전시' label", async ({
  page,
}) => {
  await page.goto("/");
  const cards: Record<string, { title: string; venue: string; dates: string }> = {
    discovery: {
      title: "리움: 소장품 특별전",
      venue: "리움미술관",
      dates: "2026년 1월 15일 — 4월 28일",
    },
    bookmarking: {
      title: "추상 기하학의 세계",
      venue: "아모레퍼시픽미술관",
      dates: "2026년 3월 3일 — 6월 12일",
    },
    filtering: {
      title: "사진, 지금",
      venue: "국제갤러리",
      dates: "2026년 3월 20일 오픈",
    },
  };
  for (const [id, c] of Object.entries(cards)) {
    const card = page.locator(`article#${id} .card-mockup`);
    await expect(card.locator(".card-mockup__label")).toHaveText("전시");
    await expect(card.locator(".card-mockup__title")).toHaveText(c.title);
    await expect(card.locator(".card-mockup__venue")).toHaveText(c.venue);
    await expect(card.locator(".card-mockup__dates")).toHaveText(c.dates);
  }
});

// ============================================================
// Downloads — bilingual heading + tagline, live links,
// dark-section button restyle
// ============================================================

test("downloads heading stacks 'gallr 다운로드' with muted 'Download gallr'", async ({
  page,
}) => {
  await page.goto("/");
  const heading = page.locator(".downloads__heading");
  await expect(heading).toContainText("gallr 다운로드");
  const enSpan = heading.locator(".bi-en");
  await expect(enSpan).toHaveText("Download gallr");
  await expect(enSpan).toHaveAttribute("lang", "en");
});

test("downloads tagline stacks Korean with muted English", async ({ page }) => {
  await page.goto("/");
  const tagline = page.locator(".downloads__tagline");
  await expect(tagline).toContainText("애플/안드로이드에서 무료로 다운로드하세요.");
  const en = tagline.locator(".bi-en");
  await expect(en).toContainText("Available for iPhone and Android");
});

test("downloads CTA hrefs point to the live store listings", async ({ page }) => {
  await page.goto("/");
  const dlAppStore = page.locator(
    '#downloads a[aria-label*="App Store"]',
  );
  const dlGooglePlay = page.locator(
    '#downloads a[aria-label*="Google Play"]',
  );
  await expect(dlAppStore).toHaveAttribute("href", APP_STORE_URL);
  await expect(dlGooglePlay).toHaveAttribute("href", GOOGLE_PLAY_URL);
});

test("downloads-section buttons render with white fill and black text", async ({
  page,
}) => {
  await page.goto("/");
  const btn = page.locator("#downloads .btn--primary").first();
  const styles = await btn.evaluate((el) => {
    const s = window.getComputedStyle(el);
    return { bg: s.backgroundColor, color: s.color };
  });
  expect(styles.bg).toBe("rgb(255, 255, 255)"); // #ffffff
  expect(styles.color).toBe("rgb(0, 0, 0)"); // #000000
});

// ============================================================
// About — bilingual section heading, paragraph-level stacking
// ============================================================

test("about section heading stacks 'gallr 소개' with muted 'About'", async ({
  page,
}) => {
  await page.goto("/");
  const heading = page.locator(".about__heading");
  await expect(heading).toContainText("gallr 소개");
  const enSpan = heading.locator(".bi-en");
  await expect(enSpan).toHaveText("About");
  await expect(enSpan).toHaveAttribute("lang", "en");
});

test("about body has one Korean paragraph followed by one .bi-en English paragraph", async ({
  page,
}) => {
  await page.goto("/");
  const paragraphs = page.locator(".about__body > p");
  await expect(paragraphs).toHaveCount(2);
  await expect(paragraphs.nth(0)).toContainText("좋은 전시를 너무 늦게");
  const enP = paragraphs.nth(1);
  await expect(enP).toHaveClass(/\bbi-en\b/);
  await expect(enP).toHaveAttribute("lang", "en");
  await expect(enP).toContainText("gallr is the easiest way to discover");
});

// ============================================================
// Global — no /coming-soon links remain anywhere on the page
// ============================================================

test("no anchor on the page references /coming-soon", async ({ page }) => {
  await page.goto("/");
  const stale = page.locator('a[href="/coming-soon"]');
  await expect(stale).toHaveCount(0);
});

// ============================================================
// Color contrast — body bg + h1 ink (kept from prior suite)
// ============================================================

test("page background is #ffffff and headline color is #000000", async ({
  page,
}) => {
  await page.goto("/");
  const bodyBg = await page.evaluate(
    () => window.getComputedStyle(document.body).backgroundColor,
  );
  expect(bodyBg).toBe("rgb(255, 255, 255)");
  const h1Color = await page.evaluate(() => {
    const h1 = document.querySelector("h1");
    return h1 ? window.getComputedStyle(h1).color : null;
  });
  expect(h1Color).toBe("rgb(0, 0, 0)");
});

// ============================================================
// Responsive — stacked KO/EN must not cause horizontal overflow
// ============================================================

test("no horizontal overflow at 320px viewport", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 568 });
  await page.goto("/");
  const overflow = await page.evaluate(
    () =>
      document.documentElement.scrollWidth >
      document.documentElement.clientWidth,
  );
  expect(overflow).toBe(false);
});

test("no horizontal overflow at 1440px viewport", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/");
  const overflow = await page.evaluate(
    () =>
      document.documentElement.scrollWidth >
      document.documentElement.clientWidth,
  );
  expect(overflow).toBe(false);
});
```

- [ ] **Step 2: Run the full suite and confirm it fails**

From `web/`:

```bash
cd web && npm run build && npx playwright test
```

Expected: many failures (the new test file describes the end state; current site still has English headline, `/coming-soon` links, no `.bi-en` elements, etc.). pa11y passes (no contrast regression yet).

- [ ] **Step 3: Commit the failing tests**

From repo root:

```bash
git add web/tests/smoke.test.ts
git commit -m "test(035): describe Korean-forward bilingual end state

Failing tests for stacked KO/EN pattern, KO-only hero <h1>, KO-only
mockup cards, live store links, and <html lang='ko'>. Implementation
follows in subsequent tasks.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Add the `.bi-en` utility and switch document `lang`

Foundation for every later task. Add the muted-English utility class and flip the document language. After this task the page itself doesn't yet use `.bi-en`, but the styling and document language are in place.

**Files:**
- Modify: `web/styles/main.css` (add `.bi-en` rule)
- Modify: `web/_includes/base.html:2` (`lang="en"` → `lang="ko"`)

- [ ] **Step 1: Add `.bi-en` utility to `main.css`**

Insert the following block immediately after the closing brace of the `.btn:focus-visible` rule (around line 111), before the `/* ── Section separators ... */` comment. This places it with the other shared utilities.

```css
/* ── Bilingual secondary text (.bi-en) ───────────────────────
   Muted English rendered beneath Korean primary copy.
   em-relative sizing so it scales with the parent (heading vs.
   body). display: block makes a <span> stack visually while
   keeping it semantically inside the parent element.
   ── */

.bi-en {
  display: block;
  font-size: 0.78em;
  line-height: 1.4;
  margin-top: 0.25em;
  font-weight: 400;
  color: var(--color-ink-secondary);
  text-transform: none;
  letter-spacing: 0;
}
```

(`text-transform: none` and `letter-spacing: 0` are explicit resets so a `.bi-en` placed inside an uppercase eyebrow heading like `.features__heading` renders English in normal case with normal tracking.)

- [ ] **Step 2: Switch `<html lang>` in the document shell**

In `web/_includes/base.html`, change line 2 from:

```html
<html lang="en">
```

to:

```html
<html lang="ko">
```

- [ ] **Step 3: Build and run the document-language test**

From `web/`:

```bash
cd web && npm run build && npx playwright test -g "document <html> declares lang='ko'"
```

Expected: PASS.

- [ ] **Step 4: Commit**

From repo root:

```bash
git add web/styles/main.css web/_includes/base.html
git commit -m "feat(035): add .bi-en utility and set <html lang='ko'>

Foundation for bilingual stacked content. Utility uses
em-relative sizing and var(--color-ink-secondary) for muted
English; resets text-transform and letter-spacing so it works
inside uppercase eyebrow headings.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Convert hero to Korean-forward + live store links

KO-only `<h1>`, stacked tagline, live store URLs. Removes all English from the hero headline (intentional brand choice).

**Files:**
- Modify: `web/_includes/hero.html` (full rewrite)

- [ ] **Step 1: Replace `web/_includes/hero.html` with the bilingual version**

```html
<section id="hero" class="hero" aria-labelledby="hero-headline">
  <div class="hero__inner">
    <div class="hero__text">
      <h1 id="hero-headline" class="hero__headline">
        내 주변 전시를 발견하는 가장 쉬운 방법
      </h1>
      <p class="hero__tagline">
        오픈 예정 / 종료 예정 전시를 한눈에 — 더이상 보고싶었던 전시를 놓치지 마세요.
        <span class="bi-en" lang="en">Browse exhibitions opening and closing soon — so you never miss what matters.</span>
      </p>
    </div>

    <nav class="hero__ctas" aria-label="gallr 다운로드">
      <a
        href="https://apps.apple.com/kr/app/gallr-%EA%B0%A4%EB%9F%AC-%EC%A0%84%EC%8B%9C-%EC%A0%95%EB%B3%B4/id6760855059"
        class="btn btn--primary"
        aria-label="Download gallr on the App Store"
      >
        App Store
      </a>
      <a
        href="https://play.google.com/store/apps/details?id=com.gallr.app"
        class="btn btn--primary"
        aria-label="Get gallr on Google Play"
      >
        Google Play
      </a>
    </nav>
  </div>
</section>
```

- [ ] **Step 2: Build and run the hero tests**

From `web/`:

```bash
cd web && npm run build && npx playwright test -g "hero"
```

Expected: all hero tests PASS (`hero <h1>...`, `hero tagline...`, `hero CTA hrefs...`).

- [ ] **Step 3: Commit**

From repo root:

```bash
git add web/_includes/hero.html
git commit -m "feat(035): hero — KO-only headline, stacked tagline, live store URLs

The hero <h1> is the lone KO-only exception in the bilingual
pattern; the brand moment carries weight without an English
crutch. CTA hrefs point to the live App Store (kr) and Google
Play listings.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Convert features data to bilingual + Seoul-gallery mockups

Update `_data/features.js` with KO copy and KO-only mockup data. Required before Task 5 (templates) can render the new fields.

**Files:**
- Modify: `web/_data/features.js` (full rewrite)

- [ ] **Step 1: Replace `web/_data/features.js`**

```js
// Feature entries for the Feature Showcase section.
// `headline` (EN) is rendered as muted secondary text via .bi-en.
// `headlineKo` is the primary, dominant text.
// Mockup data is Korean-only — the in-app card renders in Korean.
module.exports = [
  {
    id: "discovery",
    headlineKo: "내 근처 전시 찾기",
    headline: "Find exhibitions near you",
    descriptionKo:
      "지금 진행 중이거나 오픈 예정인 전시를 한눈에 확인하세요. 추천 전시, 에디터 픽, 그리고 이번 주 오픈·종료 전시를 큐레이션합니다.",
    description:
      "Browse ongoing and upcoming exhibitions in your city with filters — Featured, Editor's picks, Opening This Week, and Closing This Week.",
    mockup: {
      titleKo: "리움: 소장품 특별전",
      venueKo: "리움미술관",
      dateRangeKo: "2026년 1월 15일 — 4월 28일",
    },
  },
  {
    id: "bookmarking",
    headlineKo: "관심 전시 저장하기",
    headline: "Save what interests you",
    descriptionKo:
      "마음에 드는 전시를 저장해 나만의 리스트를 만들어보세요. 저장한 전시는 오프라인에서도 언제든 확인할 수 있어요.",
    description:
      "Bookmark any exhibition to build your personal shortlist. Your saved exhibitions are available offline, so you always have your list at hand.",
    mockup: {
      titleKo: "추상 기하학의 세계",
      venueKo: "아모레퍼시픽미술관",
      dateRangeKo: "2026년 3월 3일 — 6월 12일",
    },
  },
  {
    id: "filtering",
    headlineKo: "원하는 기준으로 필터링",
    headline: "Filter by what matters",
    descriptionKo:
      "지역, 추천, 에디터 픽, 일정별로 전시를 필터링하고 나에게 필요한 전시만 골라보세요.",
    description:
      "Narrow your view by region, featured picks, editor's picks, or timing — opening this week, closing this week. See only what's relevant to you.",
    mockup: {
      titleKo: "사진, 지금",
      venueKo: "국제갤러리",
      dateRangeKo: "2026년 3월 20일 오픈",
    },
  },
];
```

- [ ] **Step 2: Build and verify the data flows through**

From `web/`:

```bash
cd web && npm run build
```

Expected: build succeeds. Tests still fail for features and cards — templates don't yet read the new fields.

- [ ] **Step 3: Commit**

From repo root:

```bash
git add web/_data/features.js
git commit -m "feat(035): bilingual feature copy + Seoul-gallery mockups

Adds headlineKo/descriptionKo to each feature entry. Mockup
data is Korean-only (titleKo/venueKo/dateRangeKo) — the in-app
card preview renders in Korean.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Convert features template + card mockup partial to bilingual rendering

Render the new fields. Section heading stacks; entry headlines are KO-only; entry descriptions stack; cards use Korean-only fields with `전시` label.

**Files:**
- Modify: `web/_includes/features.html` (full rewrite)
- Modify: `web/_includes/card-mockup.html` (full rewrite)

- [ ] **Step 1: Rewrite `web/_includes/features.html`**

```html
<section id="features" class="features" aria-labelledby="features-heading">
  <div class="features__inner">
    <h2 id="features-heading" class="features__heading">
      기능
      <span class="bi-en" lang="en">Features</span>
    </h2>

    <div class="features__grid">
      {% for feature in features %}
      <article id="{{ feature.id }}" class="feature-entry" aria-labelledby="feature-{{ feature.id }}-title">
        <h3 id="feature-{{ feature.id }}-title" class="feature-entry__headline">
          {{ feature.headlineKo }}
        </h3>
        <p class="feature-entry__description">
          {{ feature.descriptionKo }}
          <span class="bi-en" lang="en">{{ feature.description }}</span>
        </p>

        {% if feature.mockup %}
          {% set mockup = feature.mockup %}
          {% include "card-mockup.html" %}
        {% endif %}
      </article>
      {% endfor %}
    </div>
  </div>
</section>
```

- [ ] **Step 2: Rewrite `web/_includes/card-mockup.html`**

```html
{# Card Mockup — Korean-only preview of the gallr exhibition card.
   Mirrors the app's Reductionist design: sharp corners, border-ink
   outline, sans-serif title, hairline internal separator.
   Parameters: mockup.titleKo, mockup.venueKo, mockup.dateRangeKo
#}
<div class="card-mockup" role="img" aria-label="{{ mockup.titleKo }} 전시 카드 미리보기">
  <div class="card-mockup__header">
    <span class="card-mockup__label">전시</span>
  </div>
  <hr class="card-mockup__rule" />
  <div class="card-mockup__body">
    <h4 class="card-mockup__title">{{ mockup.titleKo }}</h4>
    <p class="card-mockup__venue">{{ mockup.venueKo }}</p>
    <p class="card-mockup__dates">{{ mockup.dateRangeKo }}</p>
  </div>
</div>
```

- [ ] **Step 3: Build and run the features tests**

From `web/`:

```bash
cd web && npm run build && npx playwright test -g "features|feature entry|card mockups"
```

Expected: all four feature/card tests PASS (`features section heading...`, `each feature entry has a KO-only headline...`, `each feature description stacks...`, `card mockups render Korean-only fields...`).

- [ ] **Step 4: Commit**

From repo root:

```bash
git add web/_includes/features.html web/_includes/card-mockup.html
git commit -m "feat(035): bilingual features + KO-only mockup cards

Section heading stacks 기능 / Features. Entry headlines are
Korean-only. Entry descriptions stack KO primary with muted EN
inside .bi-en. Cards use Korean-only titleKo/venueKo/dateRangeKo
and the 전시 label.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Convert downloads section + dark-section button restyle

Section heading and tagline stack; both CTAs get live URLs; buttons in this dark section render white-fill / black-text with orange hover.

**Files:**
- Modify: `web/_includes/downloads.html` (full rewrite)
- Modify: `web/styles/main.css` (add section-scoped button overrides)

- [ ] **Step 1: Rewrite `web/_includes/downloads.html`**

```html
<section id="downloads" class="downloads" aria-labelledby="downloads-heading">
  <div class="downloads__inner">
    <h2 id="downloads-heading" class="downloads__heading">
      gallr 다운로드
      <span class="bi-en" lang="en">Download gallr</span>
    </h2>
    <p class="downloads__tagline">
      애플/안드로이드에서 무료로 다운로드하세요.
      <span class="bi-en" lang="en">Available for iPhone and Android. Free to download.</span>
    </p>

    <nav class="downloads__ctas" aria-label="앱스토어에서 gallr 다운로드">
      <a
        href="https://apps.apple.com/kr/app/gallr-%EA%B0%A4%EB%9F%AC-%EC%A0%84%EC%8B%9C-%EC%A0%95%EB%B3%B4/id6760855059"
        class="btn btn--primary"
        aria-label="Download gallr on the App Store"
      >
        App Store
      </a>
      <a
        href="https://play.google.com/store/apps/details?id=com.gallr.app"
        class="btn btn--primary"
        aria-label="Get gallr on Google Play"
      >
        Google Play
      </a>
    </nav>
  </div>
</section>
```

- [ ] **Step 2: Add the dark-section button override to `main.css`**

Append the following block at the **end** of `web/styles/main.css` (after the existing `/* ── Focus rings ... */` block):

```css
/* ── Downloads section button override ───────────────────────
   The downloads section sits on a dark surface in the redesign;
   .btn--primary scoped here flips to white fill / black text
   with an orange hover, matching the bilingual-redesign spec.
   ── */

#downloads .btn--primary {
  background-color: var(--color-paper);
  color: var(--color-ink);
  border: 1px solid var(--color-paper);
}

#downloads .btn--primary:hover {
  background-color: var(--color-accent);
  color: var(--color-ink);
  border-color: var(--color-accent);
}
```

(Scoped via `#downloads` rather than a new modifier class so the markup doesn't carry section-coupled CSS classes. The downloads section already has `background-color: var(--color-paper-alt)` which is `#f5f5f5` — note that the original feature request mentions a "dark background section" but the current site uses paper-alt; the white-fill button still works because the paper-alt background is light enough that white buttons read clearly. If a true black background is desired later, that's a separate token/section change. The Playwright test asserts only the white-fill/black-text styling, which is what the spec calls for and what this rule produces.)

- [ ] **Step 3: Build and run the downloads tests**

From `web/`:

```bash
cd web && npm run build && npx playwright test -g "downloads"
```

Expected: all four downloads tests PASS (`downloads heading...`, `downloads tagline...`, `downloads CTA hrefs...`, `downloads-section buttons render with white fill...`).

- [ ] **Step 4: Commit**

From repo root:

```bash
git add web/_includes/downloads.html web/styles/main.css
git commit -m "feat(035): bilingual downloads section + white-fill button override

Heading and tagline stack KO primary with muted EN. Both CTA
hrefs point to live store listings. #downloads-scoped button
override renders white fill / black text / orange hover per spec.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: Convert about section to paragraph-level bilingual stacking

Section heading stacks; body becomes one Korean `<p>` followed by one `.bi-en` `<p>`.

**Files:**
- Modify: `web/_includes/about.html` (full rewrite)

- [ ] **Step 1: Rewrite `web/_includes/about.html`**

```html
<section id="about" class="about" aria-labelledby="about-heading">
  <div class="about__inner">
    <h2 id="about-heading" class="about__heading">
      gallr 소개
      <span class="bi-en" lang="en">About</span>
    </h2>

    <div class="about__body">
      <p>
        좋은 전시를 너무 늦게 알게 되는 일, 이제는 없도록.
        gallr는 국내 기관부터 갤러리, 대안공간까지 — 도시의 전시를 한곳에 모았습니다.
      </p>
      <p class="bi-en" lang="en">
        gallr is the easiest way to discover exhibitions in your city.
        Great shows come and go, and sometimes people find out too late.
        From major institutions to independent spaces, we bring the full
        picture together in one place.
      </p>
    </div>
  </div>
</section>
```

(Note: the Korean paragraph has `<br>`-style line break in the source spec; flowing inline as one paragraph reads more naturally in body copy and matches how every other paragraph in the codebase is written. The newline between sentences is whitespace-collapsed by the browser. If the visual designer wants a hard break, that's a follow-up tweak.)

- [ ] **Step 2: Build and run the about tests**

From `web/`:

```bash
cd web && npm run build && npx playwright test -g "about"
```

Expected: both about tests PASS (`about section heading...`, `about body has one Korean paragraph...`).

- [ ] **Step 3: Commit**

From repo root:

```bash
git add web/_includes/about.html
git commit -m "feat(035): bilingual about — KO heading stack + paragraph-level body

Section heading stacks gallr 소개 / About. Body uses paragraph-
level stacking: one Korean <p>, then one .bi-en <p> with the
matching English paragraph.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: Verify the full suite is green and audit accessibility

All implementation tasks are landed. This task confirms nothing was missed: full Playwright suite passes, pa11y reports zero violations, no `/coming-soon` references remain in the build output.

**Files:**
- (No code changes; verification only.)

- [ ] **Step 1: Build and run the full test suite**

From `web/`:

```bash
cd web && npm test
```

(Equivalent to `npm run build && node tests/accessibility.test.js && npx playwright test`.)

Expected:
- `pa11y`: `✓ No WCAG AA violations found.`
- Playwright: every test passes (no failures, no skipped).

- [ ] **Step 2: Confirm zero `/coming-soon` references in built output**

From `web/`:

```bash
grep -rn 'href="/coming-soon"' dist/ || echo "OK: no /coming-soon refs in dist/"
```

Expected: prints `OK: no /coming-soon refs in dist/`.

- [ ] **Step 3: Spot-check Korean copy in build output**

From `web/`:

```bash
grep -c '내 주변 전시를 발견' dist/index.html
grep -c 'gallr 다운로드' dist/index.html
grep -c '리움미술관' dist/index.html
```

Expected: each command prints `1` or higher. (Confirms Korean copy survived the build pipeline intact — important because some pipelines mangle UTF-8.)

- [ ] **Step 4: Confirm `<html lang="ko">` in build output**

From `web/`:

```bash
grep -c 'lang="ko"' dist/index.html
```

Expected: prints `1` (`base.html` renders the document shell once).

- [ ] **Step 5: If everything passes, commit a verification marker**

If pa11y had any issues (it shouldn't, given `--color-ink-secondary: #525252` passes WCAG AA easily), or any test was skipped or flaky, **stop and investigate** — do not paper over the issue.

If everything is green:

From repo root:

```bash
git commit --allow-empty -m "chore(035): verify full suite green for Korean-forward redesign

Manual verification — no code changes:
- npm test passes (pa11y + Playwright)
- dist/index.html has zero /coming-soon hrefs
- dist/index.html has <html lang='ko'>
- All Korean copy strings present in build output

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: Manual QA + open PR to develop

Browser-based dogfood pass, then open the PR. Per memory: when finishing a branch, default to push + open PR (do not present the keep/discard menu).

**Files:**
- (No code changes.)

- [ ] **Step 1: Start the dev server and dogfood the page**

From `web/`:

```bash
cd web && npm run preview
```

Open `http://localhost:8080` and check the following manually:

1. Page reads as Korean-primary on first impression.
2. Hero `<h1>` is Korean only (no English line beneath).
3. All other sections show muted English under Korean.
4. Section headings (`기능 — Features`, `gallr 소개 — About`) stack correctly.
5. Tap both hero store buttons → opens correct App Store / Google Play listings (in a real browser; Playwright can't verify external navigation).
6. Tap both download-section store buttons → same.
7. Mobile viewport (375px in DevTools): no overflow, text wraps cleanly.
8. Desktop viewport (1280px): three-up feature grid, mockup cards show Korean titles/venues.

Stop the dev server (`Ctrl-C`) when done.

- [ ] **Step 2: Push branch and open the PR**

From repo root:

```bash
git push -u origin 035-website-korean-forward-redesign
gh pr create --base develop --title "feat(035): gallrmap.com Korean-forward redesign" --body "$(cat <<'EOF'
## Summary
- Korean-primary marketing site: every section renders Korean as primary text with stacked muted English (`.bi-en` utility) beneath, except the hero `<h1>` which is Korean only.
- App Store and Google Play CTAs now point to the live store listings; `/coming-soon` is no longer linked anywhere on the index page.
- `<html lang="ko">`; every `.bi-en` element carries `lang="en"` for assistive tech.
- Feature mockup cards swap to Seoul galleries (Leeum / Amorepacific / Kukje), Korean-only.

## Test plan
- [ ] `cd web && npm test` passes (pa11y + Playwright)
- [ ] `grep -rn 'href="/coming-soon"' web/dist/` returns no matches
- [ ] Manual: Korean reading pass on mobile + desktop
- [ ] Manual: tap both store buttons in real browser, confirm correct store listings open
- [ ] Manual: muted English remains legible on `--color-paper` background

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: `gh pr create` prints the PR URL. Return the URL.

---

## Self-Review

Walked through every requirement in the spec and matched it to a task:

- KO-primary layout with stacked muted EN → Tasks 3, 5, 6, 7
- Hero `<h1>` KO-only exception → Task 3 + dedicated Playwright assertion in Task 1
- `.bi-en` utility (em-relative, muted secondary, normal-weight) → Task 2
- `<html lang="ko">` + `lang="en"` on every `.bi-en` element → Task 2 + assertions in Task 1
- Feature copy + Seoul-gallery mockups → Tasks 4, 5
- `전시` card label → Task 5
- Live App Store + Google Play URLs → Tasks 3, 6
- Downloads section button restyle (white fill, black text, orange hover) → Task 6
- Paragraph-level stacking in About → Task 7
- pa11y zero violations → Task 8
- Playwright visual acceptance → Task 1 (red), Tasks 3/5/6/7 (green), Task 8 (full sweep)
- 320px / 1440px responsive sanity → Task 1 assertion
- No `/coming-soon` references remain → Task 1 assertion + Task 8 grep verification
- Manual QA + PR → Task 9

Placeholder scan: clean. No "TBD", "TODO", or "implement appropriate X" left in the plan.

Type/identifier consistency: feature-data fields (`headlineKo`, `descriptionKo`, `headline`, `description`) and mockup fields (`titleKo`, `venueKo`, `dateRangeKo`) match between Task 4 (data), Task 5 (template), and the test assertions in Task 1. CSS class `.bi-en` is consistent throughout. Live store URL strings are byte-identical between Task 1 (test constants) and Tasks 3/6 (template hrefs).

One spec-vs-codebase deviation worth re-flagging here: the `#999999` from the feature request is replaced with `var(--color-ink-secondary)` (`#525252`) per codebase convention. Documented at the top of this plan.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-07-website-korean-forward-redesign.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
