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
SOLO_POLICY_NODE_MARKER="$TEST_ROOT/inherited-path-node-used-for-solo-policy"
SOLO_POLICY_NODE_BYPASS="$TEST_ROOT/inherited-path-node-bypass-enabled"
FS_MONITOR_MARKER="$TEST_ROOT/fsmonitor-invoked"
REAL_GIT=$(command -v git)
REAL_NODE=$(command -v node)
REAL_EXPECT=$(command -v expect 2>/dev/null || true)
[[ "$REAL_EXPECT" = /* && -x "$REAL_EXPECT" ]] || {
  printf 'expect is required on PATH for terminal-backed production guard tests\n' >&2
  exit 1
}

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
  "$TEST_REPO/scripts/staging-rehearsal" \
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
printf '%s\n' 'efbe42620ff99f5929a6316de76740a3a55aa641f6e69538c83995156933d7d0' \
  > "$TEST_REPO/scripts/staging-rehearsal/production-project-ref.sha256"
printf '%s\n' '42492da06234ad0ac76f5d5debdb6d1ae027cffbe746a1c13b89bb8bc0139137' \
  > "$TEST_REPO/scripts/staging-rehearsal/legacy-compatibility-project-ref.sha256"

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
    '[ "${2:-}" = - ] || fail_node_env' \
    'if [ "${GALLR_PRODUCTION_VALIDATION_KIND:-}" = solo_policy_window ]; then' \
    '  [ -n "${GALLR_PRODUCTION_VALIDATION_ISSUED_AT:-}" ] || fail_node_env' \
    '  [ -n "${GALLR_PRODUCTION_VALIDATION_VALID_UNTIL:-}" ] || fail_node_env' \
    '  [ -n "${GALLR_PRODUCTION_VALIDATION_POLICY_MTIME:-}" ] || fail_node_env' \
    '  [ "${GALLR_PRODUCTION_VALIDATION_PROJECT_REF+x}" != x ] || fail_node_env' \
    '  [ "${GALLR_PRODUCTION_VALIDATION_DATABASE_URL+x}" != x ] || fail_node_env' \
    'else' \
    '  [ "${GALLR_PRODUCTION_VALIDATION_KIND+x}" != x ] || fail_node_env' \
    '  [ -n "${GALLR_PRODUCTION_VALIDATION_PROJECT_REF:-}" ] || fail_node_env' \
    '  [ -n "${GALLR_PRODUCTION_VALIDATION_DATABASE_URL:-}" ] || fail_node_env' \
    '  [ "${GALLR_PRODUCTION_VALIDATION_ISSUED_AT+x}" != x ] || fail_node_env' \
    '  [ "${GALLR_PRODUCTION_VALIDATION_VALID_UNTIL+x}" != x ] || fail_node_env' \
    '  [ "${GALLR_PRODUCTION_VALIDATION_POLICY_MTIME+x}" != x ] || fail_node_env' \
    'fi'
  printf 'if [ "${GALLR_PRODUCTION_VALIDATION_KIND:-}" = solo_policy_window ] && [ -e "%s" ]; then : > "%s"; exit 0; fi\n' \
    "$SOLO_POLICY_NODE_BYPASS" "$SOLO_POLICY_NODE_MARKER"
  printf 'if [ "${GALLR_PRODUCTION_VALIDATION_KIND:-}" = solo_policy_window ]; then : > "%s"; fi\n' \
    "$SOLO_POLICY_NODE_MARKER"
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
utc_after_seconds() {
  "$REAL_NODE" -e \
    'process.stdout.write(new Date(Date.now() + Number(process.argv[1]) * 1000).toISOString().replace(/\.\d{3}Z$/, "Z"))' \
    -- "$1"
}

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
  local production_target_mode="${1:-staging_rehearsal}"
  chmod 600 "$MANIFEST_PATH" 2>/dev/null || true
  {
    printf 'manifest_schema=1\n'
    printf 'target=staging\n'
    printf 'production_target_mode=%s\n' "$production_target_mode"
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

write_solo_manifest() {
  local migration_path='supabase/migrations/20260722000000_test.sql'
  local reviewer="${1:-$EXECUTOR}"
  local production_target_mode="${2:-staging_rehearsal}"
  chmod 600 "$MANIFEST_PATH" 2>/dev/null || true
  {
    printf 'manifest_schema=2\n'
    printf 'run_id=production-solo-test\n'
    printf 'generated_at_utc=%s\n' "$(utc_after_seconds -3600)"
    printf 'target=staging\n'
    printf 'production_target_mode=%s\n' "$production_target_mode"
    printf 'change_record=%s\n' "$CHANGE_RECORD"
    printf 'executor=%s\n' "$EXECUTOR"
    printf 'reviewer=%s\n' "$reviewer"
    printf 'repository_commit=%s\n' "$REVIEWED_COMMIT"
    printf 'staging_project_ref_sha256=%s\n' "$(sha256_text "$STAGING_REF")"
    printf 'production_project_ref_sha256=%s\n' "$(sha256_text "$PRODUCTION_REF")"
    printf 'governance_mode=solo_operator\n'
    printf 'human_reviewer_count=0\n'
    printf 'automation_is_independent_human_review=false\n'
    printf 'residual_risk_accepted=true\n'
    printf 'minimum_cooldown_seconds=900\n'
    printf 'destructive_actions=forbidden\n'
    printf 'first_confirmation_sha256=%s\n' "$(sha256_text "INTENT STAGING $STAGING_REF NOT PRODUCTION $PRODUCTION_REF $REVIEWED_COMMIT ACCEPT_NO_INDEPENDENT_REVIEW")"
    printf 'remote_contact_performed=false\n'
    printf 'migration_count=1\n'
    printf '\n[migration_sha256]\n'
    printf '%s  %s\n' "$(sha256_file "$TEST_REPO/$migration_path")" "$migration_path"
  } > "$MANIFEST_PATH"
  chmod 444 "$MANIFEST_PATH"
}

write_solo_policy() {
  local gate="${1:-gate4}"
  local operation="${2:-additive_database_deploy}"
  local destructive_actions="${3:-forbidden}"
  local cooldown_seconds="${4:-1800}"
  local issued_at="${5:-$(utc_after_seconds -1900)}"
  local valid_until="${6:-$(utc_after_seconds 1200)}"
  local first_confirmation="${7:-INTENT PRODUCTION $PRODUCTION_REF NOT STAGING $STAGING_REF $gate $operation $REVIEWED_COMMIT}"
  local operator="${8:-$EXECUTOR}"
  chmod 600 "$POLICY_PATH" 2>/dev/null || true
  {
    printf 'policy_schema=2\n'
    printf 'policy_kind=gallr_production_cutover\n'
    printf 'governance_mode=solo_operator\n'
    printf 'target=production\n'
    printf 'approval_status=self_attested\n'
    printf 'authorized_gate=%s\n' "$gate"
    printf 'authorized_operation=%s\n' "$operation"
    printf 'destructive_actions=%s\n' "$destructive_actions"
    printf 'issued_at_utc=%s\n' "$issued_at"
    printf 'valid_until_utc=%s\n' "$valid_until"
    printf 'minimum_cooldown_seconds=%s\n' "$cooldown_seconds"
    printf 'production_project_ref_sha256=%s\n' "$(sha256_text "$PRODUCTION_REF")"
    printf 'staging_project_ref_sha256=%s\n' "$(sha256_text "$STAGING_REF")"
    printf 'repository_commit=%s\n' "$REVIEWED_COMMIT"
    printf 'operator_manifest_sha256=%s\n' "$(sha256_file "$MANIFEST_PATH")"
    printf 'change_record_sha256=%s\n' "$(sha256_text "$CHANGE_RECORD")"
    printf 'operator_identity_sha256=%s\n' "$(sha256_text "$operator")"
    printf 'evidence_directory_sha256=%s\n' "$(sha256_text "$EVIDENCE_DIR")"
    printf 'first_confirmation_sha256=%s\n' "$(sha256_text "$first_confirmation")"
  } > "$POLICY_PATH"
  chmod 400 "$POLICY_PATH"
}

set_policy_age_seconds() {
  "$REAL_NODE" -e \
    'const fs = require("node:fs"); const when = new Date(Date.now() - Number(process.argv[2]) * 1000); fs.utimesSync(process.argv[1], when, when);' \
    "$POLICY_PATH" "$1"
}

run_with_tty_confirmation() {
  local prompt="$1"
  local confirmation="$2"
  shift 2

  "$REAL_EXPECT" -f - "$prompt" "$confirmation" "$@" <<'EXPECT' |
set timeout 30
set prompt [lindex $argv 0]
set confirmation [lindex $argv 1]
set command [lrange $argv 2 end]
log_user 0
spawn -noecho /bin/bash -c {/bin/stty -echo; exec "$@"} gallr-pty {*}$command
expect {
  -exact $prompt {}
  eof {
    puts -nonewline $expect_out(buffer)
    set result [wait]
    exit [lindex $result 3]
  }
  timeout {
    puts stderr "timed out before production confirmation prompt"
    exit 124
  }
}
send -- "$confirmation\r"
expect {
  eof { set transcript $expect_out(buffer) }
  timeout {
    puts stderr "timed out after production confirmation prompt"
    exit 124
  }
}
puts -nonewline $transcript
set result [wait]
exit [lindex $result 3]
EXPECT
    tr -d '\r'
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
  local governance_mode="${9:-}"
  local include_approver="${10:-auto}"
  local include_confirmation="${11:-auto}"
  local input_mode="${12:-pipe}"

  local -a guard_environment=(
    env
    PATH="$FAKE_BIN:$PATH"
    REMOTE_MARKER="$REMOTE_MARKER"
    GALLR_EXPECTED_STAGING_PROJECT_REF="$BASE_STAGING_REF"
    GALLR_PRODUCTION_PROJECT_REF="$BASE_PRODUCTION_REF"
    GALLR_PRODUCTION_DATABASE_URL="$database_url"
    GALLR_PRODUCTION_POLICY_FILE="$policy_path"
    GALLR_OPERATOR_MANIFEST="$MANIFEST_PATH"
    GALLR_PRODUCTION_EVIDENCE_DIR="$evidence_dir"
    GALLR_REVIEWED_COMMIT="$BASE_REVIEWED_COMMIT"
    GALLR_CHANGE_RECORD="$BASE_CHANGE_RECORD"
    GALLR_PRODUCTION_EXECUTOR="$executor"
  )
  if [[ -n "$governance_mode" ]]; then
    guard_environment+=(GALLR_GOVERNANCE_MODE="$governance_mode")
  fi
  if [[ "$include_confirmation" == 'yes' ||
    ( "$include_confirmation" == 'auto' && "$governance_mode" != 'solo_operator' ) ]]; then
    guard_environment+=(GALLR_PRODUCTION_CONFIRMATION="$confirmation")
  fi
  if [[ "$include_approver" == 'yes' ||
    ( "$include_approver" == 'auto' && "$governance_mode" != 'solo_operator' ) ]]; then
    guard_environment+=(GALLR_PRODUCTION_APPROVER="$approver")
  fi

  case "$input_mode" in
    pipe)
      printf '%s\n' "$confirmation" | \
        "${guard_environment[@]}" /bin/bash "$guard_path" "$gate"
      ;;
    tty)
      run_with_tty_confirmation \
        'Type the solo-operator production execution confirmation, then press Return: ' \
        "$confirmation" \
        "${guard_environment[@]}" /bin/bash "$guard_path" "$gate"
      ;;
    *)
      printf 'Unsupported production guard test input mode: %s\n' "$input_mode" >&2
      return 2
      ;;
  esac
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
grep -Fxq \
  'PASS: exact production target attested for gate4; no remote contact performed' \
  "$TEST_ROOT/pass.stdout"
run_guard gate4 "$DATABASE_URL" \
  "PRODUCTION $PRODUCTION_REF gate4 $REVIEWED_COMMIT" \
  "$POLICY_PATH" "$EVIDENCE_DIR" "$EXECUTOR" "$APPROVER" \
  "$TEST_REPO/scripts/production-cutover/assert-production-target.sh" \
  separated_humans > "$TEST_ROOT/explicit-separated-humans.stdout"
grep -Fxq \
  'PASS: exact production target attested for gate4; no remote contact performed' \
  "$TEST_ROOT/explicit-separated-humans.stdout"
[[ ! -e "$REMOTE_MARKER" ]] || {
  printf 'Valid guard unexpectedly invoked a remote-capable command.\n' >&2
  exit 1
}
[[ ! -e "$GIT_ENV_FAILURE" && ! -e "$NODE_ENV_FAILURE" ]] || {
  printf 'A trusted child received an unsafe inherited environment.\n' >&2
  exit 1
}

write_manifest legacy_mobile_catalog_pair
write_policy
run_guard > "$TEST_ROOT/production-pair-pass.stdout"
grep -Fxq \
  'PASS: exact production target attested for gate4; no remote contact performed' \
  "$TEST_ROOT/production-pair-pass.stdout"

write_manifest arbitrary_pair
write_policy
assert_rejected 'operator manifest production target mode is unsupported'
write_manifest
write_policy

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

write_policy gate4 "$PRODUCTION_REF" 'Operator@Example.Invalid' 'operator@example.invalid'
assert_rejected 'production executor and independent approver must be different people' \
  gate4 "$DATABASE_URL" "PRODUCTION $PRODUCTION_REF gate4 $REVIEWED_COMMIT" \
  "$POLICY_PATH" "$EVIDENCE_DIR" 'Operator@Example.Invalid' 'operator@example.invalid'
write_policy

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
rm -rf -- "$ROGUE_ROOT"

# Solo-operator governance is explicit and uses a separate schema. The first
# target-bound confirmation is sealed into the policy; the second is typed only
# after both the policy issue time and its filesystem mtime satisfy the fixed
# 30-minute cooldown.
write_solo_manifest
write_solo_policy
set_policy_age_seconds 1900
SOLO_GATE4_CONFIRMATION="EXECUTE PRODUCTION $PRODUCTION_REF NOT STAGING $STAGING_REF gate4 additive_database_deploy $REVIEWED_COMMIT"
assert_rejected 'solo production execution confirmation requires an interactive terminal' \
  gate4 "$DATABASE_URL" "$SOLO_GATE4_CONFIRMATION" \
  "$POLICY_PATH" "$EVIDENCE_DIR" "$EXECUTOR" "$APPROVER" \
  "$TEST_REPO/scripts/production-cutover/assert-production-target.sh" \
  solo_operator auto auto pipe
run_guard gate4 "$DATABASE_URL" "$SOLO_GATE4_CONFIRMATION" \
  "$POLICY_PATH" "$EVIDENCE_DIR" "$EXECUTOR" "$APPROVER" \
  "$TEST_REPO/scripts/production-cutover/assert-production-target.sh" \
  solo_operator auto auto tty > "$TEST_ROOT/solo-gate4.stdout"
grep -Fxq \
  'PASS: exact production target attested for gate4; no remote contact performed; legacy retirement is not authorized' \
  "$TEST_ROOT/solo-gate4.stdout"
[[ ! -e "$SOLO_POLICY_NODE_MARKER" ]] || {
  printf 'Solo cooldown validation resolved Node.js through inherited PATH.\n' >&2
  exit 1
}

assert_rejected 'GALLR_PRODUCTION_CONFIRMATION must be unset in solo_operator mode' \
  gate4 "$DATABASE_URL" "$SOLO_GATE4_CONFIRMATION" \
  "$POLICY_PATH" "$EVIDENCE_DIR" "$EXECUTOR" "$APPROVER" \
  "$TEST_REPO/scripts/production-cutover/assert-production-target.sh" \
  solo_operator auto yes

write_solo_manifest 'different-reviewer@example.invalid'
write_solo_policy
set_policy_age_seconds 1900
assert_rejected 'solo operator manifest reviewer disclosure is missing, duplicated, or does not match' \
  gate4 "$DATABASE_URL" "$SOLO_GATE4_CONFIRMATION" \
  "$POLICY_PATH" "$EVIDENCE_DIR" "$EXECUTOR" "$APPROVER" \
  "$TEST_REPO/scripts/production-cutover/assert-production-target.sh" \
  solo_operator

write_solo_manifest
write_solo_policy gate4 additive_database_deploy forbidden 1800 \
  "$(utc_after_seconds -1900)" "$(utc_after_seconds 1200)" \
  "INTENT PRODUCTION $PRODUCTION_REF NOT STAGING $STAGING_REF gate4 additive_database_deploy $REVIEWED_COMMIT" \
  'different-operator@example.invalid'
set_policy_age_seconds 1900
assert_rejected 'approved solo operator is missing, duplicated, or does not match' \
  gate4 "$DATABASE_URL" "$SOLO_GATE4_CONFIRMATION" \
  "$POLICY_PATH" "$EVIDENCE_DIR" "$EXECUTOR" "$APPROVER" \
  "$TEST_REPO/scripts/production-cutover/assert-production-target.sh" \
  solo_operator

write_solo_policy
set_policy_age_seconds 1900

assert_rejected 'GALLR_PRODUCTION_APPROVER must be unset in solo_operator mode' \
  gate4 "$DATABASE_URL" "$SOLO_GATE4_CONFIRMATION" \
  "$POLICY_PATH" "$EVIDENCE_DIR" "$EXECUTOR" "$APPROVER" \
  "$TEST_REPO/scripts/production-cutover/assert-production-target.sh" \
  solo_operator yes

write_manifest
write_solo_policy
set_policy_age_seconds 1900
assert_rejected 'separated_humans mode requires a schema-1 production policy' \
  gate4 "$DATABASE_URL" "PRODUCTION $PRODUCTION_REF gate4 $REVIEWED_COMMIT" \
  "$POLICY_PATH" "$EVIDENCE_DIR" "$EXECUTOR" "$APPROVER"

write_solo_manifest
write_policy
assert_rejected 'solo_operator mode requires a schema-2 production policy' \
  gate4 "$DATABASE_URL" "$SOLO_GATE4_CONFIRMATION" \
  "$POLICY_PATH" "$EVIDENCE_DIR" "$EXECUTOR" "$APPROVER" \
  "$TEST_REPO/scripts/production-cutover/assert-production-target.sh" \
  solo_operator

write_solo_policy gate4 ownership_transfer
set_policy_age_seconds 1900
assert_rejected 'authorized production operation is missing, duplicated, or does not match' \
  gate4 "$DATABASE_URL" "$SOLO_GATE4_CONFIRMATION" \
  "$POLICY_PATH" "$EVIDENCE_DIR" "$EXECUTOR" "$APPROVER" \
  "$TEST_REPO/scripts/production-cutover/assert-production-target.sh" \
  solo_operator

write_solo_policy gate4 additive_database_deploy allowed
set_policy_age_seconds 1900
assert_rejected 'solo production policy must forbid destructive actions' \
  gate4 "$DATABASE_URL" "$SOLO_GATE4_CONFIRMATION" \
  "$POLICY_PATH" "$EVIDENCE_DIR" "$EXECUTOR" "$APPROVER" \
  "$TEST_REPO/scripts/production-cutover/assert-production-target.sh" \
  solo_operator

write_solo_policy gate4 additive_database_deploy forbidden 60
set_policy_age_seconds 1900
assert_rejected 'solo production policy cooldown must be exactly 1800 seconds' \
  gate4 "$DATABASE_URL" "$SOLO_GATE4_CONFIRMATION" \
  "$POLICY_PATH" "$EVIDENCE_DIR" "$EXECUTOR" "$APPROVER" \
  "$TEST_REPO/scripts/production-cutover/assert-production-target.sh" \
  solo_operator

write_solo_policy gate4 additive_database_deploy forbidden 1800 \
  "$(utc_after_seconds -120)" "$(utc_after_seconds 1200)"
set_policy_age_seconds 1900
assert_rejected 'solo production policy timestamps or cooldown are invalid' \
  gate4 "$DATABASE_URL" "$SOLO_GATE4_CONFIRMATION" \
  "$POLICY_PATH" "$EVIDENCE_DIR" "$EXECUTOR" "$APPROVER" \
  "$TEST_REPO/scripts/production-cutover/assert-production-target.sh" \
  solo_operator

: > "$SOLO_POLICY_NODE_BYPASS"
assert_rejected 'solo production policy timestamps or cooldown are invalid' \
  gate4 "$DATABASE_URL" "$SOLO_GATE4_CONFIRMATION" \
  "$POLICY_PATH" "$EVIDENCE_DIR" "$EXECUTOR" "$APPROVER" \
  "$TEST_REPO/scripts/production-cutover/assert-production-target.sh" \
  solo_operator auto auto tty
rm -f -- "$SOLO_POLICY_NODE_BYPASS"
[[ ! -e "$SOLO_POLICY_NODE_MARKER" ]] || {
  printf 'Inherited PATH substituted the solo cooldown interpreter.\n' >&2
  exit 1
}

write_solo_policy
set_policy_age_seconds 120
assert_rejected 'solo production policy timestamps or cooldown are invalid' \
  gate4 "$DATABASE_URL" "$SOLO_GATE4_CONFIRMATION" \
  "$POLICY_PATH" "$EVIDENCE_DIR" "$EXECUTOR" "$APPROVER" \
  "$TEST_REPO/scripts/production-cutover/assert-production-target.sh" \
  solo_operator

write_solo_policy gate4 additive_database_deploy forbidden 1800 \
  "$(utc_after_seconds -3700)" "$(utc_after_seconds 1200)"
set_policy_age_seconds 3700
assert_rejected 'solo production policy timestamps or cooldown are invalid' \
  gate4 "$DATABASE_URL" "$SOLO_GATE4_CONFIRMATION" \
  "$POLICY_PATH" "$EVIDENCE_DIR" "$EXECUTOR" "$APPROVER" \
  "$TEST_REPO/scripts/production-cutover/assert-production-target.sh" \
  solo_operator

write_solo_policy gate4 additive_database_deploy forbidden 1800 \
  "$(utc_after_seconds -1900)" "$(utc_after_seconds 1200)" \
  "INTENT PRODUCTION $PRODUCTION_REF NOT STAGING $STAGING_REF gate6 ownership_transfer $REVIEWED_COMMIT"
set_policy_age_seconds 1900
assert_rejected 'first solo confirmation is missing, duplicated, or does not match' \
  gate4 "$DATABASE_URL" "$SOLO_GATE4_CONFIRMATION" \
  "$POLICY_PATH" "$EVIDENCE_DIR" "$EXECUTOR" "$APPROVER" \
  "$TEST_REPO/scripts/production-cutover/assert-production-target.sh" \
  solo_operator

write_solo_policy
set_policy_age_seconds 1900
assert_rejected 'typed solo production confirmation does not exactly match' \
  gate4 "$DATABASE_URL" \
  "EXECUTE PRODUCTION $PRODUCTION_REF NOT STAGING $STAGING_REF gate4 ownership_transfer $REVIEWED_COMMIT" \
  "$POLICY_PATH" "$EVIDENCE_DIR" "$EXECUTOR" "$APPROVER" \
  "$TEST_REPO/scripts/production-cutover/assert-production-target.sh" \
  solo_operator auto auto tty

write_solo_policy gate6 ownership_transfer
set_policy_age_seconds 1900
SOLO_GATE6_CONFIRMATION="EXECUTE PRODUCTION $PRODUCTION_REF NOT STAGING $STAGING_REF gate6 ownership_transfer $REVIEWED_COMMIT"
run_guard gate6 "$DATABASE_URL" "$SOLO_GATE6_CONFIRMATION" \
  "$POLICY_PATH" "$EVIDENCE_DIR" "$EXECUTOR" "$APPROVER" \
  "$TEST_REPO/scripts/production-cutover/assert-production-target.sh" \
  solo_operator auto auto tty > "$TEST_ROOT/solo-gate6.stdout"
grep -Fxq \
  'PASS: exact production target attested for gate6; no remote contact performed; legacy retirement is not authorized' \
  "$TEST_ROOT/solo-gate6.stdout"

[[ ! -e "$REMOTE_MARKER" && ! -e "$GIT_ENV_FAILURE" &&
  ! -e "$NODE_ENV_FAILURE" ]] || {
  printf 'Solo governance invoked a forbidden command or leaked an unsafe child environment.\n' >&2
  exit 1
}

printf 'PASS: production target guard fails closed without remote contact.\n'
