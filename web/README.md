# gallr web

Static marketing site for [gallr](https://gallrmap.com). Built with Eleventy, deployed on Vercel.

## Setup

```bash
cd web
npm install
cp .env.local.example .env.local        # fill in real values from Supabase
set -a && source .env.local && set +a    # bash/zsh — exports the vars to child processes
```

## Daily commands

```bash
npm run dev          # Eleventy dev server with live reload
npm run build        # Production build → dist/
npm run preview      # Build + serve dist/ at http://localhost:8080
npm run test         # Build + Node tests + pa11y + all Playwright projects
npm run refresh-seed # Manually rebuild scripts/showcase-seed.json from real Supabase data
```

## Environment variables

| Variable | Required | Used by |
|---|---|---|
| `SUPABASE_URL` | Yes (production); optional (dev) | `scripts/fetch-showcase.js`, `scripts/refresh-seed.js` |
| `SUPABASE_ANON_KEY` | Yes (production); optional (dev) | Same; accepts a publishable key or legacy anon key |
| `GALLR_EXHIBITION_SOURCE` | No; defaults to `legacy` | All exhibition catalog, showcase, and seed readers |
| `GALLR_REQUIRE_LIVE_DATA` | Set to `1` for staging/cutover evidence jobs | Makes any seed fallback fatal; Vercel enables the same behavior automatically |
| `GALLR_ENABLE_IMPACT` | No; set to `1` or `true` only for R2+ | Enables public impact recording |
| `GALLR_ENABLE_RSVP` | No; set to `1` or `true` only for R3+ | Enables the public RSVP endpoint |
| `GALLR_ENABLE_PROMOTION` | No; set to `1` or `true` only for R4 | Enables the labelled local-promotion surface |
| `GALLR_IMPACT_ENDPOINT` | No | Overrides the derived `record-exhibition-view` function URL |
| `GALLR_RSVP_ENDPOINT` | No | Overrides the derived `launch-rsvp` function URL |
| `GALLR_PROMOTION_ENDPOINT` | No | Overrides the derived `promoted-nearby` function URL |
| `GALLR_GALLERY_WORKSPACE_URL` | No; defaults to `https://gallery.gallrmap.com/` | Overrides public owner-workspace links for an isolated Preview branch |

`GALLR_EXHIBITION_SOURCE` accepts only `legacy` or `canonical-v2`. Each value
selects one fixed table/integrity-RPC pair; invalid values fail configuration and
canonical failures never fall back silently to the legacy endpoint. Keep
production on `legacy` until the V2 migration, backfill, reconciliation, and
canary gates in `../docs/public-exhibition-catalog-cutover-runbook.md` pass.

Public build clients reject `sb_secret_*` and legacy `service_role` keys.
Opaque `sb_publishable_*` keys are sent only in the `apikey` header; legacy anon
JWTs retain their compatible bearer header.

Each later release slice requires its matching `GALLR_ENABLE_*` flag. Once a
slice is enabled, Eleventy derives the matching `/functions/v1/...` URL from
`SUPABASE_URL`; the endpoint override is normally unnecessary. Overrides exist
for an explicit staging proxy or isolated cutover and must remain public
endpoint URLs without embedded credentials. An endpoint override alone never
activates a release slice.

Use `GALLR_GALLERY_WORKSPACE_URL` as a branch-scoped Preview variable when the
public site and Gallery workspace need to be rehearsed together before custom
domain cutover. Do not set it globally in Production; the committed default is
the production owner-workspace domain.

**Live-data guard:** when `VERCEL=1` or `GALLR_REQUIRE_LIVE_DATA=1`, the catalog and showcase fetchers error out if live data cannot be verified (missing env vars, HTTP/integrity failure, or an invalid empty showcase). Offline CI jobs may continue using seeds; staging and cutover evidence jobs must set the explicit guard.

In Vercel: **Project Settings → Environment Variables** → add both vars to the **Production** environment (and **Preview** if you want PR deploys to use live data too).

## How the homepage data is assembled

```
                ┌──────────────────────────────┐
                │  scripts/fetch-showcase.js   │  build-time
                │  (runs each `npm run build`) │
                └──────────────┬───────────────┘
                               │
            ┌──────────────────┴──────────────────┐
            │                                     │
   env vars set → live fetch          env vars absent → seed fallback
   (12 currently-running shows,         (scripts/showcase-seed.json,
    sampled deterministically by         12 hand-curated shows)
    today's UTC date)
            │                                     │
            └──────────────────┬──────────────────┘
                               │
                               ▼
                    _data/showcase.json
                               │
                               ▼
                Hero marquee (slice(0, 8))
                Features    (indexes 8-10)
                Now Showing (slice(0, 8))
```

The hero marquee and Now Showing intentionally share the first 8 exhibitions — they're two presentations of the same "live" set. Features pulls from indexes 8-10 so the same 3 exhibitions don't appear in 3 different places on the page.

### Refreshing the seed

The seed is the offline / fallback dataset. Run `npm run refresh-seed` (with env vars exported) to rebuild it from current Supabase data:

```bash
set -a && source .env.local && set +a
npm run refresh-seed
```

The script reads `scripts/seed-anchors.json` (your hand-picked exhibition IDs/titles + venue allowlist), fetches matching rows from Supabase, fills remaining slots from major venues, and writes `scripts/showcase-seed.json`. Errors loudly if it can't assemble enough rows.

## Testing

```bash
npm run test
```

Runs:
1. **Build** — Eleventy + fetch-showcase
2. **Node assertions** — `tests/showcase.test.js` (data shape)
3. **pa11y** — WCAG 2.1 AA accessibility audit on `dist/index.html`
4. **`tests/refresh-seed.test.js`** — unit tests for the curated-seed builder
5. **Playwright** — browser acceptance across four projects:
   - `chromium` (smoke, JS off)
   - `chromium-js` (editorial, JS on)
   - `chromium-mobile` (responsive navigation and layout guards)
   - `chromium-catalog` (catalogue, map, detail, filters, and RSVP)

## Deployment

Vercel auto-deploys on push to `develop` (preview) and `main` (production). The build command is `npm run build`; output directory is `dist/`. Configure once in Vercel → Project Settings.

Fresh-data rebuilds are also triggered daily at **09:00 KST** by `.github/workflows/rebuild-web.yml`, which POSTs to a Vercel Deploy Hook. Configure the private hook URL as the GitHub Actions secret `VERCEL_DEPLOY_HOOK_URL`.

## Layout

```
web/
├── _data/              # Build-time data (gitignored: showcase.json regenerated each build)
├── _includes/          # Eleventy partials (hero, features, now-showing, downloads, about, base)
├── public/             # Static assets (fonts, logos, favicon, SVG placeholders)
├── scripts/            # Build scripts (copy-fonts, fetch-showcase, refresh-seed)
├── styles/             # tokens.css + main.css
├── tests/              # Playwright + Node tests
├── coming-soon/        # /coming-soon route
├── privacy.html        # /privacy route
├── index.html          # Homepage
├── package.json
├── playwright.config.ts
└── .env.local.example  # Copy to .env.local
```
