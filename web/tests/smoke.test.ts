import { test, expect } from "@playwright/test";

// All tests run with JavaScript disabled (configured in playwright.config.ts).
// Tests are intentionally written before implementation (Test-First, Constitution Principle II).
// They should FAIL on an empty scaffold and PASS after each story is implemented.

// ============================================================
// US1 — First Impression & Value Proposition (P1)
// Spec: FR-001 (headline above fold), FR-002 (palette restriction)
// ============================================================

test("US1 — T013: <h1> exists and has non-empty text", async ({ page }) => {
  await page.goto("/");
  const h1 = page.locator("h1");
  await expect(h1).toBeVisible();
  const text = await h1.textContent();
  expect(text?.trim().length).toBeGreaterThan(0);
});

test("US1 — T014: page background is #ffffff and headline color is #000000", async ({
  page,
}) => {
  await page.goto("/");

  const bodyBg = await page.evaluate(() => {
    return window.getComputedStyle(document.body).backgroundColor;
  });
  // rgb(255, 255, 255) = #ffffff
  expect(bodyBg).toBe("rgb(255, 255, 255)");

  const h1Color = await page.evaluate(() => {
    const h1 = document.querySelector("h1");
    return h1 ? window.getComputedStyle(h1).color : null;
  });
  // rgb(0, 0, 0) = #000000
  expect(h1Color).toBe("rgb(0, 0, 0)");
});

// ============================================================
// US2 — App Feature Showcase (P2)
// Spec: FR-005 (3 feature entries), FR-011 (section separator)
// ============================================================

test("US2 — T019: feature articles for discovery, bookmarking, and filtering exist", async ({
  page,
}) => {
  await page.goto("/");
  await expect(page.locator("article#discovery")).toBeVisible();
  await expect(page.locator("article#bookmarking")).toBeVisible();
  await expect(page.locator("article#filtering")).toBeVisible();
});

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

// ============================================================
// US3 — App Store Download Access (P3)
// Spec: FR-006 (download CTAs present and functional)
// ============================================================

test("US3 — T027: App Store link has non-empty href", async ({ page }) => {
  await page.goto("/");
  // .first() — the same CTA appears in both hero and downloads sections
  const appStoreLink = page.locator('[aria-label*="App Store"]').first();
  await expect(appStoreLink).toBeVisible();
  const href = await appStoreLink.getAttribute("href");
  expect(href).toBeTruthy();
  expect(href).not.toBe("#");
  expect(href).not.toBe("");
});

test("US3 — T028: Google Play link has non-empty href", async ({ page }) => {
  await page.goto("/");
  const googlePlayLink = page.locator('[aria-label*="Google Play"]').first();
  await expect(googlePlayLink).toBeVisible();
  const href = await googlePlayLink.getAttribute("href");
  expect(href).toBeTruthy();
  expect(href).not.toBe("#");
  expect(href).not.toBe("");
});

test("US3 — T029: both download links have aria-labels containing 'gallr' and store name", async ({
  page,
}) => {
  await page.goto("/");

  const appStoreLabel = await page
    .locator('[aria-label*="App Store"]')
    .first()
    .getAttribute("aria-label");
  expect(appStoreLabel?.toLowerCase()).toContain("gallr");
  expect(appStoreLabel?.toLowerCase()).toContain("app store");

  const googlePlayLabel = await page
    .locator('[aria-label*="Google Play"]')
    .first()
    .getAttribute("aria-label");
  expect(googlePlayLabel?.toLowerCase()).toContain("gallr");
  expect(googlePlayLabel?.toLowerCase()).toContain("google play");
});

// ============================================================
// Polish — Responsive layout (T039)
// Spec: FR-007 (no overflow at 320px–1440px)
// ============================================================

test("Polish — T039: no horizontal overflow at 320px viewport", async ({
  page,
}) => {
  await page.setViewportSize({ width: 320, height: 568 });
  await page.goto("/");
  const overflow = await page.evaluate(() => {
    return document.documentElement.scrollWidth > document.documentElement.clientWidth;
  });
  expect(overflow).toBe(false);
});

test("Polish — T039: no horizontal overflow at 1440px viewport", async ({
  page,
}) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/");
  const overflow = await page.evaluate(() => {
    return document.documentElement.scrollWidth > document.documentElement.clientWidth;
  });
  expect(overflow).toBe(false);
});
