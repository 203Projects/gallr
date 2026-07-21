# Staging queued-writer coordinator

This gate proves the final ownership boundary on a **disposable, populated
staging clone**. A `service_role` update is allowed while Sheet ownership is
still active, queues behind the exact locks held by
`admin_enable_legacy_exhibition_mirror`, and must fail after activation commits.

The coordinator is intentionally one-way. It never rewrites private runtime
flags, restores legacy grants, changes or deletes audit events, deletes a test
row, calls `pg_terminate_backend`, or touches unrelated sessions. On shell exit
it stops only the `psql` clients that it launched, allowing their in-flight
transactions to roll back. A failure after activation starts leaves the clone
and the external evidence directory intact and requires clone restoration.

## Ordering

Run this as Gate 2 on the clean populated clone, before provisioning the 1,205
transport fixtures. Fixture provisioning intentionally changes canonical/V2
counts and would break the global canonical/V2/legacy parity required for safe
bridge activation unless legacy projection were already active.

## Prerequisites

- the deployed V2/bridge migrations are present;
- the Apps Script/Sheet writer is stopped;
- runtime is exactly Sheet-owned: `(mirror=false, writes_blocked=false)`;
- canonical, V2, and legacy payloads are globally identical;
- the supplied target ID exists exactly once as a published canonical, V2, and
  legacy exhibition;
- the checkout is clean, linked to the exact reviewed staging ref, and still at
  the commit recorded in preflight's `operator-manifest.txt`;
- the external two-approver identity policy is mode `0400`, unexpired, and its
  exact marker is installed only on this disposable clone;
- the direct `db.<ref>.supabase.co` database URI belongs to that staging ref and
  is different from production (pooler URIs are rejected because this proof
  coordinates exact backend sessions through `pg_stat_activity`);
- `psql` is installed;
- `GALLR_CONCURRENCY_EVIDENCE_DIR` is the external preflight directory that
  contains the reviewed mode-`0444` `operator-manifest.txt`, is owned by the
  operator, and has mode `0700`.

Use a fresh run ID and an approval reason that includes it:

```sh
export GALLR_EXPECTED_STAGING_PROJECT_REF='aaaaaaaaaaaaaaaaaaaa'
export GALLR_PRODUCTION_PROJECT_REF='bbbbbbbbbbbbbbbbbbbb'
export GALLR_STAGING_DATABASE_URL='postgresql://postgres:REDACTED@db.aaaaaaaaaaaaaaaaaaaa.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=%2Fabsolute%2Fexternal%2Fsupabase-ca.crt'
export GALLR_STAGING_REHEARSAL_CONFIRM="$GALLR_EXPECTED_STAGING_PROJECT_REF"
export GALLR_STAGING_IDENTITY_POLICY_PATH='/absolute/private/identity-policy.txt'
export GALLR_CONCURRENCY_EVIDENCE_DIR='/absolute/private/staging-evidence'
export GALLR_CONCURRENCY_RUN_ID='bridge-20260721-01'
export GALLR_CONCURRENCY_APPROVAL_REASON='approved staging bridge rehearsal bridge-20260721-01'
export GALLR_CONCURRENCY_TARGET_EXHIBITION_ID='known-published-exhibition-id'

./scripts/staging-rehearsal/run-safe-bash.sh \
  scripts/staging-rehearsal/concurrency/run.sh
```

Invoke the launcher directly, not through `bash` and not by sourcing it. It
starts the credential-bearing coordinator in privileged Bash with profiles,
rc files, startup-environment files, and exported functions disabled.

The URI is provided to `psql` through `PGDATABASE`; it is never placed in its
argument list or written to the manifest. An exact shared URI parser validates
the direct hostname and database user for the expected project ref, rejects
pooler connections and connection-parameter overrides, and forces
`sslmode=verify-full` with the absolute approved CA certificate path.
Project refs are recorded only as SHA-256 fingerprints. Each run gets a new
`0700` directory containing retained identity, event manifest,
preflight/postflight TSVs, and complete client logs. Any pre-existing run
directory or dangling evidence path is refused. Git routing and configuration
overrides are removed before resolving `HEAD`, and that commit must exactly
match the single `repository_commit` in the operator manifest. The linked-target
guard then binds the refs, linked CLI project, clean commit, bridge migration,
and database target to that reviewed manifest. Immediately before the first
preflight database query, the independent identity gate rechecks that link and
validates the direct URL, policy, and database-resident marker.

The coordinator installs its failure trap as soon as the private run directory
exists. `fail_closed_guards_passed` is recorded only after both target guards
succeed. Failed runs retain and seal the regular evidence created before the
failure. Successful runs must contain the exact expected evidence inventory;
any extra name, symlink, directory, FIFO, non-owned entry, non-regular entry, or
missing expected file makes the run fail instead of being silently skipped.
Every accepted evidence file, including `target-identity-guard.log`, is sealed
mode `0400` on exit.

On success, keep the evidence and continue directly to the approved
canonical-owned gates; the 1,205 transport fixture provisioning is the next
gate. Restore the entire staging clone only after the rehearsal is complete.
If this coordinator fails after activation starts, restore the clone before
retrying. Do not use the local
`supabase/tests/exhibition_catalog_v2_concurrency.sh` against staging: that
local-only regression restores grants/runtime and deletes its audit fixture.

## Network-free checks

```sh
bash -n scripts/staging-rehearsal/concurrency/*.sh
bash scripts/staging-rehearsal/concurrency/tests/guards.test.sh
```
