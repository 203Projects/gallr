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

[[ $# -eq 1 ]] || fail 'usage: run-postgrest-evidence.sh catalog|event|featured|empty|mutation'
case_name="$1"
case "${case_name}" in
  catalog|event|featured|empty|mutation) ;;
  *) fail 'unknown PostgREST evidence case' ;;
esac

for name in \
  GALLR_EXPECTED_STAGING_PROJECT_REF \
  GALLR_PRODUCTION_PROJECT_REF \
  GALLR_STAGING_REHEARSAL_CONFIRM \
  GALLR_STAGING_EVIDENCE_DIR \
  GALLR_FIXTURE_RUN_ID \
  SUPABASE_URL \
  SUPABASE_ANON_KEY; do
  require_env "${name}"
done

if [[ "${case_name}" == 'mutation' ]]; then
  require_env GALLR_POSTGREST_MUTATION_HOOK
  require_env GALLR_POSTGREST_MUTATION_HOOK_SHA256
  require_env GALLR_POSTGREST_MUTATION_ATTESTATION
  require_env GALLR_STAGING_DATABASE_URL
  require_env GALLR_STAGING_IDENTITY_POLICY_PATH
fi

# Snapshot credential-bearing inputs before the first child process. Keep the
# snapshots unexported and pass only the minimum required values to each guard
# or harness invocation below.
unset staging_ref production_ref staging_confirmation evidence_dir_input
unset fixture_run_id supabase_url supabase_anon_key
unset mutation_hook mutation_hook_sha256 mutation_attestation mutation_timeout
unset mutation_database_url mutation_identity_policy_path
staging_ref="${GALLR_EXPECTED_STAGING_PROJECT_REF}"
production_ref="${GALLR_PRODUCTION_PROJECT_REF}"
staging_confirmation="${GALLR_STAGING_REHEARSAL_CONFIRM}"
evidence_dir_input="${GALLR_STAGING_EVIDENCE_DIR}"
fixture_run_id="${GALLR_FIXTURE_RUN_ID}"
supabase_url="${SUPABASE_URL}"
supabase_anon_key="${SUPABASE_ANON_KEY}"
mutation_hook="${GALLR_POSTGREST_MUTATION_HOOK:-}"
mutation_hook_sha256="${GALLR_POSTGREST_MUTATION_HOOK_SHA256:-}"
mutation_attestation="${GALLR_POSTGREST_MUTATION_ATTESTATION:-}"
mutation_timeout="${GALLR_POSTGREST_MUTATION_HOOK_TIMEOUT_MS:-30000}"
mutation_database_url="${GALLR_STAGING_DATABASE_URL:-}"
mutation_identity_policy_path="${GALLR_STAGING_IDENTITY_POLICY_PATH:-}"
unset GALLR_EXPECTED_STAGING_PROJECT_REF GALLR_PRODUCTION_PROJECT_REF
unset GALLR_STAGING_REHEARSAL_CONFIRM GALLR_STAGING_EVIDENCE_DIR
unset GALLR_FIXTURE_RUN_ID SUPABASE_URL SUPABASE_ANON_KEY
unset SUPABASE_SERVICE_ROLE_KEY SUPABASE_SECRET_KEY GALLR_SERVICE_ROLE_KEY
unset GALLR_STAGING_DATABASE_URL DATABASE_URL GALLR_STAGING_IDENTITY_POLICY_PATH
unset GALLR_POSTGREST_MUTATION_HOOK GALLR_POSTGREST_MUTATION_HOOK_SHA256
unset GALLR_POSTGREST_MUTATION_ATTESTATION GALLR_POSTGREST_MUTATION_HOOK_TIMEOUT_MS

[[ "${fixture_run_id}" =~ ^[a-z0-9][a-z0-9-]{7,31}$ ]] \
  || fail 'GALLR_FIXTURE_RUN_ID has an invalid format'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
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
linked_guard_path="${script_dir}/assert-linked-staging.sh"
target_identity_guard_path="${script_dir}/assert-disposable-clone-target.sh"
integration_path="${repo_root}/web/tests/fetch-exhibitions.integration.test.js"
[[ -x "${linked_guard_path}" ]] || fail 'linked staging guard is missing'
[[ -x "${target_identity_guard_path}" && ! -L "${target_identity_guard_path}" ]] \
  || fail 'disposable-clone target guard is missing or is a symbolic link'
[[ -f "${integration_path}" ]] || fail 'PostgREST integration harness is missing'
command -v node >/dev/null 2>&1 || fail 'node is required'
command -v jq >/dev/null 2>&1 || fail 'jq is required'

[[ "${evidence_dir_input}" = /* ]] \
  || fail 'evidence directory must be an absolute path'
[[ -d "${evidence_dir_input}" && ! -L "${evidence_dir_input}" ]] \
  || fail 'evidence directory is missing or is a symbolic link'
evidence_dir="$(cd -- "${evidence_dir_input}" && pwd -P)"
case "${evidence_dir}" in
  "${repo_root}"|"${repo_root}/"*) fail 'evidence directory must be outside the repository' ;;
esac
if evidence_mode="$(stat -f '%Lp' "${evidence_dir}" 2>/dev/null)"; then
  :
elif evidence_mode="$(stat -c '%a' "${evidence_dir}" 2>/dev/null)"; then
  :
else
  fail 'could not inspect evidence directory permissions'
fi
[[ "${evidence_mode}" == '700' && -O "${evidence_dir}" ]] \
  || fail 'evidence directory must be owned by the current user and mode 0700'

fixture_run_dir="${evidence_dir}/fixtures-${fixture_run_id}"
fixture_manifest="${fixture_run_dir}/manifest.json"
[[ -d "${fixture_run_dir}" && ! -L "${fixture_run_dir}" && -O "${fixture_run_dir}" ]] \
  || fail 'fixture run directory is missing, linked, or not operator-owned'
[[ -f "${fixture_manifest}" && ! -L "${fixture_manifest}" && -O "${fixture_manifest}" ]] \
  || fail 'sealed fixture manifest is missing, linked, or not operator-owned'
if manifest_mode="$(stat -f '%Lp' "${fixture_manifest}" 2>/dev/null)"; then
  :
elif manifest_mode="$(stat -c '%a' "${fixture_manifest}" 2>/dev/null)"; then
  :
else
  fail 'could not inspect fixture manifest permissions'
fi
[[ "${manifest_mode}" == '400' ]] || fail 'fixture manifest must have mode 0400'

fixture_prefix="$(jq -er '.fixture_prefix | strings' "${fixture_manifest}")"
fixture_count="$(jq -er '.fixture_count | numbers' "${fixture_manifest}")"
load_event_id="$(jq -er '.database_evidence.load_event_id | strings' "${fixture_manifest}")"
empty_event_id="$(jq -er '.database_evidence.empty_event_id | strings' "${fixture_manifest}")"
boundary_cursor_id="$(jq -er '.database_evidence.boundary_cursor_id | strings' "${fixture_manifest}")"
mutation_target_id="$(jq -er '.mutation_target_id | strings' "${fixture_manifest}")"
baseline_v2_count="$(jq -er '.baseline.v2_count | numbers' "${fixture_manifest}")"
manifest_staging_sha256="$(jq -er '.staging_ref_sha256 | strings' "${fixture_manifest}")"
manifest_production_sha256="$(jq -er '.production_ref_sha256 | strings' "${fixture_manifest}")"

[[ "$(jq -er '.schema_version' "${fixture_manifest}")" == '1' ]] \
  || fail 'fixture manifest schema is invalid'
[[ "$(jq -er '.state' "${fixture_manifest}")" == 'provisioned' ]] \
  || fail 'fixture manifest is not provisioned'
[[ "${fixture_prefix}" == "gallr-rehearsal-${fixture_run_id}-" ]] \
  || fail 'fixture prefix does not match the run ID'
[[ "${fixture_count}" == '1205' ]] || fail 'fixture manifest count must be 1205'
[[ "${baseline_v2_count}" =~ ^[0-9]+$ ]] || fail 'fixture baseline V2 count is invalid'
[[ "${manifest_staging_sha256}" == \
   "$(sha256_text "${staging_ref}")" ]] \
  || fail 'fixture manifest staging fingerprint mismatch'
[[ "${manifest_production_sha256}" == \
   "$(sha256_text "${production_ref}")" ]] \
  || fail 'fixture manifest production fingerprint mismatch'
[[ "${mutation_target_id}" == "${fixture_prefix}catalog-0750.mutate,(same-id):한글" ]] \
  || fail 'fixture mutation target is invalid'

case "${case_name}" in
  catalog)
    exact_count=$((baseline_v2_count + fixture_count))
    minimum_count="${fixture_count}"
    minimum_page_requests=$(((exact_count + 499) / 500 + 1))
    event_id=''
    expected_cursor=''
    featured_only='0'
    evidence_name='postgrest-complete-catalog.txt'
    ;;
  event)
    exact_count="${fixture_count}"
    minimum_count='1001'
    minimum_page_requests='4'
    event_id="${load_event_id}"
    expected_cursor="${boundary_cursor_id}"
    featured_only='0'
    evidence_name='postgrest-fixture-event.txt'
    ;;
  featured)
    exact_count='5'
    minimum_count='5'
    minimum_page_requests='2'
    event_id="${load_event_id}"
    expected_cursor=''
    featured_only='1'
    evidence_name='postgrest-featured-event.txt'
    ;;
  empty)
    exact_count='0'
    minimum_count='0'
    minimum_page_requests='1'
    event_id="${empty_event_id}"
    expected_cursor=''
    featured_only='0'
    evidence_name='postgrest-empty-event.txt'
    ;;
  mutation)
    exact_count="${fixture_count}"
    minimum_count='1001'
    minimum_page_requests='4'
    event_id="${load_event_id}"
    expected_cursor="${boundary_cursor_id}"
    featured_only='0'
    evidence_name='postgrest-same-id-retry.txt'
    ;;
esac

linked_guard_output="$(
  BASH_ENV=/dev/null ENV=/dev/null \
  GALLR_EXPECTED_STAGING_PROJECT_REF="${staging_ref}" \
  GALLR_PRODUCTION_PROJECT_REF="${production_ref}" \
  GALLR_STAGING_REHEARSAL_CONFIRM="${staging_confirmation}" \
  GALLR_STAGING_EVIDENCE_DIR="${evidence_dir}" \
    "${linked_guard_path}"
)"
target_identity_guard='not_required_for_read_only_case'
if [[ "${case_name}" == 'mutation' ]]; then
  target_identity_output="$(
    BASH_ENV=/dev/null ENV=/dev/null \
    GALLR_EXPECTED_STAGING_PROJECT_REF="${staging_ref}" \
    GALLR_PRODUCTION_PROJECT_REF="${production_ref}" \
    GALLR_STAGING_DATABASE_URL="${mutation_database_url}" \
    GALLR_STAGING_REHEARSAL_CONFIRM="${staging_confirmation}" \
    GALLR_STAGING_EVIDENCE_DIR="${evidence_dir}" \
    GALLR_STAGING_IDENTITY_POLICY_PATH="${mutation_identity_policy_path}" \
      "${target_identity_guard_path}"
  )" \
    || fail 'disposable-clone target identity failed'
  [[ "$(printf '%s\n' "${target_identity_output}" | grep -Fxc \
    'PASS: independent policy and disposable-clone marker identify staging')" == '1' ]] \
    || fail 'target-identity output is missing its exact pass record'
  target_identity_guard='independent_policy_and_database_marker_passed'
fi
evidence_path="${evidence_dir}/${evidence_name}"
[[ ! -e "${evidence_path}" && ! -L "${evidence_path}" ]] \
  || fail "refusing to overwrite ${evidence_name}"
umask 077
(set -o noclobber; : >"${evidence_path}") \
  || fail "could not exclusively create ${evidence_name}"

seal_evidence() {
  [[ -f "${evidence_path}" && ! -L "${evidence_path}" ]] || return 1
  chmod 0400 "${evidence_path}"
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

printf '%s\n' \
  'evidence_schema=1' \
  "phase=postgrest-${case_name}" \
  "repository_commit=$(safe_git -C "${repo_root}" rev-parse HEAD)" \
  "operator_manifest_sha256=$(sha256_file "${evidence_dir}/operator-manifest.txt")" \
  "fixture_manifest_sha256=$(sha256_file "${fixture_manifest}")" \
  "runner_sha256=$(sha256_file "${BASH_SOURCE[0]}")" \
  "integration_harness_sha256=$(sha256_file "${integration_path}")" \
  "target_guard_sha256=$(sha256_file "${target_identity_guard_path}")" \
  "linked_guard=${linked_guard_output}" \
  "target_identity_guard=${target_identity_guard}" \
  'api_credentials_recorded=false' \
  | tee -a "${evidence_path}"

target_guard_sha256="$(sha256_file "${target_identity_guard_path}")"
unset NODE_DEBUG NODE_DEBUG_NATIVE NODE_EXTRA_CA_CERTS NODE_TLS_REJECT_UNAUTHORIZED
unset NODE_USE_ENV_PROXY SSL_CERT_FILE SSL_CERT_DIR SSLKEYLOGFILE
unset HTTP_PROXY HTTPS_PROXY ALL_PROXY NO_PROXY
unset http_proxy https_proxy all_proxy no_proxy

set +e
if [[ "${case_name}" == 'mutation' ]]; then
  GALLR_POSTGREST_INTEGRATION=1 \
  GALLR_POSTGREST_TARGET=staging \
  GALLR_EXHIBITION_SOURCE=canonical-v2 \
  GALLR_EXPECTED_EXHIBITION_COUNT="${exact_count}" \
  GALLR_EXPECTED_MIN_EXHIBITIONS="${minimum_count}" \
  GALLR_EXPECTED_MIN_PAGE_REQUESTS="${minimum_page_requests}" \
  GALLR_TEST_EVENT_ID="${event_id}" \
  GALLR_EXPECTED_CURSOR="${expected_cursor}" \
  GALLR_TEST_FEATURED_ONLY="${featured_only}" \
  GALLR_POSTGREST_MUTATION_HOOK="${mutation_hook}" \
  GALLR_POSTGREST_MUTATION_HOOK_SHA256="${mutation_hook_sha256}" \
  GALLR_POSTGREST_FIXTURE_MANIFEST="${fixture_manifest}" \
  GALLR_POSTGREST_MUTATION_ATTESTATION="${mutation_attestation}" \
  GALLR_POSTGREST_MUTATION_TARGET_ID="${mutation_target_id}" \
  GALLR_POSTGREST_MUTATION_HOOK_TIMEOUT_MS="${mutation_timeout}" \
  GALLR_POSTGREST_TARGET_GUARD="${target_identity_guard_path}" \
  GALLR_POSTGREST_TARGET_GUARD_SHA256="${target_guard_sha256}" \
  GALLR_STAGING_DATABASE_URL="${mutation_database_url}" \
  GALLR_STAGING_EVIDENCE_DIR="${evidence_dir}" \
  GALLR_STAGING_IDENTITY_POLICY_PATH="${mutation_identity_policy_path}" \
  GALLR_EXPECTED_FETCH_ATTEMPTS=2 \
  GALLR_EXPECTED_INTEGRITY_CALLS=2 \
  GALLR_EXPECTED_STAGING_PROJECT_REF="${staging_ref}" \
  GALLR_PRODUCTION_PROJECT_REF="${production_ref}" \
  GALLR_STAGING_REHEARSAL_CONFIRM="${staging_confirmation}" \
  SUPABASE_URL="${supabase_url}" \
  SUPABASE_ANON_KEY="${supabase_anon_key}" \
  NODE_OPTIONS='' NODE_PATH='' \
    node "${integration_path}" 2>&1 | tee -a "${evidence_path}"
  pipeline_status=("${PIPESTATUS[@]}")
else
  GALLR_POSTGREST_INTEGRATION=1 \
  GALLR_POSTGREST_TARGET=staging \
  GALLR_EXHIBITION_SOURCE=canonical-v2 \
  GALLR_EXPECTED_EXHIBITION_COUNT="${exact_count}" \
  GALLR_EXPECTED_MIN_EXHIBITIONS="${minimum_count}" \
  GALLR_EXPECTED_MIN_PAGE_REQUESTS="${minimum_page_requests}" \
  GALLR_TEST_EVENT_ID="${event_id}" \
  GALLR_EXPECTED_CURSOR="${expected_cursor}" \
  GALLR_TEST_FEATURED_ONLY="${featured_only}" \
  GALLR_EXPECTED_STAGING_PROJECT_REF="${staging_ref}" \
  GALLR_PRODUCTION_PROJECT_REF="${production_ref}" \
  GALLR_STAGING_REHEARSAL_CONFIRM="${staging_confirmation}" \
  SUPABASE_URL="${supabase_url}" \
  SUPABASE_ANON_KEY="${supabase_anon_key}" \
  NODE_OPTIONS='' NODE_PATH='' \
    node "${integration_path}" 2>&1 | tee -a "${evidence_path}"
  pipeline_status=("${PIPESTATUS[@]}")
fi
set -e
[[ "${pipeline_status[0]}" -eq 0 ]] || fail "PostgREST ${case_name} evidence failed"
[[ "${pipeline_status[1]}" -eq 0 ]] || fail "could not retain PostgREST ${case_name} evidence"

seal_evidence || fail "could not seal ${evidence_name}"
trap - EXIT HUP INT TERM
printf 'PASS: retained %s\n' "${evidence_name}"
