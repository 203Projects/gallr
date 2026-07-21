#!/usr/bin/env bash

# Bootstrap the disposable-clone marker exactly once. The marker does not exist
# yet, so this installer cannot call assert-disposable-clone-target.sh. It
# instead binds one direct PostgreSQL installation to the clean linked checkout,
# sealed operator manifest, independently validated policy, and an exact typed
# confirmation containing both the staging ref and reviewed commit.

set -euo pipefail
umask 077

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

clear_libpq_environment() {
  unset PGAPPNAME PGCHANNELBINDING PGCLIENTENCODING PGCONNECT_TIMEOUT
  unset PGDATABASE PGDATESTYLE PGGSSENCMODE PGGSSLIB PGHOST PGHOSTADDR
  unset PGKRBSRVNAME PGLOADBALANCEHOSTS PGOPTIONS PGPASSFILE PGPASSWORD PGPORT
  unset PGREQUIREAUTH PGREQUIREPEER PGSERVICE PGSERVICEFILE PGSSLCERT PGSSLCRL
  unset PGREQUIRESSL PGSSLCERTMODE PGSSLCRLDIR PGSSLKEY
  unset PGSSLMAXPROTOCOLVERSION PGSSLMINPROTOCOLVERSION
  unset PGSSLMODE PGSSLNEGOTIATION PGSSLROOTCERT PGTARGETSESSIONATTRS
  unset PGTCP_USER_TIMEOUT PGTZ PGUSER
}

# Nothing above this point launches a child process. Snapshot the only trusted
# caller inputs into fresh, non-exported variables, then remove credentials,
# target labels, validator aliases, and poisonable internal names before the
# first command substitution or external command.
require_env GALLR_EXPECTED_STAGING_PROJECT_REF
require_env GALLR_PRODUCTION_PROJECT_REF
require_env GALLR_STAGING_DATABASE_URL
require_env GALLR_STAGING_REHEARSAL_CONFIRM
require_env GALLR_STAGING_EVIDENCE_DIR
require_env GALLR_STAGING_IDENTITY_POLICY_PATH
require_env GALLR_REVIEWED_COMMIT
require_env GALLR_DISPOSABLE_CLONE_MARKER_INSTALL_CONFIRMATION

unset bootstrap_staging_ref bootstrap_production_ref bootstrap_database_url
unset bootstrap_staging_confirmation bootstrap_evidence_dir_input
unset bootstrap_policy_path bootstrap_reviewed_commit
unset bootstrap_install_confirmation
bootstrap_staging_ref="${GALLR_EXPECTED_STAGING_PROJECT_REF}"
bootstrap_production_ref="${GALLR_PRODUCTION_PROJECT_REF}"
bootstrap_database_url="${GALLR_STAGING_DATABASE_URL}"
bootstrap_staging_confirmation="${GALLR_STAGING_REHEARSAL_CONFIRM}"
bootstrap_evidence_dir_input="${GALLR_STAGING_EVIDENCE_DIR}"
bootstrap_policy_path="${GALLR_STAGING_IDENTITY_POLICY_PATH}"
bootstrap_reviewed_commit="${GALLR_REVIEWED_COMMIT}"
bootstrap_install_confirmation="${GALLR_DISPOSABLE_CLONE_MARKER_INSTALL_CONFIRMATION}"

unset GALLR_EXPECTED_STAGING_PROJECT_REF GALLR_PRODUCTION_PROJECT_REF
unset GALLR_STAGING_DATABASE_URL GALLR_STAGING_REHEARSAL_CONFIRM
unset GALLR_STAGING_EVIDENCE_DIR GALLR_STAGING_IDENTITY_POLICY_PATH
unset GALLR_REVIEWED_COMMIT
unset GALLR_DISPOSABLE_CLONE_MARKER_INSTALL_CONFIRMATION
unset GALLR_VALIDATION_PROJECT_REF GALLR_VALIDATION_DATABASE_URL
unset GALLR_VALIDATION_REQUIRE_DIRECT
unset GALLR_IDENTITY_POLICY_PATH GALLR_IDENTITY_REPO_ROOT
unset GALLR_IDENTITY_OPERATOR_MANIFEST_PATH
unset GALLR_IDENTITY_EXPECTED_STAGING_REF GALLR_IDENTITY_PRODUCTION_REF
unset GALLR_IDENTITY_CURRENT_COMMIT
unset GALLR_MARKER_ID GALLR_POLICY_ISSUED_AT_UTC
unset GALLR_MARKER_VALID_UNTIL_UTC GALLR_STAGING_REF_SHA256
unset GALLR_PRODUCTION_REF_SHA256 GALLR_OPERATOR_MANIFEST_SHA256
unset GALLR_POLICY_SHA256 GALLR_CHANGE_RECORD
unset GALLR_APPROVER_ONE GALLR_APPROVER_TWO

unset staging_ref production_ref database_url staging_confirmation
unset evidence_dir_input policy_path reviewed_commit install_confirmation
unset policy_record final_policy_record marker_id policy_issued_at_utc
unset valid_until_utc staging_ref_sha256 production_ref_sha256
unset repository_commit operator_manifest_sha256 change_record
unset approver_one approver_two policy_sha256 extra_field
unset command_name source_dir script_dir expected_repo_root repo_root
unset current_commit commit_pattern project_ref_pattern
unset expected_install_confirmation installer_path linked_guard_path
unset database_validator_path policy_validator_path marker_sql_path
unset required_path relative_path evidence_dir evidence_dir_mode
unset operator_manifest_path evidence_path evidence_created
unset staging_ref_fingerprint production_ref_fingerprint
unset confirmation_fingerprint snapshot_installer_sha256
unset snapshot_linked_guard_sha256 snapshot_database_validator_sha256
unset snapshot_policy_validator_sha256 snapshot_marker_sql_sha256
unset snapshot_operator_manifest_sha256 snapshot_policy_sha256
unset snapshot_policy_record final_commit

unset DATABASE_URL DB_URL POSTGRES_URL POSTGRES_URL_NON_POOLING
unset POSTGRES_PRISMA_URL SUPABASE_DB_URL GALLR_STAGING_DB_URL
unset GALLR_PRODUCTION_DATABASE_URL GALLR_SERVICE_ROLE_KEY
unset SUPABASE_ACCESS_TOKEN SUPABASE_URL SUPABASE_ANON_KEY
unset SUPABASE_SERVICE_ROLE_KEY SUPABASE_SECRET_KEY
unset SUPABASE_DB_PASSWORD
clear_libpq_environment

unset NODE_OPTIONS NODE_PATH NODE_DEBUG NODE_DEBUG_NATIVE
unset NODE_EXTRA_CA_CERTS NODE_TLS_REJECT_UNAUTHORIZED NODE_USE_ENV_PROXY
unset SSL_CERT_FILE SSL_CERT_DIR SSLKEYLOGFILE
unset HTTP_PROXY HTTPS_PROXY ALL_PROXY NO_PROXY
unset http_proxy https_proxy all_proxy no_proxy
unset BASH_ENV ENV CDPATH
unset -f awk bash chmod git node psql sha256sum shasum stat 2>/dev/null || :

unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_OBJECT_DIRECTORY
unset GIT_ALTERNATE_OBJECT_DIRECTORIES GIT_COMMON_DIR GIT_CEILING_DIRECTORIES
unset GIT_DISCOVERY_ACROSS_FILESYSTEM GIT_CONFIG GIT_CONFIG_PARAMETERS
unset GIT_CONFIG_SYSTEM
export GIT_CONFIG_COUNT=0 GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
export GIT_OPTIONAL_LOCKS=0
export LC_ALL=C
IFS=$' \t\n'

for command_name in awk bash chmod git node psql stat; do
  command -v "${command_name}" >/dev/null 2>&1 \
    || fail "required command is unavailable: ${command_name}"
done

sha256_file() {
  if command -v shasum >/dev/null 2>&1; then
    command shasum -a 256 "$1" | command awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then
    command sha256sum "$1" | command awk '{print $1}'
  else
    fail 'shasum or sha256sum is required'
  fi
}

sha256_text() {
  if command -v shasum >/dev/null 2>&1; then
    printf '%s' "$1" | command shasum -a 256 | command awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then
    printf '%s' "$1" | command sha256sum | command awk '{print $1}'
  else
    fail 'shasum or sha256sum is required'
  fi
}

file_mode() {
  local target="$1"
  local value

  if value=$(command stat -f '%Lp' "${target}" 2>/dev/null); then
    printf '%s\n' "${value}"
    return 0
  fi
  if value=$(command stat -c '%a' "${target}" 2>/dev/null); then
    printf '%s\n' "${value}"
    return 0
  fi
  return 1
}

file_nlink() {
  local target="$1"
  local value

  if value=$(command stat -f '%l' "${target}" 2>/dev/null); then
    printf '%s\n' "${value}"
    return 0
  fi
  if value=$(command stat -c '%h' "${target}" 2>/dev/null); then
    printf '%s\n' "${value}"
    return 0
  fi
  return 1
}

safe_git() {
  command git \
    -c core.fsmonitor=false \
    -c core.hooksPath=/dev/null \
    -c core.excludesFile=/dev/null \
    "$@"
}

source_dir="${BASH_SOURCE[0]%/*}"
[[ "${source_dir}" != "${BASH_SOURCE[0]}" ]] || source_dir='.'
script_dir="$(builtin cd -- "${source_dir}" && builtin pwd -P)"
expected_repo_root="$(builtin cd -- "${script_dir}/../.." && builtin pwd -P)"
repo_root="$(safe_git -C "${script_dir}" rev-parse --show-toplevel 2>/dev/null)" \
  || fail 'could not resolve the repository root'
repo_root="$(builtin cd -- "${repo_root}" && builtin pwd -P)"
[[ "${repo_root}" == "${expected_repo_root}" ]] \
  || fail 'Git repository root does not match the checked-in installer location'

current_commit="$(safe_git -C "${repo_root}" rev-parse HEAD 2>/dev/null)" \
  || fail 'could not resolve the repository commit'
commit_pattern='^([0-9a-f]{40}|[0-9a-f]{64})$'
project_ref_pattern='^[a-z0-9]{20}$'
[[ "${bootstrap_reviewed_commit}" =~ ${commit_pattern} ]] \
  || fail 'reviewed commit must be a full lowercase Git object ID'
[[ "${bootstrap_reviewed_commit}" == "${current_commit}" ]] \
  || fail 'reviewed commit must exactly match the current checkout'
[[ "${bootstrap_staging_ref}" =~ ${project_ref_pattern} ]] \
  || fail 'expected staging project ref must be 20 lowercase alphanumeric characters'
[[ "${bootstrap_production_ref}" =~ ${project_ref_pattern} ]] \
  || fail 'production project ref must be 20 lowercase alphanumeric characters'
[[ "${bootstrap_staging_ref}" != "${bootstrap_production_ref}" ]] \
  || fail 'staging and production project references must differ'
[[ "${bootstrap_staging_confirmation}" == "${bootstrap_staging_ref}" ]] \
  || fail 'staging confirmation must exactly match the expected staging ref'

expected_install_confirmation="INSTALL_GALLR_DISPOSABLE_CLONE_MARKER:${bootstrap_staging_ref}:${bootstrap_reviewed_commit}"
[[ "${bootstrap_install_confirmation}" == "${expected_install_confirmation}" ]] \
  || fail 'typed marker-install confirmation does not match staging and the reviewed commit'

installer_path="${script_dir}/install-disposable-clone-marker.sh"
linked_guard_path="${script_dir}/assert-linked-staging.sh"
database_validator_path="${script_dir}/lib/validate-database-target.mjs"
policy_validator_path="${script_dir}/lib/validate-target-identity-policy.mjs"
marker_sql_path="${script_dir}/sql/install-disposable-clone-marker.sql"
for required_path in \
  "${installer_path}" \
  "${linked_guard_path}" \
  "${database_validator_path}" \
  "${policy_validator_path}" \
  "${marker_sql_path}"; do
  [[ -f "${required_path}" && ! -L "${required_path}" ]] \
    || fail 'a required marker-installer artifact is missing or is a symbolic link'
  relative_path="${required_path#"${repo_root}/"}"
  safe_git -C "${repo_root}" ls-files --error-unmatch -- "${relative_path}" \
    >/dev/null 2>&1 \
    || fail 'a required marker-installer artifact is not tracked by Git'
done
[[ -x "${installer_path}" ]] || fail 'marker installer is not executable'
[[ -x "${linked_guard_path}" ]] || fail 'linked staging guard is not executable'

[[ "${bootstrap_evidence_dir_input}" = /* ]] \
  || fail 'evidence directory must be an absolute path'
[[ -d "${bootstrap_evidence_dir_input}" ]] \
  || fail 'evidence directory does not exist'
[[ ! -L "${bootstrap_evidence_dir_input}" ]] \
  || fail 'evidence directory must not be a symbolic link'
evidence_dir="$(builtin cd -- "${bootstrap_evidence_dir_input}" && builtin pwd -P)"
case "${evidence_dir}" in
  "${repo_root}"|"${repo_root}/"*)
    fail 'evidence directory must be outside the repository'
    ;;
esac
evidence_dir_mode="$(file_mode "${evidence_dir}")" \
  || fail 'could not inspect evidence directory permissions'
[[ "${evidence_dir_mode}" == '700' ]] \
  || fail 'evidence directory permissions must be 0700'
[[ -O "${evidence_dir}" ]] \
  || fail 'evidence directory must be owned by the current user'

operator_manifest_path="${evidence_dir}/operator-manifest.txt"
evidence_path="${evidence_dir}/disposable-clone-marker-installation.txt"
[[ -f "${operator_manifest_path}" && ! -L "${operator_manifest_path}" ]] \
  || fail 'operator manifest is missing or is a symbolic link'
[[ ! -e "${evidence_path}" && ! -L "${evidence_path}" ]] \
  || fail 'refusing to overwrite existing marker-installation evidence'

if ! (set -o noclobber; : > "${evidence_path}") 2>/dev/null; then
  fail 'could not exclusively create marker-installation evidence'
fi

evidence_created=true

require_open_evidence() {
  local mode
  local nlink

  [[ -f "${evidence_path}" && ! -L "${evidence_path}" && -O "${evidence_path}" ]] \
    || return 1
  mode="$(file_mode "${evidence_path}")" || return 1
  [[ "${mode}" == '600' ]] || return 1
  nlink="$(file_nlink "${evidence_path}")" || return 1
  [[ "${nlink}" == '1' ]]
}

append_evidence() {
  require_open_evidence || fail 'marker-installation evidence changed while open'
  [[ "$1" != *$'\n'* && "$1" != *$'\r'* ]] \
    || fail 'refusing to write a malformed evidence record'
  printf '%s\n' "$1" >> "${evidence_path}"
}

seal_evidence() {
  local mode
  local nlink

  [[ -f "${evidence_path}" && ! -L "${evidence_path}" && -O "${evidence_path}" ]] \
    || return 1
  nlink="$(file_nlink "${evidence_path}")" || return 1
  [[ "${nlink}" == '1' ]] || return 1
  command chmod 0400 "${evidence_path}" || return 1
  mode="$(file_mode "${evidence_path}")" || return 1
  [[ "${mode}" == '400' ]]
}

on_exit() {
  local status=$?
  trap - EXIT HUP INT TERM
  if [[ "${evidence_created:-false}" == true ]] && ! seal_evidence; then
    printf 'ERROR: could not seal marker-installation evidence\n' >&2
    status=1
  fi
  exit "${status}"
}

trap on_exit EXIT
trap 'exit 130' HUP INT TERM

staging_ref_fingerprint="$(sha256_text "${bootstrap_staging_ref}")"
production_ref_fingerprint="$(sha256_text "${bootstrap_production_ref}")"
confirmation_fingerprint="$(sha256_text "${bootstrap_install_confirmation}")"

snapshot_installer_sha256="$(sha256_file "${installer_path}")"
snapshot_linked_guard_sha256="$(sha256_file "${linked_guard_path}")"
snapshot_database_validator_sha256="$(sha256_file "${database_validator_path}")"
snapshot_policy_validator_sha256="$(sha256_file "${policy_validator_path}")"
snapshot_marker_sql_sha256="$(sha256_file "${marker_sql_path}")"

append_evidence 'evidence_schema=1'
append_evidence 'operation=install-disposable-clone-marker'
append_evidence "repository_commit=${current_commit}"
append_evidence "staging_project_ref_sha256=${staging_ref_fingerprint}"
append_evidence "production_project_ref_sha256=${production_ref_fingerprint}"
append_evidence "confirmation_sha256=${confirmation_fingerprint}"
append_evidence "installer_sha256=${snapshot_installer_sha256}"
append_evidence "linked_guard_sha256=${snapshot_linked_guard_sha256}"
append_evidence "database_validator_sha256=${snapshot_database_validator_sha256}"
append_evidence "policy_validator_sha256=${snapshot_policy_validator_sha256}"
append_evidence "marker_sql_sha256=${snapshot_marker_sql_sha256}"
append_evidence 'database_url_recorded=false'

run_linked_guard() {
  local output

  if ! output=$(
    (
      clear_libpq_environment
      BASH_ENV=/dev/null ENV=/dev/null \
      GALLR_EXPECTED_STAGING_PROJECT_REF="${bootstrap_staging_ref}" \
      GALLR_PRODUCTION_PROJECT_REF="${bootstrap_production_ref}" \
      GALLR_STAGING_REHEARSAL_CONFIRM="${bootstrap_staging_confirmation}" \
      GALLR_STAGING_EVIDENCE_DIR="${evidence_dir}" \
        command bash --noprofile --norc "${linked_guard_path}"
    ) 2>/dev/null
  ); then
    return 1
  fi
  [[ "${output}" == 'PASS: linked project matches the reviewed staging manifest' ]]
}

run_database_validator() {
  GALLR_VALIDATION_PROJECT_REF="${bootstrap_staging_ref}" \
  GALLR_VALIDATION_DATABASE_URL="${bootstrap_database_url}" \
  GALLR_VALIDATION_REQUIRE_DIRECT=true \
  NODE_OPTIONS='' NODE_PATH='' \
    command node "${database_validator_path}" >/dev/null 2>&1
}

run_policy_validator() {
  GALLR_IDENTITY_POLICY_PATH="${bootstrap_policy_path}" \
  GALLR_IDENTITY_REPO_ROOT="${repo_root}" \
  GALLR_IDENTITY_OPERATOR_MANIFEST_PATH="${operator_manifest_path}" \
  GALLR_IDENTITY_EXPECTED_STAGING_REF="${bootstrap_staging_ref}" \
  GALLR_IDENTITY_PRODUCTION_REF="${bootstrap_production_ref}" \
  GALLR_IDENTITY_CURRENT_COMMIT="${current_commit}" \
  NODE_OPTIONS='' NODE_PATH='' \
    command node "${policy_validator_path}" 2>/dev/null
}

parse_policy_record() {
  local record="$1"
  local remainder
  local field_count=1
  local parsed_value

  [[ -n "${record}" && "${record}" != *$'\n'* && "${record}" != *$'\r'* ]] \
    || fail 'identity policy validator returned an invalid record'
  remainder="${record}"
  while [[ "${remainder}" == *$'\t'* ]]; do
    remainder="${remainder#*$'\t'}"
    field_count=$((field_count + 1))
  done
  [[ "${field_count}" -eq 11 ]] \
    || fail 'identity policy validator returned an unexpected field count'

  unset marker_id policy_issued_at_utc valid_until_utc
  unset parsed_staging_ref_sha256 parsed_production_ref_sha256
  unset parsed_repository_commit parsed_operator_manifest_sha256
  unset change_record approver_one approver_two parsed_policy_sha256
  IFS=$'\t' read -r \
    marker_id \
    policy_issued_at_utc \
    valid_until_utc \
    parsed_staging_ref_sha256 \
    parsed_production_ref_sha256 \
    parsed_repository_commit \
    parsed_operator_manifest_sha256 \
    change_record \
    approver_one \
    approver_two \
    parsed_policy_sha256 \
    <<< "${record}"
  for parsed_value in \
    "${marker_id}" "${policy_issued_at_utc}" "${valid_until_utc}" \
    "${parsed_staging_ref_sha256}" "${parsed_production_ref_sha256}" \
    "${parsed_repository_commit}" "${parsed_operator_manifest_sha256}" \
    "${change_record}" "${approver_one}" "${approver_two}" \
    "${parsed_policy_sha256}"; do
    [[ -n "${parsed_value}" ]] \
      || fail 'identity policy validator returned an empty field'
  done
  [[ "${record}" != *"${bootstrap_staging_ref}"* \
     && "${record}" != *"${bootstrap_production_ref}"* ]] \
    || fail 'identity policy validator disclosed a raw project ref'
  [[ "${parsed_staging_ref_sha256}" == "${staging_ref_fingerprint}" \
     && "${parsed_production_ref_sha256}" == "${production_ref_fingerprint}" \
     && "${parsed_repository_commit}" == "${current_commit}" ]] \
    || fail 'identity policy validator returned a changed target binding'
}

run_linked_guard \
  || fail 'linked project did not match the reviewed staging manifest'
append_evidence 'initial_linked_guard=pass'

run_database_validator \
  || fail 'database URL did not resolve to the reviewed direct staging target'
append_evidence 'initial_direct_url_validation=pass'

if ! policy_record="$(run_policy_validator)"; then
  fail 'independent staging identity policy validation failed'
fi
parse_policy_record "${policy_record}"

snapshot_operator_manifest_sha256="$(sha256_file "${operator_manifest_path}")"
snapshot_policy_sha256="$(sha256_file "${bootstrap_policy_path}")"
[[ "${snapshot_operator_manifest_sha256}" == "${parsed_operator_manifest_sha256}" ]] \
  || fail 'operator manifest changed during identity policy validation'
[[ "${snapshot_policy_sha256}" == "${parsed_policy_sha256}" ]] \
  || fail 'identity policy changed during validation'
append_evidence "operator_manifest_sha256=${snapshot_operator_manifest_sha256}"
append_evidence "identity_policy_sha256=${snapshot_policy_sha256}"
append_evidence 'initial_policy_validation=pass'

snapshot_policy_record="${policy_record}"

# Revalidate the policy first, then place the linked-target and direct-URL
# checks directly in front of a final byte-for-byte artifact comparison and the
# sole psql session. No marker query is possible during this bootstrap phase.
if ! final_policy_record="$(run_policy_validator)"; then
  fail 'independent identity policy changed before marker installation'
fi
[[ "${final_policy_record}" == "${snapshot_policy_record}" ]] \
  || fail 'identity policy validator result changed before marker installation'
parse_policy_record "${final_policy_record}"
append_evidence 'final_policy_validation=pass'

run_linked_guard \
  || fail 'linked project or reviewed checkout changed before marker installation'
append_evidence 'final_linked_guard=pass'

run_database_validator \
  || fail 'database URL validation changed before marker installation'
append_evidence 'final_direct_url_validation=pass'

final_commit="$(safe_git -C "${repo_root}" rev-parse HEAD 2>/dev/null)" \
  || fail 'could not re-resolve the repository commit'
[[ "${final_commit}" == "${current_commit}" \
   && "$(sha256_file "${installer_path}")" == "${snapshot_installer_sha256}" \
   && "$(sha256_file "${linked_guard_path}")" == "${snapshot_linked_guard_sha256}" \
   && "$(sha256_file "${database_validator_path}")" == "${snapshot_database_validator_sha256}" \
   && "$(sha256_file "${policy_validator_path}")" == "${snapshot_policy_validator_sha256}" \
   && "$(sha256_file "${marker_sql_path}")" == "${snapshot_marker_sql_sha256}" \
   && "$(sha256_file "${operator_manifest_path}")" == "${snapshot_operator_manifest_sha256}" \
   && "$(sha256_file "${bootstrap_policy_path}")" == "${snapshot_policy_sha256}" ]] \
  || fail 'a reviewed marker-installation artifact changed before psql'
append_evidence 'artifact_stability=pass'

clear_libpq_environment
if ! PGDATABASE="${bootstrap_database_url}" \
  PGCONNECT_TIMEOUT=15 \
  PGSSLMODE=verify-full \
  PGPASSFILE=/dev/null \
  PGOPTIONS='-c statement_timeout=15000 -c lock_timeout=3000' \
  PGAPPNAME='gallr-disposable-clone-marker-install' \
    command psql -X --no-password --set=ON_ERROR_STOP=1 \
      -v installation_confirmation=INSTALL_GALLR_DISPOSABLE_CLONE_MARKER \
      -v "marker_id=${marker_id}" \
      -v "policy_issued_at_utc=${policy_issued_at_utc}" \
      -v "valid_until_utc=${valid_until_utc}" \
      -v "staging_ref_sha256=${parsed_staging_ref_sha256}" \
      -v "production_ref_sha256=${parsed_production_ref_sha256}" \
      -v "repository_commit=${parsed_repository_commit}" \
      -v "operator_manifest_sha256=${parsed_operator_manifest_sha256}" \
      -v "policy_sha256=${parsed_policy_sha256}" \
      -v "change_record=${change_record}" \
      -v "approver_one=${approver_one}" \
      -v "approver_two=${approver_two}" \
      -f "${marker_sql_path}" >/dev/null 2>&1; then
  clear_libpq_environment
  fail 'disposable-clone marker installation failed'
fi
clear_libpq_environment

append_evidence 'psql_install=success'
append_evidence 'evidence_success=install-disposable-clone-marker'
seal_evidence || fail 'could not seal marker-installation evidence'
trap - EXIT HUP INT TERM
printf 'PASS: installed the approved disposable-clone marker and sealed evidence\n'
