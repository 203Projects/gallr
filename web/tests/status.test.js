// Unit test for scripts/lib/status.js
// Run: node tests/status.test.js (also runs as part of `npm test`)

const assert = require("assert").strict;
const { classify, STATUSES } = require("../scripts/lib/status.js");

const TODAY = "2026-05-15";

// classify(opening, closing, today) → "current" | "opening_soon" | "closing_soon" | "closed"
const cases = [
  // [label, opening, closing, today, expected]
  ["fully open in window",            "2026-04-01", "2026-08-01", TODAY, "current"],
  ["closes today",                    "2026-04-01", "2026-05-15", TODAY, "closing_soon"],
  ["closes in 7 days exactly",        "2026-04-01", "2026-05-22", TODAY, "closing_soon"],
  ["closes in 8 days (just outside)", "2026-04-01", "2026-05-23", TODAY, "current"],
  ["closed yesterday",                "2026-04-01", "2026-05-14", TODAY, "closed"],
  ["opens tomorrow",                  "2026-05-16", "2026-08-01", TODAY, "opening_soon"],
  ["opens in 7 days exactly",         "2026-05-22", "2026-08-01", TODAY, "opening_soon"],
  ["opens in 8 days (just outside)",  "2026-05-23", "2026-08-01", TODAY, "opening_soon"],
  // Future-but-not-yet-open: "opening_soon" applies to ALL future openings (catalog purposes).
  ["opens in 30 days",                "2026-06-14", "2026-08-01", TODAY, "opening_soon"],
];

for (const [label, opening, closing, today, expected] of cases) {
  const actual = classify(opening, closing, today);
  assert.equal(actual, expected, `${label}: expected ${expected}, got ${actual} (open=${opening} close=${closing})`);
}

// STATUSES export must list exactly these four, in this order, for sidebar rendering
assert.deepEqual(STATUSES, ["current", "opening_soon", "closing_soon", "closed"]);

console.log("[status.test] all tests passed");
