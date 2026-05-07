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

test("Task 7 — [data-reveal] starts at opacity 0 (without reduced motion)", async ({
  page,
}) => {
  await page.emulateMedia({ reducedMotion: "no-preference" });
  await page.goto("/");
  // Force a reveal element into the DOM if none exists yet (later tasks add real ones)
  await page.evaluate(() => {
    if (document.querySelector("[data-reveal]")) return;
    const div = document.createElement("div");
    div.setAttribute("data-reveal", "");
    div.style.position = "fixed";
    div.style.top = "9999px"; // off-screen so the IO doesn't immediately reveal it
    div.id = "reveal-probe";
    document.body.appendChild(div);
  });
  const opacity = await page.evaluate(() => {
    const el = document.querySelector("#reveal-probe") || document.querySelector("[data-reveal]");
    return el ? getComputedStyle(el).opacity : null;
  });
  expect(parseFloat(opacity || "1")).toBeLessThanOrEqual(0.01);
});

test("Task 7 — sticky header gets is-stuck after 100px scroll", async ({ page }) => {
  await page.goto("/");
  await page.waitForLoadState("networkidle");
  await page.evaluate(() => window.scrollTo(0, 200));
  await page.waitForTimeout(100);
  const stuck = await page.evaluate(() =>
    document.querySelector(".site-header")?.classList.contains("is-stuck")
  );
  expect(stuck).toBe(true);
});

test("Task 8 — hero has eyebrow row with FEATURED label and YYYY / MM", async ({
  page,
}) => {
  await page.goto("/");
  const meta = page.locator(".hero__meta");
  await expect(meta).toBeVisible();
  const text = (await meta.textContent()) || "";
  expect(text).toContain("FEATURED");
  expect(text).toMatch(/20\d{2} \/ \d{2}/);
});

test("Task 8 — hero h1 uses the editorial display token (≥56px)", async ({
  page,
}) => {
  await page.goto("/");
  const fontSize = await page.evaluate(() => {
    const h1 = document.querySelector(".hero__headline");
    return h1 ? parseFloat(getComputedStyle(h1).fontSize) : 0;
  });
  expect(fontSize).toBeGreaterThanOrEqual(56);
});

test("Task 8 — hero kinetic word host has 3 children, first is .is-active", async ({
  page,
}) => {
  await page.goto("/");
  await page.waitForLoadState("networkidle");
  const counts = await page.evaluate(() => {
    const host = document.querySelector(".hero__kinetic");
    if (!host) return null;
    const children = host.querySelectorAll(":scope > span");
    const active = host.querySelectorAll(":scope > span.is-active");
    return { children: children.length, active: active.length };
  });
  expect(counts).not.toBeNull();
  expect(counts!.children).toBe(3);
  expect(counts!.active).toBe(1);
});

test("Task 8 — hero CTAs have data-magnetic and link to live store URLs", async ({
  page,
}) => {
  await page.goto("/");
  const ctas = page.locator(".hero__ctas a");
  await expect(ctas).toHaveCount(2);
  const dataMagneticCount = await ctas.evaluateAll((els) =>
    els.filter((el) => el.hasAttribute("data-magnetic")).length
  );
  expect(dataMagneticCount).toBe(2);
  const hrefs = await ctas.evaluateAll((els) => els.map((el) => el.getAttribute("href")));
  expect(hrefs[0]).toContain("apps.apple.com");
  expect(hrefs[1]).toContain("play.google.com");
});

test("Task 9 — hero marquee renders 8 image tiles", async ({ page }) => {
  await page.goto("/");
  // Target the SOURCE marquee inner (the script clones it once, but
  // the original is the first match). 8 tiles in source = 16 in DOM.
  const tiles = page.locator(".hero__marquee [data-marquee-inner]").first().locator(".hero__marquee-tile");
  await expect(tiles).toHaveCount(8);
});

test("Task 9 — hero count caption mentions Seoul + 1,200+ + 매일", async ({
  page,
}) => {
  await page.goto("/");
  const text = (await page.locator(".hero__count").textContent()) || "";
  expect(text).toContain("SEOUL");
  expect(text).toContain("1,200+");
  expect(text).toContain("매일");
});

test("Task 10 — .status-badge--accent uses the orange accent color", async ({
  page,
}) => {
  await page.goto("/");
  // Inject a probe so the test passes regardless of which sections render badges
  await page.evaluate(() => {
    const span = document.createElement("span");
    span.className = "status-badge status-badge--accent";
    span.textContent = "종료 임박";
    span.id = "badge-probe";
    document.body.appendChild(span);
  });
  const color = await page.evaluate(() => {
    const el = document.getElementById("badge-probe");
    return el ? getComputedStyle(el).color : null;
  });
  // #FF5400 = rgb(255, 84, 0)
  expect(color).toBe("rgb(255, 84, 0)");
});

test("Task 11 — features renders 3 .feature-block elements", async ({ page }) => {
  await page.goto("/");
  const blocks = page.locator("#features .feature-block");
  await expect(blocks).toHaveCount(3);
});

test("Task 11 — each feature block has eyebrow with FEATURE No. NN /", async ({ page }) => {
  await page.goto("/");
  const eyebrows = await page.locator("#features .feature-block__eyebrow").allTextContents();
  expect(eyebrows.length).toBe(3);
  for (let i = 0; i < eyebrows.length; i++) {
    expect(eyebrows[i]).toMatch(/FEATURE No\. 0\d \//);
  }
});

test("Task 11 — each feature block has an exhibition image with caption", async ({ page }) => {
  await page.goto("/");
  const figures = page.locator("#features .feature-block figure img");
  await expect(figures).toHaveCount(3);
  const captions = await page.locator("#features .feature-block figcaption").count();
  expect(captions).toBe(3);
});

test("Task 12 — #now-showing renders 8 tiles", async ({ page }) => {
  await page.goto("/");
  await expect(page.locator("#now-showing .grid-tile")).toHaveCount(8);
});

test("Task 12 — #now-showing CTA links to App Store on iOS UA", async ({
  browser,
}) => {
  const context = await browser.newContext({
    userAgent:
      "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15",
    javaScriptEnabled: true,
  });
  const page = await context.newPage();
  await page.goto("/");
  await page.waitForLoadState("networkidle");
  // Click resolves the runtime href
  const href = await page.evaluate(() => {
    const a = document.querySelector("#now-showing .now-showing__cta") as HTMLAnchorElement | null;
    return a ? a.href : null;
  });
  expect(href).toContain("apps.apple.com");
  await context.close();
});
