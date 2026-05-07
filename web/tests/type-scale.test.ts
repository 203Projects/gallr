import { test, expect, type Page } from "@playwright/test";

// At small viewports, computed font sizes must respect mobile-honest
// ceilings. Regression net for "fonts too large in some places".

async function fontSizePx(page: Page, selector: string): Promise<number> {
  return await page.locator(selector).first().evaluate(
    (el) => parseFloat(getComputedStyle(el).fontSize)
  );
}

test.describe("type scale at 360px viewport", () => {
  test.use({ viewport: { width: 360, height: 800 } });

  test("hero headline is ≤ 40px", async ({ page }) => {
    await page.goto("/");
    expect(await fontSizePx(page, ".hero__headline")).toBeLessThanOrEqual(40);
  });

  test("feature-block headline is ≤ 32px", async ({ page }) => {
    await page.goto("/");
    expect(await fontSizePx(page, ".feature-block__headline")).toBeLessThanOrEqual(32);
  });

  test("about headline is ≤ 32px", async ({ page }) => {
    await page.goto("/");
    expect(await fontSizePx(page, ".about__headline")).toBeLessThanOrEqual(32);
  });

  test("downloads headline is ≤ 38px", async ({ page }) => {
    await page.goto("/");
    // 2px tolerance above the 36px clamp floor — guards against fractional
    // pixel drift while still catching a genuine regression (>38px means
    // --type-display is not clamping correctly at mobile width).
    expect(await fontSizePx(page, ".downloads__headline")).toBeLessThanOrEqual(38);
  });
});

test.describe("type scale at 1440px viewport", () => {
  test.use({ viewport: { width: 1440, height: 900 } });

  test("hero headline is ≥ 60px (editorial scale)", async ({ page }) => {
    await page.goto("/");
    expect(await fontSizePx(page, ".hero__headline")).toBeGreaterThanOrEqual(60);
  });
});
