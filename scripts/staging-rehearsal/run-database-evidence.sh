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

file_mode() {
  local target="$1"
  local value

  if value=$(stat -f '%Lp' "${target}" 2>/dev/null); then
    printf '%s\n' "${value}"
    return 0
  fi
  if value=$(stat -c '%a' "${target}" 2>/dev/null); then
    printf '%s\n' "${value}"
    return 0
  fi
  return 1
}

file_nlink() {
  local target="$1"
  local value

  if value=$(stat -f '%l' "${target}" 2>/dev/null); then
    printf '%s\n' "${value}"
    return 0
  fi
  if value=$(stat -c '%h' "${target}" 2>/dev/null); then
    printf '%s\n' "${value}"
    return 0
  fi
  return 1
}

require_sealed_pre_migration_file() {
  local evidence_file="$1"
  local mode
  local nlink

  [[ -e "${evidence_file}" && -f "${evidence_file}" ]] \
    || fail 'pre-migration evidence is missing or is not a regular file'
  [[ ! -L "${evidence_file}" ]] \
    || fail 'pre-migration evidence must not be a symbolic link'
  [[ -O "${evidence_file}" ]] \
    || fail 'pre-migration evidence must be owned by the current user'
  mode=$(file_mode "${evidence_file}") \
    || fail 'could not inspect pre-migration evidence permissions'
  [[ "${mode}" == '400' ]] \
    || fail 'pre-migration evidence permissions must be 0400'
  nlink=$(file_nlink "${evidence_file}") \
    || fail 'could not inspect pre-migration evidence link count'
  [[ "${nlink}" == '1' ]] \
    || fail 'pre-migration evidence must have exactly one hard link'
}

require_exact_evidence_field() {
  local evidence_file="$1"
  local key="$2"
  local expected="$3"
  local key_count
  local exact_count

  key_count=$(LC_ALL=C grep -a -Ec "^${key}=" "${evidence_file}" || true)
  exact_count=$(LC_ALL=C grep -a -Fxc "${key}=${expected}" "${evidence_file}" || true)
  [[ "${key_count}" -eq 1 && "${exact_count}" -eq 1 ]] \
    || fail "pre-migration evidence has an invalid ${key} field"
}

validate_pre_migration_evidence() {
  local evidence_file="$1"
  local expected_commit="$2"
  local expected_manifest_sha256="$3"
  local expected_runner_sha256="$4"
  local expected_sql_sha256="$5"
  local expected_staging_sha256="$6"
  local expected_production_sha256="$7"
  local expected_linked_guard="$8"
  local sha256_before
  local sha256_after
  local legacy_hash_key_lines
  local legacy_hash_lines

  require_sealed_pre_migration_file "${evidence_file}"
  sha256_before=$(sha256_file "${evidence_file}")
  require_exact_evidence_field "${evidence_file}" evidence_schema 2
  require_exact_evidence_field "${evidence_file}" phase pre-migration
  require_exact_evidence_field \
    "${evidence_file}" repository_commit "${expected_commit}"
  require_exact_evidence_field \
    "${evidence_file}" operator_manifest_sha256 "${expected_manifest_sha256}"
  require_exact_evidence_field \
    "${evidence_file}" runner_sha256 "${expected_runner_sha256}"
  require_exact_evidence_field \
    "${evidence_file}" sql_sha256 "${expected_sql_sha256}"
  require_exact_evidence_field \
    "${evidence_file}" staging_project_ref_sha256 "${expected_staging_sha256}"
  require_exact_evidence_field \
    "${evidence_file}" production_project_ref_sha256 "${expected_production_sha256}"
  require_exact_evidence_field \
    "${evidence_file}" linked_guard "${expected_linked_guard}"
  require_exact_evidence_field \
    "${evidence_file}" database_url_recorded false
  require_exact_evidence_field \
    "${evidence_file}" pre_migration_evidence_sha256 not-required
  require_exact_evidence_field \
    "${evidence_file}" evidence_success pre-migration
  [[ "$(tail -n 1 "${evidence_file}")" == 'evidence_success=pre-migration' ]] \
    || fail 'pre-migration evidence success marker must be the final line'

  legacy_hash_key_lines=$(grep -a -Ec \
    '^legacy_full_payload_sha256=' \
    "${evidence_file}" || true)
  legacy_hash_lines=$(grep -a -Ec \
    '^legacy_full_payload_sha256=[0-9a-f]{64}$' \
    "${evidence_file}" || true)
  [[ "${legacy_hash_key_lines}" -eq 1 && "${legacy_hash_lines}" -eq 1 ]] \
    || fail 'pre-migration evidence must contain one full legacy payload SHA-256'

  expected_legacy_payload_sha256=$(sed -n \
    's/^legacy_full_payload_sha256=\([0-9a-f]\{64\}\)$/\1/p' \
    "${evidence_file}")
  [[ "${expected_legacy_payload_sha256}" =~ ^[0-9a-f]{64}$ ]] \
    || fail 'pre-migration legacy payload SHA-256 is invalid'
  sha256_after=$(sha256_file "${evidence_file}")
  [[ "${sha256_before}" == "${sha256_after}" ]] \
    || fail 'pre-migration evidence changed during validation'
  require_sealed_pre_migration_file "${evidence_file}"
  pre_migration_evidence_sha256="${sha256_after}"
}

usage() {
  printf '%s\n' \
    'Usage: run-database-evidence.sh pre-migration|post-migration|post-import|final-representative|observe-locks'
}

[[ $# -eq 1 ]] || {
  usage >&2
  exit 2
}

phase="$1"
case "${phase}" in
  pre-migration)
    sql_name='pre-migration-inventory.sql'
    evidence_name='pre-migration-inventory.txt'
    require_direct='false'
    expected_runtime='not-checked'
    require_representative_data='false'
    ;;
  post-migration)
    sql_name='post-migration-validation.sql'
    evidence_name='post-migration-validation.txt'
    require_direct='false'
    expected_runtime='sheet-owned'
    require_representative_data='false'
    ;;
  post-import)
    sql_name='post-migration-validation.sql'
    evidence_name='post-import-validation.txt'
    require_direct='false'
    expected_runtime='sheet-owned'
    require_representative_data='false'
    ;;
  final-representative)
    sql_name='post-migration-validation.sql'
    evidence_name='final-representative-validation.txt'
    require_direct='false'
    expected_runtime='canonical-owned'
    require_representative_data='true'
    ;;
  observe-locks)
    sql_name='migration-lock-observer.sql'
    evidence_name='migration-lock-observer.txt'
    require_direct='true'
    expected_runtime='not-checked'
    require_representative_data='false'
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac

require_env GALLR_EXPECTED_STAGING_PROJECT_REF
require_env GALLR_PRODUCTION_PROJECT_REF
require_env GALLR_STAGING_DATABASE_URL
require_env GALLR_STAGING_REHEARSAL_CONFIRM
require_env GALLR_STAGING_EVIDENCE_DIR

# Snapshot the credential-bearing database URL and target labels before the
# first child process. The unexported copies are passed only to the exact
# validator, guard, or psql invocation that needs them.
unset staging_ref_raw production_ref_raw staging_database_url
unset staging_confirmation evidence_dir_input
staging_ref_raw="${GALLR_EXPECTED_STAGING_PROJECT_REF}"
production_ref_raw="${GALLR_PRODUCTION_PROJECT_REF}"
staging_database_url="${GALLR_STAGING_DATABASE_URL}"
staging_confirmation="${GALLR_STAGING_REHEARSAL_CONFIRM}"
evidence_dir_input="${GALLR_STAGING_EVIDENCE_DIR}"
unset GALLR_EXPECTED_STAGING_PROJECT_REF GALLR_PRODUCTION_PROJECT_REF
unset GALLR_STAGING_DATABASE_URL DATABASE_URL
unset GALLR_STAGING_REHEARSAL_CONFIRM GALLR_STAGING_EVIDENCE_DIR
unset SUPABASE_ACCESS_TOKEN SUPABASE_URL SUPABASE_ANON_KEY
unset SUPABASE_SERVICE_ROLE_KEY SUPABASE_SECRET_KEY
unset GALLR_SERVICE_ROLE_KEY

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

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
expected_repo_root="$(cd -- "${script_dir}/../.." && pwd -P)"
validator_path="${script_dir}/lib/validate-database-target.mjs"
linked_guard_path="${script_dir}/assert-linked-staging.sh"
[[ -f "${validator_path}" ]] || fail 'database target validator is missing'
[[ -x "${linked_guard_path}" ]] || fail 'linked staging guard is missing'
command -v node >/dev/null 2>&1 || fail 'node is required'

project_ref_pattern='^[a-z0-9]{20}$'
[[ "${staging_ref_raw}" =~ ${project_ref_pattern} ]] \
  || fail 'expected staging project ref must be 20 lowercase alphanumeric characters'
[[ "${production_ref_raw}" =~ ${project_ref_pattern} ]] \
  || fail 'production project ref must be 20 lowercase alphanumeric characters'
[[ "${staging_ref_raw}" != "${production_ref_raw}" ]] \
  || fail 'staging and production project references must differ'
[[ "${staging_confirmation}" == "${staging_ref_raw}" ]] \
  || fail 'confirmation must exactly match the expected staging project ref'

GALLR_VALIDATION_PROJECT_REF="${staging_ref_raw}" \
GALLR_VALIDATION_DATABASE_URL="${staging_database_url}" \
GALLR_VALIDATION_REQUIRE_DIRECT="${require_direct}" \
NODE_OPTIONS='' NODE_PATH='' \
  node "${validator_path}" \
  || fail 'database URL target validation failed'

[[ "${evidence_dir_input}" = /* ]] \
  || fail 'evidence directory must be an absolute path'
[[ -d "${evidence_dir_input}" ]] \
  || fail 'evidence directory does not exist'
[[ ! -L "${evidence_dir_input}" ]] \
  || fail 'evidence directory must not be a symbolic link'

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

if mode="$(stat -f '%Lp' "${evidence_dir}" 2>/dev/null)"; then
  :
elif mode="$(stat -c '%a' "${evidence_dir}" 2>/dev/null)"; then
  :
else
  fail 'could not inspect evidence directory permissions'
fi
[[ "${mode}" == '700' ]] || fail 'evidence directory permissions must be 0700'
[[ -O "${evidence_dir}" ]] || fail 'evidence directory must be owned by the current user'

linked_guard_output="$(
  BASH_ENV=/dev/null ENV=/dev/null \
  GALLR_EXPECTED_STAGING_PROJECT_REF="${staging_ref_raw}" \
  GALLR_PRODUCTION_PROJECT_REF="${production_ref_raw}" \
  GALLR_STAGING_REHEARSAL_CONFIRM="${staging_confirmation}" \
  GALLR_STAGING_EVIDENCE_DIR="${evidence_dir}" \
    "${linked_guard_path}"
)"
[[ -n "${linked_guard_output}" \
   && "${linked_guard_output}" != *$'\n'* \
   && "${linked_guard_output}" != *$'\r'* ]] \
  || fail 'linked staging guard returned an invalid success marker'

command -v psql >/dev/null 2>&1 || fail 'psql is required'

sql_path="${script_dir}/sql/${sql_name}"
pre_migration_sql_path="${script_dir}/sql/pre-migration-inventory.sql"
evidence_path="${evidence_dir}/${evidence_name}"
[[ -f "${sql_path}" ]] || fail "missing SQL file: ${sql_name}"
[[ -f "${pre_migration_sql_path}" ]] \
  || fail 'missing SQL file: pre-migration-inventory.sql'

operator_manifest_path="${evidence_dir}/operator-manifest.txt"
[[ -f "${operator_manifest_path}" && ! -L "${operator_manifest_path}" ]] \
  || fail 'operator manifest is missing or is a symbolic link'
repo_commit="$(safe_git -C "${repo_root}" rev-parse HEAD)"
operator_manifest_sha256="$(sha256_file "${operator_manifest_path}")"
sql_sha256="$(sha256_file "${sql_path}")"
pre_migration_sql_sha256="$(sha256_file "${pre_migration_sql_path}")"
runner_sha256="$(sha256_file "${BASH_SOURCE[0]}")"
staging_ref_sha256="$(sha256_text "${staging_ref_raw}")"
production_ref_sha256="$(sha256_text "${production_ref_raw}")"

expected_legacy_payload_sha256='not-checked'
pre_migration_evidence_sha256='not-required'
case "${phase}" in
  post-migration|post-import)
    pre_migration_evidence="${evidence_dir}/pre-migration-inventory.txt"
    validate_pre_migration_evidence \
      "${pre_migration_evidence}" \
      "${repo_commit}" \
      "${operator_manifest_sha256}" \
      "${runner_sha256}" \
      "${pre_migration_sql_sha256}" \
      "${staging_ref_sha256}" \
      "${production_ref_sha256}" \
      "${linked_guard_output}"
    ;;
esac

[[ ! -e "${evidence_path}" && ! -L "${evidence_path}" ]] \
  || fail "refusing to overwrite existing evidence: ${evidence_name}"

umask 077
if ! (set -o noclobber; : >"${evidence_path}"); then
  fail "could not exclusively create evidence: ${evidence_name}"
fi

seal_evidence() {
  local sealed_mode
  local sealed_nlink

  [[ -f "${evidence_path}" && ! -L "${evidence_path}" && -O "${evidence_path}" ]] \
    || return 1
  sealed_nlink=$(file_nlink "${evidence_path}") || return 1
  [[ "${sealed_nlink}" == '1' ]] || return 1
  chmod 0400 "${evidence_path}" || return 1
  sealed_mode=$(file_mode "${evidence_path}") || return 1
  [[ "${sealed_mode}" == '400' ]]
}

on_exit() {
  local status=$?
  trap - EXIT HUP INT TERM
  if ! seal_evidence; then
    printf 'ERROR: could not seal %s\n' "${evidence_name}" >&2
    status=1
  fi
  exit "${status}"
}

trap on_exit EXIT
trap 'exit 130' HUP INT TERM

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

unset PGAPPNAME PGCONNECT_TIMEOUT PGDATABASE PGHOST PGHOSTADDR PGOPTIONS
unset PGPASSFILE PGPASSWORD PGPORT PGSERVICE PGSERVICEFILE PGSSLMODE PGUSER
unset PGCHANNELBINDING PGCLIENTENCODING PGGSSENCMODE PGLOADBALANCEHOSTS
unset PGDATESTYLE PGGSSLIB PGKRBSRVNAME PGREQUIREAUTH PGREQUIREPEER
unset PGREQUIRESSL PGSSLCERT PGSSLCERTMODE PGSSLCRL PGSSLCRLDIR PGSSLKEY
unset PGSSLMAXPROTOCOLVERSION PGSSLMINPROTOCOLVERSION PGSSLNEGOTIATION
unset PGSSLROOTCERT PGTARGETSESSIONATTRS PGTCP_USER_TIMEOUT PGTZ

printf '%s\n' \
  'evidence_schema=2' \
  "phase=${phase}" \
  "repository_commit=${repo_commit}" \
  "operator_manifest_sha256=${operator_manifest_sha256}" \
  "sql_sha256=${sql_sha256}" \
  "runner_sha256=${runner_sha256}" \
  "staging_project_ref_sha256=${staging_ref_sha256}" \
  "production_project_ref_sha256=${production_ref_sha256}" \
  "linked_guard=${linked_guard_output}" \
  'database_url_recorded=false' \
  "pre_migration_evidence_sha256=${pre_migration_evidence_sha256}" \
  | tee -a "${evidence_path}"

PGAPPNAME="gallr_staging_evidence_${phase//-/_}" \
PGCONNECT_TIMEOUT=10 \
PGSSLMODE=verify-full \
PGPASSFILE=/dev/null \
PGDATABASE="${staging_database_url}" \
  psql -X --no-password -v ON_ERROR_STOP=1 \
    -v expected_runtime="${expected_runtime}" \
    -v require_representative_data="${require_representative_data}" \
    -v expected_legacy_payload_sha256="${expected_legacy_payload_sha256}" \
    -f "${sql_path}" 2>&1 \
  | redact_psql_output \
  | tee -a "${evidence_path}"

printf 'evidence_success=%s\n' "${phase}" | tee -a "${evidence_path}"

seal_evidence || fail "could not seal ${evidence_name}"
trap - EXIT HUP INT TERM
printf 'PASS: retained %s\n' "${evidence_name}"
