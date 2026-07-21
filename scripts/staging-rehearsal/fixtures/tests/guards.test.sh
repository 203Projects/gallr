#!/usr/bin/env bash
set -euo pipefail

TEST_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
FIXTURE_DIR=$(cd "$TEST_DIR/.." && pwd -P)
COMMON_PATH="$FIXTURE_DIR/common.sh"
PROVISION_PATH="$FIXTURE_DIR/provision.sh"
CLEANUP_PATH="$FIXTURE_DIR/cleanup.sh"
REPO_ROOT=$(cd "$FIXTURE_DIR/../../.." && pwd -P)
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/gallr-fixture-guards.XXXXXX")
FAKE_BIN="$TEST_ROOT/bin"
EVIDENCE_ROOT="$TEST_ROOT/evidence"
PSQL_MARKER="$TEST_ROOT/psql-was-invoked"
SEQUENCE_LOG="$TEST_ROOT/sequence.log"
INSIDE_REPO=''

cleanup() {
  if [[ -n "$INSIDE_REPO" && -d "$INSIDE_REPO" && ! -L "$INSIDE_REPO" ]]; then
    rmdir "$INSIDE_REPO" 2>/dev/null || true
  fi
  case "$TEST_ROOT" in
    "${TMPDIR:-/tmp}"/gallr-fixture-guards.*)
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
  'for forbidden in PGHOST PGHOSTADDR PGPASSWORD PGPORT PGSERVICE PGSERVICEFILE PGUSER; do' \
  '  eval "value=\${$forbidden-}"' \
  '  [ -z "$value" ] || exit 91' \
  'done' \
  '[ "${PGDATABASE:-}" = "$EXPECTED_DATABASE_URL" ] || exit 92' \
  '[ "${PGSSLMODE:-}" = verify-full ] || exit 93' \
  '[ "${PGPASSFILE:-}" = /dev/null ] || exit 89' \
  '[ -z "${GALLR_EXPECTED_STAGING_PROJECT_REF:-}" ] || exit 94' \
  '[ -z "${GALLR_PRODUCTION_PROJECT_REF:-}" ] || exit 95' \
  '[ -z "${GALLR_STAGING_DATABASE_URL:-}" ] || exit 96' \
  '[ -z "${GALLR_STAGING_REHEARSAL_CONFIRM:-}" ] || exit 87' \
  'for argument in "$@"; do [ "$argument" != "$EXPECTED_DATABASE_URL" ] || exit 90; done' \
  'touch "$PSQL_MARKER"' \
  'printf "psql\n" >> "$SEQUENCE_LOG"' \
  'exit 97' > "$FAKE_BIN/psql"
chmod 700 "$FAKE_BIN/psql"

printf '%s\n' \
  '#!/bin/sh' \
  'if [ "${1:-}" = "$LINKED_GUARD_EXPECTED_PATH" ]; then' \
  '  [ "${BASH_ENV:-}" = /dev/null ] || exit 86' \
  '  [ "${ENV:-}" = /dev/null ] || exit 85' \
  '  printf "guard\n" >> "$SEQUENCE_LOG"' \
  '  [ "${LINKED_GUARD_SHOULD_FAIL:-0}" = 0 ] || exit 89' \
  '  printf "PASS: stubbed linked staging guard\n"' \
  '  exit 0' \
  'fi' \
  'if [ "${1:-}" = "$IDENTITY_GUARD_EXPECTED_PATH" ]; then' \
  '  [ "${BASH_ENV:-}" = /dev/null ] || exit 84' \
  '  [ "${ENV:-}" = /dev/null ] || exit 83' \
  '  printf "identity\n" >> "$SEQUENCE_LOG"' \
  '  [ "${IDENTITY_GUARD_SHOULD_FAIL:-0}" = 0 ] || exit 88' \
  '  printf "PASS: independent policy and disposable-clone marker identify staging\n"' \
  '  exit 0' \
  'fi' \
  'exec /bin/bash "$@"' > "$FAKE_BIN/bash"
chmod 700 "$FAKE_BIN/bash"

STAGING_REF='aaaaaaaaaaaaaaaaaaaa'
PRODUCTION_REF='bbbbbbbbbbbbbbbbbbbb'
RUN_ID='fixture-test-0001'
DATABASE_URL="postgresql://postgres:redacted@db.$STAGING_REF.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=%2Ftmp%2Fgallr-staging-root-ca.pem"
LINKED_GUARD_PATH="$REPO_ROOT/scripts/staging-rehearsal/assert-linked-staging.sh"
IDENTITY_GUARD_PATH="$REPO_ROOT/scripts/staging-rehearsal/assert-disposable-clone-target.sh"

sha256_text() {
  if command -v shasum >/dev/null 2>&1; then
    printf '%s' "$1" | shasum -a 256 | awk '{print $1}'
  else
    printf '%s' "$1" | sha256sum | awk '{print $1}'
  fi
}

validate() {
  env \
    PATH="$FAKE_BIN:$PATH" \
    LINKED_GUARD_EXPECTED_PATH="$LINKED_GUARD_PATH" \
    IDENTITY_GUARD_EXPECTED_PATH="$IDENTITY_GUARD_PATH" \
    SEQUENCE_LOG="$SEQUENCE_LOG" \
    GALLR_EXPECTED_STAGING_PROJECT_REF="${1:-$STAGING_REF}" \
    GALLR_PRODUCTION_PROJECT_REF="${2:-$PRODUCTION_REF}" \
    GALLR_STAGING_DATABASE_URL="${3:-$DATABASE_URL}" \
    GALLR_STAGING_REHEARSAL_CONFIRM="${4:-$STAGING_REF}" \
    GALLR_STAGING_EVIDENCE_DIR="${5:-$EVIDENCE_ROOT}" \
    GALLR_STAGING_IDENTITY_POLICY_PATH="$TEST_ROOT/identity-policy.txt" \
    GALLR_FIXTURE_RUN_ID="$RUN_ID" \
    GALLR_FIXTURE_CONNECT_TIMEOUT_SECONDS="${6:-15}" \
    /bin/bash -c 'source "$1"; fixture_validate_environment guard-test' _ "$COMMON_PATH"
}

run_cleanup_wrapper() {
  env \
    PATH="$FAKE_BIN:$PATH" \
    LINKED_GUARD_EXPECTED_PATH="$LINKED_GUARD_PATH" \
    IDENTITY_GUARD_EXPECTED_PATH="$IDENTITY_GUARD_PATH" \
    SEQUENCE_LOG="$SEQUENCE_LOG" \
    GALLR_EXPECTED_STAGING_PROJECT_REF="$STAGING_REF" \
    GALLR_PRODUCTION_PROJECT_REF="$PRODUCTION_REF" \
    GALLR_STAGING_DATABASE_URL="$DATABASE_URL" \
    GALLR_STAGING_REHEARSAL_CONFIRM="$STAGING_REF" \
    GALLR_STAGING_EVIDENCE_DIR="$EVIDENCE_ROOT" \
    GALLR_STAGING_IDENTITY_POLICY_PATH="$TEST_ROOT/identity-policy.txt" \
    GALLR_FIXTURE_RUN_ID="$RUN_ID" \
    /bin/bash "$CLEANUP_PATH"
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
  [[ ! -e "$PSQL_MARKER" ]] || {
    printf 'Rejected input reached psql.\n' >&2
    exit 1
  }
}

validate >/dev/null
[[ ! -e "$PSQL_MARKER" ]] || {
  printf 'Static environment validation unexpectedly invoked psql.\n' >&2
  exit 1
}

assert_rejected 'must be exactly 20 lowercase alphanumeric characters' \
  'short-ref' "$PRODUCTION_REF"
assert_rejected 'staging and production project refs must differ' \
  "$STAGING_REF" "$STAGING_REF"
assert_rejected 'database URL target validation failed' \
  "$STAGING_REF" "$PRODUCTION_REF" \
  "postgresql://postgres:$STAGING_REF@db.$PRODUCTION_REF.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=%2Ftmp%2Fgallr-staging-root-ca.pem"
assert_rejected 'database URL target validation failed' \
  "$STAGING_REF" "$PRODUCTION_REF" \
  "postgresql://postgres:redacted@db.$STAGING_REF.supabase.co:5432/postgres?sslmode=disable&sslrootcert=%2Ftmp%2Fgallr-staging-root-ca.pem"
assert_rejected 'database URL target validation failed' \
  "$STAGING_REF" "$PRODUCTION_REF" \
  "postgresql://postgres.$STAGING_REF:redacted@aws-0-region.pooler.supabase.com:6543/postgres?sslmode=verify-full&sslrootcert=%2Ftmp%2Fgallr-staging-root-ca.pem"
assert_rejected 'confirmation must exactly equal the expected staging project ref' \
  "$STAGING_REF" "$PRODUCTION_REF" "$DATABASE_URL" "$PRODUCTION_REF"
assert_rejected 'must be between 5 and 60' \
  "$STAGING_REF" "$PRODUCTION_REF" "$DATABASE_URL" "$STAGING_REF" "$EVIDENCE_ROOT" 0

chmod 755 "$EVIDENCE_ROOT"
assert_rejected 'evidence directory must have mode 0700'
chmod 700 "$EVIDENCE_ROOT"

INSIDE_REPO="$REPO_ROOT/scripts/staging-rehearsal/fixtures/tests/.guard-evidence-$$"
mkdir -m 700 "$INSIDE_REPO"
assert_rejected 'evidence must be stored outside the repository' \
  "$STAGING_REF" "$PRODUCTION_REF" "$DATABASE_URL" "$STAGING_REF" "$INSIDE_REPO"
rmdir "$INSIDE_REPO"

EVIDENCE_LINK="$TEST_ROOT/evidence-link"
ln -s "$EVIDENCE_ROOT" "$EVIDENCE_LINK"
assert_rejected 'must not be a symbolic link' \
  "$STAGING_REF" "$PRODUCTION_REF" "$DATABASE_URL" "$STAGING_REF" "$EVIDENCE_LINK"

if env \
  PATH="$FAKE_BIN:$PATH" \
  LINKED_GUARD_EXPECTED_PATH="$LINKED_GUARD_PATH" \
  IDENTITY_GUARD_EXPECTED_PATH="$IDENTITY_GUARD_PATH" \
  LINKED_GUARD_SHOULD_FAIL=1 \
  SEQUENCE_LOG="$SEQUENCE_LOG" \
  GALLR_EXPECTED_STAGING_PROJECT_REF="$STAGING_REF" \
  GALLR_PRODUCTION_PROJECT_REF="$PRODUCTION_REF" \
  GALLR_STAGING_DATABASE_URL="$DATABASE_URL" \
  GALLR_STAGING_REHEARSAL_CONFIRM="$STAGING_REF" \
  GALLR_STAGING_EVIDENCE_DIR="$EVIDENCE_ROOT" \
  GALLR_STAGING_IDENTITY_POLICY_PATH="$TEST_ROOT/identity-policy.txt" \
  GALLR_FIXTURE_RUN_ID="$RUN_ID" \
  /bin/bash "$PROVISION_PATH" > "$TEST_ROOT/linked.stdout" 2> "$TEST_ROOT/linked.stderr"; then
  printf 'Provision unexpectedly ignored a linked-target guard failure.\n' >&2
  exit 1
fi
grep -Fq 'linked staging target did not match' "$TEST_ROOT/linked.stderr"
[[ ! -e "$PSQL_MARKER" ]] || {
  printf 'Linked-target rejection reached psql.\n' >&2
  exit 1
}

RUN_DIR="$EVIDENCE_ROOT/fixtures-$RUN_ID"
mkdir -m 700 "$RUN_DIR"
if env \
  PATH="$FAKE_BIN:$PATH" \
  LINKED_GUARD_EXPECTED_PATH="$LINKED_GUARD_PATH" \
  IDENTITY_GUARD_EXPECTED_PATH="$IDENTITY_GUARD_PATH" \
  SEQUENCE_LOG="$SEQUENCE_LOG" \
  GALLR_EXPECTED_STAGING_PROJECT_REF="$STAGING_REF" \
  GALLR_PRODUCTION_PROJECT_REF="$PRODUCTION_REF" \
  GALLR_STAGING_DATABASE_URL="$DATABASE_URL" \
  GALLR_STAGING_REHEARSAL_CONFIRM="$STAGING_REF" \
  GALLR_STAGING_EVIDENCE_DIR="$EVIDENCE_ROOT" \
  GALLR_STAGING_IDENTITY_POLICY_PATH="$TEST_ROOT/identity-policy.txt" \
  GALLR_FIXTURE_RUN_ID="$RUN_ID" \
  /bin/bash "$PROVISION_PATH" > "$TEST_ROOT/existing.stdout" 2> "$TEST_ROOT/existing.stderr"; then
  printf 'Provision unexpectedly accepted an existing run directory.\n' >&2
  exit 1
fi
grep -Fq 'evidence run already exists' "$TEST_ROOT/existing.stderr"
[[ ! -e "$PSQL_MARKER" ]] || {
  printf 'Existing-run rejection reached psql.\n' >&2
  exit 1
}
rmdir "$RUN_DIR"

mkdir -m 700 "$RUN_DIR"
STAGING_SHA=$(sha256_text "$STAGING_REF")
PRODUCTION_SHA=$(sha256_text "$PRODUCTION_REF")
PREFIX="gallr-rehearsal-$RUN_ID-"
LOAD_EVENT_ID="${PREFIX}event.catalog.v2,(load):한글"
EMPTY_EVENT_ID="${PREFIX}event.catalog.v2,(empty):한글"
EDITOR_ID="${PREFIX}editor.special,(guest):한글"
BOUNDARY_ID="${PREFIX}catalog-0500.cursor,(reserved):한글"
MUTATION_ID="${PREFIX}catalog-0750.mutate,(same-id):한글"
MEDIA_OBJECT_PATH="staging-rehearsal/${PREFIX}cover-0005.webp"
printf '%s\t%s\t%s\t%s\n' \
  "$RUN_ID" "$PREFIX" "$STAGING_SHA" "$PRODUCTION_SHA" > "$RUN_DIR/identity.tsv"
node "$TEST_DIR/fake-evidence.mjs" baseline > "$RUN_DIR/baseline.tsv"
node "$TEST_DIR/fake-evidence.mjs" provision \
  "$PREFIX" "$LOAD_EVENT_ID" "$EMPTY_EVENT_ID" "$EDITOR_ID" \
  "$BOUNDARY_ID" "$MUTATION_ID" "$MEDIA_OBJECT_PATH" \
  > "$RUN_DIR/provisioned.json.incomplete"
touch "$RUN_DIR/cleaned.json.incomplete"
chmod 600 \
  "$RUN_DIR/identity.tsv" \
  "$RUN_DIR/baseline.tsv" \
  "$RUN_DIR/provisioned.json.incomplete" \
  "$RUN_DIR/cleaned.json.incomplete"

ln "$RUN_DIR/identity.tsv" "$RUN_DIR/identity.tsv.hardlink"
if run_cleanup_wrapper > "$TEST_ROOT/hardlink.stdout" 2> "$TEST_ROOT/hardlink.stderr"; then
  printf 'Cleanup unexpectedly accepted hard-linked identity evidence.\n' >&2
  exit 1
fi
grep -Fq 'must have exactly one hard link' "$TEST_ROOT/hardlink.stderr"
[[ ! -e "$PSQL_MARKER" ]] || {
  printf 'Hard-link rejection reached psql.\n' >&2
  exit 1
}
rm "$RUN_DIR/identity.tsv.hardlink"

if run_cleanup_wrapper > "$TEST_ROOT/dangling.stdout" 2> "$TEST_ROOT/dangling.stderr"; then
  printf 'Cleanup unexpectedly accepted dangling evidence.\n' >&2
  exit 1
fi
grep -Fq 'database evidence is empty' "$TEST_ROOT/dangling.stderr"
[[ ! -e "$PSQL_MARKER" ]] || {
  printf 'Dangling-evidence rejection reached psql.\n' >&2
  exit 1
}

rm \
  "$RUN_DIR/identity.tsv" \
  "$RUN_DIR/baseline.tsv" \
  "$RUN_DIR/provisioned.json.incomplete" \
  "$RUN_DIR/cleaned.json.incomplete"
rmdir "$RUN_DIR"

: > "$SEQUENCE_LOG"
if env \
  PATH="$FAKE_BIN:$PATH" \
  EXPECTED_DATABASE_URL="$DATABASE_URL" \
  PSQL_MARKER="$PSQL_MARKER" \
  SEQUENCE_LOG="$SEQUENCE_LOG" \
  LINKED_GUARD_EXPECTED_PATH="$LINKED_GUARD_PATH" \
  IDENTITY_GUARD_EXPECTED_PATH="$IDENTITY_GUARD_PATH" \
  PGHOST='production.invalid' \
  PGHOSTADDR='203.0.113.10' \
  PGPASSWORD='must-not-reach-psql' \
  PGSERVICE='must-not-reach-psql' \
  GALLR_EXPECTED_STAGING_PROJECT_REF="$STAGING_REF" \
  GALLR_PRODUCTION_PROJECT_REF="$PRODUCTION_REF" \
  GALLR_STAGING_DATABASE_URL="$DATABASE_URL" \
  GALLR_STAGING_REHEARSAL_CONFIRM="$STAGING_REF" \
  GALLR_STAGING_EVIDENCE_DIR="$EVIDENCE_ROOT" \
  GALLR_STAGING_IDENTITY_POLICY_PATH="$TEST_ROOT/identity-policy.txt" \
  GALLR_FIXTURE_RUN_ID="$RUN_ID" \
  /bin/bash "$PROVISION_PATH" > "$TEST_ROOT/run.stdout" 2> "$TEST_ROOT/run.stderr"; then
  printf 'Provision unexpectedly passed with the non-connecting psql stub.\n' >&2
  exit 1
fi
[[ -e "$PSQL_MARKER" ]] || {
  printf 'Expected the valid wrapper to reach the psql stub.\n' >&2
  exit 1
}
[[ "$(sed -n '1p' "$SEQUENCE_LOG")" == 'guard' ]] || {
  printf 'Linked staging guard did not run first.\n' >&2
  exit 1
}
[[ "$(sed -n '2p' "$SEQUENCE_LOG")" == 'identity' ]] || {
  printf 'Disposable-clone identity guard did not follow the linked guard.\n' >&2
  exit 1
}
[[ "$(sed -n '3p' "$SEQUENCE_LOG")" == 'psql' ]] || {
  printf 'psql did not follow both target guards.\n' >&2
  exit 1
}

printf 'PASS: fixture guards fail closed before any database connection.\n'
