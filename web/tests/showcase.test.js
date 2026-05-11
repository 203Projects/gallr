#!/usr/bin/env node
// Node-only test for scripts/fetch-showcase.js
// Run: node tests/showcase.test.js
//
// Covers:
//  1. Seed-fallback path (env vars absent, not on Vercel) — exit 0, source: "seed".
//  2. Live path with stubbed fetch — exit 0, source: "supabase", rows in closing_date asc order, camelcase translation correct.
//  3. Empty curated set locally — seed fallback (exit 0).
//  4. Empty curated set on Vercel (VERCEL=1) — hard fail (non-zero exit).
//  5. HTTP error on Vercel — hard fail (non-zero exit).

const fs = require("fs");
const path = require("path");
const os = require("os");
const { spawnSync } = require("child_process");
const assert = require("assert").strict;

const ROOT = path.join(__dirname, "..");
const SCRIPT = path.join(ROOT, "scripts", "fetch-showcase.js");
const OUTPUT = path.join(ROOT, "_data", "showcase.json");

function clearOutput() {
  if (fs.existsSync(OUTPUT)) fs.unlinkSync(OUTPUT);
}

function readOutput() {
  return JSON.parse(fs.readFileSync(OUTPUT, "utf8"));
}

function runScript(env) {
  return spawnSync("node", [SCRIPT], {
    cwd: ROOT,
    env: { ...process.env, ...env },
    encoding: "utf8",
  });
}

function runScriptWithStubbedFetch({ env, fetchImpl }) {
  const wrapperPath = path.join(os.tmpdir(), `fetch-showcase-wrapper-${Date.now()}-${Math.random().toString(36).slice(2)}.js`);
  const wrapper = `
    global.fetch = ${fetchImpl};
    require(${JSON.stringify(SCRIPT)});
  `;
  fs.writeFileSync(wrapperPath, wrapper);
  try {
    return spawnSync("node", [wrapperPath], {
      cwd: ROOT,
      env: { ...process.env, ...env },
      encoding: "utf8",
    });
  } finally {
    fs.unlinkSync(wrapperPath);
  }
}

// Test 1: seed-fallback when env vars are absent
(function testSeedFallback() {
  clearOutput();
  const env = { SUPABASE_URL: "", SUPABASE_ANON_KEY: "", VERCEL: "" };
  const result = runScript(env);
  assert.equal(result.status, 0, "seed fallback exits 0 locally");
  assert(fs.existsSync(OUTPUT), "showcase.json written");
  const data = readOutput();
  assert.equal(data.source, "seed", "source is 'seed'");
  assert(Array.isArray(data.exhibitions), "exhibitions is an array");
  assert(data.exhibitions.length >= 1, "at least one exhibition");
  for (const ex of data.exhibitions) {
    for (const k of ["id", "titleKo", "titleEn", "venueKo", "venueEn", "openingDate", "closingDate", "coverImageUrl", "status", "statusLabelKo"]) {
      assert(k in ex, `exhibition ${ex.id} missing field ${k}`);
    }
  }
  console.log("✓ test 1: seed fallback");
})();

// Test 2: live path with stubbed fetch
(function testLivePathHappy() {
  clearOutput();
  const fetchImpl = `async (url, opts) => ({
    ok: true,
    status: 200,
    json: async () => ([
      { id: "a", name_ko: "전시 A", name_en: "Show A", venue_name_ko: "베뉴 A", venue_name_en: "Venue A", opening_date: "2026-01-01", closing_date: "2026-06-01", cover_image_url: "https://stub/a.jpg" },
      { id: "b", name_ko: "전시 B", name_en: "Show B", venue_name_ko: "베뉴 B", venue_name_en: "Venue B", opening_date: "2026-01-01", closing_date: "2026-07-01", cover_image_url: "https://stub/b.jpg" },
    ]),
  })`;
  const env = { SUPABASE_URL: "https://stub.supabase.co", SUPABASE_ANON_KEY: "stub", VERCEL: "" };
  const result = runScriptWithStubbedFetch({ env, fetchImpl });
  assert.equal(result.status, 0, `live path exits 0; stderr=${result.stderr}`);
  const data = readOutput();
  assert.equal(data.source, "supabase", "source is 'supabase'");
  assert.equal(data.exhibitions.length, 2, "2 rows in output");
  assert.equal(data.exhibitions[0].id, "a", "first row preserved from fetch");
  assert.equal(data.exhibitions[0].titleKo, "전시 A", "name_ko → titleKo");
  assert.equal(data.exhibitions[0].venueKo, "베뉴 A", "venue_name_ko → venueKo");
  assert.equal(data.exhibitions[0].coverImageUrl, "https://stub/a.jpg", "cover_image_url → coverImageUrl");
  console.log("✓ test 2: live path happy");
})();

// Test 3: empty curated set locally → seed fallback
(function testEmptyResultLocal() {
  clearOutput();
  const fetchImpl = `async () => ({ ok: true, status: 200, json: async () => [] })`;
  const env = { SUPABASE_URL: "https://stub.supabase.co", SUPABASE_ANON_KEY: "stub", VERCEL: "" };
  const result = runScriptWithStubbedFetch({ env, fetchImpl });
  assert.equal(result.status, 0, "empty result falls back locally");
  const data = readOutput();
  assert.equal(data.source, "seed", "fallback to seed");
  console.log("✓ test 3: empty result local → seed");
})();

// Test 4: empty curated set on Vercel → hard fail
(function testEmptyResultVercel() {
  clearOutput();
  const fetchImpl = `async () => ({ ok: true, status: 200, json: async () => [] })`;
  const env = { SUPABASE_URL: "https://stub.supabase.co", SUPABASE_ANON_KEY: "stub", VERCEL: "1" };
  const result = runScriptWithStubbedFetch({ env, fetchImpl });
  assert.notEqual(result.status, 0, "empty result hard-fails on Vercel");
  assert(/FATAL/i.test(result.stderr) || /FATAL/i.test(result.stdout), "FATAL log emitted");
  console.log("✓ test 4: empty result on Vercel → hard fail");
})();

// Test 5: HTTP error on Vercel → hard fail
(function testHttpErrorVercel() {
  clearOutput();
  const fetchImpl = `async () => ({ ok: false, status: 500, json: async () => ({ error: "boom" }) })`;
  const env = { SUPABASE_URL: "https://stub.supabase.co", SUPABASE_ANON_KEY: "stub", VERCEL: "1" };
  const result = runScriptWithStubbedFetch({ env, fetchImpl });
  assert.notEqual(result.status, 0, "HTTP 500 hard-fails on Vercel");
  console.log("✓ test 5: HTTP error on Vercel → hard fail");
})();

console.log("✓ showcase.test.js — all 5 tests passed");
