#!/usr/bin/env node

const assert = require("assert").strict;
const { seoulDateIso } = require("../scripts/lib/site-date.js");

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

console.log("✓ site-date.test.js — Seoul calendar boundary passed");
