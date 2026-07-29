#!/usr/bin/env node
// Node-only test for scripts/fetch-showcase.js
// Run: node tests/showcase.test.js
//
// Covers:
//  1. Seed-fallback path (env vars absent, not on Vercel) — exit 0, source: "seed".
//  2. Live path with stubbed fetch — exit 0, source: "supabase", rows preserved in
//     fetch order (script trusts Supabase's ORDER BY rather than re-sorting client-side),
//     camelcase translation correct.
//  3. Empty curated set locally — seed fallback (exit 0).
//  4. Empty curated set on Vercel (VERCEL=1) — hard fail (non-zero exit).
//  5. HTTP error on Vercel — hard fail (non-zero exit).
//
// Each test runs the script in a fresh mkdtemp directory so the real
// web/_data/showcase.json is never touched. The script computes its paths via
// path.join(__dirname, ".."), so copying scripts/fetch-showcase.js and
// scripts/showcase-seed.json into <tempdir>/scripts/ causes it to write to
// <tempdir>/_data/showcase.json automatically.

const fs = require("fs");
const path = require("path");
const os = require("os");
const { spawnSync } = require("child_process");
const assert = require("assert").strict;

const ROOT = path.join(__dirname, "..");
const REAL_SCRIPT = path.join(ROOT, "scripts", "fetch-showcase.js");
const REAL_SEED = path.join(ROOT, "scripts", "showcase-seed.json");
const REAL_SOURCE_MODULE = path.join(
  ROOT,
  "scripts",
  "lib",
  "exhibition-reader-source.js"
);

function makeTempProject() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "fetch-showcase-"));
  fs.mkdirSync(path.join(dir, "scripts", "lib"), { recursive: true });
  fs.copyFileSync(REAL_SCRIPT, path.join(dir, "scripts", "fetch-showcase.js"));
  fs.copyFileSync(
    path.join(ROOT, "scripts", "supabase-api-headers.js"),
    path.join(dir, "scripts", "supabase-api-headers.js")
  );
  fs.copyFileSync(REAL_SEED, path.join(dir, "scripts", "showcase-seed.json"));
  fs.copyFileSync(
    REAL_SOURCE_MODULE,
    path.join(dir, "scripts", "lib", "exhibition-reader-source.js")
  );
  return dir;
}

function cleanupTempProject(dir) {
  fs.rmSync(dir, { recursive: true, force: true });
}

function outputPath(dir) {
  return path.join(dir, "_data", "showcase.json");
}

function readOutput(dir) {
  return JSON.parse(fs.readFileSync(outputPath(dir), "utf8"));
}

function runScript(dir, env) {
  return spawnSync("node", [path.join(dir, "scripts", "fetch-showcase.js")], {
    cwd: dir,
    env: { ...process.env, ...env },
    encoding: "utf8",
  });
}

function runScriptWithStubbedFetch(dir, { env, fetchImpl }) {
  const scriptInTemp = path.join(dir, "scripts", "fetch-showcase.js");
  const wrapperPath = path.join(dir, `wrapper-${Date.now()}-${Math.random().toString(36).slice(2)}.js`);
  const wrapper = `
    global.fetch = ${fetchImpl};
    require(${JSON.stringify(scriptInTemp)});
  `;
  fs.writeFileSync(wrapperPath, wrapper);
  return spawnSync("node", [wrapperPath], {
    cwd: dir,
    env: { ...process.env, ...env },
    encoding: "utf8",
  });
}

// Test 1: seed-fallback when env vars are absent
(function testSeedFallback() {
  const dir = makeTempProject();
  try {
    const env = {
      SUPABASE_URL: "",
      SUPABASE_ANON_KEY: "",
      VERCEL: "",
      GALLR_EXHIBITION_SOURCE: "",
    };
    const result = runScript(dir, env);
    assert.equal(result.status, 0, "seed fallback exits 0 locally");
    assert(fs.existsSync(outputPath(dir)), "showcase.json written");
    const data = readOutput(dir);
    assert.equal(data.source, "seed", "source is 'seed'");
    assert.equal(data.readerSource, "legacy", "blank source defaults to legacy");
    assert(Array.isArray(data.exhibitions), "exhibitions is an array");
    assert(data.exhibitions.length >= 1, "at least one exhibition");
    for (const ex of data.exhibitions) {
      for (const k of ["id", "titleKo", "titleEn", "venueKo", "venueEn", "openingDate", "closingDate", "coverImageUrl", "status", "statusLabelKo"]) {
        assert(k in ex, `exhibition ${ex.id} missing field ${k}`);
      }
    }
    console.log("✓ test 1: seed fallback");
  } finally {
    cleanupTempProject(dir);
  }
})();

// Test 2: live path with stubbed fetch — verifies the script preserves Supabase's
// row order (i.e. doesn't re-sort client-side; it trusts the order=closing_date.asc
// clause in the REST query).
(function testLivePathHappy() {
  const dir = makeTempProject();
  try {
    const fetchImpl = `async (url, opts) => {
      const parsed = new URL(url);
      if (parsed.pathname !== "/rest/v1/exhibitions") throw new Error("wrong legacy resource");
      if (parsed.searchParams.get("order") !== "closing_date.asc,id.asc") throw new Error("wrong order");
      return {
        ok: true,
        status: 200,
        json: async () => ([
        { id: "a", name_ko: "전시 A", name_en: "Show A", venue_name_ko: "베뉴 A", venue_name_en: "Venue A", opening_date: "2026-01-01", closing_date: "2026-06-01", cover_image_url: "https://stub/a.jpg" },
        { id: "b", name_ko: "전시 B", name_en: "Show B", venue_name_ko: "베뉴 B", venue_name_en: "Venue B", opening_date: "2026-01-01", closing_date: "2026-07-01", cover_image_url: "https://stub/b.jpg" },
        ]),
      };
    }`;
    const env = {
      SUPABASE_URL: "https://stub.supabase.co",
      SUPABASE_ANON_KEY: "stub",
      VERCEL: "",
      GALLR_EXHIBITION_SOURCE: "legacy",
    };
    const result = runScriptWithStubbedFetch(dir, { env, fetchImpl });
    assert.equal(result.status, 0, `live path exits 0; stderr=${result.stderr}`);
    const data = readOutput(dir);
    assert.equal(data.source, "supabase", "source is 'supabase'");
    assert.equal(data.readerSource, "legacy");
    assert.equal(data.exhibitions.length, 2, "2 rows in output");
    assert.equal(data.exhibitions[0].id, "a", "first row preserved from fetch order");
    assert.equal(data.exhibitions[0].titleKo, "전시 A", "name_ko → titleKo");
    assert.equal(data.exhibitions[0].venueKo, "베뉴 A", "venue_name_ko → venueKo");
    assert.equal(data.exhibitions[0].coverImageUrl, "https://stub/a.jpg", "cover_image_url → coverImageUrl");
    console.log("✓ test 2: live path happy");
  } finally {
    cleanupTempProject(dir);
  }
})();

// Test 3: empty curated set locally → seed fallback
(function testEmptyResultLocal() {
  const dir = makeTempProject();
  try {
    const fetchImpl = `async () => ({ ok: true, status: 200, json: async () => [] })`;
    const env = { SUPABASE_URL: "https://stub.supabase.co", SUPABASE_ANON_KEY: "stub", VERCEL: "", GALLR_EXHIBITION_SOURCE: "" };
    const result = runScriptWithStubbedFetch(dir, { env, fetchImpl });
    assert.equal(result.status, 0, "empty result falls back locally");
    const data = readOutput(dir);
    assert.equal(data.source, "seed", "fallback to seed");
    console.log("✓ test 3: empty result local → seed");
  } finally {
    cleanupTempProject(dir);
  }
})();

// Test 4: empty curated set on Vercel → hard fail
(function testEmptyResultVercel() {
  const dir = makeTempProject();
  try {
    const fetchImpl = `async () => ({ ok: true, status: 200, json: async () => [] })`;
    const env = { SUPABASE_URL: "https://stub.supabase.co", SUPABASE_ANON_KEY: "stub", VERCEL: "1", GALLR_EXHIBITION_SOURCE: "" };
    const result = runScriptWithStubbedFetch(dir, { env, fetchImpl });
    assert.notEqual(result.status, 0, "empty result hard-fails on Vercel");
    assert(/FATAL/i.test(result.stderr) || /FATAL/i.test(result.stdout), "FATAL log emitted");
    console.log("✓ test 4: empty result on Vercel → hard fail");
  } finally {
    cleanupTempProject(dir);
  }
})();

// Test 5: HTTP error on Vercel → hard fail
(function testHttpErrorVercel() {
  const dir = makeTempProject();
  try {
    const fetchImpl = `async () => ({ ok: false, status: 500, json: async () => ({ error: "boom" }) })`;
    const env = { SUPABASE_URL: "https://stub.supabase.co", SUPABASE_ANON_KEY: "stub", VERCEL: "1", GALLR_EXHIBITION_SOURCE: "" };
    const result = runScriptWithStubbedFetch(dir, { env, fetchImpl });
    assert.notEqual(result.status, 0, "HTTP 500 hard-fails on Vercel");
    console.log("✓ test 5: HTTP error on Vercel → hard fail");
  } finally {
    cleanupTempProject(dir);
  }
})();

// Test 6: canonical-v2 uses only its fixed resource and records the source.
(function testCanonicalV2Endpoint() {
  const dir = makeTempProject();
  try {
    const fetchImpl = `async (url) => {
      const parsed = new URL(url);
      if (parsed.pathname !== "/rest/v1/exhibition_catalog_v2") throw new Error("wrong v2 resource");
      if (parsed.searchParams.get("order") !== "closing_date.asc,id.asc") throw new Error("wrong order");
      return {
        ok: true,
        status: 200,
        json: async () => ([{
          id: "v2-a",
          name_ko: "정식 전시",
          name_en: "Canonical Show",
          venue_name_ko: "정식 베뉴",
          venue_name_en: "Canonical Venue",
          opening_date: "2026-01-01",
          closing_date: "2026-07-01",
          cover_image_url: "https://stub/v2-a.jpg"
        }]),
      };
    }`;
    const env = {
      SUPABASE_URL: "https://stub.supabase.co",
      SUPABASE_ANON_KEY: "stub",
      VERCEL: "",
      GALLR_EXHIBITION_SOURCE: "canonical-v2",
    };
    const result = runScriptWithStubbedFetch(dir, { env, fetchImpl });
    assert.equal(result.status, 0, `canonical path exits 0; stderr=${result.stderr}`);
    const data = readOutput(dir);
    assert.equal(data.source, "supabase");
    assert.equal(data.readerSource, "canonical-v2");
    assert.deepEqual(data.exhibitions.map((item) => item.id), ["v2-a"]);
    console.log("✓ test 6: canonical-v2 endpoint");
  } finally {
    cleanupTempProject(dir);
  }
})();

// Test 7: invalid flags fail configuration and never fall back to seed.
(function testInvalidSource() {
  const dir = makeTempProject();
  try {
    const result = runScript(dir, {
      SUPABASE_URL: "",
      SUPABASE_ANON_KEY: "",
      VERCEL: "",
      GALLR_EXHIBITION_SOURCE: "canonical-v3",
    });
    assert.notEqual(result.status, 0, "invalid source exits non-zero");
    assert.match(`${result.stdout}\n${result.stderr}`, /invalid GALLR_EXHIBITION_SOURCE/);
    assert.equal(fs.existsSync(outputPath(dir)), false, "invalid source writes no fallback");
    console.log("✓ test 7: invalid source fails closed");
  } finally {
    cleanupTempProject(dir);
  }
})();

console.log("✓ showcase.test.js — all 7 tests passed");
