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
npm run test         # Build + Node tests + pa11y + Playwright (53 tests)
npm run refresh-seed # Manually rebuild scripts/showcase-seed.json from real Supabase data
```

## Environment variables

| Variable | Required | Used by |
|---|---|---|
| `SUPABASE_URL` | Yes (production); optional (dev) | `scripts/fetch-showcase.js`, `scripts/refresh-seed.js` |
| `SUPABASE_ANON_KEY` | Yes (production); optional (dev) | same |

**Production guard:** when `VERCEL=1` or `CI=true`, `fetch-showcase.js` errors out hard if it cannot fetch live exhibitions (missing env vars, HTTP failure, empty result set). Without the guard, a misconfigured production build would silently ship the SVG placeholder seed to real visitors.

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
5. **Playwright** — 53 tests across 3 projects:
   - `chromium` (smoke, JS off): 9 tests
   - `chromium-js` (editorial, JS on): 25 tests
   - `chromium-mobile` (Pixel 5, redesign guards): 19 tests covering type-scale, section rhythm, CTA pair, Now Showing grid, image fallback

## Deployment

Vercel auto-deploys on push to `develop` (preview) and `main` (production). The build command is `npm run build`; output directory is `dist/`. Configure once in Vercel → Project Settings.

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
