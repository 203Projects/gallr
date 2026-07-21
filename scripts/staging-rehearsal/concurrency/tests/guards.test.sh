#!/usr/bin/env bash
set -euo pipefail

TEST_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
COMMON_PATH=$(cd "$TEST_DIR/.." && pwd -P)/common.sh
RUN_PATH=$(cd "$TEST_DIR/.." && pwd -P)/run.sh
CONCURRENCY_DIR=$(cd "$TEST_DIR/.." && pwd -P)
REPO_ROOT=$(cd "$TEST_DIR/../../../.." && pwd -P)
BRIDGE_MIGRATION="$REPO_ROOT/supabase/migrations/20260721120000_public_exhibition_catalog_v2.sql"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/gallr-concurrency-guards.XXXXXX")
FAKE_BIN="$TEST_ROOT/bin"
EVIDENCE_ROOT="$TEST_ROOT/evidence"
PSQL_MARKER="$TEST_ROOT/psql-was-invoked"
SEQUENCE_LOG="$TEST_ROOT/sequence.log"

cleanup() {
  case "$TEST_ROOT" in
    "${TMPDIR:-/tmp}"/gallr-concurrency-guards.*)
      rm -rf -- "$TEST_ROOT"
      ;;
    *)
      printf 'Refusing unsafe test cleanup path: %s\n' "$TEST_ROOT" >&2
      ;;
  esac
}
trap cleanup EXIT HUP INT TERM

mkdir -m 700 "$FAKE_BIN" "$EVIDENCE_ROOT"
printf '%s\n' \
  '#!/bin/sh' \
  'for name in PGPASSWORD PGHOSTADDR PGSSLROOTCERT PGTARGETSESSIONATTRS PGLOADBALANCEHOSTS; do' \
  '  eval "value=\${$name-}"' \
  '  if [ -n "$value" ]; then printf "inherited libpq override: %s\n" "$name" >&2; exit 96; fi' \
  'done' \
  '[ "${PGDATABASE:-}" = "$EXPECTED_DATABASE_URL" ] || { printf "unsafe PGDATABASE\n" >&2; exit 92; }' \
  '[ "${PGSSLMODE:-}" = verify-full ] || { printf "unsafe PGSSLMODE\n" >&2; exit 91; }' \
  '[ "${PGPASSFILE:-}" = /dev/null ] || { printf "unsafe PGPASSFILE\n" >&2; exit 94; }' \
  '[ "${PGOPTIONS:-}" = "-c statement_timeout=50s -c lock_timeout=40s" ] || { printf "unsafe PGOPTIONS\n" >&2; exit 95; }' \
  'if [ "${INJECT_UNEXPECTED_EVIDENCE:-0}" = 1 ]; then' \
  '  mkfifo "$CONCURRENCY_RUN_DIR/unexpected.fifo" || exit 93' \
  'fi' \
  'touch "$PSQL_MARKER"' \
  'printf "psql\\n" >> "$SEQUENCE_LOG"' \
  'exit 97' > "$FAKE_BIN/psql"
chmod 700 "$FAKE_BIN/psql"
printf '%s\n' \
  '#!/bin/sh' \
  'if [ "${1:-}" = "$LINKED_GUARD_EXPECTED_PATH" ]; then' \
  '  printf "guard\\n" >> "$SEQUENCE_LOG"' \
  '  printf "PASS: stubbed linked staging guard\\n"' \
  '  exit 0' \
  'fi' \
  'if [ "${1:-}" = "$IDENTITY_GUARD_EXPECTED_PATH" ]; then' \
  '  printf "identity\\n" >> "$SEQUENCE_LOG"' \
  '  [ "${IDENTITY_GUARD_SHOULD_FAIL:-0}" = 0 ] || exit 88' \
  '  printf "PASS: independent policy and disposable-clone marker identify staging\\n"' \
  '  exit 0' \
  'fi' \
  'exec /bin/bash "$@"' > "$FAKE_BIN/bash"
chmod 700 "$FAKE_BIN/bash"
printf '%s\n' \
  '#!/bin/sh' \
  '/bin/mkdir "$@" || exit $?' \
  'if [ "${INJECT_DANGLING_EVIDENCE:-0}" = 1 ]; then' \
  '  ln -s "$DANGLING_EVIDENCE_TARGET" "$CONCURRENCY_RUN_DIR/preflight.tsv" || exit 87' \
  'fi' > "$FAKE_BIN/mkdir"
chmod 700 "$FAKE_BIN/mkdir"
if command -v shasum >/dev/null 2>&1; then
  BRIDGE_SHA256=$(shasum -a 256 "$BRIDGE_MIGRATION" | awk '{print $1}')
else
  BRIDGE_SHA256=$(sha256sum "$BRIDGE_MIGRATION" | awk '{print $1}')
fi
REPOSITORY_COMMIT=$(git -C "$REPO_ROOT" rev-parse --verify 'HEAD^{commit}')

write_operator_manifest() {
  local repository_commit="$1"

  chmod 600 "$EVIDENCE_ROOT/operator-manifest.txt" 2>/dev/null || true
  printf 'manifest_schema=1\nrepository_commit=%s\n%s  %s\n' \
    "$repository_commit" \
    "$BRIDGE_SHA256" \
    'supabase/migrations/20260721120000_public_exhibition_catalog_v2.sql' \
    > "$EVIDENCE_ROOT/operator-manifest.txt"
  chmod 444 "$EVIDENCE_ROOT/operator-manifest.txt"
}

write_operator_manifest "$REPOSITORY_COMMIT"

STAGING_REF='aaaaaaaaaaaaaaaaaaaa'
PRODUCTION_REF='bbbbbbbbbbbbbbbbbbbb'
RUN_ID='bridge-test-0001'
REASON='approved staging rehearsal bridge-test-0001'
TARGET_ID='representative-exhibition'
DATABASE_URL="postgresql://postgres:redacted@db.$STAGING_REF.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=%2Ftmp%2Fgallr-staging-root-ca.pem"

validate() {
  env \
    PATH="$FAKE_BIN:$PATH" \
    GIT_DIR="$TEST_ROOT/forged-git-dir" \
    GIT_WORK_TREE="$TEST_ROOT/forged-work-tree" \
    GIT_CONFIG_GLOBAL="$TEST_ROOT/forged-git-config" \
    GIT_CONFIG_SYSTEM="$TEST_ROOT/forged-git-system-config" \
    GALLR_EXPECTED_STAGING_PROJECT_REF="${1:-$STAGING_REF}" \
    GALLR_PRODUCTION_PROJECT_REF="${2:-$PRODUCTION_REF}" \
    GALLR_STAGING_DATABASE_URL="${3:-$DATABASE_URL}" \
    GALLR_STAGING_REHEARSAL_CONFIRM="${4:-$STAGING_REF}" \
    GALLR_STAGING_IDENTITY_POLICY_PATH="$TEST_ROOT/identity-policy.txt" \
    GALLR_CONCURRENCY_EVIDENCE_DIR="$EVIDENCE_ROOT" \
    GALLR_CONCURRENCY_RUN_ID="$RUN_ID" \
    GALLR_CONCURRENCY_APPROVAL_REASON="$REASON" \
    GALLR_CONCURRENCY_TARGET_EXHIBITION_ID="$TARGET_ID" \
    bash -c 'source "$1"; concurrency_validate_environment' _ "$COMMON_PATH"
}

assert_rejected() {
  local expected="$1"
  shift
  local output

  if output=$(validate "$@" 2>&1); then
    printf 'Expected guard rejection containing: %s\n' "$expected" >&2
    exit 1
  fi
  grep -Fq "$expected" <<< "$output" || {
    printf 'Guard failed for an unexpected reason: %s\n' "$output" >&2
    exit 1
  }
}

bash "$RUN_PATH" --help >/dev/null
validate
[[ ! -e "$PSQL_MARKER" ]] || {
  printf 'Static validation unexpectedly invoked psql.\n' >&2
  exit 1
}

write_operator_manifest '0000000000000000000000000000000000000000'
assert_rejected 'repository commit does not match the operator manifest'
write_operator_manifest "$REPOSITORY_COMMIT"

assert_rejected 'staging and production project refs must differ' \
  "$STAGING_REF" "$STAGING_REF"
assert_rejected 'must be exactly 20 lowercase alphanumeric characters' \
  'short-ref' "$PRODUCTION_REF"
assert_rejected 'database URL does not identify the expected staging project' \
  "$STAGING_REF" "$PRODUCTION_REF" \
  'postgresql://postgres:redacted@db.not-the-ref.invalid/postgres?sslmode=verify-full&sslrootcert=%2Ftmp%2Fgallr-staging-root-ca.pem'
SUBSTRING_SPOOF="postgresql://postgres:$STAGING_REF@db.$PRODUCTION_REF.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=%2Ftmp%2Fgallr-staging-root-ca.pem"
assert_rejected 'database URL does not identify the expected staging project' \
  "$STAGING_REF" "$PRODUCTION_REF" "$SUBSTRING_SPOOF"
assert_rejected 'database URL does not identify the expected staging project' \
  "$STAGING_REF" "$PRODUCTION_REF" \
  "postgresql://postgres:redacted@db.$STAGING_REF.supabase.co:5432/postgres?sslmode=disable&sslrootcert=%2Ftmp%2Fgallr-staging-root-ca.pem"
assert_rejected 'database URL does not identify the expected staging project' \
  "$STAGING_REF" "$PRODUCTION_REF" \
  "postgresql://postgres.$STAGING_REF:redacted@aws-0-region.pooler.supabase.com:5432/postgres?sslmode=verify-full&sslrootcert=%2Ftmp%2Fgallr-staging-root-ca.pem"
assert_rejected 'must exactly equal the staging project ref' \
  "$STAGING_REF" "$PRODUCTION_REF" "$DATABASE_URL" "$PRODUCTION_REF"

PRODUCTION_URL="postgresql://postgres:redacted@$PRODUCTION_REF.$STAGING_REF.invalid/postgres?sslmode=verify-full&sslrootcert=%2Ftmp%2Fgallr-staging-root-ca.pem"
assert_rejected 'database URL does not identify the expected staging project' \
  "$STAGING_REF" "$PRODUCTION_REF" "$PRODUCTION_URL"

run_coordinator() {
  local test_run_id="$1"
  shift

  env \
    PATH="$FAKE_BIN:$PATH" \
    GIT_DIR="$TEST_ROOT/forged-git-dir" \
    GIT_WORK_TREE="$TEST_ROOT/forged-work-tree" \
    GIT_CONFIG_GLOBAL="$TEST_ROOT/forged-git-config" \
    GIT_CONFIG_SYSTEM="$TEST_ROOT/forged-git-system-config" \
    PSQL_MARKER="$PSQL_MARKER" \
    SEQUENCE_LOG="$SEQUENCE_LOG" \
    EXPECTED_DATABASE_URL="$DATABASE_URL" \
    LINKED_GUARD_EXPECTED_PATH="$REPO_ROOT/scripts/staging-rehearsal/assert-linked-staging.sh" \
    IDENTITY_GUARD_EXPECTED_PATH="$REPO_ROOT/scripts/staging-rehearsal/assert-disposable-clone-target.sh" \
    GALLR_EXPECTED_STAGING_PROJECT_REF="$STAGING_REF" \
    GALLR_PRODUCTION_PROJECT_REF="$PRODUCTION_REF" \
    GALLR_STAGING_DATABASE_URL="$DATABASE_URL" \
    GALLR_STAGING_REHEARSAL_CONFIRM="$STAGING_REF" \
    GALLR_STAGING_IDENTITY_POLICY_PATH="$TEST_ROOT/identity-policy.txt" \
    GALLR_CONCURRENCY_EVIDENCE_DIR="$EVIDENCE_ROOT" \
    GALLR_CONCURRENCY_RUN_ID="$test_run_id" \
    GALLR_CONCURRENCY_APPROVAL_REASON="approved staging rehearsal $test_run_id" \
    GALLR_CONCURRENCY_TARGET_EXHIBITION_ID="$TARGET_ID" \
    PGPASSWORD='must-not-reach-psql' \
    PGPASSFILE='/must/not/reach/psql' \
    PGHOSTADDR='203.0.113.10' \
    PGOPTIONS='-c role=anon' \
    PGSSLROOTCERT='/must/not/reach/psql' \
    PGTARGETSESSIONATTRS='read-write' \
    PGLOADBALANCEHOSTS='random' \
    "$@" \
    /bin/bash "$RUN_PATH"
}

: > "$SEQUENCE_LOG"
if run_coordinator "$RUN_ID" > "$TEST_ROOT/run.stdout" 2> "$TEST_ROOT/run.stderr"; then
  printf 'Coordinator unexpectedly passed with the non-connecting psql stub.\n' >&2
  exit 1
fi

if grep -Fq 'inherited libpq override:' "$TEST_ROOT/run.stderr"; then
  printf 'Coordinator leaked an inherited libpq override to psql.\n' >&2
  exit 1
fi

[[ "$(sed -n '1p' "$SEQUENCE_LOG")" == "guard" ]] || {
  printf 'Linked-target guard did not run before psql.\n' >&2
  sed -n '1,160p' "$TEST_ROOT/run.stderr" >&2
  exit 1
}
[[ "$(sed -n '2p' "$SEQUENCE_LOG")" == "identity" ]] || {
  printf 'Disposable-clone identity guard did not follow the linked guard.\n' >&2
  exit 1
}
[[ "$(sed -n '3p' "$SEQUENCE_LOG")" == "psql" ]] || {
  printf 'Expected the psql stub after both target guards.\n' >&2
  sed -n '1,120p' "$TEST_ROOT/run.stderr" >&2
  exit 1
}

mode_of() {
  stat -f '%Lp' "$1" 2>/dev/null || stat -c '%a' "$1"
}
for sealed_path in "$EVIDENCE_ROOT/$RUN_ID"/*; do
  [[ "$(mode_of "$sealed_path")" == "400" ]] || {
    printf 'Run evidence was not sealed mode 0400: %s\n' "$sealed_path" >&2
    exit 1
  }
done

MAIN_MANIFEST="$EVIDENCE_ROOT/$RUN_ID/manifest.tsv"
linked_line=$(awk -F '\t' '$2 == "linked_target_verified" { print NR }' "$MAIN_MANIFEST")
identity_line=$(awk -F '\t' '$2 == "target_identity_verified" { print NR }' "$MAIN_MANIFEST")
prepared_line=$(awk -F '\t' '$2 == "prepared" && $3 == "fail_closed_guards_passed" { print NR }' "$MAIN_MANIFEST")
failed_line=$(awk -F '\t' '$2 == "failed" { line = NR } END { print line }' "$MAIN_MANIFEST")
[[ "$linked_line" =~ ^[0-9]+$ && "$identity_line" =~ ^[0-9]+$ &&
   "$prepared_line" =~ ^[0-9]+$ && "$failed_line" =~ ^[0-9]+$ &&
   "$linked_line" -lt "$identity_line" && "$identity_line" -lt "$prepared_line" &&
   "$prepared_line" -lt "$failed_line" ]] || {
  printf 'Manifest did not record guard completion before prepared status.\n' >&2
  exit 1
}

IDENTITY_FAILURE_RUN_ID='bridge-test-identity'
rm -f "$PSQL_MARKER"
: > "$SEQUENCE_LOG"
if run_coordinator "$IDENTITY_FAILURE_RUN_ID" \
  IDENTITY_GUARD_SHOULD_FAIL=1 \
  > "$TEST_ROOT/identity-failure.stdout" \
  2> "$TEST_ROOT/identity-failure.stderr"; then
  printf 'Coordinator unexpectedly ignored target-identity guard failure.\n' >&2
  exit 1
fi
IDENTITY_FAILURE_MANIFEST="$EVIDENCE_ROOT/$IDENTITY_FAILURE_RUN_ID/manifest.tsv"
[[ -f "$IDENTITY_FAILURE_MANIFEST" && ! -L "$IDENTITY_FAILURE_MANIFEST" ]] || exit 86
if grep -Fq $'prepared\tfail_closed_guards_passed' "$IDENTITY_FAILURE_MANIFEST"; then
  printf 'Prepared marker was written before every target guard passed.\n' >&2
  exit 1
fi
grep -Fq $'failed\texit_status=' "$IDENTITY_FAILURE_MANIFEST" || {
  printf 'Identity-guard failure was not retained in the run manifest.\n' >&2
  exit 1
}
[[ ! -e "$PSQL_MARKER" ]] || {
  printf 'Identity-guard failure unexpectedly reached psql.\n' >&2
  exit 1
}

DANGLING_RUN_ID='bridge-test-dangling'
DANGLING_TARGET="$TEST_ROOT/dangling-evidence-target"
: > "$SEQUENCE_LOG"
if run_coordinator "$DANGLING_RUN_ID" \
  INJECT_DANGLING_EVIDENCE=1 \
  DANGLING_EVIDENCE_TARGET="$DANGLING_TARGET" \
  > "$TEST_ROOT/dangling.stdout" \
  2> "$TEST_ROOT/dangling.stderr"; then
  printf 'Coordinator unexpectedly accepted dangling evidence.\n' >&2
  exit 1
fi
grep -Fq 'refusing to overwrite evidence:' "$TEST_ROOT/dangling.stderr"
grep -Fq 'evidence entry is not an owned regular file:' "$TEST_ROOT/dangling.stderr"
[[ -L "$EVIDENCE_ROOT/$DANGLING_RUN_ID/preflight.tsv" ]] || exit 87
[[ ! -e "$DANGLING_TARGET" && ! -L "$DANGLING_TARGET" ]] || {
  printf 'Dangling evidence target was unexpectedly created.\n' >&2
  exit 1
}

UNEXPECTED_RUN_ID='bridge-test-unexpected'
: > "$SEQUENCE_LOG"
if run_coordinator "$UNEXPECTED_RUN_ID" \
  INJECT_UNEXPECTED_EVIDENCE=1 \
  > "$TEST_ROOT/unexpected.stdout" \
  2> "$TEST_ROOT/unexpected.stderr"; then
  printf 'Coordinator unexpectedly accepted an unexpected FIFO.\n' >&2
  exit 1
fi
grep -Fq 'unexpected evidence entry:' "$TEST_ROOT/unexpected.stderr"
[[ -p "$EVIDENCE_ROOT/$UNEXPECTED_RUN_ID/unexpected.fifo" ]] || exit 88
UNEXPECTED_MANIFEST="$EVIDENCE_ROOT/$UNEXPECTED_RUN_ID/manifest.tsv"
grep -Fq 'evidence_inventory_invalid=true' "$UNEXPECTED_MANIFEST" || {
  printf 'Unexpected evidence was not recorded in the run manifest.\n' >&2
  exit 1
}
[[ "$(mode_of "$UNEXPECTED_MANIFEST")" == "400" ]] || exit 89

chmod 755 "$EVIDENCE_ROOT"
assert_rejected 'evidence root must have mode 0700'

for deployed_lock in \
  'pg_catalog.pg_advisory_xact_lock(73241, 1)' \
  'lock table public.exhibitions in share mode' \
  'lock table public.exhibition_catalog_v2 in share mode'; do
  grep -Fq "$deployed_lock" "$BRIDGE_MIGRATION" || {
    printf 'Deployed lock contract changed: %s\n' "$deployed_lock" >&2
    exit 1
  }
  grep -Fq "$deployed_lock" "$CONCURRENCY_DIR/activation.sql" || {
    printf 'Coordinator lock contract drifted: %s\n' "$deployed_lock" >&2
    exit 1
  }
done

if grep -Eiq \
  'pg_terminate_backend|delete[[:space:]]+from[[:space:]]+content\.audit_log|update[[:space:]]+content_private\.exhibition_catalog_runtime|grant[[:space:]].*public\.exhibitions' \
  "$CONCURRENCY_DIR"/*.sql; then
  printf 'Coordinator SQL contains a forbidden cleanup operation.\n' >&2
  exit 1
fi

printf 'PASS: staging concurrency guards fail closed without a database connection.\n'
