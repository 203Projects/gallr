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
