#!/bin/bash

# Read-only production target attestation. This helper performs no network I/O,
# does not create or modify evidence, and deliberately does not invoke the
# Supabase CLI or psql. It must pass immediately before the separately reviewed
# production command described by the selected governance mode.

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

mtime_of() {
  stat -f '%m' "$1" 2>/dev/null || stat -c '%Y' "$1" 2>/dev/null ||
    fail "could not inspect modification time"
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

single_value_of() {
  awk -v expected_key="$2" '
    index($0, expected_key "=") == 1 {
      count++
      value = substr($0, length(expected_key) + 2)
    }
    END { if (count == 1) print value }
  ' "$1"
}

validate_solo_policy_window() {
  /usr/bin/env -i \
    LC_ALL=C \
    GALLR_PRODUCTION_VALIDATION_KIND=solo_policy_window \
    GALLR_PRODUCTION_VALIDATION_ISSUED_AT="$1" \
    GALLR_PRODUCTION_VALIDATION_VALID_UNTIL="$2" \
    GALLR_PRODUCTION_VALIDATION_POLICY_MTIME="$3" \
    "$TRUSTED_NODE" --input-type=module - <<'NODE'
const issuedText = process.env.GALLR_PRODUCTION_VALIDATION_ISSUED_AT || "";
const validUntilText = process.env.GALLR_PRODUCTION_VALIDATION_VALID_UNTIL || "";
const policyMtimeText = process.env.GALLR_PRODUCTION_VALIDATION_POLICY_MTIME || "";
const exactUtc = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/;
const cooldownMs = 1800 * 1000;
const maximumLifetimeMs = 60 * 60 * 1000;

function exactEpoch(text) {
  if (!exactUtc.test(text)) return NaN;
  const epoch = Date.parse(text);
  if (!Number.isFinite(epoch)) return NaN;
  return new Date(epoch).toISOString().replace(".000Z", "Z") === text
    ? epoch
    : NaN;
}

const issuedAt = exactEpoch(issuedText);
const validUntil = exactEpoch(validUntilText);
const policyMtimeSeconds = Number(policyMtimeText);
const now = Date.now();
if (
  !Number.isFinite(issuedAt) ||
  !Number.isFinite(validUntil) ||
  !Number.isSafeInteger(policyMtimeSeconds) ||
  policyMtimeSeconds < 0 ||
  issuedAt > now - cooldownMs ||
  // stat(1) exposes whole seconds here. Treat the unseen fractional second as
  // maximally recent so the cooldown can never pass early due to truncation.
  (policyMtimeSeconds + 1) * 1000 > now - cooldownMs ||
  validUntil <= now ||
  validUntil <= issuedAt ||
  validUntil - issuedAt > maximumLifetimeMs
) {
  process.exit(1);
}
NODE
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
unset GOVERNANCE_MODE OPERATION EXPECTED_INTENT_CONFIRMATION
unset EXPECTED_FIRST_CONFIRMATION_SHA256 POLICY_MTIME_EPOCH
unset POLICY_ISSUED_AT POLICY_VALID_UNTIL
unset EXECUTOR_CANONICAL APPROVER_CANONICAL STAGING_INTENT_CONFIRMATION
unset TRUSTED_NODE

case "${1:-}" in
  gate4|gate6) GATE=$1 ;;
  *) fail 'usage: assert-production-target.sh gate4|gate6' ;;
esac
[[ $# -eq 1 ]] || fail 'usage: assert-production-target.sh gate4|gate6'

GOVERNANCE_MODE=${GALLR_GOVERNANCE_MODE-separated_humans}
case "$GOVERNANCE_MODE" in
  separated_humans|solo_operator) ;;
  *) fail 'GALLR_GOVERNANCE_MODE must be separated_humans or solo_operator' ;;
esac

case "$GATE" in
  gate4) OPERATION='additive_database_deploy' ;;
  gate6) OPERATION='ownership_transfer' ;;
esac

for env_name in \
  GALLR_EXPECTED_STAGING_PROJECT_REF \
  GALLR_PRODUCTION_PROJECT_REF \
  GALLR_PRODUCTION_DATABASE_URL \
  GALLR_PRODUCTION_POLICY_FILE \
  GALLR_OPERATOR_MANIFEST \
  GALLR_PRODUCTION_EVIDENCE_DIR \
  GALLR_REVIEWED_COMMIT \
  GALLR_CHANGE_RECORD \
  GALLR_PRODUCTION_EXECUTOR
do
  require_env "$env_name"
done

if [[ "$GOVERNANCE_MODE" == 'separated_humans' ]]; then
  require_env GALLR_PRODUCTION_CONFIRMATION
  require_env GALLR_PRODUCTION_APPROVER
else
  [[ "${GALLR_PRODUCTION_CONFIRMATION+x}" != 'x' ]] ||
    fail 'GALLR_PRODUCTION_CONFIRMATION must be unset in solo_operator mode; type it at the guard prompt after cooldown'
  [[ "${GALLR_PRODUCTION_APPROVER+x}" != 'x' ]] ||
    fail 'GALLR_PRODUCTION_APPROVER must be unset in solo_operator mode'
fi

STAGING_REF=$GALLR_EXPECTED_STAGING_PROJECT_REF
PRODUCTION_REF=$GALLR_PRODUCTION_PROJECT_REF
PRODUCTION_DATABASE_URL=$GALLR_PRODUCTION_DATABASE_URL
PRODUCTION_CONFIRMATION=${GALLR_PRODUCTION_CONFIRMATION:-}
POLICY_INPUT=$GALLR_PRODUCTION_POLICY_FILE
MANIFEST_INPUT=$GALLR_OPERATOR_MANIFEST
EVIDENCE_INPUT=$GALLR_PRODUCTION_EVIDENCE_DIR
REVIEWED_COMMIT=$GALLR_REVIEWED_COMMIT
CHANGE_RECORD=$GALLR_CHANGE_RECORD
EXECUTOR=$GALLR_PRODUCTION_EXECUTOR
APPROVER=${GALLR_PRODUCTION_APPROVER:-}

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
  GALLR_PRODUCTION_APPROVER \
  GALLR_GOVERNANCE_MODE

# This guard never needs any other database/API credential. Remove common
# credential-bearing and interpreter-injection variables before the first
# external child, and give Git an independently minimal environment below.
unset GALLR_STAGING_DATABASE_URL GALLR_SERVICE_ROLE_KEY DATABASE_URL
unset GALLR_PRODUCTION_VALIDATION_PROJECT_REF
unset GALLR_PRODUCTION_VALIDATION_DATABASE_URL
unset GALLR_PRODUCTION_VALIDATION_KIND
unset GALLR_PRODUCTION_VALIDATION_ISSUED_AT
unset GALLR_PRODUCTION_VALIDATION_VALID_UNTIL
unset GALLR_PRODUCTION_VALIDATION_POLICY_MTIME
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
TRUSTED_NODE=''
if [[ "$GOVERNANCE_MODE" == 'solo_operator' ]]; then
  TRUSTED_NODE=$(
    PATH='/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin'
    command -v node
  ) || fail 'trusted Node.js interpreter is unavailable'
  case "$TRUSTED_NODE" in
    /usr/bin/node|/bin/node|/usr/local/bin/node|/opt/homebrew/bin/node) ;;
    *) fail 'trusted Node.js interpreter resolved outside the fixed system path' ;;
  esac
  [[ -f "$TRUSTED_NODE" && -x "$TRUSTED_NODE" ]] ||
    fail 'trusted Node.js interpreter must be an executable file'
fi

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
validate_single_line GALLR_REVIEWED_COMMIT "$REVIEWED_COMMIT"
validate_single_line GALLR_CHANGE_RECORD "$CHANGE_RECORD"
validate_single_line GALLR_PRODUCTION_EXECUTOR "$EXECUTOR"
validate_project_ref GALLR_EXPECTED_STAGING_PROJECT_REF "$STAGING_REF"
validate_project_ref GALLR_PRODUCTION_PROJECT_REF "$PRODUCTION_REF"
[[ "$STAGING_REF" != "$PRODUCTION_REF" ]] ||
  fail 'staging and production project references must be distinct'
if [[ "$GOVERNANCE_MODE" == 'separated_humans' ]]; then
  validate_single_line GALLR_PRODUCTION_CONFIRMATION "$PRODUCTION_CONFIRMATION"
  validate_single_line GALLR_PRODUCTION_APPROVER "$APPROVER"
  EXECUTOR_CANONICAL=$(printf '%s\n' "$EXECUTOR" | awk '{ print tolower($0) }')
  APPROVER_CANONICAL=$(printf '%s\n' "$APPROVER" | awk '{ print tolower($0) }')
  [[ "$EXECUTOR_CANONICAL" != "$APPROVER_CANONICAL" ]] ||
    fail 'production executor and independent approver must be different people'
  EXECUTOR_CANONICAL=''
  APPROVER_CANONICAL=''
fi
[[ "$REVIEWED_COMMIT" =~ ^[0-9a-f]{40}([0-9a-f]{24})?$ ]] ||
  fail 'GALLR_REVIEWED_COMMIT must be a full SHA-1 or SHA-256 commit ID'

if [[ "$GOVERNANCE_MODE" == 'separated_humans' ]]; then
  EXPECTED_CONFIRMATION="PRODUCTION ${PRODUCTION_REF} ${GATE} ${REVIEWED_COMMIT}"
  [[ "$PRODUCTION_CONFIRMATION" == "$EXPECTED_CONFIRMATION" ]] ||
    fail 'typed production confirmation does not exactly match target, gate, and commit'
fi
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
if [[ "$GOVERNANCE_MODE" == 'solo_operator' ]]; then
  POLICY_MTIME_EPOCH=$(mtime_of "$POLICY_PATH")
  [[ "$POLICY_MTIME_EPOCH" =~ ^[0-9]+$ ]] ||
    fail 'production policy modification time is invalid'
fi
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
EVIDENCE_DIR_SHA256=$(sha256_text "$EVIDENCE_DIR")

if [[ "$GOVERNANCE_MODE" == 'separated_humans' ]]; then
  APPROVER_SHA256=$(sha256_text "$APPROVER")
  require_exact_line_once "$MANIFEST_PATH" 'manifest_schema=1' 'operator manifest schema'
else
  require_exact_line_once "$MANIFEST_PATH" 'manifest_schema=2' 'solo operator manifest schema'
  require_exact_line_once "$MANIFEST_PATH" 'governance_mode=solo_operator' 'solo operator manifest governance mode'
  require_exact_line_once "$MANIFEST_PATH" 'human_reviewer_count=0' 'solo operator manifest human reviewer count'
  require_exact_line_once "$MANIFEST_PATH" 'automation_is_independent_human_review=false' 'solo operator manifest automation disclosure'
  require_exact_line_once "$MANIFEST_PATH" 'residual_risk_accepted=true' 'solo operator manifest residual-risk acceptance'
  require_exact_line_once "$MANIFEST_PATH" 'minimum_cooldown_seconds=900' 'solo operator staging cooldown'
  require_exact_line_once "$MANIFEST_PATH" 'destructive_actions=forbidden' 'solo operator manifest destructive-action boundary'
  require_exact_line_once "$MANIFEST_PATH" "change_record=${CHANGE_RECORD}" 'solo operator manifest change record'
  require_exact_line_once "$MANIFEST_PATH" "executor=${EXECUTOR}" 'solo operator manifest executor'
  require_exact_line_once "$MANIFEST_PATH" "reviewer=${EXECUTOR}" 'solo operator manifest reviewer disclosure'
  STAGING_INTENT_CONFIRMATION="INTENT STAGING ${STAGING_REF} NOT PRODUCTION ${PRODUCTION_REF} ${HEAD_COMMIT} ACCEPT_NO_INDEPENDENT_REVIEW"
  require_exact_line_once "$MANIFEST_PATH" \
    "first_confirmation_sha256=$(sha256_text "$STAGING_INTENT_CONFIRMATION")" \
    'solo operator manifest first confirmation'
  STAGING_INTENT_CONFIRMATION=''
fi
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
if [[ "$GOVERNANCE_MODE" == 'separated_humans' ]]; then
  require_exact_line_once "$POLICY_PATH" 'policy_schema=1' 'separated_humans mode requires a schema-1 production policy'
  [[ "$POLICY_NONEMPTY_LINES" == '12' && "$POLICY_TOTAL_LINES" == '12' ]] ||
    fail 'production policy must contain exactly the 12 documented nonempty lines'
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
else
  require_exact_line_once "$POLICY_PATH" 'policy_schema=2' 'solo_operator mode requires a schema-2 production policy'
  [[ "$POLICY_NONEMPTY_LINES" == '19' && "$POLICY_TOTAL_LINES" == '19' ]] ||
    fail 'solo production policy must contain exactly the 19 documented nonempty lines'
  require_exact_line_once "$POLICY_PATH" 'policy_kind=gallr_production_cutover' 'solo production policy kind'
  require_exact_line_once "$POLICY_PATH" 'governance_mode=solo_operator' 'solo production governance mode'
  require_exact_line_once "$POLICY_PATH" 'target=production' 'production policy target'
  require_exact_line_once "$POLICY_PATH" 'approval_status=self_attested' 'solo production attestation status'
  require_exact_line_once "$POLICY_PATH" "authorized_gate=${GATE}" 'authorized production gate'
  require_exact_line_once "$POLICY_PATH" "authorized_operation=${OPERATION}" 'authorized production operation'
  require_exact_line_once "$POLICY_PATH" 'destructive_actions=forbidden' 'solo production policy must forbid destructive actions'
  require_exact_line_once "$POLICY_PATH" 'minimum_cooldown_seconds=1800' 'solo production policy cooldown must be exactly 1800 seconds'
  require_exact_line_once "$POLICY_PATH" "production_project_ref_sha256=${PRODUCTION_REF_SHA256}" 'approved production target'
  require_exact_line_once "$POLICY_PATH" "staging_project_ref_sha256=${STAGING_REF_SHA256}" 'approved staging boundary'
  require_exact_line_once "$POLICY_PATH" "repository_commit=${HEAD_COMMIT}" 'approved repository commit'
  require_exact_line_once "$POLICY_PATH" "operator_manifest_sha256=${MANIFEST_SHA256}" 'approved operator manifest'
  require_exact_line_once "$POLICY_PATH" "change_record_sha256=${CHANGE_RECORD_SHA256}" 'approved change record'
  require_exact_line_once "$POLICY_PATH" "operator_identity_sha256=${EXECUTOR_SHA256}" 'approved solo operator'
  require_exact_line_once "$POLICY_PATH" "evidence_directory_sha256=${EVIDENCE_DIR_SHA256}" 'approved evidence directory'

  EXPECTED_INTENT_CONFIRMATION="INTENT PRODUCTION ${PRODUCTION_REF} NOT STAGING ${STAGING_REF} ${GATE} ${OPERATION} ${HEAD_COMMIT}"
  EXPECTED_FIRST_CONFIRMATION_SHA256=$(sha256_text "$EXPECTED_INTENT_CONFIRMATION")
  require_exact_line_once "$POLICY_PATH" \
    "first_confirmation_sha256=${EXPECTED_FIRST_CONFIRMATION_SHA256}" \
    'first solo confirmation'
  EXPECTED_INTENT_CONFIRMATION=''
  EXPECTED_FIRST_CONFIRMATION_SHA256=''

  POLICY_ISSUED_AT=$(single_value_of "$POLICY_PATH" 'issued_at_utc')
  POLICY_VALID_UNTIL=$(single_value_of "$POLICY_PATH" 'valid_until_utc')
  [[ -n "$POLICY_ISSUED_AT" && -n "$POLICY_VALID_UNTIL" ]] ||
    fail 'solo production policy timestamps are missing or duplicated'
  if ! validate_solo_policy_window \
    "$POLICY_ISSUED_AT" "$POLICY_VALID_UNTIL" "$POLICY_MTIME_EPOCH"
  then
    fail 'solo production policy timestamps or cooldown are invalid'
  fi
  POLICY_ISSUED_AT=''
  POLICY_VALID_UNTIL=''

  [[ -t 0 ]] ||
    fail 'solo production execution confirmation requires an interactive terminal'
  printf 'Type the solo-operator production execution confirmation, then press Return: ' >&2
  if ! IFS= read -r PRODUCTION_CONFIRMATION; then
    fail 'solo production execution confirmation was not provided'
  fi
  validate_single_line GALLR_PRODUCTION_CONFIRMATION "$PRODUCTION_CONFIRMATION"
  EXPECTED_CONFIRMATION="EXECUTE PRODUCTION ${PRODUCTION_REF} NOT STAGING ${STAGING_REF} ${GATE} ${OPERATION} ${REVIEWED_COMMIT}"
  [[ "$PRODUCTION_CONFIRMATION" == "$EXPECTED_CONFIRMATION" ]] ||
    fail 'typed solo production confirmation does not exactly match production target, staging exclusion, gate, operation, and commit'
  PRODUCTION_CONFIRMATION=''
  EXPECTED_CONFIRMATION=''
fi

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
if [[ "$GOVERNANCE_MODE" == 'solo_operator' ]]; then
  [[ "$(mtime_of "$POLICY_PATH")" == "$POLICY_MTIME_EPOCH" ]] ||
    fail 'production policy modification time changed during target attestation'
fi
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

if [[ "$GOVERNANCE_MODE" == 'solo_operator' ]]; then
  printf 'PASS: exact production target attested for %s; no remote contact performed; legacy retirement is not authorized\n' "$GATE"
else
  # Keep the schema-1/default output byte-compatible with the original guard.
  printf 'PASS: exact production target attested for %s; no remote contact performed\n' "$GATE"
fi
