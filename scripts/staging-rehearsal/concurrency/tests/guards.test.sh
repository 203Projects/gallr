#!/usr/bin/env bash
set -euo pipefail

TEST_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
SOURCE_CONCURRENCY_DIR=$(cd "$TEST_DIR/.." && pwd -P)
SOURCE_REHEARSAL_DIR=$(cd "$SOURCE_CONCURRENCY_DIR/.." && pwd -P)
SOURCE_REPO_ROOT=$(cd "$TEST_DIR/../../../.." && pwd -P)
SOURCE_BRIDGE_MIGRATION="$SOURCE_REPO_ROOT/supabase/migrations/20260721120000_public_exhibition_catalog_v2.sql"
TEST_CA_SOURCE="$SOURCE_REHEARSAL_DIR/tests/fixtures/test-root-ca.pem"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/gallr-concurrency-guards.XXXXXX")
TEST_ROOT=$(cd "$TEST_ROOT" && pwd -P)
REPO_ROOT="$TEST_ROOT/repo"
REHEARSAL_DIR="$REPO_ROOT/scripts/staging-rehearsal"
CONCURRENCY_DIR="$REHEARSAL_DIR/concurrency"
COMMON_PATH="$CONCURRENCY_DIR/common.sh"
RUN_PATH="$CONCURRENCY_DIR/run.sh"
BRIDGE_MIGRATION="$REPO_ROOT/supabase/migrations/20260721120000_public_exhibition_catalog_v2.sql"
FAKE_BIN="$TEST_ROOT/bin"
EVIDENCE_ROOT="$TEST_ROOT/evidence"
SECURE_ROOT="$TEST_ROOT/secure"
TEST_CA_PATH="$SECURE_ROOT/test-root-ca.pem"
PSQL_MARKER="$TEST_ROOT/psql-was-invoked"
SEQUENCE_LOG="$TEST_ROOT/sequence.log"
IDENTITY_GUARD_FAILURE_MARKER="$TEST_ROOT/identity-guard-failure"
UNEXPECTED_EVIDENCE_MARKER="$TEST_ROOT/unexpected-evidence-run-id"
BOOTSTRAP_LEAK_MARKER="$TEST_ROOT/bootstrap-environment-leaked"

cleanup() {
  case "$TEST_ROOT" in
    /tmp/gallr-concurrency-guards.*|/private/tmp/gallr-concurrency-guards.*|\
      /private/var/*/gallr-concurrency-guards.*)
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
RUN_ID='bridge-test-0001'
REASON='approved staging rehearsal bridge-test-0001'
TARGET_ID='representative-exhibition'
ENCODED_DATABASE_PASSWORD='test%3Apass%5Cword'
EXPECTED_PGPASS_PASSWORD='test\:pass\\word'
REAL_NODE_SOURCE=$(node -p 'require("node:fs").realpathSync.native(process.execPath)')
REVIEWED_NODE="$FAKE_BIN/node"
REAL_GIT=$(command -v git)
REAL_DIRNAME=$(command -v dirname)

mkdir -m 700 "$FAKE_BIN" "$EVIDENCE_ROOT" "$SECURE_ROOT"
cp "$REAL_NODE_SOURCE" "$REVIEWED_NODE"
chmod 500 "$REVIEWED_NODE"
NODE_LIBRARY_SOURCE=$(
  find "$(dirname "$REAL_NODE_SOURCE")/../lib" \
    -maxdepth 1 -type f -name 'libnode.*.dylib' -print -quit 2>/dev/null || true
)
if [[ -n "$NODE_LIBRARY_SOURCE" ]]; then
  mkdir -m 700 "$TEST_ROOT/lib"
  cp "$NODE_LIBRARY_SOURCE" "$TEST_ROOT/lib/"
  chmod 400 "$TEST_ROOT/lib/$(basename "$NODE_LIBRARY_SOURCE")"
fi
mkdir -p \
  "$CONCURRENCY_DIR" \
  "$REHEARSAL_DIR/lib" \
  "$REPO_ROOT/supabase/migrations"
chmod 700 \
  "$REPO_ROOT" \
  "$REPO_ROOT/scripts" \
  "$REHEARSAL_DIR" \
  "$CONCURRENCY_DIR" \
  "$REHEARSAL_DIR/lib" \
  "$REPO_ROOT/supabase" \
  "$REPO_ROOT/supabase/migrations"
cp "$TEST_CA_SOURCE" "$TEST_CA_PATH"
chmod 0400 "$TEST_CA_PATH"
cp "$SOURCE_CONCURRENCY_DIR"/*.sh "$SOURCE_CONCURRENCY_DIR"/*.sql "$CONCURRENCY_DIR/"
cp \
  "$SOURCE_REHEARSAL_DIR/lib/database-target.mjs" \
  "$SOURCE_REHEARSAL_DIR/lib/validate-database-target.mjs" \
  "$SOURCE_REHEARSAL_DIR/lib/run-psql-with-validated-target.mjs" \
  "$SOURCE_REHEARSAL_DIR/lib/reviewed-toolchain.sh" \
  "$REHEARSAL_DIR/lib/"
cp "$SOURCE_BRIDGE_MIGRATION" "$BRIDGE_MIGRATION"
{
  printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail'
  printf 'expected_staging_ref=%q\n' "$STAGING_REF"
  printf 'expected_pgpass_password=%q\n' "$EXPECTED_PGPASS_PASSWORD"
  printf 'source_ca_path=%q\n' "$TEST_CA_PATH"
  printf 'psql_marker=%q\n' "$PSQL_MARKER"
  printf 'sequence_log=%q\n' "$SEQUENCE_LOG"
  printf 'evidence_root=%q\n' "$EVIDENCE_ROOT"
  printf 'unexpected_evidence_marker=%q\n' "$UNEXPECTED_EVIDENCE_MARKER"
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
[[ "${PGAPPNAME:-}" == gallr-stg-control-bridge-test-* \
   && "${PGCONNECT_TIMEOUT:-}" == 15 ]] || exit 94
[[ "${PGOPTIONS:-}" == \
  '-c statement_timeout=50s -c lock_timeout=40s' ]] || exit 95
for forbidden in \
  PGPASSWORD PGHOSTADDR PGSERVICE PGSERVICEFILE \
  PGTARGETSESSIONATTRS PGLOADBALANCEHOSTS PGSSLCERT PGSSLKEY PGGSSLIB \
  GALLR_EXPECTED_STAGING_PROJECT_REF GALLR_PRODUCTION_PROJECT_REF \
  GALLR_STAGING_DATABASE_URL GALLR_STAGING_REHEARSAL_CONFIRM \
  GALLR_VALIDATION_DATABASE_URL GALLR_VALIDATION_PROJECT_REF \
  GALLR_VALIDATION_REQUIRE_DIRECT GALLR_VALIDATION_SSLROOTCERT_SHA256 \
  GALLR_PSQL_APPNAME GALLR_PSQL_CONNECT_TIMEOUT GALLR_PSQL_OPTIONS \
  GALLR_VALIDATED_PSQL_PATH GALLR_VALIDATED_PSQL_SHA256 \
  FAKE_STAGING_REF FAKE_RUN_ID FAKE_EXPECTED_PGPASS_PASSWORD \
  FAKE_SOURCE_CA_PATH PSQL_MARKER SEQUENCE_LOG \
  INJECT_UNEXPECTED_EVIDENCE CONCURRENCY_RUN_DIR; do
  [[ "${!forbidden+x}" != x ]] || {
    printf 'inherited environment override: %s\n' "$forbidden" >&2
    exit 96
  }
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
if [[ -s "$unexpected_evidence_marker" ]]; then
  unexpected_run_id=$(< "$unexpected_evidence_marker")
  [[ "$unexpected_run_id" =~ ^[a-z0-9][a-z0-9-]{7,31}$ ]] || exit 104
  mkfifo "$evidence_root/$unexpected_run_id/unexpected.fifo" || exit 104
fi
touch "$psql_marker"
printf 'psql\n' >> "$sequence_log"
exit 105
EOF
} > "$FAKE_BIN/psql"
chmod 700 "$FAKE_BIN/psql"
{
  printf '%s\n' '#!/bin/sh' 'set -eu'
  printf 'sequence_log=%q\n' "$SEQUENCE_LOG"
  cat <<'EOF'
[ "${BASH_ENV:-}" = /dev/null ] || exit 86
[ "${ENV:-}" = /dev/null ] || exit 85
printf 'guard\n' >> "$sequence_log"
printf 'PASS: stubbed linked staging guard\n'
EOF
} > "$REHEARSAL_DIR/assert-linked-staging.sh"
chmod 700 "$REHEARSAL_DIR/assert-linked-staging.sh"
{
  printf '%s\n' '#!/bin/sh' 'set -eu'
  printf 'sequence_log=%q\n' "$SEQUENCE_LOG"
  printf 'failure_marker=%q\n' "$IDENTITY_GUARD_FAILURE_MARKER"
  cat <<'EOF'
[ -z "${BASH_ENV+x}" ] || exit 84
[ -z "${ENV+x}" ] || exit 83
[ -z "${IDENTITY_GUARD_SHOULD_FAIL+x}" ] || exit 82
printf 'identity\n' >> "$sequence_log"
[ ! -e "$failure_marker" ] || exit 88
printf 'PASS: independent policy and disposable-clone marker identify staging\n'
EOF
} > "$REHEARSAL_DIR/assert-disposable-clone-target.sh"
chmod 700 "$REHEARSAL_DIR/assert-disposable-clone-target.sh"

printf '%s\n' \
  '#!/bin/sh' \
  '/bin/mkdir "$@" || exit $?' \
  'if [ "${INJECT_DANGLING_EVIDENCE:-0}" = 1 ]; then' \
  '  ln -s "$DANGLING_EVIDENCE_TARGET" "$CONCURRENCY_RUN_DIR/preflight.tsv" || exit 87' \
  'fi' > "$FAKE_BIN/mkdir"
chmod 700 "$FAKE_BIN/mkdir"

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

"$REAL_GIT" -C "$REPO_ROOT" init -q
"$REAL_GIT" -C "$REPO_ROOT" config user.name 'Gallr Test'
"$REAL_GIT" -C "$REPO_ROOT" config user.email 'test@gallr.invalid'
"$REAL_GIT" -C "$REPO_ROOT" add .
"$REAL_GIT" -C "$REPO_ROOT" \
  -c commit.gpgsign=false commit -q -m 'fake reviewed repository'

if command -v shasum >/dev/null 2>&1; then
  BRIDGE_SHA256=$(shasum -a 256 "$BRIDGE_MIGRATION" | awk '{print $1}')
  REVIEWED_NODE_SHA256=$(shasum -a 256 "$REVIEWED_NODE" | awk '{print $1}')
  REVIEWED_PSQL_SHA256=$(shasum -a 256 "$FAKE_BIN/psql" | awk '{print $1}')
else
  BRIDGE_SHA256=$(sha256sum "$BRIDGE_MIGRATION" | awk '{print $1}')
  REVIEWED_NODE_SHA256=$(sha256sum "$REVIEWED_NODE" | awk '{print $1}')
  REVIEWED_PSQL_SHA256=$(sha256sum "$FAKE_BIN/psql" | awk '{print $1}')
fi
REPOSITORY_COMMIT=$("$REAL_GIT" -C "$REPO_ROOT" rev-parse --verify 'HEAD^{commit}')

write_operator_manifest() {
  local repository_commit="$1"

  chmod 600 "$EVIDENCE_ROOT/operator-manifest.txt" 2>/dev/null || true
  printf 'manifest_schema=1\nrepository_commit=%s\n' \
    "$repository_commit" \
    > "$EVIDENCE_ROOT/operator-manifest.txt"
  printf 'reviewed_node_path=%s\nreviewed_node_sha256=%s\n' \
    "$REVIEWED_NODE" \
    "$REVIEWED_NODE_SHA256" \
    >> "$EVIDENCE_ROOT/operator-manifest.txt"
  printf 'reviewed_psql_path=%s\nreviewed_psql_sha256=%s\n' \
    "$FAKE_BIN/psql" \
    "$REVIEWED_PSQL_SHA256" \
    >> "$EVIDENCE_ROOT/operator-manifest.txt"
  printf '%s  %s\n' \
    "$BRIDGE_SHA256" \
    'supabase/migrations/20260721120000_public_exhibition_catalog_v2.sql' \
    >> "$EVIDENCE_ROOT/operator-manifest.txt"
  chmod 444 "$EVIDENCE_ROOT/operator-manifest.txt"
}

write_operator_manifest "$REPOSITORY_COMMIT"

TEST_CA_URI_PATH="${TEST_CA_PATH//\//%2F}"
DATABASE_URL="postgresql://postgres:${ENCODED_DATABASE_PASSWORD}@db.$STAGING_REF.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=${TEST_CA_URI_PATH}"

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
  "postgresql://postgres:${ENCODED_DATABASE_PASSWORD}@db.not-the-ref.invalid/postgres?sslmode=verify-full&sslrootcert=${TEST_CA_URI_PATH}"
SUBSTRING_SPOOF="postgresql://postgres:$STAGING_REF@db.$PRODUCTION_REF.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=${TEST_CA_URI_PATH}"
assert_rejected 'database URL does not identify the expected staging project' \
  "$STAGING_REF" "$PRODUCTION_REF" "$SUBSTRING_SPOOF"
assert_rejected 'database URL does not identify the expected staging project' \
  "$STAGING_REF" "$PRODUCTION_REF" \
  "postgresql://postgres:${ENCODED_DATABASE_PASSWORD}@db.$STAGING_REF.supabase.co:5432/postgres?sslmode=disable&sslrootcert=${TEST_CA_URI_PATH}"
assert_rejected 'database URL does not identify the expected staging project' \
  "$STAGING_REF" "$PRODUCTION_REF" \
  "postgresql://postgres.$STAGING_REF:${ENCODED_DATABASE_PASSWORD}@aws-0-region.pooler.supabase.com:5432/postgres?sslmode=verify-full&sslrootcert=${TEST_CA_URI_PATH}"
assert_rejected 'must exactly equal the staging project ref' \
  "$STAGING_REF" "$PRODUCTION_REF" "$DATABASE_URL" "$PRODUCTION_REF"

PRODUCTION_URL="postgresql://postgres:${ENCODED_DATABASE_PASSWORD}@$PRODUCTION_REF.$STAGING_REF.invalid/postgres?sslmode=verify-full&sslrootcert=${TEST_CA_URI_PATH}"
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
  local value

  if value=$(stat -f '%Lp' "$1" 2>/dev/null); then
    printf '%s\n' "${value}"
  elif value=$(stat -c '%a' "$1" 2>/dev/null); then
    printf '%s\n' "${value}"
  else
    return 1
  fi
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
touch "$IDENTITY_GUARD_FAILURE_MARKER"
if run_coordinator "$IDENTITY_FAILURE_RUN_ID" \
  > "$TEST_ROOT/identity-failure.stdout" \
  2> "$TEST_ROOT/identity-failure.stderr"; then
  printf 'Coordinator unexpectedly ignored target-identity guard failure.\n' >&2
  exit 1
fi
rm "$IDENTITY_GUARD_FAILURE_MARKER"
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
printf '%s\n' "$UNEXPECTED_RUN_ID" > "$UNEXPECTED_EVIDENCE_MARKER"
if run_coordinator "$UNEXPECTED_RUN_ID" \
  > "$TEST_ROOT/unexpected.stdout" \
  2> "$TEST_ROOT/unexpected.stderr"; then
  printf 'Coordinator unexpectedly accepted an unexpected FIFO.\n' >&2
  exit 1
fi
rm "$UNEXPECTED_EVIDENCE_MARKER"
grep -Fq 'unexpected evidence entry:' "$TEST_ROOT/unexpected.stderr"
[[ -p "$EVIDENCE_ROOT/$UNEXPECTED_RUN_ID/unexpected.fifo" ]] || exit 88
UNEXPECTED_MANIFEST="$EVIDENCE_ROOT/$UNEXPECTED_RUN_ID/manifest.tsv"
grep -Fq 'evidence_inventory_invalid=true' "$UNEXPECTED_MANIFEST" || {
  printf 'Unexpected evidence was not recorded in the run manifest.\n' >&2
  exit 1
}
[[ "$(mode_of "$UNEXPECTED_MANIFEST")" == "400" ]] || exit 89

[[ ! -e "$BOOTSTRAP_LEAK_MARKER" ]] || {
  printf 'A bootstrap pathname utility inherited database credentials.\n' >&2
  exit 1
}

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
