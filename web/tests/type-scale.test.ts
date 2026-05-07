import { test, expect } from "@playwright/test";

// At small viewports, computed font sizes must respect mobile-honest
// ceilings. Regression net for "fonts too large in some places".

async function fontSizePx(page, selector: string): Promise<number> {
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

  test("downloads headline is ≤ 36px", async ({ page }) => {
    await page.goto("/");
    expect(await fontSizePx(page, ".downloads__headline")).toBeLessThanOrEqual(36);
  });
});

test.describe("type scale at 1440px viewport", () => {
  test.use({ viewport: { width: 1440, height: 900 } });

  test("hero headline is ≥ 60px (editorial scale)", async ({ page }) => {
    await page.goto("/");
    expect(await fontSizePx(page, ".hero__headline")).toBeGreaterThanOrEqual(60);
  });
});
