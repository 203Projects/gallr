import { test, expect, type Page } from "@playwright/test";

// On gallrmap.com, all primary nav links must remain available on mobile.
// This test pins them visible on mobile, with two presentation tiers:
//   - ≤420px: Korean labels only (the .bi-en spans are hidden)
//   - 421–640px: bilingual (both Korean and English visible)
// The desktop floating CTA (다운로드) must stay hidden on mobile.

async function displayValue(page: Page, selector: string): Promise<string> {
  return await page.locator(selector).first().evaluate((el) => {
    return getComputedStyle(el).display;
  });
}

async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= document.documentElement.clientWidth,
    ),
  ).toBe(true);
}

test.describe("mobile site nav at 375px", () => {
  test.use({ viewport: { width: 375, height: 800 } });

  test("nav container and all four links are visible; English half and CTA hidden", async ({ page }) => {
    await page.goto("/");

    await expect(page.locator(".site-nav")).toBeVisible();

    const links = page.locator(".site-nav__link");
    await expect(links).toHaveCount(4);
    await expect(page.locator('.site-nav__link[href="/submit/"]')).toBeVisible();

    expect(await displayValue(page, ".site-nav .bi-en")).toBe("none");
    expect(await displayValue(page, ".site-header__cta")).toBe("none");
    await expectNoHorizontalOverflow(page);
  });
});

test.describe("mobile site nav at 414px", () => {
  test.use({ viewport: { width: 414, height: 800 } });

  test("nav visible, English half still hidden (below 421px cutoff), CTA hidden", async ({ page }) => {
    await page.goto("/");

    await expect(page.locator(".site-nav")).toBeVisible();
    expect(await displayValue(page, ".site-nav .bi-en")).toBe("none");
    expect(await displayValue(page, ".site-header__cta")).toBe("none");
    await expectNoHorizontalOverflow(page);
  });
});

test.describe("mobile site nav at 480px", () => {
  test.use({ viewport: { width: 480, height: 800 } });

  test("nav visible, English half restored (above 421px cutoff), CTA still hidden", async ({ page }) => {
    await page.goto("/");

    await expect(page.locator(".site-nav")).toBeVisible();
    expect(await displayValue(page, ".site-nav .bi-en")).toBe("inline");
    expect(await displayValue(page, ".site-header__cta")).toBe("none");
    await expectNoHorizontalOverflow(page);
  });
});

test.describe("mobile site nav at 640px", () => {
  test.use({ viewport: { width: 640, height: 800 } });

  test("still mobile presentation: bilingual, no pipe dividers, no CTA", async ({ page }) => {
    await page.goto("/");

    await expect(page.locator(".site-nav")).toBeVisible();
    expect(await displayValue(page, ".site-nav .bi-en")).toBe("inline");
    expect(await displayValue(page, ".site-header__cta")).toBe("none");
    await expectNoHorizontalOverflow(page);

    // Pipe dividers ('|') between links are dropped on mobile.
    // The pseudo-element ::before content for adjacent nav links is "none".
    const dividerContent = await page.locator(".site-nav__link").nth(1).evaluate((el) => {
      return getComputedStyle(el, "::before").content;
    });
    expect(dividerContent).toBe("none");
  });
});

test.describe("desktop site nav at 641px (just past mobile breakpoint)", () => {
  test.use({ viewport: { width: 641, height: 800 } });

  test("desktop presentation: bilingual, pipe dividers, no CTA-hide override", async ({ page }) => {
    await page.goto("/");

    await expect(page.locator(".site-nav")).toBeVisible();
    expect(await displayValue(page, ".site-nav .bi-en")).toBe("inline");
    await expectNoHorizontalOverflow(page);

    // The mobile rule `.site-header__cta { display: none }` no longer applies.
    // The CTA stays opacity:0 / pointer-events:none until the header is .is-stuck,
    // but its computed `display` value should be the base, not `none`.
    expect(await displayValue(page, ".site-header__cta")).not.toBe("none");

    // Pipe dividers ('|') between links are restored on desktop.
    const dividerContent = await page.locator(".site-nav__link").nth(1).evaluate((el) => {
      return getComputedStyle(el, "::before").content;
    });
    expect(dividerContent).toBe('"|"');
  });
});
