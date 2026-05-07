import { test, expect, type Page } from "@playwright/test";

// Mobile sections must not feel "boxed off" — Now Showing and About
// use hairline borders below 768px and thick borders at and above.

async function borderTopWidth(page: Page, selector: string): Promise<string> {
  return await page.locator(selector).evaluate(
    (el) => getComputedStyle(el).borderTopWidth
  );
}

test.describe("mobile borders (375px viewport)", () => {
  test.use({ viewport: { width: 375, height: 800 } });

  test("now-showing top border is 1px (hairline)", async ({ page }) => {
    await page.goto("/");
    expect(await borderTopWidth(page, ".now-showing")).toBe("1px");
  });

  test("about top border is 1px (hairline)", async ({ page }) => {
    await page.goto("/");
    expect(await borderTopWidth(page, ".about")).toBe("1px");
  });

  test("features top border is 4px (kept thick — the marketing→product seam)", async ({ page }) => {
    await page.goto("/");
    expect(await borderTopWidth(page, ".features")).toBe("4px");
  });
});

test.describe("desktop borders (1024px viewport)", () => {
  test.use({ viewport: { width: 1024, height: 768 } });

  test("now-showing top border is 4px", async ({ page }) => {
    await page.goto("/");
    expect(await borderTopWidth(page, ".now-showing")).toBe("4px");
  });

  test("about top border is 4px", async ({ page }) => {
    await page.goto("/");
    expect(await borderTopWidth(page, ".about")).toBe("4px");
  });
});
