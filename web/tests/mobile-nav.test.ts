import { test, expect, type Page } from "@playwright/test";

// On gallrmap.com, all primary nav links must remain available on mobile.
// This test pins them visible on mobile, with two presentation tiers:
//   - ≤511px: Korean labels only (the .bi-en spans are hidden)
//   - 512–640px: bilingual (both Korean and English visible)
// The desktop floating CTA (다운로드) must stay hidden on mobile.

async function displayValue(page: Page, selector: string): Promise<string> {
  return await page.locator(selector).first().evaluate((el) => {
    return getComputedStyle(el).display;
  });
}

async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  await page.evaluate(async () => {
    await document.fonts.ready;
    await new Promise<void>((resolve) => {
      requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
    });
  });

  const snapshot = await page.evaluate(() => {
    const root = document.documentElement;
    const clientWidth = root.clientWidth;
    const describe = (element: Element): string => {
      const id = element.id ? `#${element.id}` : "";
      const classes = Array.from(element.classList)
        .map((className) => `.${className}`)
        .join("");
      return `${element.tagName.toLowerCase()}${id}${classes}`;
    };
    const overflowingElements = Array.from(document.body.querySelectorAll("*"))
      .map((element) => {
        const rect = element.getBoundingClientRect();
        return {
          selector: describe(element),
          left: Math.round(rect.left * 100) / 100,
          right: Math.round(rect.right * 100) / 100,
          width: Math.round(rect.width * 100) / 100,
        };
      })
      .filter(({ left, right }) => left < -0.5 || right > clientWidth + 0.5)
      .slice(0, 12);

    return {
      clientWidth,
      scrollWidth: root.scrollWidth,
      overflowingElements,
    };
  });

  expect(
    snapshot.scrollWidth,
    `Horizontal overflow: ${JSON.stringify(snapshot, null, 2)}`,
  ).toBeLessThanOrEqual(snapshot.clientWidth);
}

test.describe("mobile site nav at 375px", () => {
  test.use({ viewport: { width: 375, height: 800 } });

  test("nav container and all four links are visible; English half and CTA hidden", async ({ page }) => {
    await page.goto("/");

    await expect(page.locator(".site-nav")).toBeVisible();

    const links = page.locator(".site-nav__link");
    await expect(links).toHaveCount(4);
    await expect(page.locator('.site-nav__link[href="https://gallery.gallermap.com/"]')).toBeVisible();

    expect(await displayValue(page, ".site-nav .bi-en")).toBe("none");
    expect(await displayValue(page, ".site-header__cta")).toBe("none");
    await expectNoHorizontalOverflow(page);
  });
});

test.describe("mobile site nav at 414px", () => {
  test.use({ viewport: { width: 414, height: 800 } });

  test("nav visible, English half hidden below the 512px cutoff, CTA hidden", async ({ page }) => {
    await page.goto("/");

    await expect(page.locator(".site-nav")).toBeVisible();
    expect(await displayValue(page, ".site-nav .bi-en")).toBe("none");
    expect(await displayValue(page, ".site-header__cta")).toBe("none");
    await expectNoHorizontalOverflow(page);
  });
});

test.describe("mobile site nav at 480px", () => {
  test.use({ viewport: { width: 480, height: 800 } });

  test("nav visible, English half hidden below the 512px cutoff, CTA still hidden", async ({ page }) => {
    await page.goto("/");

    await expect(page.locator(".site-nav")).toBeVisible();
    expect(await displayValue(page, ".site-nav .bi-en")).toBe("none");
    expect(await displayValue(page, ".site-header__cta")).toBe("none");
    await expectNoHorizontalOverflow(page);
  });
});

test.describe("mobile site nav at 512px", () => {
  test.use({ viewport: { width: 512, height: 800 } });

  test("nav visible, English half restored at the 512px cutoff, CTA still hidden", async ({ page }) => {
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
