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
