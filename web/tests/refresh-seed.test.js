// Unit test for scripts/refresh-seed.js — runs against a stubbed
// Supabase fetch and asserts the curated seed shape.
//
// Run directly: node tests/refresh-seed.test.js
// Exits with non-zero status on failure.

const path = require("path");
const fs = require("fs");
const os = require("os");
const assert = require("assert").strict;

const ROOT = path.join(__dirname, "..");

// Stubbed Supabase row factory
function row(i, venueEn, nameKo) {
  return {
    id: `stub-${i}`,
    name_ko: nameKo || `Show ${i}`,
    name_en: `Show ${i} EN`,
    venue_name_ko: `${venueEn} 한글`,
    venue_name_en: venueEn,
    opening_date: "2026-04-01",
    closing_date: "2026-08-01",
    cover_image_url: `https://stub.supabase.co/storage/v1/object/public/exhibitions/${i}.jpg`,
  };
}

async function withStubbedFetch(rowsByQuery, fn) {
  const originalFetch = global.fetch;
  const calls = [];
  global.fetch = async (url) => {
    calls.push(new URL(url));
    // Each call selects rows based on the query — anchors hit by id/title,
    // fill hits by venue.
    const text = url.includes("name_ko=eq.")
      ? rowsByQuery.byTitle || []
      : url.includes("id=eq.")
      ? rowsByQuery.byId || []
      : rowsByQuery.byVenue || [];
    return {
      ok: true,
      status: 200,
      json: async () => text,
    };
  };
  try {
    await fn(calls);
  } finally {
    global.fetch = originalFetch;
  }
}

async function runWithEnv(env, fn) {
  const original = {};
  for (const k of Object.keys(env)) {
    original[k] = process.env[k];
    process.env[k] = env[k];
  }
  try {
    await fn();
  } finally {
    for (const k of Object.keys(original)) {
      if (original[k] === undefined) delete process.env[k];
      else process.env[k] = original[k];
    }
  }
}

async function tempProject(fn) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "refresh-seed-"));
  const scriptsDir = path.join(dir, "scripts");
  fs.mkdirSync(scriptsDir);
  fs.mkdirSync(path.join(scriptsDir, "lib"));
  // Symlink (or copy) the script under test
  fs.copyFileSync(path.join(ROOT, "scripts", "refresh-seed.js"), path.join(scriptsDir, "refresh-seed.js"));
  fs.copyFileSync(
    path.join(ROOT, "scripts", "lib", "exhibition-reader-source.js"),
    path.join(scriptsDir, "lib", "exhibition-reader-source.js")
  );
  try {
    await fn(dir, scriptsDir);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

async function readSeed(scriptsDir) {
  const raw = fs.readFileSync(path.join(scriptsDir, "showcase-seed.json"), "utf8");
  return JSON.parse(raw);
}

(async () => {
  // ── Test 1: anchors-first ordering + auto-fill ──
  await tempProject(async (root, scriptsDir) => {
    fs.writeFileSync(path.join(scriptsDir, "seed-anchors.json"), JSON.stringify({
      anchors: [{ id: "stub-1" }, { title_ko: "한국 단색화의 계보" }],
      fillVenues: ["MMCA Seoul"],
      targetCount: 5,
    }));

    const fillRows = [3, 4, 5, 6].map((i) => row(i, "MMCA Seoul"));
    const anchorById = [row(1, "Leeum Museum of Art")];
    const anchorByTitle = [row(2, "Other Venue", "한국 단색화의 계보")];

    await withStubbedFetch({ byId: anchorById, byTitle: anchorByTitle, byVenue: fillRows }, async () => {
      await runWithEnv({ SUPABASE_URL: "https://stub.supabase.co", SUPABASE_ANON_KEY: "stub", GALLR_EXHIBITION_SOURCE: "" }, async () => {
        process.chdir(root);
        delete require.cache[require.resolve(path.join(scriptsDir, "refresh-seed.js"))];
        await require(path.join(scriptsDir, "refresh-seed.js")).run();
      });
    });

    const seed = await readSeed(scriptsDir);
    assert.equal(seed.exhibitions.length, 5, "5 exhibitions written");
    assert.equal(seed.readerSource, "legacy");
    assert.equal(seed.exhibitions[0].id, "stub-1", "first entry is anchor by id");
    assert.equal(seed.exhibitions[1].id, "stub-2", "second entry is anchor by title");
    for (const ex of seed.exhibitions) {
      assert.match(ex.coverImageUrl, /^https:\/\//, "all URLs are https://");
    }
  });

  // ── Test 2: errors loudly when Supabase env is missing ──
  await tempProject(async (root, scriptsDir) => {
    fs.writeFileSync(path.join(scriptsDir, "seed-anchors.json"), JSON.stringify({
      anchors: [], fillVenues: ["MMCA Seoul"], targetCount: 5,
    }));

    let threw = false;
    await runWithEnv({ SUPABASE_URL: "", SUPABASE_ANON_KEY: "", GALLR_EXHIBITION_SOURCE: "" }, async () => {
      process.chdir(root);
      delete require.cache[require.resolve(path.join(scriptsDir, "refresh-seed.js"))];
      try {
        await require(path.join(scriptsDir, "refresh-seed.js")).run();
      } catch (e) {
        threw = true;
      }
    });
    assert.equal(threw, true, "missing env throws");
  });

  // ── Test 3: errors loudly when fewer rows than targetCount ──
  await tempProject(async (root, scriptsDir) => {
    fs.writeFileSync(path.join(scriptsDir, "seed-anchors.json"), JSON.stringify({
      anchors: [], fillVenues: ["MMCA Seoul"], targetCount: 12,
    }));

    const tooFew = [1, 2, 3].map((i) => row(i, "MMCA Seoul"));
    let threw = false;
    await withStubbedFetch({ byVenue: tooFew }, async () => {
      await runWithEnv({ SUPABASE_URL: "https://stub.supabase.co", SUPABASE_ANON_KEY: "stub", GALLR_EXHIBITION_SOURCE: "" }, async () => {
        process.chdir(root);
        delete require.cache[require.resolve(path.join(scriptsDir, "refresh-seed.js"))];
        try {
          await require(path.join(scriptsDir, "refresh-seed.js")).run();
        } catch (e) {
          threw = true;
        }
      });
    });
    assert.equal(threw, true, "insufficient rows throws");
  });

  // ── Test 4: canonical-v2 fixes every seed query to the v2 resource ──
  await tempProject(async (root, scriptsDir) => {
    fs.writeFileSync(path.join(scriptsDir, "seed-anchors.json"), JSON.stringify({
      anchors: [], fillVenues: ["MMCA Seoul"], targetCount: 1,
    }));

    const rows = [row(1, "MMCA Seoul")];
    await withStubbedFetch({ byVenue: rows }, async (calls) => {
      await runWithEnv({
        SUPABASE_URL: "https://stub.supabase.co",
        SUPABASE_ANON_KEY: "stub",
        GALLR_EXHIBITION_SOURCE: "canonical-v2",
      }, async () => {
        process.chdir(root);
        delete require.cache[require.resolve(path.join(scriptsDir, "refresh-seed.js"))];
        await require(path.join(scriptsDir, "refresh-seed.js")).run();
      });
      assert.ok(calls.length > 0);
      assert.ok(calls.every((url) => url.pathname === "/rest/v1/exhibition_catalog_v2"));
      assert.equal(calls.at(-1).searchParams.get("order"), "closing_date.asc,id.asc");
    });

    const seed = await readSeed(scriptsDir);
    assert.equal(seed.readerSource, "canonical-v2");
  });

  console.log("[refresh-seed.test] all 4 tests passed");
})().catch((e) => {
  console.error("[refresh-seed.test] FAILED:", e);
  process.exit(1);
});
