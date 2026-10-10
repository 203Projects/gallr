# Implementation plan

Add revision-checked, idempotent owner withdrawal and discard commands using existing membership and command-receipt helpers. Lock the latest review round before the exhibition/version, matching staff decisions. Terminal accepted rounds block owner changes. Discard soft-hides the retained draft after withdrawing any open round. Increment the draft revision to invalidate stale clients and preserve review snapshots.

Expose commands through OwnerRepository and validated Supabase adapters. Add bilingual withdrawal and discard actions with explicit discard confirmation. Keep published row hiding unchanged.

Companion PR #275 implements the owner publication email. Keep this branch focused on submission controls and avoid duplicate publication triggers or messages. Preserve existing decision email contracts.

## Constitution Check
PASS: specification precedes implementation; failing behavior tests precede tested paths; minimal reuse of current workflow; stories have independent acceptance tests; structured audit/outbox evidence; Principle VI: web and SQL are independent artifacts, no KMP logic changes. No complexity exceptions.

## Verification
Focused then full Gallery typecheck/tests/build; migration lineage; clean disposable local replay, pgTAP, lint/advisors and concurrency checks from database CI. Browser verification where feasible. No live provider calls.

## Verification results
- Node 22.23.1: Gallery typecheck, 128 Vitest tests, production build passed.
- Publication email delivery moved to companion PR #275; this branch does not change Edge Function code.
- Migration lineage: 88 files and all 16 validator regressions passed.
- Disposable local database: final clean migration replay and migration history check passed; all 49 pgTAP suites / 1,612 assertions passed, including the open-round ordering regression. Database lint and security advisors reported no findings.
- All seven existing database concurrency regressions and the new owner-versus-staff race in both winning orders passed; real libpq routing regression passed. Local Docker required an explicit loopback port binding override; verified 127.0.0.1:57322 before disposal.
- Browser QA at 1280×900 and 390×844: submitted fields read-only → withdraw → edit/save → discard confirmation/cancel → confirmed removal. Korean mobile dialog fit without horizontal overflow. Page identity, meaningful content, no framework overlay, no page errors, screenshots and interactions passed. Used agent-browser for initial inspection and Playwright for final interaction assertions; the Browser plugin was absent. The temporary harness supplies a fake OwnerRepository; database/RPC behavior is verified separately above.
- Companion compatibility: PR #275 publication migration applied after this migration on the same disposable database; its 13 pgTAP assertions passed. No duplicate publication event exists in this branch.
- Live Supabase Auth, remote deployment and real email inbox delivery were not exercised. Rollout order for this branch: submission-control migration, then Gallery client. Coordinate migration ordering with PR #275; deploy its compatible outbox receiver before its publication-email migration. Stage the combined journey before production promotion.
