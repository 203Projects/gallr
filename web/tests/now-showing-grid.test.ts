import { test, expect, type Page } from "@playwright/test";

async function gridColumns(page: Page, selector: string): Promise<number> {
  return await page.locator(selector).evaluate((el) => {
    return getComputedStyle(el).gridTemplateColumns.split(" ").filter(Boolean).length;
  });
}

test.describe("now-showing grid steps", () => {
  test("360px viewport → 2 columns", async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 800 });
    await page.goto("/");
    expect(await gridColumns(page, ".now-showing__grid")).toBe(2);
  });

  test("800px viewport → 3 columns", async ({ page }) => {
    await page.setViewportSize({ width: 800, height: 800 });
    await page.goto("/");
    expect(await gridColumns(page, ".now-showing__grid")).toBe(3);
  });

  test("1440px viewport → 4 columns", async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto("/");
    expect(await gridColumns(page, ".now-showing__grid")).toBe(4);
  });
});
