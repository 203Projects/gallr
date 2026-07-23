#!/usr/bin/env bash

# Shared fail-closed guards for the staging catalog fixture lifecycle.
# This file is sourced by provision.sh and cleanup.sh; it does not connect by
# itself and deliberately never prints the database URL.

if [[ $- == *a* ]]; then
  set +a
fi

FIXTURE_COUNT=1205
FIXTURE_FEATURED_COUNT=5
FIXTURE_HOMEPAGE_COUNT=4

fixture_die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

fixture_require_command() {
  command -v "$1" >/dev/null 2>&1 || fixture_die "required command not found: $1"
}

fixture_validate_single_line() {
  local name="$1"
  local value="$2"

  [[ -n "$value" ]] || fixture_die "$name is required"
  [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] ||
    fixture_die "$name must be a single-line value"
}

fixture_clear_libpq_environment() {
  unset PGAPPNAME PGCHANNELBINDING PGCLIENTENCODING PGCONNECT_TIMEOUT
  unset PGDATABASE PGDATESTYLE PGGSSENCMODE PGGSSLIB PGHOST PGHOSTADDR
  unset PGKRBSRVNAME PGLOADBALANCEHOSTS PGOPTIONS PGPASSFILE PGPASSWORD PGPORT
  unset PGREQUIREAUTH PGREQUIREPEER PGSERVICE PGSERVICEFILE PGSSLCERT PGSSLCRL
  unset PGREQUIRESSL PGSSLCERTMODE PGSSLCRLDIR PGSSLKEY
  unset PGSSLMAXPROTOCOLVERSION PGSSLMINPROTOCOLVERSION
  unset PGSSLMODE PGSSLNEGOTIATION PGSSLROOTCERT PGTARGETSESSIONATTRS
  unset PGTCP_USER_TIMEOUT PGTZ PGUSER
}

fixture_sha256_string() {
  local value="$1"
  local digest

  if command -v shasum >/dev/null 2>&1; then
    digest=$(printf '%s' "$value" | shasum -a 256 | awk '{print $1}')
  elif command -v sha256sum >/dev/null 2>&1; then
    digest=$(printf '%s' "$value" | sha256sum | awk '{print $1}')
  elif command -v openssl >/dev/null 2>&1; then
    digest=$(printf '%s' "$value" | openssl dgst -sha256 | awk '{print $NF}')
  else
    fixture_die "shasum, sha256sum, or openssl is required to fingerprint project refs"
  fi

  [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || fixture_die "could not fingerprint project ref"
  printf '%s\n' "$digest"
}

fixture_mode() {
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

  fixture_die "could not inspect permissions for $target"
}

fixture_nlink() {
  local target="$1"
  local links

  if links=$(stat -f '%l' "$target" 2>/dev/null); then
    printf '%s\n' "$links"
    return
  fi

  if links=$(stat -c '%h' "$target" 2>/dev/null); then
    printf '%s\n' "$links"
    return
  fi

  fixture_die "could not inspect link count for $target"
}

fixture_assert_evidence_file() {
  local target="$1"
  local description="$2"
  local allowed_modes="$3"
  local mode

  [[ -f "$target" && ! -L "$target" ]] ||
    fixture_die "$description is missing or is not a regular file"
  [[ -O "$target" ]] || fixture_die "$description must be owned by the current user"
  [[ "$(fixture_nlink "$target")" == "1" ]] ||
    fixture_die "$description must have exactly one hard link"
  mode=$(fixture_mode "$target")
  case ",$allowed_modes," in
    *,"$mode",*) ;;
    *) fixture_die "$description has unsafe permissions (expected $allowed_modes)" ;;
  esac
}

fixture_validate_environment() {
  local action="$1"

  : "${GALLR_EXPECTED_STAGING_PROJECT_REF:?GALLR_EXPECTED_STAGING_PROJECT_REF is required}"
  : "${GALLR_PRODUCTION_PROJECT_REF:?GALLR_PRODUCTION_PROJECT_REF is required}"
  : "${GALLR_STAGING_DATABASE_URL:?GALLR_STAGING_DATABASE_URL is required}"
  : "${GALLR_FIXTURE_RUN_ID:?GALLR_FIXTURE_RUN_ID is required}"
  : "${GALLR_STAGING_REHEARSAL_CONFIRM:?GALLR_STAGING_REHEARSAL_CONFIRM is required}"
  : "${GALLR_STAGING_EVIDENCE_DIR:?GALLR_STAGING_EVIDENCE_DIR is required}"
  : "${GALLR_STAGING_IDENTITY_POLICY_PATH:?GALLR_STAGING_IDENTITY_POLICY_PATH is required}"

  # Retain these values in the current shell but never let unrelated child
  # processes inherit them. Exact validators/guards receive scoped assignments.
  export -n GALLR_EXPECTED_STAGING_PROJECT_REF GALLR_PRODUCTION_PROJECT_REF
  export -n GALLR_STAGING_DATABASE_URL GALLR_STAGING_REHEARSAL_CONFIRM
  export -n GALLR_STAGING_EVIDENCE_DIR GALLR_STAGING_IDENTITY_POLICY_PATH
  unset DATABASE_URL GALLR_SERVICE_ROLE_KEY
  unset GALLR_VALIDATION_PROJECT_REF GALLR_VALIDATION_DATABASE_URL
  unset GALLR_VALIDATION_REQUIRE_DIRECT GALLR_VALIDATION_SSLROOTCERT_SHA256
  unset GALLR_PSQL_APPNAME GALLR_PSQL_CONNECT_TIMEOUT GALLR_PSQL_OPTIONS
  unset GALLR_VALIDATED_PSQL_PATH GALLR_VALIDATED_PSQL_SHA256
  unset SUPABASE_ACCESS_TOKEN SUPABASE_URL SUPABASE_ANON_KEY
  unset SUPABASE_SERVICE_ROLE_KEY SUPABASE_SECRET_KEY

  fixture_validate_single_line GALLR_STAGING_DATABASE_URL "$GALLR_STAGING_DATABASE_URL"
  fixture_validate_single_line GALLR_STAGING_EVIDENCE_DIR "$GALLR_STAGING_EVIDENCE_DIR"
  fixture_validate_single_line \
    GALLR_STAGING_IDENTITY_POLICY_PATH \
    "$GALLR_STAGING_IDENTITY_POLICY_PATH"

  FIXTURE_SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
  FIXTURE_STAGING_REHEARSAL_DIR=$(cd "$FIXTURE_SCRIPT_DIR/.." && pwd -P)
  FIXTURE_REPO_ROOT=$(cd "$FIXTURE_SCRIPT_DIR/../../.." && pwd -P)
  FIXTURE_TARGET_VALIDATOR="$FIXTURE_STAGING_REHEARSAL_DIR/lib/validate-database-target.mjs"
  FIXTURE_PSQL_RUNNER="$FIXTURE_STAGING_REHEARSAL_DIR/lib/run-psql-with-validated-target.mjs"
  FIXTURE_TOOLCHAIN_HELPER="$FIXTURE_STAGING_REHEARSAL_DIR/lib/reviewed-toolchain.sh"
  FIXTURE_LINKED_STAGING_GUARD="$FIXTURE_STAGING_REHEARSAL_DIR/assert-linked-staging.sh"
  FIXTURE_TARGET_IDENTITY_GUARD="$FIXTURE_STAGING_REHEARSAL_DIR/assert-disposable-clone-target.sh"
  [[ -f "$FIXTURE_TARGET_VALIDATOR" ]] ||
    fixture_die "shared database target validator is missing"
  [[ -f "$FIXTURE_PSQL_RUNNER" && ! -L "$FIXTURE_PSQL_RUNNER" ]] ||
    fixture_die "shared validated psql runner is missing or is a symbolic link"
  [[ -f "$FIXTURE_TOOLCHAIN_HELPER" && ! -L "$FIXTURE_TOOLCHAIN_HELPER" ]] ||
    fixture_die "reviewed toolchain helper is missing or is a symbolic link"
  [[ -f "$FIXTURE_LINKED_STAGING_GUARD" ]] ||
    fixture_die "shared linked-staging guard is missing"
  [[ -f "$FIXTURE_TARGET_IDENTITY_GUARD" && ! -L "$FIXTURE_TARGET_IDENTITY_GUARD" ]] ||
    fixture_die "disposable-clone target guard is missing or is a symbolic link"

  [[ "$GALLR_EXPECTED_STAGING_PROJECT_REF" =~ ^[a-z0-9]{20}$ ]] ||
    fixture_die "GALLR_EXPECTED_STAGING_PROJECT_REF must be exactly 20 lowercase alphanumeric characters"
  [[ "$GALLR_PRODUCTION_PROJECT_REF" =~ ^[a-z0-9]{20}$ ]] ||
    fixture_die "GALLR_PRODUCTION_PROJECT_REF has an invalid format"
  [[ "$GALLR_EXPECTED_STAGING_PROJECT_REF" != "$GALLR_PRODUCTION_PROJECT_REF" ]] ||
    fixture_die "staging and production project refs must differ"

  [[ "$GALLR_FIXTURE_RUN_ID" =~ ^[a-z0-9][a-z0-9-]{7,31}$ ]] ||
    fixture_die "GALLR_FIXTURE_RUN_ID must be 8-32 lowercase ASCII letters, digits, or hyphens"
  [[ "$GALLR_FIXTURE_RUN_ID" != *"$GALLR_EXPECTED_STAGING_PROJECT_REF"* ]] ||
    fixture_die "GALLR_FIXTURE_RUN_ID must not contain a project ref"
  [[ "$GALLR_FIXTURE_RUN_ID" != *"$GALLR_PRODUCTION_PROJECT_REF"* ]] ||
    fixture_die "GALLR_FIXTURE_RUN_ID must not contain a project ref"
  [[ "$GALLR_STAGING_REHEARSAL_CONFIRM" == "$GALLR_EXPECTED_STAGING_PROJECT_REF" ]] ||
    fixture_die "$action confirmation must exactly equal the expected staging project ref"

  GALLR_FIXTURE_CONNECT_TIMEOUT_SECONDS=${GALLR_FIXTURE_CONNECT_TIMEOUT_SECONDS:-15}
  [[ "$GALLR_FIXTURE_CONNECT_TIMEOUT_SECONDS" =~ ^[0-9]+$ ]] ||
    fixture_die "GALLR_FIXTURE_CONNECT_TIMEOUT_SECONDS must be an integer"
  (( GALLR_FIXTURE_CONNECT_TIMEOUT_SECONDS >= 5 &&
     GALLR_FIXTURE_CONNECT_TIMEOUT_SECONDS <= 60 )) ||
    fixture_die "GALLR_FIXTURE_CONNECT_TIMEOUT_SECONDS must be between 5 and 60"

  [[ "$GALLR_STAGING_EVIDENCE_DIR" == /* &&
     -d "$GALLR_STAGING_EVIDENCE_DIR" &&
     ! -L "$GALLR_STAGING_EVIDENCE_DIR" ]] ||
    fixture_die "GALLR_STAGING_EVIDENCE_DIR must be an existing absolute directory"
  FIXTURE_EVIDENCE_ROOT=$(cd "$GALLR_STAGING_EVIDENCE_DIR" && pwd -P)
  [[ -O "$FIXTURE_EVIDENCE_ROOT" ]] ||
    fixture_die "evidence directory must be owned by the current user"
  [[ "$(fixture_mode "$FIXTURE_EVIDENCE_ROOT")" == "700" ]] ||
    fixture_die "evidence directory must have mode 0700"
  case "$FIXTURE_EVIDENCE_ROOT/" in
    "$FIXTURE_REPO_ROOT"/*)
      fixture_die "evidence must be stored outside the repository"
      ;;
  esac
  FIXTURE_OPERATOR_MANIFEST_PATH="$FIXTURE_EVIDENCE_ROOT/operator-manifest.txt"
  # shellcheck source=../lib/reviewed-toolchain.sh
  source "$FIXTURE_TOOLCHAIN_HELPER"
  gallr_read_reviewed_toolchain "$FIXTURE_OPERATOR_MANIFEST_PATH" ||
    fixture_die "reviewed Node.js/psql toolchain does not match the preflight manifest"

  # Bind the operator-supplied refs to preflight's sealed manifest, the clean
  # reviewed commit, and the repository's linked Supabase project before any
  # fixture connection. Do not expose the credential-bearing URI to the guard.
  if ! (
    fixture_clear_libpq_environment
    BASH_ENV=/dev/null ENV=/dev/null \
    GALLR_EXPECTED_STAGING_PROJECT_REF="$GALLR_EXPECTED_STAGING_PROJECT_REF" \
    GALLR_PRODUCTION_PROJECT_REF="$GALLR_PRODUCTION_PROJECT_REF" \
    GALLR_STAGING_REHEARSAL_CONFIRM="$GALLR_STAGING_REHEARSAL_CONFIRM" \
    GALLR_STAGING_EVIDENCE_DIR="$FIXTURE_EVIDENCE_ROOT" \
      /bin/bash "$FIXTURE_LINKED_STAGING_GUARD"
  ); then
    fixture_die "linked staging target did not match the reviewed preflight manifest"
  fi

  gallr_run_reviewed_node \
    "GALLR_VALIDATION_PROJECT_REF=$GALLR_EXPECTED_STAGING_PROJECT_REF" \
    "GALLR_VALIDATION_DATABASE_URL=$GALLR_STAGING_DATABASE_URL" \
    GALLR_VALIDATION_REQUIRE_DIRECT=true \
    -- "$FIXTURE_TARGET_VALIDATOR" ||
    fixture_die "database URL target validation failed"

  unset FIXTURE_STAGING_REF_RAW FIXTURE_PRODUCTION_REF_RAW
  unset FIXTURE_DATABASE_URL FIXTURE_IDENTITY_POLICY_PATH
  FIXTURE_STAGING_REF_RAW="$GALLR_EXPECTED_STAGING_PROJECT_REF"
  FIXTURE_PRODUCTION_REF_RAW="$GALLR_PRODUCTION_PROJECT_REF"
  FIXTURE_DATABASE_URL="$GALLR_STAGING_DATABASE_URL"
  FIXTURE_IDENTITY_POLICY_PATH="$GALLR_STAGING_IDENTITY_POLICY_PATH"
  export -n FIXTURE_STAGING_REF_RAW FIXTURE_PRODUCTION_REF_RAW
  export -n FIXTURE_DATABASE_URL FIXTURE_IDENTITY_POLICY_PATH

  # The launcher retains unexported target inputs only long enough to derive the
  # discrete psql environment. Do not pass raw refs, the confirmation, or the
  # original credential variable onward.
  unset GALLR_EXPECTED_STAGING_PROJECT_REF
  unset GALLR_PRODUCTION_PROJECT_REF
  unset GALLR_STAGING_REHEARSAL_CONFIRM
  unset GALLR_STAGING_DATABASE_URL
  unset GALLR_STAGING_IDENTITY_POLICY_PATH

  FIXTURE_STAGING_REF_FINGERPRINT=$(
    fixture_sha256_string "$FIXTURE_STAGING_REF_RAW"
  )
  FIXTURE_PRODUCTION_REF_FINGERPRINT=$(
    fixture_sha256_string "$FIXTURE_PRODUCTION_REF_RAW"
  )

  FIXTURE_PREFIX="gallr-rehearsal-${GALLR_FIXTURE_RUN_ID}-"
  FIXTURE_LOAD_EVENT_ID="${FIXTURE_PREFIX}event.catalog.v2,(load):한글"
  FIXTURE_EMPTY_EVENT_ID="${FIXTURE_PREFIX}event.catalog.v2,(empty):한글"
  FIXTURE_EDITOR_ID="${FIXTURE_PREFIX}editor.special,(guest):한글"
  FIXTURE_BOUNDARY_ID="${FIXTURE_PREFIX}catalog-0500.cursor,(reserved):한글"
  FIXTURE_MUTATION_ID="${FIXTURE_PREFIX}catalog-0750.mutate,(same-id):한글"
  FIXTURE_MEDIA_OBJECT_PATH="staging-rehearsal/${FIXTURE_PREFIX}cover-0005.webp"

  [[ ${#FIXTURE_BOUNDARY_ID} -le 128 ]] ||
    fixture_die "derived fixture ID exceeds the canonical 128-character limit"

  umask 077
  FIXTURE_RUN_DIR="$FIXTURE_EVIDENCE_ROOT/fixtures-$GALLR_FIXTURE_RUN_ID"
  FIXTURE_PSQL_RAW_OUTPUT="$FIXTURE_RUN_DIR/.psql-stderr.$$"
  FIXTURE_PSQL_RAW_OUTPUT_CREATED=false
  export FIXTURE_SCRIPT_DIR FIXTURE_REPO_ROOT FIXTURE_PREFIX
  export FIXTURE_LOAD_EVENT_ID FIXTURE_EMPTY_EVENT_ID FIXTURE_EDITOR_ID
  export FIXTURE_BOUNDARY_ID FIXTURE_MUTATION_ID FIXTURE_MEDIA_OBJECT_PATH
  export FIXTURE_EVIDENCE_ROOT FIXTURE_RUN_DIR
  export FIXTURE_STAGING_REF_FINGERPRINT FIXTURE_PRODUCTION_REF_FINGERPRINT
  export FIXTURE_PSQL_RUNNER
  export FIXTURE_TOOLCHAIN_HELPER FIXTURE_OPERATOR_MANIFEST_PATH
  export FIXTURE_LINKED_STAGING_GUARD FIXTURE_TARGET_IDENTITY_GUARD
  export GALLR_FIXTURE_CONNECT_TIMEOUT_SECONDS
}

fixture_cleanup_psql_raw_output() {
  if [[ "${FIXTURE_PSQL_RAW_OUTPUT_CREATED:-false}" == true ]]; then
    [[ -f "$FIXTURE_PSQL_RAW_OUTPUT" \
       && ! -L "$FIXTURE_PSQL_RAW_OUTPUT" \
       && -O "$FIXTURE_PSQL_RAW_OUTPUT" ]] || return 1
    rm -f -- "$FIXTURE_PSQL_RAW_OUTPUT" || return 1
    FIXTURE_PSQL_RAW_OUTPUT_CREATED=false
  fi
}

fixture_on_exit_cleanup() {
  local status=$?
  trap - EXIT HUP INT QUIT TERM
  if ! fixture_cleanup_psql_raw_output; then
    printf 'ERROR: could not remove fixture psql scratch output\n' >&2
    status=1
  fi
  exit "$status"
}

fixture_assert_disposable_clone_target() {
  local evidence_path="$1"
  local status

  [[ "$evidence_path" == "$FIXTURE_EVIDENCE_ROOT/"* ]] ||
    fixture_die "target-identity evidence must be inside the external evidence root"
  [[ ! -e "$evidence_path" && ! -L "$evidence_path" ]] ||
    fixture_die "refusing to overwrite target-identity evidence"

  umask 077
  (set -o noclobber; : > "$evidence_path") ||
    fixture_die "could not exclusively create target-identity evidence"
  chmod 600 "$evidence_path" ||
    fixture_die "could not protect target-identity evidence"

  if gallr_run_clean_bash \
    "GALLR_EXPECTED_STAGING_PROJECT_REF=$FIXTURE_STAGING_REF_RAW" \
    "GALLR_PRODUCTION_PROJECT_REF=$FIXTURE_PRODUCTION_REF_RAW" \
    "GALLR_STAGING_DATABASE_URL=$FIXTURE_DATABASE_URL" \
    "GALLR_STAGING_REHEARSAL_CONFIRM=$FIXTURE_STAGING_REF_RAW" \
    "GALLR_STAGING_EVIDENCE_DIR=$FIXTURE_EVIDENCE_ROOT" \
    "GALLR_STAGING_IDENTITY_POLICY_PATH=$FIXTURE_IDENTITY_POLICY_PATH" \
    -- "$FIXTURE_TARGET_IDENTITY_GUARD" > "$evidence_path" 2>&1; then
    status=0
  else
    status=$?
  fi

  chmod 400 "$evidence_path" ||
    fixture_die "could not seal target-identity evidence"
  [[ "$status" -eq 0 ]] ||
    fixture_die "disposable-clone target identity failed; inspect $evidence_path"
  [[ "$(grep -Fxc \
    'PASS: independent policy and disposable-clone marker identify staging' \
    "$evidence_path")" == "1" ]] ||
    fixture_die "target-identity evidence is missing its exact pass record"
}

fixture_redact_psql_stderr() {
  local line

  while IFS= read -r line || [[ -n "$line" ]]; do
    if [[ "$line" == *"postgresql://"* || "$line" == *"postgres://"* ]]; then
      printf '%s\n' '<psql connection detail redacted>' >&2
      continue
    fi
    line=${line//"$FIXTURE_STAGING_REF_RAW"/'<staging-ref>'}
    line=${line//"$FIXTURE_PRODUCTION_REF_RAW"/'<production-ref>'}
    printf '%s\n' "$line" >&2
  done
}

fixture_psql() {
  local status
  local redact_status

  # The shared launcher revalidates and splits the URI, removes inherited
  # libpq routing, and supplies a private ephemeral passfile to psql.
  fixture_clear_libpq_environment
  [[ ! -e "$FIXTURE_PSQL_RAW_OUTPUT" && ! -L "$FIXTURE_PSQL_RAW_OUTPUT" ]] ||
    fixture_die "refusing to overwrite fixture psql scratch output"
  (set -o noclobber; : >"$FIXTURE_PSQL_RAW_OUTPUT") ||
    fixture_die "could not exclusively create fixture psql scratch output"
  FIXTURE_PSQL_RAW_OUTPUT_CREATED=true
  chmod 0600 "$FIXTURE_PSQL_RAW_OUTPUT" ||
    fixture_die "could not protect fixture psql scratch output"

  if gallr_run_reviewed_node \
      "GALLR_VALIDATION_PROJECT_REF=$FIXTURE_STAGING_REF_RAW" \
      "GALLR_VALIDATION_DATABASE_URL=$FIXTURE_DATABASE_URL" \
      GALLR_VALIDATION_REQUIRE_DIRECT=true \
      "GALLR_PSQL_CONNECT_TIMEOUT=$GALLR_FIXTURE_CONNECT_TIMEOUT_SECONDS" \
      GALLR_PSQL_OPTIONS= \
      "GALLR_PSQL_APPNAME=gallr-staging-fixture-${GALLR_FIXTURE_RUN_ID}" \
      "GALLR_VALIDATED_PSQL_PATH=$GALLR_REVIEWED_PSQL_PATH" \
      "GALLR_VALIDATED_PSQL_SHA256=$GALLR_REVIEWED_PSQL_SHA256" \
      -- "$FIXTURE_PSQL_RUNNER" -- \
        --set=ON_ERROR_STOP=1 "$@" 2>"$FIXTURE_PSQL_RAW_OUTPUT"; then
    status=0
  else
    status=$?
  fi
  if fixture_redact_psql_stderr <"$FIXTURE_PSQL_RAW_OUTPUT"; then
    redact_status=0
  else
    redact_status=$?
  fi
  fixture_cleanup_psql_raw_output ||
    fixture_die "could not remove fixture psql scratch output"
  fixture_clear_libpq_environment
  [[ "$redact_status" -eq 0 ]] ||
    fixture_die "could not redact fixture psql stderr"
  return "$status"
}

fixture_validate_hash() {
  [[ "$1" =~ ^[0-9a-f]{64}$ ]] || fixture_die "invalid SHA-256 value in baseline evidence"
}

fixture_read_baseline() {
  local baseline_path="$1"
  local trailing

  fixture_assert_evidence_file "$baseline_path" "baseline evidence" "400,600"

  IFS=$'\t' read -r \
    BASELINE_CANONICAL_COUNT \
    BASELINE_CANONICAL_ID_HASH \
    BASELINE_CANONICAL_CATALOG_HASH \
    BASELINE_VERSION_COUNT \
    BASELINE_VERSION_CATALOG_HASH \
    BASELINE_V2_COUNT \
    BASELINE_V2_ID_HASH \
    BASELINE_V2_CATALOG_HASH \
    BASELINE_LEGACY_COUNT \
    BASELINE_LEGACY_ID_HASH \
    BASELINE_LEGACY_CATALOG_HASH \
    BASELINE_EVENT_COUNT \
    BASELINE_EVENT_CATALOG_HASH \
    BASELINE_EDITOR_COUNT \
    BASELINE_EDITOR_CATALOG_HASH \
    BASELINE_MEDIA_COUNT \
    BASELINE_MEDIA_CATALOG_HASH \
    BASELINE_ATTACHMENT_COUNT \
    BASELINE_ATTACHMENT_CATALOG_HASH \
    BASELINE_CURATION_COUNT \
    BASELINE_CURATION_CATALOG_HASH \
    BASELINE_SUBMISSION_COUNT \
    BASELINE_SUBMISSION_CATALOG_HASH \
    BASELINE_SUBMISSION_MEDIA_COUNT \
    BASELINE_SUBMISSION_MEDIA_CATALOG_HASH \
    BASELINE_IMPORT_ROW_COUNT \
    BASELINE_IMPORT_ROW_CATALOG_HASH \
    BASELINE_IMPORT_LINK_COUNT \
    BASELINE_IMPORT_LINK_CATALOG_HASH \
    BASELINE_AUDIT_COUNT \
    BASELINE_CAPTURED_AT \
    trailing < "$baseline_path"

  [[ -z "${trailing:-}" ]] || fixture_die "baseline evidence has unexpected fields"
  for count_value in \
    "$BASELINE_CANONICAL_COUNT" "$BASELINE_VERSION_COUNT" \
    "$BASELINE_V2_COUNT" "$BASELINE_LEGACY_COUNT" \
    "$BASELINE_EVENT_COUNT" "$BASELINE_EDITOR_COUNT" \
    "$BASELINE_MEDIA_COUNT" "$BASELINE_ATTACHMENT_COUNT" \
    "$BASELINE_CURATION_COUNT" "$BASELINE_SUBMISSION_COUNT" \
    "$BASELINE_SUBMISSION_MEDIA_COUNT" "$BASELINE_IMPORT_ROW_COUNT" \
    "$BASELINE_IMPORT_LINK_COUNT" "$BASELINE_AUDIT_COUNT"; do
    [[ "$count_value" =~ ^[0-9]+$ ]] || fixture_die "invalid count in baseline evidence"
  done
  fixture_validate_hash "$BASELINE_CANONICAL_ID_HASH"
  fixture_validate_hash "$BASELINE_CANONICAL_CATALOG_HASH"
  fixture_validate_hash "$BASELINE_VERSION_CATALOG_HASH"
  fixture_validate_hash "$BASELINE_V2_ID_HASH"
  fixture_validate_hash "$BASELINE_V2_CATALOG_HASH"
  fixture_validate_hash "$BASELINE_LEGACY_ID_HASH"
  fixture_validate_hash "$BASELINE_LEGACY_CATALOG_HASH"
  fixture_validate_hash "$BASELINE_EVENT_CATALOG_HASH"
  fixture_validate_hash "$BASELINE_EDITOR_CATALOG_HASH"
  fixture_validate_hash "$BASELINE_MEDIA_CATALOG_HASH"
  fixture_validate_hash "$BASELINE_ATTACHMENT_CATALOG_HASH"
  fixture_validate_hash "$BASELINE_CURATION_CATALOG_HASH"
  fixture_validate_hash "$BASELINE_SUBMISSION_CATALOG_HASH"
  fixture_validate_hash "$BASELINE_SUBMISSION_MEDIA_CATALOG_HASH"
  fixture_validate_hash "$BASELINE_IMPORT_ROW_CATALOG_HASH"
  fixture_validate_hash "$BASELINE_IMPORT_LINK_CATALOG_HASH"
  [[ -n "$BASELINE_CAPTURED_AT" ]] || fixture_die "baseline timestamp is missing"

  export BASELINE_CANONICAL_COUNT BASELINE_CANONICAL_ID_HASH
  export BASELINE_CANONICAL_CATALOG_HASH BASELINE_VERSION_COUNT
  export BASELINE_VERSION_CATALOG_HASH BASELINE_V2_COUNT BASELINE_V2_ID_HASH
  export BASELINE_V2_CATALOG_HASH BASELINE_LEGACY_COUNT BASELINE_LEGACY_ID_HASH
  export BASELINE_LEGACY_CATALOG_HASH
  export BASELINE_EVENT_COUNT BASELINE_EVENT_CATALOG_HASH
  export BASELINE_EDITOR_COUNT BASELINE_EDITOR_CATALOG_HASH
  export BASELINE_MEDIA_COUNT BASELINE_MEDIA_CATALOG_HASH
  export BASELINE_ATTACHMENT_COUNT BASELINE_ATTACHMENT_CATALOG_HASH
  export BASELINE_CURATION_COUNT BASELINE_CURATION_CATALOG_HASH
  export BASELINE_SUBMISSION_COUNT BASELINE_SUBMISSION_CATALOG_HASH
  export BASELINE_SUBMISSION_MEDIA_COUNT BASELINE_SUBMISSION_MEDIA_CATALOG_HASH
  export BASELINE_IMPORT_ROW_COUNT BASELINE_IMPORT_ROW_CATALOG_HASH
  export BASELINE_IMPORT_LINK_COUNT BASELINE_IMPORT_LINK_CATALOG_HASH
  export BASELINE_AUDIT_COUNT
  export BASELINE_CAPTURED_AT
}

fixture_assert_single_json_line() {
  local evidence_path="$1"
  local line_count
  local payload

  [[ -s "$evidence_path" ]] || fixture_die "database evidence is empty"
  line_count=$(wc -l < "$evidence_path" | tr -d ' ')
  [[ "$line_count" == "1" ]] || fixture_die "database evidence must contain exactly one JSON line"
  IFS= read -r payload < "$evidence_path"
  [[ "$payload" == \{*\} ]] || fixture_die "database evidence is not a JSON object"
  gallr_run_reviewed_node -- -e '
    const fs = require("node:fs");
    const value = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
    if (value === null || Array.isArray(value) || typeof value !== "object") process.exit(1);
  ' "$evidence_path" >/dev/null 2>&1 || fixture_die "database evidence is invalid JSON"
}

fixture_read_identity() {
  local identity_path="$1"
  local allowed_modes="$2"
  local identity_prefix
  local identity_production
  local identity_run
  local identity_staging
  local line_count
  local identity_extra

  fixture_assert_evidence_file "$identity_path" "fixture identity evidence" "$allowed_modes"
  line_count=$(wc -l < "$identity_path" | tr -d ' ')
  [[ "$line_count" == "1" ]] ||
    fixture_die "fixture identity evidence must contain exactly one line"
  IFS=$'\t' read -r \
    identity_run identity_prefix identity_staging identity_production identity_extra \
    < "$identity_path"
  [[ -z "${identity_extra:-}" ]] ||
    fixture_die "fixture identity evidence has unexpected fields"
  [[ "$identity_run" == "$GALLR_FIXTURE_RUN_ID" ]] ||
    fixture_die "fixture identity run ID mismatch"
  [[ "$identity_prefix" == "$FIXTURE_PREFIX" ]] ||
    fixture_die "fixture identity prefix mismatch"
  [[ "$identity_staging" == "$FIXTURE_STAGING_REF_FINGERPRINT" ]] ||
    fixture_die "fixture identity staging ref fingerprint mismatch"
  [[ "$identity_production" == "$FIXTURE_PRODUCTION_REF_FINGERPRINT" ]] ||
    fixture_die "fixture identity production ref fingerprint mismatch"
}

fixture_validate_provisioned_evidence() {
  local evidence_path="$1"
  local allowed_modes="$2"
  local hashes
  local extra

  fixture_assert_evidence_file "$evidence_path" "provision database evidence" "$allowed_modes"
  fixture_assert_single_json_line "$evidence_path"
  hashes=$(
    gallr_run_reviewed_node \
      "FIXTURE_EXPECTED_COUNT=$FIXTURE_COUNT" \
      "FIXTURE_EXPECTED_FEATURED_COUNT=$FIXTURE_FEATURED_COUNT" \
      "FIXTURE_EXPECTED_HOMEPAGE_COUNT=$FIXTURE_HOMEPAGE_COUNT" \
      "FIXTURE_EXPECTED_PREFIX=$FIXTURE_PREFIX" \
      "FIXTURE_EXPECTED_LOAD_EVENT_ID=$FIXTURE_LOAD_EVENT_ID" \
      "FIXTURE_EXPECTED_EMPTY_EVENT_ID=$FIXTURE_EMPTY_EVENT_ID" \
      "FIXTURE_EXPECTED_EDITOR_ID=$FIXTURE_EDITOR_ID" \
      "FIXTURE_EXPECTED_BOUNDARY_ID=$FIXTURE_BOUNDARY_ID" \
      "FIXTURE_EXPECTED_MUTATION_ID=$FIXTURE_MUTATION_ID" \
      "FIXTURE_EXPECTED_MEDIA_OBJECT_PATH=$FIXTURE_MEDIA_OBJECT_PATH" \
      -- - "$evidence_path" <<'NODE'
const fs = require("node:fs");
const evidence = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
const expected = process.env;
const fail = (message) => {
  process.stderr.write(`provision evidence validation failed: ${message}\n`);
  process.exit(1);
};
const fixtureCount = Number(expected.FIXTURE_EXPECTED_COUNT);
if (evidence.state !== "provisioned") fail("state");
if (evidence.fixture_count !== fixtureCount) fail("fixture count");
if (evidence.featured_count !== Number(expected.FIXTURE_EXPECTED_FEATURED_COUNT)) fail("featured count");
if (evidence.homepage_count !== Number(expected.FIXTURE_EXPECTED_HOMEPAGE_COUNT)) fail("homepage count");
if (evidence.load_event_id !== expected.FIXTURE_EXPECTED_LOAD_EVENT_ID) fail("load event");
if (evidence.empty_event_id !== expected.FIXTURE_EXPECTED_EMPTY_EVENT_ID) fail("empty event");
if (evidence.editor_id !== expected.FIXTURE_EXPECTED_EDITOR_ID) fail("editor");
if (evidence.boundary_cursor_id !== expected.FIXTURE_EXPECTED_BOUNDARY_ID) fail("boundary cursor");
if (evidence.mutation_target_id !== expected.FIXTURE_EXPECTED_MUTATION_ID) fail("mutation target");
if (evidence.media_object_path !== expected.FIXTURE_EXPECTED_MEDIA_OBJECT_PATH) fail("media object path");
if (evidence.storage_bytes_created !== false) fail("storage byte assertion");
if (typeof evidence.captured_at !== "string" || evidence.captured_at.length === 0) fail("capture timestamp");
for (const field of ["runtime", "event_integrity", "featured_integrity", "empty_integrity", "reconciliation"]) {
  if (!evidence[field] || Array.isArray(evidence[field]) || typeof evidence[field] !== "object") fail(field);
}
const hashFields = [
  "fixture_version_id_checksum_sha256",
  "fixture_media_id_checksum_sha256",
  "fixture_attachment_id_checksum_sha256",
  "fixture_curation_id_checksum_sha256",
];
const hashes = hashFields.map((field) => {
  const value = evidence[field];
  if (typeof value !== "string" || !/^[0-9a-f]{64}$/.test(value)) fail(field);
  return value;
});
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const validateArray = (field, length, predicate) => {
  const values = evidence[field];
  if (!Array.isArray(values) || values.length !== length) fail(field);
  if (new Set(values).size !== values.length || !values.every(predicate)) fail(field);
  return values;
};
const exhibitionIds = validateArray(
  "fixture_exhibition_ids",
  fixtureCount,
  (value) => typeof value === "string" && value.startsWith(expected.FIXTURE_EXPECTED_PREFIX),
);
if (!exhibitionIds.includes(expected.FIXTURE_EXPECTED_BOUNDARY_ID)) fail("boundary cursor membership");
if (!exhibitionIds.includes(expected.FIXTURE_EXPECTED_MUTATION_ID)) fail("mutation target membership");
validateArray("fixture_version_ids", fixtureCount, (value) => typeof value === "string" && uuid.test(value));
validateArray("fixture_media_asset_ids", 1, (value) => typeof value === "string" && uuid.test(value));
validateArray("fixture_curation_ids", 2, (value) => typeof value === "string" && uuid.test(value));
process.stdout.write(hashes.join("\t"));
NODE
  ) || fixture_die "provision database evidence is invalid"

  IFS=$'\t' read -r \
    FIXTURE_VERSION_ID_HASH \
    FIXTURE_MEDIA_ID_HASH \
    FIXTURE_ATTACHMENT_ID_HASH \
    FIXTURE_CURATION_ID_HASH \
    extra <<< "$hashes"
  [[ -z "${extra:-}" ]] ||
    fixture_die "provision evidence hash output has unexpected fields"
  fixture_validate_hash "$FIXTURE_VERSION_ID_HASH"
  fixture_validate_hash "$FIXTURE_MEDIA_ID_HASH"
  fixture_validate_hash "$FIXTURE_ATTACHMENT_ID_HASH"
  fixture_validate_hash "$FIXTURE_CURATION_ID_HASH"
  export FIXTURE_VERSION_ID_HASH FIXTURE_MEDIA_ID_HASH
  export FIXTURE_ATTACHMENT_ID_HASH FIXTURE_CURATION_ID_HASH
}

fixture_build_manifest() {
  local manifest_path="$1"
  local provisioned_path="$2"
  local provisioned_json

  [[ ! -e "$manifest_path" && ! -L "$manifest_path" ]] ||
    fixture_die "refusing to overwrite manifest evidence"
  IFS= read -r provisioned_json < "$provisioned_path"
  (set -o noclobber; : > "$manifest_path") ||
    fixture_die "could not exclusively create manifest evidence"
  chmod 600 "$manifest_path" || fixture_die "could not protect manifest evidence"
  printf '%s' \
    "{\"schema_version\":1,\"state\":\"provisioned\",\"run_id\":\"$GALLR_FIXTURE_RUN_ID\"," \
    "\"fixture_prefix\":\"$FIXTURE_PREFIX\",\"fixture_count\":$FIXTURE_COUNT," \
    "\"mutation_target_id\":\"$FIXTURE_MUTATION_ID\"," \
    "\"staging_ref_sha256\":\"$FIXTURE_STAGING_REF_FINGERPRINT\"," \
    "\"production_ref_sha256\":\"$FIXTURE_PRODUCTION_REF_FINGERPRINT\"," \
    "\"baseline\":{\"captured_at\":\"$BASELINE_CAPTURED_AT\"," \
    "\"canonical_count\":$BASELINE_CANONICAL_COUNT," \
    "\"canonical_id_checksum_sha256\":\"$BASELINE_CANONICAL_ID_HASH\"," \
    "\"canonical_catalog_checksum_sha256\":\"$BASELINE_CANONICAL_CATALOG_HASH\"," \
    "\"version_count\":$BASELINE_VERSION_COUNT," \
    "\"version_catalog_checksum_sha256\":\"$BASELINE_VERSION_CATALOG_HASH\"," \
    "\"v2_count\":$BASELINE_V2_COUNT," \
    "\"v2_id_checksum_sha256\":\"$BASELINE_V2_ID_HASH\"," \
    "\"v2_catalog_checksum_sha256\":\"$BASELINE_V2_CATALOG_HASH\"," \
    "\"legacy_count\":$BASELINE_LEGACY_COUNT," \
    "\"legacy_id_checksum_sha256\":\"$BASELINE_LEGACY_ID_HASH\"," \
    "\"legacy_catalog_checksum_sha256\":\"$BASELINE_LEGACY_CATALOG_HASH\"," \
    "\"event_count\":$BASELINE_EVENT_COUNT," \
    "\"event_catalog_checksum_sha256\":\"$BASELINE_EVENT_CATALOG_HASH\"," \
    "\"editor_count\":$BASELINE_EDITOR_COUNT," \
    "\"editor_catalog_checksum_sha256\":\"$BASELINE_EDITOR_CATALOG_HASH\"," \
    "\"media_count\":$BASELINE_MEDIA_COUNT," \
    "\"media_catalog_checksum_sha256\":\"$BASELINE_MEDIA_CATALOG_HASH\"," \
    "\"attachment_count\":$BASELINE_ATTACHMENT_COUNT," \
    "\"attachment_catalog_checksum_sha256\":\"$BASELINE_ATTACHMENT_CATALOG_HASH\"," \
    "\"curation_count\":$BASELINE_CURATION_COUNT," \
    "\"curation_catalog_checksum_sha256\":\"$BASELINE_CURATION_CATALOG_HASH\"," \
    "\"submission_count\":$BASELINE_SUBMISSION_COUNT," \
    "\"submission_catalog_checksum_sha256\":\"$BASELINE_SUBMISSION_CATALOG_HASH\"," \
    "\"submission_media_count\":$BASELINE_SUBMISSION_MEDIA_COUNT," \
    "\"submission_media_catalog_checksum_sha256\":\"$BASELINE_SUBMISSION_MEDIA_CATALOG_HASH\"," \
    "\"import_row_count\":$BASELINE_IMPORT_ROW_COUNT," \
    "\"import_row_catalog_checksum_sha256\":\"$BASELINE_IMPORT_ROW_CATALOG_HASH\"," \
    "\"import_link_count\":$BASELINE_IMPORT_LINK_COUNT," \
    "\"import_link_catalog_checksum_sha256\":\"$BASELINE_IMPORT_LINK_CATALOG_HASH\"," \
    "\"audit_count\":$BASELINE_AUDIT_COUNT}," \
    "\"database_evidence\":$provisioned_json}" > "$manifest_path"
  printf '\n' >> "$manifest_path"
}

fixture_validate_manifest() {
  local manifest_path="$1"
  local provisioned_path="$2"
  local allowed_modes="$3"
  local baseline_path="$4"
  local hashes
  local extra

  fixture_assert_evidence_file "$manifest_path" "provisioned manifest" "$allowed_modes"
  fixture_assert_single_json_line "$manifest_path"
  hashes=$(
    gallr_run_reviewed_node \
      "MANIFEST_EXPECTED_RUN_ID=$GALLR_FIXTURE_RUN_ID" \
      "MANIFEST_EXPECTED_PREFIX=$FIXTURE_PREFIX" \
      "MANIFEST_EXPECTED_MUTATION_ID=$FIXTURE_MUTATION_ID" \
      "MANIFEST_EXPECTED_STAGING_SHA256=$FIXTURE_STAGING_REF_FINGERPRINT" \
      "MANIFEST_EXPECTED_PRODUCTION_SHA256=$FIXTURE_PRODUCTION_REF_FINGERPRINT" \
      -- - \
      "$manifest_path" "$provisioned_path" "$baseline_path" <<'NODE'
const fs = require("node:fs");
const { isDeepStrictEqual } = require("node:util");
const manifest = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
const provisioned = JSON.parse(fs.readFileSync(process.argv[3], "utf8"));
const baselineText = fs.readFileSync(process.argv[4], "utf8");
const expected = process.env;
const fail = (message) => {
  process.stderr.write(`manifest validation failed: ${message}\n`);
  process.exit(1);
};
if (!baselineText.endsWith("\n") || baselineText.trimEnd().includes("\n")) fail("baseline line count");
const fields = baselineText.trimEnd().split("\t");
if (fields.length !== 31) fail("baseline field count");
const integer = (index) => {
  if (!/^(0|[1-9][0-9]*)$/.test(fields[index])) fail(`baseline integer ${index}`);
  return Number(fields[index]);
};
const text = (index) => fields[index];
const baseline = {
  captured_at: text(30),
  canonical_count: integer(0),
  canonical_id_checksum_sha256: text(1),
  canonical_catalog_checksum_sha256: text(2),
  version_count: integer(3),
  version_catalog_checksum_sha256: text(4),
  v2_count: integer(5),
  v2_id_checksum_sha256: text(6),
  v2_catalog_checksum_sha256: text(7),
  legacy_count: integer(8),
  legacy_id_checksum_sha256: text(9),
  legacy_catalog_checksum_sha256: text(10),
  event_count: integer(11),
  event_catalog_checksum_sha256: text(12),
  editor_count: integer(13),
  editor_catalog_checksum_sha256: text(14),
  media_count: integer(15),
  media_catalog_checksum_sha256: text(16),
  attachment_count: integer(17),
  attachment_catalog_checksum_sha256: text(18),
  curation_count: integer(19),
  curation_catalog_checksum_sha256: text(20),
  submission_count: integer(21),
  submission_catalog_checksum_sha256: text(22),
  submission_media_count: integer(23),
  submission_media_catalog_checksum_sha256: text(24),
  import_row_count: integer(25),
  import_row_catalog_checksum_sha256: text(26),
  import_link_count: integer(27),
  import_link_catalog_checksum_sha256: text(28),
  audit_count: integer(29),
};
if (manifest.schema_version !== 1 || manifest.state !== "provisioned") fail("state");
if (manifest.run_id !== expected.MANIFEST_EXPECTED_RUN_ID) fail("run ID");
if (manifest.fixture_prefix !== expected.MANIFEST_EXPECTED_PREFIX) fail("prefix");
if (manifest.fixture_count !== 1205) fail("fixture count");
if (manifest.mutation_target_id !== expected.MANIFEST_EXPECTED_MUTATION_ID) fail("mutation target");
if (manifest.staging_ref_sha256 !== expected.MANIFEST_EXPECTED_STAGING_SHA256) fail("staging fingerprint");
if (manifest.production_ref_sha256 !== expected.MANIFEST_EXPECTED_PRODUCTION_SHA256) fail("production fingerprint");
if (!isDeepStrictEqual(manifest.baseline, baseline)) fail("baseline");
if (!isDeepStrictEqual(manifest.database_evidence, provisioned)) fail("database evidence");
const hashFields = [
  "fixture_version_id_checksum_sha256",
  "fixture_media_id_checksum_sha256",
  "fixture_attachment_id_checksum_sha256",
  "fixture_curation_id_checksum_sha256",
];
const hashes = hashFields.map((field) => {
  const value = provisioned[field];
  if (typeof value !== "string" || !/^[0-9a-f]{64}$/.test(value)) fail(field);
  return value;
});
process.stdout.write(hashes.join("\t"));
NODE
  ) || fixture_die "provisioned manifest is invalid"

  IFS=$'\t' read -r \
    FIXTURE_VERSION_ID_HASH \
    FIXTURE_MEDIA_ID_HASH \
    FIXTURE_ATTACHMENT_ID_HASH \
    FIXTURE_CURATION_ID_HASH \
    extra <<< "$hashes"
  [[ -z "${extra:-}" ]] || fixture_die "manifest hash evidence has unexpected fields"
  fixture_validate_hash "$FIXTURE_VERSION_ID_HASH"
  fixture_validate_hash "$FIXTURE_MEDIA_ID_HASH"
  fixture_validate_hash "$FIXTURE_ATTACHMENT_ID_HASH"
  fixture_validate_hash "$FIXTURE_CURATION_ID_HASH"
  export FIXTURE_VERSION_ID_HASH FIXTURE_MEDIA_ID_HASH
  export FIXTURE_ATTACHMENT_ID_HASH FIXTURE_CURATION_ID_HASH
}

fixture_validate_cleaned_evidence() {
  local evidence_path="$1"
  local allowed_modes="$2"

  fixture_assert_evidence_file "$evidence_path" "cleanup database evidence" "$allowed_modes"
  fixture_assert_single_json_line "$evidence_path"
  CLEANED_AUDIT_COUNT=$(
    gallr_run_reviewed_node -- - "$evidence_path" <<'NODE'
const fs = require("node:fs");
const evidence = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
const fail = (message) => {
  process.stderr.write(`cleanup evidence validation failed: ${message}\n`);
  process.exit(1);
};
if (evidence.state !== "cleaned" || evidence.fixture_count !== 1205) fail("state or count");
if (evidence.baseline_restored !== true) fail("baseline assertion");
if (typeof evidence.captured_at !== "string" || evidence.captured_at.length === 0) fail("capture timestamp");
if (!Number.isSafeInteger(evidence.audit_rows_retained) || evidence.audit_rows_retained < 0) fail("audit count");
const expectedDeleted = {
  canonical_exhibitions: 1205,
  curation_placements: 2,
  editors: 1,
  events: 2,
  exhibition_versions: 1205,
  media_assets: 1,
  publication_pointers: 1205,
  version_media: 1,
};
if (!evidence.deleted || Array.isArray(evidence.deleted) || typeof evidence.deleted !== "object") fail("deleted counts");
if (Object.keys(evidence.deleted).length !== Object.keys(expectedDeleted).length) fail("deleted count inventory");
for (const [key, value] of Object.entries(expectedDeleted)) {
  if (evidence.deleted[key] !== value) fail(`deleted count ${key}`);
}
for (const field of ["v2_integrity", "legacy_integrity", "reconciliation"]) {
  if (!evidence[field] || Array.isArray(evidence[field]) || typeof evidence[field] !== "object") fail(field);
}
process.stdout.write(String(evidence.audit_rows_retained));
NODE
  ) || fixture_die "cleanup database evidence is invalid"
  [[ "$CLEANED_AUDIT_COUNT" =~ ^[0-9]+$ ]] || fixture_die "cleanup audit evidence is invalid"
  export CLEANED_AUDIT_COUNT
}

fixture_assert_exact_baseline_restored() {
  local baseline_path="$1"
  local current_path="$2"
  local cleaned_path="$3"

  fixture_assert_evidence_file "$current_path" "baseline restoration verification" "600"
  gallr_run_reviewed_node -- - \
    "$baseline_path" "$current_path" "$cleaned_path" <<'NODE'
const fs = require("node:fs");
const fail = (message) => {
  process.stderr.write(`baseline restoration verification failed: ${message}\n`);
  process.exit(1);
};
const parse = (path) => {
  const raw = fs.readFileSync(path, "utf8");
  if (!raw.endsWith("\n") || raw.trimEnd().includes("\n")) fail("line count");
  const fields = raw.trimEnd().split("\t");
  if (fields.length !== 31) fail("field count");
  return fields;
};
const baseline = parse(process.argv[2]);
const current = parse(process.argv[3]);
for (let index = 0; index <= 29; index += 1) {
  if (current[index] !== baseline[index]) fail(`field ${index + 1} drift`);
}
const cleaned = JSON.parse(fs.readFileSync(process.argv[4], "utf8"));
if (String(cleaned.audit_rows_retained) !== current[29]) fail("cleanup audit count drift");
NODE
}
