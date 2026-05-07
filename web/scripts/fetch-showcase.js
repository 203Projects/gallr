#!/usr/bin/env node
// Build-time fetcher for the editorial-redesign showcase.
//
// When SUPABASE_URL + SUPABASE_ANON_KEY are set in the environment, fetches
// up to 40 currently-running exhibitions with cover images from Supabase
// and randomly selects 12 (Fisher–Yates seeded by today's UTC date so each
// daily build is stable; rebuilds within a day produce the same set).
//
// When env vars are absent OR the fetch fails (network error / non-200),
// copies scripts/showcase-seed.json to _data/showcase.json. Logs which
// path was taken.

const fs = require("fs");
const path = require("path");

const ROOT = path.join(__dirname, "..");
const SEED = path.join(ROOT, "scripts", "showcase-seed.json");
const OUTPUT_DIR = path.join(ROOT, "_data");
const OUTPUT = path.join(OUTPUT_DIR, "showcase.json");

const SAMPLE_SIZE = 12;
const FETCH_LIMIT = 40;
const CLOSING_SOON_DAYS = 7;
const OPENING_SOON_DAYS = 7;

function todayIso() {
  // Must stay UTC — seed stability and cross-builder determinism depend on
  // it. Do not switch to toLocaleDateString().
  return new Date().toISOString().slice(0, 10); // YYYY-MM-DD
}

// Mulberry32 — small deterministic PRNG seeded from a string
function seededRng(seed) {
  let h = 1779033703 ^ seed.length;
  for (let i = 0; i < seed.length; i++) {
    h = Math.imul(h ^ seed.charCodeAt(i), 3432918353);
    h = (h << 13) | (h >>> 19);
  }
  let a = h >>> 0;
  return function () {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function shuffle(arr, rng) {
  const a = arr.slice();
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(rng() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
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

// Production-build guard: on Vercel, every seed fallback is a hard
// error — silently shipping placeholder artwork to real visitors is a
// worse outcome than a failed deploy. Local dev and GitHub Actions
// test runs keep the lenient fallback (tests don't validate image
// content, so the seed is acceptable there).
const IS_PRODUCTION_BUILD = process.env.VERCEL === "1";

function writeFromSeed(reason) {
  if (IS_PRODUCTION_BUILD) {
    console.error(
      `[fetch-showcase] FATAL: production build cannot fall back to seed (${reason}). ` +
        `Verify SUPABASE_URL + SUPABASE_ANON_KEY are set and Supabase is reachable.`
    );
    process.exit(1);
  }
  console.log(`[fetch-showcase] using seed fallback (${reason})`);
  if (!fs.existsSync(OUTPUT_DIR)) fs.mkdirSync(OUTPUT_DIR, { recursive: true });
  fs.copyFileSync(SEED, OUTPUT);
  console.log(`[fetch-showcase] wrote ${OUTPUT} from seed`);
}

async function main() {
  const url = process.env.SUPABASE_URL;
  const key = process.env.SUPABASE_ANON_KEY;

  if (!url || !key) {
    writeFromSeed("env vars absent");
    return;
  }

  const today = todayIso();
  const endpoint =
    `${url}/rest/v1/exhibitions` +
    `?select=id,name_ko,name_en,venue_name_ko,venue_name_en,opening_date,closing_date,cover_image_url` +
    `&cover_image_url=not.is.null` +
    `&opening_date=lte.${today}` +
    `&closing_date=gte.${today}` +
    `&limit=${FETCH_LIMIT}`;

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
    writeFromSeed("empty result set");
    return;
  }

  const rng = seededRng(today);
  const picked = shuffle(rows, rng).slice(0, SAMPLE_SIZE);

  const exhibitions = picked.map((r) => {
    const { status, statusLabelKo } = classify(
      r.opening_date,
      r.closing_date,
      today
    );
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
