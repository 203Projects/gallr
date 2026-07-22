# Staging rehearsal preflight

`preflight.sh` prepares local evidence for the exhibition-catalog staging-clone
rehearsal. It is deliberately incapable of linking a Supabase project, querying
a database, pushing migrations, or calling a remote endpoint. Supabase CLI use
is restricted to version and `--help` inspection.

The helper fails unless the staging and production project references are
distinct, the current commit is the explicitly reviewed commit, and every
database, importer, admin, reader, test, runbook, and environment-template file
needed by the rehearsal is tracked and clean. Project-reference values are
never printed or written; the manifest records only their SHA-256 fingerprints.

## Run

Choose an existing secure parent directory outside the repository. The named
run directory may be absent or empty; the helper creates it if needed, changes
its mode to `0700`, and refuses symlinks, repository paths, or nonempty
directories.

```sh
export GALLR_EXPECTED_STAGING_PROJECT_REF='<20-character-staging-ref>'
export GALLR_PRODUCTION_PROJECT_REF='<20-character-production-ref>'
export GALLR_STAGING_EVIDENCE_DIR='/absolute/secure/path/20260721T120000Z-staging'
export GALLR_REVIEWED_COMMIT="$(git rev-parse HEAD)"
export GALLR_CHANGE_RECORD='<approved-change-record>'
export GALLR_EXECUTOR='<executor-name>'
export GALLR_REVIEWER='<different-reviewer-name>'
export GALLR_REHEARSAL_RUN_ID='20260721T120000Z-staging' # optional

./scripts/staging-rehearsal/run-safe-bash.sh \
  scripts/staging-rehearsal/preflight.sh
```

Do not load service-role or database credentials merely to run this helper.
The manifest reports only whether the future remote variable names are present;
it never reads or records their values.

## Output

The external evidence directory receives two read-only files:

- `operator-manifest.txt` — commit, cached branch divergence, target
  fingerprints, CLI contract, required environment-name contracts, and one
  SHA-256 value for every tracked migration;
- `rehearsal-plan.txt` — ordered, non-executable instructions for the later
  authorized staging session and its stop conditions.

The output is preflight evidence, not proof that a clone exists or that a
remote migration succeeded. A later operator must verify the target again,
capture linked migration history, review a dry run, measure locks, and retain
the database/reconciliation evidence required by the cutover runbook.

## Independent disposable-clone identity

Before any staging mutation, follow [`TARGET-IDENTITY.md`](TARGET-IDENTITY.md).
An identity operator other than the executor prepares a two-approver,
mode-`0400` policy outside the repository and installs its expiring marker on
the disposable clone only. Then load:

```sh
export GALLR_STAGING_IDENTITY_POLICY_PATH='/absolute/secure/identity-policy.txt'
./scripts/staging-rehearsal/run-safe-bash.sh \
  scripts/staging-rehearsal/assert-disposable-clone-target.sh
```

Invoke `run-safe-bash.sh` directly, never through `bash` or by sourcing it. Its
privileged, no-profile/no-rc Bash child discards `BASH_ENV`, `ENV`, `CDPATH`,
shell-option exports, and exported functions before a credential-bearing
staging entrypoint starts.

The check binds the reviewed commit, exact operator-manifest bytes, two target
fingerprints, linked project, direct database URL, independent approvers, and
the database-resident marker. Fixture provision/cleanup, the queued-writer
activation, the anonymous write-denial coordinator, the rollback-only legacy
writer probe, and the PostgREST mutation case invoke this check themselves.
Migration pushes, import application, and admin mutation sessions must invoke
it immediately before their first write. Read-only evidence commands still use
the manifest-bound linked-target guard.

## Local validation

Syntax-check without running it:

```sh
sh -n scripts/staging-rehearsal/preflight.sh
sh -n scripts/staging-rehearsal/run-safe-bash.sh
bash scripts/staging-rehearsal/tests/run-safe-bash.test.sh
```

A behavioral test should use two fake but syntactically valid, distinct project
references and a fresh directory under a temporary parent. Run it only from a
clean test checkout whose required artifacts are committed. Confirm that the
two reference strings occur nowhere in either output file and that the evidence
directory mode is `0700`. Using the real workspace before its required files
are committed must fail closed.

## Database evidence after preflight

The remaining files are intentionally separate from `preflight.sh` because
they do connect to the approved staging clone. Run them only after the manifest
has been reviewed and the linked project reference has been checked again.
Load `GALLR_STAGING_DATABASE_URL` from the approved secret manager without
printing it. Before doing so, download the clone's server root certificate from
**Database Settings → SSL Configuration**, store it outside the repository as
`0400` or `0600`, and verify its approved SHA-256 fingerprint. The URI must
contain exactly
`sslmode=verify-full&sslrootcert=%2Fabsolute%2Fexternal%2Fsupabase-ca.crt`;
`sslmode=require`, `sslmode=verify-ca`, a relative certificate path, duplicate
fields, or missing TLS fields fail closed. Set
`GALLR_STAGING_REHEARSAL_CONFIRM` to the exact expected staging ref, retain
`GALLR_STAGING_IDENTITY_POLICY_PATH` for the rollback-only writer probe, and
use the guarded coordinator:

```sh
./scripts/staging-rehearsal/run-safe-bash.sh \
  scripts/staging-rehearsal/run-database-evidence.sh pre-migration
./scripts/staging-rehearsal/run-safe-bash.sh \
  scripts/staging-rehearsal/run-database-evidence.sh observe-locks
GALLR_LEGACY_PROBE_EXHIBITION_ID='<existing-legacy-exhibition-id>' \
  ./scripts/staging-rehearsal/run-safe-bash.sh \
    scripts/staging-rehearsal/run-migration-writer-probe.sh
./scripts/staging-rehearsal/run-safe-bash.sh \
  scripts/staging-rehearsal/run-database-evidence.sh post-migration
./scripts/staging-rehearsal/run-safe-bash.sh \
  scripts/staging-rehearsal/run-database-evidence.sh post-import
./scripts/staging-rehearsal/run-safe-bash.sh \
  scripts/staging-rehearsal/run-database-evidence.sh final-representative
```

`pre-migration` creates evidence schema 2 and writes
`evidence_success=pre-migration` only after its read-only `psql` session exits
successfully. The result is a regular, operator-owned, single-link mode-`0400`
file. Both `post-migration` and `post-import` refuse to connect unless those
exact pre-migration bytes remain sealed and contain one valid legacy payload
hash plus the current repository commit, operator-manifest hash, target
fingerprints, runner hash, and pre-migration SQL hash. Their own evidence records
`pre_migration_evidence_sha256`, cryptographically binding each later result to
the validated before-state file. If the commit, manifest, runner, or SQL changes,
review the change and capture a new pre-migration baseline on a refreshed clone;
never edit or loosen the old evidence.

The lock observer is long-running; start it in a second session immediately
before the reviewed migration push and stop it only after the push finishes.
Start the writer probe in a third session over a direct database connection.
Every probe transaction performs an unchanged update as `service_role` and
then rolls it back. Correlate an iteration's `elapsed_ms` and backend PID with
the observer and migration timestamps; the observer alone does not prove how
long a representative writer waited. Stop the probe with Ctrl-C after the push.
The before/after scripts use repeatable-read, read-only transactions. Run
`final-representative` only after the canonical-owned admin lifecycle and
1,205-row fixtures are present; unlike the earlier phases, it requires every
representative matrix category to be non-zero. The
post-migration phase exits non-zero on missing catalog objects, projection
membership drift, failed field/checksum reconciliation, or incorrect anonymous
grants. Early post-migration/import matrices are inventory only and may contain
intentional zeros.

The evidence-chain guard is covered without a database or network connection:

```sh
scripts/staging-rehearsal/tests/database-evidence-chain.test.sh
```

For behavioral access evidence, set the already-approved staging/production
references, database URL, external evidence directory, and an exact staging
confirmation, then run:

```sh
export GALLR_STAGING_REHEARSAL_CONFIRM="${GALLR_EXPECTED_STAGING_PROJECT_REF}"
./scripts/staging-rehearsal/run-safe-bash.sh \
  scripts/staging-rehearsal/run-anonymous-access-checks.sh
```

That coordinator keeps the credential-bearing URL out of the `psql` argument
list, opens independent database sessions, and fails unless the allowed public
read succeeds while both the private-schema read and catalog write return
SQLSTATE `42501`. It refuses to overwrite existing evidence.

## Fixture and PostgREST evidence

After canonical ownership and the admin lifecycle pass, use
[`fixtures/README.md`](fixtures/README.md) to provision the exact 1,205-row
fixture. Then run the manifest-derived coordinator instead of transcribing
event IDs or cursors:

```sh
./scripts/staging-rehearsal/run-safe-bash.sh \
  scripts/staging-rehearsal/run-postgrest-evidence.sh catalog
./scripts/staging-rehearsal/run-safe-bash.sh \
  scripts/staging-rehearsal/run-postgrest-evidence.sh event
./scripts/staging-rehearsal/run-safe-bash.sh \
  scripts/staging-rehearsal/run-postgrest-evidence.sh featured
./scripts/staging-rehearsal/run-safe-bash.sh \
  scripts/staging-rehearsal/run-postgrest-evidence.sh empty
./scripts/staging-rehearsal/run-safe-bash.sh \
  scripts/staging-rehearsal/run-postgrest-evidence.sh mutation
```

The first four cases are API-read-only. The mutation case additionally requires
the direct database URL, sealed identity policy, exact hook SHA-256, and staging
fixture attestation; it verifies the independent marker before running the
credential-sanitized hook. Every case refuses to overwrite evidence and seals
complete or partial output mode `0400`.
