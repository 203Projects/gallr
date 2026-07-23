# Disposable staging-clone identity gate

Fixture provisioning and the queued-writer rehearsal intentionally mutate the
database. A project-ref variable, URL hostname, or CLI link can all be labelled
incorrectly by the same operator. `assert-disposable-clone-target.sh` therefore
requires agreement from six bound sources before those mutations:

1. the exact staging and production project-ref fingerprints in the sealed
   `operator-manifest.txt`;
2. the repository's current reviewed commit and clean checkout;
3. the currently linked Supabase project;
4. the exact hostname, username, database, and port in the direct PostgreSQL
   URL;
5. a separately prepared, mode-`0400`, profile-specific identity policy; and
6. one expiring marker stored only in the disposable clone.

The policy stores SHA-256 fingerprints, never raw project refs. The marker
stores the policy and operator-manifest fingerprints, so neither artifact can
be silently substituted. This gate protects against operator target-label
mistakes; it is not a defense against a database superuser deliberately forging
the marker.

`GALLR_GOVERNANCE_MODE` defaults to `separated_humans`. That profile retains the
two-approver workflow. `solo_operator` is an explicit alternative for a person
who genuinely operates alone. It uses one stable real identity, records
`human_reviewer_count=0`, and records that automation, CI, AI, scripts, and
separate terminal or database sessions are not independent human review. Never
invent aliases or count automation as another person.

Solo controls reduce accidental target and sequencing mistakes. They do not
provide peer review or defend against a malicious or compromised operator, OS
account, repository owner, or database superuser. That residual risk must be
accepted explicitly. The same boundary applies to local execution integrity:
the fixed-path launcher removes known child loader/runtime injection variables
and checks reviewed executable bytes, but an interpreted launcher cannot undo
loader code already admitted by its parent process and does not attest every
dynamic dependency. Run from a trusted clean terminal and keep the reviewed
checkout, tool paths, symlinks, and dependencies unchanged; a malicious or
same-UID replacement is outside this accidental-misuse control.

## 1. Prepare the profile-specific policy

After `preflight.sh` has produced `operator-manifest.txt`, identify production
and the disposable clone in the Supabase dashboard and change record. Confirm
that the clone can be discarded and restored. Do not infer identity from
terminal variable names, the current CLI link, or a database URL. In
`separated_humans`, an identity operator who is not the executor performs this
check. In `solo_operator`, the one operator performs it in a separate intent
step and accepts that this is not independent review.

### Schema 1: `separated_humans` (default)

Use `target-identity-policy.example` only as a field-order reference. Create the
real file in an operator-owned mode-`0700` directory outside the repository.
The file must contain exactly the twelve fields shown, one per line, with LF
line endings and a final newline. Requirements:

- both ref values are lowercase SHA-256 fingerprints of the exact raw refs;
- `repository_commit`, `change_record`, and both ref fingerprints exactly match
  the operator manifest;
- `operator_manifest_sha256` hashes the exact manifest bytes;
- `approver_one` and `approver_two` are distinct, neither is the executor, and
  one is the reviewer recorded in the operator manifest;
- timestamps use UTC second precision; issue time may be at most five minutes
  ahead and validity is positive, unexpired, and no longer than seven days;
- `marker_id` is a new lowercase RFC 4122 UUID; and
- neither raw project ref appears anywhere in the policy.

Seal the finished file:

```sh
chmod 0400 /absolute/secure/identity-policy.txt
```

The checker rejects symlinks, a policy inside the repository, any other file
mode, a parent directory other than mode `0700`, duplicate or unknown fields,
expired policy, swapped labels, and artifacts not bound to the current manifest
and commit.

The linked-target guard also requires the exact production ref to match the
reviewed digest in `production-project-ref.sha256` and rejects a staging ref
with that digest. The anchor is part of the reviewed commit; changing it
requires a fresh review, preflight, policy, cooldown, and authorization.

### Schema 2: `solo_operator`

Solo preflight requires the executor and legacy `reviewer` fields to contain the
same exact stable identity. The repeated field is schema compatibility, not a
claim that another person reviewed the run. Before preflight, type this exact
intent literal:

```text
INTENT STAGING <staging-ref> NOT PRODUCTION <production-ref> <full-reviewed-commit> ACCEPT_NO_INDEPENDENT_REVIEW
```

Pass it as `GALLR_SOLO_OPERATOR_FIRST_CONFIRMATION`. Preflight stores only its
SHA-256 in the schema-2 manifest. Then create the policy with exactly these 15
lines, in this order, with LF endings and a final newline:

```text
policy_schema=2
policy_kind=gallr_disposable_clone_target
governance_mode=solo_operator
issued_at_utc=<UTC timestamp with exact second precision>
valid_until_utc=<UTC timestamp no more than one day after issued_at_utc>
minimum_cooldown_seconds=900
destructive_actions=forbidden
staging_project_ref_sha256=<sha256 of exact staging ref, no newline>
production_project_ref_sha256=<sha256 of exact production ref, no newline>
repository_commit=<full reviewed commit>
operator_manifest_sha256=<sha256 of exact operator-manifest.txt bytes>
change_record=<exact change-record ID>
operator_identity=<same stable identity used by preflight>
first_confirmation_sha256=<sha256 of exact INTENT literal, no newline>
marker_id=<new lowercase RFC 4122 UUID>
```

The policy must match the schema-2 manifest fields
`governance_mode=solo_operator`, `human_reviewer_count=0`,
`automation_is_independent_human_review=false`,
`residual_risk_accepted=true`, `minimum_cooldown_seconds=900`, and
`destructive_actions=forbidden`. Neither raw project ref may appear in the
policy.

Seal the policy mode `0400`. Installation is blocked until `issued_at_utc` and
every available file-system modification, metadata-change, and creation
timestamp are at least 900 seconds old. Changing metadata, rewriting, copying
over, touching, or replacing the file restarts the file-time cooldown. The
policy must still be unexpired; do not backdate timestamps or alter the system
clock to bypass the wait.

## 2. Install the marker on the disposable clone only

`sql/install-disposable-clone-marker.sql` is clone-preparation SQL, not a
migration. Install it once after re-checking the target in the dashboard under
the selected governance profile. Never add it to `supabase/migrations`, never
run it as part of `supabase db push`, and never run it against production.

Use only the checked-in bootstrap wrapper. Do not invoke the SQL manually or
transcribe policy fields into shell variables. From the exact clean reviewed
checkout, load the target inputs and direct `postgres` URL.

In `separated_humans`, type the installation confirmation as one literal line.
Its exact shape is
`INSTALL_GALLR_DISPOSABLE_CLONE_MARKER:<exact-staging-ref>:<full-reviewed-commit>`.
Do not construct it with shell expansion: the repeated ref and commit are an
intentional operator check against the independently reviewed dashboard and
change record.

```sh
export GALLR_EXPECTED_STAGING_PROJECT_REF='<exact-staging-ref>'
export GALLR_PRODUCTION_PROJECT_REF='<exact-production-ref>'
export GALLR_STAGING_REHEARSAL_CONFIRM='<exact-staging-ref>'
export GALLR_STAGING_DATABASE_URL='<approved-direct-uri-with-sslmode=verify-full-and-absolute-sslrootcert>'
export GALLR_STAGING_EVIDENCE_DIR='<absolute-preflight-evidence-directory>'
export GALLR_STAGING_IDENTITY_POLICY_PATH='<absolute-sealed-identity-policy>'
export GALLR_REVIEWED_COMMIT='<full-reviewed-commit>'

printf '%s\n' \
  'Type INSTALL_GALLR_DISPOSABLE_CLONE_MARKER:<exact-staging-ref>:<full-reviewed-commit>'
IFS= read -r GALLR_DISPOSABLE_CLONE_MARKER_INSTALL_CONFIRMATION
export GALLR_DISPOSABLE_CLONE_MARKER_INSTALL_CONFIRMATION

./scripts/staging-rehearsal/run-safe-bash.sh \
  scripts/staging-rehearsal/install-disposable-clone-marker.sh
unset GALLR_DISPOSABLE_CLONE_MARKER_INSTALL_CONFIRMATION
```

In `solo_operator`, do not set
`GALLR_DISPOSABLE_CLONE_MARKER_INSTALL_CONFIRMATION`. Select the profile and run
the same installer after the complete 15-minute cooldown:

```sh
export GALLR_GOVERNANCE_MODE='solo_operator'
unset GALLR_DISPOSABLE_CLONE_MARKER_INSTALL_CONFIRMATION

./scripts/staging-rehearsal/run-safe-bash.sh \
  scripts/staging-rehearsal/install-disposable-clone-marker.sh
```

The installer validates the schema-2 policy before creating evidence and then
prompts for exactly:

```text
EXECUTE STAGING <staging-ref> NOT PRODUCTION <production-ref> <full-reviewed-commit> ACCEPT_NO_INDEPENDENT_REVIEW
```

Type it manually. The wrapper hashes it and stores only the fingerprint in the
marker and installation evidence. Solo confirmation requires interactive
terminal stdin; pipes, redirected files, FIFOs, and `/dev/null` fail before
evidence creation. If the target, excluded production project, commit, manifest,
policy, or any policy file timestamp changes, stop and restart the
intent/policy/cooldown sequence.

Before its first child process, the wrapper snapshots its required inputs and
removes credentials, libpq routing variables, validator aliases, and internal
shell aliases from the inherited environment. It then:

1. requires the wrapper, linked guard, validators, shared database-target
   parser, reviewed-toolchain helper, validated `psql` launcher, and install SQL
   to be checked-in regular files in the reviewed repository;
2. validates the profile-specific policy and cooldown and parses the validator's
   strict TSV record instead of sourcing the policy;
3. in solo mode, prompts for and hashes the exact action-time confirmation;
4. creates the no-clobber evidence file only after the policy, cooldown, and
   action-time confirmation pass;
5. runs the linked-project guard and direct-URL validator;
6. revalidates the policy, linked target, and direct URL and compares the exact
   commit, manifest, policy, wrapper, guard, parser, toolchain helper, launcher,
   validator, and SQL bytes; and
7. asks the shared launcher to revalidate the URI and open exactly one
   `sslmode=verify-full` `psql` installation session. The launcher clears
   inherited libpq routing, credential, TLS, and session overrides; supplies
   discrete validated `PGHOST`, `PGPORT`, `PGDATABASE=postgres`, `PGUSER`,
   `PGSSLMODE`, and `PGSSLROOTCERT` values; disables GSS transport and client
   certificate discovery; snapshots the checked SQL bytes; and gives the
   decoded password to libpq only through a launcher-owned ephemeral
   mode-`0600` `PGPASSFILE`. It runs the exact `psql` path and digest bound by
   preflight with an environment built from scratch, rejects connection-changing
   and local-side-effect psql meta-commands, removes the raw URI from the child
   environment and argument vector, removes the passfile when the child exits,
   and takes all SQL variables from the validated policy record.

The shared parser pins the reviewed Supabase Root 2021 CA file SHA-256
`700723581420dd1ac98fd7e9ac529f0ef210eadcaf87fc868a3ad7d114c2f3b7`.
A certificate rotation is a stop condition: update the pin through normal
review, then create a fresh preflight manifest and authorization policy.

The SQL fails if its private schema already exists; do not overwrite or repair
an existing marker. Investigate and refresh the clone instead. The wrapper
refuses existing evidence. Once evidence creation begins, it seals complete or
partial output at
`${GALLR_STAGING_EVIDENCE_DIR}/disposable-clone-marker-installation.txt` mode
`0400`. Only a successful file ends with
`evidence_success=install-disposable-clone-marker`; it contains fingerprints,
never raw refs or the database URL.

This bootstrap deliberately cannot call `assert-disposable-clone-target.sh`,
because the marker that guard reads does not exist until installation commits.
In `separated_humans`, residual trust remains in the independent dashboard
identification, the two approvers, and the database superuser/direct connection.
In `solo_operator`, the same human performs the target checks and accepts the
lack of independent judgment after the cooldown. A database superuser can still
forge the marker in either profile. Immediately after installation, run the
normal marker guard; it adds evidence of agreement but cannot retroactively
remove that bootstrap trust.

The marker lives in
`gallr_rehearsal_private.disposable_clone_marker`. The schema and table grant no
access to `anon`, `authenticated`, or `service_role`; they are readable only by
the direct database operator. Destroying or restoring the disposable clone is
the cleanup. If this schema is ever found in production, stop the rollout and
treat it as a target-identity incident.

No marker has been installed by these repository changes.

## 3. Check identity immediately before mutation

Set the usual rehearsal variables plus the sealed policy path:

```sh
export GALLR_EXPECTED_STAGING_PROJECT_REF='<exact-staging-ref>'
export GALLR_PRODUCTION_PROJECT_REF='<exact-production-ref>'
export GALLR_STAGING_REHEARSAL_CONFIRM="$GALLR_EXPECTED_STAGING_PROJECT_REF"
export GALLR_STAGING_DATABASE_URL='<direct-uri-with-sslmode=verify-full-and-absolute-sslrootcert>'
export GALLR_STAGING_EVIDENCE_DIR='/absolute/preflight/evidence-directory'
export GALLR_STAGING_IDENTITY_POLICY_PATH='/absolute/secure/identity-policy.txt'

./scripts/staging-rehearsal/run-safe-bash.sh \
  scripts/staging-rehearsal/assert-disposable-clone-target.sh
```

Always execute `run-safe-bash.sh` directly. Do not run it through `bash` or
source it; its direct privileged shebang and privileged no-startup-file Bash
child keep inherited startup files, exported functions, known runtime
injection variables, and caller-selected bootstrap `PATH` outside this
credential-bearing identity check. This sanitation protects descendants; it
does not repair an already compromised parent interpreter or host.

The wrapper first runs `assert-linked-staging.sh`, then validates the direct URL
and profile-specific policy locally. Only after those checks pass does it ask
the shared launcher to open one database session. The launcher revalidates the
URI, clears inherited libpq routing and credential variables, expands the
approved target into discrete connection variables, and uses an ephemeral
mode-`0600` passfile instead of placing the URI in `PGDATABASE`. The `psql`
child receives no raw URI in either its environment or argument vector, forces
`sslmode=verify-full` with the approved Supabase server root certificate, sets
`default_transaction_read_only=on`, disables GSS transport and client-certificate
discovery, and executes a private snapshot of the marker query inside one
read-only transaction. Preflight binds the exact canonical Node.js and `psql`
paths and SHA-256 digests; each runner rechecks those files and secure ancestor
directories before use. The passfile is removed after the child exits. The
wrapper prints only a generic pass line and never prints raw refs, the URL, or
credentials.

The identity gate must run in the same process immediately before the first
fixture or concurrency mutation. Save its generic output in that run's
external evidence directory and seal the log mode `0400`. A successful check is
short-lived evidence for that invocation, not permission to reuse a terminal
later or against another URL.

In solo mode the marker also binds the stable operator identity, both
confirmation fingerprints, effective first-attestation time, 900-second
minimum cooldown, `human_reviewer_count=0`, automation disclosure, residual-risk
acceptance, and `destructive_actions=forbidden`. The read-only guard proves that
those exact values reached the disposable clone; it does not turn them into
independent human approval.

### Fixture integration

In `fixtures/common.sh`, call the wrapper after the existing linked-target and
direct-URL checks but before raw target variables are unset and before any
`fixture_psql` call. Require `GALLR_STAGING_IDENTITY_POLICY_PATH`. If the wrapper
fails, exit before creating the fixture evidence directory or capturing a
baseline.

### Concurrency integration

In `concurrency/run.sh`, call the wrapper after the existing linked-target log
check and before the preflight query or `MUTATION_PHASE=1`. Map
`GALLR_STAGING_EVIDENCE_DIR` to `CONCURRENCY_EVIDENCE_ROOT` for the call and
retain a mode-`0400` `target-identity-guard.log`. Require
`GALLR_STAGING_IDENTITY_POLICY_PATH` in `concurrency/common.sh`.

Do not add an override, skip flag, local-mode bypass, or “confirm anyway” path
to either mutating coordinator.

## Network-free verification

The policy-validator, bootstrap-installer, and marker-guard tests use only
temporary local files and a fake `psql`; they make no DNS, Supabase, or
PostgreSQL connection:

```sh
node scripts/staging-rehearsal/lib/validate-target-identity-policy.test.mjs
node --test scripts/staging-rehearsal/lib/validate-database-target.test.mjs
node --test scripts/staging-rehearsal/lib/run-psql-with-validated-target.test.mjs
bash scripts/staging-rehearsal/lib/reviewed-toolchain.test.sh
bash scripts/staging-rehearsal/lib/libpq-routing-regression.test.sh
bash scripts/staging-rehearsal/tests/install-disposable-clone-marker.test.sh
bash scripts/staging-rehearsal/tests/target-identity-guard.test.sh
```

The SQL should additionally be exercised on a freshly reset local Supabase
database before review. That local test must install the marker inside a
throwaway local database transaction/environment and leave no remote state.
