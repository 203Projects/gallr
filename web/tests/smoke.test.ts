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
