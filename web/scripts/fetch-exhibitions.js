#!/usr/bin/env node
// Build-time fetcher for the multi-page catalog.
//
// Reads SUPABASE_URL + SUPABASE_ANON_KEY, fetches every row from the
// exhibitions table, enriches each with `slug` and `status`, and writes
// _data/exhibitions.json. Falls back to scripts/exhibitions-seed.json
// when env vars are missing or the fetch fails (local dev / CI).
//
// Production builds (VERCEL=1) exit non-zero on any fallback path —
// shipping placeholder data to real visitors is worse than a failed deploy.

const fs = require("fs");
const path = require("path");
const { classify } = require("./lib/status.js");
const { buildSlug } = require("./lib/slug.js");

const ROOT = path.join(__dirname, "..");
const SEED = path.join(ROOT, "scripts", "exhibitions-seed.json");
const OUTPUT_DIR = path.join(ROOT, "_data");
const OUTPUT = path.join(OUTPUT_DIR, "exhibitions.json");

const SELECT_COLS = [
  "id", "name_ko", "name_en",
  "venue_name_ko", "venue_name_en",
  "city_ko", "address_ko",
  "latitude", "longitude",
  "opening_date", "closing_date",
  "cover_image_url",
  "description_ko", "description_en",
  "ticket_url", "is_featured",
].join(",");

const IS_PRODUCTION_BUILD = process.env.VERCEL === "1";

function todayIso() {
  return new Date().toISOString().slice(0, 10);
}

function enrich(rows, today) {
  return rows.map((r) => ({
    ...r,
    slug: buildSlug({ name_en: r.name_en, name_ko: r.name_ko, id: r.id }),
    status: classify(r.opening_date, r.closing_date, today),
  }));
}

function pickFeatured(exhibitions) {
  // Prefer rows explicitly marked is_featured. Tiebreak by id ascending.
  const flagged = exhibitions
    .filter((e) => e.is_featured === true)
    .sort((a, b) => String(a.id).localeCompare(String(b.id)));

  if (flagged.length === 1) return flagged[0].id;
  if (flagged.length > 1) {
    console.warn(`[fetch-exhibitions] ${flagged.length} is_featured rows; using first by id ascending: ${flagged[0].id}`);
    return flagged[0].id;
  }
  // Fallback: most recently opened current exhibition
  const current = exhibitions
    .filter((e) => e.status === "current")
    .sort((a, b) => String(b.opening_date).localeCompare(String(a.opening_date)));
  if (current.length > 0) {
    console.warn(`[fetch-exhibitions] no is_featured row; falling back to most-recently-opened current: ${current[0].id}`);
    return current[0].id;
  }
  console.warn(`[fetch-exhibitions] no is_featured + no current rows; featuredId=null`);
  return null;
}

function writeFromSeed(reason, todayOverride) {
  if (IS_PRODUCTION_BUILD) {
    console.error(
      `[fetch-exhibitions] FATAL: production build cannot fall back to seed (${reason}).`
    );
    process.exit(1);
  }
  console.log(`[fetch-exhibitions] using seed fallback (${reason})`);
  if (!fs.existsSync(OUTPUT_DIR)) fs.mkdirSync(OUTPUT_DIR, { recursive: true });
  const seed = JSON.parse(fs.readFileSync(SEED, "utf8"));
  const today = todayOverride || todayIso();
  const exhibitions = enrich(seed.exhibitions || [], today);
  const out = {
    fetchedAt: new Date().toISOString(),
    source: "seed",
    today,
    exhibitions,
    featuredId: pickFeatured(exhibitions),
  };
  fs.writeFileSync(OUTPUT, JSON.stringify(out, null, 2));
  console.log(`[fetch-exhibitions] wrote ${OUTPUT} from seed (${exhibitions.length} entries)`);
}

async function run(todayOverride) {
  const url = (process.env.SUPABASE_URL || "").trim();
  const key = (process.env.SUPABASE_ANON_KEY || "").trim();

  if (!url || !key) {
    writeFromSeed("env vars absent", todayOverride);
    return;
  }

  const today = todayOverride || todayIso();
  const endpoint =
    `${url}/rest/v1/exhibitions` +
    `?select=${SELECT_COLS}` +
    `&order=opening_date.desc` +
    `&limit=2000`; // hard ceiling; revisit when dataset grows

  let rows;
  try {
    const res = await fetch(endpoint, {
      headers: { apikey: key, Authorization: `Bearer ${key}` },
    });
    if (!res.ok) { writeFromSeed(`HTTP ${res.status}`, todayOverride); return; }
    rows = await res.json();
  } catch (err) {
    writeFromSeed(`fetch error: ${err.message}`, todayOverride);
    return;
  }

  if (!Array.isArray(rows)) {
    writeFromSeed("non-array response", todayOverride);
    return;
  }

  if (rows.length === 0) {
    writeFromSeed("empty result set", todayOverride);
    return;
  }

  const exhibitions = enrich(rows, today);
  const out = {
    fetchedAt: new Date().toISOString(),
    source: "supabase",
    today,
    exhibitions,
    featuredId: pickFeatured(exhibitions),
  };

  if (!fs.existsSync(OUTPUT_DIR)) fs.mkdirSync(OUTPUT_DIR, { recursive: true });
  fs.writeFileSync(OUTPUT, JSON.stringify(out, null, 2));
  console.log(`[fetch-exhibitions] wrote ${OUTPUT} from supabase (${exhibitions.length} entries)`);
}

if (require.main === module) {
  run().catch((err) => {
    console.error("[fetch-exhibitions] unexpected error:", err);
    if (IS_PRODUCTION_BUILD) process.exit(1);
    writeFromSeed("unexpected error");
  });
}

module.exports = { run };
