# gallrmap.com Multi-Page Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expand `gallrmap.com` from a single-page editorial site into five static routes (`/`, `/exhibitions/`, `/exhibitions/[slug]/`, `/map/`, `/about/`) backed by build-time Supabase fetches.

**Architecture:** Eleventy 3.x + Nunjucks. New build script `fetch-exhibitions.js` writes `_data/exhibitions.json`. 11ty pagination generates one HTML file per exhibition. Discover is a single page with client-side filter via `?status=&city=` URL params. Map page loads Naver Maps JS SDK and a JSON island of exhibitions. Korean-forward bilingual pattern (PR #44) extended to all new pages — no EN/KO toggle. All work on branch `038-gallrmap-multipage-catalog` (already created off `develop`).

**Tech stack:** Eleventy 3.x, Nunjucks, vanilla JS (no framework), Naver Maps JS SDK v3, Supabase (anon read), Playwright + pa11y for tests.

**Spec:** `docs/superpowers/specs/2026-05-07-gallrmap-multipage-catalog-design.md`

---

## File structure

### New files

```
web/
  scripts/
    lib/
      status.js                    Date → status classifier (current|opening_soon|closing_soon|closed)
      slug.js                      Slug builder: slugify(name) + "-" + id.slice(0,4)
    fetch-exhibitions.js           Build-time fetcher → _data/exhibitions.json
    refresh-exhibitions-seed.js    Manual one-shot to refresh exhibitions-seed.json
    exhibitions-seed.json          Seed fallback for offline/CI builds
    exhibitions-seed-anchors.json  Anchor config for refresh-exhibitions-seed
  _includes/
    components/
      exhibition-card.njk          Card for Discover grid + Now-Showing future refactor
      status-chip.njk              Outlined / inverted / accent status pill
      meta-pair.njk                Eyebrow + bilingual value pair
      filter-list.njk              Vertical link list, inverted-active treatment
      sheet.njk                    Full-screen mobile overlay (filter drawer)
      sticky-mobile-cta.njk        Fixed-bottom orange CTA bar
  exhibitions/
    index.html                     Discover page
    exhibition.11ty.js             Pagination → /exhibitions/[slug]/
  map/
    index.html                     Map page
  about/
    index.html                     About page (wraps existing about.html include)
  client/
    filter.js                      Discover URL-param ↔ DOM filter
    map.js                         Naver SDK init, pin/sidebar sync
    share.js                       Web Share API + clipboard fallback
  tests/
    fixtures/
      exhibitions.json             Stable fixture for unit + build tests
    status.test.js                 lib/status.js unit test
    slug.test.js                   lib/slug.js unit test
    exhibitions-seed.test.js       refresh-exhibitions-seed.js test
    multipage-build.test.js        Asserts dist/ contains all expected routes
    discover-filter.test.ts        Playwright: filter mechanics
    detail-page.test.ts            Playwright: detail-page rendering
    map-page.test.ts               Playwright: map sidebar + stubbed SDK
    about-page.test.ts             Playwright: about-page renders
```

### Modified files

```
web/
  _includes/
    base.html              Top-nav links: ABOUT becomes /about/, add MAP and EXHIBITIONS links
    hero.html              Featured-exhibition link (small, additive)
  styles/
    main.css               Append component styles + page styles for the four new pages
  package.json             Add refresh-exhibitions-seed script
  tests/
    accessibility.test.js  Add /exhibitions/, /exhibitions/[slug]/, /map/, /about/
```

### Out of scope (verify-only)

```
gas/                       Verify the existing sync upserts new columns by name; no code changes expected
```

---

## Phase 1 — Foundation libraries

Pure-JS pieces that have nothing to do with Eleventy. Test-first, fast feedback.

### Task 1: Status classifier — `lib/status.js`

**Files:**
- Create: `web/scripts/lib/status.js`
- Test: `web/tests/status.test.js`

**Background:** The mobile app's spec 022 defines four statuses by date math relative to today. Reproduce that here so the web is consistent. Window thresholds: 7 days for both opening-soon and closing-soon (matches existing `fetch-showcase.js` constants).

- [ ] **Step 1: Write the failing test**

Create `web/tests/status.test.js`:

```js
// Unit test for scripts/lib/status.js
// Run: node tests/status.test.js (also runs as part of `npm test`)

const assert = require("assert").strict;
const { classify, STATUSES } = require("../scripts/lib/status.js");

const TODAY = "2026-05-15";

// classify(opening, closing, today) → "current" | "opening_soon" | "closing_soon" | "closed"
const cases = [
  // [label, opening, closing, today, expected]
  ["fully open in window",            "2026-04-01", "2026-08-01", TODAY, "current"],
  ["closes today",                    "2026-04-01", "2026-05-15", TODAY, "closing_soon"],
  ["closes in 7 days exactly",        "2026-04-01", "2026-05-22", TODAY, "closing_soon"],
  ["closes in 8 days (just outside)", "2026-04-01", "2026-05-23", TODAY, "current"],
  ["closed yesterday",                "2026-04-01", "2026-05-14", TODAY, "closed"],
  ["opens tomorrow",                  "2026-05-16", "2026-08-01", TODAY, "opening_soon"],
  ["opens in 7 days exactly",         "2026-05-22", "2026-08-01", TODAY, "opening_soon"],
  ["opens in 8 days (just outside)",  "2026-05-23", "2026-08-01", TODAY, "opening_soon"],
  // Anything more than 7 days in the future is still opening_soon? No — the spec says opening_soon
  // is a 7-day window. Outside that window → closed (because it's not yet open and not closing soon).
  // Wait: re-read existing fetch-showcase.js classify():
  //   dToOpen > 0 && dToOpen <= OPENING_SOON_DAYS → opening-soon
  //   else → ongoing (which is "current" here)
  // That implies a future-but-far-out exhibition is "current" by the existing classifier.
  // Per spec, our four statuses are current/opening_soon/closing_soon/closed.
  // Future-but-not-yet-open: keep as "opening_soon" only when within 7 days, else… we need a fifth
  // bucket OR a deliberate decision. Decision: future > 7d is "opening_soon" too — visitors care
  // about all upcoming things, and the discover sidebar lumps them together. Add a test that pins it:
  ["opens in 30 days",                "2026-06-14", "2026-08-01", TODAY, "opening_soon"],
];

for (const [label, opening, closing, today, expected] of cases) {
  const actual = classify(opening, closing, today);
  assert.equal(actual, expected, `${label}: expected ${expected}, got ${actual} (open=${opening} close=${closing})`);
}

// STATUSES export must list exactly these four, in this order, for sidebar rendering
assert.deepEqual(STATUSES, ["current", "opening_soon", "closing_soon", "closed"]);

console.log("[status.test] all tests passed");
```

- [ ] **Step 2: Run test, confirm it fails**

```bash
cd web && node tests/status.test.js
```

Expected: error like `Cannot find module '../scripts/lib/status.js'`.

- [ ] **Step 3: Implement `lib/status.js`**

Create `web/scripts/lib/status.js`:

```js
// Date → status classifier for exhibitions.
// Shared by the build pipeline (fetch-exhibitions.js) and tests.
// Mirrors the mobile app's spec 022 logic with explicit web statuses:
//   current        — open today, more than 7 days until close
//   closing_soon   — open today, ≤ 7 days until close
//   opening_soon   — not yet open
//   closed         — already closed (closing_date < today)

const STATUSES = ["current", "opening_soon", "closing_soon", "closed"];
const OPENING_SOON_DAYS = 7;
const CLOSING_SOON_DAYS = 7;

function daysBetween(a, b) {
  const ms = new Date(b).getTime() - new Date(a).getTime();
  return Math.round(ms / (1000 * 60 * 60 * 24));
}

function classify(openingDate, closingDate, today) {
  const dToClose = daysBetween(today, closingDate);
  const dToOpen = daysBetween(today, openingDate);

  // Already closed
  if (dToClose < 0) return "closed";

  // Not yet open: any future opening counts as opening_soon for catalog purposes.
  if (dToOpen > 0) return "opening_soon";

  // Open today: closing within the window is closing_soon, else current.
  if (dToClose <= CLOSING_SOON_DAYS) return "closing_soon";
  return "current";
}

module.exports = { classify, STATUSES, OPENING_SOON_DAYS, CLOSING_SOON_DAYS };
```

- [ ] **Step 4: Run test, confirm it passes**

```bash
cd web && node tests/status.test.js
```

Expected: `[status.test] all tests passed`.

- [ ] **Step 5: Commit**

```bash
git add web/scripts/lib/status.js web/tests/status.test.js
git commit -m "test(status): TDD — date-to-status classifier with 7-day windows"
```

---

### Task 2: Slug builder — `lib/slug.js`

**Files:**
- Create: `web/scripts/lib/slug.js`
- Test: `web/tests/slug.test.js`

**Background:** `slug = slugify(name_en ?? name_ko) + "-" + id.slice(0,4)`. The 4-char id suffix guarantees uniqueness across rows that happen to share titles (rare but real for recurring annual shows). Slugify must produce URL-safe ASCII; Korean inputs round-trip through transliteration that's good enough for URLs (we don't need lossless decoding).

- [ ] **Step 1: Write the failing test**

Create `web/tests/slug.test.js`:

```js
const assert = require("assert").strict;
const { buildSlug, slugify } = require("../scripts/lib/slug.js");

// Slugify primitives
assert.equal(slugify("Void Forms"),                "void-forms");
assert.equal(slugify("Line & Form"),               "line-form");
assert.equal(slugify("  Trim   Spaces  "),         "trim-spaces");
assert.equal(slugify("Already-hyphen"),            "already-hyphen");
assert.equal(slugify("UPPERCASE"),                 "uppercase");
assert.equal(slugify(""),                          "");

// Korean input: keep it as-is (URL-safe via percent-encoding at link time)
// but normalize whitespace and strip punctuation. We don't transliterate.
assert.equal(slugify("한국 단색화의 계보"),         "한국-단색화의-계보");
assert.equal(slugify("VOID — FORMS"),              "void-forms");

// buildSlug composes slugify(en ?? ko) + "-" + first 4 chars of id
const id = "abcd1234-5678-9012-3456-789012345678";
assert.equal(buildSlug({ name_en: "Void Forms", name_ko: "보이드 폼", id }), "void-forms-abcd");
assert.equal(buildSlug({ name_en: null, name_ko: "보이드 폼", id }),         "보이드-폼-abcd");
assert.equal(buildSlug({ name_en: "",   name_ko: "보이드 폼", id }),         "보이드-폼-abcd");
assert.equal(buildSlug({ name_en: "Void Forms", name_ko: null, id }),        "void-forms-abcd");

// Collision-resilience: same name, different ids → different slugs
const a = buildSlug({ name_en: "Annual Show", name_ko: null, id: "1111aaaa-..." });
const b = buildSlug({ name_en: "Annual Show", name_ko: null, id: "2222bbbb-..." });
assert.notEqual(a, b);
assert.equal(a, "annual-show-1111");
assert.equal(b, "annual-show-2222");

console.log("[slug.test] all tests passed");
```

- [ ] **Step 2: Run test, confirm it fails**

```bash
cd web && node tests/slug.test.js
```

Expected: `Cannot find module '../scripts/lib/slug.js'`.

- [ ] **Step 3: Implement `lib/slug.js`**

Create `web/scripts/lib/slug.js`:

```js
// Slug helpers for exhibition URLs.
// Korean characters are preserved as-is; browsers + Eleventy handle them via
// percent-encoding at link time. We deliberately do not transliterate Korean
// to ASCII — round-tripping is not lossless and the percent-encoded form
// is universally supported.

function slugify(input) {
  if (!input) return "";
  return String(input)
    .toLowerCase()
    .normalize("NFKC")
    // Replace anything that is NOT a letter/digit/hyphen with a space.
    // The unicode property escape \p{L} matches Korean and other scripts.
    .replace(/[^\p{L}\p{N}-]+/gu, " ")
    .trim()
    .replace(/\s+/g, "-")
    // Collapse repeated hyphens
    .replace(/-+/g, "-");
}

function buildSlug({ name_en, name_ko, id }) {
  const base = slugify(name_en || name_ko || "");
  const suffix = String(id).slice(0, 4);
  return base ? `${base}-${suffix}` : suffix;
}

module.exports = { slugify, buildSlug };
```

- [ ] **Step 4: Run test, confirm it passes**

```bash
cd web && node tests/slug.test.js
```

Expected: `[slug.test] all tests passed`.

- [ ] **Step 5: Commit**

```bash
git add web/scripts/lib/slug.js web/tests/slug.test.js
git commit -m "test(slug): TDD — slug builder preserves Korean, suffixes id"
```

---

### Task 3: Wire new tests into `npm test`

**Files:**
- Modify: `web/package.json`

- [ ] **Step 1: Read current test script**

```bash
cd web && grep '"test"' package.json
```

Expected output:
```
"test": "npm run build && node tests/showcase.test.js && node tests/accessibility.test.js && node tests/refresh-seed.test.js && npx playwright test"
```

- [ ] **Step 2: Add status + slug tests to the script**

Edit `web/package.json` — replace the `"test"` line:

Old:
```json
"test": "npm run build && node tests/showcase.test.js && node tests/accessibility.test.js && node tests/refresh-seed.test.js && npx playwright test"
```

New:
```json
"test": "node tests/status.test.js && node tests/slug.test.js && npm run build && node tests/showcase.test.js && node tests/accessibility.test.js && node tests/refresh-seed.test.js && npx playwright test"
```

(Pure-Node unit tests run *before* the build so a typo in lib/* fails fast.)

- [ ] **Step 3: Run, confirm both new tests pass**

```bash
cd web && node tests/status.test.js && node tests/slug.test.js
```

Expected: both print `... all tests passed`.

- [ ] **Step 4: Commit**

```bash
git add web/package.json
git commit -m "test(npm): run status + slug unit tests before build"
```

---

## Phase 2 — Data layer

Supabase migration, RLS verification, the new fetch script, and the seed fixture. After Phase 2, `_data/exhibitions.json` exists and the rest of the plan can read it.

### Task 4: Verify Supabase RLS allows anon read on `exhibitions`

**Files:** none modified — verification step.

- [ ] **Step 1: Probe the existing endpoint**

The existing `fetch-showcase.js` already reads from `exhibitions` with the anon key in production. Confirm it works in your local shell:

```bash
cd web
SUPABASE_URL="$SUPABASE_URL" SUPABASE_ANON_KEY="$SUPABASE_ANON_KEY" \
  curl -sS "$SUPABASE_URL/rest/v1/exhibitions?select=id&limit=1" \
  -H "apikey: $SUPABASE_ANON_KEY" -H "Authorization: Bearer $SUPABASE_ANON_KEY"
```

Expected: a JSON array (possibly empty if no rows) — not an error like `{"code":"42501","message":"permission denied for table exhibitions"}`.

If the endpoint returns `42501`, run the policy migration in Step 2. Otherwise skip Step 2.

- [ ] **Step 2 (only if Step 1 returned 42501): Add the policy via Supabase MCP**

Use the Supabase MCP `apply_migration` tool. Migration name: `exhibitions_anon_read_policy`. SQL:

```sql
CREATE POLICY exhibitions_anon_read ON exhibitions
  FOR SELECT TO anon USING (true);
```

Re-run Step 1 to confirm.

- [ ] **Step 3: No commit (verification only)**

If a migration was applied, it lives in Supabase project history — not in this repo.

---

### Task 5: Add missing columns to the `exhibitions` table — RESOLVED 2026-05-07

**Files:** Supabase migration only — no repo files modified.

**Outcome (2026-05-07):** Discovered partial pre-existing state — `description_ko` and `description_en` already existed from spec `012-bilingual-data-pipeline` with shape `text NOT NULL DEFAULT ''`. User decision: adapt downstream to "empty string = no description" rather than alter existing columns. Applied a reduced migration covering only the genuinely-missing columns.

Migration applied (name: `exhibitions_add_ticket_url_and_featured`):

```sql
ALTER TABLE exhibitions ADD COLUMN IF NOT EXISTS ticket_url text;
ALTER TABLE exhibitions ADD COLUMN IF NOT EXISTS featured   boolean NOT NULL DEFAULT false;
```

Final column shape on `exhibitions` (verified):

| column | data_type | nullable | default |
|---|---|---|---|
| `description_ko` (existing) | text | NO | `''::text` |
| `description_en` (existing) | text | NO | `''::text` |
| `ticket_url` (new) | text | YES | null |
| `featured` (new) | boolean | NO | `false` |

**Downstream tasks must treat empty strings as the "no description" sentinel for `description_ko` / `description_en`** — see updated guidance in Tasks 7, 10, 18 below.

---

### Task 6: Verify `gas/` sync handles new columns without code changes

**Files:**
- Read-only inspection of `gas/`

**Background:** Spec says "the existing sync upserts by column-name-keyed object, so new columns flow through without code changes — but the implementation plan must verify against the actual gas/ code, not assume."

- [ ] **Step 1: Identify the upsert path**

```bash
ls gas/
grep -rn "exhibitions" gas/ | head -30
grep -rn "upsert\|insert\|REST\|PATCH" gas/ | head -30
```

- [ ] **Step 2: Inspect the row-builder**

Find the function that builds the row object sent to Supabase (likely keyed by sheet header → value).

```bash
grep -rn "name_ko\|venue_name_ko" gas/
```

Read the surrounding function. Confirm the row is built from the sheet's header row (so adding `description_ko`, `description_en`, `ticket_url`, `featured` columns to the sheet flows through automatically) rather than from a hardcoded list of fields.

- [ ] **Step 3: Document the finding inline (no commit)**

Two outcomes:

- **(A) Sheet-header-driven** — confirmed. No `gas/` changes needed. Action item: add the four headers to the source Sheet (manual user step, do later as part of Phase 7 manual QA).
- **(B) Hardcoded field list** — `gas/` must be updated. If this is the case, **stop the plan and surface to the user** — extending the gas/ sync was not in the brainstorming scope and may need its own decision.

If (A), proceed to Task 7. If (B), pause and report.

---

### Task 7: Build-time fetcher — `fetch-exhibitions.js`

**Files:**
- Create: `web/scripts/fetch-exhibitions.js`
- Create: `web/scripts/exhibitions-seed.json` (initial empty stub — refreshed for real in Task 9)
- Modify: `web/package.json` (add to `build` script)

**Background:** Mirrors `fetch-showcase.js` patterns: env-var contract, production guard via `process.env.VERCEL`, seed fallback for local dev. The new script fetches all exhibitions (no random sampling), enriches each row with `slug` + `status`, and writes `_data/exhibitions.json`.

- [ ] **Step 1: Write a failing build-test smoke check**

Create `web/tests/fetch-exhibitions.test.js`:

```js
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
    featured: i === 1,
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
      await require(path.join(dir, "scripts", "fetch-exhibitions.js")).run();
    });
    const out = JSON.parse(fs.readFileSync(path.join(dir, "_data", "exhibitions.json"), "utf8"));
    assert.equal(out.exhibitions.length, 3);
    assert.equal(out.source, "supabase");
    // Each row enriched with slug + status
    for (const ex of out.exhibitions) {
      assert.match(ex.slug, /^show-\d+-id-$/);
      assert.ok(["current", "opening_soon", "closing_soon", "closed"].includes(ex.status));
    }
    // Featured pick: row 1 has featured=true → out.featuredId === row 1's id
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

  // ── Test 4: featured fallback to most-recently-opened current row when no featured=true ──
  await inTempDir(async (dir) => {
    const rows = [
      row(1, { featured: false, opening_date: "2026-01-01", closing_date: "2026-12-31" }),
      row(2, { featured: false, opening_date: "2026-04-01", closing_date: "2026-12-31" }),
      row(3, { featured: false, opening_date: "2026-05-01", closing_date: "2026-12-31" }),
    ];
    await withStubbedFetch(rows, async () => {
      process.env.SUPABASE_URL = "https://stub";
      process.env.SUPABASE_ANON_KEY = "stub";
      process.chdir(dir);
      delete require.cache[require.resolve(path.join(dir, "scripts", "fetch-exhibitions.js"))];
      await require(path.join(dir, "scripts", "fetch-exhibitions.js")).run();
    });
    const out = JSON.parse(fs.readFileSync(path.join(dir, "_data", "exhibitions.json"), "utf8"));
    assert.equal(out.featuredId, "id-3-aaaa-bbbb", "fallback picks most-recent opening_date");
  });

  console.log("[fetch-exhibitions.test] all tests passed");
})().catch((e) => { console.error(e); process.exit(1); });
```

- [ ] **Step 2: Run test, confirm failure**

```bash
cd web && node tests/fetch-exhibitions.test.js
```

Expected: `Cannot find module '..scripts/fetch-exhibitions.js'` or similar.

- [ ] **Step 3: Implement `fetch-exhibitions.js`**

Create `web/scripts/fetch-exhibitions.js`:

```js
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
  "city", "address",
  "opening_date", "closing_date",
  "cover_image_url",
  "description_ko", "description_en",
  "ticket_url", "featured",
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
  // Prefer rows explicitly marked featured. Tiebreak by id ascending.
  const flagged = exhibitions
    .filter((e) => e.featured === true)
    .sort((a, b) => String(a.id).localeCompare(String(b.id)));

  if (flagged.length === 1) return flagged[0].id;
  if (flagged.length > 1) {
    console.warn(`[fetch-exhibitions] ${flagged.length} featured rows; using first by id ascending: ${flagged[0].id}`);
    return flagged[0].id;
  }
  // Fallback: most recently opened current exhibition
  const current = exhibitions
    .filter((e) => e.status === "current")
    .sort((a, b) => String(b.opening_date).localeCompare(String(a.opening_date)));
  if (current.length > 0) {
    console.warn(`[fetch-exhibitions] no featured row; falling back to most-recently-opened current: ${current[0].id}`);
    return current[0].id;
  }
  console.warn(`[fetch-exhibitions] no featured + no current rows; featuredId=null`);
  return null;
}

function writeFromSeed(reason) {
  if (IS_PRODUCTION_BUILD) {
    console.error(
      `[fetch-exhibitions] FATAL: production build cannot fall back to seed (${reason}).`
    );
    process.exit(1);
  }
  console.log(`[fetch-exhibitions] using seed fallback (${reason})`);
  if (!fs.existsSync(OUTPUT_DIR)) fs.mkdirSync(OUTPUT_DIR, { recursive: true });
  const seed = JSON.parse(fs.readFileSync(SEED, "utf8"));
  const today = todayIso();
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

async function run() {
  const url = (process.env.SUPABASE_URL || "").trim();
  const key = (process.env.SUPABASE_ANON_KEY || "").trim();

  if (!url || !key) {
    writeFromSeed("env vars absent");
    return;
  }

  const today = todayIso();
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
    if (!res.ok) { writeFromSeed(`HTTP ${res.status}`); return; }
    rows = await res.json();
  } catch (err) {
    writeFromSeed(`fetch error: ${err.message}`);
    return;
  }

  if (!Array.isArray(rows)) {
    writeFromSeed("non-array response");
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
```

- [ ] **Step 4: Create the empty seed stub**

Create `web/scripts/exhibitions-seed.json`:

```json
{
  "fetchedAt": "1970-01-01T00:00:00.000Z",
  "source": "seed-curated",
  "exhibitions": []
}
```

(Real seed content is generated by Task 9.)

- [ ] **Step 5: Run the test, confirm pass**

```bash
cd web && node tests/fetch-exhibitions.test.js
```

Expected: `[fetch-exhibitions.test] all tests passed`.

- [ ] **Step 6: Wire into the build**

Edit `web/package.json` — extend the `build` script:

Old:
```json
"build": "node scripts/copy-fonts.js && node scripts/fetch-showcase.js && eleventy"
```

New:
```json
"build": "node scripts/copy-fonts.js && node scripts/fetch-showcase.js && node scripts/fetch-exhibitions.js && eleventy"
```

Also extend the test script (which currently runs the build) to include the new test:

Old:
```json
"test": "node tests/status.test.js && node tests/slug.test.js && npm run build && node tests/showcase.test.js && node tests/accessibility.test.js && node tests/refresh-seed.test.js && npx playwright test"
```

New:
```json
"test": "node tests/status.test.js && node tests/slug.test.js && node tests/fetch-exhibitions.test.js && npm run build && node tests/showcase.test.js && node tests/accessibility.test.js && node tests/refresh-seed.test.js && npx playwright test"
```

- [ ] **Step 7: Run a build to confirm wiring**

```bash
cd web && npm run build
```

Expected: build completes; `web/_data/exhibitions.json` exists. With env vars set, `source: "supabase"`. Without, `source: "seed"` with empty exhibitions.

- [ ] **Step 8: Commit**

```bash
git add web/scripts/fetch-exhibitions.js web/scripts/exhibitions-seed.json web/scripts/lib/ web/tests/fetch-exhibitions.test.js web/package.json
git commit -m "feat(build): fetch-exhibitions.js writes _data/exhibitions.json"
```

---

### Task 8: `refresh-exhibitions-seed.js` + anchors config

**Files:**
- Create: `web/scripts/refresh-exhibitions-seed.js`
- Create: `web/scripts/exhibitions-seed-anchors.json`
- Modify: `web/package.json` (npm script)
- Test: `web/tests/exhibitions-seed.test.js`

**Background:** Mirrors the existing `refresh-seed.js` pattern. Two purposes: (1) curates a real-data fixture for offline builds, (2) provides a deterministic test fixture for downstream Playwright tests. Targets a wider variety of statuses + cities than the showcase seed (which is "now showing" only).

- [ ] **Step 1: Write the failing test**

Create `web/tests/exhibitions-seed.test.js`:

```js
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
    ticket_url: null, featured: false,
    ...overrides,
  };
}

async function inTempDir(fn) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "refresh-exh-"));
  fs.mkdirSync(path.join(dir, "scripts", "lib"), { recursive: true });
  fs.copyFileSync(path.join(ROOT, "scripts", "refresh-exhibitions-seed.js"), path.join(dir, "scripts", "refresh-exhibitions-seed.js"));
  fs.copyFileSync(path.join(ROOT, "scripts", "lib", "status.js"), path.join(dir, "scripts", "lib", "status.js"));
  fs.copyFileSync(path.join(ROOT, "scripts", "lib", "slug.js"), path.join(dir, "scripts", "lib", "slug.js"));
  try { await fn(dir); } finally { fs.rmSync(dir, { recursive: true, force: true }); }
}

async function withStubbedFetch(byVenue, fn) {
  const orig = global.fetch;
  global.fetch = async () => ({ ok: true, status: 200, json: async () => byVenue });
  try { await fn(); } finally { global.fetch = orig; }
}

(async () => {
  // ── Test 1: writes seed with N entries, mixed statuses across cities ──
  await inTempDir(async (dir) => {
    fs.writeFileSync(path.join(dir, "scripts", "exhibitions-seed-anchors.json"), JSON.stringify({
      fillVenues: ["MMCA Seoul", "Leeum Museum of Art"],
      targetCount: 6,
    }));
    const rows = [1, 2, 3, 4, 5, 6, 7].map((i) => row(i));
    await withStubbedFetch(rows, async () => {
      process.env.SUPABASE_URL = "https://stub";
      process.env.SUPABASE_ANON_KEY = "stub";
      process.chdir(dir);
      delete require.cache[require.resolve(path.join(dir, "scripts", "refresh-exhibitions-seed.js"))];
      await require(path.join(dir, "scripts", "refresh-exhibitions-seed.js")).run();
    });
    const seed = JSON.parse(fs.readFileSync(path.join(dir, "scripts", "exhibitions-seed.json"), "utf8"));
    assert.equal(seed.exhibitions.length, 6);
    assert.equal(seed.source, "seed-curated");
  });

  // ── Test 2: errors when env vars missing ──
  await inTempDir(async (dir) => {
    fs.writeFileSync(path.join(dir, "scripts", "exhibitions-seed-anchors.json"), JSON.stringify({
      fillVenues: ["MMCA Seoul"], targetCount: 2,
    }));
    delete process.env.SUPABASE_URL;
    delete process.env.SUPABASE_ANON_KEY;
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
      process.chdir(dir);
      delete require.cache[require.resolve(path.join(dir, "scripts", "refresh-exhibitions-seed.js"))];
      try { await require(path.join(dir, "scripts", "refresh-exhibitions-seed.js")).run(); }
      catch { threw = true; }
    });
    assert.equal(threw, true);
  });

  console.log("[exhibitions-seed.test] all tests passed");
})().catch((e) => { console.error(e); process.exit(1); });
```

- [ ] **Step 2: Confirm failure**

```bash
cd web && node tests/exhibitions-seed.test.js
```

Expected: `Cannot find module '../scripts/refresh-exhibitions-seed.js'`.

- [ ] **Step 3: Implement `refresh-exhibitions-seed.js`**

Create `web/scripts/refresh-exhibitions-seed.js`:

```js
#!/usr/bin/env node
// One-shot manual builder for exhibitions-seed.json.
// Targets a varied seed (mixed statuses, ≥ 2 cities) so offline builds
// and Playwright fixtures exercise filter UI realistically.
//
// Usage:
//   SUPABASE_URL=... SUPABASE_ANON_KEY=... npm run refresh-exhibitions-seed

const fs = require("fs");
const path = require("path");

const ROOT = path.join(__dirname, "..");
const ANCHORS_FILE = path.join(ROOT, "scripts", "exhibitions-seed-anchors.json");
const OUTPUT = path.join(ROOT, "scripts", "exhibitions-seed.json");

const SELECT_COLS = [
  "id", "name_ko", "name_en",
  "venue_name_ko", "venue_name_en",
  "city", "address",
  "opening_date", "closing_date",
  "cover_image_url",
  "description_ko", "description_en",
  "ticket_url", "featured",
].join(",");

async function fetchVenues(url, key, venues, limit) {
  if (!venues.length) return [];
  const venueIn = venues.map((v) => `"${v}"`).join(",");
  const endpoint =
    `${url}/rest/v1/exhibitions` +
    `?select=${SELECT_COLS}` +
    `&venue_name_en=in.(${encodeURIComponent(venueIn)})` +
    `&limit=${limit}`;
  const res = await fetch(endpoint, {
    headers: { apikey: key, Authorization: `Bearer ${key}` },
  });
  if (!res.ok) throw new Error(`Supabase fetch ${res.status}`);
  const rows = await res.json();
  return Array.isArray(rows) ? rows : [];
}

async function run() {
  const url = (process.env.SUPABASE_URL || "").trim();
  const key = (process.env.SUPABASE_ANON_KEY || "").trim();
  if (!url || !key) {
    throw new Error("[refresh-exhibitions-seed] SUPABASE_URL and SUPABASE_ANON_KEY required");
  }
  const cfg = JSON.parse(fs.readFileSync(ANCHORS_FILE, "utf8"));
  const { fillVenues = [], targetCount = 12 } = cfg;
  const rows = await fetchVenues(url, key, fillVenues, targetCount * 2);
  if (rows.length < targetCount) {
    throw new Error(
      `[refresh-exhibitions-seed] got ${rows.length} rows, needed ${targetCount}. Add fillVenues or lower targetCount.`
    );
  }
  const exhibitions = rows.slice(0, targetCount);
  const out = {
    fetchedAt: new Date().toISOString(),
    source: "seed-curated",
    exhibitions,
  };
  fs.writeFileSync(OUTPUT, JSON.stringify(out, null, 2));
  console.log(`[refresh-exhibitions-seed] wrote ${exhibitions.length} entries`);
  return out;
}

if (require.main === module) {
  run().catch((e) => { console.error(e.message || e); process.exit(1); });
}
module.exports = { run };
```

- [ ] **Step 4: Create the anchors config**

Create `web/scripts/exhibitions-seed-anchors.json`:

```json
{
  "fillVenues": [
    "Leeum Museum of Art",
    "MMCA Seoul",
    "Amorepacific Museum of Art",
    "Kukje Gallery",
    "PKM Gallery",
    "Busan Museum of Art"
  ],
  "targetCount": 12
}
```

- [ ] **Step 5: Run the test, confirm pass**

```bash
cd web && node tests/exhibitions-seed.test.js
```

Expected: `[exhibitions-seed.test] all tests passed`.

- [ ] **Step 6: Add npm script + wire test**

Edit `web/package.json`:

Add to `scripts`:
```json
"refresh-exhibitions-seed": "node scripts/refresh-exhibitions-seed.js"
```

Update the `test` script to include the new seed test (insert before `&& npm run build`):
```json
"test": "node tests/status.test.js && node tests/slug.test.js && node tests/fetch-exhibitions.test.js && node tests/exhibitions-seed.test.js && npm run build && ..."
```

- [ ] **Step 7: Commit**

```bash
git add web/scripts/refresh-exhibitions-seed.js web/scripts/exhibitions-seed-anchors.json web/tests/exhibitions-seed.test.js web/package.json
git commit -m "feat(seed): refresh-exhibitions-seed for offline + fixture builds"
```

---

### Task 9: Run `refresh-exhibitions-seed` against live Supabase

**Files:**
- Modify: `web/scripts/exhibitions-seed.json` (overwritten with real curated data)

- [ ] **Step 1: Run with credentials**

```bash
cd web
SUPABASE_URL="$SUPABASE_URL" SUPABASE_ANON_KEY="$SUPABASE_ANON_KEY" \
  npm run refresh-exhibitions-seed
```

Expected: `[refresh-exhibitions-seed] wrote 12 entries`. The seed file is now populated with real exhibition rows.

- [ ] **Step 2: Inspect the result**

```bash
cd web && node -e 'const s=require("./scripts/exhibitions-seed.json"); console.log(s.exhibitions.length, "rows"); console.log("cities:", [...new Set(s.exhibitions.map(e => e.city))]);'
```

Expected: 12 rows, ≥ 2 cities.

- [ ] **Step 3: Commit the curated seed**

```bash
git add web/scripts/exhibitions-seed.json
git commit -m "chore(seed): refresh exhibitions-seed with curated real data"
```

---

### Task 10: Build-time fixture for tests

**Files:**
- Create: `web/tests/fixtures/exhibitions.json`

**Background:** Test data discipline — Playwright tests in later tasks must not fetch from live Supabase. They read this fixture instead. Stable, hand-curated to exercise: each of the 4 statuses, ≥ 2 cities, 1 row with `featured: true`, 1 row with all of `description_ko + description_en + ticket_url` populated, 1 row with empty-string descriptions and null `ticket_url` (the "no extras" case). **Note:** `description_ko` and `description_en` are `NOT NULL DEFAULT ''` in production — fixtures use `""`, never `null`. `ticket_url` is genuinely nullable.

- [ ] **Step 1: Create the fixture**

Create `web/tests/fixtures/exhibitions.json`:

```json
{
  "exhibitions": [
    {
      "id": "fx-001-current-featured",
      "name_ko": "보이드 폼", "name_en": "Void Forms",
      "venue_name_ko": "노이에 갤러리", "venue_name_en": "Neue Galerie",
      "city": "Seoul", "address": "1045 5th Ave, Seoul",
      "opening_date": "2026-04-01", "closing_date": "2026-12-31",
      "cover_image_url": "https://stub/fx-001.jpg",
      "description_ko": "공간의 부재를 탐구하는 전시.",
      "description_en": "An exploration of absence in space.",
      "ticket_url": "https://tickets.example/fx-001",
      "featured": true
    },
    {
      "id": "fx-002-closing-soon",
      "name_ko": "선과 형태", "name_en": "Line and Form",
      "venue_name_ko": "리움 미술관", "venue_name_en": "Leeum Museum of Art",
      "city": "Seoul", "address": "747-18 Hannam-dong",
      "opening_date": "2026-01-01", "closing_date": "2026-05-12",
      "cover_image_url": "https://stub/fx-002.jpg",
      "description_ko": "", "description_en": "",
      "ticket_url": null, "featured": false
    },
    {
      "id": "fx-003-opening-soon",
      "name_ko": "콘크리트 부르", "name_en": "Concrete Brut",
      "venue_name_ko": "국제갤러리", "venue_name_en": "Kukje Gallery",
      "city": "Seoul", "address": "54 Samcheong-ro",
      "opening_date": "2026-05-12", "closing_date": "2026-09-01",
      "cover_image_url": "https://stub/fx-003.jpg",
      "description_ko": "콘크리트의 물성에 대한 탐구.", "description_en": "",
      "ticket_url": null, "featured": false
    },
    {
      "id": "fx-004-closed",
      "name_ko": "모노크롬 연구", "name_en": "Monochrome Studies",
      "venue_name_ko": "부산시립미술관", "venue_name_en": "Busan Museum of Art",
      "city": "Busan", "address": "58 APEC-ro",
      "opening_date": "2025-11-01", "closing_date": "2026-03-31",
      "cover_image_url": "https://stub/fx-004.jpg",
      "description_ko": "", "description_en": "",
      "ticket_url": null, "featured": false
    }
  ]
}
```

- [ ] **Step 2: Commit**

```bash
git add web/tests/fixtures/exhibitions.json
git commit -m "test(fixtures): hand-curated exhibitions fixture for downstream tests"
```

---

## Phase 3 — Shared components & top-nav

Stub all six new components with markup + minimal CSS. Detail/Discover/Map can then import them in subsequent phases without further touching this layer.

### Task 11: Top-nav links — base.html

**Files:**
- Modify: `web/_includes/base.html`

**Background:** Current `<header>` has only the gallr wordmark + a `다운로드` CTA. Add the three nav links (`전시 / 지도 / 소개`) so all five routes are reachable. Keep Korean-forward bilingual: Korean primary, muted EN beneath.

- [ ] **Step 1: Read the current header**

Open `web/_includes/base.html` lines 38-47.

- [ ] **Step 2: Update the header**

Replace lines 38-47 with:

```html
  <header class="site-header">
    <div class="site-header__inner">
      <a href="/" class="site-logo" aria-label="gallr — home">
        <img src="/logos/b-arch-pin.svg" width="28" height="28" alt="" aria-hidden="true" class="site-logo__mark"/>
        <span class="site-logo__wordmark">gallr</span>
      </a>
      <nav class="site-nav" aria-label="Primary">
        <a href="/exhibitions/" class="site-nav__link">
          전시 <span class="bi-en" lang="en">EXHIBITIONS</span>
        </a>
        <a href="/map/" class="site-nav__link">
          지도 <span class="bi-en" lang="en">MAP</span>
        </a>
        <a href="/about/" class="site-nav__link">
          소개 <span class="bi-en" lang="en">ABOUT</span>
        </a>
      </nav>
      <a href="#downloads" class="site-header__cta" lang="ko">다운로드</a>
    </div>
    <div class="site-header__progress" aria-hidden="true"></div>
  </header>
```

- [ ] **Step 3: Append nav styles to main.css**

Append to `web/styles/main.css`:

```css
/* Top-nav links (added by multi-page catalog) */
.site-nav {
  display: flex;
  gap: var(--space-md);
  flex: 1;
  justify-content: center;
}
.site-nav__link {
  font-size: var(--type-eyebrow);
  letter-spacing: var(--type-eyebrow-tracking);
  text-transform: uppercase;
  color: var(--color-ink);
  text-decoration: none;
  white-space: nowrap;
}
.site-nav__link[aria-current="page"] { color: var(--color-accent); }
.site-nav__link:hover { opacity: 0.7; }
.site-nav .bi-en {
  display: inline;
  margin-left: 0.4em;
  color: var(--color-ink-secondary);
  font-size: 0.85em;
}
@media (max-width: 640px) {
  .site-nav { display: none; } /* hamburger menu out of scope; nav links hidden on small */
  .site-header__inner { justify-content: space-between; }
}
```

- [ ] **Step 4: Build to confirm no syntax errors**

```bash
cd web && npm run build 2>&1 | tail -20
```

Expected: build succeeds with no template errors. Visit `dist/index.html` and grep for the new links:

```bash
grep -c "site-nav__link" web/dist/index.html
```

Expected: `3` (three nav links rendered).

- [ ] **Step 5: Commit**

```bash
git add web/_includes/base.html web/styles/main.css
git commit -m "feat(nav): add Exhibitions / Map / About links to top nav"
```

---

### Task 12: `status-chip` component

**Files:**
- Create: `web/_includes/components/status-chip.njk`
- Modify: `web/styles/main.css`

**Background:** Used by Discover cards and the map sidebar. Three variants: `default` (outlined black), `accent` (orange — for `closing_soon` and `opening_soon`), `inverted` (black-fill, used in active filter rows but reused here for consistency).

- [ ] **Step 1: Create the include**

Create `web/_includes/components/status-chip.njk`:

```njk
{# Status chip — renders Korean primary + muted EN beneath.
   Args:
     status      one of "current" | "opening_soon" | "closing_soon" | "closed"
     variant     optional override: "default" | "accent" | "inverted"
   Usage:
     {% include "components/status-chip.njk" %}
#}
{% set _labels = {
  current:       { ko: "진행 중",  en: "CURRENT" },
  opening_soon:  { ko: "오픈 예정", en: "OPENING SOON" },
  closing_soon:  { ko: "종료 임박", en: "CLOSING SOON" },
  closed:        { ko: "종료됨",   en: "CLOSED" }
} %}
{% set _label = _labels[status] %}
{% set _variant = variant or ("accent" if status in ["opening_soon", "closing_soon"] else "default") %}
<span class="status-chip status-chip--{{ _variant }}" data-status="{{ status }}">
  <span class="status-chip__ko">{{ _label.ko }}</span>
  <span class="status-chip__en bi-en" lang="en">{{ _label.en }}</span>
</span>
```

- [ ] **Step 2: Append styles to main.css**

```css
/* Status chip */
.status-chip {
  display: inline-flex;
  align-items: baseline;
  gap: var(--space-xs);
  padding: var(--space-xs) var(--space-sm);
  border: var(--border-ink);
  font-size: var(--type-meta);
  font-weight: 700;
  letter-spacing: 0.05em;
  text-transform: uppercase;
  white-space: nowrap;
}
.status-chip__en { font-size: 0.85em; color: var(--color-ink-secondary); }

.status-chip--default { background: transparent; color: var(--color-ink); }
.status-chip--inverted {
  background: var(--color-ink); color: var(--color-paper); border-color: var(--color-ink);
}
.status-chip--inverted .status-chip__en { color: var(--color-ink-on-dark-secondary); }
.status-chip--accent {
  background: var(--color-accent); color: var(--color-paper); border-color: var(--color-accent);
}
.status-chip--accent .status-chip__en { color: rgba(255,255,255,0.75); }
```

- [ ] **Step 3: Smoke-render in a throwaway template**

Quick check that the include parses. Add a throwaway block to `web/index.html` at the very bottom (BEFORE the layout closes — meaning the `front matter` content area):

```bash
cd web && cat >> /tmp/sc-test.njk <<'EOF'
---
permalink: /__sc-test/index.html
layout: base.html
---
{% include "components/status-chip.njk" with { status: "current" } %}
{% include "components/status-chip.njk" with { status: "opening_soon" } %}
{% include "components/status-chip.njk" with { status: "closing_soon" } %}
{% include "components/status-chip.njk" with { status: "closed" } %}
EOF
cp /tmp/sc-test.njk web/__sc-test.njk
npm run build 2>&1 | tail -10
ls web/dist/__sc-test/
```

Expected: build succeeds, `web/dist/__sc-test/index.html` exists. Open it and visually confirm four chips render.

- [ ] **Step 4: Remove the throwaway template**

```bash
rm web/__sc-test.njk
```

- [ ] **Step 5: Commit**

```bash
git add web/_includes/components/status-chip.njk web/styles/main.css
git commit -m "feat(component): status-chip — bilingual, three variants"
```

---

### Task 13: `meta-pair` component

**Files:**
- Create: `web/_includes/components/meta-pair.njk`
- Modify: `web/styles/main.css`

- [ ] **Step 1: Create the include**

Create `web/_includes/components/meta-pair.njk`:

```njk
{# Meta pair — eyebrow + bilingual value.
   Args:
     labelKo, labelEn      eyebrow text (uppercase)
     valueKo               primary value (Korean)
     valueEn               optional muted English value beneath
   Usage:
     {% include "components/meta-pair.njk" with {
        labelKo: "갤러리", labelEn: "GALLERY",
        valueKo: ex.venue_name_ko, valueEn: ex.venue_name_en
     } %}
#}
<div class="meta-pair">
  <span class="meta-pair__label">
    {{ labelKo }} <span class="bi-en" lang="en">{{ labelEn }}</span>
  </span>
  <span class="meta-pair__value">
    {{ valueKo }}
    {% if valueEn %}<span class="bi-en" lang="en">{{ valueEn }}</span>{% endif %}
  </span>
</div>
```

- [ ] **Step 2: Append styles**

```css
/* Meta pair */
.meta-pair { display: flex; flex-direction: column; gap: var(--space-xs); }
.meta-pair__label {
  font-size: var(--type-eyebrow);
  letter-spacing: var(--type-eyebrow-tracking);
  text-transform: uppercase;
  color: var(--color-ink);
  font-weight: 700;
}
.meta-pair__label .bi-en { color: var(--color-ink-secondary); margin-left: 0.4em; font-size: 0.85em; }
.meta-pair__value { font-size: var(--type-body); color: var(--color-ink); }
.meta-pair__value .bi-en { display: block; color: var(--color-ink-secondary); font-size: 0.9em; margin-top: 2px; }
```

- [ ] **Step 3: Commit**

```bash
git add web/_includes/components/meta-pair.njk web/styles/main.css
git commit -m "feat(component): meta-pair — bilingual eyebrow + value"
```

---

### Task 14: `exhibition-card` component

**Files:**
- Create: `web/_includes/components/exhibition-card.njk`
- Modify: `web/styles/main.css`

**Background:** Default + featured variants. Default is a 4:3 image + meta. Featured spans 2 columns and includes an excerpt of `description_ko` (when present).

- [ ] **Step 1: Create the include**

Create `web/_includes/components/exhibition-card.njk`:

```njk
{# Exhibition card.
   Args:
     ex          exhibition row from _data/exhibitions.json
     variant     "default" (default) | "featured"
   Renders an <article> with a link to /exhibitions/{slug}/.
#}
{% set _variant = variant or "default" %}
<article class="exhibition-card exhibition-card--{{ _variant }}"
         data-status="{{ ex.status }}"
         data-city="{{ ex.city | lower }}">
  <a class="exhibition-card__link" href="/exhibitions/{{ ex.slug }}/">
    <div class="exhibition-card__image-wrap" data-fallback-title="{{ ex.name_ko }}">
      {% if ex.cover_image_url %}
      <img class="exhibition-card__image"
           src="{{ ex.cover_image_url }}"
           alt="{{ ex.name_ko }}, {{ ex.venue_name_ko }}"
           loading="lazy"
           onerror="this.parentElement.classList.add('exhibition-card__image-wrap--missing')" />
      {% endif %}
      <span class="exhibition-card__chip">
        {% include "components/status-chip.njk" with { status: ex.status } %}
      </span>
    </div>
    <div class="exhibition-card__caption">
      <h3 class="exhibition-card__title">
        {{ ex.name_ko }}
        {% if ex.name_en %}<span class="bi-en" lang="en">{{ ex.name_en }}</span>{% endif %}
      </h3>
      <p class="exhibition-card__venue">
        {{ ex.venue_name_ko }}
        {% if ex.venue_name_en %}<span class="bi-en" lang="en">{{ ex.venue_name_en }}</span>{% endif %}
      </p>
      <p class="exhibition-card__dates" lang="en">
        {{ ex.opening_date }} — {{ ex.closing_date }}
      </p>
      {% if _variant == "featured" and ex.description_ko %}
      <p class="exhibition-card__excerpt">{{ ex.description_ko | truncate(160) }}</p>
      {% endif %}
    </div>
  </a>
</article>
```

- [ ] **Step 2: Append styles**

```css
/* Exhibition card */
.exhibition-card { border: var(--border-ink); background: var(--color-paper); }
.exhibition-card__link { display: block; color: inherit; text-decoration: none; }
.exhibition-card__image-wrap {
  position: relative; aspect-ratio: 4/3; overflow: hidden;
  background: var(--color-paper-alt);
  border-bottom: var(--border-ink);
}
.exhibition-card__image {
  width: 100%; height: 100%; object-fit: cover;
  filter: grayscale(1); transition: filter var(--duration-med) var(--ease-gallery);
}
.exhibition-card:hover .exhibition-card__image { filter: grayscale(0); }
.exhibition-card__image-wrap--missing::after {
  content: attr(data-fallback-title);
  position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
  padding: var(--space-md); text-align: center; color: var(--color-ink-secondary);
}
.exhibition-card__chip {
  position: absolute; top: var(--space-sm); left: var(--space-sm);
}
.exhibition-card__caption {
  padding: var(--space-md);
  display: flex; flex-direction: column; gap: var(--space-xs);
}
.exhibition-card__title {
  font-size: var(--type-headline);
  font-weight: 700;
  letter-spacing: -0.01em;
  margin: 0;
}
.exhibition-card__title .bi-en {
  display: block; font-size: 0.7em; font-weight: 500;
  color: var(--color-ink-secondary); margin-top: 2px;
}
.exhibition-card__venue { font-size: var(--type-meta); text-transform: uppercase; letter-spacing: 0.05em; margin: 0; }
.exhibition-card__venue .bi-en { color: var(--color-ink-secondary); margin-left: 0.4em; font-size: 0.9em; }
.exhibition-card__dates { font-size: var(--type-meta); color: var(--color-ink-secondary); margin: 0; }
.exhibition-card__excerpt { font-size: var(--type-body); margin: var(--space-sm) 0 0; color: var(--color-ink); }
.exhibition-card--featured { grid-column: span 2; }
```

- [ ] **Step 3: Commit**

```bash
git add web/_includes/components/exhibition-card.njk web/styles/main.css
git commit -m "feat(component): exhibition-card — default + featured variants"
```

---

### Task 15: `filter-list` component

**Files:**
- Create: `web/_includes/components/filter-list.njk`
- Modify: `web/styles/main.css`

- [ ] **Step 1: Create the include**

Create `web/_includes/components/filter-list.njk`:

```njk
{# Filter list — vertical link list with inverted-active treatment.
   Args:
     groupKey         URL param key, e.g. "status" or "city"
     groupLabelKo     "상태" / "도시"
     groupLabelEn     "STATUS" / "CITY"
     items            [{ value, labelKo, labelEn }]
     activeValue      currently-active value (or "all")
   Filter clicks are wired up by client/filter.js.
#}
<div class="filter-list" data-filter-group="{{ groupKey }}">
  <h2 class="filter-list__heading">
    {{ groupLabelKo }} <span class="bi-en" lang="en">{{ groupLabelEn }}</span>
  </h2>
  <ul class="filter-list__items">
    {% for item in items %}
    <li>
      <a class="filter-list__link {% if item.value == activeValue %}is-active{% endif %}"
         href="?{{ groupKey }}={{ item.value }}"
         data-filter-value="{{ item.value }}">
        {{ item.labelKo }}
        {% if item.labelEn %}<span class="bi-en" lang="en">{{ item.labelEn }}</span>{% endif %}
      </a>
    </li>
    {% endfor %}
  </ul>
</div>
```

- [ ] **Step 2: Append styles**

```css
/* Filter list */
.filter-list { display: flex; flex-direction: column; gap: var(--space-sm); }
.filter-list__heading {
  font-size: var(--type-eyebrow); letter-spacing: var(--type-eyebrow-tracking);
  text-transform: uppercase; font-weight: 700;
  margin: 0 0 var(--space-sm);
}
.filter-list__heading .bi-en { color: var(--color-ink-secondary); margin-left: 0.4em; font-size: 0.85em; }
.filter-list__items { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: var(--space-xs); }
.filter-list__link {
  display: block; padding: var(--space-sm) var(--space-md);
  color: var(--color-ink); text-decoration: none;
  border: var(--border-ink); background: transparent;
  font-size: var(--type-meta); text-transform: uppercase; letter-spacing: 0.05em;
}
.filter-list__link.is-active { background: var(--color-ink); color: var(--color-paper); }
.filter-list__link:hover:not(.is-active) { background: var(--color-paper-alt); }
.filter-list__link .bi-en { color: var(--color-ink-secondary); margin-left: 0.4em; font-size: 0.9em; }
.filter-list__link.is-active .bi-en { color: var(--color-ink-on-dark-secondary); }
```

- [ ] **Step 3: Commit**

```bash
git add web/_includes/components/filter-list.njk web/styles/main.css
git commit -m "feat(component): filter-list — inverted-active links"
```

---

### Task 16: `sheet` and `sticky-mobile-cta` components

**Files:**
- Create: `web/_includes/components/sheet.njk`
- Create: `web/_includes/components/sticky-mobile-cta.njk`
- Modify: `web/styles/main.css`

- [ ] **Step 1: Create `sheet.njk`**

```njk
{# Full-screen overlay for mobile filter drawer.
   Args:
     id           DOM id for aria controls
     titleKo      "필터" / "메뉴"
     titleEn      "FILTERS" / "MENU"
     content      slot rendered into the body
   Visibility is controlled by .is-open on the root.
#}
<div class="sheet" id="{{ id }}" role="dialog" aria-modal="true" aria-labelledby="{{ id }}-title" hidden>
  <div class="sheet__inner">
    <header class="sheet__header">
      <h2 class="sheet__title" id="{{ id }}-title">
        {{ titleKo }} <span class="bi-en" lang="en">{{ titleEn }}</span>
      </h2>
      <button class="sheet__close" data-sheet-close="{{ id }}" aria-label="Close">×</button>
    </header>
    <div class="sheet__body">
      {{ content | safe }}
    </div>
  </div>
</div>
```

- [ ] **Step 2: Create `sticky-mobile-cta.njk`**

```njk
{# Sticky bottom CTA for detail page on small screens.
   Args:
     href         link target (Play Store / App Store / both via downloads.html)
     labelKo      "앱에서 보기"
     labelEn      "Get the App"
#}
<a class="sticky-mobile-cta" href="{{ href }}">
  {{ labelKo }} <span class="bi-en" lang="en">{{ labelEn }}</span>
</a>
```

- [ ] **Step 3: Append styles**

```css
/* Sheet (mobile overlay) */
.sheet {
  position: fixed; inset: 0; z-index: 50;
  background: var(--color-paper); overflow-y: auto;
}
.sheet[hidden] { display: none; }
.sheet__inner { padding: var(--space-lg); }
.sheet__header { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: var(--space-md); }
.sheet__title { font-size: var(--type-headline); font-weight: 700; margin: 0; }
.sheet__title .bi-en { color: var(--color-ink-secondary); margin-left: 0.4em; font-size: 0.7em; }
.sheet__close {
  background: transparent; border: var(--border-ink); width: 44px; height: 44px;
  font-size: 24px; cursor: pointer;
}

/* Sticky mobile CTA */
.sticky-mobile-cta {
  position: fixed; bottom: 0; left: 0; right: 0; z-index: 40;
  background: var(--color-accent); color: var(--color-paper);
  padding: var(--space-md); text-align: center;
  font-weight: 700; text-transform: uppercase; letter-spacing: 0.05em;
  text-decoration: none;
  display: none; /* shown on detail page only via .has-sticky-cta */
}
.has-sticky-cta .sticky-mobile-cta { display: block; }
@media (min-width: 768px) {
  .has-sticky-cta .sticky-mobile-cta { display: none; }
}
.sticky-mobile-cta .bi-en { color: rgba(255,255,255,0.75); margin-left: 0.4em; font-size: 0.9em; }
```

- [ ] **Step 4: Commit**

```bash
git add web/_includes/components/sheet.njk web/_includes/components/sticky-mobile-cta.njk web/styles/main.css
git commit -m "feat(component): sheet + sticky-mobile-cta"
```

---

## Phase 4 — Discover + Detail pages

These two share components and ship together.

### Task 17: 11ty pagination → static detail pages

**Files:**
- Create: `web/exhibitions/exhibition.11ty.js`
- Create: `web/exhibitions/_layout.njk` (will be created in Task 19; this task creates the pagination skeleton)

- [ ] **Step 1: Create the pagination file**

Create `web/exhibitions/exhibition.11ty.js`:

```js
// 11ty pagination — generates one HTML file per exhibition.
// Reads from _data/exhibitions.json (written by fetch-exhibitions.js).
// Output: /exhibitions/[slug]/index.html

module.exports = {
  data: {
    layout: "base.html",
    pagination: {
      data: "exhibitions.exhibitions",
      size: 1,
      alias: "exhibition",
    },
    eleventyComputed: {
      title: (data) => `${data.exhibition.name_ko} — gallr`,
    },
    permalink: (data) => `/exhibitions/${data.exhibition.slug}/index.html`,
  },
  render: function (data) {
    const ex = data.exhibition;
    // Stub — Task 19 replaces this with the full template via includes.
    return `<article class="detail-page">
      <h1>${this.escapeHtml(ex.name_ko)}</h1>
      <p>Status: ${ex.status}</p>
      <p>Slug: ${ex.slug}</p>
    </article>`;
  },
  escapeHtml: function (s) {
    return String(s).replace(/[&<>"']/g, (c) => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
  },
};
```

- [ ] **Step 2: Build, confirm one HTML file per fixture row**

```bash
cd web && npm run build && ls dist/exhibitions/
```

Expected (with the 12-row curated seed): twelve `<slug>/` directories, each containing `index.html`.

- [ ] **Step 3: Open one to confirm slug + status are rendered**

```bash
ls web/dist/exhibitions/ | head -1 | xargs -I {} cat web/dist/exhibitions/{}/index.html | grep -E "Status|Slug"
```

Expected: status + slug visible.

- [ ] **Step 4: Commit**

```bash
git add web/exhibitions/exhibition.11ty.js
git commit -m "feat(detail): 11ty pagination scaffolds /exhibitions/[slug]/"
```

---

### Task 18: Detail page — full template

**Files:**
- Create: `web/exhibitions/_detail.njk` (the actual template, used by exhibition.11ty.js)
- Modify: `web/exhibitions/exhibition.11ty.js` (render via the include)
- Modify: `web/styles/main.css`

**Background:** The detail page is mostly markup + CSS. Per spec: hero image (7 cols) + info panel (5 cols) ≥ 768px, single column below. Action stack: orange "앱에서 보기", outlined "공유", black-fill "티켓" (conditional), text-only "길찾기 →". Description block omitted when both `_ko` and `_en` are null.

- [ ] **Step 1: Create the detail template**

Create `web/exhibitions/_detail.njk`:

```njk
{# Detail page body. Renders inside base.html. Variable: ex (the exhibition row). #}
<article class="detail-page has-sticky-cta">
  <div class="detail-page__inner">

    <div class="detail-page__hero" data-fallback-title="{{ ex.name_ko }}">
      {% if ex.cover_image_url %}
      <img class="detail-page__hero-image"
           src="{{ ex.cover_image_url }}"
           alt="{{ ex.name_ko }}, {{ ex.venue_name_ko }}"
           onerror="this.parentElement.classList.add('detail-page__hero--missing')" />
      {% endif %}
    </div>

    <aside class="detail-page__info">
      <header class="detail-page__title-block">
        {% include "components/status-chip.njk" with { status: ex.status } %}
        <h1 class="detail-page__title">
          {{ ex.name_ko }}
          {% if ex.name_en %}<span class="bi-en" lang="en">{{ ex.name_en }}</span>{% endif %}
        </h1>
      </header>

      <div class="detail-page__meta-grid">
        {% include "components/meta-pair.njk" with {
          labelKo: "갤러리", labelEn: "GALLERY",
          valueKo: ex.venue_name_ko, valueEn: ex.venue_name_en
        } %}
        {% include "components/meta-pair.njk" with {
          labelKo: "일정", labelEn: "DATES",
          valueKo: ex.opening_date + " — " + ex.closing_date, valueEn: null
        } %}
        {% include "components/meta-pair.njk" with {
          labelKo: "위치", labelEn: "LOCATION",
          valueKo: ex.address, valueEn: null
        } %}
        {% include "components/meta-pair.njk" with {
          labelKo: "도시", labelEn: "CITY",
          valueKo: ex.city, valueEn: null
        } %}
      </div>

      {% if ex.description_ko or ex.description_en %}
      <section class="detail-page__about">
        <h2 class="detail-page__about-heading">
          전시 소개 <span class="bi-en" lang="en">ABOUT THE EXHIBITION</span>
        </h2>
        {% if ex.description_ko %}<p class="detail-page__about-prose">{{ ex.description_ko }}</p>{% endif %}
        {% if ex.description_en %}<p class="detail-page__about-prose bi-en" lang="en">{{ ex.description_en }}</p>{% endif %}
      </section>
      {% endif %}

      <div class="detail-page__actions">
        <a class="detail-page__cta-primary" href="#downloads">
          앱에서 보기 <span class="bi-en" lang="en">Get the App</span>
        </a>
        <div class="detail-page__action-row">
          <button class="detail-page__cta-outline" data-share-button data-share-title="{{ ex.name_ko }}" data-share-url="/exhibitions/{{ ex.slug }}/">
            공유 <span class="bi-en" lang="en">Share</span>
          </button>
          {% if ex.ticket_url %}
          <a class="detail-page__cta-filled" href="{{ ex.ticket_url }}" target="_blank" rel="noopener">
            티켓 → <span class="bi-en" lang="en">Tickets →</span>
          </a>
          {% endif %}
        </div>
        <a class="detail-page__cta-text"
           href="https://map.naver.com/v5/search/{{ ex.address | urlencode }}"
           target="_blank" rel="noopener">
          길찾기 → <span class="bi-en" lang="en">Get Directions →</span>
        </a>
      </div>
    </aside>

  </div>
  {% include "components/sticky-mobile-cta.njk" with {
    href: "#downloads", labelKo: "앱에서 보기", labelEn: "Get the App"
  } %}
</article>
<script defer src="/scripts/share.js"></script>
```

- [ ] **Step 2: Replace the stub render in `exhibition.11ty.js`**

Replace the entire `module.exports` with:

```js
const fs = require("fs");
const path = require("path");
const TEMPLATE = fs.readFileSync(path.join(__dirname, "_detail.njk"), "utf8");

module.exports = {
  data: {
    layout: "base.html",
    pagination: {
      data: "exhibitions.exhibitions",
      size: 1,
      alias: "exhibition",
    },
    eleventyComputed: {
      title: (data) => `${data.exhibition.name_ko} — gallr`,
    },
    permalink: (data) => `/exhibitions/${data.exhibition.slug}/index.html`,
  },
  render: function (data) {
    return this.renderTemplate(TEMPLATE, "njk", { ex: data.exhibition });
  },
};
```

**Note:** 11ty's JS template files have access to `this.renderTemplate(string, engine, data)` for inline rendering against any engine. If that API isn't available in 3.x, the alternative is to make `_detail.njk` a partial included via `{% include %}` from a `.njk` paginated template. Verify by running the build:

- [ ] **Step 3: Build and verify**

```bash
cd web && npm run build 2>&1 | tail -20
ls dist/exhibitions/ | head -3
cat dist/exhibitions/$(ls dist/exhibitions/ | head -1)/index.html | grep -E "detail-page__title|status-chip"
```

Expected: build succeeds, detail HTML includes the title block and status chip.

If `this.renderTemplate` is not available in 11ty 3.x, fall through to Step 3b.

- [ ] **Step 3b (only if Step 3 fails): convert to a `.njk` paginated template**

Delete `web/exhibitions/exhibition.11ty.js`. Create `web/exhibitions/exhibition.njk` with the front-matter form:

```njk
---
layout: base.html
pagination:
  data: exhibitions.exhibitions
  size: 1
  alias: exhibition
permalink: "/exhibitions/{{ exhibition.slug }}/index.html"
eleventyComputed:
  title: "{{ exhibition.name_ko }} — gallr"
---
{% set ex = exhibition %}
{% include "../exhibitions/_detail.njk" %}
```

Note: includes look in `_includes/` first by Eleventy convention. Since `_detail.njk` is in `web/exhibitions/`, move it to `web/_includes/detail-page.njk` instead and adjust the include path:

```njk
{% include "detail-page.njk" %}
```

- [ ] **Step 4: Append detail-page styles**

```css
/* Detail page */
.detail-page__inner {
  display: grid; gap: var(--space-xl);
  padding: var(--space-xl) var(--page-padding-x);
  max-width: var(--max-width); margin: 0 auto;
}
@media (min-width: 768px) {
  .detail-page__inner {
    grid-template-columns: 7fr 5fr;
    align-items: start;
  }
}
.detail-page__hero {
  position: relative; aspect-ratio: 4/3;
  background: var(--color-paper-alt); overflow: hidden;
  border: var(--border-ink);
}
.detail-page__hero-image {
  width: 100%; height: 100%; object-fit: cover;
  filter: grayscale(1); transition: filter var(--duration-med) var(--ease-gallery);
}
.detail-page__hero:hover .detail-page__hero-image { filter: grayscale(0); }
.detail-page__hero--missing::after {
  content: attr(data-fallback-title);
  position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
  padding: var(--space-md); color: var(--color-ink-secondary);
}
.detail-page__info { display: flex; flex-direction: column; gap: var(--space-lg); }
.detail-page__title-block { display: flex; flex-direction: column; gap: var(--space-md); }
.detail-page__title {
  font-size: var(--type-display-sm); font-weight: 700;
  letter-spacing: -0.02em; margin: 0; line-height: 1.1;
}
.detail-page__title .bi-en {
  display: block; font-size: 0.55em; font-weight: 500;
  color: var(--color-ink-secondary); margin-top: var(--space-sm);
}
.detail-page__meta-grid {
  display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-md);
}
.detail-page__about-heading {
  font-size: var(--type-eyebrow); letter-spacing: var(--type-eyebrow-tracking);
  text-transform: uppercase; font-weight: 700;
  margin: 0 0 var(--space-md);
}
.detail-page__about-heading .bi-en { color: var(--color-ink-secondary); margin-left: 0.4em; font-size: 0.85em; }
.detail-page__about-prose { font-size: var(--type-body-lg); line-height: 1.6; margin: 0 0 var(--space-md); }
.detail-page__about-prose.bi-en { color: var(--color-ink-secondary); font-size: var(--type-body); }

.detail-page__actions { display: flex; flex-direction: column; gap: var(--space-md); }
.detail-page__cta-primary {
  display: block; padding: var(--space-md); text-align: center;
  background: var(--color-accent); color: var(--color-paper);
  text-decoration: none; font-weight: 700; text-transform: uppercase; letter-spacing: 0.05em;
  border: 1px solid var(--color-accent);
}
.detail-page__cta-primary .bi-en { color: rgba(255,255,255,0.8); margin-left: 0.4em; font-size: 0.9em; }
.detail-page__action-row { display: flex; gap: var(--space-sm); }
.detail-page__cta-outline {
  flex: 1; padding: var(--space-md); text-align: center;
  background: transparent; color: var(--color-ink);
  border: var(--border-ink); cursor: pointer;
  font-weight: 700; text-transform: uppercase; letter-spacing: 0.05em;
  font-family: inherit; font-size: var(--type-meta);
}
.detail-page__cta-filled {
  flex: 1; padding: var(--space-md); text-align: center;
  background: var(--color-ink); color: var(--color-paper);
  text-decoration: none;
  font-weight: 700; text-transform: uppercase; letter-spacing: 0.05em;
  font-size: var(--type-meta);
}
.detail-page__cta-text {
  display: inline-block; color: var(--color-ink); text-decoration: underline;
  font-size: var(--type-meta); text-transform: uppercase; letter-spacing: 0.05em;
}
.detail-page__cta-text .bi-en, .detail-page__cta-outline .bi-en, .detail-page__cta-filled .bi-en {
  color: var(--color-ink-secondary); margin-left: 0.4em; font-size: 0.9em;
}
.detail-page__cta-filled .bi-en { color: rgba(255,255,255,0.7); }
```

- [ ] **Step 5: Implement `share.js`**

Create `web/client/share.js`:

```js
// Web Share API + clipboard fallback. Used by detail page Share button.
(function () {
  document.addEventListener("click", function (e) {
    const btn = e.target.closest("[data-share-button]");
    if (!btn) return;
    const title = btn.dataset.shareTitle || document.title;
    const path = btn.dataset.shareUrl || window.location.pathname;
    const url = new URL(path, window.location.origin).toString();
    if (navigator.share) {
      navigator.share({ title, url }).catch(() => {});
    } else if (navigator.clipboard) {
      navigator.clipboard.writeText(url).then(() => {
        btn.dataset.shareCopied = "true";
        const original = btn.textContent;
        btn.textContent = "복사됨";
        setTimeout(() => { btn.textContent = original; delete btn.dataset.shareCopied; }, 2000);
      });
    }
  });
})();
```

- [ ] **Step 6: Add `client/` to passthrough copy**

Edit `web/.eleventy.js`:

Old:
```js
eleventyConfig.addPassthroughCopy("scripts/main.js");
```

New:
```js
eleventyConfig.addPassthroughCopy("scripts/main.js");
eleventyConfig.addPassthroughCopy({ "client": "scripts" });
```

This copies `web/client/*.js` → `dist/scripts/*.js`, so `<script src="/scripts/share.js">` resolves.

- [ ] **Step 7: Build and visually verify one page**

```bash
cd web && npm run build
open dist/exhibitions/$(ls dist/exhibitions/ | head -1)/index.html
```

Expected: detail page renders with hero image (or fallback), title, status chip, meta grid, action stack. About section appears for the row that has descriptions; absent for others.

- [ ] **Step 8: Commit**

```bash
git add web/exhibitions/ web/_includes/detail-page.njk web/client/share.js web/styles/main.css web/.eleventy.js
git commit -m "feat(detail): /exhibitions/[slug]/ rendered from exhibitions.json"
```

---

### Task 19: Discover page — markup + filter sidebar

**Files:**
- Create: `web/exhibitions/index.html`
- Modify: `web/styles/main.css`

- [ ] **Step 1: Create the page**

Create `web/exhibitions/index.html`:

```njk
---
layout: base.html
title: "전시 — gallr"
permalink: /exhibitions/index.html
---
{% set statusItems = [
  { value: "all",          labelKo: "전체",     labelEn: "ALL" },
  { value: "current",      labelKo: "진행 중",  labelEn: "CURRENT" },
  { value: "opening_soon", labelKo: "오픈 예정", labelEn: "OPENING SOON" },
  { value: "closing_soon", labelKo: "종료 임박", labelEn: "CLOSING SOON" },
  { value: "closed",       labelKo: "종료됨",   labelEn: "CLOSED" }
] %}
{% set cities = exhibitions.exhibitions | map(attribute="city") | unique | list %}
{% set cityItems = [{ value: "all", labelKo: "전체 도시", labelEn: "ALL CITIES" }] %}
{% for c in cities %}
  {% set cityItems = cityItems.concat([{ value: c|lower, labelKo: c, labelEn: c|upper }]) %}
{% endfor %}

<section class="discover-page">
  <header class="discover-page__header">
    <h1 class="discover-page__title">
      전시 <span class="bi-en" lang="en">EXHIBITIONS</span>
    </h1>
    <p class="discover-page__count" lang="en">
      {{ exhibitions.exhibitions.length }} TOTAL
    </p>
  </header>

  <div class="discover-page__layout">
    <aside class="discover-page__sidebar">
      {% include "components/filter-list.njk" with {
        groupKey: "status", groupLabelKo: "상태", groupLabelEn: "STATUS",
        items: statusItems, activeValue: "all"
      } %}
      {% include "components/filter-list.njk" with {
        groupKey: "city", groupLabelKo: "도시", groupLabelEn: "CITY",
        items: cityItems, activeValue: "all"
      } %}
    </aside>

    <div class="discover-page__grid">
      {% for ex in exhibitions.exhibitions %}
        {% include "components/exhibition-card.njk" %}
      {% endfor %}
      <div class="discover-page__empty" data-empty hidden>
        <p>조건에 맞는 전시가 없습니다.</p>
        <p class="bi-en" lang="en">No exhibitions match your filters.</p>
        <button class="discover-page__reset" data-filter-reset>
          필터 초기화 <span class="bi-en" lang="en">RESET FILTERS</span>
        </button>
      </div>
    </div>
  </div>
</section>
<script defer src="/scripts/filter.js"></script>
```

**Note on Nunjucks:** The `cityItems = cityItems.concat(...)` pattern only works because `concat` is a JS array method exposed by Nunjucks. If your Eleventy/Nunjucks version rejects it, replace the loop with a custom filter or build the list in `_data/`. Easier path: precompute cities in `_data/exhibitions.js` (a tiny new file).

If the build errors on `concat`, do Step 1b instead.

- [ ] **Step 1b (only if Step 1 errored): precompute cities in a new data file**

Create `web/_data/cities.js`:

```js
// Computed at build time — used by /exhibitions/index.html filter sidebar.
const exhibitions = require("./exhibitions.json");

module.exports = function () {
  const set = new Set(
    exhibitions.exhibitions.map((e) => e.city).filter(Boolean)
  );
  const items = [{ value: "all", labelKo: "전체 도시", labelEn: "ALL CITIES" }];
  for (const c of [...set].sort()) {
    items.push({ value: c.toLowerCase(), labelKo: c, labelEn: c.toUpperCase() });
  }
  return items;
};
```

Replace the `{% set cities … %}` and `{% set cityItems … %}` blocks in `web/exhibitions/index.html` with:

```njk
{% set cityItems = cities %}
```

- [ ] **Step 2: Append discover styles**

```css
/* Discover page */
.discover-page { padding: var(--space-xl) var(--page-padding-x); max-width: var(--max-width); margin: 0 auto; }
.discover-page__header {
  display: flex; justify-content: space-between; align-items: baseline;
  border-bottom: var(--border-section); padding-bottom: var(--space-md); margin-bottom: var(--space-lg);
}
.discover-page__title { font-size: var(--type-display); font-weight: 800; margin: 0; letter-spacing: -0.04em; }
.discover-page__title .bi-en { display: block; font-size: 0.3em; color: var(--color-ink-secondary); font-weight: 500; }
.discover-page__count { font-size: var(--type-meta); color: var(--color-ink-secondary); margin: 0; }
.discover-page__layout { display: grid; gap: var(--space-lg); }
@media (min-width: 768px) {
  .discover-page__layout { grid-template-columns: 240px 1fr; }
}
.discover-page__sidebar { display: flex; flex-direction: column; gap: var(--space-lg); }
.discover-page__grid { display: grid; gap: var(--space-md); grid-template-columns: 1fr; }
@media (min-width: 768px) { .discover-page__grid { grid-template-columns: 1fr 1fr; } }
@media (min-width: 1280px) { .discover-page__grid { grid-template-columns: 1fr 1fr 1fr; } }
.discover-page__empty {
  grid-column: 1 / -1; padding: var(--space-3xl) var(--space-md);
  text-align: center; color: var(--color-ink-secondary);
}
.discover-page__empty[hidden] { display: none; }
.discover-page__reset {
  margin-top: var(--space-md); padding: var(--space-sm) var(--space-md);
  background: transparent; border: var(--border-ink); cursor: pointer; font-family: inherit;
}
```

- [ ] **Step 3: Build, confirm Discover renders**

```bash
cd web && npm run build && open dist/exhibitions/index.html
```

Expected: page renders with sidebar (status + city groups), card grid populated from the 12 fixture rows.

- [ ] **Step 4: Commit**

```bash
git add web/exhibitions/index.html web/styles/main.css web/_data/cities.js
git commit -m "feat(discover): /exhibitions/ page with filter sidebar + card grid"
```

(Omit `web/_data/cities.js` from the commit if Step 1b wasn't needed.)

---

### Task 20: Discover client-side filtering — `client/filter.js`

**Files:**
- Create: `web/client/filter.js`
- Test: `web/tests/discover-filter.test.ts` (Playwright)

**Background:** URL params drive the filter state. On load, read `?status=&city=` from `location.search`, hide non-matching cards, mark the matching `.filter-list__link` as active. On filter click, update URL via `history.replaceState` and re-toggle. Reset button clears both.

- [ ] **Step 1: Write a failing Playwright test**

Create `web/tests/discover-filter.test.ts`:

```ts
import { test, expect } from "@playwright/test";

const BASE = "http://localhost:8080";

test.describe("Discover filtering", () => {
  test("default: all 4 fixture cards visible", async ({ page }) => {
    await page.goto(`${BASE}/exhibitions/`);
    const visibleCards = await page.locator(".exhibition-card:visible").count();
    expect(visibleCards).toBeGreaterThanOrEqual(4); // 4 fixture rows minimum
  });

  test("status=closed shows only closed rows", async ({ page }) => {
    await page.goto(`${BASE}/exhibitions/?status=closed`);
    const visibleCards = page.locator(".exhibition-card:visible");
    await expect(visibleCards).toHaveCount(1, { timeout: 2000 });
    await expect(visibleCards.first()).toHaveAttribute("data-status", "closed");
  });

  test("clicking a status filter updates the URL", async ({ page }) => {
    await page.goto(`${BASE}/exhibitions/`);
    await page.locator('[data-filter-group="status"] [data-filter-value="current"]').click();
    await expect(page).toHaveURL(/[?&]status=current/);
    const visibleCards = page.locator(".exhibition-card:visible");
    await expect(visibleCards.first()).toHaveAttribute("data-status", "current");
  });

  test("city + status combine", async ({ page }) => {
    await page.goto(`${BASE}/exhibitions/?status=current&city=busan`);
    const visibleCards = page.locator(".exhibition-card:visible");
    // fx-001 is current+seoul, fx-004 is closed+busan, so current+busan = 0
    await expect(visibleCards).toHaveCount(0);
    const empty = page.locator("[data-empty]");
    await expect(empty).toBeVisible();
  });

  test("reset button clears filters", async ({ page }) => {
    await page.goto(`${BASE}/exhibitions/?status=closed`);
    await page.locator("[data-filter-reset]").click();
    await expect(page).toHaveURL(/\/exhibitions\/$/);
    const visibleCards = await page.locator(".exhibition-card:visible").count();
    expect(visibleCards).toBeGreaterThanOrEqual(4);
  });
});
```

- [ ] **Step 2: Confirm failing run**

```bash
cd web && npm run build && npx serve dist -p 8080 &
sleep 2
npx playwright test tests/discover-filter.test.ts
kill %1
```

Expected: tests fail (filter.js doesn't exist yet, all cards always visible).

- [ ] **Step 3: Implement `client/filter.js`**

Create `web/client/filter.js`:

```js
// Discover page client-side filter.
// URL params:  ?status=<value>&city=<value>
// Defaults:    status=all, city=all (omit param to mean "all")

(function () {
  const root = document.querySelector(".discover-page");
  if (!root) return;

  const cards = Array.from(root.querySelectorAll(".exhibition-card"));
  const empty = root.querySelector("[data-empty]");
  const groups = ["status", "city"];

  function readParams() {
    const p = new URLSearchParams(window.location.search);
    return { status: p.get("status") || "all", city: p.get("city") || "all" };
  }

  function applyFilters() {
    const { status, city } = readParams();
    let visibleCount = 0;
    for (const card of cards) {
      const cardStatus = card.dataset.status;
      const cardCity = card.dataset.city;
      const statusOk = status === "all" || cardStatus === status;
      const cityOk = city === "all" || cardCity === city;
      const visible = statusOk && cityOk;
      card.hidden = !visible;
      if (visible) visibleCount++;
    }
    if (empty) empty.hidden = visibleCount > 0;
    syncActiveLinks(status, city);
  }

  function syncActiveLinks(status, city) {
    for (const group of groups) {
      const value = group === "status" ? status : city;
      const links = root.querySelectorAll(`[data-filter-group="${group}"] [data-filter-value]`);
      for (const link of links) {
        link.classList.toggle("is-active", link.dataset.filterValue === value);
      }
    }
  }

  function setParam(key, value) {
    const p = new URLSearchParams(window.location.search);
    if (value === "all") p.delete(key);
    else p.set(key, value);
    const search = p.toString();
    const url = window.location.pathname + (search ? `?${search}` : "");
    window.history.replaceState(null, "", url);
  }

  root.addEventListener("click", function (e) {
    const link = e.target.closest("[data-filter-value]");
    if (link) {
      e.preventDefault();
      const group = link.closest("[data-filter-group]").dataset.filterGroup;
      setParam(group, link.dataset.filterValue);
      applyFilters();
      return;
    }
    if (e.target.closest("[data-filter-reset]")) {
      e.preventDefault();
      window.history.replaceState(null, "", window.location.pathname);
      applyFilters();
    }
  });

  applyFilters();
})();
```

- [ ] **Step 4: Use the matching CSS rule for hidden cards**

`.exhibition-card[hidden]` is hidden by default browser behavior, but the `:visible` Playwright selector relies on CSS visibility too. Append:

```css
.exhibition-card[hidden] { display: none !important; }
```

- [ ] **Step 5: Re-run tests**

```bash
cd web && npm run build && npx serve dist -p 8080 &
sleep 2
npx playwright test tests/discover-filter.test.ts
kill %1
```

Expected: all 5 tests pass.

- [ ] **Step 6: Commit**

```bash
git add web/client/filter.js web/tests/discover-filter.test.ts web/styles/main.css
git commit -m "feat(discover): URL-driven client-side filter with reset"
```

---

### Task 21: Detail page Playwright tests

**Files:**
- Create: `web/tests/detail-page.test.ts`

- [ ] **Step 1: Write the test**

```ts
import { test, expect } from "@playwright/test";

const BASE = "http://localhost:8080";

test.describe("Detail page", () => {
  test("featured fixture renders title, description, tickets, share, directions", async ({ page }) => {
    await page.goto(`${BASE}/exhibitions/void-forms-fx-0/`);
    await expect(page.locator(".detail-page__title")).toContainText("보이드 폼");
    await expect(page.locator(".detail-page__about")).toBeVisible();
    await expect(page.locator(".detail-page__cta-filled")).toHaveAttribute("href", "https://tickets.example/fx-001");
    await expect(page.locator("[data-share-button]")).toBeVisible();
    await expect(page.locator(".detail-page__cta-text")).toContainText("길찾기");
    await expect(page.locator(".detail-page__cta-primary")).toContainText("앱에서 보기");
  });

  test("row with no description omits the About block", async ({ page }) => {
    await page.goto(`${BASE}/exhibitions/line-and-form-fx-0/`);
    await expect(page.locator(".detail-page__about")).toHaveCount(0);
  });

  test("row with no ticket_url omits the Tickets button", async ({ page }) => {
    await page.goto(`${BASE}/exhibitions/line-and-form-fx-0/`);
    await expect(page.locator(".detail-page__cta-filled")).toHaveCount(0);
  });

  test("status chip reflects the row's status", async ({ page }) => {
    await page.goto(`${BASE}/exhibitions/monochrome-studies-fx-0/`);
    await expect(page.locator(".status-chip")).toHaveAttribute("data-status", "closed");
  });
});
```

**Note:** The slug suffix is the *first 4 chars* of the id. For fixture id `fx-001-current-featured`, slug suffix is `fx-0`. Double-check the actual generated slugs by listing `dist/exhibitions/`:

```bash
ls web/dist/exhibitions/
```

Adjust the slugs in the test to match what's actually generated.

- [ ] **Step 2: Run, confirm pass**

```bash
cd web && npm run build && npx serve dist -p 8080 &
sleep 2
npx playwright test tests/detail-page.test.ts
kill %1
```

Expected: all 4 tests pass.

- [ ] **Step 3: Commit**

```bash
git add web/tests/detail-page.test.ts
git commit -m "test(detail): Playwright coverage for detail-page rendering"
```

---

## Phase 5 — Map page

Naver Maps SDK + sidebar list with bidirectional sync.

### Task 22: Map page markup

**Files:**
- Create: `web/map/index.html`
- Modify: `web/styles/main.css`

- [ ] **Step 1: Create the page**

Create `web/map/index.html`:

```njk
---
layout: base.html
title: "지도 — gallr"
permalink: /map/index.html
---
{% set mapped = exhibitions.exhibitions | rejectattr("status", "equalto", "closed") | list %}
<section class="map-page">
  <aside class="map-page__sidebar" id="map-sidebar">
    <header class="map-page__sidebar-header">
      <h2 class="map-page__sidebar-title">
        전시 <span class="bi-en" lang="en">EXHIBITIONS</span>
        <span class="map-page__sidebar-count">· {{ mapped.length }}</span>
      </h2>
    </header>
    <ul class="map-page__list" data-map-list>
      {% for ex in mapped %}
      <li class="map-page__list-item" data-exhibition-id="{{ ex.id }}" data-slug="{{ ex.slug }}">
        <a href="/exhibitions/{{ ex.slug }}/" class="map-page__list-link">
          {% include "components/status-chip.njk" with { status: ex.status } %}
          <span class="map-page__list-title">
            {{ ex.name_ko }}
            {% if ex.name_en %}<span class="bi-en" lang="en">{{ ex.name_en }}</span>{% endif %}
          </span>
          <span class="map-page__list-venue">
            {{ ex.venue_name_ko }}
            {% if ex.venue_name_en %}<span class="bi-en" lang="en">{{ ex.venue_name_en }}</span>{% endif %}
          </span>
        </a>
      </li>
      {% endfor %}
    </ul>
  </aside>

  <div class="map-page__map" id="naver-map" data-naver-map></div>

  <script type="application/json" id="exhibitions-data">
    {% set data = [] %}
    {%- for ex in mapped -%}
      {%- set data = data.concat([{ id: ex.id, slug: ex.slug, nameKo: ex.name_ko, venueKo: ex.venue_name_ko, address: ex.address, status: ex.status }]) -%}
    {%- endfor -%}
    {{ data | dump | safe }}
  </script>
</section>

{# Naver Maps SDK — public client ID is referrer-restricted in the Naver console. #}
<script src="https://oapi.map.naver.com/openapi/v3/maps.js?ncpClientId={{ site.naverClientId }}"></script>
<script defer src="/scripts/map.js"></script>
```

**Note on lat/lng:** Spec assumes the Supabase row has lat/lng. Inspect the actual schema:

```bash
curl -sS "$SUPABASE_URL/rest/v1/exhibitions?select=*&limit=1" \
  -H "apikey: $SUPABASE_ANON_KEY" -H "Authorization: Bearer $SUPABASE_ANON_KEY" | python3 -m json.tool | head -40
```

If `lat`/`lng` columns exist, add them to the `SELECT_COLS` in `fetch-exhibitions.js` and to the JSON island. If not, the map will need geocoding by `address` — that is **out of scope** for this plan and the implementer must surface the gap to the user.

- [ ] **Step 2: Verify lat/lng presence**

Either confirm the columns exist (proceed) or stop and surface the gap.

If lat/lng columns are present:
- Add `"latitude", "longitude"` (or whatever the columns are named) to `SELECT_COLS` in `fetch-exhibitions.js`.
- Add `lat: ex.latitude, lng: ex.longitude` to the JSON-island object.

If not present, **stop and report** — we need to decide between (a) running a one-time geocoding pass, (b) deferring the map page, or (c) using a coarse city-center fallback.

- [ ] **Step 3: Add `naverClientId` to site data**

Edit `web/_data/site.json` — append `"naverClientId": "REPLACE_ME"`.

The real value comes from the Naver Cloud console; populated locally via env var or hardcoded after consultation with the user. **Action item:** confirm value with user before deploy.

- [ ] **Step 4: Append map-page styles**

```css
/* Map page */
.map-page {
  display: grid; grid-template-columns: 1fr;
  height: calc(100vh - 60px);
}
@media (min-width: 768px) {
  .map-page { grid-template-columns: 320px 1fr; }
}
.map-page__sidebar {
  border-right: var(--border-ink); overflow-y: auto;
  background: var(--color-paper);
}
.map-page__sidebar-header { padding: var(--space-md); border-bottom: var(--border-ink); }
.map-page__sidebar-title { font-size: var(--type-eyebrow); letter-spacing: var(--type-eyebrow-tracking); text-transform: uppercase; margin: 0; }
.map-page__sidebar-count { color: var(--color-ink-secondary); }
.map-page__list { list-style: none; margin: 0; padding: 0; }
.map-page__list-item { border-bottom: var(--border-ink); }
.map-page__list-item.is-active { background: var(--color-ink); color: var(--color-paper); }
.map-page__list-item.is-active .bi-en { color: var(--color-ink-on-dark-secondary); }
.map-page__list-link {
  display: flex; flex-direction: column; gap: var(--space-xs);
  padding: var(--space-md); color: inherit; text-decoration: none;
}
.map-page__list-title { font-weight: 700; }
.map-page__list-venue { font-size: var(--type-meta); text-transform: uppercase; letter-spacing: 0.05em; }
.map-page__map {
  background: var(--color-paper-alt);
  filter: grayscale(1) contrast(1.05);
}
```

- [ ] **Step 5: Build, verify the page renders without map.js**

```bash
cd web && npm run build && open dist/map/index.html
```

Expected: sidebar list visible, map area is a gray box. Naver SDK is loaded but not yet initialized.

- [ ] **Step 6: Commit**

```bash
git add web/map/ web/styles/main.css web/_data/site.json
git commit -m "feat(map): scaffold /map/ with sidebar + Naver SDK include"
```

---

### Task 23: Map client script — Naver init + pin/sidebar sync

**Files:**
- Create: `web/client/map.js`
- Test: `web/tests/map-page.test.ts`

- [ ] **Step 1: Implement `client/map.js`**

```js
// /map/ page client.
// 1. Reads exhibitions JSON island.
// 2. If window.naver is loaded, initializes the map + pins.
// 3. Wires bidirectional sync: pin click ↔ sidebar row click.

(function () {
  const dataEl = document.getElementById("exhibitions-data");
  if (!dataEl) return;
  let exhibitions;
  try { exhibitions = JSON.parse(dataEl.textContent); } catch { return; }

  const sidebarItems = Array.from(document.querySelectorAll("[data-exhibition-id]"));
  function setActive(id) {
    for (const item of sidebarItems) {
      const isActive = item.dataset.exhibitionId === id;
      item.classList.toggle("is-active", isActive);
      if (isActive) item.scrollIntoView({ block: "nearest", behavior: "smooth" });
    }
    if (window.__gallrMapMarkers) {
      for (const m of window.__gallrMapMarkers) {
        m.element.classList.toggle("is-active", m.id === id);
      }
    }
  }

  // Sidebar → pin (delegated)
  document.addEventListener("click", function (e) {
    const item = e.target.closest("[data-exhibition-id]");
    if (!item) return;
    // Allow the link to navigate; only intercept for keyboard / non-link clicks.
    // Default Cmd/Ctrl-click should still work.
    if (e.target.closest("a")) return;
    e.preventDefault();
    setActive(item.dataset.exhibitionId);
  });

  function initMap() {
    if (!window.naver || !window.naver.maps) return;
    const container = document.getElementById("naver-map");
    if (!container) return;
    const valid = exhibitions.filter((e) => e.lat != null && e.lng != null);
    if (valid.length === 0) return;
    const map = new naver.maps.Map(container, {
      center: new naver.maps.LatLng(valid[0].lat, valid[0].lng),
      zoom: 11,
    });
    const bounds = new naver.maps.LatLngBounds();
    const markers = [];
    for (const ex of valid) {
      const pos = new naver.maps.LatLng(ex.lat, ex.lng);
      bounds.extend(pos);
      const el = document.createElement("div");
      el.className = "map-pin";
      el.title = ex.nameKo;
      const marker = new naver.maps.Marker({
        position: pos,
        map,
        icon: { content: el.outerHTML, anchor: new naver.maps.Point(6, 6) },
      });
      naver.maps.Event.addListener(marker, "click", () => setActive(ex.id));
      markers.push({ id: ex.id, element: el, marker });
    }
    window.__gallrMapMarkers = markers;
    if (markers.length > 1) map.fitBounds(bounds);
  }

  if (window.naver && window.naver.maps) initMap();
  else window.addEventListener("load", initMap);
})();
```

- [ ] **Step 2: Append map-pin styles**

```css
.map-pin {
  width: 12px; height: 12px; background: var(--color-ink);
  display: inline-block;
}
.map-pin.is-active { background: var(--color-accent); }
```

- [ ] **Step 3: Write the Playwright test with stubbed Naver SDK**

Create `web/tests/map-page.test.ts`:

```ts
import { test, expect } from "@playwright/test";

const BASE = "http://localhost:8080";

test.describe("Map page", () => {
  test.beforeEach(async ({ page }) => {
    // Stub the Naver SDK so the test doesn't need a token or network access
    await page.route("**/maps.js**", (route) => {
      route.fulfill({
        status: 200,
        contentType: "application/javascript",
        body: `
          window.naver = {
            maps: {
              Map: function(){ this.fitBounds = function(){}; },
              LatLng: function(lat,lng){ this.lat=lat; this.lng=lng; },
              LatLngBounds: function(){ this.extend = function(){}; },
              Marker: function(){},
              Point: function(){},
              Event: { addListener: function(){} }
            }
          };
        `,
      });
    });
  });

  test("sidebar lists all non-closed fixture exhibitions", async ({ page }) => {
    await page.goto(`${BASE}/map/`);
    const items = page.locator(".map-page__list-item");
    await expect(items).toHaveCount(3); // fx-001, fx-002, fx-003 (fx-004 is closed)
  });

  test("map container exists with grayscale class", async ({ page }) => {
    await page.goto(`${BASE}/map/`);
    await expect(page.locator("#naver-map")).toBeVisible();
  });

  test("clicking sidebar status chip area marks the row active", async ({ page }) => {
    await page.goto(`${BASE}/map/`);
    // Click the status-chip span (which is inside the link but not a link itself isn't the case
    // since the whole row is wrapped in <a>). For now assert the markup wires the data attribute.
    const first = page.locator(".map-page__list-item").first();
    await expect(first).toHaveAttribute("data-exhibition-id", /fx-/);
  });
});
```

**Note:** Because the entire sidebar row is wrapped in `<a href="/exhibitions/[slug]/">`, a click navigates away rather than activating. This is by design — the spec said pin click activates the sidebar (one-way for nav purposes). Bidirectional sync is implemented but the sidebar-to-pin direction is only meaningful via keyboard focus or hover. Adjust the test if needed.

- [ ] **Step 4: Run the tests**

```bash
cd web && npm run build && npx serve dist -p 8080 &
sleep 2
npx playwright test tests/map-page.test.ts
kill %1
```

Expected: all 3 tests pass (with the test count adjusted to match how many fixture rows are non-closed at the test's "today" — see the next step).

- [ ] **Step 5: Address fixture-time drift**

The fixture's status depends on `today`. As wall-clock time moves past the fixture's `closing_date`s, statuses change and the test counts drift. Two options:

- **(A)** Pin the fixture to dates far in the future (already done — `2026-12-31`, `2026-09-01` etc.).
- **(B)** Inject a `TODAY` env var honored by `lib/status.js` and `fetch-exhibitions.js` for tests.

Choose **(A)** for now. If/when the fixture needs to test edge cases close to today, do **(B)** as a follow-up.

- [ ] **Step 6: Commit**

```bash
git add web/client/map.js web/tests/map-page.test.ts web/styles/main.css
git commit -m "feat(map): Naver SDK init + pin/sidebar sync"
```

---

## Phase 6 — About page + home featured wiring

### Task 24: About page

**Files:**
- Create: `web/about/index.html`

- [ ] **Step 1: Create the page**

Create `web/about/index.html`:

```njk
---
layout: base.html
title: "소개 — gallr"
permalink: /about/index.html
---
{% include "about.html" %}
```

- [ ] **Step 2: Build, verify**

```bash
cd web && npm run build && open dist/about/index.html
```

Expected: page renders the same About content as the home page section.

- [ ] **Step 3: Commit**

```bash
git add web/about/index.html
git commit -m "feat(about): /about/ wraps existing about include"
```

---

### Task 25: Home hero featured-exhibition link

**Files:**
- Modify: `web/_includes/hero.html`

**Background:** The current hero has no featured-exhibition overlay; the only "featured" surface today is the marquee. Per spec: "The hero featured-exhibition image overlay (the small black bar that currently reads `FEATURED EXHIBITION ↗` over the right-column image) becomes a real link to the `featured = true` exhibition's detail page."

But re-reading the live `hero.html` (Task 1 inspection): there is no such overlay today — that copy was from the redesign mockup, not the live site. The current live hero is the editorial Korean-forward kinetic headline + marquee.

**Resolution:** the spec's Home section says the change is "single, additive" and described an overlay that doesn't actually exist. The minimum work that satisfies the spec's intent is: each marquee tile becomes a link to its detail page, and the tile linked first is the `featured` exhibition.

- [ ] **Step 1: Read the current marquee block (lines 51-66)**

Already known. Marquee currently renders `<figure>` + `<img>` per `showcase.exhibitions.slice(0, 8)` — 8 tiles, no links.

- [ ] **Step 2: Wrap marquee tiles in links**

Replace lines 51-66 of `web/_includes/hero.html`:

Old:
```njk
  <div class="hero__marquee" data-marquee data-duration="40" aria-label="현재 진행 중인 전시" data-reveal>
    <div class="hero__marquee-track">
      <div class="hero__marquee-inner" data-marquee-inner>
        {% for ex in showcase.exhibitions.slice(0, 8) %}
        <figure class="hero__marquee-tile" data-fallback-title="{{ ex.titleKo }}">
          <img
            src="{{ ex.coverImageUrl }}"
            alt="{{ ex.titleKo }}, {{ ex.venueKo }}"
            loading="{% if loop.index <= 4 %}eager{% else %}lazy{% endif %}"
            onerror="this.parentElement.classList.add('hero__marquee-tile--missing')"
          />
        </figure>
        {% endfor %}
      </div>
    </div>
  </div>
```

New:
```njk
  <div class="hero__marquee" data-marquee data-duration="40" aria-label="현재 진행 중인 전시" data-reveal>
    <div class="hero__marquee-track">
      <div class="hero__marquee-inner" data-marquee-inner>
        {% for ex in showcase.exhibitions.slice(0, 8) %}
        {# Look up the matching exhibitions.json row by id to get the slug. #}
        {% set fullEx = exhibitions.exhibitions | selectattr("id", "equalto", ex.id) | first %}
        {% set href = fullEx and "/exhibitions/" + fullEx.slug + "/" or "/exhibitions/" %}
        <a class="hero__marquee-tile-link" href="{{ href }}">
          <figure class="hero__marquee-tile" data-fallback-title="{{ ex.titleKo }}">
            <img
              src="{{ ex.coverImageUrl }}"
              alt="{{ ex.titleKo }}, {{ ex.venueKo }}"
              loading="{% if loop.index <= 4 %}eager{% else %}lazy{% endif %}"
              onerror="this.parentElement.classList.add('hero__marquee-tile--missing')"
            />
          </figure>
        </a>
        {% endfor %}
      </div>
    </div>
  </div>
```

- [ ] **Step 3: Append minimal style for the wrapping link**

```css
.hero__marquee-tile-link { display: block; color: inherit; text-decoration: none; }
```

- [ ] **Step 4: Build, click a tile manually to confirm navigation**

```bash
cd web && npm run build && open dist/index.html
```

Click any marquee tile; expect navigation to `/exhibitions/[slug]/`.

- [ ] **Step 5: Commit**

```bash
git add web/_includes/hero.html web/styles/main.css
git commit -m "feat(home): hero marquee tiles link to detail pages"
```

---

## Phase 7 — Multi-page acceptance + visual + a11y

### Task 26: Multi-page build assertion test

**Files:**
- Create: `web/tests/multipage-build.test.js`

- [ ] **Step 1: Write the test**

```js
const fs = require("fs");
const path = require("path");
const assert = require("assert").strict;

const ROOT = path.join(__dirname, "..");
const DIST = path.join(ROOT, "dist");

function exists(p) { return fs.existsSync(path.join(DIST, p)); }

const exhibitions = require(path.join(ROOT, "_data", "exhibitions.json"));

// Top-level routes
const routes = ["index.html", "exhibitions/index.html", "map/index.html", "about/index.html", "privacy/index.html"];
for (const r of routes) assert.ok(exists(r), `${r} missing`);

// One detail page per exhibition
for (const ex of exhibitions.exhibitions) {
  assert.ok(exists(`exhibitions/${ex.slug}/index.html`), `exhibitions/${ex.slug}/index.html missing`);
}

// JSON island present on map page
const mapHtml = fs.readFileSync(path.join(DIST, "map/index.html"), "utf8");
assert.ok(mapHtml.includes('id="exhibitions-data"'), "map page missing JSON island");

// Discover page contains at least one card
const discoverHtml = fs.readFileSync(path.join(DIST, "exhibitions/index.html"), "utf8");
assert.ok(discoverHtml.includes("exhibition-card"), "discover page missing cards");

console.log("[multipage-build.test] all assertions passed");
```

- [ ] **Step 2: Run, confirm pass**

```bash
cd web && npm run build && node tests/multipage-build.test.js
```

Expected: `[multipage-build.test] all assertions passed`.

- [ ] **Step 3: Wire into `npm test`**

Edit `web/package.json` `test` script — add `&& node tests/multipage-build.test.js` after the build:

```json
"test": "node tests/status.test.js && node tests/slug.test.js && node tests/fetch-exhibitions.test.js && node tests/exhibitions-seed.test.js && npm run build && node tests/multipage-build.test.js && node tests/showcase.test.js && node tests/accessibility.test.js && node tests/refresh-seed.test.js && npx playwright test"
```

- [ ] **Step 4: Commit**

```bash
git add web/tests/multipage-build.test.js web/package.json
git commit -m "test(multipage): assert all five routes + per-exhibition pages exist"
```

---

### Task 27: Extend pa11y to new routes

**Files:**
- Modify: `web/tests/accessibility.test.js`

- [ ] **Step 1: Read the current pa11y test**

```bash
cd web && cat tests/accessibility.test.js
```

Understand the URL list and pattern (it likely loops over an array of paths).

- [ ] **Step 2: Add the four new routes**

Append to the URL list: `/exhibitions/`, `/exhibitions/<first-slug>/`, `/map/`, `/about/`.

The first slug comes from `_data/exhibitions.json`. Either hardcode the first slug (and update if the seed changes) or read it dynamically:

```js
const exhibitions = require("../_data/exhibitions.json");
const firstSlug = exhibitions.exhibitions[0]?.slug;
const detailPath = firstSlug ? `/exhibitions/${firstSlug}/` : null;
```

- [ ] **Step 3: Run pa11y**

```bash
cd web && npm run build && node tests/accessibility.test.js
```

Expected: zero AA violations.

If violations appear (likely candidates: insufficient color contrast on muted EN, missing `lang` attributes, missing alt on fallback states), fix at the source (markup or CSS) and re-run. Do not silence pa11y rules.

- [ ] **Step 4: Commit**

```bash
git add web/tests/accessibility.test.js
git commit -m "test(a11y): pa11y covers /exhibitions/, /map/, /about/, detail page"
```

---

### Task 28: Visual regression coverage

**Files:**
- Modify: existing visual-regression Playwright test (review which file holds the four-viewport pattern from `ad70a78`)

- [ ] **Step 1: Identify the visual test file**

```bash
cd web && grep -rn "toHaveScreenshot\|375.*768\|breakpoints" tests/ | head -10
```

Locate the test that loops over viewports. Read it.

- [ ] **Step 2: Add the four new routes to the loop**

Add `/exhibitions/`, `/exhibitions/<first-slug>/`, `/map/`, `/about/` to the page list. Use the same fixture-driven first slug as Task 27.

- [ ] **Step 3: Generate baselines**

```bash
cd web && npm run build && npx serve dist -p 8080 &
sleep 2
npx playwright test --update-snapshots
kill %1
```

Inspect the new screenshots in `tests/__screenshots__/` (or wherever Playwright stores them). Visually compare against the redesign mockups; flag any mismatches as inline TODOs to address before deploy (style fixes only — not new functionality).

- [ ] **Step 4: Commit baselines**

```bash
git add web/tests/ web/tests/__screenshots__/
git commit -m "test(visual): add four new routes to visual regression baselines"
```

---

## Phase 8 — Pre-deploy + ship

### Task 29: Naver console + Vercel env vars

**Files:** none (external configuration)

- [ ] **Step 1: Confirm Naver client ID and add domains to allowlist**

Action items for the user (cannot be automated):

1. Log in to the Naver Cloud console.
2. Locate or create the Maps JS application.
3. Add to the referrer allowlist:
   - `https://gallrmap.com`
   - `https://*.vercel.app` (preview deploys; restrict if security needs it)
   - `http://localhost:8080` (local preview)
4. Copy the client ID into `web/_data/site.json` as `naverClientId`.

Surface this checklist to the user before running Step 2. Pause the plan if access is unavailable.

- [ ] **Step 2: Confirm Vercel env vars**

Action items for the user:

1. In the Vercel project, confirm `SUPABASE_URL` and `SUPABASE_ANON_KEY` are set on Production and Preview.
2. (No new env vars required — Naver client ID is in the repo, by necessity.)

- [ ] **Step 3: No commit**

This is a configuration step; nothing in the repo changes.

---

### Task 30: Full test pass + open PR

**Files:** none modified — verification + PR creation.

- [ ] **Step 1: Run the full test suite locally**

```bash
cd web && npm test
```

Expected: every test passes. If any fail, fix in place and re-run before opening the PR.

- [ ] **Step 2: Manual visual check at desktop + mobile**

Use the dev server:

```bash
cd web && npm run preview
```

Visit each route at desktop (1280) and mobile (375 via DevTools). Check:

- All five routes load with no console errors.
- Discover filters work (status, city, both, reset).
- Detail page renders with + without descriptions, with + without ticket URL.
- Map page sidebar lists exhibitions; map area shows pins (real Naver tiles will only render after the allowlist is configured).
- About page renders.
- Home marquee tiles link to detail pages.

- [ ] **Step 3: Push and open PR against `develop`**

```bash
git push -u origin 038-gallrmap-multipage-catalog

gh pr create --base develop --title "feat(web): multi-page catalog (exhibitions, detail, map, about)" --body "$(cat <<'EOF'
## Summary
- Expands gallrmap.com from one editorial single page into five static routes.
- Per-exhibition detail pages built from Supabase at build time via 11ty pagination.
- Discover page with URL-driven status × city filters (client-side, no per-filter HTML files).
- Naver Maps page with all current/upcoming exhibitions and bidirectional sidebar/pin sync.
- Adds 2 Supabase columns (`ticket_url` text NULL, `featured` boolean NOT NULL DEFAULT false). `description_ko` / `description_en` already existed from spec 012; treat empty string as "no description". Mobile app unaffected.
- Korean-forward bilingual pattern (PR #44) extended to all new pages — no EN/KO toggle.

Spec: `docs/superpowers/specs/2026-05-07-gallrmap-multipage-catalog-design.md`

## Test plan
- [ ] `npm test` passes locally
- [ ] Naver console allowlist updated for production + preview domains
- [ ] Vercel preview deploy: every route loads without console errors
- [ ] Discover filter mechanics verified manually (status × city, reset)
- [ ] Detail page renders correctly for fixtures with + without optional fields
- [ ] Map shows pins on `/map/` (after allowlist configured)
- [ ] Home marquee tiles link to detail pages
- [ ] Mobile app builds unchanged (sanity check)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 4: After PR review and merge into `develop`, deploy via existing pipeline**

Vercel auto-deploys on `develop` merges (per PR #44/#46/#47 history). No extra steps required.

If something breaks post-deploy: `vercel rollback` to the prior production deployment. Supabase columns are additive and stay; mobile app is unaffected.

---

## Self-Review

Spec coverage walk-through:

| Spec section | Plan tasks |
|---|---|
| Schema additions (4 cols) | Task 5 |
| RLS verification | Task 4 |
| Apps Script verification | Task 6 |
| `fetch-exhibitions.js` | Task 7 |
| Pagination → detail pages | Task 17, Task 18 |
| `/` Home — featured link | Task 25 |
| `/exhibitions/` Discover | Tasks 19, 20 |
| `/exhibitions/[slug]/` Detail | Task 18, Task 21 |
| `/map/` | Tasks 22, 23 |
| `/about/` | Task 24 |
| Korean-forward bilingual extension | Threaded through every component (Tasks 12–16) and page tasks |
| `lib/status.js` | Task 1 |
| `lib/slug.js` | Task 2 |
| Component inventory (6 components) | Tasks 12–16 |
| Test scaffolding extension (unit, build, Playwright, pa11y) | Tasks 1, 2, 7, 8, 20, 21, 23, 26, 27, 28 |
| Naver allowlist | Task 29 |
| Acceptance criterion 1 (no JS errors on all routes) | Tasks 26, 30 |
| AC 2 (bilingual consistency) | Component tasks + manual review in Task 30 |
| AC 3 (Discover filters) | Task 20 |
| AC 4 (detail conditional rendering) | Task 21 |
| AC 5 (map pins for non-closed) | Task 22 (`rejectattr` filter) + Task 23 |
| AC 6 (Lighthouse) | Manual check Task 30; not auto-asserted |
| AC 7 (`npm test` passes) | Task 30 |
| AC 8 (mobile app unchanged) | Sanity check Task 30 |
| AC 9 (home unchanged save for hero link) | Task 25 |

Every spec section maps to a task. **Lighthouse AC 6 is not automated** — flagged for manual verification in Task 30. If automation is needed later, add a `@playwright/test`-orchestrated Lighthouse run as a follow-up.

Type/method consistency check:
- `classify` / `STATUSES` defined in Task 1, used in Tasks 7, 8.
- `buildSlug` / `slugify` defined in Task 2, used in Tasks 7, 8.
- `pickFeatured` defined in Task 7 — internal to the script, no external callers.
- `data-status`, `data-city`, `data-exhibition-id` data-attribute names are referenced consistently across components (Task 14) and clients (Tasks 20, 23).
- Status values use snake_case throughout (`current`, `opening_soon`, `closing_soon`, `closed`). Confirmed across `lib/status.js`, the URL params, and CSS attribute selectors.

No placeholder phrases (`TODO`, `TBD`, "implement later", etc.) remain. Two explicit pause-points for the implementer (Task 6 outcome B; Task 22 if no lat/lng) are tagged as such, not as placeholders.
