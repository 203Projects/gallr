# gallr Admin

This guide applies to `admin/`. Read the root [`CLAUDE.md`](../CLAUDE.md) first. Read
[`DESIGN.md`](../DESIGN.md) before every visual or interaction change, and use
[`README.md`](./README.md) for the full repository contract, environment setup, and cutover gates.

Admin uses Node.js 22.23.1 from the root `.node-version` file with Vite, React, and TypeScript. It is a
separate deployable from the public site and gallery-owner workspace.

## Commands

Run from `admin/`:

```bash
npm ci
npm run dev
npm run typecheck
npm test
npm run build
```

Before handoff, run typecheck, tests, and build. Use focused Vitest files while iterating, but retain
the full gate for changes to repositories, lifecycle operations, media, auth, or deployment config.

## Architecture and data safety

- Keep UI and workflow code dependent on `AdminExhibitionRepository` and service interfaces. Only
  Supabase adapters may know RPC, Storage, or wire-response details; validate unknown responses at
  that boundary and return domain types.
- Browser code may use only staff-scoped RPCs and a publishable Supabase key. Never query or mutate
  canonical tables directly, and never place service-role, worker, Stripe, NAVER secret, or other
  server credentials in a `VITE_` variable.
- Missing Supabase configuration fails closed. In-memory fixtures are allowed only in tests or an
  explicit local development session with `VITE_ADMIN_FIXTURE_MODE=true`; production must never
  fall back to fixtures.
- Preserve immutable published versions, exact working-version/revision guards, and retained request
  IDs for ambiguous lifecycle retries. Do not weaken conflict handling or make destructive commands
  retry with a new identity.
- Media upload is not publication. Preserve signed upload, server validation, immutable delivery,
  processing status, and the rule that attached media must be published before an exhibition.
- Schema/RPC changes belong in the root migration lineage and must ship atomically with adapter,
  validation, and test updates. Follow the root database verification contract.

## Code style and tests

- Keep domain types independent of React and Supabase. Prefer small components with typed props and
  explicit events; keep server orchestration in the repository/service boundary.
- Keep state transitions explicit. Test revision conflicts, authorization denial, malformed RPC
  responses, idempotent retries, and fail-closed configuration—not only successful rendering.
- Co-locate `*.test.ts` / `*.test.tsx` with the behavior under test. Do not replace repository tests
  with component mocks when the wire contract or mutation semantics changed.

## Release boundary

A passing build does not authorize production deployment, DNS/Auth changes, database rollout, or a
reader cutover. Use Preview with staging credentials and follow the cutover/runbook gates linked from
`README.md`. Production and staging credentials must remain separate in 1Password.
