import { test, expect, type Page } from "@playwright/test";

// .cta-pair stacks below 480px (1 column), pairs at 480px+ (2 columns).

async function gridColumns(page: Page, selector: string): Promise<number> {
  return await page.locator(selector).first().evaluate((el) => {
    const cs = getComputedStyle(el);
    return cs.gridTemplateColumns.split(" ").filter(Boolean).length;
  });
}

test.describe("cta-pair at 360px", () => {
  test.use({ viewport: { width: 360, height: 800 } });

  test("hero CTA pair is 1-column", async ({ page }) => {
    await page.goto("/");
    expect(await gridColumns(page, ".hero__ctas.cta-pair")).toBe(1);
  });

  test("downloads CTA pair is 1-column", async ({ page }) => {
    await page.goto("/");
    expect(await gridColumns(page, ".downloads__ctas.cta-pair")).toBe(1);
  });
});

test.describe("cta-pair at 600px", () => {
  test.use({ viewport: { width: 600, height: 800 } });

  test("hero CTA pair is 2-column", async ({ page }) => {
    await page.goto("/");
    expect(await gridColumns(page, ".hero__ctas.cta-pair")).toBe(2);
  });

  test("downloads CTA pair is 2-column", async ({ page }) => {
    await page.goto("/");
    expect(await gridColumns(page, ".downloads__ctas.cta-pair")).toBe(2);
  });
});
