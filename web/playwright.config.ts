import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./tests",
  testMatch: "**/*.test.ts",
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,

  globalSetup: require.resolve("./tests/global-setup.ts"),
  globalTeardown: require.resolve("./tests/global-teardown.ts"),

  use: {
    baseURL: "http://localhost:4242",
  },

  // Auto-start a static file server against the built dist/
  webServer: {
    command: "serve dist -l 4242 --no-clipboard",
    url: "http://localhost:4242",
    reuseExistingServer: false,
    timeout: 30000,
  },

  projects: [
    {
      // Existing structural smoke tests run with JS disabled — proves the
      // site is useful without runtime JS.
      name: "chromium",
      testMatch: "**/smoke.test.ts",
      use: { ...devices["Desktop Chrome"], javaScriptEnabled: false },
    },
    {
      // Editorial tests verify motion primitives, kinetic word, sticky
      // header, and deterministic homepage visuals — these need JS enabled.
      name: "chromium-js",
      testMatch: /(editorial|homepage-visual)\.test\.ts/,
      use: { ...devices["Desktop Chrome"], javaScriptEnabled: true },
    },
    {
      // Mobile viewport tests for the fluid redesign — type scale,
      // section rhythm, CTA pair stacking, grid column count, and mobile nav.
      name: "chromium-mobile",
      testMatch: /(type-scale|section-rhythm|cta-pair|now-showing-grid|image-fallback|hero-layout|mobile-nav)\.test\.ts/,
      use: { ...devices["Pixel 5"], javaScriptEnabled: true },
    },
    {
      // Catalog tests (discover filter, detail page) — fixture seed swapped
      // in by globalSetup, JS enabled.
      name: "chromium-catalog",
      testMatch: /(discover-filter|detail-page|exhibition-imagery|map-page|privacy-page|rsvp-page)\.test\.ts/,
      use: { ...devices["Desktop Chrome"], javaScriptEnabled: true },
    },
  ],
});
