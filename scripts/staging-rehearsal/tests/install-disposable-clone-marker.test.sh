#!/usr/bin/env bash

# Network-free adversarial tests for the disposable-clone marker bootstrap.
# The production validators run against a synthetic clean Git repository; node,
# git, and psql shims provide deterministic race/failure injection without DNS
# or a PostgreSQL connection.

set -euo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
REHEARSAL_DIR=$(cd "${SCRIPT_DIR}/.." && pwd -P)
REAL_GIT=$(command -v git)
REAL_NODE=$(command -v node)

TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/gallr-marker-installer.XXXXXX")
TEST_ROOT=$(cd "${TEST_ROOT}" && pwd -P)
case "${TEST_ROOT}" in
  /tmp/*|/private/tmp/*|/private/var/*) ;;
  *) printf 'unexpected temporary path\n' >&2; exit 1 ;;
esac
trap 'rm -rf -- "${TEST_ROOT}"' EXIT HUP INT TERM

REPO_ROOT="${TEST_ROOT}/repository"
RUNNER_DIR="${REPO_ROOT}/scripts/staging-rehearsal"
SECURE_ROOT="${TEST_ROOT}/secure"
EVIDENCE_PARENT="${TEST_ROOT}/evidence"
FAKE_BIN="${TEST_ROOT}/bin"
PSQL_LOG="${TEST_ROOT}/psql.log"
NODE_LOG="${TEST_ROOT}/node.log"
SANITIZE_LOG="${TEST_ROOT}/sanitize.log"

mkdir -m 700 \
  "${REPO_ROOT}" \
  "${SECURE_ROOT}" \
  "${EVIDENCE_PARENT}" \
  "${FAKE_BIN}"
mkdir -p \
  "${RUNNER_DIR}/lib" \
  "${RUNNER_DIR}/sql" \
  "${REPO_ROOT}/supabase/migrations" \
  "${REPO_ROOT}/supabase/.temp"

cp "${REHEARSAL_DIR}/install-disposable-clone-marker.sh" \
  "${RUNNER_DIR}/install-disposable-clone-marker.sh"
cp "${REHEARSAL_DIR}/assert-linked-staging.sh" \
  "${RUNNER_DIR}/assert-linked-staging.sh"
cp "${REHEARSAL_DIR}/lib/validate-database-target.mjs" \
  "${RUNNER_DIR}/lib/validate-database-target.mjs"
cp "${REHEARSAL_DIR}/lib/validate-target-identity-policy.mjs" \
  "${RUNNER_DIR}/lib/validate-target-identity-policy.mjs"
cp "${REHEARSAL_DIR}/sql/install-disposable-clone-marker.sql" \
  "${RUNNER_DIR}/sql/install-disposable-clone-marker.sql"

chmod +x \
  "${RUNNER_DIR}/install-disposable-clone-marker.sh" \
  "${RUNNER_DIR}/assert-linked-staging.sh"
printf '%s\n' 'supabase/.temp/' > "${REPO_ROOT}/.gitignore"
printf '%s\n' '-- marker installer test migration' \
  > "${REPO_ROOT}/supabase/migrations/20260722000000_test.sql"

git -C "${REPO_ROOT}" init -q
git -C "${REPO_ROOT}" config user.name 'Marker Installer Test'
git -C "${REPO_ROOT}" config user.email 'marker-installer@example.test'
git -C "${REPO_ROOT}" add .gitignore scripts supabase/migrations
git -C "${REPO_ROOT}" commit -qm 'marker installer fixture'

STAGING_REF='ssssssssssssssssssss'
PRODUCTION_REF='pppppppppppppppppppp'
COMMIT=$(git -C "${REPO_ROOT}" rev-parse HEAD)
DATABASE_URL="postgresql://postgres:test@db.${STAGING_REF}.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=%2Ftmp%2Fgallr-staging-root-ca.pem"
PRODUCTION_DATABASE_URL="postgresql://postgres:test@db.${PRODUCTION_REF}.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=%2Ftmp%2Fgallr-production-root-ca.pem"
POOLER_DATABASE_URL="postgresql://postgres.${STAGING_REF}:test@aws-0-test.pooler.supabase.com:5432/postgres?sslmode=verify-full&sslrootcert=%2Ftmp%2Fgallr-staging-root-ca.pem"
INSTALL_CONFIRMATION="INSTALL_GALLR_DISPOSABLE_CLONE_MARKER:${STAGING_REF}:${COMMIT}"
CHANGE_RECORD='CHG-MARKER-001'
EXECUTOR='executor@example.test'
REVIEWER='reviewer@example.test'
APPROVER_TWO='identity@example.test'
MARKER_ID='123e4567-e89b-42d3-a456-426614174000'
MIGRATION_PATH="${REPO_ROOT}/supabase/migrations/20260722000000_test.sql"
LINKED_REF_PATH="${REPO_ROOT}/supabase/.temp/project-ref"
INSTALLER="${RUNNER_DIR}/install-disposable-clone-marker.sh"
MARKER_SQL="${RUNNER_DIR}/sql/install-disposable-clone-marker.sql"
POLICY_PATH="${SECURE_ROOT}/identity-policy.txt"
MANIFEST_TEMPLATE="${TEST_ROOT}/operator-manifest.txt"

sha256_text() {
  if command -v shasum >/dev/null 2>&1; then
    printf '%s' "$1" | shasum -a 256 | awk '{print $1}'
  else
    printf '%s' "$1" | sha256sum | awk '{print $1}'
  fi
}

sha256_file() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

file_mode() {
  stat -f '%Lp' "$1" 2>/dev/null || stat -c '%a' "$1"
}

file_nlink() {
  stat -f '%l' "$1" 2>/dev/null || stat -c '%h' "$1"
}

utc_after() {
  "${REAL_NODE}" -e \
    'process.stdout.write(new Date(Date.now() + Number(process.argv[1])).toISOString().replace(/\.\d{3}Z$/, "Z"))' \
    -- "$1"
}

STAGING_SHA256=$(sha256_text "${STAGING_REF}")
PRODUCTION_SHA256=$(sha256_text "${PRODUCTION_REF}")
MIGRATION_SHA256=$(sha256_file "${MIGRATION_PATH}")

printf '%s\n' "${STAGING_REF}" > "${LINKED_REF_PATH}"
{
  printf 'manifest_schema=1\n'
  printf 'run_id=marker-installer-test\n'
  printf 'generated_at_utc=%s\n' "$(utc_after -60000)"
  printf 'target=staging\n'
  printf 'change_record=%s\n' "${CHANGE_RECORD}"
  printf 'executor=%s\n' "${EXECUTOR}"
  printf 'reviewer=%s\n' "${REVIEWER}"
  printf 'repository_commit=%s\n' "${COMMIT}"
  printf 'staging_project_ref_sha256=%s\n' "${STAGING_SHA256}"
  printf 'production_project_ref_sha256=%s\n' "${PRODUCTION_SHA256}"
  printf 'migration_count=1\n'
  printf '\n[migration_sha256]\n'
  printf '%s  supabase/migrations/20260722000000_test.sql\n' \
    "${MIGRATION_SHA256}"
} > "${MANIFEST_TEMPLATE}"
chmod 0444 "${MANIFEST_TEMPLATE}"
MANIFEST_SHA256=$(sha256_file "${MANIFEST_TEMPLATE}")

{
  printf 'policy_schema=1\n'
  printf 'policy_kind=gallr_disposable_clone_target\n'
  printf 'issued_at_utc=%s\n' "$(utc_after -60000)"
  printf 'valid_until_utc=%s\n' "$(utc_after 3600000)"
  printf 'staging_project_ref_sha256=%s\n' "${STAGING_SHA256}"
  printf 'production_project_ref_sha256=%s\n' "${PRODUCTION_SHA256}"
  printf 'repository_commit=%s\n' "${COMMIT}"
  printf 'operator_manifest_sha256=%s\n' "${MANIFEST_SHA256}"
  printf 'change_record=%s\n' "${CHANGE_RECORD}"
  printf 'approver_one=%s\n' "${REVIEWER}"
  printf 'approver_two=%s\n' "${APPROVER_TWO}"
  printf 'marker_id=%s\n' "${MARKER_ID}"
} > "${POLICY_PATH}"
chmod 0400 "${POLICY_PATH}"
POLICY_SHA256=$(sha256_file "${POLICY_PATH}")
cp "${POLICY_PATH}" "${TEST_ROOT}/policy.backup"
cp "${MARKER_SQL}" "${TEST_ROOT}/marker-sql.backup"

cat > "${FAKE_BIN}/git" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ ! -e "${FAKE_SANITIZE_LOG}" ]]; then
  [[ -z "${GALLR_EXPECTED_STAGING_PROJECT_REF+x}" ]] || exit 61
  [[ -z "${GALLR_PRODUCTION_PROJECT_REF+x}" ]] || exit 62
  [[ -z "${GALLR_STAGING_DATABASE_URL+x}" ]] || exit 63
  [[ -z "${GALLR_STAGING_REHEARSAL_CONFIRM+x}" ]] || exit 64
  [[ -z "${GALLR_STAGING_EVIDENCE_DIR+x}" ]] || exit 65
  [[ -z "${GALLR_STAGING_IDENTITY_POLICY_PATH+x}" ]] || exit 66
  [[ -z "${GALLR_REVIEWED_COMMIT+x}" ]] || exit 67
  [[ -z "${GALLR_DISPOSABLE_CLONE_MARKER_INSTALL_CONFIRMATION+x}" ]] || exit 68
  [[ -z "${GALLR_VALIDATION_DATABASE_URL+x}" ]] || exit 69
  [[ -z "${GALLR_IDENTITY_POLICY_PATH+x}" ]] || exit 70
  [[ -z "${DATABASE_URL+x}" ]] || exit 71
  [[ -z "${SUPABASE_SERVICE_ROLE_KEY+x}" ]] || exit 72
  [[ -z "${PGPASSWORD+x}" && -z "${PGHOST+x}" ]] || exit 73
  [[ -z "${database_url+x}" && -z "${staging_ref+x}" ]] || exit 74
  [[ -z "${production_ref+x}" && -z "${policy_record+x}" ]] || exit 75
  [[ -z "${marker_id+x}" && -z "${current_commit+x}" ]] || exit 76
  [[ -z "${expected_install_confirmation+x}" ]] || exit 77
  [[ -z "${bootstrap_database_url+x}" ]] || exit 78
  printf '%s\n' sanitized > "${FAKE_SANITIZE_LOG}"
fi

exec "${REAL_GIT}" "$@"
EOF

cat > "${FAKE_BIN}/node" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

target=${1:-}
case "${target}" in
  */validate-database-target.mjs)
    printf '%s\n' database >> "${FAKE_NODE_LOG}"
    database_count=$(grep -c '^database$' "${FAKE_NODE_LOG}")
    if [[ "${FAKE_NODE_MODE:-normal}" == final-url-fail && \
          "${database_count}" -eq 2 ]]; then
      exit 81
    fi
    set +e
    "${REAL_NODE}" "$@"
    status=$?
    set -e
    if [[ "${status}" -eq 0 && \
          "${FAKE_NODE_MODE:-normal}" == mutate-sql-after-final-url && \
          "${database_count}" -eq 2 ]]; then
      chmod 0600 "${FAKE_MARKER_SQL}"
      printf '%s\n' '-- adversarial late mutation' >> "${FAKE_MARKER_SQL}"
    fi
    exit "${status}"
    ;;
  */validate-target-identity-policy.mjs)
    printf '%s\n' policy >> "${FAKE_NODE_LOG}"
    policy_count=$(grep -c '^policy$' "${FAKE_NODE_LOG}")
    if [[ "${FAKE_NODE_MODE:-normal}" == malformed-policy-record ]]; then
      record=$("${REAL_NODE}" "$@")
      printf '%s\textra\n' "${record}"
      exit 0
    fi
    if [[ "${FAKE_NODE_MODE:-normal}" == policy-change && \
          "${policy_count}" -eq 2 ]]; then
      chmod 0600 "${FAKE_POLICY_PATH}"
      printf '%s\n' 'changed=true' >> "${FAKE_POLICY_PATH}"
    fi
    set +e
    "${REAL_NODE}" "$@"
    status=$?
    set -e
    if [[ "${status}" -eq 0 && \
          "${FAKE_NODE_MODE:-normal}" == link-change && \
          "${policy_count}" -eq 2 ]]; then
      printf '%s\n' "${FAKE_PRODUCTION_REF}" > "${FAKE_LINKED_REF_PATH}"
    fi
    exit "${status}"
    ;;
  *) exec "${REAL_NODE}" "$@" ;;
esac
EOF

cat > "${FAKE_BIN}/psql" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' called >> "${FAKE_PSQL_LOG}"
[[ "${PGDATABASE:-}" == "${FAKE_EXPECTED_DATABASE_URL}" ]] || exit 91
[[ "${PGSSLMODE:-}" == verify-full ]] || exit 92
[[ "${PGPASSFILE:-}" == /dev/null ]] || exit 93
[[ "${PGCONNECT_TIMEOUT:-}" == 15 ]] || exit 94
[[ "${PGOPTIONS:-}" == *statement_timeout=15000* ]] || exit 95
[[ "${PGOPTIONS:-}" == *lock_timeout=3000* ]] || exit 96
[[ -z "${PGHOST+x}" && -z "${PGHOSTADDR+x}" ]] || exit 97
[[ -z "${PGPASSWORD+x}" && -z "${PGSERVICE+x}" ]] || exit 98
[[ -z "${GALLR_STAGING_DATABASE_URL+x}" ]] || exit 99
[[ -z "${GALLR_EXPECTED_STAGING_PROJECT_REF+x}" ]] || exit 100
[[ -z "${GALLR_DISPOSABLE_CLONE_MARKER_INSTALL_CONFIRMATION+x}" ]] || exit 101
[[ -z "${DATABASE_URL+x}" && -z "${SUPABASE_SERVICE_ROLE_KEY+x}" ]] || exit 102
[[ -z "${database_url+x}" && -z "${staging_ref+x}" ]] || exit 103
[[ -z "${policy_record+x}" && -z "${marker_id+x}" ]] || exit 104

installation_confirmation=
marker_id_value=
staging_sha=
production_sha=
commit_value=
manifest_sha=
policy_sha=
sql_file=
for argument in "$@"; do
  [[ "${argument}" != *postgresql://* && "${argument}" != *postgres://* ]] || exit 105
  [[ "${argument}" != *"${FAKE_STAGING_REF}"* ]] || exit 106
  [[ "${argument}" != *"${FAKE_PRODUCTION_REF}"* ]] || exit 107
  case "${argument}" in
    installation_confirmation=*) installation_confirmation=${argument#*=} ;;
    marker_id=*) marker_id_value=${argument#*=} ;;
    staging_ref_sha256=*) staging_sha=${argument#*=} ;;
    production_ref_sha256=*) production_sha=${argument#*=} ;;
    repository_commit=*) commit_value=${argument#*=} ;;
    operator_manifest_sha256=*) manifest_sha=${argument#*=} ;;
    policy_sha256=*) policy_sha=${argument#*=} ;;
    */install-disposable-clone-marker.sql) sql_file=${argument} ;;
  esac
done

[[ "${installation_confirmation}" == INSTALL_GALLR_DISPOSABLE_CLONE_MARKER ]] || exit 108
[[ "${marker_id_value}" == "${FAKE_EXPECTED_MARKER_ID}" ]] || exit 109
[[ "${staging_sha}" == "${FAKE_EXPECTED_STAGING_SHA}" ]] || exit 110
[[ "${production_sha}" == "${FAKE_EXPECTED_PRODUCTION_SHA}" ]] || exit 111
[[ "${commit_value}" == "${FAKE_EXPECTED_COMMIT}" ]] || exit 112
[[ "${manifest_sha}" == "${FAKE_EXPECTED_MANIFEST_SHA}" ]] || exit 113
[[ "${policy_sha}" == "${FAKE_EXPECTED_POLICY_SHA}" ]] || exit 114
[[ "${sql_file}" == "${FAKE_MARKER_SQL}" ]] || exit 115

[[ "${FAKE_PSQL_MODE:-success}" != fail ]] || exit 116
printf '%s\n' 'simulated marker installation'
EOF

chmod +x "${FAKE_BIN}/git" "${FAKE_BIN}/node" "${FAKE_BIN}/psql"

case_number=0
CURRENT_EVIDENCE_DIR=
prepare_evidence() {
  local label="$1"
  case_number=$((case_number + 1))
  CURRENT_EVIDENCE_DIR="${EVIDENCE_PARENT}/${case_number}-${label}"
  mkdir -m 700 "${CURRENT_EVIDENCE_DIR}"
  cp "${MANIFEST_TEMPLATE}" "${CURRENT_EVIDENCE_DIR}/operator-manifest.txt"
  chmod 0444 "${CURRENT_EVIDENCE_DIR}/operator-manifest.txt"
}

reset_mutations() {
  chmod 0600 "${POLICY_PATH}" 2>/dev/null || true
  cp "${TEST_ROOT}/policy.backup" "${POLICY_PATH}"
  chmod 0400 "${POLICY_PATH}"
  cp "${TEST_ROOT}/marker-sql.backup" "${MARKER_SQL}"
  printf '%s\n' "${STAGING_REF}" > "${LINKED_REF_PATH}"
}

psql_calls() {
  wc -l < "${PSQL_LOG}" | tr -d ' '
}

run_installer() {
  local database_url="${1:-${DATABASE_URL}}"
  local confirmation="${2:-${INSTALL_CONFIRMATION}}"
  local node_mode="${3:-normal}"
  local psql_mode="${4:-success}"
  local reviewed_commit="${5:-${COMMIT}}"

  : > "${PSQL_LOG}"
  : > "${NODE_LOG}"
  rm -f -- "${SANITIZE_LOG}"

  env -i \
    "PATH=${FAKE_BIN}:${PATH}" \
    "REAL_GIT=${REAL_GIT}" \
    "REAL_NODE=${REAL_NODE}" \
    "FAKE_SANITIZE_LOG=${SANITIZE_LOG}" \
    "FAKE_NODE_LOG=${NODE_LOG}" \
    "FAKE_NODE_MODE=${node_mode}" \
    "FAKE_PSQL_LOG=${PSQL_LOG}" \
    "FAKE_PSQL_MODE=${psql_mode}" \
    "FAKE_EXPECTED_DATABASE_URL=${database_url}" \
    "FAKE_EXPECTED_MARKER_ID=${MARKER_ID}" \
    "FAKE_EXPECTED_STAGING_SHA=${STAGING_SHA256}" \
    "FAKE_EXPECTED_PRODUCTION_SHA=${PRODUCTION_SHA256}" \
    "FAKE_EXPECTED_COMMIT=${COMMIT}" \
    "FAKE_EXPECTED_MANIFEST_SHA=${MANIFEST_SHA256}" \
    "FAKE_EXPECTED_POLICY_SHA=${POLICY_SHA256}" \
    "FAKE_STAGING_REF=${STAGING_REF}" \
    "FAKE_PRODUCTION_REF=${PRODUCTION_REF}" \
    "FAKE_POLICY_PATH=${POLICY_PATH}" \
    "FAKE_MARKER_SQL=${MARKER_SQL}" \
    "FAKE_LINKED_REF_PATH=${LINKED_REF_PATH}" \
    "GALLR_EXPECTED_STAGING_PROJECT_REF=${STAGING_REF}" \
    "GALLR_PRODUCTION_PROJECT_REF=${PRODUCTION_REF}" \
    "GALLR_STAGING_DATABASE_URL=${database_url}" \
    "GALLR_STAGING_REHEARSAL_CONFIRM=${STAGING_REF}" \
    "GALLR_STAGING_EVIDENCE_DIR=${CURRENT_EVIDENCE_DIR}" \
    "GALLR_STAGING_IDENTITY_POLICY_PATH=${POLICY_PATH}" \
    "GALLR_REVIEWED_COMMIT=${reviewed_commit}" \
    "GALLR_DISPOSABLE_CLONE_MARKER_INSTALL_CONFIRMATION=${confirmation}" \
    "DATABASE_URL=${PRODUCTION_DATABASE_URL}" \
    'SUPABASE_SERVICE_ROLE_KEY=poison-service-key' \
    'SUPABASE_ACCESS_TOKEN=poison-access-token' \
    'PGHOST=production.invalid' \
    'PGPASSWORD=poison-password' \
    'PGSERVICE=poison-service' \
    "GALLR_VALIDATION_DATABASE_URL=${PRODUCTION_DATABASE_URL}" \
    "GALLR_IDENTITY_POLICY_PATH=${TEST_ROOT}/poison-policy" \
    "database_url=${PRODUCTION_DATABASE_URL}" \
    "staging_ref=${PRODUCTION_REF}" \
    "production_ref=${STAGING_REF}" \
    'policy_record=poison-policy-record' \
    'marker_id=poison-marker-id' \
    'current_commit=poison-commit' \
    "expected_install_confirmation=${PRODUCTION_REF}" \
    "bootstrap_database_url=${PRODUCTION_DATABASE_URL}" \
    'GIT_DIR=/poison/git-dir' \
    'BASH_ENV=/dev/null' \
    bash "${INSTALLER}" 2>&1
}

assert_no_sensitive_output() {
  local output="$1"
  local label="$2"
  [[ "${output}" != *"${STAGING_REF}"* \
     && "${output}" != *"${PRODUCTION_REF}"* \
     && "${output}" != *postgresql://* \
     && "${output}" != *poison-password* ]] || {
    printf '%s disclosed a raw target or credential\n' "${label}" >&2
    exit 1
  }
}

assert_partial_evidence_sealed() {
  local label="$1"
  local evidence="${CURRENT_EVIDENCE_DIR}/disposable-clone-marker-installation.txt"
  [[ -f "${evidence}" && ! -L "${evidence}" ]] || {
    printf '%s did not retain regular partial evidence\n' "${label}" >&2
    exit 1
  }
  [[ "$(file_mode "${evidence}")" == 400 && "$(file_nlink "${evidence}")" == 1 ]] || {
    printf '%s did not seal partial evidence\n' "${label}" >&2
    exit 1
  }
  ! grep -q '^evidence_success=' "${evidence}" || {
    printf '%s wrote a success marker after failure\n' "${label}" >&2
    exit 1
  }
}

expect_fail() {
  local label="$1"
  local expected_calls="$2"
  local database_url="${3:-${DATABASE_URL}}"
  local confirmation="${4:-${INSTALL_CONFIRMATION}}"
  local node_mode="${5:-normal}"
  local psql_mode="${6:-success}"
  local reviewed_commit="${7:-${COMMIT}}"
  local expect_evidence="${8:-true}"
  local output

  prepare_evidence "${label// /-}"
  if output=$(run_installer \
    "${database_url}" \
    "${confirmation}" \
    "${node_mode}" \
    "${psql_mode}" \
    "${reviewed_commit}"); then
    printf '%s unexpectedly passed\n' "${label}" >&2
    exit 1
  fi
  assert_no_sensitive_output "${output}" "${label}"
  [[ "$(psql_calls)" == "${expected_calls}" ]] || {
    printf '%s made an unexpected number of psql calls\n' "${label}" >&2
    exit 1
  }
  if [[ "${expect_evidence}" == true ]]; then
    assert_partial_evidence_sealed "${label}"
  else
    [[ ! -e "${CURRENT_EVIDENCE_DIR}/disposable-clone-marker-installation.txt" ]] || {
      printf '%s created evidence before rejecting early input\n' "${label}" >&2
      exit 1
    }
  fi
  reset_mutations
}

prepare_evidence valid
output=$(run_installer)
assert_no_sensitive_output "${output}" 'valid installation'
[[ "${output}" == 'PASS: installed the approved disposable-clone marker and sealed evidence' ]] || {
  printf 'valid installation returned unexpected output\n' >&2
  exit 1
}
[[ "$(psql_calls)" == 1 ]] || {
  printf 'valid installation did not make exactly one psql call\n' >&2
  exit 1
}
[[ -f "${SANITIZE_LOG}" ]] || {
  printf 'earliest Git child did not verify the sanitized environment\n' >&2
  exit 1
}
VALID_EVIDENCE="${CURRENT_EVIDENCE_DIR}/disposable-clone-marker-installation.txt"
[[ "$(file_mode "${VALID_EVIDENCE}")" == 400 \
   && "$(file_nlink "${VALID_EVIDENCE}")" == 1 ]] || {
  printf 'valid marker-install evidence is not sealed\n' >&2
  exit 1
}
[[ "$(tail -n 1 "${VALID_EVIDENCE}")" == \
   'evidence_success=install-disposable-clone-marker' ]] || {
  printf 'valid marker-install evidence lacks its final success marker\n' >&2
  exit 1
}
grep -Fxq 'database_url_recorded=false' "${VALID_EVIDENCE}"
grep -Fxq 'initial_linked_guard=pass' "${VALID_EVIDENCE}"
grep -Fxq 'final_linked_guard=pass' "${VALID_EVIDENCE}"
grep -Fxq 'initial_direct_url_validation=pass' "${VALID_EVIDENCE}"
grep -Fxq 'final_direct_url_validation=pass' "${VALID_EVIDENCE}"
grep -Fxq 'artifact_stability=pass' "${VALID_EVIDENCE}"
grep -Fxq 'psql_install=success' "${VALID_EVIDENCE}"
! grep -Fq "${STAGING_REF}" "${VALID_EVIDENCE}"
! grep -Fq "${PRODUCTION_REF}" "${VALID_EVIDENCE}"
! grep -Fq 'postgresql://' "${VALID_EVIDENCE}"

expect_fail \
  'wrong typed confirmation' 0 "${DATABASE_URL}" \
  'INSTALL_GALLR_DISPOSABLE_CLONE_MARKER' normal success "${COMMIT}" false
expect_fail \
  'wrong reviewed commit' 0 "${DATABASE_URL}" "${INSTALL_CONFIRMATION}" \
  normal success 0000000000000000000000000000000000000000 false
expect_fail 'pooler URL' 0 "${POOLER_DATABASE_URL}"
expect_fail 'production URL' 0 "${PRODUCTION_DATABASE_URL}"

chmod 0600 "${POLICY_PATH}"
expect_fail 'writable independent policy' 0

expect_fail 'malformed policy validator record' 0 \
  "${DATABASE_URL}" "${INSTALL_CONFIRMATION}" malformed-policy-record
expect_fail 'policy changed between validation passes' 0 \
  "${DATABASE_URL}" "${INSTALL_CONFIRMATION}" policy-change
expect_fail 'linked target changed before install' 0 \
  "${DATABASE_URL}" "${INSTALL_CONFIRMATION}" link-change
expect_fail 'final direct URL validation changed' 0 \
  "${DATABASE_URL}" "${INSTALL_CONFIRMATION}" final-url-fail
expect_fail 'checked-in SQL changed after final URL validation' 0 \
  "${DATABASE_URL}" "${INSTALL_CONFIRMATION}" mutate-sql-after-final-url
expect_fail 'psql installation failure' 1 \
  "${DATABASE_URL}" "${INSTALL_CONFIRMATION}" normal fail

prepare_evidence preexisting
printf '%s\n' existing \
  > "${CURRENT_EVIDENCE_DIR}/disposable-clone-marker-installation.txt"
if output=$(run_installer); then
  printf 'preexisting evidence unexpectedly passed\n' >&2
  exit 1
fi
assert_no_sensitive_output "${output}" 'preexisting evidence'
[[ "$(psql_calls)" == 0 ]] || {
  printf 'preexisting evidence reached psql\n' >&2
  exit 1
}

printf 'PASS: marker bootstrap is target-bound, single-connection, sanitized, and fail-closed\n'
