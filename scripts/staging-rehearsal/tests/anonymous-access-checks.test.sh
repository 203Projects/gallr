#!/usr/bin/env bash

# Offline orchestration coverage for the anonymous catalog checks. The runner,
# URI parser, validator, and psql launcher are real; guards and psql are local
# fakes, so a network connection is impossible.

set -euo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
REHEARSAL_DIR=$(cd "${SCRIPT_DIR}/.." && pwd -P)
TEST_CA_SOURCE="${SCRIPT_DIR}/fixtures/test-root-ca.pem"
PINNED_CA_SHA256='700723581420dd1ac98fd7e9ac529f0ef210eadcaf87fc868a3ad7d114c2f3b7'
REAL_NODE=$(command -v node)

TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/gallr-anonymous-access.XXXXXX")
TEST_ROOT=$(cd "${TEST_ROOT}" && pwd -P)
case "${TEST_ROOT}" in
  /tmp/*|/private/tmp/*|/private/var/*) ;;
  *) printf 'unexpected temporary path\n' >&2; exit 1 ;;
esac
trap 'rm -rf -- "${TEST_ROOT}"' EXIT HUP INT TERM

REPO_ROOT="${TEST_ROOT}/repository"
RUNNER_DIR="${REPO_ROOT}/scripts/staging-rehearsal"
SECURE_ROOT="${TEST_ROOT}/secure"
SUCCESS_EVIDENCE="${TEST_ROOT}/success-evidence"
GUARD_FAILURE_EVIDENCE="${TEST_ROOT}/guard-failure-evidence"
FAKE_BIN="${TEST_ROOT}/bin"
ORDER_LOG="${TEST_ROOT}/order.log"
TRANSPORT_LOG="${TEST_ROOT}/transport.log"
IDENTITY_FAIL_CONTROL="${TEST_ROOT}/identity-fail-on-call"

mkdir -m 700 \
  "${REPO_ROOT}" \
  "${SECURE_ROOT}" \
  "${SUCCESS_EVIDENCE}" \
  "${GUARD_FAILURE_EVIDENCE}" \
  "${FAKE_BIN}"
mkdir -p "${RUNNER_DIR}/lib" "${RUNNER_DIR}/sql"

sha256_file() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
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

file_nlink() {
  local value

  if value=$(stat -f '%l' "$1" 2>/dev/null); then
    printf '%s\n' "${value}"
  elif value=$(stat -c '%h' "$1" 2>/dev/null); then
    printf '%s\n' "${value}"
  else
    return 1
  fi
}

[[ -f "${TEST_CA_SOURCE}" && ! -L "${TEST_CA_SOURCE}" ]] \
  || { printf 'pinned test CA is missing\n' >&2; exit 1; }
[[ "$(sha256_file "${TEST_CA_SOURCE}")" == "${PINNED_CA_SHA256}" ]] \
  || { printf 'pinned test CA digest changed\n' >&2; exit 1; }
TEST_CA_PATH="${SECURE_ROOT}/test-root-ca.pem"
cp "${TEST_CA_SOURCE}" "${TEST_CA_PATH}"
chmod 0400 "${TEST_CA_PATH}"
[[ "$(sha256_file "${TEST_CA_PATH}")" == "${PINNED_CA_SHA256}" ]] \
  || { printf 'copied test CA digest changed\n' >&2; exit 1; }
TEST_CA_URI_PATH="${TEST_CA_PATH//\//%2F}"

cp "${REHEARSAL_DIR}/run-anonymous-access-checks.sh" \
  "${RUNNER_DIR}/run-anonymous-access-checks.sh"
cp "${REHEARSAL_DIR}/lib/database-target.mjs" \
  "${RUNNER_DIR}/lib/database-target.mjs"
cp "${REHEARSAL_DIR}/lib/validate-database-target.mjs" \
  "${RUNNER_DIR}/lib/validate-database-target.mjs"
cp "${REHEARSAL_DIR}/lib/run-psql-with-validated-target.mjs" \
  "${RUNNER_DIR}/lib/run-psql-with-validated-target.mjs"
cp "${REHEARSAL_DIR}/lib/reviewed-toolchain.sh" \
  "${RUNNER_DIR}/lib/reviewed-toolchain.sh"
for sql_name in \
  anonymous-positive.sql \
  anonymous-private-read-must-fail.sql \
  anonymous-catalog-write-must-fail.sql; do
  cp "${REHEARSAL_DIR}/sql/${sql_name}" "${RUNNER_DIR}/sql/${sql_name}"
done

{
  printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail'
  printf 'readonly fake_order_log=%q\n' "${ORDER_LOG}"
  cat <<'EOF'
[[ "${GALLR_EXPECTED_STAGING_PROJECT_REF:-}" == aaaaaaaaaaaaaaaaaaaa ]]
[[ "${GALLR_PRODUCTION_PROJECT_REF:-}" == bbbbbbbbbbbbbbbbbbbb ]]
[[ "${GALLR_STAGING_REHEARSAL_CONFIRM:-}" == aaaaaaaaaaaaaaaaaaaa ]]
printf '%s\n' linked >>"${fake_order_log}"
printf '%s\n' 'PASS: linked project matches the reviewed staging manifest'
EOF
} >"${RUNNER_DIR}/assert-linked-staging.sh"

{
  printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail'
  printf 'readonly fake_order_log=%q\n' "${ORDER_LOG}"
  printf 'readonly fake_identity_fail_control=%q\n' "${IDENTITY_FAIL_CONTROL}"
  cat <<'EOF'
[[ "${GALLR_EXPECTED_STAGING_PROJECT_REF:-}" == aaaaaaaaaaaaaaaaaaaa ]]
[[ "${GALLR_PRODUCTION_PROJECT_REF:-}" == bbbbbbbbbbbbbbbbbbbb ]]
[[ "${GALLR_STAGING_REHEARSAL_CONFIRM:-}" == aaaaaaaaaaaaaaaaaaaa ]]
[[ "${GALLR_STAGING_DATABASE_URL:-}" == postgresql://* ]]
[[ -f "${GALLR_STAGING_IDENTITY_POLICY_PATH:-}" ]]
identity_count=$(grep -Ec '^identity' "${fake_order_log}" 2>/dev/null || true)
identity_count=$((identity_count + 1))
if [[ "$(<"${fake_identity_fail_control}")" == "${identity_count}" ]]; then
  printf '%s\n' identity-fail >>"${fake_order_log}"
  exit 79
fi
printf '%s\n' identity >>"${fake_order_log}"
printf '%s\n' \
  'PASS: independent policy and disposable-clone marker identify staging'
EOF
} >"${RUNNER_DIR}/assert-disposable-clone-target.sh"

{
  printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail'
  printf 'readonly real_node=%q\n' "${REAL_NODE}"
  cat <<'EOF'
for forbidden in \
  staging_database_url staging_ref_raw production_ref_raw \
  DATABASE_URL SUPABASE_ANON_KEY SUPABASE_SERVICE_ROLE_KEY; do
  [[ "${!forbidden+x}" != x ]] || exit 69
done
exec "${real_node}" "$@"
EOF
} >"${FAKE_BIN}/node"

{
  printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail'
  printf 'readonly fake_order_log=%q\n' "${ORDER_LOG}"
  printf 'readonly fake_transport_log=%q\n' "${TRANSPORT_LOG}"
  printf 'readonly fake_source_ca_path=%q\n' "${TEST_CA_PATH}"
  cat <<'EOF'
portable_stat_mode() {
  local value

  if value=$(stat -f '%Lp' "$1" 2>/dev/null); then
    printf '%s\n' "${value}"
  elif value=$(stat -c '%a' "$1" 2>/dev/null); then
    printf '%s\n' "${value}"
  else
    return 1
  fi
}

sql_file=
[[ $# -ge 2 && "$1" == '-X' && "$2" == '--no-password' ]] || exit 74
for argument in "$@"; do
  [[ "${argument}" != *postgresql://* && "${argument}" != *postgres://* ]] \
    || exit 70
  [[ "${argument}" != *aaaaaaaaaaaaaaaaaaaa* \
     && "${argument}" != *bbbbbbbbbbbbbbbbbbbb* ]] || exit 71
done
while [[ $# -gt 0 ]]; do
  case "$1" in
    -f)
      shift
      [[ $# -gt 0 ]] || exit 72
      sql_file="$1"
      ;;
  esac
  shift
done

case "${sql_file}" in
  */anonymous-positive.sql)
    case_name=positive
    expected_appname=gallr_staging_anonymous_positive
    ;;
  */anonymous-private-read-must-fail.sql)
    case_name=private-denial
    expected_appname=gallr_staging_anonymous-private-read-denied
    ;;
  */anonymous-catalog-write-must-fail.sql)
    case_name=catalog-denial
    expected_appname=gallr_staging_anonymous-catalog-write-denied
    ;;
  *) exit 73 ;;
esac
printf 'psql:%s\n' "${case_name}" >>"${fake_order_log}"

[[ "${PGHOST:-}" == db.aaaaaaaaaaaaaaaaaaaa.supabase.co ]] || exit 75
[[ "${PGPORT:-}" == 5432 \
   && "${PGDATABASE:-}" == postgres \
   && "${PGUSER:-}" == postgres ]] || exit 76
[[ "${PGSSLMODE:-}" == verify-full \
   && "${PGGSSENCMODE:-}" == disable \
   && "${PGSSLCERTMODE:-}" == disable \
   && "${PGAPPNAME:-}" == "${expected_appname}" \
   && "${PGCONNECT_TIMEOUT:-}" == 10 ]] || exit 77
[[ -z "${PGOPTIONS+x}" \
   && -z "${PGPASSWORD+x}" \
   && -z "${PGHOSTADDR+x}" \
   && -z "${PGSERVICE+x}" \
   && -z "${PGSERVICEFILE+x}" ]] || exit 78

for forbidden in \
  GALLR_STAGING_DATABASE_URL GALLR_VALIDATION_DATABASE_URL \
  GALLR_VALIDATION_PROJECT_REF GALLR_VALIDATION_REQUIRE_DIRECT \
  GALLR_VALIDATION_SSLROOTCERT_SHA256 GALLR_PSQL_APPNAME \
  GALLR_PSQL_CONNECT_TIMEOUT GALLR_PSQL_OPTIONS \
  GALLR_PSQL_FUTURE_POISON SUPABASE_SECRET_KEY DATABASE_URL; do
  [[ "${!forbidden+x}" != x ]] || exit 80
done
while IFS='=' read -r environment_name environment_value; do
  [[ "${environment_name}" != FAKE_* \
     && "${environment_name}" != GALLR_* \
     && "${environment_name}" != SUPABASE_* ]] || exit 81
  [[ "${environment_value}" != *postgresql://* \
     && "${environment_value}" != *postgres://* ]] || exit 81
  [[ "${environment_value}" != *'test:pass\word'* ]] || exit 82
done < <(env)

[[ -n "${PGPASSFILE:-}" \
   && "${PGPASSFILE}" != /dev/null \
   && -f "${PGPASSFILE}" \
   && ! -L "${PGPASSFILE}" \
   && -O "${PGPASSFILE}" ]] || exit 83
[[ "$(portable_stat_mode "${PGPASSFILE}")" == 600 ]] \
  || exit 84
expected_passfile='db.aaaaaaaaaaaaaaaaaaaa.supabase.co:5432:postgres:postgres:test\:pass\\word'
[[ "$(<"${PGPASSFILE}")" == "${expected_passfile}" ]] || exit 85

[[ -n "${PGSSLROOTCERT:-}" \
   && "${PGSSLROOTCERT}" != "${fake_source_ca_path}" \
   && -f "${PGSSLROOTCERT}" \
   && ! -L "${PGSSLROOTCERT}" \
   && -O "${PGSSLROOTCERT}" ]] || exit 86
certificate_mode=$(portable_stat_mode "${PGSSLROOTCERT}")
certificate_parent=$(dirname "${PGSSLROOTCERT}")
certificate_parent_mode=$(portable_stat_mode "${certificate_parent}")
[[ "${certificate_mode}" == 400 && "${certificate_parent_mode}" == 700 ]] \
  || exit 87
cmp -s "${PGSSLROOTCERT}" "${fake_source_ca_path}" || exit 88
[[ "$(sha256_file=$(if command -v shasum >/dev/null 2>&1; then
      shasum -a 256 "${PGSSLROOTCERT}" | awk '{print $1}'
    else
      sha256sum "${PGSSLROOTCERT}" | awk '{print $1}'
    fi); printf '%s' "${sha256_file}")" == \
   700723581420dd1ac98fd7e9ac529f0ef210eadcaf87fc868a3ad7d114c2f3b7 ]] \
  || exit 89
printf '%s\n' "${certificate_parent}" >>"${fake_transport_log}"

case "${case_name}" in
  positive)
    printf '%s\n' 'GALLR_ANON_ROLE_ASSUMED positive-read'
    exit 0
    ;;
  private-denial|catalog-denial)
    printf '%s\n' 'GALLR_ANON_ROLE_ASSUMED'
    printf '%s\n' 'ERROR:  42501: permission denied for anonymous role' >&2
    exit 3
    ;;
esac
EOF
} >"${FAKE_BIN}/psql"

chmod +x \
  "${RUNNER_DIR}/run-anonymous-access-checks.sh" \
  "${RUNNER_DIR}/assert-linked-staging.sh" \
  "${RUNNER_DIR}/assert-disposable-clone-target.sh" \
  "${FAKE_BIN}/node" \
  "${FAKE_BIN}/psql"

FAKE_NODE_PATH="$(cd "${FAKE_BIN}" && pwd -P)/node"
FAKE_PSQL_PATH="$(cd "${FAKE_BIN}" && pwd -P)/psql"
FAKE_NODE_SHA256=$(sha256_file "${FAKE_NODE_PATH}")
FAKE_PSQL_SHA256=$(sha256_file "${FAKE_PSQL_PATH}")
for manifest_path in \
  "${SUCCESS_EVIDENCE}/operator-manifest.txt" \
  "${GUARD_FAILURE_EVIDENCE}/operator-manifest.txt"; do
  printf '%s\n' \
    'operator_manifest=test-fixture' \
    "reviewed_node_path=${FAKE_NODE_PATH}" \
    "reviewed_node_sha256=${FAKE_NODE_SHA256}" \
    "reviewed_psql_path=${FAKE_PSQL_PATH}" \
    "reviewed_psql_sha256=${FAKE_PSQL_SHA256}" \
    >"${manifest_path}"
  chmod 0444 "${manifest_path}"
done
printf '%s\n' 'identity_policy=test-fixture' \
  >"${SECURE_ROOT}/identity-policy.txt"
chmod 0400 "${SECURE_ROOT}/identity-policy.txt"

GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 \
  git -C "${REPO_ROOT}" init -q
git -C "${REPO_ROOT}" config user.name 'Anonymous Access Test'
git -C "${REPO_ROOT}" config user.email 'anonymous-access@example.test'
git -C "${REPO_ROOT}" config commit.gpgsign false
git -C "${REPO_ROOT}" add scripts
git -c core.hooksPath=/dev/null -C "${REPO_ROOT}" \
  commit -qm 'test anonymous access harness'

STAGING_REF='aaaaaaaaaaaaaaaaaaaa'
PRODUCTION_REF='bbbbbbbbbbbbbbbbbbbb'
ENCODED_DATABASE_PASSWORD='test%3Apass%5Cword'
DATABASE_URL="postgresql://postgres:${ENCODED_DATABASE_PASSWORD}@db.${STAGING_REF}.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=${TEST_CA_URI_PATH}"
RUNNER="${RUNNER_DIR}/run-anonymous-access-checks.sh"

run_case() {
  local evidence_dir="$1"
  local fail_identity_on_call="$2"
  printf '%s\n' "${fail_identity_on_call}" >"${IDENTITY_FAIL_CONTROL}"
  env \
    "PATH=${FAKE_BIN}:${PATH}" \
    "GALLR_EXPECTED_STAGING_PROJECT_REF=${STAGING_REF}" \
    "GALLR_PRODUCTION_PROJECT_REF=${PRODUCTION_REF}" \
    "GALLR_STAGING_DATABASE_URL=${DATABASE_URL}" \
    "GALLR_STAGING_REHEARSAL_CONFIRM=${STAGING_REF}" \
    "GALLR_STAGING_EVIDENCE_DIR=${evidence_dir}" \
    "GALLR_STAGING_IDENTITY_POLICY_PATH=${SECURE_ROOT}/identity-policy.txt" \
    "GALLR_PSQL_FUTURE_POISON=must-not-reach-psql" \
    "SUPABASE_SECRET_KEY=must-not-reach-psql" \
    "DATABASE_URL=${DATABASE_URL}" \
    "PGHOST=production.invalid" \
    "PGHOSTADDR=127.0.0.1" \
    "PGDATABASE=production" \
    "PGUSER=production" \
    "PGPASSWORD=must-not-reach-psql" \
    "PGOPTIONS=-c role=anon" \
    bash "${RUNNER}" 2>&1
}

assert_no_sensitive_output() {
  local value="$1"
  local label="$2"
  [[ "${value}" != *"${DATABASE_URL}"* \
     && "${value}" != *"${STAGING_REF}"* \
     && "${value}" != *"${PRODUCTION_REF}"* \
     && "${value}" != *'test:pass\word'* ]] || {
    printf '%s disclosed target credentials\n' "${label}" >&2
    exit 1
  }
}

assert_evidence_set() {
  local evidence_dir="$1"
  local evidence_path
  for evidence_path in \
    "${evidence_dir}/anonymous-positive.txt" \
    "${evidence_dir}/anonymous-private-read-denied.txt" \
    "${evidence_dir}/anonymous-catalog-write-denied.txt"; do
    [[ -f "${evidence_path}" \
       && ! -L "${evidence_path}" \
       && -O "${evidence_path}" \
       && "$(file_mode "${evidence_path}")" == 400 \
       && "$(file_nlink "${evidence_path}")" == 1 ]] || {
      printf 'anonymous evidence was not sealed safely\n' >&2
      exit 1
    }
    grep -Fqx 'database_url_recorded=false' "${evidence_path}"
    grep -Fqx \
      'target_identity_guard=independent_policy_and_database_marker_passed' \
      "${evidence_path}"
    ! grep -Fq "${DATABASE_URL}" "${evidence_path}"
    ! grep -Fq "${STAGING_REF}" "${evidence_path}"
    ! grep -Fq "${PRODUCTION_REF}" "${evidence_path}"
    ! grep -Fq 'test:pass\word' "${evidence_path}"
  done
}

: >"${ORDER_LOG}"
: >"${TRANSPORT_LOG}"
success_output=$(run_case "${SUCCESS_EVIDENCE}" 0)
assert_no_sensitive_output "${success_output}" 'successful anonymous run'
[[ "${success_output}" == \
  'Anonymous positive read and both independent SQLSTATE 42501 denials passed.' ]]
expected_success_order=$'linked\nidentity\npsql:positive\npsql:private-denial\nidentity\npsql:catalog-denial'
[[ "$(<"${ORDER_LOG}")" == "${expected_success_order}" ]] || {
  printf 'anonymous access ordering changed\n' >&2
  exit 1
}
[[ "$(grep -c '^psql:' "${ORDER_LOG}")" == 3 ]]
[[ "$(grep -c '^identity$' "${ORDER_LOG}")" == 2 ]]
[[ "$(wc -l <"${TRANSPORT_LOG}" | tr -d ' ')" == 3 ]]
while IFS= read -r transport_directory; do
  [[ -n "${transport_directory}" && ! -e "${transport_directory}" ]] || {
    printf 'validated psql transport directory was not removed\n' >&2
    exit 1
  }
done <"${TRANSPORT_LOG}"
assert_evidence_set "${SUCCESS_EVIDENCE}"
grep -Fq 'GALLR_ANON_ROLE_ASSUMED positive-read' \
  "${SUCCESS_EVIDENCE}/anonymous-positive.txt"
for denial_path in \
  "${SUCCESS_EVIDENCE}/anonymous-private-read-denied.txt" \
  "${SUCCESS_EVIDENCE}/anonymous-catalog-write-denied.txt"; do
  grep -Fq 'GALLR_ANON_ROLE_ASSUMED' "${denial_path}"
  grep -Eq 'ERROR:[[:space:]]+42501:' "${denial_path}"
  ! grep -Fq 'GALLR_EXPECTED_DENIAL_DID_NOT_OCCUR' "${denial_path}"
done

: >"${ORDER_LOG}"
: >"${TRANSPORT_LOG}"
set +e
guard_failure_output=$(run_case "${GUARD_FAILURE_EVIDENCE}" 2)
guard_failure_status=$?
set -e
[[ "${guard_failure_status}" -ne 0 ]]
assert_no_sensitive_output "${guard_failure_output}" 'second-guard failure'
[[ "${guard_failure_output}" == \
  *'ERROR: disposable-clone target identity failed'* ]]
expected_failure_order=$'linked\nidentity\npsql:positive\npsql:private-denial\nidentity-fail'
[[ "$(<"${ORDER_LOG}")" == "${expected_failure_order}" ]] || {
  printf 'second identity-guard failure did not stop catalog psql\n' >&2
  exit 1
}
[[ "$(grep -c '^psql:' "${ORDER_LOG}")" == 2 ]]
! grep -Fq 'psql:catalog-denial' "${ORDER_LOG}"
assert_evidence_set "${GUARD_FAILURE_EVIDENCE}"

printf '%s\n' 'anonymous access orchestration tests passed'
