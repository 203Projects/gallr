# gallr web — Agent Guide

The gallr **presentation/companion website**: an **Eleventy 3.x** static site (no runtime JS shipped
beyond progressive enhancement). It is independent of the KMP app — the root `CLAUDE.md` KMP rules do
**not** apply here. Self-hosted Inter font; pa11y + Playwright for acceptance.

## Commands (run from `web/`)

```bash
npm run dev      # eleventy --serve (live reload)
npm run build    # copy-fonts → fetch-showcase → fetch-exhibitions → eleventy  (writes dist/)
npm run preview  # build, then serve dist/ on :8080
npm test         # Node unit tests + build + Playwright (chromium / -js / -mobile / -catalog projects)
```

## Data flow

- The build fetches exhibitions + showcase data **at build time** into `_data/` (Eleventy global data),
  via `scripts/fetch-exhibitions.js` and `scripts/fetch-showcase.js`.
- **Source is Supabase** (same `exhibitions` table as the app: `name_ko`/`name_en`, `venue_name_ko`,
  dates, `cover_image_url`, …). With Supabase env vars missing it falls back to bundled seed JSON
  (`exhibitions-seed.json`).
- **Production builds (`VERCEL=1`) fail non-zero** if Supabase env is missing or the fetch fails;
  local/dev/CI silently use the seed. Deployed via Vercel (`vercel.json`).

## Conventions & gotchas

- **Eleventy expects exact file paths.** Build scripts write to `_data/exhibitions.json` /
  `_data/showcase.json`; global data loading depends on these. Renaming them breaks the build silently.
- **Playwright fixtures swap the seed.** `tests/global-setup.ts` replaces `exhibitions-seed.json` with
  fixture data and rebuilds `dist/` before tests; teardown restores it. Don't hand-edit `dist/` —
  it's generated.
- **Bilingual data:** the fetch selects both `_ko` and `_en`; keep seed JSON and schema in sync or the
  fetch/schema mismatch surfaces at build time.
- **Sitemap:** paginated exhibition pages need data-sourced `<url>` entries (not `collections.all`).
- Keep the visual language aligned with the app's `DESIGN.md` aesthetic (monochrome, sharp corners,
  single `#FF5400` accent), adapted to web.
