# Production cutover target guard

`assert-production-target.sh` is a read-only, fail-closed attestation for the
future production actions in cutover Gates 4 and 6. It does **not** link a
project, open a database connection, invoke the Supabase CLI, execute `psql`,
create evidence, or change any local or remote state.

The staging operator manifest is consumed only as evidence of the reviewed
commit and exact migration bytes. It is not production authorization. A
different person must prepare a separate production policy artifact for each
gate. The guard binds that policy to the exact production/staging references,
reviewed commit, complete migration set, operator manifest bytes, change
record, executor, approver, and external evidence directory.

## Required external inputs

- An operator manifest created by the staging preflight, outside the repository,
  mode `0400` or `0444`, with exactly one hard link.
- A pre-existing production evidence directory outside the repository, owned by
  the operator and mode `0700`.
- A production policy in a different external mode-`0700` directory, owned by
  the operator. The policy itself must be a non-symlink regular file with exact
  mode `0400` and exactly one hard link; it must not be inside the run evidence
  directory.
- The Supabase server root certificate downloaded from the production
  project's **Database Settings → SSL Configuration** panel, stored outside
  the repository with its approved SHA-256 fingerprint recorded in the change
  record. Keep the certificate path absolute and protect the file as `0400` or
  `0600`.
- A direct database URI of the form
  `postgresql://postgres:<password>@db.<production-ref>.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=%2Fabsolute%2Fexternal%2Fsupabase-ca.crt`.
  Pooler URLs, alternate databases/ports/users, missing passwords,
  `sslmode=require`, `sslmode=verify-ca`, relative certificate paths,
  fragments, duplicate fields, and query overrides are rejected. The URI is
  parsed but never used to connect and is never printed or passed in argv.
  `verify-full` is mandatory so libpq verifies both the CA and the exact
  `db.<production-ref>.supabase.co` hostname.

The policy preparation is an independent approval step. The cutover executor
must not create or edit the approved policy. Mode `0400` is tamper resistance,
not a cryptographic signature; retain the authoritative approval in the change
management system as well.

The guard must remain at its checked-in repository path. Repository discovery
is derived from that path and performed with inherited Git routing/configuration
disabled. Both the guard and its database-URL validator must be tracked regular
files with one hard link, and their current SHA-256 bytes must exactly match the
reviewed `HEAD` commit. The URL parser is executed from that reviewed Git blob,
not from a mutable worktree path. The checkout must then be completely clean.

## Policy format

The independent approver creates one policy for `gate4` and a new policy for
`gate6`. It contains exactly these 12 nonempty lines (no comments or blank
lines):

```text
policy_schema=1
target=production
approval_status=approved
authorized_gate=gate4
production_project_ref_sha256=<sha256 of exact production ref, no newline>
staging_project_ref_sha256=<sha256 of exact staging ref, no newline>
repository_commit=<full reviewed commit>
operator_manifest_sha256=<sha256 of exact operator-manifest.txt bytes>
change_record_sha256=<sha256 of exact change-record string, no newline>
executor_sha256=<sha256 of exact executor identity, no newline>
approver_sha256=<sha256 of exact independent approver identity, no newline>
evidence_directory_sha256=<sha256 of canonical evidence path, no newline>
```

The `gate6` artifact uses `authorized_gate=gate6`. Use `printf '%s'` (not
`echo`) when hashing text fields. On macOS, the SHA-256 forms are:

```sh
printf '%s' "$GALLR_PRODUCTION_PROJECT_REF" | shasum -a 256
shasum -a 256 "$GALLR_OPERATOR_MANIFEST"
```

The independent approver should build the file in their secure workflow,
verify all 12 values against the approved change record, transfer it through
the approved channel, and set the received artifact to `0400`. Do not generate
it in the repository or in the production run evidence directory.

## Exact Gate 4 integration

From the reviewed, completely clean checkout, after a separately authorized
operator has deliberately linked the CLI to production, export the inputs
without printing the database URI:

```sh
export GALLR_EXPECTED_STAGING_PROJECT_REF='<exact-20-character-staging-ref>'
export GALLR_PRODUCTION_PROJECT_REF='<exact-20-character-production-ref>'
export GALLR_PRODUCTION_DATABASE_URL='<direct-production-postgres-uri>'
export GALLR_OPERATOR_MANIFEST='/absolute/external/staging-evidence/operator-manifest.txt'
export GALLR_PRODUCTION_POLICY_FILE='/absolute/external/policy/gate4-policy.txt'
export GALLR_PRODUCTION_EVIDENCE_DIR='/absolute/external/production-evidence'
export GALLR_REVIEWED_COMMIT="$(git rev-parse HEAD)"
export GALLR_CHANGE_RECORD='<exact-approved-change-record>'
export GALLR_PRODUCTION_EXECUTOR='<executor-identity>'
export GALLR_PRODUCTION_APPROVER='<different-approver-identity>'
export GALLR_PRODUCTION_CONFIRMATION="PRODUCTION ${GALLR_PRODUCTION_PROJECT_REF} gate4 ${GALLR_REVIEWED_COMMIT}"

BASH_ENV=/dev/null ENV=/dev/null \
  /bin/bash --noprofile --norc \
    ./scripts/production-cutover/assert-production-target.sh gate4
```

Proceed with the separately reviewed Gate 4 command only if the guard prints
`PASS`, in the same shell and without changing the checkout, link, inputs, or
evidence directory. Re-run the guard immediately before every production
command; a prior pass is not a reusable token.

## Exact Gate 6 integration

Obtain a new independent policy bound to `authorized_gate=gate6`, then change
only the policy path and typed confirmation:

```sh
export GALLR_PRODUCTION_POLICY_FILE='/absolute/external/policy/gate6-policy.txt'
export GALLR_PRODUCTION_CONFIRMATION="PRODUCTION ${GALLR_PRODUCTION_PROJECT_REF} gate6 ${GALLR_REVIEWED_COMMIT}"

BASH_ENV=/dev/null ENV=/dev/null \
  /bin/bash --noprofile --norc \
    ./scripts/production-cutover/assert-production-target.sh gate6
```

Proceed with the separately reviewed Gate 6 command only after that new guard
pass. The guard intentionally refuses a Gate 4 policy at Gate 6.

## Network-free verification

These tests use only temporary local Git repositories and syntactically fake
project references/URIs. They install tripwire commands that fail if `supabase`,
`psql`, or `curl` is invoked. They also inject hostile Git selectors/config,
interpreter options, hard links, relocated guards, and unreviewed guard/parser
bytes to verify that each condition fails closed or is safely isolated.

```sh
node scripts/production-cutover/lib/validate-production-database-target.test.mjs
bash scripts/production-cutover/tests/assert-production-target.test.sh
bash -n scripts/production-cutover/assert-production-target.sh
```
