#!/usr/bin/env bash

# Shared, non-connecting guards for the staging queued-writer rehearsal.
# Sourcing this file does not access a database or create evidence.

if [[ $- == *a* ]]; then
  set +a
fi

concurrency_die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

concurrency_require_command() {
  command -v "$1" >/dev/null 2>&1 ||
    concurrency_die "required command not found: $1"
}

concurrency_validate_single_line() {
  local name="$1"
  local value="$2"

  [[ -n "$value" ]] || concurrency_die "$name is required"
  [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] ||
    concurrency_die "$name must be a single-line value"
}

concurrency_mode() {
  local target="$1"
  local mode

  if mode=$(stat -f '%Lp' "$target" 2>/dev/null); then
    printf '%s\n' "$mode"
    return
  fi
  if mode=$(stat -c '%a' "$target" 2>/dev/null); then
    printf '%s\n' "$mode"
    return
  fi
  concurrency_die "could not inspect permissions for $target"
}

concurrency_sha256_string() {
  local value="$1"
  local digest

  if command -v shasum >/dev/null 2>&1; then
    digest=$(printf '%s' "$value" | shasum -a 256 | awk '{print $1}')
  elif command -v sha256sum >/dev/null 2>&1; then
    digest=$(printf '%s' "$value" | sha256sum | awk '{print $1}')
  elif command -v openssl >/dev/null 2>&1; then
    digest=$(printf '%s' "$value" | openssl dgst -sha256 | awk '{print $NF}')
  else
    concurrency_die "shasum, sha256sum, or openssl is required"
  fi

  [[ "$digest" =~ ^[0-9a-f]{64}$ ]] ||
    concurrency_die "could not fingerprint a project reference"
  printf '%s\n' "$digest"
}

concurrency_sha256_file() {
  local path="$1"
  local digest

  if command -v shasum >/dev/null 2>&1; then
    digest=$(shasum -a 256 "$path" | awk '{print $1}')
  elif command -v sha256sum >/dev/null 2>&1; then
    digest=$(sha256sum "$path" | awk '{print $1}')
  elif command -v openssl >/dev/null 2>&1; then
    digest=$(openssl dgst -sha256 "$path" | awk '{print $NF}')
  else
    concurrency_die "shasum, sha256sum, or openssl is required"
  fi

  [[ "$digest" =~ ^[0-9a-f]{64}$ ]] ||
    concurrency_die "could not hash $path"
  printf '%s\n' "$digest"
}

concurrency_sanitize_git_environment() {
  unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_OBJECT_DIRECTORY
  unset GIT_ALTERNATE_OBJECT_DIRECTORIES GIT_COMMON_DIR GIT_CEILING_DIRECTORIES
  unset GIT_DISCOVERY_ACROSS_FILESYSTEM GIT_CONFIG GIT_CONFIG_PARAMETERS
  unset GIT_CONFIG_SYSTEM
  export GIT_CONFIG_COUNT=0 GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
  export GIT_OPTIONAL_LOCKS=0
}

concurrency_safe_git() {
  command git \
    -c core.fsmonitor=false \
    -c core.hooksPath=/dev/null \
    -c core.excludesFile=/dev/null \
    "$@"
}

concurrency_validate_environment() {
  : "${GALLR_EXPECTED_STAGING_PROJECT_REF:?GALLR_EXPECTED_STAGING_PROJECT_REF is required}"
  : "${GALLR_PRODUCTION_PROJECT_REF:?GALLR_PRODUCTION_PROJECT_REF is required}"
  : "${GALLR_STAGING_DATABASE_URL:?GALLR_STAGING_DATABASE_URL is required}"
  : "${GALLR_STAGING_REHEARSAL_CONFIRM:?GALLR_STAGING_REHEARSAL_CONFIRM is required}"
  : "${GALLR_STAGING_IDENTITY_POLICY_PATH:?GALLR_STAGING_IDENTITY_POLICY_PATH is required}"
  : "${GALLR_CONCURRENCY_EVIDENCE_DIR:?GALLR_CONCURRENCY_EVIDENCE_DIR is required}"
  : "${GALLR_CONCURRENCY_RUN_ID:?GALLR_CONCURRENCY_RUN_ID is required}"
  : "${GALLR_CONCURRENCY_APPROVAL_REASON:?GALLR_CONCURRENCY_APPROVAL_REASON is required}"
  : "${GALLR_CONCURRENCY_TARGET_EXHIBITION_ID:?GALLR_CONCURRENCY_TARGET_EXHIBITION_ID is required}"

  # Keep caller inputs available to this coordinator without exporting raw
  # target labels, the database URL, or policy path to unrelated utilities.
  export -n GALLR_EXPECTED_STAGING_PROJECT_REF GALLR_PRODUCTION_PROJECT_REF
  export -n GALLR_STAGING_DATABASE_URL GALLR_STAGING_REHEARSAL_CONFIRM
  export -n GALLR_STAGING_IDENTITY_POLICY_PATH
  export -n GALLR_CONCURRENCY_EVIDENCE_DIR GALLR_CONCURRENCY_RUN_ID
  export -n GALLR_CONCURRENCY_APPROVAL_REASON
  export -n GALLR_CONCURRENCY_TARGET_EXHIBITION_ID
  unset DATABASE_URL GALLR_SERVICE_ROLE_KEY
  unset GALLR_VALIDATION_PROJECT_REF GALLR_VALIDATION_DATABASE_URL
  unset GALLR_VALIDATION_REQUIRE_DIRECT GALLR_VALIDATION_SSLROOTCERT_SHA256
  unset GALLR_PSQL_APPNAME GALLR_PSQL_CONNECT_TIMEOUT GALLR_PSQL_OPTIONS
  unset GALLR_VALIDATED_PSQL_PATH GALLR_VALIDATED_PSQL_SHA256
  unset SUPABASE_ACCESS_TOKEN SUPABASE_URL SUPABASE_ANON_KEY
  unset SUPABASE_SERVICE_ROLE_KEY SUPABASE_SECRET_KEY
  concurrency_sanitize_git_environment

  concurrency_validate_single_line \
    GALLR_EXPECTED_STAGING_PROJECT_REF \
    "$GALLR_EXPECTED_STAGING_PROJECT_REF"
  concurrency_validate_single_line \
    GALLR_PRODUCTION_PROJECT_REF \
    "$GALLR_PRODUCTION_PROJECT_REF"
  concurrency_validate_single_line \
    GALLR_STAGING_DATABASE_URL \
    "$GALLR_STAGING_DATABASE_URL"
  concurrency_validate_single_line \
    GALLR_STAGING_REHEARSAL_CONFIRM \
    "$GALLR_STAGING_REHEARSAL_CONFIRM"
  concurrency_validate_single_line \
    GALLR_STAGING_IDENTITY_POLICY_PATH \
    "$GALLR_STAGING_IDENTITY_POLICY_PATH"
  concurrency_validate_single_line \
    GALLR_CONCURRENCY_EVIDENCE_DIR \
    "$GALLR_CONCURRENCY_EVIDENCE_DIR"
  concurrency_validate_single_line \
    GALLR_CONCURRENCY_RUN_ID \
    "$GALLR_CONCURRENCY_RUN_ID"
  concurrency_validate_single_line \
    GALLR_CONCURRENCY_APPROVAL_REASON \
    "$GALLR_CONCURRENCY_APPROVAL_REASON"
  concurrency_validate_single_line \
    GALLR_CONCURRENCY_TARGET_EXHIBITION_ID \
    "$GALLR_CONCURRENCY_TARGET_EXHIBITION_ID"

  [[ "$GALLR_EXPECTED_STAGING_PROJECT_REF" =~ ^[a-z0-9]{20}$ ]] ||
    concurrency_die "GALLR_EXPECTED_STAGING_PROJECT_REF must be exactly 20 lowercase alphanumeric characters"
  [[ "$GALLR_PRODUCTION_PROJECT_REF" =~ ^[a-z0-9]{20}$ ]] ||
    concurrency_die "GALLR_PRODUCTION_PROJECT_REF must be exactly 20 lowercase alphanumeric characters"
  [[ "$GALLR_EXPECTED_STAGING_PROJECT_REF" != "$GALLR_PRODUCTION_PROJECT_REF" ]] ||
    concurrency_die "staging and production project refs must differ"
  [[ "$GALLR_STAGING_REHEARSAL_CONFIRM" == "$GALLR_EXPECTED_STAGING_PROJECT_REF" ]] ||
    concurrency_die "GALLR_STAGING_REHEARSAL_CONFIRM must exactly equal the staging project ref"

  [[ "$GALLR_CONCURRENCY_RUN_ID" =~ ^[a-z0-9][a-z0-9-]{7,31}$ ]] ||
    concurrency_die "GALLR_CONCURRENCY_RUN_ID must be 8-32 lowercase letters, digits, or hyphens"
  [[ "$GALLR_CONCURRENCY_APPROVAL_REASON" =~ ^[A-Za-z0-9][A-Za-z0-9\ .,:_/-]{9,159}$ ]] ||
    concurrency_die "GALLR_CONCURRENCY_APPROVAL_REASON must be 10-160 strict ASCII characters"
  [[ "$GALLR_CONCURRENCY_APPROVAL_REASON" == *"$GALLR_CONCURRENCY_RUN_ID"* ]] ||
    concurrency_die "approval reason must contain the exact run ID"
  [[ "$GALLR_CONCURRENCY_TARGET_EXHIBITION_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._,:/-]{0,127}$ ]] ||
    concurrency_die "target exhibition ID must be a 1-128 character ASCII identifier"

  GALLR_CONCURRENCY_WAIT_TIMEOUT_SECONDS=${GALLR_CONCURRENCY_WAIT_TIMEOUT_SECONDS:-30}
  GALLR_CONCURRENCY_CONNECT_TIMEOUT_SECONDS=${GALLR_CONCURRENCY_CONNECT_TIMEOUT_SECONDS:-15}
  [[ "$GALLR_CONCURRENCY_WAIT_TIMEOUT_SECONDS" =~ ^[0-9]+$ ]] ||
    concurrency_die "wait timeout must be an integer"
  [[ "$GALLR_CONCURRENCY_CONNECT_TIMEOUT_SECONDS" =~ ^[0-9]+$ ]] ||
    concurrency_die "connect timeout must be an integer"
  (( GALLR_CONCURRENCY_WAIT_TIMEOUT_SECONDS >= 5 && GALLR_CONCURRENCY_WAIT_TIMEOUT_SECONDS <= 120 )) ||
    concurrency_die "wait timeout must be between 5 and 120 seconds"
  (( GALLR_CONCURRENCY_CONNECT_TIMEOUT_SECONDS >= 5 && GALLR_CONCURRENCY_CONNECT_TIMEOUT_SECONDS <= 60 )) ||
    concurrency_die "connect timeout must be between 5 and 60 seconds"

  [[ "$GALLR_CONCURRENCY_EVIDENCE_DIR" == /* ]] ||
    concurrency_die "GALLR_CONCURRENCY_EVIDENCE_DIR must be an absolute path"
  [[ -d "$GALLR_CONCURRENCY_EVIDENCE_DIR" ]] ||
    concurrency_die "evidence root must already exist"
  [[ ! -L "$GALLR_CONCURRENCY_EVIDENCE_DIR" ]] ||
    concurrency_die "evidence root must not be a symbolic link"
  [[ -O "$GALLR_CONCURRENCY_EVIDENCE_DIR" ]] ||
    concurrency_die "evidence root must be owned by the current user"
  [[ "$(concurrency_mode "$GALLR_CONCURRENCY_EVIDENCE_DIR")" == "700" ]] ||
    concurrency_die "evidence root must have mode 0700"

  CONCURRENCY_SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
  CONCURRENCY_STAGING_REHEARSAL_DIR=$(cd "$CONCURRENCY_SCRIPT_DIR/.." && pwd -P)
  CONCURRENCY_REPO_ROOT=$(cd "$CONCURRENCY_SCRIPT_DIR/../../.." && pwd -P)
  CONCURRENCY_EVIDENCE_ROOT=$(cd "$GALLR_CONCURRENCY_EVIDENCE_DIR" && pwd -P)
  case "$CONCURRENCY_EVIDENCE_ROOT/" in
    "$CONCURRENCY_REPO_ROOT"/*)
      concurrency_die "evidence root must be outside the repository"
      ;;
  esac

  concurrency_require_command git
  concurrency_require_command awk
  concurrency_require_command grep
  concurrency_require_command sed
  concurrency_require_command stat

  CONCURRENCY_DATABASE_VALIDATOR="$CONCURRENCY_STAGING_REHEARSAL_DIR/lib/validate-database-target.mjs"
  CONCURRENCY_PSQL_RUNNER="$CONCURRENCY_STAGING_REHEARSAL_DIR/lib/run-psql-with-validated-target.mjs"
  CONCURRENCY_TOOLCHAIN_HELPER="$CONCURRENCY_STAGING_REHEARSAL_DIR/lib/reviewed-toolchain.sh"
  CONCURRENCY_LINKED_STAGING_GUARD="$CONCURRENCY_STAGING_REHEARSAL_DIR/assert-linked-staging.sh"
  CONCURRENCY_TARGET_IDENTITY_GUARD="$CONCURRENCY_STAGING_REHEARSAL_DIR/assert-disposable-clone-target.sh"
  [[ -f "$CONCURRENCY_DATABASE_VALIDATOR" ]] ||
    concurrency_die "shared database-target validator is missing"
  [[ -f "$CONCURRENCY_PSQL_RUNNER" && ! -L "$CONCURRENCY_PSQL_RUNNER" ]] ||
    concurrency_die "shared validated psql runner is missing or is a symbolic link"
  [[ -f "$CONCURRENCY_TOOLCHAIN_HELPER" && ! -L "$CONCURRENCY_TOOLCHAIN_HELPER" ]] ||
    concurrency_die "reviewed toolchain helper is missing or is a symbolic link"
  [[ -f "$CONCURRENCY_LINKED_STAGING_GUARD" ]] ||
    concurrency_die "shared linked-staging guard is missing"
  [[ -f "$CONCURRENCY_TARGET_IDENTITY_GUARD" && ! -L "$CONCURRENCY_TARGET_IDENTITY_GUARD" ]] ||
    concurrency_die "disposable-clone target guard is missing or is a symbolic link"
  CONCURRENCY_STAGING_REF_SHA256=$(concurrency_sha256_string "$GALLR_EXPECTED_STAGING_PROJECT_REF")
  CONCURRENCY_PRODUCTION_REF_SHA256=$(concurrency_sha256_string "$GALLR_PRODUCTION_PROJECT_REF")
  CONCURRENCY_BRIDGE_MIGRATION_RELATIVE="supabase/migrations/20260721120000_public_exhibition_catalog_v2.sql"
  CONCURRENCY_BRIDGE_MIGRATION_PATH="$CONCURRENCY_REPO_ROOT/$CONCURRENCY_BRIDGE_MIGRATION_RELATIVE"
  [[ -f "$CONCURRENCY_BRIDGE_MIGRATION_PATH" ]] ||
    concurrency_die "reviewed bridge migration is missing"
  CONCURRENCY_OPERATOR_MANIFEST_PATH="$CONCURRENCY_EVIDENCE_ROOT/operator-manifest.txt"
  [[ -f "$CONCURRENCY_OPERATOR_MANIFEST_PATH" && ! -L "$CONCURRENCY_OPERATOR_MANIFEST_PATH" ]] ||
    concurrency_die "operator manifest is missing or is a symbolic link"
  [[ -O "$CONCURRENCY_OPERATOR_MANIFEST_PATH" ]] ||
    concurrency_die "operator manifest must be owned by the current user"
  [[ "$(concurrency_mode "$CONCURRENCY_OPERATOR_MANIFEST_PATH")" == "444" ]] ||
    concurrency_die "operator manifest must retain preflight mode 0444"
  # shellcheck source=../lib/reviewed-toolchain.sh
  source "$CONCURRENCY_TOOLCHAIN_HELPER"
  gallr_read_reviewed_toolchain "$CONCURRENCY_OPERATOR_MANIFEST_PATH" ||
    concurrency_die "reviewed Node.js/psql toolchain does not match the preflight manifest"
  if ! gallr_run_reviewed_node \
    "GALLR_VALIDATION_PROJECT_REF=$GALLR_EXPECTED_STAGING_PROJECT_REF" \
    "GALLR_VALIDATION_DATABASE_URL=$GALLR_STAGING_DATABASE_URL" \
    GALLR_VALIDATION_REQUIRE_DIRECT=true \
    -- "$CONCURRENCY_DATABASE_VALIDATOR"; then
    concurrency_die "database URL failed exact staging-target validation"
  fi
  CONCURRENCY_OPERATOR_MANIFEST_SHA256=$(
    concurrency_sha256_file "$CONCURRENCY_OPERATOR_MANIFEST_PATH"
  )
  if ! CONCURRENCY_MANIFEST_REPOSITORY_COMMIT=$(
    awk -F= '
      $1 == "repository_commit" {
        count += 1
        value = substr($0, index($0, "=") + 1)
      }
      END {
        if (count != 1) exit 1
        print value
      }
    ' "$CONCURRENCY_OPERATOR_MANIFEST_PATH"
  ); then
    concurrency_die "operator manifest must contain exactly one repository_commit"
  fi
  [[ "$CONCURRENCY_MANIFEST_REPOSITORY_COMMIT" =~ ^([0-9a-f]{40}|[0-9a-f]{64})$ ]] ||
    concurrency_die "operator manifest repository_commit is invalid"
  if ! CONCURRENCY_REPOSITORY_COMMIT=$(
    concurrency_safe_git -C "$CONCURRENCY_REPO_ROOT" rev-parse --verify 'HEAD^{commit}'
  ); then
    concurrency_die "could not resolve the reviewed repository commit"
  fi
  [[ "$CONCURRENCY_REPOSITORY_COMMIT" =~ ^([0-9a-f]{40}|[0-9a-f]{64})$ ]] ||
    concurrency_die "resolved repository commit is invalid"
  [[ "$CONCURRENCY_REPOSITORY_COMMIT" == "$CONCURRENCY_MANIFEST_REPOSITORY_COMMIT" ]] ||
    concurrency_die "repository commit does not match the operator manifest"
  CONCURRENCY_BRIDGE_MIGRATION_SHA256=$(
    concurrency_sha256_file "$CONCURRENCY_BRIDGE_MIGRATION_PATH"
  )
  CONCURRENCY_RUN_DIR="$CONCURRENCY_EVIDENCE_ROOT/$GALLR_CONCURRENCY_RUN_ID"
  CONCURRENCY_APP_CONTROL="gallr-stg-control-$GALLR_CONCURRENCY_RUN_ID"
  CONCURRENCY_APP_ACTIVATION="gallr-stg-activate-$GALLR_CONCURRENCY_RUN_ID"
  CONCURRENCY_APP_WRITER="gallr-stg-writer-$GALLR_CONCURRENCY_RUN_ID"

  export GALLR_CONCURRENCY_WAIT_TIMEOUT_SECONDS
  export GALLR_CONCURRENCY_CONNECT_TIMEOUT_SECONDS
  export CONCURRENCY_SCRIPT_DIR CONCURRENCY_STAGING_REHEARSAL_DIR
  export CONCURRENCY_REPO_ROOT
  export CONCURRENCY_DATABASE_VALIDATOR CONCURRENCY_PSQL_RUNNER
  export CONCURRENCY_LINKED_STAGING_GUARD
  export CONCURRENCY_TARGET_IDENTITY_GUARD
  export CONCURRENCY_OPERATOR_MANIFEST_PATH
  export CONCURRENCY_MANIFEST_REPOSITORY_COMMIT
  export CONCURRENCY_OPERATOR_MANIFEST_SHA256 CONCURRENCY_REPOSITORY_COMMIT
  export CONCURRENCY_BRIDGE_MIGRATION_SHA256
  export CONCURRENCY_EVIDENCE_ROOT CONCURRENCY_RUN_DIR
  export CONCURRENCY_STAGING_REF_SHA256 CONCURRENCY_PRODUCTION_REF_SHA256
  export CONCURRENCY_APP_CONTROL CONCURRENCY_APP_ACTIVATION
  export CONCURRENCY_APP_WRITER
}
