// Asserts that the build produced every page the multi-page catalog spec
// requires. Runs after `npm run build` and before Playwright. Designed to
// be meaningful with real data and a quiet no-op against the empty seed:
// the per-exhibition loop has zero iterations rather than failing.

const fs = require("fs");
const path = require("path");
const assert = require("assert").strict;

const ROOT = path.join(__dirname, "..");
const DIST = path.join(ROOT, "dist");
const exists = (p) => fs.existsSync(path.join(DIST, p));
const read = (p) => fs.readFileSync(path.join(DIST, p), "utf8");

const exhibitions = require(path.join(ROOT, "_data", "exhibitions.json"));

// 1. Top-level routes — these are seed-independent.
const routes = [
  "index.html",
  "exhibitions/index.html",
  "map/index.html",
  "about/index.html",
  "privacy/index.html",
];
for (const r of routes) assert.ok(exists(r), `route missing: ${r}`);

// 2. Per-exhibition detail pages — non-empty seed must have one HTML per row.
for (const ex of exhibitions.exhibitions || []) {
  assert.ok(
    exists(`exhibitions/${ex.slug}/index.html`),
    `detail page missing: exhibitions/${ex.slug}/index.html`
  );
}

// 3. Map page must always carry the JSON island (even empty).
const mapHtml = read("map/index.html");
assert.ok(
  mapHtml.includes('id="exhibitions-data"'),
  "map page missing JSON island #exhibitions-data"
);

const seedRows = (exhibitions.exhibitions || []).length;
console.log(
  `[multipage-build.test] all assertions passed (${seedRows} exhibition row${seedRows === 1 ? "" : "s"})`
);
