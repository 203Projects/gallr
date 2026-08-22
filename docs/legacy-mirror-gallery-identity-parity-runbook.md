# Legacy mirror gallery-identity parity rollout

Restores Seoul-to-Singapore catalogue parity after
`20260814010000_public_gallery_identity.sql` added
`public.exhibition_catalog_v2.gallery_id` to Seoul only.

This runbook covers one specific, bounded change. It does not authorize any
other production operation, credential change, or legacy retirement.

## Why this is needed

The canonical integrity checksum is derived from
`to_jsonb(row) - 'content_checksum_sha256'`, so it covers every column of the
row. With `gallery_id` present on Seoul and absent on the compatibility
project, the two projects hash different row shapes. Every mirrored row fails
the receiver's comparison with
`legacy_mobile_catalog_canonical_v2_checksum_mismatch`, the guarded apply
aborts, and the receiver answers `HTTP 502`.

Observed impact before the fix:

| Signal | Value |
| --- | --- |
| Seoul exhibitions / canonical rows | 336 / 336 |
| Singapore exhibitions / canonical rows | 328 / 328 |
| Singapore newest `updated_at` | 2026-08-13T23:56Z |
| Seoul newest `updated_at` | 2026-08-18T13:23Z |
| Dead-lettered `legacy_catalog.sync_requested` | 24 (5/5 attempts, HTTP 502) |
| pg_net 502 responses, trailing 12h | 72 |

Installed pre-1.7.7 mobile clients read the compatibility project, so they were
served a five-day-stale catalogue. The guard behaved correctly by refusing to
publish an unverifiable snapshot; the defect is the row-shape divergence, not
the guard.

## Scope

Applies `20260819104500_legacy_mobile_gallery_identity_parity.sql` to both
projects in the pair, then deploys the updated `legacy-catalog-mirror`
coordinator to Seoul.

**Order is mandatory: migration to both projects first, coordinator second.**
The updated coordinator sends `gallery_id`. Deploying it before the
compatibility project has the column makes every apply fail on an unknown
field, converting a checksum failure into a schema failure without improving
availability.

The migration is additive and safe on both projects. On Seoul the column and
its trigger already exist, every statement is guarded (`add column if not
exists`, `create or replace`), and the target-replacement RPC is server-only
and disabled there, so applying it is inert apart from refreshing the function
body. On the compatibility project it installs the column and the widened
contract.

Not in scope: enabling or disabling any mirror configuration flag, changing
`legacy_writes_blocked`, rotating credentials, retiring the compatibility
project, or any mobile release.

## Preconditions

1. Reviewed commit is merged to `develop` and the checkout is completely clean.
2. `node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs` passes.
3. The database CI gate is green on the reviewed commit: 41/41 pgTAP files,
   `supabase db lint`, and `supabase db advisors --type security`.
4. `deno task test` and `deno task check` pass in
   `supabase/functions/legacy-catalog-mirror`.
5. A change record exists. This runbook was prepared under
   `CR-GALLR-MIRROR-PARITY-20260819`.

## Step 1 — Pair manifests

Generate one manifest per direction from the reviewed, clean checkout. The
preflight is network-free: it cannot link a project, query a database, or
contact a remote endpoint. Each manifest names the other reviewed project as
the excluded ref, and records fingerprints rather than project references.

Run once with Seoul as the target and Singapore excluded, then once with the
opposite assignment. Keep evidence outside the repository in a mode-`0700`
directory.

```sh
export GALLR_PRODUCTION_TARGET_MODE=legacy_mobile_catalog_pair
export GALLR_GOVERNANCE_MODE=solo_operator            # only if genuinely alone
export GALLR_REVIEWED_COMMIT="$(git rev-parse HEAD)"
export GALLR_CHANGE_RECORD='CR-GALLR-MIRROR-PARITY-20260819'
export GALLR_EXECUTOR='<stable real identity>'
export GALLR_REVIEWER="$GALLR_EXECUTOR"               # solo mode only
export GALLR_STAGING_EVIDENCE_DIR='/absolute/external/evidence/<direction>'
export GALLR_REVIEWED_NODE_PATH='<canonical, non-symlink node path>'
export GALLR_REVIEWED_PSQL_PATH='<canonical, non-symlink psql path>'
export GALLR_PRODUCTION_PROJECT_REF='<target ref for this direction>'
export GALLR_EXPECTED_STAGING_PROJECT_REF='<the other ref>'
export GALLR_SOLO_OPERATOR_FIRST_CONFIRMATION="INTENT STAGING ${GALLR_EXPECTED_STAGING_PROJECT_REF} NOT PRODUCTION ${GALLR_PRODUCTION_PROJECT_REF} ${GALLR_REVIEWED_COMMIT} ACCEPT_NO_INDEPENDENT_REVIEW"

BASH_ENV=/dev/null ENV=/dev/null \
  /bin/bash --noprofile --norc \
    ./scripts/staging-rehearsal/preflight.sh
```

Toolchain paths must be canonical. `/opt/homebrew/bin/node` is a symlink and is
rejected; pass the resolved Cellar path. Confirm each manifest is mode `0400`
or `0444` with exactly one hard link, and that it records
`production_target_mode=legacy_mobile_catalog_pair`.

A manifest is evidence of the reviewed commit and migration bytes. **It is not
production authorization**, and it is rejected by credential-bearing staging
runners.

## Step 2 — Apply the migration to both projects

For each project, re-run the production guard immediately before the command.
A prior `PASS` is not a reusable token.

```sh
BASH_ENV=/dev/null ENV=/dev/null \
  /bin/bash --noprofile --norc \
    ./scripts/production-cutover/assert-production-target.sh gate4
```

Proceed only on `PASS`, in the same shell, without changing the checkout, link,
inputs, or evidence directory. Apply the migration to the compatibility project
first so the column exists before Seoul's coordinator can possibly send it.

Verify on each project after applying:

```sql
select count(*) = 1 as column_present
from information_schema.columns
where table_schema = 'public'
  and table_name = 'exhibition_catalog_v2'
  and column_name = 'gallery_id';
```

Both projects must return `true` before continuing. The migration also clears
`last_snapshot_sha256`, so the next scheduled pass reapplies the current
authoritative snapshot instead of reporting `unchanged`.

## Step 3 — Deploy the coordinator

Deploy `legacy-catalog-mirror` to Seoul only. The Singapore receiver is
protocol-compatible and does not need redeployment.

## Step 4 — Verify

Within roughly five minutes the scheduled reconciliation should apply. Confirm
with the read-only parity watchdog:

```sh
env \
  GALLR_SEOUL_SUPABASE_URL='op://DEV/gallr-korea-server/hostname' \
  GALLR_SEOUL_SECRET_KEY='op://DEV/gallr-korea-server/credential' \
  GALLR_LEGACY_SUPABASE_URL='op://DEV/gallr-production-server/hostname' \
  GALLR_LEGACY_SECRET_KEY='op://DEV/gallr-production-server/credential' \
  op run -- node scripts/legacy-mobile-mirror/check-mirror-parity.mjs
```

Expected: exit `0` and `MIRROR OK`. Before the fix this command exits `1` and
names the diverging resources.

Then confirm the operator dry run reports zero inserts, updates, and deletes
for all four resources, per
[`scripts/legacy-mobile-mirror/README.md`](../scripts/legacy-mobile-mirror/README.md).

Also confirm on Seoul that no new `legacy_catalog.sync_requested` rows are
failing. The 24 rows already dead-lettered will not retry on their own; the
five-minute reconciliation repairs the catalogue independently of them, so
treat them as historical evidence rather than a queue to drain.

## Stop conditions

Stop and reassess rather than working around any of these:

- The preflight or production guard prints anything other than `PASS`.
- The compatibility project does not report `column_present = true` before the
  coordinator deploy.
- The parity watchdog still exits non-zero 15 minutes after the deploy.
- A dry run proposes deletions. Investigate; do not raise
  `max_delete_fraction` reflexively.
- The receiver returns a checksum mismatch after both projects have the
  column. That means a third column has diverged; re-run the column-set
  comparison across both projects before retrying.

## Rollback

The migration is additive and the column is nullable on the compatibility
project, so the safe rollback is forward: redeploy the previous coordinator
build, which omits `gallery_id`. The apply then fails closed on the new
`legacy_mobile_catalog_gallery_identity_is_missing` guard rather than writing
nulls, returning the system to the pre-rollout stalled state without
corrupting the mirror. Do not drop the column to roll back; dropping it
re-creates the original row-shape divergence.

## After rollout

Schedule the parity watchdog every 15 minutes and alert on two consecutive
non-zero exits. The August 2026 freeze lasted five days precisely because a
silent five-minute failure loop had no comparison check watching it.

Any future column added to `public.exhibition_catalog_v2` must ship to both
projects and the snapshot in the same rollout. See the row-shape parity section
in [`scripts/legacy-mobile-mirror/README.md`](../scripts/legacy-mobile-mirror/README.md).
