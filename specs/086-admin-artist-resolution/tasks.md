# Tasks

- [x] Write failing Admin tests for prefill/focus, prompt, link/create replacement,
  cancellation, target reorder/removal, stale searches, bilingual copy, retries.
- [x] Implement visible resolution feedback and prefilled creation/search.
- [x] Write failing offline backfill tests, implement review/SQL preparation,
  document staff review and external execution boundary.
- [x] Run Admin gates, script/lineage checks, and rendered fixture verification.
  Admin typecheck, 275 tests and build passed; 8 offline backfill tests, 5
  disposable PostgreSQL tests and 16 migration-lineage tests passed. Playwright
  verified local fixtures at 1440×1000 and 390×844, including Resolve, Cancel,
  Create and Link. SQL CLI preparation/private-file/no-overwrite smoke passed.
- [x] Close review findings: cap search at 100 Unicode characters while keeping
  full bilingual names, wrap long names on mobile, and run offline/canonical
  backfill checks in Database CI. All 90 migrations replayed on a disposable
  local database; canonical backfill/search-RPC/audit/retry/denial checks and
  all 60 existing art-metadata pgTAP assertions passed with rolled-back fixtures.
- [ ] External rollout: obtain environment-specific published-credit exports,
  complete staff identity review, rehearse against the deployed staging schema,
  and execute separately authorized backfills. No remote data was changed here.
