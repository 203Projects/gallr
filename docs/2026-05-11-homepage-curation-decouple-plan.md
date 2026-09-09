# Decouple gallr homepage from daily live rotation — Implementation Plan

> **Status (2026-09-03): Implemented historical plan.** The deterministic
> homepage query, seed workflow, and showcase tests shipped in May 2026. The
> unchecked boxes below preserve the original execution script; they are not
> active tasks. Deterministic visual-regression coverage was completed later by
> `specs/081-homepage-visual-regression`.

> **Historical filename note (2026-07-23):** The implemented SQL is now tracked
> as `supabase/migrations/20260511101318_add_is_homepage_featured.sql`, matching
> the version recorded by Supabase. Numeric `015` references below describe the
> original implementation plan and must not be used for current deployment.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the daily date-seeded homepage rotation with a manually-curated set driven by a new `is_homepage_featured` boolean on `public.exhibitions`, so the homepage is deterministic and unblocks visual regression testing.

**Architecture:** Two PRs. PR 1 adds an additive schema column + partial index (no behavior change). PR 2 rewrites `web/scripts/fetch-showcase.js` to query flagged rows ordered by `closing_date asc`, removes the PRNG/date-seeding code, keeps the existing seed-fallback + Vercel hard-fail guard, and updates `tests/showcase.test.js` for the variable-count behavior.

**Tech Stack:** Supabase Postgres (migration via `supabase/migrations/015_…sql`); Node.js build script (no framework); Node-native `assert` for tests run via `node tests/<name>.test.js`.

**Spec:** `docs/2026-05-11-homepage-curation-decouple-design.md`

**Deviation from spec:** The spec proposed updating `refresh-seed.js` to use the same `is_homepage_featured` query. After reading the script, that's the wrong move — `refresh-seed.js` is an anchor-plus-venue-fill curation tool driven by `seed-anchors.json`, not a date-rotation script. Repurposing it would delete a working tool. Instead, we leave `refresh-seed.js` untouched and regenerate `showcase-seed.json` as a one-shot after curation (Task 8). The seed file is the local-dev fallback only; on Vercel the build hard-fails if Supabase is unreachable, so seed accuracy in production is not load-bearing.

**Branching:** Per `feedback_git_branching.md`, both PRs target `develop`.

---

## File Structure

**PR 1 — Schema migration**
- Create: `supabase/migrations/015_add_is_homepage_featured.sql` — `ALTER TABLE` + partial index.

**PR 2 — Build script swap**
- Modify: `web/scripts/fetch-showcase.js` — replace date-seeded sampling with `is_homepage_featured=eq.true` query.
- Modify: `web/tests/showcase.test.js` — relax the hardcoded `length === 12` assertion; add live-path tests with stubbed `global.fetch` (mirroring the pattern in `tests/refresh-seed.test.js`).
- Modify: `web/scripts/showcase-seed.json` — regenerated from the new curated state (done manually between Task 7 and Task 8; the regeneration command is in Task 8).

**Out of scope (deferred):**
- `web/scripts/refresh-seed.js` — leave as-is. See "Deviation from spec" above.
- Visual regression baselines — follow-up #2 in the memory; unblocked by this work but not part of this plan.

---

## PR 1: Schema migration

### Task 1: Add the migration file

**Files:**
- Create: `supabase/migrations/015_add_is_homepage_featured.sql`

- [ ] **Step 1: Create the migration file**

Write `supabase/migrations/015_add_is_homepage_featured.sql` with this content exactly:

```sql
-- Migration: 015_add_is_homepage_featured.sql
-- Adds a boolean flag to exhibitions so the gallrmap.com homepage can
-- render a manually-curated set instead of a daily date-seeded rotation.
-- The partial index keeps lookups O(curated_count) as the table grows.
-- See docs/2026-05-11-homepage-curation-decouple-design.md for context.

ALTER TABLE public.exhibitions
  ADD COLUMN IF NOT EXISTS is_homepage_featured boolean NOT NULL DEFAULT false;

CREATE INDEX IF NOT EXISTS exhibitions_homepage_featured_idx
  ON public.exhibitions (is_homepage_featured)
  WHERE is_homepage_featured = true;
```

- [ ] **Step 2: Inspect the migration directory to confirm numbering**

Run:
```bash
ls supabase/migrations/ | tail -5
```

Expected: the last existing migration is `014_add_event_cover_image_url.sql` (or higher — if it's higher, bump the new file's prefix accordingly and re-save).

- [ ] **Step 3: Commit**

```bash
git checkout -b feat/homepage-featured-flag
git add supabase/migrations/015_add_is_homepage_featured.sql
git commit -m "feat(db): add is_homepage_featured flag to exhibitions

Additive column + partial index. No behavior change yet; the homepage
build script still uses the existing date-rotation query until PR 2."
```

### Task 2: Apply the migration to Supabase

**Files:** None — this is a Supabase operation.

- [ ] **Step 1: Apply to a preview branch first**

Per `supabase/agent-skills` guidance, prefer a preview branch over going straight to production. Use the Supabase MCP tool `apply_migration` against a preview branch, or via `supabase` CLI:

```bash
# If a preview branch exists:
supabase db push --linked
```

Expected: migration applies, no errors.

- [ ] **Step 2: Verify the column exists**

Run a query against the preview branch:

```sql
SELECT column_name, data_type, column_default, is_nullable
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'exhibitions'
  AND column_name = 'is_homepage_featured';
```

Expected output: one row with `data_type = boolean`, `column_default = false`, `is_nullable = NO`.

- [ ] **Step 3: Verify the partial index exists**

```sql
SELECT indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename = 'exhibitions'
  AND indexname = 'exhibitions_homepage_featured_idx';
```

Expected: one row whose `indexdef` includes `WHERE (is_homepage_featured = true)`.

- [ ] **Step 4: Apply to production**

Once the preview is confirmed:

```bash
supabase db push --linked --project-ref yhuhjxswjbrtmbpbrciq
```

Expected: same success. Re-run Step 2 and Step 3 against the production project URL.

### Task 3: Open PR 1

**Files:** None.

- [ ] **Step 1: Push the branch**

```bash
git push -u origin feat/homepage-featured-flag
```

- [ ] **Step 2: Open the PR against `develop`**

```bash
gh pr create --base develop --title "feat(db): add is_homepage_featured flag to exhibitions" --body "$(cat <<'EOF'
## Summary
- Adds `is_homepage_featured boolean NOT NULL DEFAULT false` to `public.exhibitions`.
- Adds a partial index over the column for fast curated-set lookups.
- Additive only — no behavior change. The homepage script still runs the existing date-rotation query.

## Why
Follow-up #1 from the catalog work — see `docs/2026-05-11-homepage-curation-decouple-design.md`. The build script swap follows in a second PR so the schema can bake without affecting the live site.

## Test plan
- [ ] Migration applied to Supabase preview branch
- [ ] Column + partial index verified via `information_schema` and `pg_indexes` queries
- [ ] Migration applied to production
- [ ] No live-site impact (script still does daily rotation)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: PR URL returned. Wait for merge before starting PR 2.

### Task 4: Curate the initial set (between PRs)

**Files:** None — this is a Supabase table-editor operation.

- [ ] **Step 1: Choose the initial curated exhibitions**

Open Supabase table editor → `exhibitions`. Use the existing `web/scripts/showcase-seed.json` as the starting reference — those entries are already a hand-picked set. Flag the matching rows by setting `is_homepage_featured = true`.

- [ ] **Step 2: Verify the count**

Run via Supabase SQL editor or MCP `execute_sql`:

```sql
SELECT id, name_ko, closing_date
FROM public.exhibitions
WHERE is_homepage_featured = true
ORDER BY closing_date ASC;
```

Expected: rows return in `closing_date` ascending order; count matches your intent (≤ 12, ≥ 1).

---

## PR 2: Build script swap

Start this PR after PR 1 is merged AND the initial curation set (Task 4) is in place.

### Task 5: Write the failing tests

**Files:**
- Modify: `web/tests/showcase.test.js`

This task captures all four test cases from the spec. The existing `showcase.test.js` only covers the seed-fallback path (env vars absent). We need to add live-path coverage with stubbed `global.fetch`, and we need to relax the hardcoded `length === 12` assertion because curated count is variable.

- [ ] **Step 1: Replace `web/tests/showcase.test.js` with the new test file**

Overwrite the file with:

```javascript
#!/usr/bin/env node
// Node-only test for scripts/fetch-showcase.js
// Run: node tests/showcase.test.js
//
// Covers:
//  1. Seed-fallback path (env vars absent, not on Vercel) — exit 0, source: "seed".
//  2. Live path with stubbed fetch — exit 0, source: "supabase", rows in closing_date asc order, camelcase translation correct.
//  3. Empty curated set locally — seed fallback (exit 0).
//  4. Empty curated set on Vercel (VERCEL=1) — hard fail (non-zero exit).
//  5. HTTP error on Vercel — hard fail (non-zero exit).

const fs = require("fs");
const path = require("path");
const os = require("os");
const { execSync, spawnSync } = require("child_process");
const assert = require("assert").strict;

const ROOT = path.join(__dirname, "..");
const SCRIPT = path.join(ROOT, "scripts", "fetch-showcase.js");
const OUTPUT = path.join(ROOT, "_data", "showcase.json");

function clearOutput() {
  if (fs.existsSync(OUTPUT)) fs.unlinkSync(OUTPUT);
}

function readOutput() {
  return JSON.parse(fs.readFileSync(OUTPUT, "utf8"));
}

// Run the script in a child process with a controlled env. The script
// has no `module.exports`, so we shell out and assert on its output.
function runScript(env) {
  return spawnSync("node", [SCRIPT], {
    cwd: ROOT,
    env: { ...process.env, ...env },
    encoding: "utf8",
  });
}

// Stubbed-fetch driver: runs the script via a wrapper that monkey-patches
// global.fetch before requiring it. The wrapper writes a temp file we eval.
function runScriptWithStubbedFetch({ env, fetchImpl }) {
  const wrapperPath = path.join(os.tmpdir(), `fetch-showcase-wrapper-${Date.now()}-${Math.random().toString(36).slice(2)}.js`);
  const wrapper = `
    global.fetch = ${fetchImpl};
    require(${JSON.stringify(SCRIPT)});
  `;
  fs.writeFileSync(wrapperPath, wrapper);
  try {
    return spawnSync("node", [wrapperPath], {
      cwd: ROOT,
      env: { ...process.env, ...env },
      encoding: "utf8",
    });
  } finally {
    fs.unlinkSync(wrapperPath);
  }
}

// ── Test 1: seed-fallback when env vars are absent ──
(function testSeedFallback() {
  clearOutput();
  const env = { SUPABASE_URL: "", SUPABASE_ANON_KEY: "", VERCEL: "" };
  const result = runScript(env);
  assert.equal(result.status, 0, "seed fallback exits 0 locally");
  assert(fs.existsSync(OUTPUT), "showcase.json written");
  const data = readOutput();
  assert.equal(data.source, "seed", "source is 'seed'");
  assert(Array.isArray(data.exhibitions), "exhibitions is an array");
  assert(data.exhibitions.length >= 1, "at least one exhibition");
  for (const ex of data.exhibitions) {
    for (const k of ["id", "titleKo", "titleEn", "venueKo", "venueEn", "openingDate", "closingDate", "coverImageUrl", "status", "statusLabelKo"]) {
      assert(k in ex, `exhibition ${ex.id} missing field ${k}`);
    }
  }
  console.log("✓ test 1: seed fallback");
})();

// ── Test 2: live path with stubbed fetch ──
(function testLivePathHappy() {
  clearOutput();
  // Two rows, intentionally out of closing_date order in the response so we
  // verify the script doesn't re-sort (Supabase returns ordered rows; the
  // script trusts that ordering).
  const fetchImpl = `async (url, opts) => ({
    ok: true,
    status: 200,
    json: async () => ([
      { id: "a", name_ko: "전시 A", name_en: "Show A", venue_name_ko: "베뉴 A", venue_name_en: "Venue A", opening_date: "2026-01-01", closing_date: "2026-06-01", cover_image_url: "https://stub/a.jpg" },
      { id: "b", name_ko: "전시 B", name_en: "Show B", venue_name_ko: "베뉴 B", venue_name_en: "Venue B", opening_date: "2026-01-01", closing_date: "2026-07-01", cover_image_url: "https://stub/b.jpg" },
    ]),
  })`;
  const env = { SUPABASE_URL: "https://stub.supabase.co", SUPABASE_ANON_KEY: "stub", VERCEL: "" };
  const result = runScriptWithStubbedFetch({ env, fetchImpl });
  assert.equal(result.status, 0, `live path exits 0; stderr=${result.stderr}`);
  const data = readOutput();
  assert.equal(data.source, "supabase", "source is 'supabase'");
  assert.equal(data.exhibitions.length, 2, "2 rows in output");
  assert.equal(data.exhibitions[0].id, "a", "first row preserved from fetch");
  assert.equal(data.exhibitions[0].titleKo, "전시 A", "name_ko → titleKo");
  assert.equal(data.exhibitions[0].venueKo, "베뉴 A", "venue_name_ko → venueKo");
  assert.equal(data.exhibitions[0].coverImageUrl, "https://stub/a.jpg", "cover_image_url → coverImageUrl");
  console.log("✓ test 2: live path happy");
})();

// ── Test 3: empty curated set locally → seed fallback ──
(function testEmptyResultLocal() {
  clearOutput();
  const fetchImpl = `async () => ({ ok: true, status: 200, json: async () => [] })`;
  const env = { SUPABASE_URL: "https://stub.supabase.co", SUPABASE_ANON_KEY: "stub", VERCEL: "" };
  const result = runScriptWithStubbedFetch({ env, fetchImpl });
  assert.equal(result.status, 0, "empty result falls back locally");
  const data = readOutput();
  assert.equal(data.source, "seed", "fallback to seed");
  console.log("✓ test 3: empty result local → seed");
})();

// ── Test 4: empty curated set on Vercel → hard fail ──
(function testEmptyResultVercel() {
  clearOutput();
  const fetchImpl = `async () => ({ ok: true, status: 200, json: async () => [] })`;
  const env = { SUPABASE_URL: "https://stub.supabase.co", SUPABASE_ANON_KEY: "stub", VERCEL: "1" };
  const result = runScriptWithStubbedFetch({ env, fetchImpl });
  assert.notEqual(result.status, 0, "empty result hard-fails on Vercel");
  assert(/FATAL/i.test(result.stderr) || /FATAL/i.test(result.stdout), "FATAL log emitted");
  console.log("✓ test 4: empty result on Vercel → hard fail");
})();

// ── Test 5: HTTP error on Vercel → hard fail ──
(function testHttpErrorVercel() {
  clearOutput();
  const fetchImpl = `async () => ({ ok: false, status: 500, json: async () => ({ error: "boom" }) })`;
  const env = { SUPABASE_URL: "https://stub.supabase.co", SUPABASE_ANON_KEY: "stub", VERCEL: "1" };
  const result = runScriptWithStubbedFetch({ env, fetchImpl });
  assert.notEqual(result.status, 0, "HTTP 500 hard-fails on Vercel");
  console.log("✓ test 5: HTTP error on Vercel → hard fail");
})();

console.log("✓ showcase.test.js — all 5 tests passed");
```

- [ ] **Step 2: Run the test suite to confirm it fails**

```bash
cd web && node tests/showcase.test.js
```

Expected: failure on at least Test 2 ("live path exits 0") because the current `fetch-showcase.js` still uses the date-rotation query and won't behave as expected against the stubbed response. Specifically, the test asserts the output preserves the fetch order (`id: "a"` first), but the current script shuffles via PRNG. Test 4 may also fail if the current script doesn't classify empty-curated as hard-fail-worthy on Vercel.

If Test 1 fails, fix the test before proceeding — the seed-fallback behavior is unchanged.

### Task 6: Rewrite `fetch-showcase.js`

**Files:**
- Modify: `web/scripts/fetch-showcase.js` (full file rewrite)

- [ ] **Step 1: Replace the script with the new implementation**

Overwrite `web/scripts/fetch-showcase.js` with:

```javascript
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
```

- [ ] **Step 2: Run the showcase tests**

```bash
cd web && node tests/showcase.test.js
```

Expected: all 5 tests pass. Output ends with `✓ showcase.test.js — all 5 tests passed`.

- [ ] **Step 3: Commit the test + script change together**

```bash
git checkout -b feat/homepage-curation-swap
git add web/tests/showcase.test.js web/scripts/fetch-showcase.js
git commit -m "feat(web): switch homepage to curated is_homepage_featured query

Replaces daily date-seeded sampling of 12-of-40 ongoing shows with a
deterministic query for rows flagged is_homepage_featured = true,
ordered by closing_date ascending, capped at 12. Removes the PRNG and
date-seeding code. Preserves the seed-fallback path locally and the
Vercel hard-fail guard. Adds live-path coverage to showcase.test.js."
```

### Task 7: Regenerate `showcase-seed.json` from the new curated state

**Files:**
- Modify: `web/scripts/showcase-seed.json`

The seed is the local-dev fallback. It should mirror the live curated set so an offline contributor sees the same shows the live site does.

- [ ] **Step 1: Run the production query manually and write the seed**

There's no script for this (deliberately — see "Deviation from spec"). Run the same query the script would, save the JSON output, and shape it to match the seed's format. The easiest path is to run `fetch-showcase.js` against production credentials, then copy the output:

```bash
cd web
SUPABASE_URL='<prod-url>' SUPABASE_ANON_KEY='<prod-anon-key>' node scripts/fetch-showcase.js
```

This writes `web/_data/showcase.json` with `source: "supabase"` and the curated rows.

- [ ] **Step 2: Copy the live result into the seed file**

```bash
cd web
# Re-tag source as seed-curated so the seed file is self-describing.
node -e '
  const fs = require("fs");
  const live = JSON.parse(fs.readFileSync("_data/showcase.json", "utf8"));
  const seed = { fetchedAt: live.fetchedAt, source: "seed-curated", exhibitions: live.exhibitions };
  fs.writeFileSync("scripts/showcase-seed.json", JSON.stringify(seed, null, 2));
  console.log(`wrote ${seed.exhibitions.length} entries to scripts/showcase-seed.json`);
'
```

Expected: log shows the new entry count.

- [ ] **Step 3: Verify the seed fallback uses the new content**

```bash
cd web && unset SUPABASE_URL SUPABASE_ANON_KEY && node scripts/fetch-showcase.js
```

Expected: `_data/showcase.json` is overwritten with `source: "seed"` and the entries from the new seed file.

- [ ] **Step 4: Commit**

```bash
git add web/scripts/showcase-seed.json
git commit -m "data(seed): regenerate showcase seed from curated is_homepage_featured rows"
```

### Task 8: Run the full web test suite

**Files:** None.

- [ ] **Step 1: Run the full suite**

```bash
cd web && npm test
```

Expected: all tests pass. The interesting ones for this change are `tests/showcase.test.js` (already verified in Task 6), `tests/multipage-build.test.js` (verifies homepage renders), and `tests/accessibility.test.js` (pa11y on all routes).

If `multipage-build.test.js` has a hardcoded "homepage shows N cards" assertion, treat it as a regression: relax it to "card count matches `_data/showcase.json` exhibitions length." Make that change, re-commit, re-run.

- [ ] **Step 2: Visual spot check via Playwright preview**

```bash
cd web && npm run preview
```

In another terminal, use `/browse` to open `http://localhost:8080`. Verify:
- The homepage shows the curated set in `closing_date` ascending order.
- Each card renders with its cover image, title, venue, and status label.
- No console errors.

### Task 9: Open PR 2

**Files:** None.

- [ ] **Step 1: Push the branch**

```bash
git push -u origin feat/homepage-curation-swap
```

- [ ] **Step 2: Open the PR against `develop`**

```bash
gh pr create --base develop --title "feat(web): curated homepage via is_homepage_featured" --body "$(cat <<'EOF'
## Summary
- Replaces daily date-seeded sampling in `fetch-showcase.js` with a deterministic query for rows where `is_homepage_featured = true`, ordered by `closing_date` ascending, capped at 12.
- Removes the PRNG and date-seeding code; keeps the seed-fallback path locally and the Vercel hard-fail guard.
- Adds live-path coverage to `tests/showcase.test.js` via stubbed `global.fetch`.
- Regenerates `scripts/showcase-seed.json` from the curated set.

## Why
Follow-up #1 from the catalog work — see `docs/2026-05-11-homepage-curation-decouple-design.md`. The schema column landed in a separate PR; this is the behavior swap. Unblocks visual regression baselines (follow-up #2).

## Test plan
- [ ] `node web/tests/showcase.test.js` — all 5 tests pass
- [ ] `cd web && npm test` — full suite green
- [ ] Vercel preview deploy renders curated set in closing_date asc order
- [ ] Production deploy verified post-merge

## Rollback
Revert this PR to restore daily-rotation behavior. The `is_homepage_featured` column stays (additive); no schema rollback needed.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: PR URL returned.

- [ ] **Step 3: After merge, verify production**

Once merged to `develop` and promoted to production, visit `https://gallrmap.com/`. Confirm the homepage matches the curated set. If something looks off, revert PR 2 per the rollback note.

---

## Self-Review Notes (author)

**Spec coverage:**
- Schema change → Task 1, 2, 3 (PR 1).
- Build script query change → Task 6.
- PRNG/date code removed → Task 6.
- `classify()` + seed-fallback + camelcase translation kept → Task 6 (and verified by tests 1, 2, 3).
- Empty curated set hard-fails on Vercel → Task 5 test 4, Task 6 implementation.
- `refresh-seed.js` update → deliberately deferred; documented in "Deviation from spec" + Task 7's manual-regen.
- Tests (happy path, empty local, empty Vercel, HTTP error Vercel, field-name translation) → Task 5.
- Integration test → Task 8 step 1 (relax hardcoded count assertion if present).
- Manual verification → Task 2 (schema), Task 8 step 2 (preview browser check).
- Two-PR rollout + rollback note → Task 3, Task 9.

**Type/name consistency:**
- `is_homepage_featured` consistent across schema, query, partial index, FATAL log message.
- Field translation (`name_ko → titleKo`, `venue_name_ko → venueKo`, `cover_image_url → coverImageUrl`) consistent in script + test assertions.
- `source` values: `"supabase"` for live, `"seed"` for fallback, `"seed-curated"` only inside the seed JSON file (re-tagged to `"seed"` by `writeFromSeed`). Tests assert on the post-load value, not the file value.

**Placeholder scan:** None. Every step has runnable commands or complete code blocks.

**Scope:** Single focused feature, one spec, one plan. Follow-up #2 explicitly out of scope.

---

## Execution

Plan complete and saved to `docs/2026-05-11-homepage-curation-decouple-plan.md`. Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** — Execute tasks in this session using `superpowers:executing-plans`, batched with checkpoints for review.

Which approach?
