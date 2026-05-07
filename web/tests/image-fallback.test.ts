import { test, expect } from "@playwright/test";

// When a showcase image returns 404, the parent gains a --missing
// class and shows the exhibition title text in the off-white square.

test("now-showing tile shows fallback title on image 404", async ({ page }) => {
  // Block ALL showcase images (covers SVG seed + real Supabase URLs).
  await page.route("**/showcase/**", (route) => route.abort());
  await page.route("**/storage/v1/object/public/**", (route) => route.abort());

  await page.goto("/");

  const firstWrap = page.locator(".grid-tile__image-wrap").first();
  await expect(firstWrap).toHaveClass(/grid-tile__image-wrap--missing/, {
    timeout: 5000,
  });

  const fallbackTitle = await firstWrap.getAttribute("data-fallback-title");
  expect(fallbackTitle).toBeTruthy();
  expect(fallbackTitle!.length).toBeGreaterThan(0);
});

test("feature figure shows fallback title on image 404", async ({ page }) => {
  await page.route("**/showcase/**", (route) => route.abort());
  await page.route("**/storage/v1/object/public/**", (route) => route.abort());

  await page.goto("/");

  const firstFigure = page.locator(".feature-block__figure").first();
  await expect(firstFigure).toHaveClass(/feature-block__figure--missing/, {
    timeout: 5000,
  });
});
