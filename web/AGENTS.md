# gallr Web

This guide applies to `web/`. Read the root [`CLAUDE.md`](../CLAUDE.md) first and read
[`DESIGN.md`](../DESIGN.md) before every visual or interaction change. [`README.md`](./README.md)
owns the full environment, data-flow, test, and deployment reference.

The public companion site is an Eleventy 3.x static build with progressive enhancement. It is
independent of the KMP modules but shares product data, design language, and release gates.

## Commands

Use Node.js 22.23.1 from the root `.node-version` file and run from `web/`:

```bash
npm ci
npm run dev
npm run build
npm test
```

`npm test` runs Node contract tests, a production build, accessibility checks, and all Playwright
projects. Use focused Node or Playwright tests while iterating, then run the full command before
handoff for data, routing, interaction, accessibility, or build-pipeline changes.

## Build-time data contract

- `scripts/fetch-exhibitions.js` and `scripts/fetch-showcase.js` write generated Eleventy data under
  `_data/`; never hand-edit generated `_data/*.json` or `dist/`.
- `GALLR_EXHIBITION_SOURCE` selects exactly one reviewed reader pair: `legacy` or `canonical-v2`.
  Canonical failures must not fall back to legacy. Preserve keyset pagination and each source's
  required count/checksum verification through its matching integrity RPC.
- Missing live configuration may use committed seeds only for local/offline work. `VERCEL=1` or
  `GALLR_REQUIRE_LIVE_DATA=1` makes every seed fallback fatal; never weaken that production guard.
- Keep exhibition/showcase seeds and bilingual schemas synchronized with reader changes. Generated
  detail routes and sitemap entries must come from the verified dataset.

## Browser and trust boundaries

- Static browser code may receive a publishable Supabase key only. Reject secret/service-role keys;
  never embed server credentials or credentials inside endpoint override URLs.
- Impact, RSVP, promotion, and owner-workspace integrations stay behind their reviewed
  `GALLR_ENABLE_*` or environment-specific gates. An endpoint override alone must not activate a
  release slice.
- Keep data fetching and validation in build scripts or small pure modules. Templates render prepared
  data; progressive browser scripts enhance behavior and must retain usable no-JavaScript output.
- Preserve bilingual KO/EN behavior, semantic HTML, keyboard access, reduced motion, and the
  data-sourced sitemap when changing presentation.

## Release boundary

A successful build or Preview does not authorize production environment changes, reader-source
cutover, feature-flag activation, DNS changes, or deployment-hook changes. Follow the cutover/runbook
gates linked from `README.md`, and keep staging/production values separate in 1Password.
