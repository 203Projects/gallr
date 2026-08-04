# Production cutover target guard

`assert-production-target.sh` is a read-only, fail-closed attestation for the
future production actions in cutover Gates 4 and 6. It does **not** link a
project, open a database connection, invoke the Supabase CLI, execute `psql`,
create evidence, or change any local or remote state.

The staging operator manifest is consumed only as evidence of the reviewed
commit and exact migration bytes. It is not production authorization. The
guard supports two explicit governance modes:

- With `GALLR_GOVERNANCE_MODE` unset (or set to `separated_humans`), the
  original schema-1 policy and independent-approver workflow remain unchanged.
- `GALLR_GOVERNANCE_MODE=solo_operator` selects the schema-2 workflow for a
  developer who is genuinely operating alone. It records one stable real
  identity and zero human reviewers; it never invents a second identity or
  treats CI, AI, or other automation as independent human review.

Both modes bind the policy to the exact production/staging references,
reviewed commit, complete migration set, operator manifest bytes, change
record, operator identity, and external evidence directory. Solo mode replaces
separation of duties with narrower operations, two target-bound
confirmations, a fixed 30-minute cooldown, a one-hour policy lifetime, and an
explicit acceptance of the remaining single-operator risk.

Solo controls reduce target-selection and sequencing mistakes; they do not
defend against a malicious repository and filesystem owner. The same operator
can replace code, create a new reviewed commit, or deliberately backdate a
file. Record this residual risk honestly instead of presenting automation as a
second reviewer.

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

The URI parser validates the `sslrootcert` path syntax but does not read or
fingerprint the certificate file. Verify its bytes separately against the
fingerprint in the change record; a guard `PASS` is not certificate-byte
attestation.

In `separated_humans` mode, policy preparation is an independent approval step.
The cutover executor must not create or edit the approved policy. In
`solo_operator` mode, the operator creates the self-attested policy, makes it
read-only, and then waits for the cooldown. Mode `0400` is tamper resistance,
not a cryptographic signature; retain the authoritative approval or solo risk
acceptance in the change-management system as well.

The guard must remain at its checked-in repository path. Repository discovery
is derived from that path and performed with inherited Git routing/configuration
disabled. Both the guard and its database-URL validator must be tracked regular
files with one hard link, and their current SHA-256 bytes must exactly match the
reviewed `HEAD` commit. The URL parser is executed from that reviewed Git blob,
not from a mutable worktree path. The checkout must then be completely clean.

## Schema-1 policy (`separated_humans`, default)

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

Executor and approver identities are compared case-insensitively. Changing
only letter case does not create an independent person.

## Schema-2 policy (`solo_operator`)

Solo mode is explicit and accepts exactly these 19 nonempty lines (no comments
or blank lines):

```text
policy_schema=2
policy_kind=gallr_production_cutover
governance_mode=solo_operator
target=production
approval_status=self_attested
authorized_gate=gate4
authorized_operation=additive_database_deploy
destructive_actions=forbidden
issued_at_utc=<UTC timestamp with exact second precision>
valid_until_utc=<UTC timestamp no more than one hour after issued_at_utc>
minimum_cooldown_seconds=1800
production_project_ref_sha256=<sha256 of exact production ref, no newline>
staging_project_ref_sha256=<sha256 of exact staging ref, no newline>
repository_commit=<full reviewed commit>
operator_manifest_sha256=<sha256 of exact schema-2 operator-manifest.txt bytes>
change_record_sha256=<sha256 of exact change-record string, no newline>
operator_identity_sha256=<sha256 of the one stable operator identity, no newline>
evidence_directory_sha256=<sha256 of canonical evidence path, no newline>
first_confirmation_sha256=<sha256 of the exact INTENT literal below, no newline>
```

The allowed gate-to-operation mapping is fixed in reviewed code:

- `gate4` → `additive_database_deploy`
- `gate6` → `ownership_transfer`

No other operation, including legacy retirement, can be named in this policy.
For Gate 4, type the first confirmation while preparing the policy:

```text
INTENT PRODUCTION <production-ref> NOT STAGING <staging-ref> gate4 additive_database_deploy <reviewed-commit>
```

Store only its SHA-256 in `first_confirmation_sha256`. Seal the policy at mode
`0400`. Both `issued_at_utc` and the policy file's actual modification time
must be at least 1,800 seconds old when the guard runs. The guard does not sleep
or backdate the file; wait 30 minutes. Copying or rewriting the policy starts a
new file-mtime cooldown. The policy must still be unexpired, and
`valid_until_utc - issued_at_utc` cannot exceed one hour.

The schema-2 staging operator manifest must also disclose
`governance_mode=solo_operator`, `human_reviewer_count=0`,
`automation_is_independent_human_review=false`, and the accepted staging risk
controls. `GALLR_PRODUCTION_APPROVER` must be completely unset in solo mode.

## Exact Gate 4 integration

For the temporary Seoul-to-Singapore legacy-mobile migration only, generate
one manifest per target with
`GALLR_PRODUCTION_TARGET_MODE=legacy_mobile_catalog_pair`. Each manifest must
name the other reviewed project as the excluded ref. The production guard
rechecks the two committed trust anchors and rejects a third project, a repeated
project, an unknown mode, or reuse of the pair manifest by staging tooling.

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

## Solo Gate 4 integration

Prepare and seal a fresh schema-2 Gate 4 policy, then wait the full 30 minutes.
Use the same common target, database, manifest, evidence, commit, change-record,
and executor variables shown above, but remove the approver and select solo
mode:

```sh
export GALLR_GOVERNANCE_MODE='solo_operator'
unset GALLR_PRODUCTION_APPROVER
unset GALLR_PRODUCTION_CONFIRMATION
export GALLR_PRODUCTION_POLICY_FILE='/absolute/external/policy/gate4-solo-policy.txt'

BASH_ENV=/dev/null ENV=/dev/null \
  /bin/bash --noprofile --norc \
    ./scripts/production-cutover/assert-production-target.sh gate4
```

Only after the guard validates the policy cooldown does it prompt for this
exact action-time literal; type it manually rather than exporting it early:

```text
EXECUTE PRODUCTION <production-ref> NOT STAGING <staging-ref> gate4 additive_database_deploy <reviewed-commit>
```

Solo execution confirmation must come from interactive terminal stdin. A pipe,
redirected file, FIFO, or `/dev/null` is rejected before the guard can emit
`PASS`. The cooldown evaluator also ignores inherited `PATH` entries: Node.js
must be installed at `/usr/bin/node`, `/bin/node`, `/usr/local/bin/node`, or
`/opt/homebrew/bin/node`.

## Solo Gate 6 integration

Gate 6 requires a new schema-2 policy with
`authorized_gate=gate6`, `authorized_operation=ownership_transfer`, new
timestamps, the Gate 6 INTENT literal, and a fresh 30-minute cooldown:

```text
INTENT PRODUCTION <production-ref> NOT STAGING <staging-ref> gate6 ownership_transfer <reviewed-commit>
```

After the cooldown, type the second confirmation and run the guard:

```sh
export GALLR_PRODUCTION_POLICY_FILE='/absolute/external/policy/gate6-solo-policy.txt'
unset GALLR_PRODUCTION_CONFIRMATION

BASH_ENV=/dev/null ENV=/dev/null \
  /bin/bash --noprofile --norc \
    ./scripts/production-cutover/assert-production-target.sh gate6
```

At the post-cooldown prompt, type:

```text
EXECUTE PRODUCTION <production-ref> NOT STAGING <staging-ref> gate6 ownership_transfer <reviewed-commit>
```

## What `PASS` does and does not authorize

The guard is read-only. It does not execute, inspect, or wrap the production
command that follows it. Consequently, `destructive_actions=forbidden` is a
reviewed intent boundary, not technical command sandboxing. Run only the exact
additive Gate 4 or ownership-transfer Gate 6 command recorded in the change
record, immediately after a matching `PASS` and without changing any input.

No Gate 4 or Gate 6 `PASS` authorizes deleting the Google Sheet workflow,
dropping legacy database objects, running a retirement migration, or performing
any other destructive cleanup. Legacy retirement requires successful migration
verification, a separate change record, a separate reviewed commit and guard,
fresh restore evidence, and its own longer waiting period.

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
