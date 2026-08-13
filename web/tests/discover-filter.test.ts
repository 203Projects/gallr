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

  test("city=서울 shows the 3 Seoul rows", async ({ page }) => {
    await page.goto("/exhibitions/?city=" + encodeURIComponent("서울"));
    const visibleCards = page.locator(".exhibition-card:visible");
    await expect(visibleCards).toHaveCount(3);
    for (let i = 0; i < 3; i++) {
      await expect(visibleCards.nth(i)).toHaveAttribute("data-city", "서울");
    }
  });

  test("city + status combine: current + 부산 = empty state", async ({ page }) => {
    // fx-001 is current+서울; fx-004 is closed+부산. So current+부산 = 0.
    await page.goto("/exhibitions/?status=current&city=" + encodeURIComponent("부산"));
    const visibleCards = page.locator(".exhibition-card:visible");
    await expect(visibleCards).toHaveCount(0);
    const empty = page.locator("[data-empty]");
    await expect(empty).toBeVisible();
  });

  test("reset button clears filters from empty-state", async ({ page }) => {
    // Reset button lives inside [data-empty]; only visible when 0 cards match.
    await page.goto("/exhibitions/?status=current&city=" + encodeURIComponent("부산"));
    await page.locator("[data-filter-reset]").click();
    await expect(page).toHaveURL(/\/exhibitions\/$/);
    const visibleCards = await page.locator(".exhibition-card:visible").count();
    expect(visibleCards).toBe(4);
  });
});

test.describe("Exhibition card media", () => {
  test("a missing cover image renders a quiet bilingual placeholder", async ({ page }) => {
    await page.goto("/exhibitions/");

    const card = page.locator('.exhibition-card[data-status="current"]');
    const media = card.locator(".exhibition-card__image-wrap");

    await expect(media).toHaveClass(/exhibition-card__image-wrap--missing/);
    await expect(card.locator(".exhibition-card__image")).toHaveCount(0);
    await expect(card.locator(".exhibition-card__image-fallback")).toBeVisible();
    await expect(card.locator(".exhibition-card__image-fallback")).toContainText(
      "이미지 없음"
    );
    await expect(card.locator(".exhibition-card__image-fallback")).toContainText(
      "NO IMAGE"
    );
  });

  test("a failed cover image never exposes broken alt text", async ({ page }) => {
    await page.route("https://stub/fx-002.jpg", (route) => route.abort());
    await page.goto("/exhibitions/");

    const card = page.locator('.exhibition-card[data-status="closing_soon"]');
    await expect(card.locator(".exhibition-card__image-wrap")).toHaveClass(
      /exhibition-card__image-wrap--missing/
    );
    await expect(card.locator(".exhibition-card__image")).toBeHidden();
    await expect(card.locator(".exhibition-card__image-fallback")).toBeVisible();
  });

  test("status chips over card media always use an opaque readable surface", async ({
    page,
  }) => {
    await page.goto("/exhibitions/");

    const currentChip = page
      .locator('.exhibition-card[data-status="current"] .status-chip')
      .first();
    await expect(currentChip).toHaveClass(/status-chip--inverted/);
    await expect(currentChip).toHaveCSS("background-color", "rgb(0, 0, 0)");
    await expect(currentChip).toHaveCSS("color", "rgb(255, 255, 255)");

    const urgentChip = page
      .locator('.exhibition-card[data-status="closing_soon"] .status-chip')
      .first();
    await expect(urgentChip).toHaveClass(/status-chip--accent/);
    await expect(urgentChip).toHaveCSS("background-color", "rgb(255, 84, 0)");
    await expect(urgentChip).toHaveCSS("color", "rgb(255, 255, 255)");
  });
});

test.describe("Exhibition detail media", () => {
  test("a failed hero image is hidden behind the quiet title fallback", async ({
    page,
  }) => {
    await page.route("https://stub/fx-002.jpg", (route) => route.abort());
    await page.goto("/exhibitions/line-and-form-fx-0/");

    const hero = page.locator(".detail-page__hero");
    await expect(hero).toHaveClass(/detail-page__hero--missing/);
    await expect(hero.locator(".detail-page__hero-image")).toBeHidden();
  });
});
