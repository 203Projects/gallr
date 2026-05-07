import { test, expect, Page } from "@playwright/test";

// Visual regression at four viewports. The first run with
// --update-snapshots creates baselines in tests/visual-regression.test.ts-snapshots/
// (Playwright's default location). Future runs compare against those.

const VIEWPORTS = [
  { name: "mobile-360", width: 360, height: 800 },
  { name: "mobile-414", width: 414, height: 896 },
  { name: "tablet-820", width: 820, height: 1180 },
  { name: "desktop-1440", width: 1440, height: 900 },
];

const SECTIONS = [
  { name: "hero", selector: ".hero" },
  { name: "features", selector: "#features" },
  { name: "now-showing", selector: ".now-showing" },
  { name: "downloads", selector: ".downloads" },
  { name: "about", selector: ".about" },
  { name: "footer", selector: ".site-footer" },
];

/**
 * Patch the page at navigation time to neutralise all JS motion primitives
 * before they start. Injected as an init script so it runs before main.js.
 */
const MOTION_PATCH = `
  // Stub requestAnimationFrame so marquee / stagger rAF callbacks no-op.
  window.requestAnimationFrame = () => 0;
  window.cancelAnimationFrame = () => {};

  // Stub setInterval so the kinetic word rotator never fires.
  window.setInterval = () => 0;
  window.clearInterval = () => {};

  // Stub IntersectionObserver so reveal/stagger callbacks never fire async.
  window.IntersectionObserver = class {
    constructor() {}
    observe() {}
    unobserve() {}
    disconnect() {}
  };
`;

/**
 * Settle the page into a deterministic frozen state after navigation:
 *  1. Pre-reveal all [data-reveal] elements.
 *  2. Hide the sticky header (prevents overlay on section screenshots).
 *  3. Scroll full page to trigger lazy images, then back to top.
 *  4. Wait for every <img> to finish loading / decoding.
 *  5. Pin the marquee CSS custom property and kinetic class state.
 *  6. Freeze CSS animations and transitions via a style tag.
 */
async function settleAndFreeze(page: Page): Promise<void> {
  await page.evaluate(() => {
    // Reveal all animated elements immediately.
    document
      .querySelectorAll<HTMLElement>("[data-reveal], [data-reveal-stagger], [data-reveal-stagger] > *")
      .forEach((el) => {
        el.classList.add("is-revealed");
        el.style.opacity = "1";
        el.style.transform = "none";
        el.style.transitionDelay = "0s";
      });

    // Hide the sticky header so it never overlaps section screenshots.
    const header = document.querySelector<HTMLElement>(".site-header");
    if (header) header.style.display = "none";
  });

  // Trigger lazy image loads by scrolling to the bottom then back to top.
  await page.evaluate(async () => {
    window.scrollTo(0, document.body.scrollHeight);
    await new Promise((r) => setTimeout(r, 300));
    window.scrollTo(0, 0);
    await new Promise((r) => setTimeout(r, 100));
  });

  // Wait for all images to finish loading and decoding.
  await page.evaluate(async () => {
    const imgs = Array.from(document.querySelectorAll<HTMLImageElement>("img"));
    await Promise.all(
      imgs.map((img) => {
        if (img.complete) return Promise.resolve();
        return new Promise<void>((resolve) => {
          img.addEventListener("load", () => resolve(), { once: true });
          img.addEventListener("error", () => resolve(), { once: true });
        });
      })
    );
    await Promise.all(imgs.map((img) => img.decode().catch(() => {})));
  });

  // Pin the marquee and kinetic state.
  await page.evaluate(() => {
    document.querySelectorAll<HTMLElement>("[data-marquee]").forEach((el) => {
      el.style.setProperty("--marquee-x", "0%");
    });
    document.querySelectorAll("[data-kinetic]").forEach((host) => {
      Array.from(host.querySelectorAll<HTMLElement>(":scope > span")).forEach((w, i) => {
        w.classList.toggle("is-active", i === 0);
      });
    });
  });

  // Freeze CSS animations and transitions.
  await page.addStyleTag({
    content: `
      *, *::before, *::after {
        animation-play-state: paused !important;
        animation-duration: 0s !important;
        transition-duration: 0s !important;
        transition-delay: 0s !important;
      }
    `,
  });

  // Let the style tag apply and micro-tasks settle.
  await page.waitForTimeout(200);
}

for (const vp of VIEWPORTS) {
  test.describe(`viewport ${vp.name}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height } });

    test("full page", async ({ page }) => {
      await page.addInitScript(MOTION_PATCH);
      await page.goto("/");
      await page.evaluate(() => document.fonts.ready);
      await page.waitForTimeout(800);
      await settleAndFreeze(page);
      await expect(page).toHaveScreenshot(`${vp.name}-full.png`, {
        fullPage: true,
        maxDiffPixels: 50,
      });
    });

    for (const sec of SECTIONS) {
      test(`section: ${sec.name}`, async ({ page }) => {
        await page.addInitScript(MOTION_PATCH);
        await page.goto("/");
        await page.evaluate(() => document.fonts.ready);
        await page.waitForTimeout(800);
        await settleAndFreeze(page);
        await expect(page.locator(sec.selector)).toHaveScreenshot(
          `${vp.name}-${sec.name}.png`,
          { maxDiffPixels: 50 }
        );
      });
    }
  });
}
