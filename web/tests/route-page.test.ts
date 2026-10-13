// Spec 089 DR-D14, DR-D25, DR-D27, FR-023: the shared route page's order, landmarks, targets and layout.
import { test, expect } from "@playwright/test";
import * as fs from "fs";
import * as path from "path";

// eslint-disable-next-line @typescript-eslint/no-var-requires
const { renderRouteFixture } = require("./fixtures/route-page-fixture.js");

const DIST = path.resolve(__dirname, "..", "dist");
const AXE_SOURCE = fs.readFileSync(require.resolve("axe-core/axe.min.js", { paths: [path.resolve(__dirname, "..")] }), "utf8");
const FIXTURE_DIR = path.join(DIST, "route-fixtures");

test.beforeAll(() => {
  fs.mkdirSync(FIXTURE_DIR, { recursive: true });
  fs.writeFileSync(path.join(FIXTURE_DIR, "ko.html"), renderRouteFixture("ko", "light"));
  fs.writeFileSync(path.join(FIXTURE_DIR, "en.html"), renderRouteFixture("en", "light"));
  fs.writeFileSync(path.join(FIXTURE_DIR, "ko-dark.html"), renderRouteFixture("ko", "dark"));
  fs.writeFileSync(path.join(FIXTURE_DIR, "en-dark.html"), renderRouteFixture("en", "dark"));
});

test.describe("contrast", () => {
  for (const name of ["ko", "en", "ko-dark", "en-dark"]) {
    test(`meets 4.5:1 throughout (${name})`, async ({ page }) => {
      await page.goto(`/route-fixtures/${name}.html`);
      await page.addScriptTag({ content: AXE_SOURCE });
      const violations = await page.evaluate(async () => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const result = await (window as any).axe.run({ runOnly: ["color-contrast"] });
        return result.violations.flatMap((violation: { nodes: { target: string[] }[] }) =>
          violation.nodes.map((node) => node.target.join(" ")),
        );
      });
      expect(violations).toEqual([]);
    });
  }
});

test.afterAll(() => {
  fs.rmSync(FIXTURE_DIR, { recursive: true, force: true });
});

test.describe("phone, 375 px", () => {
  test.use({ viewport: { width: 375, height: 812 } });

  test("reads wordmark, eyebrow, name, then the verdict before the drawing", async ({ page }) => {
    await page.goto("/route-fixtures/ko.html");
    const order = await page.evaluate(() => {
      const nodes = [
        document.querySelector(".route-wordmark"),
        document.querySelector(".route-eyebrow"),
        document.querySelector("h1"),
        document.querySelector(".route-verdict"),
        document.querySelector(".route-figure"),
      ];
      return nodes.slice(1).map((node, index) =>
        Boolean(nodes[index]!.compareDocumentPosition(node!) & Node.DOCUMENT_POSITION_FOLLOWING),
      );
    });
    expect(order).toEqual([true, true, true, true]);
    await expect(page.locator("h1")).toHaveCount(1);
    await expect(page.locator("header")).toHaveCount(1);
    await expect(page.locator("main")).toHaveCount(1);
    await expect(page.locator("footer")).toHaveCount(1);
    await expect(page.locator("ol.route-stops > li")).toHaveCount(4);
    await expect(page.locator("svg[role=img]")).toHaveAttribute("aria-label", /방문 순서: 1 용산구, 2 종로구/);
  });

  test("keeps the verdict above the fold and the primary action on screen", async ({ page }) => {
    await page.goto("/route-fixtures/ko.html");
    const verdict = await page.locator(".route-verdict").boundingBox();
    expect(verdict!.y + verdict!.height).toBeLessThanOrEqual(812);
    const primary = page.locator(".route-primary");
    await expect(primary).toHaveText("첫 전시 길찾기");
    await page.mouse.wheel(0, 2000);
    await expect(primary).toBeInViewport();
  });

  test("labels every directions link and keeps 44 px targets", async ({ page }) => {
    await page.goto("/route-fixtures/ko.html");
    const links = page.locator(".route-stop__directions");
    // The stop that left the catalogue has no directions.
    await expect(links).toHaveCount(3);
    for (const link of await links.all()) {
      await expect(link).toHaveAttribute("aria-label", /길찾기$/);
      const box = await link.boundingBox();
      expect(box!.height).toBeGreaterThanOrEqual(44);
      expect(box!.width).toBeGreaterThanOrEqual(44);
    }
    const primary = await page.locator(".route-primary").boundingBox();
    expect(primary!.height).toBeGreaterThanOrEqual(44);
  });
});

test.describe("desktop, 1280 px", () => {
  test.use({ viewport: { width: 1280, height: 900 } });

  test("sets the page in a 640 px column with the primary action inline", async ({ page }) => {
    await page.goto("/route-fixtures/en.html");
    const main = await page.locator("main").boundingBox();
    expect(main!.width).toBe(640);
    expect(main!.x).toBe((1280 - 640) / 2);
    await expect(page.locator(".route-primary-bar")).toHaveCSS("position", "static");
    await expect(page.locator(".route-primary")).toHaveText("DIRECTIONS TO STOP 1");
  });
});
