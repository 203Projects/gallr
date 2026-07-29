// Unit test for scripts/refresh-exhibitions-seed.js
const assert = require("assert").strict;
const fs = require("fs");
const path = require("path");
const os = require("os");

const ROOT = path.join(__dirname, "..");

function row(i, overrides = {}) {
  return {
    id: `id-${i}`,
    name_ko: `전시 ${i}`, name_en: `Show ${i}`,
    venue_name_ko: `갤러리`, venue_name_en: `Gallery`,
    city: "Seoul", address: "Addr",
    opening_date: "2026-04-01", closing_date: "2026-08-01",
    cover_image_url: `https://stub/${i}.jpg`,
    description_ko: "", description_en: "",
    ticket_url: null, is_featured: false,
    ...overrides,
  };
}

async function inTempDir(fn) {
  const origCwd = process.cwd();
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "refresh-exh-"));
  fs.mkdirSync(path.join(dir, "scripts", "lib"), { recursive: true });
  fs.copyFileSync(path.join(ROOT, "scripts", "refresh-exhibitions-seed.js"), path.join(dir, "scripts", "refresh-exhibitions-seed.js"));
  fs.copyFileSync(path.join(ROOT, "scripts", "supabase-api-headers.js"), path.join(dir, "scripts", "supabase-api-headers.js"));
  fs.copyFileSync(path.join(ROOT, "scripts", "lib", "status.js"), path.join(dir, "scripts", "lib", "status.js"));
  fs.copyFileSync(path.join(ROOT, "scripts", "lib", "slug.js"), path.join(dir, "scripts", "lib", "slug.js"));
  fs.copyFileSync(
    path.join(ROOT, "scripts", "lib", "exhibition-reader-source.js"),
    path.join(dir, "scripts", "lib", "exhibition-reader-source.js")
  );
  try { await fn(dir); }
  finally { process.chdir(origCwd); fs.rmSync(dir, { recursive: true, force: true }); }
}

async function withStubbedFetch(byVenue, fn) {
  const orig = global.fetch;
  const calls = [];
  global.fetch = async (url) => {
    calls.push(new URL(url));
    return { ok: true, status: 200, json: async () => byVenue };
  };
  try { await fn(calls); } finally { global.fetch = orig; }
}

(async () => {
  // ── Test 1: writes seed with N entries ──
  await inTempDir(async (dir) => {
    fs.writeFileSync(path.join(dir, "scripts", "exhibitions-seed-anchors.json"), JSON.stringify({
      fillVenues: ["MMCA Seoul", "Leeum Museum of Art"],
      targetCount: 6,
    }));
    const rows = [1, 2, 3, 4, 5, 6, 7].map((i) => row(i));
    await withStubbedFetch(rows, async (calls) => {
      process.env.SUPABASE_URL = "https://stub";
      process.env.SUPABASE_ANON_KEY = "stub";
      process.env.GALLR_EXHIBITION_SOURCE = "";
      process.chdir(dir);
      delete require.cache[require.resolve(path.join(dir, "scripts", "refresh-exhibitions-seed.js"))];
      await require(path.join(dir, "scripts", "refresh-exhibitions-seed.js")).run();
      assert.ok(calls.every((url) => url.pathname === "/rest/v1/exhibitions"));
    });
    const seed = JSON.parse(fs.readFileSync(path.join(dir, "scripts", "exhibitions-seed.json"), "utf8"));
    assert.equal(seed.exhibitions.length, 6);
    assert.equal(seed.source, "seed-curated");
    assert.equal(seed.readerSource, "legacy");
  });

  // ── Test 2: errors when env vars missing ──
  await inTempDir(async (dir) => {
    fs.writeFileSync(path.join(dir, "scripts", "exhibitions-seed-anchors.json"), JSON.stringify({
      fillVenues: ["MMCA Seoul"], targetCount: 2,
    }));
    delete process.env.SUPABASE_URL;
    delete process.env.SUPABASE_ANON_KEY;
    process.env.GALLR_EXHIBITION_SOURCE = "";
    process.chdir(dir);
    delete require.cache[require.resolve(path.join(dir, "scripts", "refresh-exhibitions-seed.js"))];
    let threw = false;
    try { await require(path.join(dir, "scripts", "refresh-exhibitions-seed.js")).run(); }
    catch { threw = true; }
    assert.equal(threw, true);
  });

  // ── Test 3: errors when fewer rows than targetCount ──
  await inTempDir(async (dir) => {
    fs.writeFileSync(path.join(dir, "scripts", "exhibitions-seed-anchors.json"), JSON.stringify({
      fillVenues: ["MMCA Seoul"], targetCount: 10,
    }));
    const rows = [1, 2].map((i) => row(i));
    let threw = false;
    await withStubbedFetch(rows, async () => {
      process.env.SUPABASE_URL = "https://stub";
      process.env.SUPABASE_ANON_KEY = "stub";
      process.env.GALLR_EXHIBITION_SOURCE = "";
      process.chdir(dir);
      delete require.cache[require.resolve(path.join(dir, "scripts", "refresh-exhibitions-seed.js"))];
      try { await require(path.join(dir, "scripts", "refresh-exhibitions-seed.js")).run(); }
      catch { threw = true; }
    });
    assert.equal(threw, true);
  });

  // ── Test 4: canonical-v2 seed refresh uses only the fixed v2 resource ──
  await inTempDir(async (dir) => {
    fs.writeFileSync(path.join(dir, "scripts", "exhibitions-seed-anchors.json"), JSON.stringify({
      fillVenues: ["MMCA Seoul"], targetCount: 2,
    }));
    const rows = [1, 2, 3].map((i) => row(i));
    await withStubbedFetch(rows, async (calls) => {
      process.env.SUPABASE_URL = "https://stub";
      process.env.SUPABASE_ANON_KEY = "stub";
      process.env.GALLR_EXHIBITION_SOURCE = "canonical-v2";
      process.chdir(dir);
      delete require.cache[require.resolve(path.join(dir, "scripts", "refresh-exhibitions-seed.js"))];
      await require(path.join(dir, "scripts", "refresh-exhibitions-seed.js")).run();
      assert.ok(calls.length > 0);
      assert.ok(calls.every(
        (url) => url.pathname === "/rest/v1/exhibition_catalog_v2"
      ));
    });
    const seed = JSON.parse(fs.readFileSync(path.join(dir, "scripts", "exhibitions-seed.json"), "utf8"));
    assert.equal(seed.readerSource, "canonical-v2");
  });

  console.log("[exhibitions-seed.test] all 4 tests passed");
})().catch((e) => { console.error(e); process.exit(1); });
