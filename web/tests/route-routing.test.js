// Spec 089 E-D11: the route function's rewrites shadow nothing the static site serves. The Vercel project
// builds from the repository root, so the effective config and the function entry point live there.
// Run after `npm run build`: node tests/route-routing.test.js (also runs as part of `npm test`)

const assert = require("assert").strict;
const fs = require("fs");
const path = require("path");

const ROOT = path.resolve(__dirname, "..");
const REPO = path.resolve(ROOT, "..");
const DIST = path.join(ROOT, "dist");
const config = JSON.parse(fs.readFileSync(path.join(REPO, "vercel.json"), "utf8"));
assert.equal(config.outputDirectory, "web/dist", "the root config builds the web site");
assert.ok(fs.existsSync(path.join(REPO, "api", "route.js")), "missing the root api/route.js function");
assert.equal(typeof require(path.join(REPO, "api", "route.js")), "function");
// The web-rooted config carries no rewrites, so the two cannot drift.
assert.equal(JSON.parse(fs.readFileSync(path.join(ROOT, "vercel.json"), "utf8")).rewrites, undefined);

const rewrites = config.rewrites || [];
assert.deepEqual(
  rewrites.find((rule) => rule.source === "/route/:id"),
  { source: "/route/:id", destination: "/api/route?id=:id" },
);
assert.deepEqual(
  rewrites.find((rule) => rule.source === "/route/:id/events"),
  { source: "/route/:id/events", destination: "/api/route?id=:id&events=1" },
);
// Every rewrite belongs to the route function; the rest of the site stays static.
for (const rule of rewrites) assert.match(rule.source, /^\/route\//, `unexpected rewrite ${rule.source}`);

// Nothing in the built site lives under /route/, so the rewrites shadow no page or asset.
assert.ok(fs.existsSync(DIST), "build dist/ first");
const shadowed = [];
(function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    const rel = "/" + path.relative(DIST, full).split(path.sep).join("/");
    if (rel === "/route" || rel.startsWith("/route/")) shadowed.push(rel);
    if (entry.isDirectory()) walk(full);
  }
})(DIST);
assert.deepEqual(shadowed, [], `built paths under /route/: ${shadowed.join(", ")}`);

// The page links the site's own stylesheets.
for (const sheet of ["styles/tokens.css", "styles/main.css"]) {
  assert.ok(fs.existsSync(path.join(DIST, sheet)), `missing dist/${sheet}`);
}
assert.match(fs.readFileSync(path.join(DIST, "styles/main.css"), "utf8"), /\.route-page/);

console.log("route-routing: ok");
