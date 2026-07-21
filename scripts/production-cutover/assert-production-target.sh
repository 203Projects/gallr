#!/bin/bash

# Read-only production target attestation. This helper performs no network I/O,
# does not create or modify evidence, and deliberately does not invoke the
# Supabase CLI or psql. It must pass immediately before an independently
# authorized production command.

set -euo pipefail
# Do not allow an inherited `allexport` shell option to make later snapshots of
# credentials or target identifiers part of child-process environments.
set +a

if [[ $- == *x* ]]; then
  set +x
fi

PROGRAM_NAME='production-cutover-target-guard'

fail() {
  builtin printf '%s: ERROR: %s\n' "$PROGRAM_NAME" "$*" >&2
  exit 1
}

[[ -z "${BASH_ENV:-}" || "${BASH_ENV}" == '/dev/null' ]] ||
  fail 'BASH_ENV must be unset or /dev/null for production attestation'
[[ -z "${ENV:-}" || "${ENV}" == '/dev/null' ]] ||
  fail 'ENV must be unset or /dev/null for production attestation'

# Imported shell functions must not replace the reviewed external toolchain or
# path-resolution builtins used by this guard.
unset -f awk basename cd dirname env find git grep node printf pwd sed shasum \
  sha256sum sort stat 2>/dev/null || :

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command is unavailable: $1"
}

require_env() {
  local name="$1"
  [[ -n "${!name:-}" ]] || fail "${name} is required"
}

validate_single_line() {
  local name="$1"
  local value="$2"
  [[ -n "$value" ]] || fail "${name} is required"
  [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] ||
    fail "${name} must be a single-line value"
}

validate_project_ref() {
  local name="$1"
  local value="$2"
  [[ "$value" =~ ^[a-z0-9]{20}$ ]] ||
    fail "${name} must be exactly 20 lowercase alphanumeric characters"
}

mode_of() {
  stat -f '%Lp' "$1" 2>/dev/null || stat -c '%a' "$1" 2>/dev/null ||
    fail "could not inspect permissions"
}

link_count_of() {
  stat -f '%l' "$1" 2>/dev/null || stat -c '%h' "$1" 2>/dev/null ||
    fail "could not inspect link count"
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

line_count() {
  awk -v expected="$2" '$0 == expected { count++ } END { print count + 0 }' "$1"
}

require_exact_line_once() {
  local path="$1"
  local expected="$2"
  local description="$3"
  [[ "$(line_count "$path" "$expected")" == '1' ]] ||
    fail "$description is missing, duplicated, or does not match"
}

canonical_file() {
  local input="$1"
  local description="$2"
  local parent
  local leaf

  [[ "$input" = /* ]] || fail "$description path must be absolute"
  [[ -f "$input" && ! -L "$input" ]] ||
    fail "$description must be a regular file and not a symbolic link"
  parent=$(cd -- "$(dirname -- "$input")" && pwd -P) ||
    fail "cannot resolve $description parent"
  leaf=$(basename -- "$input")
  printf '%s/%s\n' "$parent" "$leaf"
}

safe_git() {
  command env -i \
    PATH="$SAFE_PATH" \
    HOME=/nonexistent \
    USER=production-guard \
    TMPDIR=/tmp \
    LC_ALL=C \
    GIT_CONFIG_COUNT=0 \
    GIT_CONFIG_GLOBAL=/dev/null \
    GIT_CONFIG_NOSYSTEM=1 \
    GIT_OPTIONAL_LOCKS=0 \
    GIT_PAGER=cat \
    PAGER=cat \
    git \
      -c core.fsmonitor=false \
      -c core.hooksPath=/dev/null \
      -c core.excludesFile=/dev/null \
      -c core.attributesFile=/dev/null \
      "$@"
}

sha256_git_blob() {
  local object_spec="$1"
  if command -v shasum >/dev/null 2>&1; then
    safe_git -C "$REPO_ROOT" cat-file blob "$object_spec" |
      shasum -a 256 | awk '{print $1}'
  else
    safe_git -C "$REPO_ROOT" cat-file blob "$object_spec" |
      sha256sum | awk '{print $1}'
  fi
}

# Remove any inherited export attributes from internal names before they are
# assigned target values. `set +a` alone does not de-export a name that the
# caller had already exported.
unset GATE STAGING_REF PRODUCTION_REF PRODUCTION_DATABASE_URL
unset PRODUCTION_CONFIRMATION POLICY_INPUT MANIFEST_INPUT EVIDENCE_INPUT
unset REVIEWED_COMMIT CHANGE_RECORD EXECUTOR APPROVER EXPECTED_CONFIRMATION
unset EVIDENCE_DIR POLICY_PATH MANIFEST_PATH LINKED_REF LINKED_REF_PATH

case "${1:-}" in
  gate4|gate6) GATE=$1 ;;
  *) fail 'usage: assert-production-target.sh gate4|gate6' ;;
esac
[[ $# -eq 1 ]] || fail 'usage: assert-production-target.sh gate4|gate6'

for env_name in \
  GALLR_EXPECTED_STAGING_PROJECT_REF \
  GALLR_PRODUCTION_PROJECT_REF \
  GALLR_PRODUCTION_DATABASE_URL \
  GALLR_PRODUCTION_CONFIRMATION \
  GALLR_PRODUCTION_POLICY_FILE \
  GALLR_OPERATOR_MANIFEST \
  GALLR_PRODUCTION_EVIDENCE_DIR \
  GALLR_REVIEWED_COMMIT \
  GALLR_CHANGE_RECORD \
  GALLR_PRODUCTION_EXECUTOR \
  GALLR_PRODUCTION_APPROVER
do
  require_env "$env_name"
done

STAGING_REF=$GALLR_EXPECTED_STAGING_PROJECT_REF
PRODUCTION_REF=$GALLR_PRODUCTION_PROJECT_REF
PRODUCTION_DATABASE_URL=$GALLR_PRODUCTION_DATABASE_URL
PRODUCTION_CONFIRMATION=$GALLR_PRODUCTION_CONFIRMATION
POLICY_INPUT=$GALLR_PRODUCTION_POLICY_FILE
MANIFEST_INPUT=$GALLR_OPERATOR_MANIFEST
EVIDENCE_INPUT=$GALLR_PRODUCTION_EVIDENCE_DIR
REVIEWED_COMMIT=$GALLR_REVIEWED_COMMIT
CHANGE_RECORD=$GALLR_CHANGE_RECORD
EXECUTOR=$GALLR_PRODUCTION_EXECUTOR
APPROVER=$GALLR_PRODUCTION_APPROVER

# Keep credentials and target values out of every later child environment. The
# URL is provided only to the dedicated parser below, through its environment.
unset \
  GALLR_EXPECTED_STAGING_PROJECT_REF \
  GALLR_PRODUCTION_PROJECT_REF \
  GALLR_PRODUCTION_DATABASE_URL \
  GALLR_PRODUCTION_CONFIRMATION \
  GALLR_PRODUCTION_POLICY_FILE \
  GALLR_OPERATOR_MANIFEST \
  GALLR_PRODUCTION_EVIDENCE_DIR \
  GALLR_REVIEWED_COMMIT \
  GALLR_CHANGE_RECORD \
  GALLR_PRODUCTION_EXECUTOR \
  GALLR_PRODUCTION_APPROVER

# This guard never needs any other database/API credential. Remove common
# credential-bearing and interpreter-injection variables before the first
# external child, and give Git an independently minimal environment below.
unset GALLR_STAGING_DATABASE_URL GALLR_SERVICE_ROLE_KEY DATABASE_URL
unset GALLR_PRODUCTION_VALIDATION_PROJECT_REF
unset GALLR_PRODUCTION_VALIDATION_DATABASE_URL
unset SUPABASE_ACCESS_TOKEN SUPABASE_ANON_KEY SUPABASE_SERVICE_ROLE_KEY
unset SUPABASE_SECRET_KEY
unset PGAPPNAME PGCHANNELBINDING PGCLIENTENCODING PGCONNECT_TIMEOUT
unset PGDATABASE PGDATESTYLE PGGSSENCMODE PGGSSLIB PGHOST PGHOSTADDR
unset PGKRBSRVNAME PGLOADBALANCEHOSTS PGOPTIONS PGPASSFILE PGPASSWORD PGPORT
unset PGREQUIREAUTH PGREQUIREPEER PGSERVICE PGSERVICEFILE PGSSLCERT PGSSLCRL
unset PGREQUIRESSL PGSSLCERTMODE PGSSLCRLDIR PGSSLKEY
unset PGSSLMAXPROTOCOLVERSION PGSSLMINPROTOCOLVERSION
unset PGSSLMODE PGSSLNEGOTIATION PGSSLROOTCERT PGTARGETSESSIONATTRS
unset PGTCP_USER_TIMEOUT PGTZ PGUSER
unset BASH_ENV ENV CDPATH
unset NODE_OPTIONS NODE_PATH NODE_DEBUG NODE_DEBUG_NATIVE NODE_EXTRA_CA_CERTS
unset NODE_TLS_REJECT_UNAUTHORIZED NODE_USE_ENV_PROXY NODE_V8_COVERAGE
unset NODE_COMPILE_CACHE NODE_REDIRECT_WARNINGS
unset PERL5OPT PERL5LIB PYTHONHOME PYTHONPATH RUBYOPT AWKPATH AWKLIBPATH
unset LD_PRELOAD LD_LIBRARY_PATH DYLD_INSERT_LIBRARIES DYLD_LIBRARY_PATH
unset DYLD_FRAMEWORK_PATH DYLD_PRINT_TO_FILE
unset SSL_CERT_FILE SSL_CERT_DIR SSLKEYLOGFILE
unset HTTP_PROXY HTTPS_PROXY ALL_PROXY NO_PROXY
unset http_proxy https_proxy all_proxy no_proxy

# Prevent inherited Git routing/configuration from selecting a different
# repository, index, object database, or configured helper.
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_OBJECT_DIRECTORY
unset GIT_ALTERNATE_OBJECT_DIRECTORIES GIT_COMMON_DIR GIT_CEILING_DIRECTORIES
unset GIT_DISCOVERY_ACROSS_FILESYSTEM GIT_CONFIG GIT_CONFIG_PARAMETERS
unset GIT_CONFIG_SYSTEM GIT_EXEC_PATH GIT_EXTERNAL_DIFF GIT_DIFF_OPTS
unset GIT_SSH GIT_SSH_COMMAND GIT_ASKPASS GIT_SEQUENCE_EDITOR GIT_EDITOR
export GIT_CONFIG_COUNT=0 GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
export GIT_OPTIONAL_LOCKS=0
export LC_ALL=C
SAFE_PATH=$PATH

# These lookups are shell builtins, but keep all command/toolchain checks after
# the target snapshot and environment cleanup so no future child can precede it.
for command_name in env git node awk find grep sort stat; do
  require_command "$command_name"
done
if command -v shasum >/dev/null 2>&1; then
  :
elif command -v sha256sum >/dev/null 2>&1; then
  :
else
  fail 'required SHA-256 command is unavailable: install shasum or sha256sum'
fi

validate_single_line GALLR_EXPECTED_STAGING_PROJECT_REF "$STAGING_REF"
validate_single_line GALLR_PRODUCTION_PROJECT_REF "$PRODUCTION_REF"
validate_single_line GALLR_PRODUCTION_CONFIRMATION "$PRODUCTION_CONFIRMATION"
validate_single_line GALLR_REVIEWED_COMMIT "$REVIEWED_COMMIT"
validate_single_line GALLR_CHANGE_RECORD "$CHANGE_RECORD"
validate_single_line GALLR_PRODUCTION_EXECUTOR "$EXECUTOR"
validate_single_line GALLR_PRODUCTION_APPROVER "$APPROVER"
validate_project_ref GALLR_EXPECTED_STAGING_PROJECT_REF "$STAGING_REF"
validate_project_ref GALLR_PRODUCTION_PROJECT_REF "$PRODUCTION_REF"
[[ "$STAGING_REF" != "$PRODUCTION_REF" ]] ||
  fail 'staging and production project references must be distinct'
[[ "$EXECUTOR" != "$APPROVER" ]] ||
  fail 'production executor and independent approver must be different people'
[[ "$REVIEWED_COMMIT" =~ ^[0-9a-f]{40}([0-9a-f]{24})?$ ]] ||
  fail 'GALLR_REVIEWED_COMMIT must be a full SHA-1 or SHA-256 commit ID'

EXPECTED_CONFIRMATION="PRODUCTION ${PRODUCTION_REF} ${GATE} ${REVIEWED_COMMIT}"
[[ "$PRODUCTION_CONFIRMATION" == "$EXPECTED_CONFIRMATION" ]] ||
  fail 'typed production confirmation does not exactly match target, gate, and commit'
PRODUCTION_CONFIRMATION=''
EXPECTED_CONFIRMATION=''

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
SCRIPT_PATH="$SCRIPT_DIR/$(basename -- "${BASH_SOURCE[0]}")"
[[ -f "$SCRIPT_PATH" && ! -L "$SCRIPT_PATH" ]] ||
  fail 'production target guard must be a regular file and not a symbolic link'
[[ "$(link_count_of "$SCRIPT_PATH")" == '1' ]] ||
  fail 'production target guard must have exactly one hard link'

EXPECTED_REPO_ROOT=$(cd -- "$SCRIPT_DIR/../.." && pwd -P) ||
  fail 'cannot resolve the repository expected from the guard location'
REPO_ROOT=$(safe_git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null) ||
  fail 'the guard must run from a Git worktree'
REPO_ROOT=$(cd -- "$REPO_ROOT" && pwd -P)
[[ "$REPO_ROOT" == "$EXPECTED_REPO_ROOT" ]] ||
  fail 'Git repository root does not match the checked-in production guard location'
[[ "$(safe_git -C "$REPO_ROOT" rev-parse --is-inside-work-tree 2>/dev/null)" == 'true' ]] ||
  fail 'the production guard is not inside the expected Git worktree'

GUARD_RELATIVE='scripts/production-cutover/assert-production-target.sh'
VALIDATOR_RELATIVE='scripts/production-cutover/lib/validate-production-database-target.mjs'
VALIDATOR="$REPO_ROOT/$VALIDATOR_RELATIVE"
[[ "$SCRIPT_PATH" == "$REPO_ROOT/$GUARD_RELATIVE" ]] ||
  fail 'production target guard is not at its reviewed repository path'
[[ -f "$VALIDATOR" && ! -L "$VALIDATOR" ]] ||
  fail 'direct production database URL validator is missing or is a symbolic link'
[[ "$(link_count_of "$VALIDATOR")" == '1' ]] ||
  fail 'direct production database URL validator must have exactly one hard link'

EVIDENCE_INPUT=${EVIDENCE_INPUT%/}
[[ "$EVIDENCE_INPUT" = /* ]] ||
  fail 'production evidence directory path must be absolute'
[[ -d "$EVIDENCE_INPUT" && ! -L "$EVIDENCE_INPUT" ]] ||
  fail 'production evidence directory must exist and not be a symbolic link'
EVIDENCE_DIR=$(cd -- "$EVIDENCE_INPUT" && pwd -P) ||
  fail 'cannot resolve production evidence directory'
case "$EVIDENCE_DIR" in
  "$REPO_ROOT"|"$REPO_ROOT"/*)
    fail 'production evidence directory must be outside the repository'
    ;;
esac
[[ -O "$EVIDENCE_DIR" ]] ||
  fail 'production evidence directory must be owned by the current user'
[[ "$(mode_of "$EVIDENCE_DIR")" == '700' ]] ||
  fail 'production evidence directory must have mode 0700'

POLICY_PATH=$(canonical_file "$POLICY_INPUT" 'production policy artifact')
MANIFEST_PATH=$(canonical_file "$MANIFEST_INPUT" 'operator manifest')
for external_path in "$POLICY_PATH" "$MANIFEST_PATH"; do
  case "$external_path" in
    "$REPO_ROOT"|"$REPO_ROOT"/*)
      fail 'production policy and operator manifest must be outside the repository'
      ;;
  esac
done
case "$POLICY_PATH" in
  "$EVIDENCE_DIR"/*)
    fail 'production policy must be independently stored outside the run evidence directory'
    ;;
esac
[[ "$POLICY_PATH" != "$MANIFEST_PATH" ]] ||
  fail 'production policy and operator manifest must be different files'
[[ -O "$POLICY_PATH" ]] ||
  fail 'production policy artifact must be owned by the current user'
[[ -O "$MANIFEST_PATH" ]] ||
  fail 'operator manifest must be owned by the current user'
[[ "$(link_count_of "$POLICY_PATH")" == '1' ]] ||
  fail 'production policy artifact must have exactly one hard link'
[[ "$(link_count_of "$MANIFEST_PATH")" == '1' ]] ||
  fail 'operator manifest must have exactly one hard link'
[[ "$(mode_of "$POLICY_PATH")" == '400' ]] ||
  fail 'production policy artifact must have exact mode 0400'
MANIFEST_MODE=$(mode_of "$MANIFEST_PATH")
[[ "$MANIFEST_MODE" == '400' || "$MANIFEST_MODE" == '444' ]] ||
  fail 'operator manifest must have mode 0400 or 0444'

POLICY_PARENT=$(dirname -- "$POLICY_PATH")
[[ -O "$POLICY_PARENT" ]] ||
  fail 'production policy directory must be owned by the current user'
[[ "$(mode_of "$POLICY_PARENT")" == '700' ]] ||
  fail 'production policy directory must have mode 0700'

HEAD_COMMIT=$(safe_git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null) ||
  fail 'cannot resolve the repository commit'
[[ "$HEAD_COMMIT" == "$REVIEWED_COMMIT" ]] ||
  fail 'reviewed commit must exactly equal the current repository commit'

safe_git -C "$REPO_ROOT" ls-files --error-unmatch -- \
  "$GUARD_RELATIVE" "$VALIDATOR_RELATIVE" >/dev/null 2>&1 ||
  fail 'production guard and database validator must both be tracked by Git'
REVIEWED_GUARD_SHA256=$(sha256_git_blob "${REVIEWED_COMMIT}:${GUARD_RELATIVE}") ||
  fail 'cannot read production target guard bytes from the reviewed commit'
REVIEWED_VALIDATOR_SHA256=$(sha256_git_blob "${REVIEWED_COMMIT}:${VALIDATOR_RELATIVE}") ||
  fail 'cannot read database validator bytes from the reviewed commit'
[[ "$(sha256_file "$SCRIPT_PATH")" == "$REVIEWED_GUARD_SHA256" ]] ||
  fail 'production target guard bytes differ from the reviewed commit'
[[ "$(sha256_file "$VALIDATOR")" == "$REVIEWED_VALIDATOR_SHA256" ]] ||
  fail 'database validator bytes differ from the reviewed commit'

STAGING_REF_SHA256=$(sha256_text "$STAGING_REF")
PRODUCTION_REF_SHA256=$(sha256_text "$PRODUCTION_REF")
MANIFEST_SHA256=$(sha256_file "$MANIFEST_PATH")
POLICY_SHA256=$(sha256_file "$POLICY_PATH")
CHANGE_RECORD_SHA256=$(sha256_text "$CHANGE_RECORD")
EXECUTOR_SHA256=$(sha256_text "$EXECUTOR")
APPROVER_SHA256=$(sha256_text "$APPROVER")
EVIDENCE_DIR_SHA256=$(sha256_text "$EVIDENCE_DIR")

require_exact_line_once "$MANIFEST_PATH" 'manifest_schema=1' 'operator manifest schema'
require_exact_line_once "$MANIFEST_PATH" 'target=staging' 'operator manifest target'
require_exact_line_once "$MANIFEST_PATH" 'remote_contact_performed=false' 'operator manifest local-only state'
require_exact_line_once "$MANIFEST_PATH" "repository_commit=${HEAD_COMMIT}" 'operator manifest commit'
require_exact_line_once "$MANIFEST_PATH" "staging_project_ref_sha256=${STAGING_REF_SHA256}" 'operator manifest staging target'
require_exact_line_once "$MANIFEST_PATH" "production_project_ref_sha256=${PRODUCTION_REF_SHA256}" 'operator manifest production target'
require_exact_line_once "$MANIFEST_PATH" '[migration_sha256]' 'operator manifest migration section'

MANIFEST_MIGRATION_COUNT=$(awk -F= '
  $1 == "migration_count" { count++; value = $2 }
  END { if (count == 1) print value }
' "$MANIFEST_PATH")
[[ "$MANIFEST_MIGRATION_COUNT" =~ ^[1-9][0-9]*$ ]] ||
  fail 'operator manifest migration count is missing, duplicated, or invalid'

MANIFEST_MIGRATION_ENTRIES=$(awk '
  /^\[migration_sha256\]$/ { in_section = 1; next }
  /^\[/ && in_section { exit }
  in_section && NF { print }
' "$MANIFEST_PATH")
[[ -n "$MANIFEST_MIGRATION_ENTRIES" ]] ||
  fail 'operator manifest contains no migration hashes'

MANIFEST_PATHS=''
MANIFEST_ENTRY_COUNT=0
while IFS= read -r migration_entry; do
  [[ "$migration_entry" =~ ^([0-9a-f]{64})\ \ (supabase/migrations/[A-Za-z0-9._-]+\.sql)$ ]] ||
    fail 'operator manifest contains an invalid migration hash entry'
  EXPECTED_MIGRATION_SHA256=${BASH_REMATCH[1]}
  RELATIVE_MIGRATION=${BASH_REMATCH[2]}
  MIGRATION_PATH="$REPO_ROOT/$RELATIVE_MIGRATION"
  [[ -f "$MIGRATION_PATH" && ! -L "$MIGRATION_PATH" ]] ||
    fail "manifest migration is missing or is a symbolic link: $RELATIVE_MIGRATION"
  safe_git -C "$REPO_ROOT" ls-files --error-unmatch -- "$RELATIVE_MIGRATION" >/dev/null 2>&1 ||
    fail "manifest migration is not tracked by Git: $RELATIVE_MIGRATION"
  [[ "$(sha256_file "$MIGRATION_PATH")" == "$EXPECTED_MIGRATION_SHA256" ]] ||
    fail "migration bytes differ from the operator manifest: $RELATIVE_MIGRATION"
  MANIFEST_PATHS+="$RELATIVE_MIGRATION"$'\n'
  MANIFEST_ENTRY_COUNT=$((MANIFEST_ENTRY_COUNT + 1))
done <<< "$MANIFEST_MIGRATION_ENTRIES"

[[ "$MANIFEST_ENTRY_COUNT" -eq "$MANIFEST_MIGRATION_COUNT" ]] ||
  fail 'operator manifest migration count does not match its hash entries'
MANIFEST_PATHS=$(printf '%s' "$MANIFEST_PATHS" | LC_ALL=C sort)
WORKTREE_PATHS=$(
  while IFS= read -r migration_path; do
    printf '%s\n' "${migration_path#"$REPO_ROOT"/}"
  done < <(find "$REPO_ROOT/supabase/migrations" -maxdepth 1 -type f -name '*.sql' -print) \
    | LC_ALL=C sort
)
[[ "$MANIFEST_PATHS" == "$WORKTREE_PATHS" ]] ||
  fail 'working-tree migration file set differs from the operator manifest'

POLICY_NONEMPTY_LINES=$(awk 'NF { count++ } END { print count + 0 }' "$POLICY_PATH")
POLICY_TOTAL_LINES=$(awk 'END { print NR + 0 }' "$POLICY_PATH")
[[ "$POLICY_NONEMPTY_LINES" == '12' && "$POLICY_TOTAL_LINES" == '12' ]] ||
  fail 'production policy must contain exactly the 12 documented nonempty lines'
require_exact_line_once "$POLICY_PATH" 'policy_schema=1' 'production policy schema'
require_exact_line_once "$POLICY_PATH" 'target=production' 'production policy target'
require_exact_line_once "$POLICY_PATH" 'approval_status=approved' 'production approval status'
require_exact_line_once "$POLICY_PATH" "authorized_gate=${GATE}" 'authorized production gate'
require_exact_line_once "$POLICY_PATH" "production_project_ref_sha256=${PRODUCTION_REF_SHA256}" 'approved production target'
require_exact_line_once "$POLICY_PATH" "staging_project_ref_sha256=${STAGING_REF_SHA256}" 'approved staging boundary'
require_exact_line_once "$POLICY_PATH" "repository_commit=${HEAD_COMMIT}" 'approved repository commit'
require_exact_line_once "$POLICY_PATH" "operator_manifest_sha256=${MANIFEST_SHA256}" 'approved operator manifest'
require_exact_line_once "$POLICY_PATH" "change_record_sha256=${CHANGE_RECORD_SHA256}" 'approved change record'
require_exact_line_once "$POLICY_PATH" "executor_sha256=${EXECUTOR_SHA256}" 'approved executor'
require_exact_line_once "$POLICY_PATH" "approver_sha256=${APPROVER_SHA256}" 'independent approver'
require_exact_line_once "$POLICY_PATH" "evidence_directory_sha256=${EVIDENCE_DIR_SHA256}" 'approved evidence directory'

DIRTY_WORKTREE=$(safe_git -C "$REPO_ROOT" status --porcelain=v1 --untracked-files=all)
[[ -z "$DIRTY_WORKTREE" ]] ||
  fail 'production cutover checkout must be completely clean'

LINKED_REF_PATH="$REPO_ROOT/supabase/.temp/project-ref"
[[ -f "$LINKED_REF_PATH" && ! -L "$LINKED_REF_PATH" ]] ||
  fail 'linked project ref file is missing or is a symbolic link'
LINKED_REF_PARENT=$(cd -- "$(dirname -- "$LINKED_REF_PATH")" && pwd -P) ||
  fail 'cannot resolve linked project ref directory'
[[ "$LINKED_REF_PARENT" == "$REPO_ROOT/supabase/.temp" ]] ||
  fail 'linked project ref directory must not traverse a symbolic link'
[[ "$(awk 'END { print NR + 0 }' "$LINKED_REF_PATH")" == '1' ]] ||
  fail 'linked project ref file must contain exactly one line'
LINKED_REF=$(sed -n '1p' "$LINKED_REF_PATH")
validate_project_ref 'linked project ref' "$LINKED_REF"
[[ "$LINKED_REF" == "$PRODUCTION_REF" ]] ||
  fail 'linked project is not the exact approved production project'
[[ "$LINKED_REF" != "$STAGING_REF" ]] ||
  fail 'linked project resolves to staging instead of production'

# Narrow concurrent local-artifact changes after semantic validation. A pass
# requires the same sealed policy/manifest bytes, modes, and single-link inodes
# that were reviewed above.
[[ "$(link_count_of "$POLICY_PATH")" == '1' &&
  "$(mode_of "$POLICY_PATH")" == '400' &&
  "$(sha256_file "$POLICY_PATH")" == "$POLICY_SHA256" ]] ||
  fail 'production policy changed during target attestation'
MANIFEST_MODE=$(mode_of "$MANIFEST_PATH")
[[ "$(link_count_of "$MANIFEST_PATH")" == '1' &&
  ( "$MANIFEST_MODE" == '400' || "$MANIFEST_MODE" == '444' ) &&
  "$(sha256_file "$MANIFEST_PATH")" == "$MANIFEST_SHA256" ]] ||
  fail 'operator manifest changed during target attestation'

# Re-attest the validator immediately before execution so a change during the
# preceding local checks cannot silently substitute different parser code.
[[ "$(sha256_file "$VALIDATOR")" == "$REVIEWED_VALIDATOR_SHA256" ]] ||
  fail 'database validator changed during production target attestation'
# Execute the reviewed Git blob itself over stdin. The URL is scoped only to the
# Node side of the pipeline, stays out of argv, and cannot reach the Git child.
if ! safe_git -C "$REPO_ROOT" cat-file blob \
  "${REVIEWED_COMMIT}:${VALIDATOR_RELATIVE}" |
  GALLR_PRODUCTION_VALIDATION_PROJECT_REF="$PRODUCTION_REF" \
  GALLR_PRODUCTION_VALIDATION_DATABASE_URL="$PRODUCTION_DATABASE_URL" \
  NODE_OPTIONS='' NODE_PATH='' \
    node --input-type=module -
then
  fail 'direct production database URL validation failed'
fi
PRODUCTION_DATABASE_URL=''

printf 'PASS: exact production target attested for %s; no remote contact performed\n' "$GATE"
