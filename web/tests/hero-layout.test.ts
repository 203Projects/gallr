import { test, expect, type Page } from "@playwright/test";

// .hero is an editorial 3-column composition at ≥ 768px and a stacked
// column below that. Without the column-flip at mobile, every child
// shrinks to min-content and the Korean headline collapses to one word
// per line. Regression test for that fix.

async function heroDirection(page: Page): Promise<string> {
  return await page.locator(".hero").evaluate((el) => {
    return getComputedStyle(el).flexDirection;
  });
}

test.describe("hero flex-direction", () => {
  test("at 375px (mobile): column", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 800 });
    await page.goto("/");
    expect(await heroDirection(page)).toBe("column");
  });

  test("at 767px (just below desktop breakpoint): column", async ({ page }) => {
    await page.setViewportSize({ width: 767, height: 800 });
    await page.goto("/");
    expect(await heroDirection(page)).toBe("column");
  });

  test("at 768px (desktop breakpoint): row", async ({ page }) => {
    await page.setViewportSize({ width: 768, height: 800 });
    await page.goto("/");
    expect(await heroDirection(page)).toBe("row");
  });

  test("at 1280px (desktop): row", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.goto("/");
    expect(await heroDirection(page)).toBe("row");
  });
});
