#!/usr/bin/env bash
set -euo pipefail

TEST_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
SOURCE_FIXTURE_DIR=$(cd "$TEST_DIR/.." && pwd -P)
SOURCE_REHEARSAL_DIR=$(cd "$SOURCE_FIXTURE_DIR/.." && pwd -P)
TEST_CA_SOURCE="$SOURCE_REHEARSAL_DIR/tests/fixtures/test-root-ca.pem"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/gallr-fixture-guards.XXXXXX")
TEST_ROOT=$(cd "$TEST_ROOT" && pwd -P)
REPO_ROOT="$TEST_ROOT/repo"
REHEARSAL_DIR="$REPO_ROOT/scripts/staging-rehearsal"
FIXTURE_DIR="$REHEARSAL_DIR/fixtures"
COMMON_PATH="$FIXTURE_DIR/common.sh"
PROVISION_PATH="$FIXTURE_DIR/provision.sh"
CLEANUP_PATH="$FIXTURE_DIR/cleanup.sh"
FAKE_BIN="$TEST_ROOT/bin"
EVIDENCE_ROOT="$TEST_ROOT/evidence"
SECURE_ROOT="$TEST_ROOT/secure"
TEST_CA_PATH="$SECURE_ROOT/test-root-ca.pem"
PSQL_MARKER="$TEST_ROOT/psql-was-invoked"
SEQUENCE_LOG="$TEST_ROOT/sequence.log"
LINKED_GUARD_FAILURE_MARKER="$TEST_ROOT/linked-guard-failure"
BOOTSTRAP_LEAK_MARKER="$TEST_ROOT/bootstrap-environment-leaked"
INSIDE_REPO=''

cleanup() {
  if [[ -n "$INSIDE_REPO" && -d "$INSIDE_REPO" && ! -L "$INSIDE_REPO" ]]; then
    rmdir "$INSIDE_REPO" 2>/dev/null || true
  fi
  case "$TEST_ROOT" in
    /tmp/gallr-fixture-guards.*|/private/tmp/gallr-fixture-guards.*|\
      /private/var/*/gallr-fixture-guards.*)
      rm -rf -- "$TEST_ROOT"
      ;;
    *)
      printf 'Refusing unsafe test cleanup path: %s\n' "$TEST_ROOT" >&2
      ;;
  esac
}
trap cleanup EXIT HUP INT TERM

STAGING_REF='aaaaaaaaaaaaaaaaaaaa'
PRODUCTION_REF='bbbbbbbbbbbbbbbbbbbb'
RUN_ID='fixture-test-0001'
ENCODED_DATABASE_PASSWORD='test%3Apass%5Cword'
EXPECTED_PGPASS_PASSWORD='test\:pass\\word'
REAL_NODE_SOURCE=$(node -p 'require("node:fs").realpathSync.native(process.execPath)')
REAL_NODE="$FAKE_BIN/node"
REAL_DIRNAME=$(command -v dirname)

mkdir -m 700 "$FAKE_BIN" "$EVIDENCE_ROOT" "$SECURE_ROOT"
cp "$REAL_NODE_SOURCE" "$REAL_NODE"
chmod 500 "$REAL_NODE"
NODE_LIBRARY_SOURCE=$(
  find "$(dirname "$REAL_NODE_SOURCE")/../lib" \
    -maxdepth 1 -type f -name 'libnode.*.dylib' -print -quit 2>/dev/null || true
)
if [[ -n "$NODE_LIBRARY_SOURCE" ]]; then
  mkdir -m 700 "$TEST_ROOT/lib"
  cp "$NODE_LIBRARY_SOURCE" "$TEST_ROOT/lib/"
  chmod 400 "$TEST_ROOT/lib/$(basename "$NODE_LIBRARY_SOURCE")"
fi
mkdir -p "$FIXTURE_DIR/tests" "$REHEARSAL_DIR/lib"
chmod 700 "$REPO_ROOT" "$REPO_ROOT/scripts" "$REHEARSAL_DIR" \
  "$FIXTURE_DIR" "$FIXTURE_DIR/tests" "$REHEARSAL_DIR/lib"
cp "$TEST_CA_SOURCE" "$TEST_CA_PATH"
chmod 0400 "$TEST_CA_PATH"
cp \
  "$SOURCE_FIXTURE_DIR/common.sh" \
  "$SOURCE_FIXTURE_DIR/provision.sh" \
  "$SOURCE_FIXTURE_DIR/cleanup.sh" \
  "$SOURCE_FIXTURE_DIR/baseline.sql" \
  "$SOURCE_FIXTURE_DIR/provision.sql" \
  "$SOURCE_FIXTURE_DIR/cleanup.sql" \
  "$SOURCE_FIXTURE_DIR/tracked-state.sql" \
  "$FIXTURE_DIR/"
cp \
  "$SOURCE_REHEARSAL_DIR/lib/database-target.mjs" \
  "$SOURCE_REHEARSAL_DIR/lib/validate-database-target.mjs" \
  "$SOURCE_REHEARSAL_DIR/lib/run-psql-with-validated-target.mjs" \
  "$SOURCE_REHEARSAL_DIR/lib/reviewed-toolchain.sh" \
  "$REHEARSAL_DIR/lib/"

{
  printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail'
  printf 'expected_staging_ref=%q\n' "$STAGING_REF"
  printf 'expected_run_id=%q\n' "$RUN_ID"
  printf 'expected_pgpass_password=%q\n' "$EXPECTED_PGPASS_PASSWORD"
  printf 'source_ca_path=%q\n' "$TEST_CA_PATH"
  printf 'psql_marker=%q\n' "$PSQL_MARKER"
  printf 'sequence_log=%q\n' "$SEQUENCE_LOG"
  cat <<'EOF'

portable_stat_mode() {
  local value

  if value=$(stat -f '%Lp' "$1" 2>/dev/null); then
    printf '%s\n' "${value}"
  elif value=$(stat -c '%a' "$1" 2>/dev/null); then
    printf '%s\n' "${value}"
  else
    return 1
  fi
}

[[ "${PGHOST:-}" == "db.${expected_staging_ref}.supabase.co" ]] || exit 91
[[ "${PGPORT:-}" == 5432 && "${PGDATABASE:-}" == postgres \
   && "${PGUSER:-}" == postgres ]] || exit 92
[[ "${PGSSLMODE:-}" == verify-full \
   && "${PGGSSENCMODE:-}" == disable \
   && "${PGSSLCERTMODE:-}" == disable ]] || exit 93
[[ "${PGAPPNAME:-}" == "gallr-staging-fixture-${expected_run_id}" \
   && "${PGCONNECT_TIMEOUT:-}" == 15 ]] || exit 94
[[ -z "${PGOPTIONS+x}" && -z "${PGPASSWORD+x}" \
   && -z "${PGHOSTADDR+x}" && -z "${PGSERVICE+x}" \
   && -z "${PGSERVICEFILE+x}" && -z "${PGSSLCERT+x}" \
   && -z "${PGSSLKEY+x}" && -z "${PGGSSLIB+x}" ]] || exit 95
for forbidden in \
  GALLR_EXPECTED_STAGING_PROJECT_REF GALLR_PRODUCTION_PROJECT_REF \
  GALLR_STAGING_DATABASE_URL GALLR_STAGING_REHEARSAL_CONFIRM \
  GALLR_VALIDATION_DATABASE_URL GALLR_VALIDATION_PROJECT_REF \
  GALLR_VALIDATION_REQUIRE_DIRECT GALLR_VALIDATION_SSLROOTCERT_SHA256 \
  GALLR_PSQL_APPNAME GALLR_PSQL_CONNECT_TIMEOUT GALLR_PSQL_OPTIONS \
  GALLR_VALIDATED_PSQL_PATH GALLR_VALIDATED_PSQL_SHA256 \
  FAKE_STAGING_REF FAKE_RUN_ID FAKE_EXPECTED_PGPASS_PASSWORD \
  FAKE_SOURCE_CA_PATH PSQL_MARKER SEQUENCE_LOG; do
  [[ "${!forbidden+x}" != x ]] || exit 96
done
[[ -n "${PGPASSFILE:-}" && "${PGPASSFILE}" != /dev/null \
   && -f "${PGPASSFILE}" && ! -L "${PGPASSFILE}" && -O "${PGPASSFILE}" ]] \
  || exit 97
passfile_mode=$(portable_stat_mode "${PGPASSFILE}")
[[ "${passfile_mode}" == 600 \
   && "$(wc -l < "${PGPASSFILE}" | tr -d ' ')" == 1 \
   && "$(< "${PGPASSFILE}")" == \
      "db.${expected_staging_ref}.supabase.co:5432:postgres:postgres:${expected_pgpass_password}" ]] \
  || exit 98
[[ -n "${PGSSLROOTCERT:-}" && "${PGSSLROOTCERT}" != "${source_ca_path}" \
   && -f "${PGSSLROOTCERT}" && ! -L "${PGSSLROOTCERT}" \
   && -O "${PGSSLROOTCERT}" ]] || exit 99
certificate_mode=$(portable_stat_mode "${PGSSLROOTCERT}")
certificate_parent_mode=$(portable_stat_mode "$(dirname "${PGSSLROOTCERT}")")
[[ "${certificate_mode}" == 400 && "${certificate_parent_mode}" == 700 ]] || exit 100
cmp -s "${PGSSLROOTCERT}" "${source_ca_path}" || exit 101
while IFS='=' read -r environment_name environment_value; do
  [[ "${environment_value}" != *postgresql://* \
     && "${environment_value}" != *postgres://* ]] || exit 102
done < <(env)
for argument in "$@"; do
  [[ "$argument" != *postgresql://* && "$argument" != *postgres://* ]] || exit 103
done
touch "$psql_marker"
printf 'psql\n' >> "$sequence_log"
exit 104
EOF
} > "$FAKE_BIN/psql"
chmod 700 "$FAKE_BIN/psql"

LINKED_GUARD_PATH="$REHEARSAL_DIR/assert-linked-staging.sh"
IDENTITY_GUARD_PATH="$REHEARSAL_DIR/assert-disposable-clone-target.sh"
{
  printf '%s\n' '#!/bin/sh' 'set -eu'
  printf 'sequence_log=%q\n' "$SEQUENCE_LOG"
  printf 'failure_marker=%q\n' "$LINKED_GUARD_FAILURE_MARKER"
  cat <<'EOF'
[ "${BASH_ENV:-}" = /dev/null ] || exit 86
[ "${ENV:-}" = /dev/null ] || exit 85
printf 'guard\n' >> "$sequence_log"
[ ! -e "$failure_marker" ] || exit 89
printf 'PASS: stubbed linked staging guard\n'
EOF
} > "$LINKED_GUARD_PATH"
chmod 700 "$LINKED_GUARD_PATH"
{
  printf '%s\n' '#!/bin/sh' 'set -eu'
  printf 'sequence_log=%q\n' "$SEQUENCE_LOG"
  cat <<'EOF'
[ -z "${BASH_ENV+x}" ] || exit 84
[ -z "${ENV+x}" ] || exit 83
[ -z "${LINKED_GUARD_SHOULD_FAIL+x}" ] || exit 82
[ -z "${IDENTITY_GUARD_SHOULD_FAIL+x}" ] || exit 81
printf 'identity\n' >> "$sequence_log"
printf 'PASS: independent policy and disposable-clone marker identify staging\n'
EOF
} > "$IDENTITY_GUARD_PATH"
chmod 700 "$IDENTITY_GUARD_PATH"

cat > "$FAKE_BIN/dirname" <<EOF
#!/bin/sh
if [ "\${GALLR_STAGING_DATABASE_URL+x}" = x ] ||
   [ "\${DATABASE_URL+x}" = x ] ||
   [ "\${PGPASSWORD+x}" = x ]; then
  : > "$BOOTSTRAP_LEAK_MARKER"
fi
exec "$REAL_DIRNAME" "\$@"
EOF
chmod 700 "$FAKE_BIN/dirname"

TEST_CA_URI_PATH="${TEST_CA_PATH//\//%2F}"
DATABASE_URL="postgresql://postgres:${ENCODED_DATABASE_PASSWORD}@db.$STAGING_REF.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=${TEST_CA_URI_PATH}"

sha256_text() {
  if command -v shasum >/dev/null 2>&1; then
    printf '%s' "$1" | shasum -a 256 | awk '{print $1}'
  else
    printf '%s' "$1" | sha256sum | awk '{print $1}'
  fi
}

sha256_file() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

printf '%s\n' \
  'manifest_schema=1' \
  "reviewed_node_path=$REAL_NODE" \
  "reviewed_node_sha256=$(sha256_file "$REAL_NODE")" \
  "reviewed_psql_path=$FAKE_BIN/psql" \
  "reviewed_psql_sha256=$(sha256_file "$FAKE_BIN/psql")" \
  > "$EVIDENCE_ROOT/operator-manifest.txt"
chmod 444 "$EVIDENCE_ROOT/operator-manifest.txt"

validate() {
  env \
    PATH="$FAKE_BIN:$PATH" \
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
  "postgresql://postgres:$STAGING_REF@db.$PRODUCTION_REF.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=${TEST_CA_URI_PATH}"
assert_rejected 'database URL target validation failed' \
  "$STAGING_REF" "$PRODUCTION_REF" \
  "postgresql://postgres:${ENCODED_DATABASE_PASSWORD}@db.$STAGING_REF.supabase.co:5432/postgres?sslmode=disable&sslrootcert=${TEST_CA_URI_PATH}"
assert_rejected 'database URL target validation failed' \
  "$STAGING_REF" "$PRODUCTION_REF" \
  "postgresql://postgres.$STAGING_REF:${ENCODED_DATABASE_PASSWORD}@aws-0-region.pooler.supabase.com:6543/postgres?sslmode=verify-full&sslrootcert=${TEST_CA_URI_PATH}"
assert_rejected 'confirmation must exactly equal the expected staging project ref' \
  "$STAGING_REF" "$PRODUCTION_REF" "$DATABASE_URL" "$PRODUCTION_REF"
assert_rejected 'must be between 5 and 60' \
  "$STAGING_REF" "$PRODUCTION_REF" "$DATABASE_URL" "$STAGING_REF" "$EVIDENCE_ROOT" 0

chmod 755 "$EVIDENCE_ROOT"
assert_rejected 'evidence directory must have mode 0700'
chmod 700 "$EVIDENCE_ROOT"

INSIDE_REPO="$REPO_ROOT/scripts/staging-rehearsal/fixtures/tests/.guard-evidence-$$"
mkdir -m 700 "$INSIDE_REPO"
cp "$EVIDENCE_ROOT/operator-manifest.txt" "$INSIDE_REPO/operator-manifest.txt"
chmod 444 "$INSIDE_REPO/operator-manifest.txt"
assert_rejected 'evidence must be stored outside the repository' \
  "$STAGING_REF" "$PRODUCTION_REF" "$DATABASE_URL" "$STAGING_REF" "$INSIDE_REPO"
rm "$INSIDE_REPO/operator-manifest.txt"
rmdir "$INSIDE_REPO"

EVIDENCE_LINK="$TEST_ROOT/evidence-link"
ln -s "$EVIDENCE_ROOT" "$EVIDENCE_LINK"
assert_rejected 'must be an existing absolute directory' \
  "$STAGING_REF" "$PRODUCTION_REF" "$DATABASE_URL" "$STAGING_REF" "$EVIDENCE_LINK"

touch "$LINKED_GUARD_FAILURE_MARKER"
if env \
  PATH="$FAKE_BIN:$PATH" \
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
rm "$LINKED_GUARD_FAILURE_MARKER"
grep -Fq 'linked staging target did not match' "$TEST_ROOT/linked.stderr"
[[ ! -e "$PSQL_MARKER" ]] || {
  printf 'Linked-target rejection reached psql.\n' >&2
  exit 1
}

RUN_DIR="$EVIDENCE_ROOT/fixtures-$RUN_ID"
mkdir -m 700 "$RUN_DIR"
if env \
  PATH="$FAKE_BIN:$PATH" \
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
grep -Fq 'database evidence is empty' "$TEST_ROOT/dangling.stderr" || {
  printf 'Dangling-evidence rejection failed unexpectedly:\n' >&2
  sed -n '1,120p' "$TEST_ROOT/dangling.stderr" >&2
  exit 1
}
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
  sed -n '1,120p' "$TEST_ROOT/run.stderr" >&2
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
[[ ! -e "$BOOTSTRAP_LEAK_MARKER" ]] || {
  printf 'A bootstrap pathname utility inherited database credentials.\n' >&2
  exit 1
}

printf 'PASS: fixture guards fail closed before any database connection.\n'
