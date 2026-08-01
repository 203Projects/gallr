#!/usr/bin/env node
// pa11y WCAG AA accessibility audit across every multi-page catalog route.
// Asserts zero errors per page. Run after `npm run build`:
//   node tests/accessibility.test.js

const fs = require("fs");
const path = require("path");
const pa11y = require("pa11y");

const DIST = path.resolve(__dirname, "../dist");

function fileUrl(rel) {
  return `file://${path.join(DIST, rel)}`;
}

const routes = [
  { name: "home", file: "index.html" },
  { name: "discover", file: "exhibitions/index.html" },
  { name: "map", file: "map/index.html" },
  { name: "about", file: "about/index.html" },
  // HTML_CodeSniffer crashes on the initially hidden dynamic RSVP form;
  // pa11y's axe runner covers the same rendered page without that runner bug.
  { name: "rsvp", file: "rsvp/index.html", runners: ["axe"] },
];

// Conditionally add the first exhibition detail page (skipped on empty seed).
const exhibitions = require(path.join(DIST, "..", "_data", "exhibitions.json"));
const firstSlug = (exhibitions.exhibitions || [])[0]?.slug;
if (firstSlug) {
  routes.push({ name: `detail (${firstSlug})`, file: `exhibitions/${firstSlug}/index.html` });
}

async function audit(route) {
  const file = path.join(DIST, route.file);
  if (!fs.existsSync(file)) {
    console.error(`✗ ${route.name}: missing ${route.file}`);
    return false;
  }
  const url = fileUrl(route.file);
  console.log(`Running WCAG AA audit on ${route.name} (${url})`);
  let results;
  try {
    results = await pa11y(url, {
      standard: "WCAG2AA",
      ...(route.runners ? { runners: route.runners } : {}),
      ...(process.env.CI
        ? {
            chromeLaunchConfig: {
              args: ["--no-sandbox", "--disable-setuid-sandbox"],
            },
          }
        : {}),
      ignore: [
        // Color-contrast check on synthetic test rendering — same exemption
        // the original single-page audit carried.
        "WCAG2AA.Principle1.Guideline1_4.1_4_3.G18.Fail",
      ],
      includeNotices: false,
      includeWarnings: false,
      timeout: 30000,
    });
  } catch (err) {
    console.error(`✗ ${route.name}: pa11y failed: ${err.message}`);
    return false;
  }
  const errors = results.issues.filter((i) => i.type === "error");
  if (errors.length > 0) {
    console.error(`\n✗ ${route.name}: ${errors.length} WCAG AA violation(s):\n`);
    errors.forEach((issue, i) => {
      console.error(`  ${i + 1}. [${issue.code}]`);
      console.error(`     ${issue.message}`);
      console.error(`     Selector: ${issue.selector}\n`);
    });
    return false;
  }
  console.log(`✓ ${route.name}: clean`);
  return true;
}

(async () => {
  let allOk = true;
  for (const route of routes) {
    const ok = await audit(route);
    if (!ok) allOk = false;
  }
  if (!allOk) {
    console.error("\n✗ accessibility audit failed");
    process.exit(1);
  }
  console.log("\n✓ All routes pass WCAG AA.");
})();
