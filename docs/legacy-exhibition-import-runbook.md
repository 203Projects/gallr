# Runbook: Legacy exhibition import and reconciliation

**Status (2026-08-08): historical only.** The production ownership transfer and rollback window are
complete, and the external Apps Script projects were permanently deleted. Do not run a new live
Sheet import or restore the retired writer from this document; preserve it for migration evidence.

**Owner:** gallr engineering and content operations | **Frequency:** One migration,
then approved delta imports as needed
**Last Updated:** 2026-07-23 | **Last Run:** Never

## Purpose

This runbook moves a timestamped legacy exhibition snapshot into the private,
versioned PostgreSQL CMS through a staged, reviewable process. It covers source
exports, the offline dry-run bundle, service-only stage/apply/reconcile commands,
staging-clone verification, the final freeze/delta, and rollback.

No live Google Sheet export, production import, production migration, trigger
change, or public-reader change has been performed by the work documented here.
Every path, project reference, timestamp, UUID, credential, and approval below
is a placeholder until an authorized operator supplies it during a change
window.

Phase 4 is additive. It does not replace, rename, truncate, or grant anonymous
access to `public.exhibitions`. Mobile and web continue to read the legacy public
table. `public.exhibitions_v2_preview` is a service-role-only reconciliation
surface, not a public API.

## Governance profiles

`separated_humans` is the default and retains a real executor plus a different
real reviewer/approver. `solo_operator` is explicit and may be used only when
one person genuinely performs every operational responsibility. Use one stable
identity throughout; do not invent aliases. Solo evidence records
`human_reviewer_count=0`, `automation_is_independent_human_review=false`, and
explicit acceptance of the remaining risk. CI, AI, scripts, and separate
terminal or database sessions are technical controls, not independent human
review.

The solo profile uses the 15-minute staging intent/execute cooldown and the
30-minute production Gate 4/Gate 6 policies defined in the
[catalog cutover runbook](public-exhibition-catalog-cutover-runbook.md) and
[ADR-0004](adr/0004-solo-operator-cutover-governance.md). Those waits reduce
accidental target and sequencing errors; they do not reproduce peer review.

## Safety invariants

1. The `id` exported from `public.exhibitions` is authoritative. Never replace
   it with a Google Apps Script hash or a newly generated ID.
2. Never edit `bundle.json` or a staged batch to make an error disappear. Correct
   the authoritative source, export again, and create a new batch.
3. Never put a service-role key in a browser, source file, checked-in `.env`,
   request artifact, screenshot, ticket, chat, or shell history. Use a trusted
   operator terminal and inject it at runtime from the approved secret manager.
4. Never use the anonymous key for stage, apply, or reconciliation.
5. Never run `curl -v`, enable shell tracing with `set -x`, or capture terminal
   telemetry while a service-role key is loaded.
6. Missing IDs are reported but are not archived or deleted. Removal is a
   separate, explicit publisher action.
7. Legacy cover URLs are preserved as fallback strings. This procedure does not
   copy images and does not invent `media_assets` metadata.
8. Apply is allowed only for the exact batch UUID accepted under the selected
   governance profile. `separated_humans` requires the existing two-person
   review. `solo_operator` requires one stable identity, zero human reviewers,
   sealed evidence, and the applicable cooldown; it must not be described as
   independent review. Do not select the most recent batch by timestamp.
9. The two exhibition Apps Script triggers remain enabled throughout Phase 4.
   They may be disabled only after a successful final delta report and separate
   controlled-cutover approval.
10. Apply full snapshots in strictly increasing `source_snapshot_at` order. If
    `legacy_import_source_snapshot_not_newer` is returned, never edit the bundle
    timestamp; capture and review a fresh database snapshot.
11. Finish one reviewed stage/apply/reconcile cycle before applying another
    batch. Every staged batch records an applied-batch baseline; if that baseline
    changes, the pending batch is rejected and must be replaced by a fresh full
    snapshot.

## Prerequisites

- [ ] Approved change record naming the staging project and, for a later final
  run, the production project.
- [ ] Governance profile recorded. `separated_humans` names an executor and a
  different real reviewer/approver; `solo_operator` names one stable real
  identity for all responsibilities and records `human_reviewer_count=0`.
- [ ] Timestamped database backup or restorable staging clone.
- [ ] Read/export access to the exhibition Google Sheet.
- [ ] Read access to the spreadsheet timezone and the bound Apps Script project
  timezone.
- [ ] Access to Apps Script execution history and trigger administration.
- [ ] Read-only database export access for `public.exhibitions`,
  `public.events`, and `public.editors`.
- [ ] Supabase SQL Editor/direct database access for private import inspection.
- [ ] Service-role credential available only from the approved secret manager.
- [ ] Node.js available; the offline bundler has no package dependencies.
- [ ] `jq`, `curl`, and macOS `shasum` (or the platform-equivalent SHA-256 tool).
- [ ] Repository checkout pinned to the reviewed commit containing migration
  `20260721075225_legacy_import_and_compatibility_preview.sql`.
- [ ] A secure artifact location outside the repository for source exports,
  reports, approvals, and the exception ledger.
- [ ] The staging Supabase project is an isolated clone, not the production
  project.

## Blocking conditions

Stop immediately; do not stage or apply when any of these conditions is true:

- The governance profile is absent or ambiguous, identities do not satisfy that
  profile, automation is presented as a human reviewer, or required cooldown
  and attestation evidence is missing or stale.
- The Sheet or database export is missing, empty, editable in place, or has no
  recorded SHA-256 checksum.
- The spreadsheet timezone or Apps Script timezone is unknown, or reception
  timestamps are ambiguous across mismatched timezones.
- The Sheet and database snapshots were not taken in the agreed quiet window,
  or an edit/sync occurred between them without a new export.
- The offline command exits `1` or `2`, `summary.json.import_ready` is not true,
  or `issues.csv` contains an `error`.
- An authoritative ID is missing/duplicated, a required field/date/boolean is
  invalid, coordinates are invalid or unpaired, `updated_at` is invalid, or an
  event/editor reference is orphaned.
- `reconciliation.csv` contains an unexplained `mismatched`, `legacy_only`,
  `sheet_only`, or `ambiguous_sheet_match` row.
- Source checksums, snapshot timestamp, row count, or batch UUID differ between
  the reviewed files and the stage response.
- The staged batch has `blocked_rows > 0`, `error_count > 0`, or a status other
  than `validated`.
- A canonical admin draft exists, the canonical published pointer changed since
  an earlier import, or the importer reports an identity collision.
- The snapshot timestamp is older than or equal to the latest applied snapshot,
  even if the older batch was staged first.
- The latest applied batch differs from the batch baseline recorded during
  staging, even if the pending snapshot has a later timestamp.
- Database reconciliation has any unexplained difference, including
  `only_in_source`, `only_in_preview`, or a field checksum mismatch.
- `anon` or ordinary `authenticated` users can select either Phase 4 preview.
- Database tests, lint, security advisors, build tests, or the public-table
  before/after checksum fail.
- The final public-reader/RLS decision is not approved. In that case, complete
  only the Phase 4 rehearsal/backfill and leave Apps Script triggers enabled.

Structural errors are not waivable exceptions. Fix them and create a new source
snapshot. Warnings or known reconciliation differences may proceed only when
every item has a reviewed exception-ledger row and the change owner accepts the
risk in writing.

## Exception ledger

Keep the ledger with the immutable migration artifacts outside the repository.
Use these exact columns so each difference is attributable:

```text
exception_id,batch_id,source_snapshot_at,source_sha256,source_row_number,
exhibition_id,phase,severity,issue_code,field,source_value,preview_value,
rationale,risk,owner,approver,approved_at,status,resolution,resolved_at,
evidence_uri
```

Rules:

- `phase` is `offline_sheet_reconciliation`, `database_stage`, or
  `database_reconciliation`.
- `status` is `open`, `approved`, `resolved`, or `rejected`.
- Record values exactly unless they contain personal or secret data; for those,
  store a checksum and an access-controlled evidence URI.
- In `separated_humans`, executor and approver must be different real people.
  In `solo_operator`, `owner` and `approver` contain the same stable identity;
  the change record must separately disclose `human_reviewer_count=0` and must
  not present that row as independently reviewed.
- An `open` or `rejected` item is unexplained and blocks apply/cutover.
- The ledger never changes the import bundle; it documents a reviewed exception.
- Identity, row-count, checksum, authorization, draft-exposure, and unexplained
  data-integrity differences are unwaivable in solo mode. Fix them or switch to
  `separated_humans` with a real external reviewer. A solo operator may approve
  only a preclassified non-material warning whose treatment was already bound
  to the change record.

## Procedure

### Step 1: Create a unique, non-repository run directory

Choose an explicit UTC run ID. Do not use a shared or previously populated
output directory because the bundler may replace its four report files.

```sh
export GALLR_IMPORT_RUN_ID="<YYYYMMDDTHHMMSSZ>-<staging-or-production>"
export GALLR_IMPORT_DIR="/absolute/secure/path/${GALLR_IMPORT_RUN_ID}"
export GALLR_SOURCE_DIR="${GALLR_IMPORT_DIR}/source"
export GALLR_REVIEW_DIR="${GALLR_IMPORT_DIR}/review"
export GALLR_RPC_DIR="${GALLR_IMPORT_DIR}/rpc"
mkdir -p "${GALLR_SOURCE_DIR}" "${GALLR_REVIEW_DIR}" "${GALLR_RPC_DIR}"
chmod 0700 "${GALLR_IMPORT_DIR}" "${GALLR_SOURCE_DIR}" \
  "${GALLR_REVIEW_DIR}" "${GALLR_RPC_DIR}"
```

Create `operator-manifest.txt` in that directory with:

```text
run_id=<RUN_ID>
target=staging|production
governance_mode=separated_humans|solo_operator
human_reviewer_count=<positive-count-for-separated-humans|0-for-solo>
automation_is_independent_human_review=false
residual_risk_accepted=<false-for-separated-humans|true-for-solo>
repository_commit=<FULL_GIT_SHA>
sheet_document=<CONTROLLED_REFERENCE>
sheet_tab=<EXHIBITION_TAB_NAME_AND_GID>
sheet_exported_at_utc=<ISO_8601>
spreadsheet_timezone=<IANA_TIMEZONE>
apps_script_timezone=<IANA_TIMEZONE>
last_successful_sync_at_utc=<ISO_8601>
last_successful_sync_execution_id=<EXECUTION_ID>
database_snapshot_at_utc=<FILLED_FROM_JSON_EXPORT>
executor=<NAME>
reviewer=<NAME>
change_record=<ID>
exhibition_triggers_enabled=true
```

For `solo_operator`, `executor` and `reviewer` must contain the same exact stable
identity. The repeated compatibility field is not evidence of independent
review. Also bind the applicable policy/manifest hashes, first and second
confirmation hashes and timestamps, required and observed cooldown, target
fingerprints, backup identifier, rollback mode, and rollback-threshold hash in
the restricted change evidence. Do not put raw project references, database
URLs, or credentials into artifacts whose contracts require fingerprints only.

**Expected result:** A new access-controlled directory and incomplete manifest
exist outside the repository.

**If it fails:** Stop. Do not use a repository folder, an existing review
directory, or a broadly shared temp folder for production evidence.

### Step 2: Establish the quiet window and record timezones

1. In Google Sheets, open **File → Settings** and record the spreadsheet's IANA
   timezone without changing it.
2. In the bound Apps Script project, open **Project Settings** and record its
   timezone without changing it.
3. Confirm the two `syncToSupabase` triggers exist: the installable spreadsheet
   `On edit` trigger and the time-driven five-minute trigger.
4. For rehearsal, ask editors not to edit while exports are captured. For the
   final delta, announce a formal content freeze covering the exhibition Sheet,
   event Sheet, and CMS.
5. Keep the triggers enabled. Wait for, or manually run through the approved UI,
   one successful exhibition sync. Record its UTC completion time and execution
   ID.

**Expected result:** Timezones and the final successful sync are recorded, and
no edits occur during source capture.

**If it fails:** Do not infer timezone or sync success. Resolve access, repeat
the sync, and restart the export window.

### Step 3: Export the exhibition Sheet as immutable CSV

1. Select the exhibition worksheet, not the events worksheet.
2. Use **File → Download → Comma Separated Values (.csv), current sheet**.
3. Save it as:

```text
<GALLR_SOURCE_DIR>/exhibitions-sheet-<YYYYMMDDTHHMMSSZ>.csv
```

4. Record the exact UTC download completion time, tab name/GID, spreadsheet
   timezone, and Apps Script timezone in `operator-manifest.txt`.
5. Do not open and save the source CSV in Excel or another editor. Review copies
   may be made later; the checksum-protected source file stays untouched.

**Expected result:** A nonempty current-tab CSV exists and its header includes
`name_ko`, `venue_name_ko`, `city_ko`, `region_ko`, `opening_date`, and
`closing_date`.

**If it fails:** Delete only the failed download, reselect the correct tab, and
export again under a new timestamp. Never repair the source CSV manually.

### Step 4: Export timestamp-matched database JSON in one read-only snapshot

Use the approved database client or SQL Editor against the named target. Run the
following as one read-only, repeatable-read transaction. Replace no identifiers;
the three public table names are intentional.

```sql
begin transaction isolation level repeatable read read only;

select jsonb_pretty(
  jsonb_build_object(
    'source_snapshot_at', transaction_timestamp(),
    'exhibitions', coalesce(
      (select jsonb_agg(to_jsonb(exhibition) order by exhibition.id)
       from public.exhibitions as exhibition),
      '[]'::jsonb
    ),
    'events', coalesce(
      (select jsonb_agg(to_jsonb(event) order by event.id)
       from public.events as event),
      '[]'::jsonb
    ),
    'editors', coalesce(
      (select jsonb_agg(to_jsonb(editor) order by editor.id)
       from public.editors as editor),
      '[]'::jsonb
    )
  )
) as snapshot_json;

commit;
```

Save the exact JSON value—not a screenshot, HTML page, or CSV-quoted wrapper—as:

```text
<GALLR_SOURCE_DIR>/legacy-database-snapshot-<YYYYMMDDTHHMMSSZ>.json
```

Validate and split it without changing field values:

```sh
jq -e '.source_snapshot_at and (.exhibitions | type == "array") and \
  (.events | type == "array") and (.editors | type == "array")' \
  "${GALLR_SOURCE_DIR}/legacy-database-snapshot-<YYYYMMDDTHHMMSSZ>.json"

jq '{source_snapshot_at, rows: .exhibitions}' \
  "${GALLR_SOURCE_DIR}/legacy-database-snapshot-<YYYYMMDDTHHMMSSZ>.json" \
  > "${GALLR_SOURCE_DIR}/public-exhibitions-<YYYYMMDDTHHMMSSZ>.json"

jq '{source_snapshot_at, rows: .events}' \
  "${GALLR_SOURCE_DIR}/legacy-database-snapshot-<YYYYMMDDTHHMMSSZ>.json" \
  > "${GALLR_SOURCE_DIR}/public-events-<YYYYMMDDTHHMMSSZ>.json"

jq '{source_snapshot_at, rows: .editors}' \
  "${GALLR_SOURCE_DIR}/legacy-database-snapshot-<YYYYMMDDTHHMMSSZ>.json" \
  > "${GALLR_SOURCE_DIR}/public-editors-<YYYYMMDDTHHMMSSZ>.json"
```

Copy `.source_snapshot_at` into `operator-manifest.txt`. The Sheet export and
database snapshot should be within the approved quiet-window tolerance
(normally five minutes) with no intervening edit or sync. The events/editors
exports are audit evidence for foreign-key resolution; the current offline
bundler consumes only exhibitions JSON and optional Sheet CSV.

**Expected result:** All three wrapped JSON files have the same snapshot
timestamp and nonnegative row counts.

**If it fails:** Discard the entire database export set and rerun the one
transaction. Never mix exhibitions, events, and editors from different database
snapshots.

### Step 5: Checksum and freeze the source artifacts

From the secure run directory:

```sh
/usr/bin/shasum -a 256 "${GALLR_SOURCE_DIR}"/* \
  | tee "${GALLR_IMPORT_DIR}/SOURCE-SHA256SUMS.txt"
/usr/bin/shasum -a 256 -c "${GALLR_IMPORT_DIR}/SOURCE-SHA256SUMS.txt"
chmod 0444 "${GALLR_SOURCE_DIR}"/* \
  "${GALLR_IMPORT_DIR}/SOURCE-SHA256SUMS.txt"
```

Copy the Sheet CSV, exhibitions JSON, events JSON, and editors JSON checksums
into the change record. Store an immutable backup in the approved artifact
location.

**Expected result:** Verification reports `OK` for every source file, and source
files are read-only.

**If it fails:** Stop and determine which byte changed. Do not recompute a
checksum merely to bless an unexplained modification.

### Step 6: Test and run the offline bundler

From the repository root at `<FULL_GIT_SHA>`:

```sh
node --test scripts/legacy-import/legacy-import.test.mjs

node scripts/legacy-import/legacy-import.mjs \
  --legacy-json \
  "${GALLR_SOURCE_DIR}/public-exhibitions-<YYYYMMDDTHHMMSSZ>.json" \
  --sheet-csv \
  "${GALLR_SOURCE_DIR}/exhibitions-sheet-<YYYYMMDDTHHMMSSZ>.csv" \
  --sheet-timezone "<IANA-time-zone>" \
  --output-dir "${GALLR_REVIEW_DIR}"
export GALLR_BUNDLER_EXIT_CODE="$?"
```

Exit meanings:

- `0`: reports were written with no blocking offline error; review is still
  mandatory.
- `2`: reports were written but `import_ready` is false; this is a hard stop.
- `1`: arguments/input failed; reports may be absent; this is a hard stop.

The bundler is offline: it opens no network or database connection. It writes
`bundle.json`, `summary.json`, `issues.csv`, and `reconciliation.csv`.
`--sheet-timezone` must be the spreadsheet's IANA timezone from Step 3. The
bundler uses it to compare Sheet calendar dates with Supabase timestamps without
creating false mismatches at UTC boundaries.

**Expected result:** Tests pass, exit code is `0`, and exactly four nonempty
review files exist.

**If it fails:** Read the terminal error and report files if present. Fix the
source system or export procedure, then use a new run ID. Do not patch the
generated bundle.

### Step 7: Review the dry-run summary, issues, and Sheet reconciliation

Inspect the summary:

```sh
jq '{generated_at, source, sheet_source, dry_run, import_ready, counts}' \
  "${GALLR_REVIEW_DIR}/summary.json"
/usr/bin/shasum -a 256 \
  "${GALLR_SOURCE_DIR}/public-exhibitions-<YYYYMMDDTHHMMSSZ>.json"
jq -r '.source.sha256' "${GALLR_REVIEW_DIR}/summary.json"
rg '^(error|warning|info),' "${GALLR_REVIEW_DIR}/issues.csv"
rg ',(mismatched|legacy_only|sheet_only|ambiguous_sheet_match),' \
  "${GALLR_REVIEW_DIR}/reconciliation.csv"
```

Then open read-only copies of both CSVs in a proper CSV viewer and inspect every
row. CSV fields can contain quotes, commas, and newlines; do not rely only on
terminal alignment.

Required review:

1. `import_ready` is true and `counts.errors` is zero.
2. The summary SHA-256 exactly matches the exhibitions JSON file.
3. `legacy_rows` equals the exported public-table count.
4. Every warning is resolved or entered in the exception ledger.
5. Every publishable Sheet row reconciles to exactly one authoritative database
   ID.
6. `gas_id_matches_authoritative` is diagnostic only. A false value never
   authorizes replacing the database ID.
7. Duplicate generated IDs are hard blockers even when the visible Korean
   fields differ. The historical Apps Script string digest substitutes
   non-ASCII characters and can therefore collide.
8. `sheet_not_publishable` rows are expected only when the current approval gate
   intentionally excludes them.
9. Every mismatched field, Sheet-only row, public-only row, or ambiguous match is
   corrected by a new export or explicitly approved in the ledger.
10. `bundle.json.source_snapshot_at`, `source_sha256`, `row_count`, and filenames
   match the manifest and reviewed evidence.

After the profile-required acceptance, checksum and make the four review files
read-only. `separated_humans` requires both operators to sign off. In
`solo_operator`, the stable operator must inspect every row, record the exact
review-file hashes and a timestamped self-attestation, and disclose zero human
reviewers; automation may validate the files but may not sign off:

```sh
/usr/bin/shasum -a 256 "${GALLR_REVIEW_DIR}"/* \
  | tee "${GALLR_IMPORT_DIR}/REVIEW-SHA256SUMS.txt"
chmod 0444 "${GALLR_REVIEW_DIR}"/* \
  "${GALLR_IMPORT_DIR}/REVIEW-SHA256SUMS.txt"
```

**Expected result:** The bundle is approved, reproducible, and has no structural
errors or unexplained offline difference.

**If it fails:** Stop. Correct the source and restart from Step 1 with a new run
ID. Do not stage a partially reviewed bundle.

### Step 8: Verify the staging clone and migration before importing

Confirm the Supabase CLI is linked to `<STAGING_PROJECT_REF>`, not production,
before every `--linked` command. Apply reviewed migrations to the staging clone
through the normal deployment path, then run:

```sh
supabase migration list --linked
supabase db lint --linked \
  --schema public,content,content_private --fail-on error
supabase db advisors --linked --type security --fail-on error
supabase db advisors --linked --type performance --fail-on error
supabase test db supabase/tests/database --linked

cd admin
npm run typecheck
npm test
npm run build
cd ..

./gradlew :shared:testAndroidHostTest :composeApp:testAndroidHostTest
```

Also capture the legacy public table's pre-import count/checksum in SQL Editor:

```sql
select
  count(*) as row_count,
  encode(
    extensions.digest(
      convert_to(
        coalesce(jsonb_agg(to_jsonb(exhibition) order by exhibition.id), '[]'::jsonb)::text,
        'UTF8'
      ),
      'sha256'
    ),
    'hex'
  ) as row_sha256
from public.exhibitions as exhibition;
```

**Expected result:** Migration history includes the Phase 4 migration, all tests
pass, advisors/lint meet the approved baseline, and the public count/checksum is
recorded.

**If it fails:** Stop. Do not stage data into an unverified or incorrectly
linked project.

### Step 9: Stage the approved bundle through the service-only RPC

Create a request body that contains data only—never the key:

```sh
jq -n --slurpfile bundle "${GALLR_REVIEW_DIR}/bundle.json" \
  '{p_bundle: $bundle[0]}' \
  > "${GALLR_RPC_DIR}/stage-request.json"
chmod 0600 "${GALLR_RPC_DIR}/stage-request.json"
```

On a trusted operator terminal, set the target URL and load the key without
echoing it. The first complete run must target staging:

```sh
export GALLR_SUPABASE_URL="https://<STAGING_PROJECT_REF>.supabase.co"
read -s GALLR_SERVICE_ROLE_KEY
printf '\n'

curl --fail-with-body --silent --show-error \
  --request POST \
  --url "${GALLR_SUPABASE_URL}/rest/v1/rpc/migration_stage_legacy_exhibitions" \
  --header "apikey: ${GALLR_SERVICE_ROLE_KEY}" \
  --header "Authorization: Bearer ${GALLR_SERVICE_ROLE_KEY}" \
  --header "Content-Type: application/json" \
  --data-binary "@${GALLR_RPC_DIR}/stage-request.json" \
  --output "${GALLR_RPC_DIR}/stage-response.json"

unset GALLR_SERVICE_ROLE_KEY
```

Inspect the response:

```sh
jq '{batch_id, status, source_system, source_snapshot_at, source_sha256,
  normalized_sha256, baseline_batch_id, row_count, blocked_rows, error_count, warning_count,
  planned_actions, missing_previously_imported_ids, idempotent_replay}' \
  "${GALLR_RPC_DIR}/stage-response.json"
```

The database stores the submitted bundle row and its server-normalized copy even
when a row is blocked. The byte-exact database export remains in the immutable
source-artifact set from Step 5. A repeated identical source SHA may return
`idempotent_replay: true`; it must point to the same reviewed batch and report.

**Expected result:** `status` is `validated`, `blocked_rows` and `error_count`
are zero, and source timestamp/SHA/count match the approved bundle exactly.

**If it fails:** Do not apply. For HTTP `401`/`403`, verify target and credential
source without logging the key. For validation errors, inspect Step 10, correct
the authoritative source, and stage a new export/batch.

### Step 10: Inspect private batch rows in SQL Editor when needed

Use direct database access or SQL Editor. These tables are intentionally not
available to browser clients. Replace only the placeholder UUID.

```sql
select
  id,
  source_system,
  source_file_name,
  source_snapshot_at,
  source_sha256,
  normalized_sha256,
  baseline_batch_id,
  status,
  row_count,
  error_count,
  warning_count,
  created_at,
  validated_at,
  applied_at
from content.legacy_import_batches
where id = '<REVIEWED_BATCH_UUID>'::uuid;
```

```sql
select
  row_ordinal,
  source_row_number,
  source_id,
  action,
  row_sha256,
  applied_version_id
from content.legacy_import_rows
where batch_id = '<REVIEWED_BATCH_UUID>'::uuid
order by row_ordinal;
```

```sql
select
  staged.row_ordinal,
  staged.source_row_number,
  staged.source_id,
  issue.value ->> 'severity' as severity,
  issue.value ->> 'code' as code,
  issue.value ->> 'field' as field,
  issue.value ->> 'message' as message
from content.legacy_import_rows as staged
cross join lateral jsonb_array_elements(staged.issues) as issue(value)
where staged.batch_id = '<REVIEWED_BATCH_UUID>'::uuid
order by staged.row_ordinal, severity, code;
```

For one disputed row only:

```sql
select
  jsonb_pretty(raw_payload) as raw_payload,
  jsonb_pretty(normalized_payload) as normalized_payload
from content.legacy_import_rows
where batch_id = '<REVIEWED_BATCH_UUID>'::uuid
  and row_ordinal = <ROW_ORDINAL>;
```

**Expected result:** Database rows match the offline report and contain no
unreviewed error.

**If it fails:** Do not update private rows with SQL. Correct/export/restage.
Escalate broken provenance, a canonical collision, or a batch/report mismatch.

### Step 11: Apply only the reviewed batch UUID

Under `separated_humans`, the reviewer copies the exact UUID from the signed
stage response into the change record and the executor sets it explicitly.
Under `solo_operator`, the stable operator copies the exact UUID into the sealed
change evidence during the self-attestation and then sets that same UUID
explicitly. This is not independent review. Never query for `max(created_at)`.

```sh
export GALLR_REVIEWED_BATCH_UUID="<REVIEWED_BATCH_UUID>"
jq -n --arg p_batch_id "${GALLR_REVIEWED_BATCH_UUID}" \
  '{p_batch_id: $p_batch_id}' \
  > "${GALLR_RPC_DIR}/apply-request.json"
chmod 0600 "${GALLR_RPC_DIR}/apply-request.json"

read -s GALLR_SERVICE_ROLE_KEY
printf '\n'

curl --fail-with-body --silent --show-error \
  --request POST \
  --url "${GALLR_SUPABASE_URL}/rest/v1/rpc/migration_apply_legacy_exhibitions" \
  --header "apikey: ${GALLR_SERVICE_ROLE_KEY}" \
  --header "Authorization: Bearer ${GALLR_SERVICE_ROLE_KEY}" \
  --header "Content-Type: application/json" \
  --data-binary "@${GALLR_RPC_DIR}/apply-request.json" \
  --output "${GALLR_RPC_DIR}/apply-response.json"

unset GALLR_SERVICE_ROLE_KEY
jq '{batch_id, status, applied_at, applied_actions, idempotent_replay}' \
  "${GALLR_RPC_DIR}/apply-response.json"
```

Apply rechecks stateful conflicts inside a transaction and advisory lock. If the
connection outcome is ambiguous, query the batch status before retrying. A
confirmed replay of the same applied UUID is idempotent; never substitute a
different UUID during incident response. Distinct batches are applied only in
strictly increasing source-snapshot order, and the applied baseline must still
equal the `baseline_batch_id` reviewed at staging.

**Expected result:** Response status is `applied`; applied insert/revise/
unchanged counts reconcile to the batch row count.

**If it fails:** The transaction should roll back. Query batch and row status.
Resolve `canonical_draft_exists` or `canonical_changed_since_import` through the
admin/source-of-truth process, then create a fresh snapshot; do not force-update
the pointer or draft. For `legacy_import_source_snapshot_not_newer`, leave the
old batch as evidence and capture a new full snapshot; never alter its timestamp.
For `legacy_import_baseline_changed_since_stage`, another import changed the
review baseline: preserve the pending batch, then export, bundle, and review a
new full snapshot.

On an apply-time validation failure, inspect the HTTP error artifact before
restaging:

```sh
jq '{code, message, details, hint}' "${GALLR_RPC_DIR}/apply-response.json"
```

`details` contains up to 100 freshly evaluated error rows. The failed
transaction rolls back those refreshed row issues, so a later SQL query of the
staged rows may still show the original stage-time report. Preserve the error
response, then take a fresh snapshot/restage to persist current diagnostics.

### Step 12: Reconcile canonical preview against the reviewed source

Use the same reviewed UUID:

```sh
jq -n --arg p_batch_id "${GALLR_REVIEWED_BATCH_UUID}" \
  '{p_batch_id: $p_batch_id}' \
  > "${GALLR_RPC_DIR}/reconcile-request.json"
chmod 0600 "${GALLR_RPC_DIR}/reconcile-request.json"

read -s GALLR_SERVICE_ROLE_KEY
printf '\n'

curl --fail-with-body --silent --show-error \
  --request POST \
  --url "${GALLR_SUPABASE_URL}/rest/v1/rpc/migration_reconcile_legacy_exhibitions" \
  --header "apikey: ${GALLR_SERVICE_ROLE_KEY}" \
  --header "Authorization: Bearer ${GALLR_SERVICE_ROLE_KEY}" \
  --header "Content-Type: application/json" \
  --data-binary "@${GALLR_RPC_DIR}/reconcile-request.json" \
  --output "${GALLR_RPC_DIR}/reconcile-response.json"

unset GALLR_SERVICE_ROLE_KEY
jq '{batch_id, batch_status, source_count, preview_count, matching_count,
  difference_count, differences}' \
  "${GALLR_RPC_DIR}/reconcile-response.json"
jq -e '.batch_status == "applied" and .difference_count == 0' \
  "${GALLR_RPC_DIR}/reconcile-response.json"
```

The default acceptance gate is `difference_count = 0`. If the change owner has
explicitly allowed a known compatibility exception, there must be one approved
ledger row for every returned ID, status, checksum, and differing field; after
subtracting those exact approvals, the unexplained difference count must be
zero. Broad statements such as “fixture drift” are not sufficient.

Verify the public reader and preview grants in SQL:

```sql
select
  has_table_privilege('anon', 'public.exhibitions_v2_preview', 'select')
    as anon_can_read_exhibition_preview,
  has_table_privilege('authenticated', 'public.exhibitions_v2_preview', 'select')
    as authenticated_can_read_exhibition_preview,
  has_table_privilege('anon', 'public.guest_editors_v2_preview', 'select')
    as anon_can_read_editor_preview;
```

All three results must be `false`. Rerun the legacy public count/checksum query
from Step 8 and compare it byte-for-byte with the recorded pre-import values.

**Expected result:** The batch is applied, every compatibility field matches or
has an exact approved exception, preview grants remain service-only, and
`public.exhibitions` is unchanged.

**If it fails:** Do not grant preview access and do not cut over. Preserve all
responses, pause canonical publishing/imports, identify the differing field,
and fix through an additive migration or a new source batch.

### Step 13: Complete staging-clone acceptance

On staging, verify all of the following after apply/reconcile:

1. Run Step 8 tests and advisors again.
2. Publish, archive, and restore a staging-only test exhibition through the
   admin and confirm it does not mutate `public.exhibitions` in Phase 4.
3. Confirm a legacy URL appears only as fallback when no published CMS cover is
   attached; no fake media record was created.
4. Confirm a repeated stage/apply of the exact same source is idempotent.
5. Confirm a changed canonical published pointer or active draft blocks a later
   conflicting import.
6. Confirm an ID missing from a later test bundle is reported in
   `missing_previously_imported_ids` and is not archived.
7. Verify the existing mobile/web environments still read legacy
   `public.exhibitions`; do not point them at the preview.
8. Archive the full staging evidence and complete the selected profile's
   acceptance. In `separated_humans`, obtain reviewer approval. In
   `solo_operator`, record a timestamped self-attestation, zero human reviewers,
   automation disclosure, and residual-risk acceptance; automated checks are
   supporting evidence, not approval.

**Expected result:** The complete runbook succeeds on the staging clone with no
unexplained difference and no public-reader change.

**If it fails:** Production import is blocked. Fix and repeat on a refreshed
staging clone.

### Step 14: Run the final production freeze and delta only after approval

This is a later controlled change, not part of an ordinary Phase 4 rehearsal.

1. Confirm the staging acceptance, database backup, public-reader ADR, staff
   allowlist, monitoring, and rollback responsibility are accepted under the
   selected governance profile.
2. Announce a freeze on exhibition Sheet, event Sheet, and CMS edits.
3. Keep both exhibition `syncToSupabase` triggers enabled long enough to flush
   the final Sheet state. Record the last successful execution.
4. Repeat Steps 1–7 with brand-new production exports and checksums. Do not reuse
   the staging bundle or batch UUID.
5. Run the complete Step 8 preflight against the explicitly verified production
   project: confirm migration history, run the approved lint/advisor/test gates,
   and capture a new production `public.exhibitions` count/checksum baseline.
   Never reuse the staging baseline.
6. Repeat Steps 9–12 against the explicitly verified production URL and the new
   reviewed production batch UUID.
7. Require zero unexplained offline and database differences, unchanged legacy
   public count/checksum, and healthy monitoring. `separated_humans` requires
   reviewer sign-off. `solo_operator` requires the fresh schema-2 production
   policy, full 30-minute cooldown, exact target/gate/operation/commit-bound
   execution confirmation entered at the guard's post-cooldown prompt, and
   timestamped self-attestation defined by the catalog cutover runbook. Keep the
   preloaded production-confirmation variable unset; none of those controls is
   independent human review.
8. If the separately approved public-reader swap is not ready, stop here, keep
   the exhibition triggers enabled, and continue serving the legacy workflow.

Only after the successful final report and cutover authorization:

9. In Apps Script trigger administration, disable the two exhibition
   `syncToSupabase` triggers: the installable `On edit` trigger and the
   five-minute time-driven trigger. Record their trigger IDs and disable time.
10. Do not disable the separate `SyncEvents.gs` triggers; the events Sheet remains
   a blocker until its own migration is complete.
11. Make the exhibition Sheet read-only for normal editors and retain its export.
12. Execute the public-reader deployment from its separate approved ADR. Phase 4
    itself never renames or grants `public.exhibitions_v2_preview`.
13. Monitor record counts, field checksums, client errors, pagination, admin
    publication, and website rebuild delivery for the full rollback window.

**Expected result:** Triggers are disabled only after a successful final delta
and immediately before the approved reader cutover; the old table and evidence
remain recoverable.

**If it fails:** Invoke Rollback. Do not disable triggers early, delete the
legacy table, or improvise an anonymous preview grant.

This import procedure never authorizes legacy retirement. Dropping the legacy
table, RPC, or workflow may occur only after Gate 7 and a full editorial cycle,
through a separate change record, separate reviewed commit and retirement
migration, fresh restore evidence, and—under `solo_operator`—a 24-hour sealed
intent hold. That hold is not independent review.

## Verification checklist

- [ ] Governance profile, stable identities, human-reviewer count, automation
  disclosure, residual-risk acceptance, and applicable cooldown evidence are
  recorded without fabricated reviewers.
- [ ] Source CSV and three database JSON exports have recorded SHA-256 values.
- [ ] Sheet/database snapshot times and both timezones are recorded.
- [ ] Offline tests pass and `summary.json.import_ready` is true.
- [ ] Offline error count is zero; all warnings/differences are resolved or
  explicitly approved.
- [ ] Stage response matches the reviewed source SHA, timestamp, count, and UUID.
- [ ] Batch status is `validated` before apply and `applied` after apply.
- [ ] Apply actions reconcile to row count.
- [ ] Database reconciliation has zero unexplained differences.
- [ ] `public.exhibitions` count/checksum did not change during Phase 4.
- [ ] `anon` and ordinary `authenticated` roles cannot read Phase 4 previews.
- [ ] Lint, security/performance advisors, pgTAP, admin, and mobile tests pass on
  the staging clone.
- [ ] Missing imported IDs were reported but not archived.
- [ ] Legacy images were neither copied nor represented by fake media metadata.
- [ ] Service-role key was cleared and never entered an artifact or log.
- [ ] Exhibition Apps Script triggers remain enabled for Phase 4, or their later
  controlled-cutover disablement has a successful final report and approval.

## Troubleshooting

| Symptom | Likely cause | Required response |
|---------|--------------|-------------------|
| Bundler exits `2` | Source validation or reconciliation errors | Review `summary.json` and `issues.csv`; fix source/export and create a new run |
| Source checksum changes | File was edited, re-encoded, or incompletely copied | Quarantine it; repeat export and checksum under a new run ID |
| Stage RPC returns `401`/`403` | Wrong target/key or service-role RPC grant absent | Verify project and secret source without logging the key; never fall back to anon |
| Stage RPC returns `404` | Migration not applied or API schema cache is stale | Stop; verify migration history and approved deployment, then refresh through normal operations |
| `blocked_rows` or `error_count` is nonzero | Invalid row, duplicate ID, bad reference, or state conflict | Inspect private rows read-only; correct and restage a new snapshot |
| `orphan_event_id` / `orphan_editor_id` | Target reference table differs from snapshot or source is wrong | Compare timestamp-matched exports to target; repair authoritative reference data, then restage |
| `canonical_draft_exists` | Admin has an active draft | Have its owner publish/discard through the approved workflow; take a fresh snapshot |
| `canonical_changed_since_import` | Admin/import published pointer diverged | Pause both writers, reconcile audit history, and create a new reviewed delta |
| `legacy_import_source_snapshot_not_newer` | A newer or equal full snapshot was already applied | Preserve the old batch, capture a fresh snapshot, and never edit the timestamp |
| `legacy_import_baseline_changed_since_stage` | Another batch was applied after this batch was reviewed | Preserve it as evidence and run a new export/bundle/stage review cycle |
| Apply error `details.row_errors` differs from staged rows | Apply-time revalidation rolled back with the rejected transaction | Trust and preserve the HTTP error artifact; take a fresh snapshot/restage for persisted diagnostics |
| Apply connection drops | Outcome is unknown | Query batch status by exact UUID before retrying; same-UUID replay is idempotent |
| Reconcile shows `only_in_preview` | Prior import/current canonical record is outside this batch | Investigate; absence does not authorize archive. Resolve or ledger exact exception |
| Reconcile shows field mismatch | Nullability, timestamp, flag, media fallback, or source drift | Inspect `differing_fields` and checksums; do not grant public access until resolved |
| Preview is readable by anon | Grant/RLS regression | Block cutover, revoke access through a reviewed migration, rerun security tests/advisors |
| Public legacy checksum changed | Out-of-scope writer or incorrect migration | Stop, preserve evidence, identify writer, restore through approved database recovery |
| Service key appears in output/history | Credential handling failure | Stop, rotate the key, remove exposed artifacts through security procedure, and restart |

## Rollback

### Before apply

- Abandon the staged batch; do not delete or edit its evidence.
- Correct the source and stage a new immutable export.
- No canonical published data has changed.

### After apply but before any public-reader change

- Pause additional imports and admin publishing.
- Continue serving the untouched legacy `public.exhibitions` table.
- Preserve the batch, links, audit log, and reconciliation output for diagnosis.
- Fix through an additive migration or a new reviewed batch. Do not hard-delete
  canonical identities merely to recreate pre-import state.
- Keep both exhibition Apps Script triggers enabled because the legacy reader is
  still authoritative.

### After triggers are disabled but before reader cutover completes

- Keep the editorial freeze in place.
- If no canonical writes occurred after disablement, the change owner may
  re-enable the recorded on-edit and five-minute exhibition triggers.
- If any canonical write occurred, do not blindly re-enable full Sheet sync;
  reconcile the two sources and escalate to the cutover owner.

### After public-reader cutover

- Restore the previously retained legacy reader configuration.
- Pause canonical publishing/imports and keep the Sheet frozen until divergence
  is reconciled.
- Re-enable exhibition triggers only with explicit approval and only after
  confirming which source is authoritative.
- Retain the old public table, source exports, Apps Script, and credentials for
  the full rollback window.
- Image rollback requires no byte operation because Phase 4 preserved legacy
  URLs and did not copy objects.

If a service-role credential may have leaked, credential rotation is mandatory
regardless of data rollback.

## Escalation

In `solo_operator`, the same stable identity may hold every contact role below.
That role map preserves accountability but adds no independent approval. If a
material exception cannot be resolved objectively, stop the run and recruit a
real external reviewer under `separated_humans` rather than assigning an alias
or treating automation as the reviewer.

| Situation | Contact | Method |
|-----------|---------|--------|
| Source mismatch, ambiguous ID, or unresolved field difference | Content migration owner | Change record; attach checksums and row IDs, not secrets |
| Canonical draft/pointer conflict | Admin workflow owner and affected editor | Admin audit trail plus change record |
| Migration, transaction, RLS, or preview-grant failure | Backend/database owner | Incident channel and database change record |
| Potential service-role disclosure | Security owner | Security incident process immediately |
| Reader, mobile pagination, or web rebuild failure | Client/web owner and cutover commander | Cutover incident channel |
| Restore or database corruption required | Database owner | Approved backup/restore procedure; no ad hoc SQL |

## History

| Date | Run By | Notes |
|------|--------|-------|
| 2026-07-23 | Not run | Added explicit separated-human and solo-operator governance; no remote action performed |
| 2026-07-21 | Not run | Initial runbook created; no live export or production change performed |
