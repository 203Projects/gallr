import { test, expect } from "@playwright/test";

// Fixture seed (loaded by globalSetup) has 4 rows:
//   fx-001  current      seoul   featured  ticket_url
//   fx-002  closing_soon seoul
//   fx-003  opening_soon seoul
//   fx-004  closed       busan

test.describe("Discover filtering", () => {
  test("default: all 4 fixture cards visible", async ({ page }) => {
    await page.goto("/exhibitions/");
    const visibleCards = await page.locator(".exhibition-card:visible").count();
    expect(visibleCards).toBe(4);
  });

  test("status=closed shows only closed rows", async ({ page }) => {
    await page.goto("/exhibitions/?status=closed");
    const visibleCards = page.locator(".exhibition-card:visible");
    await expect(visibleCards).toHaveCount(1, { timeout: 2000 });
    await expect(visibleCards.first()).toHaveAttribute("data-status", "closed");
  });

  test("clicking a status filter updates the URL", async ({ page }) => {
    await page.goto("/exhibitions/");
    await page.locator('[data-filter-group="status"] [data-filter-value="current"]').click();
    await expect(page).toHaveURL(/[?&]status=current/);
    const visibleCards = page.locator(".exhibition-card:visible");
    await expect(visibleCards.first()).toHaveAttribute("data-status", "current");
  });

  test("city + status combine: current + busan = empty state", async ({ page }) => {
    await page.goto("/exhibitions/?status=current&city=busan");
    const visibleCards = page.locator(".exhibition-card:visible");
    await expect(visibleCards).toHaveCount(0);
    const empty = page.locator("[data-empty]");
    await expect(empty).toBeVisible();
  });

  test("reset button clears filters from empty-state", async ({ page }) => {
    // Reset button lives inside [data-empty]; only visible when 0 cards match.
    await page.goto("/exhibitions/?status=current&city=busan");
    await page.locator("[data-filter-reset]").click();
    await expect(page).toHaveURL(/\/exhibitions\/$/);
    const visibleCards = await page.locator(".exhibition-card:visible").count();
    expect(visibleCards).toBe(4);
  });
});
