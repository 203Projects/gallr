# Isolated staging catalog fixtures

This directory provides an opt-in, reversible fixture lifecycle for the real
PostgREST pagination rehearsal. It creates exactly **1,205 canonical published
exhibitions** under one run-specific prefix and one run-specific event. It never
selects a project, discovers credentials, links a Supabase project, changes
runtime ownership, changes grants, or connects unless an operator supplies all
required environment variables and the exact action confirmation.

The tooling is for a distinct, populated staging clone only. It is not a local
seed and must never be pointed at production.

## What the fixture covers

- 1,205 unique, run-prefixed canonical identities and published versions.
- A run-prefixed event containing legal PostgREST-reserved characters; all
  1,205 exhibitions belong to it, so pre-existing clone rows do not affect the
  exact event-scoped count.
- An empty run-prefixed event for the zero-row filter proof.
- The database-ordered 500th ID contains commas, parentheses, a colon, a dot,
  and Korean text. It is the exact cursor for the second keyset request.
- A separate database-ordered 750th ID is reserved as the same-ID mutation
  target for the integrity-retry rehearsal.
- Five featured rows and four homepage rows. One of each comes from a canonical
  curation placement rather than only the version fallback flags.
- House-editor and run-specific guest-editor projections.
- Korean/English text, quote and punctuation content, null coordinate pairs,
  reception/ticket fields, and non-null coordinate pairs.
- One published cover-media metadata attachment and a separate legacy cover URL
  fallback row.

No object is uploaded to Supabase Storage. The media row uses an
`https://fixtures.invalid/` URL and records `bytes_uploaded=false`. Provisioning
and cleanup affect PostgreSQL metadata only.

## Safety model

Both wrappers fail before `psql` unless all of these are true:

1. staging and production refs are different, exact 20-character lowercase
   alphanumeric project refs;
2. the shared exact target parser proves the URI is the expected Supabase
   direct host/user on port `5432`, with `/postgres` and a strong SSL mode;
3. the run ID is 8–32 lowercase ASCII letters, digits, or hyphens;
4. `GALLR_STAGING_REHEARSAL_CONFIRM` exactly equals the expected staging ref;
5. preflight's read-only operator manifest fingerprints those refs and the
   current reviewed commit, the worktree is still clean, and Supabase's linked
   project-ref file identifies that same non-production staging project;
6. a separately prepared two-approver policy and the exact expiring marker in
   the disposable clone agree with that manifest, commit, and both target
   fingerprints;
7. the evidence directory is absolute, outside this repository, owned by the
   current user, and mode `0700`;
8. the optional connect timeout is an integer from 5 through 60 seconds; and
9. the operator manifest binds canonical, non-symlink Node.js and `psql`
   executable files and their SHA-256 digests, with no writable ancestor.

The wrapper validates the URI once while accepting the fixture inputs. Before
each `psql` child, the shared launcher revalidates it, removes inherited libpq
routing, credential, TLS, and session overrides (including `PGHOSTADDR`,
`PGHOST`, `PGSERVICE`, and `PGPASSWORD`), rechecks the manifest-bound executable,
and supplies only discrete validated `PGHOST`, `PGPORT`,
`PGDATABASE=postgres`, `PGUSER`, and `PGSSLROOTCERT` values in an environment
built from scratch. It forces `PGSSLMODE=verify-full`, disables GSS transport
and client-certificate discovery, and snapshots approved SQL inputs before the
child starts. The decoded password exists only in a
launcher-owned ephemeral mode-`0600` `PGPASSFILE`; default `.pgpass` discovery
is disabled and the temporary passfile is removed when the child exits. The
raw URI is removed from the `psql` child environment, never appears in its
argument vector, and is never printed or written to evidence. Raw project refs
are also neither printed nor persisted; evidence uses SHA-256 fingerprints,
and connection errors redact project refs before reaching the terminal.
`psql` runs without user startup files or password prompts. Use the direct
connection shown by the staging project's **Connect** panel; pooler endpoints
are rejected.

Shell startup injection is disabled for both target guards by pinning
`BASH_ENV` and `ENV` to `/dev/null`. Lifecycle evidence is accepted only when
it is a current-user-owned regular file with one hard link and an expected
private/final mode; symbolic links, hard-link aliases, ambiguous final plus
incomplete pairs, and malformed JSON are rejected.

These checks bind the supplied target to preflight's reviewed local evidence.
They do not replace the operator's dashboard verification of the disposable
staging clone before confirmation.

## 1. Prepare a quiet, populated staging clone

Apply the reviewed migrations first. Confirm that this is not production and
that no unrelated editor or import will write to the clone during provision or
cleanup. The lifecycle records and later restores global counts and hashes; it
aborts on unrelated baseline drift. Each database transaction also takes
`SHARE` locks on all tracked/reference tables before comparing or mutating them;
the 10-second lock timeout fails closed instead of waiting through active work.

Run the local rehearsal preflight first and reuse the exact mode-`0700`
`GALLR_STAGING_EVIDENCE_DIR` it created. Do not modify its read-only
`operator-manifest.txt`, change commits, or dirty the worktree afterward. The
fixture wrapper creates only its own `fixtures-<run-id>` child. The included
network-free guard test uses a stubbed link assertion; an empty ad-hoc directory
is not sufficient for an authorized staging run.

Export non-secret identity values and a unique run ID:

```bash
export GALLR_EXPECTED_STAGING_PROJECT_REF='<20-char-staging-project-ref>'
export GALLR_PRODUCTION_PROJECT_REF='<production-project-ref>'
export GALLR_FIXTURE_RUN_ID='catalog-20260721a'
export GALLR_STAGING_EVIDENCE_DIR='/absolute/private/path/gallr-staging-evidence'
export GALLR_STAGING_IDENTITY_POLICY_PATH='/absolute/private/path/identity-policy.txt'
```

Read the staging database URI without echoing it or saving it in a shell file:

```bash
read -r -s -p 'Staging PostgreSQL URI: ' GALLR_STAGING_DATABASE_URL
printf '\n'
export GALLR_STAGING_DATABASE_URL
```

The URI must identify the staging ref in the exact direct hostname. Session and
transaction pooler URIs are deliberately rejected for this operational job.

## 2. Provision atomically

Copy the expected staging ref into the rehearsal confirmation only after
checking the target in the dashboard, then run the wrapper from the repository
root:

```bash
export GALLR_STAGING_REHEARSAL_CONFIRM="$GALLR_EXPECTED_STAGING_PROJECT_REF"
./scripts/staging-rehearsal/run-safe-bash.sh \
  scripts/staging-rehearsal/fixtures/provision.sh
```

Invoke `run-safe-bash.sh` directly, never through `bash` or by sourcing it. It
starts the credential-bearing fixture command in privileged Bash without
profiles, rc files, startup-environment files, or exported functions.

Provisioning does the following in one database transaction:

1. verifies the deployed tables, integrity/reconciliation functions, projection
   triggers, and operator privileges;
2. rechecks that the just-captured full-row baseline has not changed;
3. checks every run-prefix namespace for collisions;
4. inserts the event, empty event, editor, 1,205 canonical identities and
   versions, one media row/attachment, and two curation rows;
5. assigns all publication pointers, relying on deployed canonical triggers to
   populate `public.exhibition_catalog_v2`;
6. verifies exact counts, event/featured integrity, cursor positions, editor
   aliases, coordinate cases, both cover-image paths, and field-for-field
   canonical/V2 reconciliation.

On success, the private run directory contains:

- `identity.tsv`: the four safe identity values needed for cleanup;
- `baseline.tsv`: machine-readable pre-fixture counts and full-row hashes for
  canonical versions, events, editors, media, attachments, curations,
  submissions, submission media, and legacy-import references, plus V2 and
  legacy reader hashes;
- `provisioned.json`: database-produced fixture IDs, version IDs, media and
  curation IDs, scoped integrity hashes, and runtime observation;
- `manifest.json`: the immutable identity, top-level string
  `mutation_target_id`, baseline, exact fixture arrays, and deterministic
  SHA-256 hashes of the random version/media/attachment/curation UUID sets.

Final evidence files are read-only mode `0400` inside a mode `0700` run
directory; no file contains the database URI, raw project refs, or API keys.

## 3. Run the real PostgREST checks

Export only the staging Data API URL and anonymous/publishable key. The checked-
in wrapper derives every event, cursor, mutation target, and exact row count
from the sealed fixture manifest; do not transcribe fixture IDs by hand:

```bash
export SUPABASE_URL='<staging-data-api-url>'
export SUPABASE_ANON_KEY='<staging-anon-or-publishable-key>'

./scripts/staging-rehearsal/run-safe-bash.sh \
  scripts/staging-rehearsal/run-postgrest-evidence.sh catalog
./scripts/staging-rehearsal/run-safe-bash.sh \
  scripts/staging-rehearsal/run-postgrest-evidence.sh event
./scripts/staging-rehearsal/run-safe-bash.sh \
  scripts/staging-rehearsal/run-postgrest-evidence.sh featured
./scripts/staging-rehearsal/run-safe-bash.sh \
  scripts/staging-rehearsal/run-postgrest-evidence.sh empty
```

Each command refuses an existing or dangling-symlink evidence path, runs the
terminal empty-page request, checks all IDs and content checksums, and seals its
output mode `0400`. The catalog case expects the recorded pre-fixture V2 count
plus 1,205; the event, featured, and empty cases expect exactly 1,205, 5, and 0.

For the same-ID integrity-retry proof, keep the direct staging URL and identity
policy loaded. The mutation case rechecks the independent policy and database
marker immediately before invoking the checked-in, fixed SQL mutation through
the same validated direct psql transport used by the other rehearsal commands:

```bash
export GALLR_POSTGREST_MUTATION_ATTESTATION=I_CONFIRM_THIS_IS_AN_ISOLATED_STAGING_FIXTURE

./scripts/staging-rehearsal/run-safe-bash.sh \
  scripts/staging-rehearsal/run-postgrest-evidence.sh mutation
```

The harness captures the target row's content checksum when that row appears
on the first fetch attempt, reruns the target guard, and then starts only the
manifest-reviewed Node.js/psql pair. Cursor and count are validated as reader
evidence against the sealed fixture manifest; they are not SQL inputs. The
validated launcher independently requires the exact staging ref and direct
database URI. In that connection, the fixed SQL binds the exact policy/marker
identity, repository/manifest fingerprints, fixture prefix, event membership,
published version, and pre-mutation target-row checksum. It locks and updates
exactly one business field on the current published fixture version, verifies
a new checksum, commits, and must return one exact completion token. There is
no operator-supplied mutation executable and no database URI in argv or
evidence. Any target drift, repeated mutation, extra output, or wrong checksum
fails closed.

## 4. Clean the exact manifest

Keep the same refs, database URI, run ID, evidence root, and ref-matching
confirmation:

```bash
export GALLR_STAGING_REHEARSAL_CONFIRM="$GALLR_EXPECTED_STAGING_PROJECT_REF"
./scripts/staging-rehearsal/run-safe-bash.sh \
  scripts/staging-rehearsal/fixtures/cleanup.sh
```

Cleanup refuses to run unless the protected identity and manifest match the
environment and the database still contains the exact expected 1,205-row set
and the manifest-bound random UUID-set hashes. It also refuses cleanup if an
unrelated canonical/legacy row references the fixture event/editor, or if a
submission, media attachment, or import link references fixture-owned rows.
It deletes only curation and media attachments associated with those exact
canonical IDs, their exact versions and identities, the one exact media asset,
the two exact events, and the one exact editor. It verifies every affected-row
count and restores all recorded full-row table hashes plus canonical, V2, and
legacy integrity/reconciliation inside the same transaction.

It never deletes `content.audit_log`, never updates the private catalog runtime,
never changes grants, and never writes to `public.exhibitions` directly. If a
legacy compatibility row remains because runtime ownership changed during the
rehearsal, cleanup rolls back and requires operator review instead of bypassing
the bridge.

Successful cleanup adds `cleaned.json` while retaining all prior evidence.
The audit count may be greater than the baseline. Fixture SQL never mutates the
audit table and cleanup requires a non-decreasing count, but this lifecycle
does not hash baseline audit IDs or payloads and therefore does not attest
against unrelated privileged audit edits between its two transactions.

### Interrupted local finalization

The JSON result is emitted immediately before each SQL `COMMIT`, so an
`*.incomplete` file alone is never treated as proof that its transaction
committed.

If provisioning was interrupted after producing a structurally exact
`provisioned.json` or `provisioned.json.incomplete`, `cleanup.sh` can consume it
with the matching identity and baseline even when `manifest.json` was not
finalized. It reruns both target guards and passes only the validated UUID-set
hashes to the cleanup transaction. That transaction must prove the complete
fixture set before deleting anything. Provision/manifest evidence is promoted
to mode `0400` only after cleanup succeeds; a failed or uncommitted provision
therefore cannot be mistaken for a completed cleanup.

If cleanup was interrupted with a structurally exact
`cleaned.json.incomplete`, rerunning `cleanup.sh` does not execute the deletion
transaction again. After both target guards, it captures current state with the
read-only `baseline.sql` and requires every stored count and checksum,
including audit count, to match exactly. Only then does it promote the cleanup
evidence. A valid already-final `cleaned.json` uses the same read-only check for
an idempotent retry. Failed verification is sealed and retained alongside the
incomplete result; the wrapper does not delete evidence or repair database
state automatically.

## Network-free tooling checks

These tests stub both the linked-target assertion and `psql`; they never open a
database connection:

```bash
scripts/staging-rehearsal/fixtures/tests/guards.test.sh
scripts/staging-rehearsal/fixtures/tests/lifecycle.test.sh
```

The first proves hostile refs/URIs, confirmations, evidence paths, existing run
directories, malformed cleanup artifacts, shell startup injection, and
inherited libpq overrides fail closed. The second exercises normal lifecycle
finalization, interrupted provision/manifest recovery, interrupted cleanup
finalization, and exact baseline-drift rejection with deterministic fake
database output.

## Limitations

- This proves canonical-to-V2 projection and real Data API transport, not the
  browser admin command lifecycle or physical Storage upload/delivery.
- Direct owner-level fixture inserts do not manufacture admin audit events.
- Cleanup expects the stable fixture shape: 1,205 versions, two curations, and
  one media attachment. Creating additional fixture versions or media requires
  separate reviewed cleanup or restoration of the isolated clone.
- If unrelated staging data changes after baseline capture, cleanup rolls back.
  Restore quiet staging state or restore the clone; do not weaken the guards.
- A database transaction reported as failed leaves `*.incomplete` evidence.
  Use only the guarded `cleanup.sh` recovery described above. Invalid,
  conflicting, symlinked, hard-linked, or baseline-drifted evidence requires
  operator review or restoration of the isolated clone; never remove artifacts
  merely to force a retry and never reuse the run ID for provisioning.
