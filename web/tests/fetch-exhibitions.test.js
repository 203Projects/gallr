// Unit test for scripts/fetch-exhibitions.js — uses stubbed fetch.
// Run: node tests/fetch-exhibitions.test.js

const assert = require("assert").strict;
const fs = require("fs");
const path = require("path");
const os = require("os");

const ROOT = path.join(__dirname, "..");

function row(i, overrides = {}) {
  return {
    id: `id-${i}-aaaa-bbbb`,
    name_ko: `전시 ${i}`,
    name_en: `Show ${i}`,
    venue_name_ko: `갤러리 ${i}`,
    venue_name_en: `Gallery ${i}`,
    city: i % 2 === 0 ? "Seoul" : "Busan",
    address: `Addr ${i}`,
    opening_date: "2026-04-01",
    closing_date: "2026-08-01",
    cover_image_url: `https://stub/exhibitions/${i}.jpg`,
    description_ko: i === 1 ? "한글 설명" : "",
    description_en: i === 1 ? "English description" : "",
    ticket_url: i === 1 ? "https://tickets.example/1" : null,
    is_featured: i === 1,
    ...overrides,
  };
}

async function withStubbedFetch(rows, fn) {
  const original = global.fetch;
  global.fetch = async () => ({ ok: true, status: 200, json: async () => rows });
  try { await fn(); } finally { global.fetch = original; }
}

async function inTempDir(fn) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "fetch-exh-"));
  // Mirror the project layout the script expects: scripts/ + _data/.
  fs.mkdirSync(path.join(dir, "scripts", "lib"), { recursive: true });
  fs.copyFileSync(path.join(ROOT, "scripts", "fetch-exhibitions.js"), path.join(dir, "scripts", "fetch-exhibitions.js"));
  fs.copyFileSync(path.join(ROOT, "scripts", "lib", "status.js"), path.join(dir, "scripts", "lib", "status.js"));
  fs.copyFileSync(path.join(ROOT, "scripts", "lib", "slug.js"), path.join(dir, "scripts", "lib", "slug.js"));
  // Stub seed for fallback path
  fs.writeFileSync(
    path.join(dir, "scripts", "exhibitions-seed.json"),
    JSON.stringify({ exhibitions: [row(99)] })
  );
  try { await fn(dir); } finally { fs.rmSync(dir, { recursive: true, force: true }); }
}

(async () => {
  // ── Test 1: writes _data/exhibitions.json with enriched rows from Supabase ──
  await inTempDir(async (dir) => {
    const rows = [row(1), row(2), row(3)];
    await withStubbedFetch(rows, async () => {
      process.env.SUPABASE_URL = "https://stub";
      process.env.SUPABASE_ANON_KEY = "stub";
      process.chdir(dir);
      delete require.cache[require.resolve(path.join(dir, "scripts", "fetch-exhibitions.js"))];
      await require(path.join(dir, "scripts", "fetch-exhibitions.js")).run("2026-06-15");
    });
    const out = JSON.parse(fs.readFileSync(path.join(dir, "_data", "exhibitions.json"), "utf8"));
    assert.equal(out.exhibitions.length, 3);
    assert.equal(out.source, "supabase");
    // Each row enriched with slug + status
    for (const ex of out.exhibitions) {
      assert.match(ex.slug, /^show-\d+-id-\d+$/);
      assert.ok(["current", "opening_soon", "closing_soon", "closed"].includes(ex.status));
    }
    // Featured pick: row 1 has is_featured=true → out.featuredId === row 1's id
    assert.equal(out.featuredId, "id-1-aaaa-bbbb");
  });

  // ── Test 2: falls back to seed when env vars are missing (non-prod) ──
  await inTempDir(async (dir) => {
    delete process.env.VERCEL;
    delete process.env.SUPABASE_URL;
    delete process.env.SUPABASE_ANON_KEY;
    process.chdir(dir);
    delete require.cache[require.resolve(path.join(dir, "scripts", "fetch-exhibitions.js"))];
    await require(path.join(dir, "scripts", "fetch-exhibitions.js")).run();
    const out = JSON.parse(fs.readFileSync(path.join(dir, "_data", "exhibitions.json"), "utf8"));
    assert.equal(out.source, "seed");
    assert.equal(out.exhibitions.length, 1);
  });

  // ── Test 3: production build with missing env vars exits non-zero ──
  await inTempDir(async (dir) => {
    process.env.VERCEL = "1";
    delete process.env.SUPABASE_URL;
    delete process.env.SUPABASE_ANON_KEY;
    process.chdir(dir);
    delete require.cache[require.resolve(path.join(dir, "scripts", "fetch-exhibitions.js"))];
    let exitCode;
    const origExit = process.exit;
    process.exit = (c) => { exitCode = c; throw new Error("exit"); };
    try { await require(path.join(dir, "scripts", "fetch-exhibitions.js")).run(); } catch {}
    process.exit = origExit;
    delete process.env.VERCEL;
    assert.equal(exitCode, 1, "production build with missing env should exit 1");
  });

  // ── Test 4: featured fallback to most-recently-opened current row when no is_featured=true ──
  await inTempDir(async (dir) => {
    const rows = [
      row(1, { is_featured: false, opening_date: "2026-01-01", closing_date: "2026-12-31" }),
      row(2, { is_featured: false, opening_date: "2026-04-01", closing_date: "2026-12-31" }),
      row(3, { is_featured: false, opening_date: "2026-05-01", closing_date: "2026-12-31" }),
    ];
    await withStubbedFetch(rows, async () => {
      process.env.SUPABASE_URL = "https://stub";
      process.env.SUPABASE_ANON_KEY = "stub";
      process.chdir(dir);
      delete require.cache[require.resolve(path.join(dir, "scripts", "fetch-exhibitions.js"))];
      await require(path.join(dir, "scripts", "fetch-exhibitions.js")).run("2026-06-15");
    });
    const out = JSON.parse(fs.readFileSync(path.join(dir, "_data", "exhibitions.json"), "utf8"));
    assert.equal(out.featuredId, "id-3-aaaa-bbbb", "fallback picks most-recent opening_date");
  });

  console.log("[fetch-exhibitions.test] all tests passed");
})().catch((e) => { console.error(e); process.exit(1); });
