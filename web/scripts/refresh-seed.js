#!/usr/bin/env node
// One-shot manual builder for showcase-seed.json from real Supabase
// data. Reads scripts/seed-anchors.json, fetches anchor exhibitions
// by id or name_ko, then fills the remaining slots from fillVenues
// (currently-running, ordered by closing_date ASC). Writes the result
// to scripts/showcase-seed.json.
//
// Usage:
//   SUPABASE_URL=... SUPABASE_PUBLISHABLE_KEY=... npm run refresh-seed
//
// Errors loudly (non-zero exit) when env vars are missing, Supabase
// is unreachable, or fewer than targetCount rows could be assembled.

const fs = require("fs");
const path = require("path");
const { resolveExhibitionReaderSource } = require("./lib/exhibition-reader-source.js");
const { supabaseApiHeaders } = require("./supabase-api-headers.js");
const {
  resolveSupabasePublicApiKey,
} = require("./supabase-public-api-key.js");

const ROOT = path.join(__dirname, "..");
const ANCHORS_FILE = path.join(ROOT, "scripts", "seed-anchors.json");
const OUTPUT = path.join(ROOT, "scripts", "showcase-seed.json");

function todayIso() {
  return new Date().toISOString().slice(0, 10);
}

const SELECT_COLS =
  "id,name_ko,name_en,venue_name_ko,venue_name_en,opening_date,closing_date,cover_image_url";

function buildHeaders(key) {
  return supabaseApiHeaders(key);
}

async function fetchRows(url, key, query, readerSource) {
  const endpoint = `${url}/rest/v1/${readerSource.resource}?select=${SELECT_COLS}&${query}`;
  const res = await fetch(endpoint, { headers: buildHeaders(key) });
  if (!res.ok) {
    throw new Error(`Supabase fetch ${res.status} on ${query}`);
  }
  return res.json();
}

async function fetchAnchorById(url, key, id, readerSource) {
  const rows = await fetchRows(url, key, `id=eq.${encodeURIComponent(id)}`, readerSource);
  return Array.isArray(rows) && rows.length > 0 ? rows[0] : null;
}

async function fetchAnchorByTitle(url, key, nameKo, readerSource) {
  const rows = await fetchRows(
    url,
    key,
    `name_ko=eq.${encodeURIComponent(nameKo)}`,
    readerSource
  );
  return Array.isArray(rows) && rows.length > 0 ? rows[0] : null;
}

async function fetchFillRows(url, key, fillVenues, today, limit, readerSource) {
  if (!fillVenues.length) return [];
  const venueIn = fillVenues.map((v) => `"${v}"`).join(",");
  const query =
    `venue_name_en=in.(${encodeURIComponent(venueIn)})` +
    `&cover_image_url=not.is.null` +
    `&opening_date=lte.${today}` +
    `&closing_date=gte.${today}` +
    `&order=closing_date.asc,id.asc` +
    `&limit=${limit}`;
  const rows = await fetchRows(url, key, query, readerSource);
  return Array.isArray(rows) ? rows : [];
}

function classify(opening, closing, today) {
  const day = 1000 * 60 * 60 * 24;
  const dToClose = Math.round((new Date(closing).getTime() - new Date(today).getTime()) / day);
  const dToOpen = Math.round((new Date(opening).getTime() - new Date(today).getTime()) / day);
  if (dToClose >= 0 && dToClose <= 7) return { status: "closing-soon", statusLabelKo: "종료 임박" };
  if (dToOpen > 0 && dToOpen <= 7) return { status: "opening-soon", statusLabelKo: "오픈 임박" };
  return { status: "ongoing", statusLabelKo: null };
}

function shapeRow(r, today) {
  const { status, statusLabelKo } = classify(r.opening_date, r.closing_date, today);
  return {
    id: r.id,
    titleKo: r.name_ko,
    titleEn: r.name_en,
    venueKo: r.venue_name_ko,
    venueEn: r.venue_name_en,
    openingDate: r.opening_date,
    closingDate: r.closing_date,
    coverImageUrl: r.cover_image_url,
    status,
    statusLabelKo,
  };
}

async function run() {
  const readerSource = resolveExhibitionReaderSource();
  // Trim env vars defensively — pasted values from a UI sometimes carry
  // a trailing newline, which Node's fetch Headers API rejects with an
  // "invalid header value" error.
  const url = (process.env.SUPABASE_URL || "").trim();
  const key = resolveSupabasePublicApiKey();
  if (!url || !key) {
    throw new Error(
      "[refresh-seed] SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY must be set"
    );
  }

  const anchorsCfg = JSON.parse(fs.readFileSync(ANCHORS_FILE, "utf8"));
  const { anchors = [], fillVenues = [], targetCount = 12 } = anchorsCfg;
  const today = todayIso();

  // 1. Resolve anchors in order.
  const anchorRows = [];
  for (const a of anchors) {
    let row = null;
    if (a.id) row = await fetchAnchorById(url, key, a.id, readerSource);
    else if (a.title_ko) {
      row = await fetchAnchorByTitle(url, key, a.title_ko, readerSource);
    }
    if (row) anchorRows.push(row);
    else console.warn(`[refresh-seed] anchor not found: ${JSON.stringify(a)}`);
  }

  // 2. Fill remaining slots from fillVenues (deduped against anchors).
  const anchorIds = new Set(anchorRows.map((r) => r.id));
  const remaining = Math.max(0, targetCount - anchorRows.length);
  const fillCandidates = await fetchFillRows(
    url,
    key,
    fillVenues,
    today,
    remaining + 10,
    readerSource
  );
  const fillRows = fillCandidates.filter((r) => !anchorIds.has(r.id)).slice(0, remaining);

  const all = [...anchorRows, ...fillRows];
  if (all.length < targetCount) {
    throw new Error(
      `[refresh-seed] assembled ${all.length} rows, needed ${targetCount}. ` +
        `Add more anchors, more fillVenues, or lower targetCount.`
    );
  }

  const exhibitions = all.map((r) => shapeRow(r, today));
  const out = {
    fetchedAt: new Date().toISOString(),
    source: "seed-curated",
    readerSource: readerSource.name,
    exhibitions,
  };
  fs.writeFileSync(OUTPUT, JSON.stringify(out, null, 2));
  console.log(`[refresh-seed] wrote ${exhibitions.length} entries to ${OUTPUT}`);
  return out;
}

if (require.main === module) {
  run().catch((e) => {
    console.error(e.message || e);
    process.exit(1);
  });
}

module.exports = { run };
