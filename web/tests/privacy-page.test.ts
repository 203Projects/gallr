import { test, expect } from "@playwright/test";

test.describe("privacy policy", () => {
  test("uses the editorial legal-document layout", async ({ page }) => {
    await page.goto("/privacy/");

    await expect(page.getByRole("heading", { level: 1, name: "Privacy Policy" })).toBeVisible();
    await expect(page.getByRole("navigation", { name: "Privacy policy sections" })).toBeVisible();
    await expect(page.getByRole("link", { name: /privacy@gallrmap.com/ })).toHaveAttribute(
      "href",
      "mailto:privacy@gallrmap.com",
    );

    const articleWidth = await page.locator(".privacy__article").evaluate(
      (element) => element.getBoundingClientRect().width,
    );
    expect(articleWidth).toBeLessThanOrEqual(680);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(
      await page.evaluate(() => document.documentElement.clientWidth),
    );
  });

  test("keeps the policy readable on mobile", async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 800 });
    await page.goto("/privacy/");

    const titleSize = await page.locator(".privacy__header h1").evaluate(
      (element) => parseFloat(getComputedStyle(element).fontSize),
    );
    expect(titleSize).toBeLessThanOrEqual(40);
    await expect(page.locator(".privacy__contents ol")).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(
      await page.evaluate(() => document.documentElement.clientWidth),
    );
  });
});
