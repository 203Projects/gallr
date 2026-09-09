# Decouple gallr homepage from daily live rotation

**Date:** 2026-05-11
**Status:** Approved, ready for implementation plan
**Context:** Follow-up #1 from PR #51 — see `project_gallr_web_followups` memory.

## Problem

`web/scripts/fetch-showcase.js` currently fetches up to 40 currently-running exhibitions from Supabase and samples 12 of them using a PRNG seeded by today's UTC date. The set rotates daily. This means:

- Press shots, OG-image snapshots, and visual regression baselines drift with the seed.
- A screenshot taken Monday won't reproduce on Tuesday.
- Visual regression testing (follow-up #2) is blocked because the input data isn't stable.

We want a **stable, manually-curated** homepage set that only changes when we decide it should.

## Goals

1. The homepage set is deterministic across builds — no date dependency.
2. Curation is a manual editorial action, not an algorithm.
3. The build script's existing resilience (seed fallback locally, hard-fail on Vercel) is preserved.
4. Schema change is minimal and reversible.

## Non-goals

- An admin UI for curation. Supabase's table editor is sufficient.
- A separate `homepage_showcase` table. The existing `exhibitions` table already has `is_featured` / `is_editors_pick` columns; one more boolean fits the pattern.
- Auto-fill logic to pad the homepage to a target count. If the curated set is 8 rows, the homepage shows 8 rows. Editorial intent over quota.
- Daily/scheduled rotation. The whole point is determinism.

## Design

### Schema change

Add one column to `public.exhibitions`:

```sql
ALTER TABLE public.exhibitions
  ADD COLUMN is_homepage_featured boolean NOT NULL DEFAULT false;

CREATE INDEX exhibitions_homepage_featured_idx
  ON public.exhibitions (is_homepage_featured)
  WHERE is_homepage_featured = true;
```

Existing rows default to `false`, so the column is dormant until something flags `true`. The partial index keeps lookups cheap as the table grows — only flagged rows are indexed.

This mirrors the existing `is_featured` and `is_editors_pick` columns. No new table.

### Build script changes (`web/scripts/fetch-showcase.js`)

Same file path, same output (`web/_data/showcase.json`), same env-var contract, same seed-fallback semantics. Only the query and post-fetch behavior change.

**New query:**

```js
const endpoint =
  `${url}/rest/v1/exhibitions` +
  `?select=id,name_ko,name_en,venue_name_ko,venue_name_en,opening_date,closing_date,cover_image_url` +
  `&is_homepage_featured=eq.true` +
  `&order=closing_date.asc` +
  `&limit=12`;
```

`limit=12` is an upper cap, not a target. If fewer rows are flagged, fewer cards render.

**Code removed:**
- `seededRng`, `shuffle`, `todayIso`, Mulberry32 PRNG — no random sampling, no date seeding.
- `SAMPLE_SIZE`, `FETCH_LIMIT` constants.
- `CLOSING_SOON_DAYS` / `OPENING_SOON_DAYS` stay only if `classify()` still needs them.

**Code kept:**
- `classify()` for `closing-soon` / `opening-soon` / `ongoing` status labels — the homepage cards still display these badges.
- Seed-fallback path with `IS_PRODUCTION_BUILD` (`VERCEL=1`) hard-fail guard.
- Camelcase field-name translation (`name_ko → titleKo`, etc.).

**New failure case:**
- Empty curated set (Supabase returns `[]`) is treated the same as any other "empty result" condition: seed-fallback locally, hard-fail on Vercel. A homepage with zero shows is worse than a failed deploy.

### `refresh-seed.js`

Same query treatment so the seed file mirrors curated state, not daily-rotation state. The seed is the local-dev fallback; if it diverges from the curated query it ships outdated shows whenever a contributor builds offline.

### Ordering

Flagged rows render ordered by `closing_date` ascending. This is deterministic (closing dates don't drift), editorially meaningful (the homepage naturally surfaces what's about to end), and costs no extra schema work.

If a pinned-to-top need ever arises, add a `homepage_order` integer column then. Not now.

## Failure modes

| Scenario | Local dev | Production (Vercel) |
| --- | --- | --- |
| `SUPABASE_URL` / `SUPABASE_ANON_KEY` absent | Seed fallback | Hard-fail with FATAL log |
| HTTP error from Supabase | Seed fallback | Hard-fail with FATAL log |
| Network error | Seed fallback | Hard-fail with FATAL log |
| Supabase returns empty `[]` | Seed fallback | Hard-fail with FATAL log |
| Zero rows have `is_homepage_featured = true` | Seed fallback (same as empty) | Hard-fail with FATAL log |

The production hard-fail behavior is unchanged from the current script — it's the same `IS_PRODUCTION_BUILD` guard, just covering one more case (empty curated set instead of empty live-shows set).

## Testing strategy

Per `feedback_tdd.md`, tests come first.

### Unit tests for `web/scripts/fetch-showcase.js`

Match the existing test pattern in `web/scripts/` (check for a `__tests__/` directory or sibling `.test.js` file; create one if neither exists).

Cases:
- **Happy path:** Supabase returns 5 flagged rows → output has 5 exhibitions ordered by `closing_date` ascending, `source: "supabase"`.
- **Empty result on Vercel:** Hard-fails with non-zero exit and FATAL log.
- **Empty result locally:** Seed fallback.
- **HTTP error locally:** Seed fallback. **On Vercel:** hard-fail.
- **Env vars absent locally:** Seed fallback. **On Vercel:** hard-fail.
- **Field-name translation:** `name_ko → titleKo`, `venue_name_ko → venueKo` — regression guard against the schema-name trap.

### Integration test

If `web/tests/` already has a Playwright/Eleventy build-output test (from the multi-page catalog PRs), extend it:
- Homepage renders the curated set from `_data/showcase.json`.
- Card count matches the data file's `exhibitions.length` (not hardcoded to 12).

No empty-state UI test needed — production never produces an empty page (build hard-fails first).

### Manual verification

1. Apply migration to a Supabase preview branch first (per supabase plugin guidance).
2. Flag 3–4 rows via Supabase table editor.
3. Run `node web/scripts/fetch-showcase.js` locally; verify `web/_data/showcase.json` reflects them in `closing_date` ascending order.
4. Run `pa11y` audit (if the catalog branch's accessibility suite still runs in CI) to confirm no regression with variable card counts.

## Rollout

Two PRs, in order.

**PR 1 — Schema migration (additive, reversible, no behavior change).**
- Supabase migration file with the `ALTER TABLE` + index.
- Merge to `develop`; apply to production Supabase project.
- Live site is unchanged at this point — script still does the old daily-rotation thing.

**Curation step (between the PRs, in Supabase table editor).**
- Flag the same exhibitions currently in `showcase-seed.json` as a starting set — that file is already a hand-picked seed.
- Verify: `select count(*) from exhibitions where is_homepage_featured = true;` returns the expected count.

**PR 2 — Build script swap (the actual behavior change).**
- Update `fetch-showcase.js` query, remove PRNG code, keep `classify()` + seed-fallback + camelcase translation.
- Update `refresh-seed.js` to use the same query.
- Regenerate `showcase-seed.json` against the new query so the fallback matches the live path.
- All unit + integration tests pass.
- Merge to `develop` → Vercel preview deploy → confirm curated set renders → promote to production.

**Rollback:** Revert PR 2 to restore daily-rotation behavior. The schema column stays (unused columns are cheap); no schema rollback needed.

**Why two PRs:** Separating the additive schema change from the behavior swap means the migration can land and bake without touching the live site, and the script-diff review isn't drowned out by a migration in the same PR.

## Unblocks

Follow-up #2 (visual regression strategy) — completed by
`specs/081-homepage-visual-regression`, which fixes the date, replaces remote
artwork with deterministic test pixels, and compares desktop/mobile hero plus
curated-grid baselines in CI.
