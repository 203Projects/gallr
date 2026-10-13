// Spec 089 (E-D10): the page's hours reader and status labels must agree with the app.
// The expected answers live in one checked-in file that the Kotlin host tests also check.
// Run: node tests/route-hours.test.js (also runs as part of `npm test`)

const assert = require("assert").strict;
const fs = require("fs");
const path = require("path");
const { parseOpeningHours, WEEKDAYS } = require("../api/_lib/opening-hours.js");
const { STATUS_LABELS } = require("../api/_lib/route-labels.js");

const parityPath = path.join(__dirname, "../../specs/089-personal-routes/contracts/opening-hours-parity.json");
const parity = JSON.parse(fs.readFileSync(parityPath, "utf8"));

assert.ok(parity.hours.length >= 89, `expected the golden and catalogue cases, found ${parity.hours.length}`);

for (const entry of parity.hours) {
  const actual = parseOpeningHours(entry.input);
  assert.equal(actual.completeness, entry.completeness, `${entry.id}: completeness for ${JSON.stringify(entry.input)}`);
  const week = {};
  for (const day of WEEKDAYS) {
    const opening = actual.byDay[day];
    week[day] = opening ? [opening.opens, opening.closes] : null;
  }
  const expectedWeek = entry.completeness === "UNKNOWN" ? emptyWeek() : entry.week;
  assert.deepEqual(week, expectedWeek, `${entry.id}: week for ${JSON.stringify(entry.input)}`);
}

assert.deepEqual(STATUS_LABELS, parity.statusLabels, "status labels drifted from the parity file");

// Null and blank inputs are unknown, never an error.
assert.equal(parseOpeningHours(null).completeness, "UNKNOWN");
assert.equal(parseOpeningHours("   ").completeness, "UNKNOWN");

function emptyWeek() {
  return Object.fromEntries(WEEKDAYS.map((day) => [day, null]));
}

console.log(`route-hours: ${parity.hours.length} parity entries match`);
