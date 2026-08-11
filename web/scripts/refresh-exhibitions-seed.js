#!/usr/bin/env node
// One-shot manual builder for exhibitions-seed.json.
// Targets a varied seed (multiple venues) so offline builds and
// Playwright fixtures exercise the catalog UI realistically.
//
// Usage:
//   SUPABASE_URL=... SUPABASE_PUBLISHABLE_KEY=... npm run refresh-exhibitions-seed

const fs = require("fs");
const path = require("path");
const { resolveExhibitionReaderSource } = require("./lib/exhibition-reader-source.js");
const { supabaseApiHeaders } = require("./supabase-api-headers.js");
const {
  resolveSupabasePublicApiKey,
} = require("./supabase-public-api-key.js");

const ROOT = path.join(__dirname, "..");
const ANCHORS_FILE = path.join(ROOT, "scripts", "exhibitions-seed-anchors.json");
const OUTPUT = path.join(ROOT, "scripts", "exhibitions-seed.json");

const SELECT_COLS = [
  "id", "name_ko", "name_en",
  "venue_name_ko", "venue_name_en",
  "city_ko", "address_ko",
  "latitude", "longitude",
  "opening_date", "closing_date",
  "cover_image_url",
  "description_ko", "description_en", "credits_ko", "credits_en",
  "ticket_url", "is_featured",
].join(",");

async function fetchVenues(url, key, venues, limit, readerSource) {
  if (!venues.length) return [];
  const venueIn = venues.map((v) => `"${v}"`).join(",");
  const endpoint =
    `${url}/rest/v1/${readerSource.resource}` +
    `?select=${SELECT_COLS}` +
    `&venue_name_en=in.(${encodeURIComponent(venueIn)})` +
    `&cover_image_url=not.is.null` +
    `&limit=${limit}`;
  const res = await fetch(endpoint, {
    headers: supabaseApiHeaders(key),
  });
  if (!res.ok) throw new Error(`Supabase fetch ${res.status}`);
  const rows = await res.json();
  return Array.isArray(rows) ? rows : [];
}

async function run() {
  const readerSource = resolveExhibitionReaderSource();
  const url = (process.env.SUPABASE_URL || "").trim();
  const key = resolveSupabasePublicApiKey();
  if (!url || !key) {
    throw new Error(
      "[refresh-exhibitions-seed] SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY required",
    );
  }
  const cfg = JSON.parse(fs.readFileSync(ANCHORS_FILE, "utf8"));
  const { fillVenues = [], targetCount = 12 } = cfg;
  const rows = await fetchVenues(url, key, fillVenues, targetCount * 2, readerSource);
  if (rows.length < targetCount) {
    throw new Error(
      `[refresh-exhibitions-seed] got ${rows.length} rows, needed ${targetCount}. Add fillVenues or lower targetCount.`
    );
  }
  const exhibitions = rows.slice(0, targetCount);
  const out = {
    fetchedAt: new Date().toISOString(),
    source: "seed-curated",
    readerSource: readerSource.name,
    exhibitions,
  };
  fs.writeFileSync(OUTPUT, JSON.stringify(out, null, 2));
  console.log(`[refresh-exhibitions-seed] wrote ${exhibitions.length} entries`);
  return out;
}

if (require.main === module) {
  run().catch((e) => { console.error(e.message || e); process.exit(1); });
}
module.exports = { fetchVenues, run };
