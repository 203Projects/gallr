#!/usr/bin/env node

const assert = require("assert").strict;
const {
  currentYearMonth,
  seoulDateIso,
} = require("../scripts/lib/site-date.js");

assert.equal(
  seoulDateIso(new Date("2026-08-12T14:59:59Z")),
  "2026-08-12",
  "the Seoul catalogue date stays on August 12 before midnight KST"
);
assert.equal(
  seoulDateIso(new Date("2026-08-12T15:00:00Z")),
  "2026-08-13",
  "the Seoul catalogue date advances at midnight KST, not midnight UTC"
);

assert.equal(
  currentYearMonth("2026-05-10"),
  "2026 / 05",
  "the homepage month follows the deterministic Seoul-date override",
);

assert.throws(
  () => currentYearMonth("2026/05/10"),
  /YYYY-MM-DD/,
  "a malformed homepage date override fails closed",
);

console.log("✓ site-date.test.js — Seoul calendar boundary passed");
