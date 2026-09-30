# Staff-reviewed canonical artist backfill

This dependency-free Node 22 job prepares canonical artists from current
published exhibition credits. It is network-free: it never opens a database
connection, imports data, or applies a migration. The directory has no uniqueness
constraint on bilingual names, so the generated transaction locks the artist
table, deduplicates case-insensitive trimmed pairs, skips existing exact active
identities, and blocks conflicting, duplicated, or archived identities.

Published `credits_ko` / `credits_en` are free text, not an artist roster. Never
automatically zip comma-separated names or assume a collective is an individual.
The entire source pair must be reviewed against the published poster/credit.
`BBK(장종완 × 장준호)` and `ORB (김유자, 박정연)` receive an ambiguity warning;
all candidates start pending and have an empty artist list. Warning detection
is advisory and never substitutes for review. Missing translations must be
researched and supplied by staff. Do not manufacture translations.

## Preparation

Run the migration-lineage validator before any database session:

```sh
node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs
node --test scripts/staging-rehearsal/lib/validate-migration-lineage.test.mjs
node --test scripts/artist-backfill/artist-backfill.test.mjs
```

Obtain an authorized read-only export using `export-published-credits.sql` in a
verified staging session first. Follow `../staging-rehearsal/README.md` and the
applicable target identity guard. Production uses the separate
`../production-cutover/README.md` guard. Credentials come from the matching
1Password item, injected into the session; never put connection strings in
arguments or persistent environment files. Retain exports/reviews/SQL/receipts
outside the checkout in a private directory. For psql use unaligned tuples-only
output so the file contains one JSON object, with no table header.

Before exporting, set the session's `gallr.artist_backfill_environment` to
`staging` or `production`, and `gallr.artist_backfill_target_sha256` to the SHA-256
of the project reference verified by the target guard. These labels are assertions
by the operator; SQL cannot independently infer a Supabase project's identity.
Retain the guard receipt with the export. The generated SQL requires the same
verified fingerprint in its execution session and checks the source version
pointers and exact credits again before insertion.

```sh
node scripts/artist-backfill/artist-backfill.mjs prepare \
  /private/tmp/gallr-artist-review/published.json \
  /private/tmp/gallr-artist-review/review.json
```

The export shape is `{environment, targetSha256, snapshotAt, rows}`. Each row is
`{exhibitionId, versionId, creditsKo, creditsEn}`. Exhibition IDs are canonical
text identifiers (including legacy IDs); version IDs are UUIDs;
only currently pointed-to published versions are eligible. At most 10,000
exhibitions are accepted per batch. Empty exports may be reviewed but cannot
generate a mutation bundle with no approved identities.

Set `reviewedBy` to the reviewing active Admin Auth UUID and `reviewedAt` to a UTC
ISO timestamp. For every candidate, set `decision` to `approved` or `rejected`.
For approvals, supply a meaningful `reviewNote` and 1–32 bilingual artist pairs
in `artists: [{nameKo, nameEn}]`. A reviewer may retain a collective as one
identity or explicitly split it into verified people. Do not alter source,
fingerprints, candidate keys, credit text, or source pointers. Rejected entries
are omitted. Every candidate must have a decision before SQL generation.

```sh
node scripts/artist-backfill/artist-backfill.mjs sql \
  /private/tmp/gallr-artist-review/review.json \
  /private/tmp/gallr-artist-review/reviewed-backfill.sql
```

Artifacts use mode 0600 and are created exclusively; choose a fresh path for
every run. Review the complete manifest and generated SQL before execution.

## Execution boundary and verification

Local preparation is not authorization to execute against staging or production.
Use a separately authorized, verified environment session and rehearse in staging.
Do not copy staging credentials, source exports, target fingerprints, or reviewed
SQL into production. Production requires its own export and review.

Run the reviewed SQL with stop-on-error enabled and no surrounding transaction;
it owns BEGIN/COMMIT, a 5-second lock timeout and a 30-second statement timeout.
It checks the reviewer is still an active admin, retains published source
provenance, inserts only canonical identities, and records `artist.backfilled`
audit rows with source/review fingerprints. It never links or rewrites published
or draft credits. A rerun of the same approved bundle is a no-op once identities
exist. If any conflict/stale-source/target check fails, the whole transaction
rolls back; refresh evidence and review rather than weakening the guard.

Retain the returned `reviewed_identities_present` count and compare it with the
number of distinct approved bilingual pairs. Verify canonical search from Admin
for several approved Korean and English names, then resolve a draft suggestion
with Link to and confirm it replaces the row. Preserve the audit receipt and
source/review/SQL hashes. To undo an identity later, use a separately reviewed
canonical maintenance change after checking references; this job has no delete
or automatic rollback executor.

The network-free tests verify manifest validation and SQL generation. A separate
Postgres suite runs only in a fresh, explicitly named container with no network:

```sh
docker run --detach --network none --name gallr-artist-backfill-qa-local \
  --env POSTGRES_HOST_AUTH_METHOD=trust postgres:17-alpine
GALLR_ARTIST_BACKFILL_TEST_CONTAINER=gallr-artist-backfill-qa-local \
  node --test scripts/artist-backfill/artist-backfill-postgres.test.mjs
docker stop gallr-artist-backfill-qa-local
docker rm gallr-artist-backfill-qa-local
```

Wait for `docker exec gallr-artist-backfill-qa-local pg_isready -U postgres`
to pass before running the suite. No ports are published. The suite creates and
resets a minimal `content` schema in this disposable container; never point it
at an existing development or linked database. It tests generated SQL execution,
source/identity rejection, audit, whole-batch rollback, retry no-op, and exports.
CI also runs `artist-backfill-canonical.mjs` after the canonical migration replay.
It uses `SUPABASE_DB_CONTAINER` to select a local Unix-socket Docker container,
inserts real Auth/staff/published-version fixtures, exercises the generated body
and actual authenticated search RPC, and rolls back all fixtures and writes:

```sh
SUPABASE_DB_CONTAINER=supabase_db_gallr \
  node scripts/artist-backfill/artist-backfill-canonical.mjs
```

Use only a disposable local migration-replayed database for this command. It
checks insert/audit/retry, target/reviewer/source/identity denial, and the export.
Before a remote run, replay the generated SQL on a disposable database with the deployed
schema, checking retry no-op, archived/partial-name collision rollback, stale
source rejection, and audit inserts. No migration or grant changes are needed.
