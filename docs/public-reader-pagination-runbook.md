# Public exhibition reader pagination runbook

This document remains the rollback contract for legacy
`public.exhibitions`. The canonical `public.exhibition_catalog_v2` reader uses
the same keyset/count/ID rules plus a catalog content checksum; its activation
steps are in [the V2 cutover runbook](public-exhibition-catalog-cutover-runbook.md)
and ADR-0003.

This runbook verifies and rolls out the complete-collection reader introduced
by [ADR-0002](adr/0002-complete-reader-keyset-pagination.md). It is a prerequisite
for switching anonymous traffic from the legacy exhibition table to the
canonical public projection. It does not authorize that projection swap or a
production deployment by itself.

## What the reader guarantees

- Transport order is `id ASC`, 500 requested rows per page.
- Every non-empty page advances an exclusive ID cursor.
- Only an explicit empty page finishes an attempt.
- IDs are nonblank and unique across the attempt. Database collation owns page
  order; clients do not reproduce that comparison with language-specific
  string ordering.
- The website validates the first page's exact `Content-Range` count.
- Web and mobile compare the assembled ID count and SHA-256 membership checksum
  with `public.exhibition_reader_integrity` after the terminal page.
- A count/checksum mismatch discards all pages and retries once from page one.
- Any second mismatch, later-page failure, invalid DTO, or integrity-RPC failure
  fails the complete operation. A partial prefix is never published.
- Presentation sorting happens only after verification.

The checksum input is the database-ordered ID stream. Each ID is encoded as:

```text
<UTF-8 byte length>:<ID>
```

The encoded values are concatenated without a delimiter and hashed with
SHA-256. Do not substitute character count for UTF-8 byte length.

## Files in the contract

- Database indexes:
  `supabase/migrations/20260721105000_reader_keyset_indexes.sql`
- Missing legacy DTO column:
  `supabase/migrations/20260721105100_legacy_reader_ticket_url.sql`
- Integrity RPC:
  `supabase/migrations/20260721105200_reader_integrity_contract.sql`
- Database contract tests:
  `supabase/tests/database/007_reader_keyset_indexes.test.sql`
- Web reader and tests:
  `web/scripts/fetch-exhibitions.js` and
  `web/tests/fetch-exhibitions.test.js`
- Opt-in real PostgREST web test:
  `web/tests/fetch-exhibitions.integration.test.js`
- Mobile shared reader and tests:
  `shared/src/commonMain/kotlin/com/gallr/shared/data/network/ExhibitionPagination.kt`
  and
  `shared/src/commonTest/kotlin/com/gallr/shared/data/network/ExhibitionPaginationTest.kt`

## Step 1 — Verify the local schema

1. Start the local Supabase stack from the repository root:

   ```bash
   supabase start
   ```

2. Apply pending migrations:

   ```bash
   supabase migration up --local
   ```

3. Run the focused reader contract:

   ```bash
   supabase test db supabase/tests/database/007_reader_keyset_indexes.test.sql --local
   ```

4. Run the full database suite:

   ```bash
   supabase test db --local
   supabase db lint --local --level warning
   supabase db advisors --local --type all --level warn --fail-on error
   ```

5. Confirm the integrity RPC is anonymous-readable through the local gateway.
   Use the local publishable key emitted by `supabase status`; do not copy a
   service-role key into a client environment. An empty fixture should return
   count `0` and the SHA-256 of an empty byte stream.

Expected empty checksum:

```text
e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
```

## Step 2 — Run deterministic unit and cross-platform tests

1. Run the focused web contract:

   ```bash
   cd web
   node tests/fetch-exhibitions.test.js
   cd ..
   ```

   This covers 999, 1,000, 1,001, and 1,205 rows; server-shortened pages;
   duplicate and repeated-cursor IDs; same-count membership replacement;
   malformed integrity responses; and page-two/RPC failures.

2. Run all website tests and the production static build:

   ```bash
   cd web
   npm test
   cd ..
   ```

3. Run the shared mobile Android tests:

   ```bash
   ./gradlew :shared:testDebugUnitTest
   ```

4. Compile the shared code for iOS as well as Android:

   ```bash
   ./gradlew :shared:compileKotlinIosSimulatorArm64
   ./gradlew :shared:compileDebugKotlinAndroid
   ```

## Step 3 — Exercise the real PostgREST row cap

Use an isolated local project or staging clone. Do not seed production, and do
not use an uncommitted SQL transaction: PostgREST runs on another connection and
cannot see the transaction's rows.

1. Record the isolated database's baseline exhibition count and checksum.
2. Insert 1,205 valid fixtures with a unique, test-only lowercase ASCII ID
   prefix. Keep every required legacy column valid. Make at least two rows
   featured and link a subset to one test event so all three collection shapes
   can be exercised.
3. If testing server-shortened pages, set the isolated stack's
   `api.max_rows` below 500, restart that stack, and record the value. Never
   change the production cap for this test.
4. Export only the isolated stack's URL and publishable/anonymous key:

   ```bash
   export SUPABASE_URL="http://127.0.0.1:<isolated-api-port>"
   export SUPABASE_ANON_KEY="<isolated-publishable-key>"
   ```

5. Run the real web transport verification:

   ```bash
   cd web
   GALLR_POSTGREST_INTEGRATION=1 \
   GALLR_EXPECTED_MIN_EXHIBITIONS=1001 \
   GALLR_EXPECTED_EXHIBITION_COUNT=1205 \
   node tests/fetch-exhibitions.integration.test.js
   cd ..
   ```

   Adjust the exact count if the isolated database intentionally contains a
   retained baseline. The test must report more than 1,000 unique rows, at
   least two 500-row boundaries, an explicit empty terminal page, and a
   successful count/checksum comparison.

6. Exercise the featured and event-scoped mobile paths against the same stack.
   Confirm each request repeats its filter and its integrity call uses the
   corresponding typed parameter.
7. Delete only rows with the exact test prefix and the exact test event. Verify
   the baseline count and checksum are restored. Stop the isolated stack without
   retaining its test volume when the evidence has been saved.
8. Store the commands, stack configuration, row counts, checksum, and test
   output with the staging rehearsal record.

## Step 4 — Stage the rollout in dependency order

1. Take a staging database backup or restorable snapshot.
2. Apply the three reader migrations before deploying either client.
3. Run pgTAP, lint, and advisors against staging.
4. Call the integrity RPC as an anonymous client for:

   - all exhibitions;
   - featured exhibitions (`p_featured_only=true`);
   - one event (`p_event_id=<event-id>`).

5. Compare each RPC count/checksum with an independently exported, ID-sorted
   list from the same staging projection.
6. Deploy the website build to a non-production URL. Confirm the generated
   exhibition page count and sitemap count equal the RPC count.
7. Install a mobile canary build. Confirm catalog, featured, event detail,
   search, city counts, bookmarks, editor lists, and map pins all use the
   complete verified collection.
8. Test a forced page-two HTTP error and a forced checksum mismatch. The web
   build must fail; mobile must show its normal server error state. Neither may
   expose a prefix.
9. Keep this staging state for at least one complete content publish/rebuild
   cycle before requesting production activation.

## Step 5 — Activate production readers

1. Confirm the production migration history, PostgreSQL major version, backup,
   and rollback owner.
2. Apply database migrations first. Verify anonymous RPC execution and confirm
   that no table write or row count changed.
3. Record the production all/featured/event counts and checksums.
4. Deploy the website. Compare its logged row count, generated detail-page
   count, and sitemap count with the recorded integrity result.
5. Release mobile through the agreed canary percentage. Monitor server errors,
   refresh failures, RPC latency, and catalog counts before expanding rollout.
6. Do not remove the old reader, legacy table, or Apps Script credentials during
   this window. This step proves collection transport only; Sheet retirement
   still requires the final backfill, projection swap, and editorial freeze.

## Rollback

Roll back the client before changing the database:

1. Restore the prior web deployment and halt the mobile rollout.
2. Keep the additive indexes, `ticket_url` column, and integrity RPC. They do not
   mutate catalog rows and may still be called by installed client versions.
3. Record the failing endpoint, cursor, HTTP status, expected/actual count, and
   expected/actual checksum. Do not log API keys or full authorization headers.
4. Verify the legacy reader still returns the expected current catalog.
5. Fix and repeat staging verification before another canary.

Remove the additive database objects only after every dependent web/mobile
version is outside the supported rollback window and a reviewed migration
explicitly replaces the integrity contract with the canonical public
projection.
