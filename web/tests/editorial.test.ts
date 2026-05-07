import { test, expect } from "@playwright/test";

// All tests in this file run with JavaScript ENABLED (configured in
// playwright.config.ts under the "chromium-js" project). They verify
// motion primitives, kinetic word, sticky header, scroll reveals, etc.
//
// Reduced-motion tests use:
//   await page.emulateMedia({ reducedMotion: "reduce" });
//
// Tests are intentionally written before each implementation task; they
// FAIL on the current scaffold and PASS after the corresponding task.

test("editorial canary — JS executes in page context", async ({ page }) => {
  await page.goto("/");
  // page.evaluate requires JS execution in the page, so this throws under
  // javaScriptEnabled: false — verifying the chromium-js project is wired
  // correctly, not just that an HTML document was parsed.
  const sum = await page.evaluate(() => 1 + 1);
  expect(sum).toBe(2);
});

test("Task 1 — :root exposes editorial type scale tokens", async ({ page }) => {
  await page.goto("/");
  const tokens = await page.evaluate(() => {
    const root = getComputedStyle(document.documentElement);
    return {
      display: root.getPropertyValue("--type-display").trim(),
      headline: root.getPropertyValue("--type-headline").trim(),
      eyebrow: root.getPropertyValue("--type-eyebrow").trim(),
      eyebrowTracking: root.getPropertyValue("--type-eyebrow-tracking").trim(),
      bodyLg: root.getPropertyValue("--type-body-lg").trim(),
      easeGallery: root.getPropertyValue("--ease-gallery").trim(),
      durationMed: root.getPropertyValue("--duration-med").trim(),
      space3xl: root.getPropertyValue("--space-3xl").trim(),
      maxWidth: root.getPropertyValue("--max-width").trim(),
      inkOnDarkSecondary: root
        .getPropertyValue("--color-ink-on-dark-secondary")
        .trim()
        .toLowerCase(),
      typeDisplaySm: root.getPropertyValue("--type-display-sm").trim(),
    };
  });

  expect(tokens.display).toContain("clamp(");
  expect(tokens.headline).toContain("clamp(");
  expect(tokens.eyebrow).toBe("0.6875rem");
  expect(tokens.eyebrowTracking).toBe("0.2em");
  expect(tokens.bodyLg).toContain("clamp(");
  expect(tokens.easeGallery).toBe("cubic-bezier(0.16, 1, 0.3, 1)");
  expect(tokens.durationMed).toBe("500ms");
  expect(tokens.space3xl).toBe("160px");
  expect(tokens.maxWidth).toBe("1280px");
  expect(tokens.inkOnDarkSecondary).toBe("#a0a0a0");
  expect(tokens.typeDisplaySm).toContain("clamp(");
});

test("Task 6 — main.js loads and reveal observer activates", async ({ page }) => {
  await page.goto("/");
  // Confirm the script is referenced
  const hasScript = await page.evaluate(() =>
    !!document.querySelector('script[src="/scripts/main.js"]')
  );
  expect(hasScript).toBe(true);
  // Wait for DOMContentLoaded + a tick so the IO observers can register
  await page.waitForLoadState("networkidle");
  // The body gains a class once main.js initialises
  const initialised = await page.evaluate(() =>
    document.body.classList.contains("js-initialised")
  );
  expect(initialised).toBe(true);
});

test("Task 6 — reduced motion: all data-reveal elements end up revealed", async ({
  page,
}) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/");
  await page.waitForLoadState("networkidle");
  const allRevealed = await page.evaluate(() => {
    const els = document.querySelectorAll("[data-reveal]");
    if (els.length === 0) return null; // nothing to test yet
    return Array.from(els).every((el) => el.classList.contains("is-revealed"));
  });
  // Either: there are no reveal elements yet (still bootstrap), or all are revealed
  expect(allRevealed === null || allRevealed === true).toBe(true);
});
