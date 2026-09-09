# gallr Gallery

This guide applies to `gallery/`. Read the root [`CLAUDE.md`](../CLAUDE.md) first. Read
[`DESIGN.md`](../DESIGN.md) before every visual or interaction change, and use
[`README.md`](./README.md) plus the
[`gallery-owner release runbook`](../docs/gallery-owner-release-runbook.md) for configuration and
release gates.

Gallery uses Node.js 22.23.1 from the root `.node-version` file with Vite, React, and TypeScript. It shares Supabase Auth with other
products but must operate only through owner-scoped contracts.

## Commands

Run from `gallery/`:

```bash
npm ci
npm run dev
npm run typecheck
npm test
npm run build
```

Before handoff, run typecheck, tests, and build. Use focused Vitest files while iterating.

## Architecture and data safety

- Keep domain types independent of React and Supabase. Components depend on `OwnerAuth` and
  `OwnerRepository`; only adapters may know RPC, Storage, function, or wire-response details.
- Validate unknown server responses at the adapter boundary. Preserve owner scope, membership/status
  checks, revision guards, stable request IDs, bounded pagination, and paired-coordinate invariants.
- Missing Supabase configuration fails closed. There is no production fixture mode and no implicit
  fallback to sample data.
- Browser code may receive only a publishable Supabase key. Never put service-role, Stripe, NAVER
  secret, webhook, or other server credentials in `VITE_` variables.
- Keep `VITE_PUBLIC_SITE_URL` on the matching visitor environment. Leave
  `VITE_LAUNCH_KIT_ENABLED=false` until the free Launch Kit beta and its R3 release gates are
  explicitly activated. Keep `VITE_OWNER_PROMOTION_ENABLED=false`; paid R4 promotion is outside
  the active roadmap, and retained compatibility guards do not authorize activation.
- Schema/RPC changes belong in the root migration lineage and must ship atomically with adapter,
  response validation, and tests. Follow the root database verification contract.

## Code style and tests

- Prefer small typed components that render state and emit explicit events. Keep persistence and
  workflow orchestration behind the owner repository/auth boundaries.
- Test access denial, invalid response shapes, stale revisions, retry identity, upload limits,
  pagination boundaries, and fail-closed configuration in addition to successful rendering.
- Co-locate `*.test.ts` / `*.test.tsx` with the behavior under test. Preserve contract-level tests
  when changing Supabase adapters or mutations.

## Release boundary

A passing build does not authorize deployment, DNS/Auth redirect activation, owner signup, payments,
or production database changes. Follow the release runbook and keep staging/production credentials in
separate 1Password items.
