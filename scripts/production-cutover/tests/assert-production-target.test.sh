#!/usr/bin/env bash
set -euo pipefail

TEST_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
SOURCE_DIR=$(cd -- "$TEST_DIR/.." && pwd -P)
TEST_PARENT=$(cd -- "${TMPDIR:-/tmp}" && pwd -P)
TEST_ROOT=$(mktemp -d "$TEST_PARENT/gallr-production-guard.XXXXXX")
TEST_ROOT=$(cd -- "$TEST_ROOT" && pwd -P)
TEST_REPO="$TEST_ROOT/repo"
EXTERNAL_ROOT="$TEST_ROOT/external"
POLICY_DIR="$EXTERNAL_ROOT/policy"
EVIDENCE_DIR="$EXTERNAL_ROOT/evidence"
MANIFEST_PATH="$EXTERNAL_ROOT/operator-manifest.txt"
POLICY_PATH="$POLICY_DIR/gate4-policy.txt"
FAKE_BIN="$TEST_ROOT/bin"
REMOTE_MARKER="$TEST_ROOT/remote-command-invoked"
GIT_ENV_FAILURE="$TEST_ROOT/unsafe-git-environment"
NODE_ENV_FAILURE="$TEST_ROOT/unsafe-node-environment"
FS_MONITOR_MARKER="$TEST_ROOT/fsmonitor-invoked"
REAL_GIT=$(command -v git)
REAL_NODE=$(command -v node)

cleanup() {
  case "$TEST_ROOT" in
    "$TEST_PARENT"/gallr-production-guard.*)
      rm -rf -- "$TEST_ROOT"
      ;;
    *)
      printf 'Refusing unsafe test cleanup path: %s\n' "$TEST_ROOT" >&2
      ;;
  esac
}
trap cleanup EXIT HUP INT TERM

mkdir -m 700 -p \
  "$TEST_REPO/scripts/production-cutover/lib" \
  "$TEST_REPO/supabase/migrations" \
  "$TEST_REPO/supabase/.temp" \
  "$POLICY_DIR" \
  "$EVIDENCE_DIR" \
  "$FAKE_BIN"
cp "$SOURCE_DIR/assert-production-target.sh" "$TEST_REPO/scripts/production-cutover/"
cp "$SOURCE_DIR/lib/validate-production-database-target.mjs" \
  "$TEST_REPO/scripts/production-cutover/lib/"
printf '/supabase/.temp/\n' > "$TEST_REPO/.gitignore"
printf '%s\n' 'select 1;' > "$TEST_REPO/supabase/migrations/20260722000000_test.sql"

git -C "$TEST_REPO" init -q
git -C "$TEST_REPO" config user.name 'Production Guard Test'
git -C "$TEST_REPO" config user.email 'production-guard@example.invalid'
git -C "$TEST_REPO" add .
git -C "$TEST_REPO" commit -qm 'test fixture'

for forbidden in supabase psql curl; do
  printf '%s\n' \
    '#!/bin/sh' \
    'touch "$REMOTE_MARKER"' \
    'exit 99' > "$FAKE_BIN/$forbidden"
  chmod 700 "$FAKE_BIN/$forbidden"
done

# Every Git call made by the guard passes through this tripwire. It both checks
# that target/credential variables were removed and that safe_git supplied its
# fixed configuration before delegating to the real local Git binary.
{
  printf '%s\n' '#!/bin/sh'
  printf 'fail_git_env() { : > "%s"; exit 95; }\n' "$GIT_ENV_FAILURE"
  printf '%s\n' \
    '[ -z "${GALLR_PRODUCTION_DATABASE_URL:-}" ] || fail_git_env' \
    '[ -z "${GALLR_STAGING_DATABASE_URL:-}" ] || fail_git_env' \
    '[ -z "${DATABASE_URL:-}" ] || fail_git_env' \
    '[ -z "${PGPASSWORD:-}" ] || fail_git_env' \
    '[ -z "${SUPABASE_SERVICE_ROLE_KEY:-}" ] || fail_git_env' \
    '[ -z "${NODE_OPTIONS:-}" ] || fail_git_env' \
    '[ -z "${GIT_DIR:-}" ] || fail_git_env' \
    '[ -z "${GIT_WORK_TREE:-}" ] || fail_git_env' \
    '[ -z "${GIT_INDEX_FILE:-}" ] || fail_git_env' \
    '[ "${GIT_CONFIG_COUNT:-}" = 0 ] || fail_git_env' \
    '[ "${GIT_CONFIG_GLOBAL:-}" = /dev/null ] || fail_git_env' \
    '[ "${GIT_CONFIG_NOSYSTEM:-}" = 1 ] || fail_git_env' \
    '[ "${GIT_OPTIONAL_LOCKS:-}" = 0 ] || fail_git_env' \
    '[ "${1:-}" = -c ] || fail_git_env' \
    '[ "${2:-}" = core.fsmonitor=false ] || fail_git_env' \
    '[ "${3:-}" = -c ] || fail_git_env' \
    '[ "${4:-}" = core.hooksPath=/dev/null ] || fail_git_env' \
    '[ "${5:-}" = -c ] || fail_git_env' \
    '[ "${6:-}" = core.excludesFile=/dev/null ] || fail_git_env' \
    '[ "${7:-}" = -c ] || fail_git_env' \
    '[ "${8:-}" = core.attributesFile=/dev/null ] || fail_git_env'
  printf 'exec "%s" "$@"\n' "$REAL_GIT"
} > "$FAKE_BIN/git"
chmod 700 "$FAKE_BIN/git"

# The parser may receive only its two explicit validation inputs; internal
# snapshot aliases and unrelated credentials must remain unexported.
{
  printf '%s\n' '#!/bin/sh'
  printf 'fail_node_env() { : > "%s"; exit 96; }\n' "$NODE_ENV_FAILURE"
  printf '%s\n' \
    '[ -n "${GALLR_PRODUCTION_VALIDATION_PROJECT_REF:-}" ] || fail_node_env' \
    '[ -n "${GALLR_PRODUCTION_VALIDATION_DATABASE_URL:-}" ] || fail_node_env' \
    '[ "${GALLR_PRODUCTION_DATABASE_URL+x}" != x ] || fail_node_env' \
    '[ "${GALLR_PRODUCTION_PROJECT_REF+x}" != x ] || fail_node_env' \
    '[ "${PRODUCTION_DATABASE_URL+x}" != x ] || fail_node_env' \
    '[ "${PRODUCTION_REF+x}" != x ] || fail_node_env' \
    '[ "${STAGING_REF+x}" != x ] || fail_node_env' \
    '[ "${CHANGE_RECORD+x}" != x ] || fail_node_env' \
    '[ "${EXECUTOR+x}" != x ] || fail_node_env' \
    '[ "${APPROVER+x}" != x ] || fail_node_env' \
    '[ "${LINKED_REF+x}" != x ] || fail_node_env' \
    '[ "${SUPABASE_SERVICE_ROLE_KEY+x}" != x ] || fail_node_env' \
    '[ "${PGPASSWORD+x}" != x ] || fail_node_env' \
    '[ -z "${NODE_OPTIONS:-}" ] || fail_node_env' \
    '[ -z "${NODE_PATH:-}" ] || fail_node_env' \
    '[ "${NODE_DEBUG+x}" != x ] || fail_node_env' \
    '[ "${1:-}" = --input-type=module ] || fail_node_env' \
    '[ "${2:-}" = - ] || fail_node_env'
  printf 'exec "%s" "$@"\n' "$REAL_NODE"
} > "$FAKE_BIN/node"
chmod 700 "$FAKE_BIN/node"

STAGING_REF='aaaaaaaaaaaaaaaaaaaa'
PRODUCTION_REF='bbbbbbbbbbbbbbbbbbbb'
DATABASE_URL="postgresql://postgres:secret@db.$PRODUCTION_REF.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=%2Ftmp%2Fgallr-production-root-ca.pem"
CHANGE_RECORD='CR-2026-0722-approved'
EXECUTOR='executor@example.invalid'
APPROVER='approver@example.invalid'
REVIEWED_COMMIT=$(git -C "$TEST_REPO" rev-parse HEAD)
BASE_STAGING_REF=$STAGING_REF
BASE_PRODUCTION_REF=$PRODUCTION_REF
BASE_DATABASE_URL=$DATABASE_URL
BASE_CHANGE_RECORD=$CHANGE_RECORD
BASE_EXECUTOR=$EXECUTOR
BASE_APPROVER=$APPROVER
BASE_REVIEWED_COMMIT=$REVIEWED_COMMIT

sha256_file() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

sha256_text() {
  if command -v shasum >/dev/null 2>&1; then
    printf '%s' "$1" | shasum -a 256 | awk '{print $1}'
  else
    printf '%s' "$1" | sha256sum | awk '{print $1}'
  fi
}

write_manifest() {
  local migration_path='supabase/migrations/20260722000000_test.sql'
  chmod 600 "$MANIFEST_PATH" 2>/dev/null || true
  {
    printf 'manifest_schema=1\n'
    printf 'target=staging\n'
    printf 'repository_commit=%s\n' "$REVIEWED_COMMIT"
    printf 'staging_project_ref_sha256=%s\n' "$(sha256_text "$STAGING_REF")"
    printf 'production_project_ref_sha256=%s\n' "$(sha256_text "$PRODUCTION_REF")"
    printf 'remote_contact_performed=false\n'
    printf 'migration_count=1\n'
    printf '\n[migration_sha256]\n'
    printf '%s  %s\n' "$(sha256_file "$TEST_REPO/$migration_path")" "$migration_path"
  } > "$MANIFEST_PATH"
  chmod 444 "$MANIFEST_PATH"
}

write_policy() {
  local gate="${1:-gate4}"
  local production_ref="${2:-$PRODUCTION_REF}"
  local executor="${3:-$EXECUTOR}"
  local approver="${4:-$APPROVER}"
  chmod 600 "$POLICY_PATH" 2>/dev/null || true
  {
    printf 'policy_schema=1\n'
    printf 'target=production\n'
    printf 'approval_status=approved\n'
    printf 'authorized_gate=%s\n' "$gate"
    printf 'production_project_ref_sha256=%s\n' "$(sha256_text "$production_ref")"
    printf 'staging_project_ref_sha256=%s\n' "$(sha256_text "$STAGING_REF")"
    printf 'repository_commit=%s\n' "$REVIEWED_COMMIT"
    printf 'operator_manifest_sha256=%s\n' "$(sha256_file "$MANIFEST_PATH")"
    printf 'change_record_sha256=%s\n' "$(sha256_text "$CHANGE_RECORD")"
    printf 'executor_sha256=%s\n' "$(sha256_text "$executor")"
    printf 'approver_sha256=%s\n' "$(sha256_text "$approver")"
    printf 'evidence_directory_sha256=%s\n' "$(sha256_text "$EVIDENCE_DIR")"
  } > "$POLICY_PATH"
  chmod 400 "$POLICY_PATH"
}

run_guard() {
  local gate="${1:-gate4}"
  local database_url="${2:-$BASE_DATABASE_URL}"
  local confirmation="${3:-PRODUCTION $BASE_PRODUCTION_REF $gate $BASE_REVIEWED_COMMIT}"
  local policy_path="${4:-$POLICY_PATH}"
  local evidence_dir="${5:-$EVIDENCE_DIR}"
  local executor="${6:-$BASE_EXECUTOR}"
  local approver="${7:-$BASE_APPROVER}"
  local guard_path="${8:-$TEST_REPO/scripts/production-cutover/assert-production-target.sh}"

  env \
    PATH="$FAKE_BIN:$PATH" \
    REMOTE_MARKER="$REMOTE_MARKER" \
    GALLR_EXPECTED_STAGING_PROJECT_REF="$BASE_STAGING_REF" \
    GALLR_PRODUCTION_PROJECT_REF="$BASE_PRODUCTION_REF" \
    GALLR_PRODUCTION_DATABASE_URL="$database_url" \
    GALLR_PRODUCTION_CONFIRMATION="$confirmation" \
    GALLR_PRODUCTION_POLICY_FILE="$policy_path" \
    GALLR_OPERATOR_MANIFEST="$MANIFEST_PATH" \
    GALLR_PRODUCTION_EVIDENCE_DIR="$evidence_dir" \
    GALLR_REVIEWED_COMMIT="$BASE_REVIEWED_COMMIT" \
    GALLR_CHANGE_RECORD="$BASE_CHANGE_RECORD" \
    GALLR_PRODUCTION_EXECUTOR="$executor" \
    GALLR_PRODUCTION_APPROVER="$approver" \
    /bin/bash "$guard_path" "$gate"
}

assert_rejected() {
  local expected="$1"
  shift
  local output
  if output=$(run_guard "$@" 2>&1); then
    printf 'Expected guard rejection containing: %s\n' "$expected" >&2
    exit 1
  fi
  grep -Fq "$expected" <<< "$output" || {
    printf 'Guard failed for an unexpected reason: %s\n' "$output" >&2
    exit 1
  }
  [[ ! -e "$REMOTE_MARKER" ]] || {
    printf 'A forbidden remote-capable command was invoked.\n' >&2
    exit 1
  }
}

printf '%s\n' "$PRODUCTION_REF" > "$TEST_REPO/supabase/.temp/project-ref"
write_manifest
write_policy

run_guard > "$TEST_ROOT/pass.stdout"
grep -Fq 'no remote contact performed' "$TEST_ROOT/pass.stdout"
[[ ! -e "$REMOTE_MARKER" ]] || {
  printf 'Valid guard unexpectedly invoked a remote-capable command.\n' >&2
  exit 1
}
[[ ! -e "$GIT_ENV_FAILURE" && ! -e "$NODE_ENV_FAILURE" ]] || {
  printf 'A trusted child received an unsafe inherited environment.\n' >&2
  exit 1
}

# Hostile inherited Git selectors/configuration and interpreter hooks must not
# alter repository discovery or launch configured helpers.
FS_MONITOR_HELPER="$TEST_ROOT/fsmonitor-helper.sh"
{
  printf '%s\n' '#!/bin/sh'
  printf ': > "%s"\n' "$FS_MONITOR_MARKER"
  printf '%s\n' 'exit 98'
} > "$FS_MONITOR_HELPER"
chmod 700 "$FS_MONITOR_HELPER"
MALICIOUS_GIT_CONFIG="$TEST_ROOT/malicious.gitconfig"
{
  printf '%s\n' '[core]'
  printf '\tfsmonitor = %s\n' "$FS_MONITOR_HELPER"
} > "$MALICIOUS_GIT_CONFIG"
(
  export GIT_DIR="$TEST_ROOT/not-the-repository"
  export GIT_WORK_TREE="$TEST_ROOT/not-the-worktree"
  export GIT_INDEX_FILE="$TEST_ROOT/not-the-index"
  export GIT_CEILING_DIRECTORIES=/
  export GIT_CONFIG_GLOBAL="$MALICIOUS_GIT_CONFIG"
  export GIT_CONFIG_SYSTEM="$MALICIOUS_GIT_CONFIG"
  export GIT_CONFIG_COUNT=1
  export GIT_CONFIG_KEY_0=core.fsmonitor
  export GIT_CONFIG_VALUE_0="$FS_MONITOR_HELPER"
  export NODE_OPTIONS='--definitely-invalid-production-guard-option'
  export PERL5OPT='-MProduction::Guard::MustNotLoad'
  export PRODUCTION_DATABASE_URL='must-not-leak-internal-database-url'
  export PRODUCTION_REF='must-not-leak-production-ref'
  export STAGING_REF='must-not-leak-staging-ref'
  export CHANGE_RECORD='must-not-leak-change-record'
  export EXECUTOR='must-not-leak-executor'
  export APPROVER='must-not-leak-approver'
  export LINKED_REF='must-not-leak-linked-ref'
  export SUPABASE_SERVICE_ROLE_KEY='must-not-leak-service-role-key'
  export PGPASSWORD='must-not-leak-pgpassword'
  run_guard > "$TEST_ROOT/sanitized-environment.stdout"
)
grep -Fq 'no remote contact performed' "$TEST_ROOT/sanitized-environment.stdout"
[[ ! -e "$FS_MONITOR_MARKER" && ! -e "$GIT_ENV_FAILURE" &&
  ! -e "$NODE_ENV_FAILURE" ]] || {
  printf 'Inherited Git or interpreter environment was not fully isolated.\n' >&2
  exit 1
}

BENIGN_BASH_ENV="$TEST_ROOT/benign-bash-env.sh"
printf '%s\n' ':' > "$BENIGN_BASH_ENV"
(
  export BASH_ENV="$BENIGN_BASH_ENV"
  assert_rejected 'BASH_ENV must be unset or /dev/null for production attestation'
)
(
  export ENV="$BENIGN_BASH_ENV"
  assert_rejected 'ENV must be unset or /dev/null for production attestation'
)

assert_rejected 'typed production confirmation does not exactly match' \
  gate4 "$DATABASE_URL" "PRODUCTION $STAGING_REF gate4 $REVIEWED_COMMIT"
assert_rejected 'direct production database URL validation failed' \
  gate4 "postgresql://postgres.$PRODUCTION_REF:secret@aws-0.pooler.supabase.com:5432/postgres?sslmode=verify-full&sslrootcert=%2Ftmp%2Fgallr-production-root-ca.pem"
assert_rejected 'direct production database URL validation failed' \
  gate4 "postgresql://postgres:secret@db.$PRODUCTION_REF.supabase.co:5432/postgres?sslmode=disable&sslrootcert=%2Ftmp%2Fgallr-production-root-ca.pem"

write_policy gate6
assert_rejected 'authorized production gate is missing, duplicated, or does not match'
write_policy gate4

chmod 600 "$POLICY_PATH"
assert_rejected 'production policy artifact must have exact mode 0400'
chmod 400 "$POLICY_PATH"

POLICY_HARDLINK="$EXTERNAL_ROOT/policy-hardlink.txt"
ln "$POLICY_PATH" "$POLICY_HARDLINK"
assert_rejected 'production policy artifact must have exactly one hard link'
rm "$POLICY_HARDLINK"

MANIFEST_HARDLINK="$EXTERNAL_ROOT/manifest-hardlink.txt"
ln "$MANIFEST_PATH" "$MANIFEST_HARDLINK"
assert_rejected 'operator manifest must have exactly one hard link'
rm "$MANIFEST_HARDLINK"

printf '%s\n' "$STAGING_REF" > "$TEST_REPO/supabase/.temp/project-ref"
assert_rejected 'linked project is not the exact approved production project'
printf '%s\n' "$PRODUCTION_REF" > "$TEST_REPO/supabase/.temp/project-ref"

chmod 755 "$EVIDENCE_DIR"
assert_rejected 'production evidence directory must have mode 0700'
chmod 700 "$EVIDENCE_DIR"

write_policy gate4 "$STAGING_REF"
assert_rejected 'approved production target is missing, duplicated, or does not match'
write_policy

assert_rejected 'production executor and independent approver must be different people' \
  gate4 "$DATABASE_URL" "PRODUCTION $PRODUCTION_REF gate4 $REVIEWED_COMMIT" \
  "$POLICY_PATH" "$EVIDENCE_DIR" "$EXECUTOR" "$EXECUTOR"

printf '%s\n' '--dirty' >> "$TEST_REPO/supabase/migrations/20260722000000_test.sql"
assert_rejected 'migration bytes differ from the operator manifest'
git -C "$TEST_REPO" checkout -q -- supabase/migrations/20260722000000_test.sql

printf '%s\n' 'dirty' > "$TEST_REPO/untracked.txt"
assert_rejected 'production cutover checkout must be completely clean'
rm "$TEST_REPO/untracked.txt"

printf '%s\n' '# unreviewed guard bytes' >> \
  "$TEST_REPO/scripts/production-cutover/assert-production-target.sh"
assert_rejected 'production target guard bytes differ from the reviewed commit'
git -C "$TEST_REPO" checkout -q -- \
  scripts/production-cutover/assert-production-target.sh

printf '%s\n' '// unreviewed validator bytes' >> \
  "$TEST_REPO/scripts/production-cutover/lib/validate-production-database-target.mjs"
assert_rejected 'database validator bytes differ from the reviewed commit'
git -C "$TEST_REPO" checkout -q -- \
  scripts/production-cutover/lib/validate-production-database-target.mjs

POLICY_LINK="$EXTERNAL_ROOT/policy-link.txt"
ln -s "$POLICY_PATH" "$POLICY_LINK"
assert_rejected 'production policy artifact must be a regular file and not a symbolic link' \
  gate4 "$DATABASE_URL" "PRODUCTION $PRODUCTION_REF gate4 $REVIEWED_COMMIT" "$POLICY_LINK"

POLICY_IN_EVIDENCE="$EVIDENCE_DIR/policy.txt"
cp "$POLICY_PATH" "$POLICY_IN_EVIDENCE"
chmod 400 "$POLICY_IN_EVIDENCE"
assert_rejected 'production policy must be independently stored outside the run evidence directory' \
  gate4 "$DATABASE_URL" "PRODUCTION $PRODUCTION_REF gate4 $REVIEWED_COMMIT" "$POLICY_IN_EVIDENCE"

ROGUE_ROOT="$TEST_REPO/rogue"
mkdir -p "$ROGUE_ROOT/scripts/production-cutover/lib"
cp "$TEST_REPO/scripts/production-cutover/assert-production-target.sh" \
  "$ROGUE_ROOT/scripts/production-cutover/"
cp "$TEST_REPO/scripts/production-cutover/lib/validate-production-database-target.mjs" \
  "$ROGUE_ROOT/scripts/production-cutover/lib/"
assert_rejected 'Git repository root does not match the checked-in production guard location' \
  gate4 "$DATABASE_URL" "PRODUCTION $PRODUCTION_REF gate4 $REVIEWED_COMMIT" \
  "$POLICY_PATH" "$EVIDENCE_DIR" "$EXECUTOR" "$APPROVER" \
  "$ROGUE_ROOT/scripts/production-cutover/assert-production-target.sh"

printf 'PASS: production target guard fails closed without remote contact.\n'
