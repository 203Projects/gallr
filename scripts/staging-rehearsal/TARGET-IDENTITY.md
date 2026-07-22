# Disposable staging-clone identity gate

Fixture provisioning and the queued-writer rehearsal intentionally mutate the
database. A project-ref variable, URL hostname, or CLI link can all be labelled
incorrectly by the same operator. `assert-disposable-clone-target.sh` therefore
requires independent agreement from six sources before those mutations:

1. the exact staging and production project-ref fingerprints in the sealed
   `operator-manifest.txt`;
2. the repository's current reviewed commit and clean checkout;
3. the currently linked Supabase project;
4. the exact hostname, username, database, and port in the direct PostgreSQL
   URL;
5. a separately prepared, mode-`0400`, two-approver identity policy; and
6. one expiring marker stored only in the disposable clone.

The policy stores SHA-256 fingerprints, never raw project refs. The marker
stores the policy and operator-manifest fingerprints, so neither artifact can
be silently substituted. This gate protects against operator target-label
mistakes; it is not a defense against a database superuser deliberately forging
the marker.

## 1. Prepare the independent policy

After `preflight.sh` has produced `operator-manifest.txt`, an identity operator
who is not the rehearsal executor must independently identify production and
the disposable clone in the Supabase dashboard and change record. Confirm that
the clone can be discarded and restored. Do not infer identity from terminal
variable names, the current CLI link, or a database URL supplied by the
executor.

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

## 2. Install the marker on the disposable clone only

`sql/install-disposable-clone-marker.sql` is clone-preparation SQL, not a
migration. A separate, authorized identity operator installs it once after
independently re-checking the target in the dashboard. Never add it to
`supabase/migrations`, never run it as part of `supabase db push`, and never run
it against production.

Use only the checked-in bootstrap wrapper. Do not invoke the SQL manually or
transcribe policy fields into shell variables. From the exact clean reviewed
checkout, load the target inputs and direct `postgres` URL, then type the
installation confirmation as one literal line. Its exact shape is
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

Before its first child process, the wrapper snapshots its required inputs and
removes credentials, libpq routing variables, validator aliases, and internal
shell aliases from the inherited environment. It then:

1. requires the wrapper, linked guard, validators, and install SQL to be
   checked-in regular files in the reviewed repository;
2. runs the linked-project guard, direct-URL validator, and independent policy
   validator;
3. parses the validator's strict TSV record instead of sourcing the policy or
   asking the operator to transcribe it;
4. revalidates the policy, linked target, and direct URL and compares the exact
   commit, manifest, policy, wrapper, guard, validator, and SQL bytes; and
5. opens exactly one `sslmode=verify-full` `psql` installation session, with
   the approved absolute Supabase server-root-certificate path in
   `sslrootcert`, the URL
   supplied only through `PGDATABASE`, `/dev/null` as the passfile, inherited
   libpq routing cleared, and all SQL variables taken from the validated record.

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
Residual trust therefore remains in the independent dashboard identification,
the two approvers, and the database superuser/direct connection. A database
superuser can still forge the marker. Immediately after installation, run the
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
child keep inherited startup files and exported functions outside this
credential-bearing identity check.

The wrapper first runs `assert-linked-staging.sh`, then validates the direct URL
and independent policy locally. Only after those checks pass does it open one
database session. That session receives the URL through `PGDATABASE`, clears
inherited libpq routing variables, forces `sslmode=verify-full` with the
approved Supabase server root certificate, and sets
`default_transaction_read_only=on` and executes the marker query inside one
read-only transaction. It prints only a generic pass line and never prints raw
refs, the URL, or credentials.

The identity gate must run in the same process immediately before the first
fixture or concurrency mutation. Save its generic output in that run's
external evidence directory and seal the log mode `0400`. A successful check is
short-lived evidence for that invocation, not permission to reuse a terminal
later or against another URL.

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
bash scripts/staging-rehearsal/tests/install-disposable-clone-marker.test.sh
bash scripts/staging-rehearsal/tests/target-identity-guard.test.sh
```

The SQL should additionally be exercised on a freshly reset local Supabase
database before review. That local test must install the marker inside a
throwaway local database transaction/environment and leave no remote state.
