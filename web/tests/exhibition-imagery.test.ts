import { test, expect, type Page } from "@playwright/test";
import * as fs from "node:fs";
import * as path from "node:path";

const nunjucks = require("nunjucks");

// Exhibition imagery reads in full colour so the artwork carries the page.
// Monochrome is reserved as the signal for a run that has ended.
//
// Fixture rows (see tests/fixtures/exhibitions.json):
//   fx-002-closing-soon → line-and-form-fx-0      (running)
//   fx-004-closed       → monochrome-studies-fx-0 (ended)

// 1x1 transparent PNG — the fixture cover URLs are unresolvable stubs, so
// serve real bytes to keep the <img> loaded instead of falling back.
const PIXEL = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
  "base64",
);

// --duration-med is 500ms. Waiting past it before re-reading a computed style
// keeps a hover regression from slipping through mid-transition.
const TRANSITION_SETTLE_MS = 700;

async function serveCoverImages(page: Page) {
  await page.route("https://stub/**", (route) =>
    route.fulfill({ status: 200, contentType: "image/png", body: PIXEL }),
  );
}

function cardImage(page: Page, status: string) {
  const card = page.locator(`.exhibition-card[data-status="${status}"]`).first();
  return { card, image: card.locator(".exhibition-card__image") };
}

test.describe("Exhibition card imagery", () => {
  test("a running exhibition renders in colour and lifts on hover", async ({ page }) => {
    await serveCoverImages(page);
    await page.goto("/exhibitions/");

    const { card, image } = cardImage(page, "closing_soon");

    await expect(image).toBeVisible();
    await expect(image).toHaveCSS("filter", "none");
    await expect(image).toHaveCSS("transform", "none");

    await card.hover();
    await expect(image).toHaveCSS("transform", "matrix(1.03, 0, 0, 1.03, 0, 0)");
  });

  test("the hover lift is suppressed under reduced motion", async ({ page }) => {
    await serveCoverImages(page);
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.goto("/exhibitions/");

    const { card, image } = cardImage(page, "closing_soon");

    await expect(image).toBeVisible();
    await card.hover();
    await page.waitForTimeout(TRANSITION_SETTLE_MS);
    await expect(image).toHaveCSS("transform", "none");
    // Colour is a static treatment, not motion — it must survive the opt-out.
    await expect(image).toHaveCSS("filter", "none");
  });

  test("an ended exhibition stays monochrome, hovered or not", async ({ page }) => {
    await serveCoverImages(page);
    await page.goto("/exhibitions/");

    const { card, image } = cardImage(page, "closed");

    await expect(image).toBeVisible();
    await expect(image).toHaveCSS("filter", "grayscale(1)");

    await card.hover();
    await page.waitForTimeout(TRANSITION_SETTLE_MS);
    await expect(image).toHaveCSS("filter", "grayscale(1)");
  });
});

test.describe("Exhibition detail hero imagery", () => {
  test("a running exhibition's hero renders in colour", async ({ page }) => {
    await serveCoverImages(page);
    await page.goto("/exhibitions/line-and-form-fx-0/");

    await expect(page.locator(".detail-page__hero-image")).toHaveCSS("filter", "none");
  });

  test("an ended exhibition's hero renders monochrome", async ({ page }) => {
    await serveCoverImages(page);
    await page.goto("/exhibitions/monochrome-studies-fx-0/");

    const hero = page.locator(".detail-page__hero");
    await expect(hero).toHaveAttribute("data-status", "closed");
    await expect(hero.locator(".detail-page__hero-image")).toHaveCSS("filter", "grayscale(1)");
  });
});

test.describe("MICA follows the shared image and status-tag layout", () => {
  const cover = "https://oqrvbstopuppznxqoonp.supabase.co/storage/v1/object/public/exhibition-images/cms/847527a1-d780-4353-a02b-5653cd68eaee/original.jpg";

  test.beforeAll(() => {
    // Render the real templates in isolated routes; do not change the four-row
    // catalogue fixture or make the regression depend on a live Supabase read.
    const root = path.resolve(__dirname, "..");
    const env = new nunjucks.Environment(new nunjucks.FileSystemLoader(path.join(root, "_includes")));
    const ex = {
      id: "075d8567-73c5-47e8-8171-a95b85f036c5",
      slug: "__test-mica",
      name_ko: "PRAIN VILLA ART FAIR Edition 02 — MICA",
      venue_name_ko: "프레인빌라 B1 세르주 무이 라운지",
      city_ko: "서울", address_ko: "서울 강남구 삼성로122길 35",
      opening_date: "2026-10-28", closing_date: "2026-11-18",
      status: "opening_soon", cover_image_url: cover,
    };
    const document = (body: string) => `<!doctype html><html lang="ko"><head><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/styles/tokens.css"><link rel="stylesheet" href="/styles/main.css"></head><body>${body}</body></html>`;
    fs.writeFileSync(path.join(root, "dist/__test-mica-list.html"), document(
      `<main style="padding:24px;max-width:400px">${env.render("components/exhibition-card.njk", { ex })}</main>`,
    ));
    const detail = path.join(root, "dist/exhibitions/__test-mica");
    fs.mkdirSync(detail, { recursive: true });
    fs.writeFileSync(path.join(detail, "index.html"), document(env.render("detail-page.njk", { ex })));
  });

  for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
    test(`shared crop, tag placement and card-to-detail navigation at ${viewport.width}px`, async ({ page }) => {
      await page.setViewportSize(viewport);
      await page.route(cover, route => route.fulfill({
        contentType: "image/svg+xml",
        body: '<svg xmlns="http://www.w3.org/2000/svg" width="1300" height="1004"><rect width="1300" height="1004" fill="#ead477"/><rect x="60" y="30" width="1180" height="880" fill="#333"/></svg>',
      }));
      await page.goto("/__test-mica-list.html");
      const card = page.locator(".exhibition-card");
      const image = card.locator("img");
      const media = card.locator(".exhibition-card__image-wrap");
      await expect(image).toBeVisible();
      await expect(image).toHaveAttribute("src", cover);
      await expect(image).toHaveCSS("object-fit", "cover");
      await expect(media).toHaveCSS("background-color", "rgb(245, 245, 245)");
      await card.hover();
      await page.waitForTimeout(TRANSITION_SETTLE_MS);
      await expect(image).toHaveCSS("transform", "matrix(1.03, 0, 0, 1.03, 0, 0)");
      const mediaBox = await media.boundingBox();
      const chipBox = await card.locator(".status-chip").boundingBox();
      expect(chipBox!.y).toBeGreaterThanOrEqual(mediaBox!.y);
      expect(chipBox!.y + chipBox!.height).toBeLessThanOrEqual(mediaBox!.y + mediaBox!.height);
      await expect(card.locator(".exhibition-card__image-wrap .status-chip")).toHaveCount(1);

      await card.getByRole("link").click();
      await expect(page).toHaveURL(/\/exhibitions\/__test-mica\/$/);
      const hero = page.locator(".detail-page__hero");
      await expect(hero.locator("img")).toHaveCSS("object-fit", "cover");
      await expect(hero).toHaveCSS("background-color", "rgb(245, 245, 245)");
      await expect(hero.locator("img")).toHaveAttribute("src", cover);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    });
  }
});
