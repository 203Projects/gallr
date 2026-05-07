#!/usr/bin/env node
// Node-only test for scripts/fetch-showcase.js
// Run: node tests/showcase.test.js
//
// Tests the seed-fallback path (env vars absent). The live-fetch path
// is exercised in CI when SUPABASE_URL / SUPABASE_ANON_KEY are set.

const fs = require("fs");
const path = require("path");
const { execSync } = require("child_process");

const ROOT = path.join(__dirname, "..");
const OUTPUT = path.join(ROOT, "_data", "showcase.json");

function assert(cond, msg) {
  if (!cond) {
    console.error("✗ FAIL:", msg);
    process.exit(1);
  }
}

// Wipe any prior output
if (fs.existsSync(OUTPUT)) fs.unlinkSync(OUTPUT);

// Run the fetcher with no env vars set — must take the seed-fallback path
const env = { ...process.env };
delete env.SUPABASE_URL;
delete env.SUPABASE_ANON_KEY;

execSync(`node ${path.join(ROOT, "scripts", "fetch-showcase.js")}`, {
  cwd: ROOT,
  env,
  stdio: "inherit",
});

assert(fs.existsSync(OUTPUT), "showcase.json was not written");
const data = JSON.parse(fs.readFileSync(OUTPUT, "utf8"));

assert(data.source === "seed", `expected source 'seed', got '${data.source}'`);
assert(
  Array.isArray(data.exhibitions) && data.exhibitions.length === 12,
  "expected exhibitions.length === 12"
);

const required = [
  "id", "titleKo", "titleEn", "venueKo", "venueEn",
  "openingDate", "closingDate", "coverImageUrl", "status", "statusLabelKo",
];
for (const ex of data.exhibitions) {
  for (const k of required) {
    assert(k in ex, `exhibition ${ex.id} missing field ${k}`);
  }
}

const closing = data.exhibitions.filter((e) => e.status === "closing-soon");
assert(closing.length >= 1, "expected ≥1 closing-soon exhibition in seed");
for (const e of closing) {
  assert(e.statusLabelKo === "종료 임박", `closing-soon must have statusLabelKo '종료 임박'`);
}

console.log("✓ showcase.test.js — all assertions passed");
