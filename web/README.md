# gallr web

Static public companion site and exhibition catalogue for [gallr](https://gallrmap.com). Built with
Eleventy and deployed on Vercel.

## Setup

Use Node.js 22.23.1 as declared by the root `.node-version` file.

```bash
cd web
npm ci
```

Inject environment-specific values from 1Password when live data is required. Do not use a plaintext
environment file as persistent credential storage; only publishable client configuration belongs in
the static build.

## Daily commands

```bash
npm run dev          # Eleventy dev server with live reload
npm run build        # Production build → dist/
npm run preview      # Build + serve dist/ at http://localhost:8080
npm run test         # Build + Node tests + pa11y + all Playwright projects
npm run refresh-seed # Manually rebuild scripts/showcase-seed.json from real Supabase data
npm run refresh-exhibitions-seed # Rebuild the offline catalog seed
```

## Environment variables

| Variable | Required | Used by |
|---|---|---|
| `SUPABASE_URL` | Yes (production); optional (dev) | Catalog, showcase, and seed readers |
| `SUPABASE_PUBLISHABLE_KEY` | Yes (production); optional (dev) | Public key for catalog, showcase, and seed readers |
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

Public build clients reject `sb_secret_*` and legacy `service_role` keys. Configure an opaque
`sb_publishable_*` key through `SUPABASE_PUBLISHABLE_KEY`; it is sent only in the `apikey` header.
Legacy anon JWTs and their old variable name remain temporary compatibility fallbacks until the
repository TODO's deployment and installed-client gates are complete.

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

In Vercel: **Project Settings → Environment Variables** → add both vars to the **Production** environment (and **Preview** if you want PR deploys to use live data too). Configure `SUPABASE_URL` and `SUPABASE_ANON_KEY` as a matched pair from the environment's dedicated 1Password item; never mix a Preview/rehearsal value with a Production value.

## How the homepage data is assembled

- `scripts/fetch-showcase.js` runs during every build and writes `_data/showcase.json`.
- With live configuration, it reads the configured `legacy` or `canonical-v2` source and selects the
  explicitly curated `is_homepage_featured` rows that are open on the current Seoul calendar date.
- Catalogue and showcase status labels use `Asia/Seoul`, so a new exhibition becomes current at
  midnight KST even when the build runtime is still on the previous UTC date.
- Without live configuration, local/offline builds use `scripts/showcase-seed.json`.
- Production/live-evidence builds fail instead of shipping a seed fallback.

### Refreshing the seed

The seeds are offline fallback datasets. With live values injected from 1Password, refresh the
homepage showcase and catalog seeds from the currently selected Supabase reader:

```bash
npm run refresh-seed
npm run refresh-exhibitions-seed
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

Fresh-data rebuilds are also triggered daily at **09:00 KST** by `.github/workflows/rebuild-web.yml`, which POSTs to a Vercel Deploy Hook. Configure the private hook URL as the GitHub Actions secret `VERCEL_DEPLOY_HOOK_URL`. Exhibition publish/archive/restore events coalesce into one durable `public_site.rebuild_requested` event after a 30-second quiet window; `outbox-delivery` calls the same hook for that request, so approved changes reach `gallrmap.com` without waiting for the daily cron or creating one build per edit.

### Do not add an `ignoreCommand` to this project

This site bakes Supabase catalogue data into static pages at build time, so the
two rebuild triggers that matter most — the publish Deploy Hook and the daily
cron — carry **no source change at all**. A path-diff ignore command
(`git diff --quiet HEAD^ HEAD -- .`) exits `0` for exactly those triggers, and on
Vercel `0` means *skip the build*. The result is a silent failure: an owner
publishes, admin approves, the hook fires, no build runs, and the new exhibition
detail page is never generated — so the gallery portal's "view public page" link
serves a Vercel `404: NOT_FOUND`. Vercel exposes no environment variable that
identifies a Deploy Hook deployment, so the ignore command cannot special-case it.

`web/tests/rebuild-trigger-config.test.js` enforces this. The asymmetry is
deliberate: a redundant rebuild costs one cheap Eleventy build, while a wrongly
skipped rebuild is a customer-visible 404 on a published exhibition. The
`admin/` and `gallery/` portals have no build-time catalogue data and correctly
keep their path-diff ignore commands.

## Layout

```
web/
├── _data/              # Build-time data (gitignored: showcase.json regenerated each build)
├── _includes/          # Eleventy partials (hero, features, now-showing, downloads, about, base)
├── public/             # Static assets (fonts, logos, favicon, SVG placeholders)
├── scripts/            # Build, reader, and seed-refresh scripts
├── styles/             # tokens.css + main.css
├── tests/              # Playwright + Node tests
├── coming-soon/        # /coming-soon route
├── privacy.html        # /privacy route
├── index.html          # Homepage
├── package.json
├── playwright.config.ts
└── .env.local.example  # Variable-name reference; do not persist credentials here
```
