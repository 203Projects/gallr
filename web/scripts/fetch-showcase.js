#!/usr/bin/env node
// Build-time fetcher for the gallrmap.com homepage showcase.
//
// Queries Supabase for exhibitions flagged with is_homepage_featured = true,
// ordered by closing_date ascending, capped at 12. The set is manually
// curated via the Supabase table editor — no date seeding, no random sampling.
//
// When SUPABASE_URL + SUPABASE_ANON_KEY are absent OR the fetch fails OR
// returns an empty curated set:
//   - Local builds (VERCEL != "1"): copies scripts/showcase-seed.json to _data/showcase.json.
//   - Vercel builds (VERCEL == "1"): hard-fails with a FATAL log. Silently shipping
//     stale or placeholder content to real visitors is worse than a failed deploy.

const fs = require("fs");
const path = require("path");

const ROOT = path.join(__dirname, "..");
const SEED = path.join(ROOT, "scripts", "showcase-seed.json");
const OUTPUT_DIR = path.join(ROOT, "_data");
const OUTPUT = path.join(OUTPUT_DIR, "showcase.json");

const LIMIT = 12;
const CLOSING_SOON_DAYS = 7;
const OPENING_SOON_DAYS = 7;

function todayIso() {
  return new Date().toISOString().slice(0, 10);
}

function daysBetween(a, b) {
  const ms = new Date(b).getTime() - new Date(a).getTime();
  return Math.round(ms / (1000 * 60 * 60 * 24));
}

function classify(opening, closing, today) {
  const dToClose = daysBetween(today, closing);
  const dToOpen = daysBetween(today, opening);
  if (dToClose >= 0 && dToClose <= CLOSING_SOON_DAYS) {
    return { status: "closing-soon", statusLabelKo: "종료 임박" };
  }
  if (dToOpen > 0 && dToOpen <= OPENING_SOON_DAYS) {
    return { status: "opening-soon", statusLabelKo: "오픈 임박" };
  }
  return { status: "ongoing", statusLabelKo: null };
}

const IS_PRODUCTION_BUILD = process.env.VERCEL === "1";

function writeFromSeed(reason) {
  if (IS_PRODUCTION_BUILD) {
    console.error(
      `[fetch-showcase] FATAL: production build cannot fall back to seed (${reason}). ` +
        `Verify SUPABASE_URL + SUPABASE_ANON_KEY are set, Supabase is reachable, ` +
        `and at least one exhibition has is_homepage_featured = true.`
    );
    process.exit(1);
  }
  console.log(`[fetch-showcase] using seed fallback (${reason})`);
  if (!fs.existsSync(OUTPUT_DIR)) fs.mkdirSync(OUTPUT_DIR, { recursive: true });
  const seed = JSON.parse(fs.readFileSync(SEED, "utf8"));
  const out = { ...seed, source: "seed" };
  fs.writeFileSync(OUTPUT, JSON.stringify(out, null, 2));
  console.log(`[fetch-showcase] wrote ${OUTPUT} from seed`);
}

async function main() {
  const url = (process.env.SUPABASE_URL || "").trim();
  const key = (process.env.SUPABASE_ANON_KEY || "").trim();

  if (!url || !key) {
    writeFromSeed("env vars absent");
    return;
  }

  const endpoint =
    `${url}/rest/v1/exhibitions` +
    `?select=id,name_ko,name_en,venue_name_ko,venue_name_en,opening_date,closing_date,cover_image_url` +
    `&is_homepage_featured=eq.true` +
    `&order=closing_date.asc` +
    `&limit=${LIMIT}`;

  let res, rows;
  try {
    res = await fetch(endpoint, {
      headers: { apikey: key, Authorization: `Bearer ${key}` },
    });
    if (!res.ok) {
      writeFromSeed(`HTTP ${res.status}`);
      return;
    }
    rows = await res.json();
  } catch (err) {
    writeFromSeed(`fetch error: ${err.message}`);
    return;
  }

  if (!Array.isArray(rows) || rows.length === 0) {
    writeFromSeed("empty curated set (no rows with is_homepage_featured = true)");
    return;
  }

  const today = todayIso();
  const exhibitions = rows.map((r) => {
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
  });

  const out = {
    fetchedAt: new Date().toISOString(),
    source: "supabase",
    exhibitions,
  };

  if (!fs.existsSync(OUTPUT_DIR)) fs.mkdirSync(OUTPUT_DIR, { recursive: true });
  fs.writeFileSync(OUTPUT, JSON.stringify(out, null, 2));
  console.log(`[fetch-showcase] wrote ${OUTPUT} from supabase (${exhibitions.length} entries)`);
}

main().catch((err) => {
  console.error("[fetch-showcase] unexpected error:", err);
  writeFromSeed("unexpected error");
});
