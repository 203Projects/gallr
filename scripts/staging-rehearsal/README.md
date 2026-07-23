# Staging rehearsal preflight

`preflight.sh` prepares local evidence for the exhibition-catalog staging-clone
rehearsal. It is deliberately incapable of linking a Supabase project, querying
a database, pushing migrations, or calling a remote endpoint. Supabase CLI use
is restricted to version and `--help` inspection.

The helper fails unless the staging and production project references are
distinct, the current commit is the explicitly reviewed commit, and every
database, importer, admin, reader, test, runbook, and environment-template file
needed by the rehearsal is tracked and clean. For the complete protected scope,
preflight compares reviewed tree blobs and modes to the index, hashes literal
worktree bytes with Git filters disabled, and rejects non-ignored untracked
files. It does not use filter-aware porcelain status. A repository-local
`core.worktree` redirect or nonempty `info/grafts` file fails closed.
Project-reference values are never printed or written; the manifest records
only their SHA-256 fingerprints.
The supplied production ref must also match the reviewed digest in
`production-project-ref.sha256`, and the staging ref must not match it. Changing
that trust anchor is a production-safety change that requires its own review,
commit, fresh preflight, and fresh authorization.

## Governance profiles

`GALLR_GOVERNANCE_MODE` defaults to `separated_humans`. That profile keeps the
existing requirement for a real executor and a different real reviewer. Set it
to `solo_operator` only when one person genuinely holds every operational
responsibility. Solo mode uses one stable identity in both compatibility fields,
records `human_reviewer_count=0`, and explicitly records that automation, CI,
and AI are not independent human review. Do not invent aliases or use spelling
or capitalization changes to simulate another person.

Solo mode adds two target-bound confirmations separated by a fixed 15-minute
cooldown. This reduces accidental target and sequencing errors; it does not
provide peer review and does not defend against a malicious or compromised
operator, OS account, repository owner, or database superuser.

The separated-human names and approvals in these local text artifacts are
procedural records, not cryptographic signatures. Independent approval must be
established in the external change record and by the actual people involved;
the scripts only enforce distinct normalized identities and exact bindings.

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
export GALLR_REVIEWED_NODE_PATH='<canonical-absolute-node-executable>'
export GALLR_REVIEWED_PSQL_PATH='<canonical-absolute-psql-executable>'
export GALLR_CHANGE_RECORD='<approved-change-record>'
export GALLR_EXECUTOR='<executor-name>'
export GALLR_REVIEWER='<different-reviewer-name>'
export GALLR_REHEARSAL_RUN_ID='20260721T120000Z-staging' # optional

./scripts/staging-rehearsal/run-safe-bash.sh \
  scripts/staging-rehearsal/preflight.sh
```

For an honest solo run, set both compatibility identity fields to the same
stable real identity and provide the exact first confirmation. Do not construct
the confirmation from stale terminal state; verify both projects and the
reviewed commit first:

```sh
export GALLR_GOVERNANCE_MODE='solo_operator'
export GALLR_EXECUTOR='<stable-operator-identity>'
export GALLR_REVIEWER='<same-stable-operator-identity>'
export GALLR_SOLO_OPERATOR_FIRST_CONFIRMATION="INTENT STAGING ${GALLR_EXPECTED_STAGING_PROJECT_REF} NOT PRODUCTION ${GALLR_PRODUCTION_PROJECT_REF} ${GALLR_REVIEWED_COMMIT} ACCEPT_NO_INDEPENDENT_REVIEW"

./scripts/staging-rehearsal/run-safe-bash.sh \
  scripts/staging-rehearsal/preflight.sh
```

In the resulting schema-2 manifest, the repeated `reviewer` value is a legacy
field binding, not evidence of review by another person. The manifest also
records `human_reviewer_count=0`,
`automation_is_independent_human_review=false`,
`residual_risk_accepted=true`, `minimum_cooldown_seconds=900`, and
`destructive_actions=forbidden`.

Do not load service-role or database credentials merely to run this helper.
The manifest reports only whether the future remote variable names are present;
it never reads or records their values.

Supply the real canonical executable files, not `command -v` output, a symlink,
or a path selected from `PATH`. Preflight records each exact path and SHA-256
digest and rejects an executable or ancestor directory that is group- or
other-writable. The only writable-ancestor exception is the root-owned sticky
system temporary directory. For a Homebrew-style `Cellar` path, the sibling
`opt` dependency-link directory must also be non-writable by group and others.
Node.js 18 or newer and PostgreSQL client 16 or newer are required; preflight
invokes each exact executable locally to validate its version and required
capabilities without making a network connection. Every credential-bearing
runner rechecks those manifest-bound files immediately before use and starts
them with a minimal environment. Replacing either tool, changing its bytes, or
changing the path requires a fresh preflight and fresh authorization.

Default Homebrew directories may be mode `0775`, which the provenance gate
intentionally rejects. From a trusted operator terminal, inspect the owner and
group first. If this is the operator-owned solo machine, remove group/other
write permission from the fixed command and dependency roots before preflight.
On Apple Silicon:

```sh
/usr/bin/stat -f '%Sp %Su %Sg %N' \
  /opt/homebrew/bin /opt/homebrew/Cellar /opt/homebrew/opt
chmod go-w /opt/homebrew/bin /opt/homebrew/Cellar /opt/homebrew/opt
```

On Intel macOS:

```sh
/usr/bin/stat -f '%Sp %Su %Sg %N' \
  /usr/local/bin /usr/local/Cellar /usr/local/opt
chmod go-w /usr/local/bin /usr/local/Cellar /usr/local/opt
```

Do not apply that change blindly on a shared Homebrew installation. Use
independently administered, non-writable tool paths instead.

These checks reduce accidental substitution; they are not full operating-system
or dynamic-dependency attestation. They assume a trusted host, trusted operator
account, trusted parent shell, and no same-UID process modifying the checkout,
tool paths, symlink graph, or loaded libraries during a run. The interpreted
`run-safe-bash.sh` cannot retroactively remove `LD_PRELOAD`, `LD_AUDIT`, or an
equivalent loader setting already consumed while `/bin/sh` itself was loading.
Start it only from a trusted terminal where loader/runtime injection variables
are absent. The wrapper removes known loader and language-runtime injection
variables before starting Bash and gives target scripts a fixed bootstrap
`PATH`; this protects child startup, not a compromised parent process. Keep the
reviewed checkout unchanged for the entire run. If that boundary is uncertain,
stop, restore a trusted host/checkout/toolchain, rotate exposed staging
credentials, and create a fresh preflight and authorization.

## Output

The external evidence directory receives two read-only files:

- `operator-manifest.txt` — commit, cached branch divergence, target
  fingerprints, reviewed Node.js/psql paths and SHA-256 digests, CLI contract,
  required environment-name contracts, and one SHA-256 value for every tracked
  migration;
- `rehearsal-plan.txt` — ordered, non-executable instructions for the later
  authorized staging session and its stop conditions.

The output is preflight evidence, not proof that a clone exists or that a
remote migration succeeded. A later operator must verify the target again,
capture linked migration history, review a dry run, measure locks, and retain
the database/reconciliation evidence required by the cutover runbook.

## Disposable-clone identity

Before any staging mutation, follow [`TARGET-IDENTITY.md`](TARGET-IDENTITY.md).
In `separated_humans`, an identity operator other than the executor prepares
the existing two-approver policy. In `solo_operator`, the one stable operator
prepares a schema-2 policy, seals it mode `0400`, waits until both its issue time
and every available file-system modification, metadata-change, and creation
timestamp are at least 900 seconds old, and then enters this exact action-time
literal when prompted:

```text
EXECUTE STAGING <staging-ref> NOT PRODUCTION <production-ref> <full-reviewed-commit> ACCEPT_NO_INDEPENDENT_REVIEW
```

Enter the execution literal directly on interactive terminal stdin. The marker
installer rejects pipes, redirected files, FIFOs, and `/dev/null` before it
creates evidence or opens the database installation session.

Changing metadata, touching, rewriting, or replacing the solo policy restarts
the cooldown. The policy must still be unexpired. Neither the cooldown nor the
target guards count as independent human review. Then load:

```sh
export GALLR_STAGING_IDENTITY_POLICY_PATH='/absolute/secure/identity-policy.txt'
./scripts/staging-rehearsal/run-safe-bash.sh \
  scripts/staging-rehearsal/assert-disposable-clone-target.sh
```

Invoke `run-safe-bash.sh` directly, never through `bash` or by sourcing it. Its
privileged, no-profile/no-rc Bash child discards `BASH_ENV`, `ENV`, `CDPATH`,
shell-option exports, exported functions, known loader/runtime injection
variables, and the caller's `PATH` before a credential-bearing staging
entrypoint starts. This child-side sanitation does not make an already
compromised parent process trustworthy; use the host boundary above.

The check binds the reviewed commit, exact operator-manifest bytes, two target
fingerprints, linked project, direct database URL, the profile-specific policy,
and the database-resident marker. Fixture provision/cleanup, the queued-writer
activation, the anonymous write-denial coordinator, the rollback-only legacy
writer probe, and the PostgREST mutation case invoke this check themselves.
Migration pushes, import application, and admin mutation sessions must invoke
it immediately before their first write. Read-only evidence commands still use
the manifest-bound linked-target guard.

## Local validation

With Node.js 18 or newer, validate the production-recorded migration versions
and recovered historical bytes before any database command:

```sh
node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs
node --test scripts/staging-rehearsal/lib/validate-migration-lineage.test.mjs
node --test scripts/staging-rehearsal/lib/validate-database-target.test.mjs
node --test scripts/staging-rehearsal/lib/run-psql-with-validated-target.test.mjs
bash scripts/staging-rehearsal/lib/reviewed-toolchain.test.sh
bash scripts/staging-rehearsal/lib/libpq-routing-regression.test.sh
```

The target and launcher suites use fake local children. The libpq regression
uses the installed real `psql` only against unreachable loopback port 1; it
opens no remote database connection. Syntax-check the shell launch boundary and
run its network-free behavioral test:

```sh
sh -n scripts/staging-rehearsal/preflight.sh
sh -n scripts/staging-rehearsal/run-safe-bash.sh
bash scripts/staging-rehearsal/tests/run-safe-bash.test.sh
bash scripts/staging-rehearsal/tests/anonymous-access-checks.test.sh
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
`0400` or `0600` in an operator-owned mode-`0700` directory, and verify its
approved SHA-256 fingerprint. The validator rejects symlinks, noncanonical
paths, bundles that are not a currently valid self-signed CA, and files whose
owner, link count, mode, or bytes change. The reviewed parser pins the current
Supabase Root 2021 CA bytes as
`700723581420dd1ac98fd7e9ac529f0ef210eadcaf87fc868a3ad7d114c2f3b7`;
a CA rotation requires a reviewed code change and fresh preflight. The URI must
contain exactly
`sslmode=verify-full&sslrootcert=%2Fabsolute%2Fexternal%2Fsupabase-ca.crt`;
`sslmode=require`, `sslmode=verify-ca`, a relative certificate path, duplicate
fields, or missing TLS fields fail closed. Set
`GALLR_STAGING_REHEARSAL_CONFIRM` to the exact expected staging ref, retain
`GALLR_STAGING_IDENTITY_POLICY_PATH` for the rollback-only writer probe, and
use the guarded coordinator:

Each coordinator validates the supplied URI once when it accepts the target
inputs. Immediately before every database child, the shared
`run-psql-with-validated-target.mjs` launcher revalidates the same URI with
`database-target.mjs`; clears inherited libpq routing, credential, TLS, and
session overrides; rechecks the exact manifest-bound `psql` file and digest;
and starts that absolute executable with an environment built from scratch.
The child receives discrete `PGHOST`, `PGPORT`, `PGDATABASE=postgres`,
`PGUSER`, and `PGSSLROOTCERT` values, forces `PGSSLMODE=verify-full`, disables
GSS transport and client-certificate discovery, and cannot inherit a caller's
`PGGEQO` or `PGSSLCOMPRESSION`. SQL files and permitted literal relative
includes are copied into the private transport directory before execution;
`\connect`, `\c`, shell escapes, client-side copy/output/file commands, dynamic
includes, early successful `\quit`, error-policy weakening, and paths outside
that snapshot are rejected. Marker bootstrap seals success only after its
post-commit query emits one exact committed-marker token. The decoded password is
written only to a launcher-owned ephemeral mode-`0600` `PGPASSFILE`, which is
removed when the child exits. The raw URI is
removed from the `psql` child environment and never appears in its arguments,
output, or retained evidence. Do not replace this transport with a URI in
`PGDATABASE`: libpq treats that environment value as a database name rather
than expanding it as a connection URI.

Normal exit and handled signals remove the private transport directory. An
unrecoverable `SIGKILL` or power loss can leave an operator-owned
`gallr-validated-psql-*` directory in the canonical system `/tmp` directory.
The launcher deliberately ignores caller-controlled `TMPDIR`. Before
continuing, confirm no rehearsal `psql` child is active, inspect and remove only
that exact run's directory, and rotate the staging database password if its
custody is uncertain.

The Bash coordinators also use private mode-`0600` scratch files in the
external evidence directory. A `SIGKILL` or power loss can leave files matching
`.gallr-marker-query-output.<pid>`, `.marker-install-output.<pid>`,
`.*.psql-output.<pid>`, `.anonymous-psql-output.<pid>`,
`.migration-writer-psql-output.<pid>`, or a fixture run's
`.psql-stderr.<pid>`. Treat them as sensitive. Before retrying, confirm that no
rehearsal shell, Node.js, or `psql` child from that run is active; inspect the
exact operator-owned regular file; then remove only that absolute filename.
Never use a broad wildcard, and do not delete sealed partial evidence.

After each authorized phase, remove database and API secrets from the trusted
operator shell, or close that shell entirely:

```sh
unset GALLR_STAGING_DATABASE_URL GALLR_STAGING_DB_URL
unset GALLR_PRODUCTION_DATABASE_URL GALLR_SERVICE_ROLE_KEY GALLR_SUPABASE_URL
unset DATABASE_URL SUPABASE_URL SUPABASE_ANON_KEY SUPABASE_ACCESS_TOKEN
unset SUPABASE_SERVICE_ROLE_KEY SUPABASE_SECRET_KEY
unset SUPABASE_DB_URL SUPABASE_DB_PASSWORD PGPASSWORD PGPASSFILE PGSSLKEY
```

Reload only the minimum approved values immediately before the next authorized
command. If the terminal, shell history, or credential custody is uncertain,
close it and rotate the affected staging secret.

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
list and child environment by using the shared validated launcher, opens
independent database sessions, and fails unless the allowed public read
succeeds while both the private-schema read and catalog write return SQLSTATE
`42501`. It refuses to overwrite existing evidence. These checks connect with
the validated database operator and execute `SET LOCAL ROLE anon`; they do not
put an anonymous database credential in the connection URI.

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
the direct database URL, sealed identity policy, and staging fixture
attestation. It verifies the profile-bound marker, binds the target row's
first-attempt content checksum, and runs only the checked-in fixed mutation SQL
through the manifest-reviewed, validated `psql` transport. Operator-supplied
hook or guard paths are rejected. Every case refuses to overwrite evidence and
seals complete or partial output mode `0400`.
