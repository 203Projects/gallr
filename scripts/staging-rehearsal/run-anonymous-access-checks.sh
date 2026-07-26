#!/usr/bin/env bash
set -euo pipefail

if [[ $- == *x* ]]; then
  set +x
fi
if [[ $- == *a* ]]; then
  set +a
fi

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_env() {
  local name="$1"
  [[ -n "${!name:-}" ]] || fail "${name} is required"
}

sha256_file() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    fail 'shasum or sha256sum is required'
  fi
}

sha256_text() {
  if command -v shasum >/dev/null 2>&1; then
    printf '%s' "$1" | shasum -a 256 | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then
    printf '%s' "$1" | sha256sum | awk '{print $1}'
  else
    fail 'shasum or sha256sum is required'
  fi
}

require_env GALLR_EXPECTED_STAGING_PROJECT_REF
require_env GALLR_PRODUCTION_PROJECT_REF
require_env GALLR_STAGING_DATABASE_URL
require_env GALLR_STAGING_REHEARSAL_CONFIRM
require_env GALLR_STAGING_EVIDENCE_DIR
require_env GALLR_STAGING_IDENTITY_POLICY_PATH

# Snapshot the direct database URL and all target inputs before the first child
# process. These copies are intentionally unexported; subprocesses receive only
# explicitly scoped values.
unset staging_ref production_ref staging_ref_raw production_ref_raw
unset staging_database_url staging_confirmation
unset evidence_dir_input staging_identity_policy_path
staging_ref="${GALLR_EXPECTED_STAGING_PROJECT_REF}"
production_ref="${GALLR_PRODUCTION_PROJECT_REF}"
staging_database_url="${GALLR_STAGING_DATABASE_URL}"
staging_confirmation="${GALLR_STAGING_REHEARSAL_CONFIRM}"
evidence_dir_input="${GALLR_STAGING_EVIDENCE_DIR}"
staging_identity_policy_path="${GALLR_STAGING_IDENTITY_POLICY_PATH}"
unset GALLR_EXPECTED_STAGING_PROJECT_REF GALLR_PRODUCTION_PROJECT_REF
unset GALLR_STAGING_DATABASE_URL DATABASE_URL
unset GALLR_STAGING_REHEARSAL_CONFIRM GALLR_STAGING_EVIDENCE_DIR
unset GALLR_STAGING_IDENTITY_POLICY_PATH
unset GALLR_VALIDATION_PROJECT_REF GALLR_VALIDATION_DATABASE_URL
unset GALLR_VALIDATION_REQUIRE_DIRECT GALLR_VALIDATION_SSLROOTCERT_SHA256
unset GALLR_PSQL_APPNAME GALLR_PSQL_CONNECT_TIMEOUT GALLR_PSQL_OPTIONS
unset GALLR_VALIDATED_PSQL_PATH GALLR_VALIDATED_PSQL_SHA256
unset SUPABASE_ACCESS_TOKEN SUPABASE_URL SUPABASE_ANON_KEY
unset SUPABASE_SERVICE_ROLE_KEY SUPABASE_SECRET_KEY
unset GALLR_SERVICE_ROLE_KEY

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
validator_path="${script_dir}/lib/validate-database-target.mjs"
psql_runner_path="${script_dir}/lib/run-psql-with-validated-target.mjs"
toolchain_helper_path="${script_dir}/lib/reviewed-toolchain.sh"
linked_guard_path="${script_dir}/assert-linked-staging.sh"
target_identity_guard_path="${script_dir}/assert-disposable-clone-target.sh"
[[ -f "${validator_path}" ]] || fail 'database target validator is missing'
[[ -f "${psql_runner_path}" && ! -L "${psql_runner_path}" ]] \
  || fail 'validated psql runner is missing or is a symbolic link'
[[ -f "${toolchain_helper_path}" && ! -L "${toolchain_helper_path}" ]] \
  || fail 'reviewed toolchain helper is missing or is a symbolic link'
[[ -x "${linked_guard_path}" ]] || fail 'linked staging guard is missing'
[[ -x "${target_identity_guard_path}" && ! -L "${target_identity_guard_path}" ]] \
  || fail 'disposable-clone target guard is missing or is a symbolic link'

project_ref_pattern='^[a-z0-9]{20}$'
[[ "${staging_ref}" =~ ${project_ref_pattern} ]] \
  || fail 'expected staging project ref must be 20 lowercase alphanumeric characters'
[[ "${production_ref}" =~ ${project_ref_pattern} ]] \
  || fail 'production project ref must be 20 lowercase alphanumeric characters'
[[ "${staging_ref}" != "${production_ref}" ]] \
  || fail 'staging and production project references must differ'
[[ "${staging_confirmation}" == "${staging_ref}" ]] \
  || fail 'confirmation must exactly match the expected staging project ref'
[[ "${evidence_dir_input}" = /* ]] \
  || fail 'evidence directory must be an absolute path'
[[ -d "${evidence_dir_input}" ]] \
  || fail 'evidence directory does not exist'
[[ ! -L "${evidence_dir_input}" ]] \
  || fail 'evidence directory must not be a symbolic link'

expected_repo_root="$(cd -- "${script_dir}/../.." && pwd -P)"
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_OBJECT_DIRECTORY
unset GIT_ALTERNATE_OBJECT_DIRECTORIES GIT_COMMON_DIR GIT_CEILING_DIRECTORIES
unset GIT_DISCOVERY_ACROSS_FILESYSTEM GIT_CONFIG GIT_CONFIG_PARAMETERS
unset GIT_CONFIG_SYSTEM
export GIT_CONFIG_COUNT=0 GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
export GIT_OPTIONAL_LOCKS=0
safe_git() {
  command git \
    -c core.fsmonitor=false \
    -c core.hooksPath=/dev/null \
    -c core.excludesFile=/dev/null \
    "$@"
}
repo_root="$(safe_git -C "${script_dir}" rev-parse --show-toplevel 2>/dev/null)" \
  || fail 'could not resolve the repository root'
repo_root="$(cd -- "${repo_root}" && pwd -P)"
[[ "${repo_root}" == "${expected_repo_root}" ]] \
  || fail 'Git repository root does not match the checked-in runner location'
evidence_dir="$(cd -- "${evidence_dir_input}" && pwd -P)"
case "${evidence_dir}" in
  "${repo_root}"|"${repo_root}/"*)
    fail 'evidence directory must be outside the repository'
    ;;
esac

if mode="$(stat -f '%Lp' "${evidence_dir_input}" 2>/dev/null)"; then
  :
elif mode="$(stat -c '%a' "${evidence_dir_input}" 2>/dev/null)"; then
  :
else
  fail 'could not inspect evidence directory permissions'
fi
[[ "${mode}" == '700' ]] || fail 'evidence directory permissions must be 0700'
[[ -O "${evidence_dir}" ]] || fail 'evidence directory must be owned by the current user'

operator_manifest_path="${evidence_dir}/operator-manifest.txt"
[[ -f "${operator_manifest_path}" && ! -L "${operator_manifest_path}" ]] \
  || fail 'operator manifest is missing or is a symbolic link'
# shellcheck source=lib/reviewed-toolchain.sh
source "${toolchain_helper_path}"
gallr_read_reviewed_toolchain "${operator_manifest_path}" \
  || fail 'reviewed Node.js/psql toolchain does not match the preflight manifest'
gallr_run_reviewed_node \
  "GALLR_VALIDATION_PROJECT_REF=${staging_ref}" \
  "GALLR_VALIDATION_DATABASE_URL=${staging_database_url}" \
  GALLR_VALIDATION_REQUIRE_DIRECT=true \
  -- "${validator_path}" \
  || fail 'database URL target validation failed'

linked_guard_output="$(
  BASH_ENV=/dev/null ENV=/dev/null \
  GALLR_EXPECTED_STAGING_PROJECT_REF="${staging_ref}" \
  GALLR_PRODUCTION_PROJECT_REF="${production_ref}" \
  GALLR_STAGING_REHEARSAL_CONFIRM="${staging_confirmation}" \
  GALLR_STAGING_EVIDENCE_DIR="${evidence_dir}" \
    "${linked_guard_path}"
)"
assert_target_identity_now() {
  gallr_run_clean_bash \
    "GALLR_EXPECTED_STAGING_PROJECT_REF=${staging_ref}" \
    "GALLR_PRODUCTION_PROJECT_REF=${production_ref}" \
    "GALLR_STAGING_DATABASE_URL=${staging_database_url}" \
    "GALLR_STAGING_REHEARSAL_CONFIRM=${staging_confirmation}" \
    "GALLR_STAGING_EVIDENCE_DIR=${evidence_dir}" \
    "GALLR_STAGING_IDENTITY_POLICY_PATH=${staging_identity_policy_path}" \
    -- "${target_identity_guard_path}" >/dev/null \
    || fail 'disposable-clone target identity failed'
}

assert_target_identity_now


sql_dir="${script_dir}/sql"
for sql_file in \
  anonymous-positive.sql \
  anonymous-private-read-must-fail.sql \
  anonymous-catalog-write-must-fail.sql; do
  [[ -f "${sql_dir}/${sql_file}" ]] || fail "missing SQL file: ${sql_file}"
done

umask 077

evidence_paths=(
  "${evidence_dir}/anonymous-positive.txt"
  "${evidence_dir}/anonymous-private-read-denied.txt"
  "${evidence_dir}/anonymous-catalog-write-denied.txt"
)
psql_raw_output_path="${evidence_dir}/.anonymous-psql-output.$$"
psql_raw_output_created=false
evidence_created_count=0

for evidence_name in \
  anonymous-positive.txt \
  anonymous-private-read-denied.txt \
  anonymous-catalog-write-denied.txt; do
  evidence_path="${evidence_dir}/${evidence_name}"
  [[ ! -e "${evidence_path}" && ! -L "${evidence_path}" ]] \
    || fail "refusing to overwrite existing evidence: ${evidence_name}"
done

seal_evidence() {
  local evidence_path
  local evidence_index
  local seal_status=0

  for ((evidence_index = 0; evidence_index < evidence_created_count; evidence_index += 1)); do
    evidence_path=${evidence_paths[$evidence_index]}
    if [[ -e "${evidence_path}" || -L "${evidence_path}" ]]; then
      if [[ -f "${evidence_path}" && ! -L "${evidence_path}" &&
            -O "${evidence_path}" ]]; then
        chmod 0400 "${evidence_path}" || seal_status=1
      else
        seal_status=1
      fi
    fi
  done
  return "${seal_status}"
}

cleanup_psql_raw_output() {
  if [[ "${psql_raw_output_created:-false}" == true ]]; then
    [[ -f "${psql_raw_output_path}" \
       && ! -L "${psql_raw_output_path}" \
       && -O "${psql_raw_output_path}" ]] || return 1
    rm -f -- "${psql_raw_output_path}" || return 1
    psql_raw_output_created=false
  fi
}

reset_psql_raw_output() {
  [[ -f "${psql_raw_output_path}" \
     && ! -L "${psql_raw_output_path}" \
     && -O "${psql_raw_output_path}" ]] || return 1
  : >"${psql_raw_output_path}"
}

on_exit() {
  local status=$?
  trap - EXIT HUP INT QUIT TERM
  if ! cleanup_psql_raw_output; then
    printf 'ERROR: could not remove anonymous psql scratch output\n' >&2
    status=1
  fi
  if ! seal_evidence; then
    printf 'ERROR: could not seal anonymous access evidence\n' >&2
    status=1
  fi
  exit "${status}"
}

trap on_exit EXIT
trap 'exit 130' HUP INT TERM
trap 'exit 131' QUIT

[[ ! -e "${psql_raw_output_path}" && ! -L "${psql_raw_output_path}" ]] \
  || fail 'refusing to overwrite anonymous psql scratch output'
(set -o noclobber; : >"${psql_raw_output_path}") \
  || fail 'could not exclusively create anonymous psql scratch output'
psql_raw_output_created=true
chmod 0600 "${psql_raw_output_path}" \
  || fail 'could not protect anonymous psql scratch output'

for evidence_path in "${evidence_paths[@]}"; do
  (set -o noclobber; : >"${evidence_path}") \
    || fail "could not exclusively create evidence: ${evidence_path##*/}"
  ((evidence_created_count += 1))
done

repo_commit="$(safe_git -C "${repo_root}" rev-parse HEAD)"
operator_manifest_sha256="$(sha256_file "${operator_manifest_path}")"
runner_sha256="$(sha256_file "${BASH_SOURCE[0]}")"
staging_ref_sha256="$(sha256_text "${staging_ref}")"
production_ref_sha256="$(sha256_text "${production_ref}")"
staging_ref_raw="${staging_ref}"
production_ref_raw="${production_ref}"

redact_psql_output() {
  local line
  while IFS= read -r line || [[ -n "${line}" ]]; do
    if [[ "${line}" == *'postgresql://'* || "${line}" == *'postgres://'* ]]; then
      printf '%s\n' '<psql connection detail redacted>'
      continue
    fi
    line=${line//"${staging_ref_raw}"/'<staging-ref>'}
    line=${line//"${production_ref_raw}"/'<production-ref>'}
    printf '%s\n' "${line}"
  done
}

write_evidence_header() {
  local evidence_path="$1"
  local sql_name="$2"
  local sql_sha256
  sql_sha256="$(sha256_file "${sql_dir}/${sql_name}")"
  printf '%s\n' \
    'evidence_schema=1' \
    'phase=anonymous-access' \
    "case=${sql_name}" \
    "repository_commit=${repo_commit}" \
    "operator_manifest_sha256=${operator_manifest_sha256}" \
    "sql_sha256=${sql_sha256}" \
    "runner_sha256=${runner_sha256}" \
    "staging_project_ref_sha256=${staging_ref_sha256}" \
    "production_project_ref_sha256=${production_ref_sha256}" \
    "linked_guard=${linked_guard_output}" \
    'target_identity_guard=independent_policy_and_database_marker_passed' \
    'database_url_recorded=false' \
    >>"${evidence_path}"
}

write_evidence_header "${evidence_paths[0]}" anonymous-positive.sql
write_evidence_header "${evidence_paths[1]}" anonymous-private-read-must-fail.sql
write_evidence_header "${evidence_paths[2]}" anonymous-catalog-write-must-fail.sql

# Keep the credential-bearing URL out of the psql argument list, logs, and
# unrelated subprocess environments.
unset PGAPPNAME PGCONNECT_TIMEOUT PGDATABASE PGHOST PGHOSTADDR PGOPTIONS
unset PGPASSFILE PGPASSWORD PGPORT PGSERVICE PGSERVICEFILE PGSSLMODE PGUSER
unset PGCHANNELBINDING PGCLIENTENCODING PGGSSENCMODE PGLOADBALANCEHOSTS
unset PGDATESTYLE PGGSSLIB PGKRBSRVNAME PGREQUIREAUTH PGREQUIREPEER
unset PGREQUIRESSL PGSSLCERT PGSSLCERTMODE PGSSLCRL PGSSLCRLDIR PGSSLKEY
unset PGSSLMAXPROTOCOLVERSION PGSSLMINPROTOCOLVERSION PGSSLNEGOTIATION
unset PGSSLROOTCERT PGTARGETSESSIONATTRS PGTCP_USER_TIMEOUT PGTZ

set +e
if gallr_run_reviewed_node \
  GALLR_PSQL_APPNAME=gallr_staging_anonymous_positive \
  GALLR_PSQL_CONNECT_TIMEOUT=10 \
  GALLR_PSQL_OPTIONS= \
  "GALLR_VALIDATION_PROJECT_REF=${staging_ref}" \
  "GALLR_VALIDATION_DATABASE_URL=${staging_database_url}" \
  GALLR_VALIDATION_REQUIRE_DIRECT=true \
  "GALLR_VALIDATED_PSQL_PATH=${GALLR_REVIEWED_PSQL_PATH}" \
  "GALLR_VALIDATED_PSQL_SHA256=${GALLR_REVIEWED_PSQL_SHA256}" \
  -- "${psql_runner_path}" -- \
    -v ON_ERROR_STOP=1 \
    -f "${sql_dir}/anonymous-positive.sql" \
    >"${psql_raw_output_path}" 2>&1; then
  positive_status=0
else
  positive_status=$?
fi
if redact_psql_output \
  <"${psql_raw_output_path}" >>"${evidence_paths[0]}"; then
  positive_redact_status=0
else
  positive_redact_status=$?
fi
reset_psql_raw_output || fail 'could not reset anonymous psql scratch output'
set -e
[[ "${positive_status}" -eq 0 && "${positive_redact_status}" -eq 0 ]] \
  || fail 'anonymous positive read failed; inspect retained evidence'

run_expected_denial() {
  local sql_name="$1"
  local evidence_name="$2"
  local evidence_path="${evidence_dir}/${evidence_name}"
  local status
  local redact_status

  set +e
  if gallr_run_reviewed_node \
    "GALLR_PSQL_APPNAME=gallr_staging_${evidence_name%.txt}" \
    GALLR_PSQL_CONNECT_TIMEOUT=10 \
    GALLR_PSQL_OPTIONS= \
    "GALLR_VALIDATION_PROJECT_REF=${staging_ref}" \
    "GALLR_VALIDATION_DATABASE_URL=${staging_database_url}" \
    GALLR_VALIDATION_REQUIRE_DIRECT=true \
    "GALLR_VALIDATED_PSQL_PATH=${GALLR_REVIEWED_PSQL_PATH}" \
    "GALLR_VALIDATED_PSQL_SHA256=${GALLR_REVIEWED_PSQL_SHA256}" \
    -- "${psql_runner_path}" -- \
      -v ON_ERROR_STOP=1 \
      -f "${sql_dir}/${sql_name}" \
      >"${psql_raw_output_path}" 2>&1; then
    status=0
  else
    status=$?
  fi
  if redact_psql_output \
    <"${psql_raw_output_path}" >>"${evidence_path}"; then
    redact_status=0
  else
    redact_status=$?
  fi
  reset_psql_raw_output ||
    fail 'could not reset anonymous psql scratch output'
  set -e

  [[ ${redact_status} -eq 0 ]] \
    || fail "could not redact ${sql_name} evidence"
  [[ ${status} -ne 0 ]] \
    || fail "${sql_name} unexpectedly succeeded; inspect retained evidence"
  grep -Fq 'GALLR_ANON_ROLE_ASSUMED' "${evidence_path}" \
    || fail "${sql_name} failed before assuming the anon role"
  grep -Eq 'ERROR:[[:space:]]+42501:' "${evidence_path}" \
    || fail "${sql_name} did not fail with SQLSTATE 42501"
  ! grep -Fq 'GALLR_EXPECTED_DENIAL_DID_NOT_OCCUR' "${evidence_path}" \
    || fail "${sql_name} reached its fail-open marker"
}

run_expected_denial \
  anonymous-private-read-must-fail.sql \
  anonymous-private-read-denied.txt
# The catalog denial is a rollback-only DML attempt. Recheck the independently
# approved target immediately before that psql session, not merely when this
# multi-step evidence run began.
assert_target_identity_now
run_expected_denial \
  anonymous-catalog-write-must-fail.sql \
  anonymous-catalog-write-denied.txt

cleanup_psql_raw_output ||
  fail 'could not remove anonymous psql scratch output'
seal_evidence || fail 'could not seal anonymous access evidence'
trap - EXIT HUP INT QUIT TERM
printf '%s\n' \
  'Anonymous positive read and both independent SQLSTATE 42501 denials passed.'
