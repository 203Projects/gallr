import { expect, test, type Page } from "@playwright/test";

const deterministicPixel = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64",
);

async function prepareHomepage(page: Page) {
  await page.route("**/storage/v1/object/public/exhibition-images/**", (route) =>
    route.fulfill({
      status: 200,
      contentType: "image/png",
      body: deterministicPixel,
    }),
  );
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/");
  await page.evaluate(() => document.fonts.ready);
  await expect(page.locator(".hero__meta-right")).toHaveText("2026 / 05");
  await expect(page.locator(".now-showing__date")).toHaveText("2026 / 05");
}

test("desktop homepage hero matches its visual baseline", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await prepareHomepage(page);

  await expect(page.locator(".hero")).toHaveScreenshot(
    "homepage-hero-desktop.png",
    {
      animations: "disabled",
      caret: "hide",
      maxDiffPixelRatio: 0.02,
      scale: "css",
    },
  );
});

test("mobile homepage hero matches its visual baseline", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await prepareHomepage(page);

  await expect(page.locator(".hero")).toHaveScreenshot(
    "homepage-hero-mobile.png",
    {
      animations: "disabled",
      caret: "hide",
      maxDiffPixelRatio: 0.02,
      scale: "css",
    },
  );
});

test("curated homepage grid matches its visual baseline", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await prepareHomepage(page);
  await page.locator(".site-header").evaluate((element) => {
    (element as HTMLElement).style.visibility = "hidden";
  });

  await expect(page.locator("#now-showing")).toHaveScreenshot(
    "homepage-curated-grid-desktop.png",
    {
      animations: "disabled",
      caret: "hide",
      maxDiffPixelRatio: 0.02,
      scale: "css",
    },
  );
});
