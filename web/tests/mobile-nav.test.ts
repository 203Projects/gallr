import { test, expect, type Page } from "@playwright/test";

// On gallrmap.com, the three primary nav links (전시 EXHIBITIONS,
// 지도 MAP, 소개 ABOUT) were hidden entirely on viewports ≤640px.
// This test pins them visible on mobile, with two presentation tiers:
//   - ≤420px: Korean labels only (the .bi-en spans are hidden)
//   - 421–640px: bilingual (both Korean and English visible)
// The desktop floating CTA (다운로드) must stay hidden on mobile.

async function displayValue(page: Page, selector: string): Promise<string> {
  return await page.locator(selector).first().evaluate((el) => {
    return getComputedStyle(el).display;
  });
}

test.describe("mobile site nav at 375px", () => {
  test.use({ viewport: { width: 375, height: 800 } });

  test("nav container and all three links are visible; English half and CTA hidden", async ({ page }) => {
    await page.goto("/");

    await expect(page.locator(".site-nav")).toBeVisible();

    const links = page.locator(".site-nav__link");
    await expect(links).toHaveCount(3);

    expect(await displayValue(page, ".site-nav .bi-en")).toBe("none");
    expect(await displayValue(page, ".site-header__cta")).toBe("none");
  });
});

test.describe("mobile site nav at 414px", () => {
  test.use({ viewport: { width: 414, height: 800 } });

  test("nav visible, English half still hidden (below 421px cutoff), CTA hidden", async ({ page }) => {
    await page.goto("/");

    await expect(page.locator(".site-nav")).toBeVisible();
    expect(await displayValue(page, ".site-nav .bi-en")).toBe("none");
    expect(await displayValue(page, ".site-header__cta")).toBe("none");
  });
});

test.describe("mobile site nav at 480px", () => {
  test.use({ viewport: { width: 480, height: 800 } });

  test("nav visible, English half restored (above 421px cutoff), CTA still hidden", async ({ page }) => {
    await page.goto("/");

    await expect(page.locator(".site-nav")).toBeVisible();
    expect(await displayValue(page, ".site-nav .bi-en")).toBe("inline");
    expect(await displayValue(page, ".site-header__cta")).toBe("none");
  });
});

test.describe("mobile site nav at 640px", () => {
  test.use({ viewport: { width: 640, height: 800 } });

  test("still mobile presentation: bilingual, no pipe dividers, no CTA", async ({ page }) => {
    await page.goto("/");

    await expect(page.locator(".site-nav")).toBeVisible();
    expect(await displayValue(page, ".site-nav .bi-en")).toBe("inline");
    expect(await displayValue(page, ".site-header__cta")).toBe("none");

    // Pipe dividers ('|') between links are dropped on mobile.
    // The pseudo-element ::before content for adjacent nav links is "none".
    const dividerContent = await page.locator(".site-nav__link").nth(1).evaluate((el) => {
      return getComputedStyle(el, "::before").content;
    });
    expect(dividerContent).toBe("none");
  });
});
