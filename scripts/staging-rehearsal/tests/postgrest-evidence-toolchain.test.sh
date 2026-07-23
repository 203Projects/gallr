#!/usr/bin/env bash

# Network-free coverage for the reviewed Node.js boundary used by the
# PostgREST evidence runner. The integration harness and both Node executables
# are local fakes; any PATH-based Node lookup is a test failure.

set -euo pipefail
umask 077

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
REHEARSAL_DIR=$(cd -- "${SCRIPT_DIR}/.." && pwd -P)

TEST_ROOT=$(mktemp -d /tmp/gallr-postgrest-toolchain.XXXXXX)
TEST_ROOT=$(cd -- "${TEST_ROOT}" && pwd -P)
chmod 0700 "${TEST_ROOT}"
trap 'rm -rf -- "${TEST_ROOT}"' EXIT HUP INT TERM

REPO_ROOT="${TEST_ROOT}/repository"
RUNNER_DIR="${REPO_ROOT}/scripts/staging-rehearsal"
INTEGRATION_DIR="${REPO_ROOT}/web/tests"
TOOLS_DIR="${TEST_ROOT}/reviewed-tools"
POISON_BIN="${TEST_ROOT}/poison-bin"
SUCCESS_EVIDENCE="${TEST_ROOT}/success-evidence"
MUTATION_EVIDENCE="${TEST_ROOT}/mutation-evidence"
GUARD_FAILURE_EVIDENCE="${TEST_ROOT}/guard-failure-evidence"
TAMPER_EVIDENCE="${TEST_ROOT}/tamper-evidence"
REVIEWED_NODE="${TOOLS_DIR}/node"
REVIEWED_PSQL="${TOOLS_DIR}/psql"
NODE_ENVIRONMENT="${TEST_ROOT}/reviewed-node-environment.txt"
NODE_ARGUMENTS="${TEST_ROOT}/reviewed-node-arguments.txt"
GUARD_ENVIRONMENT="${TEST_ROOT}/target-guard-environment.txt"
EXECUTION_ORDER="${TEST_ROOT}/mutation-execution-order.txt"
HARNESS_MARKER="${TEST_ROOT}/reviewed-node-ran"
POISON_MARKER="${TEST_ROOT}/path-node-ran"
INTEGRATION_PATH="${INTEGRATION_DIR}/fetch-exhibitions.integration.test.js"
IDENTITY_POLICY="${TEST_ROOT}/staging-identity-policy.txt"

STAGING_REF='aaaaaaaaaaaaaaaaaaaa'
PRODUCTION_REF='bbbbbbbbbbbbbbbbbbbb'
FIXTURE_RUN_ID='toolchain-test'
SUPABASE_URL_VALUE="https://${STAGING_REF}.supabase.co"
SUPABASE_ANON_KEY_VALUE='test-anon-key'

mkdir -m 0700 \
  "${REPO_ROOT}" \
  "${TOOLS_DIR}" \
  "${POISON_BIN}" \
  "${SUCCESS_EVIDENCE}" \
  "${MUTATION_EVIDENCE}" \
  "${GUARD_FAILURE_EVIDENCE}" \
  "${TAMPER_EVIDENCE}"
mkdir -p "${RUNNER_DIR}/lib" "${INTEGRATION_DIR}"

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

file_mode() {
  local value

  if value=$(stat -f '%Lp' "$1" 2>/dev/null); then
    printf '%s\n' "${value}"
  elif value=$(stat -c '%a' "$1" 2>/dev/null); then
    printf '%s\n' "${value}"
  else
    return 1
  fi
}

cp "${REHEARSAL_DIR}/run-postgrest-evidence.sh" \
  "${RUNNER_DIR}/run-postgrest-evidence.sh"
cp "${REHEARSAL_DIR}/lib/reviewed-toolchain.sh" \
  "${RUNNER_DIR}/lib/reviewed-toolchain.sh"

cat >"${RUNNER_DIR}/assert-linked-staging.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' 'PASS: linked project matches the reviewed staging manifest'
EOF

{
  printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail'
  printf '/usr/bin/env | /usr/bin/sort > %q\n' "${GUARD_ENVIRONMENT}"
  printf 'printf "guard\\n" >> %q\n' "${EXECUTION_ORDER}"
  printf '%s\n' \
    'if [[ "${GALLR_STAGING_DATABASE_URL}" == *guard-failure* ]]; then' \
    "  printf '%s\\n' 'intentional target-identity guard failure' >&2" \
    '  exit 71' \
    'fi' \
    "printf '%s\\n' 'PASS: independent policy and disposable-clone marker identify staging'"
} >"${RUNNER_DIR}/assert-disposable-clone-target.sh"

# If the reviewed executable boundary regresses to PATH lookup, this script
# records the violation and fails before any simulated harness output.
{
  printf '%s\n' '#!/bin/sh'
  printf ': > %q\n' "${POISON_MARKER}"
  printf '%s\n' 'exit 97'
} >"${POISON_BIN}/node"

# This JavaScript must never be interpreted by the system Node.js executable.
# The manifest-reviewed fake below receives it only as an opaque argument.
printf '%s\n' 'process.exit(96);' >"${INTEGRATION_PATH}"

{
  printf '%s\n' '#!/bin/sh' 'set -eu'
  printf '/usr/bin/env | /usr/bin/sort > %q\n' "${NODE_ENVIRONMENT}"
  printf 'printf "%%s\\n" "$@" > %q\n' "${NODE_ARGUMENTS}"
  printf 'printf "harness\\n" >> %q\n' "${EXECUTION_ORDER}"
  printf ': > %q\n' "${HARNESS_MARKER}"
  printf '%s\n' \
    "printf '%s\\n' 'reviewed PostgREST integration harness passed'"
} >"${REVIEWED_NODE}"
printf '%s\n' '#!/bin/sh' 'exit 0' >"${REVIEWED_PSQL}"
printf '%s\n' 'staging_ref=aaaaaaaaaaaaaaaaaaaa' >"${IDENTITY_POLICY}"

chmod 0500 \
  "${RUNNER_DIR}/run-postgrest-evidence.sh" \
  "${RUNNER_DIR}/assert-linked-staging.sh" \
  "${RUNNER_DIR}/assert-disposable-clone-target.sh" \
  "${POISON_BIN}/node" \
  "${REVIEWED_NODE}" \
  "${REVIEWED_PSQL}"

REVIEWED_NODE_PATH="$(cd -- "${TOOLS_DIR}" && pwd -P)/node"
REVIEWED_PSQL_PATH="$(cd -- "${TOOLS_DIR}" && pwd -P)/psql"
REVIEWED_NODE_SHA256=$(sha256_file "${REVIEWED_NODE_PATH}")
REVIEWED_PSQL_SHA256=$(sha256_file "${REVIEWED_PSQL_PATH}")
STAGING_SHA256=$(sha256_text "${STAGING_REF}")
PRODUCTION_SHA256=$(sha256_text "${PRODUCTION_REF}")

prepare_evidence() {
  local evidence_dir="$1"
  local fixture_dir="${evidence_dir}/fixtures-${FIXTURE_RUN_ID}"

  mkdir -m 0700 "${fixture_dir}"
  cat >"${fixture_dir}/manifest.json" <<EOF
{
  "schema_version": 1,
  "state": "provisioned",
  "fixture_prefix": "gallr-rehearsal-${FIXTURE_RUN_ID}-",
  "fixture_count": 1205,
  "database_evidence": {
    "load_event_id": "load-event",
    "empty_event_id": "empty-event",
    "boundary_cursor_id": "boundary-cursor"
  },
  "mutation_target_id": "gallr-rehearsal-${FIXTURE_RUN_ID}-catalog-0750.mutate,(same-id):한글",
  "baseline": {
    "v2_count": 0
  },
  "staging_ref_sha256": "${STAGING_SHA256}",
  "production_ref_sha256": "${PRODUCTION_SHA256}"
}
EOF
  chmod 0400 "${fixture_dir}/manifest.json"
  printf '%s\n' \
    'manifest_schema=2' \
    "reviewed_node_path=${REVIEWED_NODE_PATH}" \
    "reviewed_node_sha256=${REVIEWED_NODE_SHA256}" \
    "reviewed_psql_path=${REVIEWED_PSQL_PATH}" \
    "reviewed_psql_sha256=${REVIEWED_PSQL_SHA256}" \
    >"${evidence_dir}/operator-manifest.txt"
  chmod 0400 "${evidence_dir}/operator-manifest.txt"
}

prepare_evidence "${SUCCESS_EVIDENCE}"
prepare_evidence "${MUTATION_EVIDENCE}"
prepare_evidence "${GUARD_FAILURE_EVIDENCE}"
prepare_evidence "${TAMPER_EVIDENCE}"

GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 \
  git -C "${REPO_ROOT}" init -q
git -C "${REPO_ROOT}" config user.name 'PostgREST Toolchain Test'
git -C "${REPO_ROOT}" config user.email 'postgrest-toolchain@example.test'
git -C "${REPO_ROOT}" config commit.gpgsign false
git -C "${REPO_ROOT}" add scripts web
git -c core.hooksPath=/dev/null -C "${REPO_ROOT}" \
  commit -qm 'test PostgREST reviewed Node boundary'

run_case() {
  local evidence_dir="$1"

  env \
    "PATH=${POISON_BIN}:${PATH}" \
    BASH_ENV=/dev/null \
    ENV=/dev/null \
    DATABASE_URL=must-not-reach-reviewed-node \
    GALLR_INJECTION_SHOULD_NOT_SURVIVE=must-not-reach-reviewed-node \
    NODE_DEBUG=must-not-reach-reviewed-node \
    NODE_OPTIONS=must-not-reach-reviewed-node \
    NODE_PATH=must-not-reach-reviewed-node \
    SUPABASE_SECRET_KEY=must-not-reach-reviewed-node \
    SUPABASE_SERVICE_ROLE_KEY=must-not-reach-reviewed-node \
    HTTP_PROXY=http://must-not-reach-reviewed-node.invalid \
    "GALLR_EXPECTED_STAGING_PROJECT_REF=${STAGING_REF}" \
    "GALLR_PRODUCTION_PROJECT_REF=${PRODUCTION_REF}" \
    "GALLR_STAGING_REHEARSAL_CONFIRM=${STAGING_REF}" \
    "GALLR_STAGING_EVIDENCE_DIR=${evidence_dir}" \
    "GALLR_FIXTURE_RUN_ID=${FIXTURE_RUN_ID}" \
    "SUPABASE_URL=${SUPABASE_URL_VALUE}" \
    "SUPABASE_ANON_KEY=${SUPABASE_ANON_KEY_VALUE}" \
    bash "${RUNNER_DIR}/run-postgrest-evidence.sh" empty 2>&1
}

run_mutation_case() {
  local evidence_dir="$1"
  local database_url="$2"

  env \
    "PATH=${POISON_BIN}:${PATH}" \
    BASH_ENV=/dev/null \
    ENV=/dev/null \
    DATABASE_URL=must-not-reach-reviewed-node \
    FIXTURE_POISON_SHOULD_NOT_SURVIVE=must-not-reach-reviewed-node \
    GALLR_INJECTION_SHOULD_NOT_SURVIVE=must-not-reach-reviewed-node \
    NODE_DEBUG=must-not-reach-reviewed-node \
    NODE_OPTIONS=must-not-reach-reviewed-node \
    NODE_PATH=must-not-reach-reviewed-node \
    SUPABASE_SECRET_KEY=must-not-reach-reviewed-node \
    SUPABASE_SERVICE_ROLE_KEY=must-not-reach-reviewed-node \
    HTTP_PROXY=http://must-not-reach-reviewed-node.invalid \
    "GALLR_EXPECTED_STAGING_PROJECT_REF=${STAGING_REF}" \
    "GALLR_PRODUCTION_PROJECT_REF=${PRODUCTION_REF}" \
    "GALLR_STAGING_REHEARSAL_CONFIRM=${STAGING_REF}" \
    "GALLR_STAGING_EVIDENCE_DIR=${evidence_dir}" \
    "GALLR_FIXTURE_RUN_ID=${FIXTURE_RUN_ID}" \
    'GALLR_POSTGREST_MUTATION_ATTESTATION=network-free-mutation-attestation' \
    "GALLR_STAGING_DATABASE_URL=${database_url}" \
    "GALLR_STAGING_IDENTITY_POLICY_PATH=${IDENTITY_POLICY}" \
    "SUPABASE_URL=${SUPABASE_URL_VALUE}" \
    "SUPABASE_ANON_KEY=${SUPABASE_ANON_KEY_VALUE}" \
    bash "${RUNNER_DIR}/run-postgrest-evidence.sh" mutation 2>&1
}

success_output=$(run_case "${SUCCESS_EVIDENCE}")
[[ "${success_output}" == *'PASS: retained postgrest-empty-event.txt'* ]]
[[ -e "${HARNESS_MARKER}" && ! -e "${POISON_MARKER}" ]]
[[ "$(<"${NODE_ARGUMENTS}")" == "${INTEGRATION_PATH}" ]]

EXPECTED_EXPLICIT="${TEST_ROOT}/expected-explicit-environment.txt"
ACTUAL_EXPLICIT="${TEST_ROOT}/actual-explicit-environment.txt"
printf '%s\n' \
  'GALLR_EXHIBITION_SOURCE=canonical-v2' \
  'GALLR_EXPECTED_CURSOR=' \
  'GALLR_EXPECTED_EXHIBITION_COUNT=0' \
  'GALLR_EXPECTED_MIN_EXHIBITIONS=0' \
  'GALLR_EXPECTED_MIN_PAGE_REQUESTS=1' \
  "GALLR_EXPECTED_STAGING_PROJECT_REF=${STAGING_REF}" \
  'GALLR_POSTGREST_INTEGRATION=1' \
  'GALLR_POSTGREST_TARGET=staging' \
  "GALLR_PRODUCTION_PROJECT_REF=${PRODUCTION_REF}" \
  "GALLR_STAGING_REHEARSAL_CONFIRM=${STAGING_REF}" \
  'GALLR_TEST_EVENT_ID=empty-event' \
  'GALLR_TEST_FEATURED_ONLY=0' \
  "SUPABASE_ANON_KEY=${SUPABASE_ANON_KEY_VALUE}" \
  "SUPABASE_URL=${SUPABASE_URL_VALUE}" \
  | LC_ALL=C sort >"${EXPECTED_EXPLICIT}"
grep -E '^(GALLR_|SUPABASE_)' "${NODE_ENVIRONMENT}" \
  >"${ACTUAL_EXPLICIT}"
diff -u "${EXPECTED_EXPLICIT}" "${ACTUAL_EXPLICIT}"

grep -Fqx 'HOME=/nonexistent' "${NODE_ENVIRONMENT}"
grep -Fqx 'LANG=C' "${NODE_ENVIRONMENT}"
grep -Fqx 'LC_ALL=C' "${NODE_ENVIRONMENT}"
grep -Fqx 'PATH=/usr/bin:/bin:/usr/sbin:/sbin' "${NODE_ENVIRONMENT}"
grep -Fqx 'TMPDIR=/tmp' "${NODE_ENVIRONMENT}"
for forbidden in \
  DATABASE_URL \
  GALLR_INJECTION_SHOULD_NOT_SURVIVE \
  HTTP_PROXY \
  NODE_DEBUG \
  NODE_OPTIONS \
  NODE_PATH \
  SUPABASE_SECRET_KEY \
  SUPABASE_SERVICE_ROLE_KEY; do
  ! grep -Eq "^${forbidden}=" "${NODE_ENVIRONMENT}"
done

SUCCESS_RESULT="${SUCCESS_EVIDENCE}/postgrest-empty-event.txt"
[[ -f "${SUCCESS_RESULT}" \
   && ! -L "${SUCCESS_RESULT}" \
   && "$(file_mode "${SUCCESS_RESULT}")" == 400 ]]
! grep -Fq "${SUPABASE_ANON_KEY_VALUE}" "${SUCCESS_RESULT}"

rm -f -- \
  "${HARNESS_MARKER}" \
  "${NODE_ENVIRONMENT}" \
  "${NODE_ARGUMENTS}" \
  "${GUARD_ENVIRONMENT}" \
  "${EXECUTION_ORDER}"

MUTATION_DATABASE_URL='postgresql://postgres.test:password@db.staging.invalid:5432/postgres?sslmode=verify-full'
mutation_output=$(run_mutation_case \
  "${MUTATION_EVIDENCE}" \
  "${MUTATION_DATABASE_URL}")
[[ "${mutation_output}" == *'PASS: retained postgrest-same-id-retry.txt'* ]]
[[ -e "${HARNESS_MARKER}" && ! -e "${POISON_MARKER}" ]]
[[ "$(<"${NODE_ARGUMENTS}")" == "${INTEGRATION_PATH}" ]]
[[ "$(<"${EXECUTION_ORDER}")" == $'guard\nharness' ]]

MUTATION_FIXTURE_MANIFEST="${MUTATION_EVIDENCE}/fixtures-${FIXTURE_RUN_ID}/manifest.json"
EXPECTED_MUTATION_EXPLICIT="${TEST_ROOT}/expected-mutation-explicit-environment.txt"
ACTUAL_MUTATION_EXPLICIT="${TEST_ROOT}/actual-mutation-explicit-environment.txt"
printf '%s\n' \
  'GALLR_EXHIBITION_SOURCE=canonical-v2' \
  'GALLR_EXPECTED_CURSOR=boundary-cursor' \
  'GALLR_EXPECTED_EXHIBITION_COUNT=1205' \
  'GALLR_EXPECTED_FETCH_ATTEMPTS=2' \
  'GALLR_EXPECTED_INTEGRITY_CALLS=2' \
  'GALLR_EXPECTED_MIN_EXHIBITIONS=1001' \
  'GALLR_EXPECTED_MIN_PAGE_REQUESTS=4' \
  "GALLR_EXPECTED_STAGING_PROJECT_REF=${STAGING_REF}" \
  'GALLR_POSTGREST_INTEGRATION=1' \
  'GALLR_POSTGREST_MUTATION_ATTESTATION=network-free-mutation-attestation' \
  "GALLR_POSTGREST_MUTATION_TARGET_ID=gallr-rehearsal-${FIXTURE_RUN_ID}-catalog-0750.mutate,(same-id):한글" \
  "GALLR_POSTGREST_FIXTURE_MANIFEST=${MUTATION_FIXTURE_MANIFEST}" \
  'GALLR_POSTGREST_TARGET=staging' \
  "GALLR_PRODUCTION_PROJECT_REF=${PRODUCTION_REF}" \
  "GALLR_STAGING_DATABASE_URL=${MUTATION_DATABASE_URL}" \
  "GALLR_STAGING_EVIDENCE_DIR=${MUTATION_EVIDENCE}" \
  "GALLR_STAGING_IDENTITY_POLICY_PATH=${IDENTITY_POLICY}" \
  "GALLR_STAGING_REHEARSAL_CONFIRM=${STAGING_REF}" \
  'GALLR_TEST_EVENT_ID=load-event' \
  'GALLR_TEST_FEATURED_ONLY=0' \
  "SUPABASE_ANON_KEY=${SUPABASE_ANON_KEY_VALUE}" \
  "SUPABASE_URL=${SUPABASE_URL_VALUE}" \
  | LC_ALL=C sort >"${EXPECTED_MUTATION_EXPLICIT}"
grep -E '^(GALLR_|SUPABASE_)' "${NODE_ENVIRONMENT}" \
  >"${ACTUAL_MUTATION_EXPLICIT}"
diff -u "${EXPECTED_MUTATION_EXPLICIT}" "${ACTUAL_MUTATION_EXPLICIT}"

grep -Fqx 'HOME=/nonexistent' "${NODE_ENVIRONMENT}"
grep -Fqx 'LANG=C' "${NODE_ENVIRONMENT}"
grep -Fqx 'LC_ALL=C' "${NODE_ENVIRONMENT}"
grep -Fqx 'PATH=/usr/bin:/bin:/usr/sbin:/sbin' "${NODE_ENVIRONMENT}"
grep -Fqx 'TMPDIR=/tmp' "${NODE_ENVIRONMENT}"
for forbidden in \
  DATABASE_URL \
  FIXTURE_POISON_SHOULD_NOT_SURVIVE \
  GALLR_INJECTION_SHOULD_NOT_SURVIVE \
  HTTP_PROXY \
  NODE_DEBUG \
  NODE_OPTIONS \
  NODE_PATH \
  SUPABASE_SECRET_KEY \
  SUPABASE_SERVICE_ROLE_KEY; do
  ! grep -Eq "^${forbidden}=" "${NODE_ENVIRONMENT}"
done

EXPECTED_GUARD_EXPLICIT="${TEST_ROOT}/expected-guard-explicit-environment.txt"
ACTUAL_GUARD_EXPLICIT="${TEST_ROOT}/actual-guard-explicit-environment.txt"
printf '%s\n' \
  "GALLR_EXPECTED_STAGING_PROJECT_REF=${STAGING_REF}" \
  "GALLR_PRODUCTION_PROJECT_REF=${PRODUCTION_REF}" \
  "GALLR_STAGING_DATABASE_URL=${MUTATION_DATABASE_URL}" \
  "GALLR_STAGING_EVIDENCE_DIR=${MUTATION_EVIDENCE}" \
  "GALLR_STAGING_IDENTITY_POLICY_PATH=${IDENTITY_POLICY}" \
  "GALLR_STAGING_REHEARSAL_CONFIRM=${STAGING_REF}" \
  | LC_ALL=C sort >"${EXPECTED_GUARD_EXPLICIT}"
grep '^GALLR_' "${GUARD_ENVIRONMENT}" >"${ACTUAL_GUARD_EXPLICIT}"
diff -u "${EXPECTED_GUARD_EXPLICIT}" "${ACTUAL_GUARD_EXPLICIT}"
for forbidden in \
  DATABASE_URL \
  FIXTURE_POISON_SHOULD_NOT_SURVIVE \
  GALLR_INJECTION_SHOULD_NOT_SURVIVE \
  HTTP_PROXY \
  NODE_DEBUG \
  NODE_OPTIONS \
  NODE_PATH \
  SUPABASE_ANON_KEY \
  SUPABASE_SECRET_KEY \
  SUPABASE_SERVICE_ROLE_KEY \
  SUPABASE_URL; do
  ! grep -Eq "^${forbidden}=" "${GUARD_ENVIRONMENT}"
done

MUTATION_RESULT="${MUTATION_EVIDENCE}/postgrest-same-id-retry.txt"
[[ -f "${MUTATION_RESULT}" \
   && ! -L "${MUTATION_RESULT}" \
   && "$(file_mode "${MUTATION_RESULT}")" == 400 ]]
! grep -Fq "${SUPABASE_ANON_KEY_VALUE}" "${MUTATION_RESULT}"
! grep -Fq 'password' "${MUTATION_RESULT}"

rm -f -- \
  "${HARNESS_MARKER}" \
  "${NODE_ENVIRONMENT}" \
  "${NODE_ARGUMENTS}" \
  "${GUARD_ENVIRONMENT}" \
  "${EXECUTION_ORDER}"

set +e
guard_failure_output=$(run_mutation_case \
  "${GUARD_FAILURE_EVIDENCE}" \
  'postgresql://guard-failure@db.staging.invalid/postgres')
guard_failure_status=$?
set -e
[[ "${guard_failure_status}" -ne 0 ]]
[[ "${guard_failure_output}" == \
   *'ERROR: disposable-clone target identity failed'* ]]
[[ "$(<"${EXECUTION_ORDER}")" == 'guard' ]]
[[ -e "${GUARD_ENVIRONMENT}" \
   && ! -e "${HARNESS_MARKER}" \
   && ! -e "${NODE_ENVIRONMENT}" \
   && ! -e "${NODE_ARGUMENTS}" \
   && ! -e "${POISON_MARKER}" ]]
[[ ! -e "${GUARD_FAILURE_EVIDENCE}/postgrest-same-id-retry.txt" ]]

rm -f -- \
  "${HARNESS_MARKER}" \
  "${NODE_ENVIRONMENT}" \
  "${NODE_ARGUMENTS}" \
  "${GUARD_ENVIRONMENT}" \
  "${EXECUTION_ORDER}"
chmod 0700 "${REVIEWED_NODE}"
printf '%s\n' '# digest changed after preflight review' >>"${REVIEWED_NODE}"
chmod 0500 "${REVIEWED_NODE}"

set +e
tamper_output=$(run_case "${TAMPER_EVIDENCE}")
tamper_status=$?
set -e
[[ "${tamper_status}" -ne 0 ]]
[[ "${tamper_output}" == \
   *'ERROR: reviewed Node.js/psql toolchain does not match the preflight manifest'* ]]
[[ ! -e "${HARNESS_MARKER}" \
   && ! -e "${NODE_ENVIRONMENT}" \
   && ! -e "${NODE_ARGUMENTS}" \
   && ! -e "${POISON_MARKER}" ]]
[[ ! -e "${TAMPER_EVIDENCE}/postgrest-empty-event.txt" ]]

printf '%s\n' 'PostgREST reviewed Node boundary tests passed'
