#!/usr/bin/env bash
set -euo pipefail

# Never expose the credential-bearing validator/psql environment assignments
# through inherited shell tracing.
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

require_env GALLR_EXPECTED_STAGING_PROJECT_REF
require_env GALLR_PRODUCTION_PROJECT_REF
require_env GALLR_STAGING_DATABASE_URL
require_env GALLR_STAGING_REHEARSAL_CONFIRM
require_env GALLR_STAGING_EVIDENCE_DIR
require_env GALLR_STAGING_IDENTITY_POLICY_PATH
require_env GALLR_LEGACY_PROBE_EXHIBITION_ID

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

case "${GALLR_LEGACY_PROBE_EXHIBITION_ID}" in
  *$'\n'*|*$'\r'*) fail 'probe exhibition ID must be a single-line value' ;;
esac

# Snapshot every target/credential input before the first child process. The
# snapshots remain unexported and are passed only to the exact validator, guard,
# or psql invocation that needs them.
unset staging_ref_raw production_ref_raw staging_database_url
unset staging_confirmation evidence_dir_input staging_identity_policy_path
unset target_id
staging_ref_raw="${GALLR_EXPECTED_STAGING_PROJECT_REF}"
production_ref_raw="${GALLR_PRODUCTION_PROJECT_REF}"
staging_database_url="${GALLR_STAGING_DATABASE_URL}"
staging_confirmation="${GALLR_STAGING_REHEARSAL_CONFIRM}"
evidence_dir_input="${GALLR_STAGING_EVIDENCE_DIR}"
staging_identity_policy_path="${GALLR_STAGING_IDENTITY_POLICY_PATH}"
target_id="${GALLR_LEGACY_PROBE_EXHIBITION_ID}"
unset GALLR_EXPECTED_STAGING_PROJECT_REF GALLR_PRODUCTION_PROJECT_REF
unset GALLR_STAGING_DATABASE_URL DATABASE_URL
unset GALLR_STAGING_REHEARSAL_CONFIRM GALLR_STAGING_EVIDENCE_DIR
unset GALLR_STAGING_IDENTITY_POLICY_PATH GALLR_LEGACY_PROBE_EXHIBITION_ID
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
sql_path="${script_dir}/sql/migration-writer-probe.sql"

[[ -f "${validator_path}" ]] || fail 'database target validator is missing'
[[ -f "${psql_runner_path}" && ! -L "${psql_runner_path}" ]] \
  || fail 'validated psql runner is missing or is a symbolic link'
[[ -f "${toolchain_helper_path}" && ! -L "${toolchain_helper_path}" ]] \
  || fail 'reviewed toolchain helper is missing or is a symbolic link'
[[ -x "${linked_guard_path}" ]] || fail 'linked staging guard is missing'
[[ -x "${target_identity_guard_path}" && ! -L "${target_identity_guard_path}" ]] \
  || fail 'disposable-clone target guard is missing or is a symbolic link'
[[ -f "${sql_path}" ]] || fail 'migration writer probe SQL is missing'
linked_guard_output="$(
  BASH_ENV=/dev/null ENV=/dev/null \
  GALLR_EXPECTED_STAGING_PROJECT_REF="${staging_ref_raw}" \
  GALLR_PRODUCTION_PROJECT_REF="${production_ref_raw}" \
  GALLR_STAGING_REHEARSAL_CONFIRM="${staging_confirmation}" \
  GALLR_STAGING_EVIDENCE_DIR="${evidence_dir_input}" \
    "${linked_guard_path}"
)"

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

[[ "${evidence_dir_input}" = /* ]] \
  || fail 'evidence directory must be an absolute path'
[[ -d "${evidence_dir_input}" ]] \
  || fail 'evidence directory does not exist'
[[ ! -L "${evidence_dir_input}" ]] \
  || fail 'evidence directory must not be a symbolic link'
evidence_dir="$(cd -- "${evidence_dir_input}" && pwd -P)"
case "${evidence_dir}" in
  "${repo_root}"|"${repo_root}/"*)
    fail 'evidence directory must be outside the repository'
    ;;
esac

if evidence_mode="$(stat -f '%Lp' "${evidence_dir}" 2>/dev/null)"; then
  :
elif evidence_mode="$(stat -c '%a' "${evidence_dir}" 2>/dev/null)"; then
  :
else
  fail 'could not inspect evidence directory permissions'
fi
[[ "${evidence_mode}" == '700' ]] \
  || fail 'evidence directory permissions must be 0700'
[[ -O "${evidence_dir}" ]] \
  || fail 'evidence directory must be owned by the current user'

operator_manifest_path="${evidence_dir}/operator-manifest.txt"
[[ -f "${operator_manifest_path}" && ! -L "${operator_manifest_path}" ]] \
  || fail 'operator manifest is missing or is a symbolic link'
# shellcheck source=lib/reviewed-toolchain.sh
source "${toolchain_helper_path}"
gallr_read_reviewed_toolchain "${operator_manifest_path}" \
  || fail 'reviewed Node.js/psql toolchain does not match the preflight manifest'
gallr_run_reviewed_node \
  "GALLR_VALIDATION_PROJECT_REF=${staging_ref_raw}" \
  "GALLR_VALIDATION_DATABASE_URL=${staging_database_url}" \
  GALLR_VALIDATION_REQUIRE_DIRECT=true \
  -- "${validator_path}" \
  || fail 'database URL must be a direct connection to the approved staging project'
repo_commit="$(safe_git -C "${repo_root}" rev-parse HEAD)"
operator_manifest_sha256="$(sha256_file "${operator_manifest_path}")"
probe_sql_sha256="$(sha256_file "${sql_path}")"
probe_script_sha256="$(sha256_file "${BASH_SOURCE[0]}")"
staging_ref_sha256="$(sha256_text "${staging_ref_raw}")"
production_ref_sha256="$(sha256_text "${production_ref_raw}")"
target_id_sha256="$(sha256_text "${target_id}")"

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

assert_target_identity_now() {
  gallr_run_clean_bash \
    "GALLR_EXPECTED_STAGING_PROJECT_REF=${staging_ref_raw}" \
    "GALLR_PRODUCTION_PROJECT_REF=${production_ref_raw}" \
    "GALLR_STAGING_DATABASE_URL=${staging_database_url}" \
    "GALLR_STAGING_REHEARSAL_CONFIRM=${staging_confirmation}" \
    "GALLR_STAGING_EVIDENCE_DIR=${evidence_dir}" \
    "GALLR_STAGING_IDENTITY_POLICY_PATH=${staging_identity_policy_path}" \
    -- "${target_identity_guard_path}" >/dev/null \
    || fail 'disposable-clone target identity failed'
}

assert_target_identity_now

evidence_path="${evidence_dir}/migration-writer-probe.txt"
psql_raw_output_path="${evidence_dir}/.migration-writer-psql-output.$$"
psql_raw_output_created=false
[[ ! -e "${evidence_path}" && ! -L "${evidence_path}" ]] \
  || fail 'refusing to overwrite migration-writer-probe.txt'
[[ ! -e "${psql_raw_output_path}" && ! -L "${psql_raw_output_path}" ]] \
  || fail 'refusing to overwrite migration-writer psql scratch output'

umask 077
evidence_created=false

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
    printf 'ERROR: could not remove migration-writer psql scratch output\n' >&2
    status=1
  fi
  if [[ "${evidence_created:-false}" == true ]] &&
    { [[ ! -f "${evidence_path}" || -L "${evidence_path}" ]] ||
      ! chmod 0400 "${evidence_path}"; }; then
    printf 'ERROR: could not seal migration writer probe evidence\n' >&2
    status=1
  fi
  exit "${status}"
}

handle_signal() {
  local signal_status=${1:-130}

  if [[ "${evidence_created:-false}" == true ]]; then
    printf 'probe_stopped_at=%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
      >>"${evidence_path}" || exit 1
  fi
  exit "${signal_status}"
}

trap on_exit EXIT
trap handle_signal HUP INT TERM
trap 'handle_signal 131' QUIT

(set -o noclobber; : >"${evidence_path}") \
  || fail 'could not exclusively create migration-writer-probe.txt'
evidence_created=true
(set -o noclobber; : >"${psql_raw_output_path}") \
  || fail 'could not exclusively create migration-writer psql scratch output'
psql_raw_output_created=true
chmod 0600 "${psql_raw_output_path}" \
  || fail 'could not protect migration-writer psql scratch output'

unset PGAPPNAME PGCONNECT_TIMEOUT PGDATABASE PGHOST PGHOSTADDR PGOPTIONS
unset PGPASSFILE PGPASSWORD PGPORT PGSERVICE PGSERVICEFILE PGSSLMODE PGUSER
unset PGCHANNELBINDING PGCLIENTENCODING PGGSSENCMODE PGLOADBALANCEHOSTS
unset PGDATESTYLE PGGSSLIB PGKRBSRVNAME PGREQUIREAUTH PGREQUIREPEER
unset PGREQUIRESSL PGSSLCERT PGSSLCERTMODE PGSSLCRL PGSSLCRLDIR PGSSLKEY
unset PGSSLMAXPROTOCOLVERSION PGSSLMINPROTOCOLVERSION PGSSLNEGOTIATION
unset PGSSLROOTCERT PGTARGETSESSIONATTRS PGTCP_USER_TIMEOUT PGTZ

printf '%s\n' \
  'probe=migration_legacy_writer' \
  "probe_started_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
  'transaction_outcome=rollback_only' \
  'elapsed_metric=database_statement_elapsed_ms_includes_update_execution_and_lock_wait' \
  "repository_commit=${repo_commit}" \
  "operator_manifest_sha256=${operator_manifest_sha256}" \
  "probe_sql_sha256=${probe_sql_sha256}" \
  "probe_script_sha256=${probe_script_sha256}" \
  "staging_project_ref_sha256=${staging_ref_sha256}" \
  "production_project_ref_sha256=${production_ref_sha256}" \
  "target_id_sha256=${target_id_sha256}" \
  "linked_guard=${linked_guard_output}" \
  'target_identity_guard=independent_policy_and_database_marker_passed' \
  'Stop with Ctrl-C only after the migration command has finished.' \
  | tee -a "${evidence_path}"

iteration=0
while :; do
  iteration=$((iteration + 1))
  assert_target_identity_now
  printf 'probe_iteration=%s launched_at=%s\n' \
    "${iteration}" "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
    | tee -a "${evidence_path}"

  set +e
  if gallr_run_reviewed_node \
    GALLR_PSQL_APPNAME=gallr_staging_migration_writer_probe \
    GALLR_PSQL_CONNECT_TIMEOUT=10 \
    GALLR_PSQL_OPTIONS= \
    "GALLR_VALIDATION_PROJECT_REF=${staging_ref_raw}" \
    "GALLR_VALIDATION_DATABASE_URL=${staging_database_url}" \
    GALLR_VALIDATION_REQUIRE_DIRECT=true \
    "GALLR_VALIDATED_PSQL_PATH=${GALLR_REVIEWED_PSQL_PATH}" \
    "GALLR_VALIDATED_PSQL_SHA256=${GALLR_REVIEWED_PSQL_SHA256}" \
    -- "${psql_runner_path}" -- \
      -v ON_ERROR_STOP=1 -v target_id="${target_id}" \
      -f "${sql_path}" >"${psql_raw_output_path}" 2>&1; then
    probe_status=0
  else
    probe_status=$?
  fi
  redact_psql_output <"${psql_raw_output_path}" | tee -a "${evidence_path}"
  local_pipeline_status=("${PIPESTATUS[@]}")
  redact_status=${local_pipeline_status[0]}
  tee_status=${local_pipeline_status[1]}
  reset_psql_raw_output ||
    fail 'could not reset migration-writer psql scratch output'
  set -e

  [[ ${probe_status} -eq 0 ]] \
    || fail "migration writer probe iteration ${iteration} failed"
  [[ ${redact_status} -eq 0 ]] \
    || fail "could not redact probe iteration ${iteration} output"
  [[ ${tee_status} -eq 0 ]] \
    || fail "could not append evidence for probe iteration ${iteration}"
  sleep 0.25
done
