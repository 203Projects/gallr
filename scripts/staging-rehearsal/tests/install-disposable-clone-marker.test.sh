#!/usr/bin/env bash

# Network-free adversarial tests for the disposable-clone marker bootstrap.
# The production validators run against a synthetic clean Git repository; node,
# git, and psql shims provide deterministic race/failure injection without DNS
# or a PostgreSQL connection.

set -euo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
REHEARSAL_DIR=$(cd "${SCRIPT_DIR}/.." && pwd -P)
TEST_CA_SOURCE="${SCRIPT_DIR}/fixtures/test-root-ca.pem"
REAL_GIT=$(command -v git)
REAL_NODE=$(command -v node)
REAL_EXPECT=$(command -v expect 2>/dev/null || true)
[[ "${REAL_EXPECT}" = /* && -x "${REAL_EXPECT}" ]] || {
  printf 'expect is required on PATH for terminal-backed marker installer tests\n' >&2
  exit 1
}

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
CONTROL_DIR="${TEST_ROOT}/control"
PSQL_LOG="${TEST_ROOT}/psql.log"
NODE_LOG="${TEST_ROOT}/node.log"
SANITIZE_LOG="${TEST_ROOT}/sanitize.log"

mkdir -m 700 \
  "${REPO_ROOT}" \
  "${SECURE_ROOT}" \
  "${EVIDENCE_PARENT}" \
  "${FAKE_BIN}" \
  "${CONTROL_DIR}"
mkdir -p \
  "${RUNNER_DIR}/lib" \
  "${RUNNER_DIR}/sql" \
  "${REPO_ROOT}/supabase/migrations" \
  "${REPO_ROOT}/supabase/.temp"
TEST_CA_PATH="${SECURE_ROOT}/test-root-ca.pem"
cp "${TEST_CA_SOURCE}" "${TEST_CA_PATH}"
chmod 0400 "${TEST_CA_PATH}"
TEST_CA_URI_PATH="${TEST_CA_PATH//\//%2F}"

cp "${REHEARSAL_DIR}/install-disposable-clone-marker.sh" \
  "${RUNNER_DIR}/install-disposable-clone-marker.sh"
cp "${REHEARSAL_DIR}/assert-linked-staging.sh" \
  "${RUNNER_DIR}/assert-linked-staging.sh"
cp "${REHEARSAL_DIR}/lib/validate-database-target.mjs" \
  "${RUNNER_DIR}/lib/validate-database-target.mjs"
cp "${REHEARSAL_DIR}/lib/database-target.mjs" \
  "${RUNNER_DIR}/lib/database-target.mjs"
cp "${REHEARSAL_DIR}/lib/run-psql-with-validated-target.mjs" \
  "${RUNNER_DIR}/lib/run-psql-with-validated-target.mjs"
cp "${REHEARSAL_DIR}/lib/reviewed-toolchain.sh" \
  "${RUNNER_DIR}/lib/reviewed-toolchain.sh"
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

STAGING_REF='ssssssssssssssssssss'
PRODUCTION_REF='pppppppppppppppppppp'
COMPATIBILITY_REF='cccccccccccccccccccc'
if command -v shasum >/dev/null 2>&1; then
  printf '%s' "${PRODUCTION_REF}" | shasum -a 256 | awk '{print $1}' \
    > "${RUNNER_DIR}/production-project-ref.sha256"
  printf '%s' "${COMPATIBILITY_REF}" | shasum -a 256 | awk '{print $1}' \
    > "${RUNNER_DIR}/legacy-compatibility-project-ref.sha256"
else
  printf '%s' "${PRODUCTION_REF}" | sha256sum | awk '{print $1}' \
    > "${RUNNER_DIR}/production-project-ref.sha256"
  printf '%s' "${COMPATIBILITY_REF}" | sha256sum | awk '{print $1}' \
    > "${RUNNER_DIR}/legacy-compatibility-project-ref.sha256"
fi

git -C "${REPO_ROOT}" init -q
git -C "${REPO_ROOT}" config user.name 'Marker Installer Test'
git -C "${REPO_ROOT}" config user.email 'marker-installer@example.test'
git -C "${REPO_ROOT}" add .gitignore scripts supabase/migrations
git -C "${REPO_ROOT}" commit -qm 'marker installer fixture'

COMMIT=$(git -C "${REPO_ROOT}" rev-parse HEAD)
ENCODED_DATABASE_PASSWORD='test%3Apass%5Cword'
EXPECTED_PGPASS_PASSWORD='test\:pass\\word'
DATABASE_URL="postgresql://postgres:${ENCODED_DATABASE_PASSWORD}@db.${STAGING_REF}.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=${TEST_CA_URI_PATH}"
PRODUCTION_DATABASE_URL="postgresql://postgres:${ENCODED_DATABASE_PASSWORD}@db.${PRODUCTION_REF}.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=${TEST_CA_URI_PATH}"
POOLER_DATABASE_URL="postgresql://postgres.${STAGING_REF}:${ENCODED_DATABASE_PASSWORD}@aws-0-test.pooler.supabase.com:5432/postgres?sslmode=verify-full&sslrootcert=${TEST_CA_URI_PATH}"
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

utc_after() {
  "${REAL_NODE}" -e \
    'process.stdout.write(new Date(Date.now() + Number(process.argv[1])).toISOString().replace(/\.\d{3}Z$/, "Z"))' \
    -- "$1"
}

STAGING_SHA256=$(sha256_text "${STAGING_REF}")
PRODUCTION_SHA256=$(sha256_text "${PRODUCTION_REF}")
MIGRATION_SHA256=$(sha256_file "${MIGRATION_PATH}")

printf '%s\n' "${STAGING_REF}" > "${LINKED_REF_PATH}"
cp "${MARKER_SQL}" "${TEST_ROOT}/marker-sql.backup"

cat > "${FAKE_BIN}/git" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

fake_root=$(CDPATH= cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
sanitize_log="${fake_root}/sanitize.log"
real_git=$(< "${fake_root}/control/real-git")

if [[ ! -e "${sanitize_log}" ]]; then
  [[ -z "${GALLR_EXPECTED_STAGING_PROJECT_REF+x}" ]] || exit 61
  [[ -z "${GALLR_PRODUCTION_PROJECT_REF+x}" ]] || exit 62
  [[ -z "${GALLR_STAGING_DATABASE_URL+x}" ]] || exit 63
  [[ -z "${GALLR_STAGING_REHEARSAL_CONFIRM+x}" ]] || exit 64
  [[ -z "${GALLR_STAGING_EVIDENCE_DIR+x}" ]] || exit 65
  [[ -z "${GALLR_STAGING_IDENTITY_POLICY_PATH+x}" ]] || exit 66
  [[ -z "${GALLR_REVIEWED_COMMIT+x}" ]] || exit 67
  [[ -z "${GALLR_DISPOSABLE_CLONE_MARKER_INSTALL_CONFIRMATION+x}" ]] || exit 68
  [[ -z "${GALLR_GOVERNANCE_MODE+x}" ]] || exit 79
  [[ -z "${GALLR_SOLO_OPERATOR_FIRST_CONFIRMATION+x}" ]] || exit 80
  [[ -z "${GALLR_EXECUTOR+x}" && -z "${GALLR_REVIEWER+x}" ]] || exit 81
  [[ -z "${GALLR_CHANGE_RECORD+x}" && -z "${GALLR_REHEARSAL_RUN_ID+x}" ]] || exit 82
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
  printf '%s\n' sanitized > "${sanitize_log}"
fi

exec "${real_git}" "$@"
EOF

cat > "${CONTROL_DIR}/policy-stat-preload.cjs" <<'EOF'
"use strict";
const fs = require("node:fs");
const path = require("node:path");
const originalCloseSync = fs.closeSync;
const originalFstatSync = fs.fstatSync;
const originalOpenSync = fs.openSync;
const policyDescriptors = new Set();
const fakeRoot = path.resolve(__dirname, "..");
const policyPath = path.join(fakeRoot, "secure", "identity-policy.txt");
const metadataTimePath = path.join(__dirname, "policy-metadata-time-ms");
function applyPolicyMetadataTime(stat) {
  if (!fs.existsSync(metadataTimePath)) return stat;
  const metadataTime = Number(fs.readFileSync(metadataTimePath, "utf8"));
  Object.defineProperty(stat, "ctimeMs", { value: metadataTime });
  Object.defineProperty(stat, "birthtimeMs", { value: metadataTime });
  return stat;
}
fs.openSync = function patchedOpenSync(target, ...args) {
  const descriptor = originalOpenSync.call(fs, target, ...args);
  if (String(target) === policyPath) policyDescriptors.add(descriptor);
  return descriptor;
};
fs.fstatSync = function patchedFstatSync(descriptor, ...args) {
  const stat = originalFstatSync.call(fs, descriptor, ...args);
  return policyDescriptors.has(descriptor) ? applyPolicyMetadataTime(stat) : stat;
};
fs.closeSync = function patchedCloseSync(descriptor, ...args) {
  policyDescriptors.delete(descriptor);
  return originalCloseSync.call(fs, descriptor, ...args);
};
EOF

cat > "${FAKE_BIN}/node" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

fake_root=$(CDPATH= cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
control_dir="${fake_root}/control"
real_node=$(< "${control_dir}/real-node")
node_mode=$(< "${control_dir}/node-mode")
node_log="${fake_root}/node.log"
marker_sql="${fake_root}/repository/scripts/staging-rehearsal/sql/install-disposable-clone-marker.sql"
policy_path="${fake_root}/secure/identity-policy.txt"
linked_ref_path="${fake_root}/repository/supabase/.temp/project-ref"
production_ref=$(< "${control_dir}/production-ref")

target=${1:-}
case "${target}" in
  */validate-database-target.mjs)
    printf '%s\n' database >> "${node_log}"
    database_count=$(grep -c '^database$' "${node_log}")
    if [[ "${node_mode}" == final-url-fail && \
          "${database_count}" -eq 2 ]]; then
      exit 81
    fi
    set +e
    "${real_node}" "$@"
    status=$?
    set -e
    if [[ "${status}" -eq 0 && \
          "${node_mode}" == mutate-sql-after-final-url && \
          "${database_count}" -eq 2 ]]; then
      chmod 0600 "${marker_sql}"
      printf '%s\n' '-- adversarial late mutation' >> "${marker_sql}"
    fi
    exit "${status}"
    ;;
  */validate-target-identity-policy.mjs)
    printf '%s\n' policy >> "${node_log}"
    policy_count=$(grep -c '^policy$' "${node_log}")
    if [[ "${node_mode}" == malformed-policy-record ]]; then
      record=$(
        "${real_node}" \
          --require "${control_dir}/policy-stat-preload.cjs" \
          "$@"
      )
      printf '%s\textra\n' "${record}"
      exit 0
    fi
    if [[ "${node_mode}" == policy-change && \
          "${policy_count}" -eq 2 ]]; then
      chmod 0600 "${policy_path}"
      printf '%s\n' 'changed=true' >> "${policy_path}"
    fi
    set +e
    "${real_node}" \
      --require "${control_dir}/policy-stat-preload.cjs" \
      "$@"
    status=$?
    set -e
    if [[ "${status}" -eq 0 && \
          "${node_mode}" == link-change && \
          "${policy_count}" -eq 2 ]]; then
      printf '%s\n' "${production_ref}" > "${linked_ref_path}"
    fi
    exit "${status}"
    ;;
  */database-target.mjs|*/run-psql-with-validated-target.mjs)
    exec "${real_node}" "$@"
    ;;
  *) exec "${real_node}" "$@" ;;
esac
EOF

cat > "${FAKE_BIN}/psql" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

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

fake_root=$(CDPATH= cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
control_dir="${fake_root}/control"
psql_log="${fake_root}/psql.log"
source_ca_path="${fake_root}/secure/test-root-ca.pem"
marker_sql="${fake_root}/repository/scripts/staging-rehearsal/sql/install-disposable-clone-marker.sql"
expected_staging_ref=$(< "${control_dir}/staging-ref")
expected_production_ref=$(< "${control_dir}/production-ref")
expected_pgpass_password=$(< "${control_dir}/expected-pgpass-password")
expected_marker_id=$(< "${control_dir}/expected-marker-id")
expected_staging_sha=$(< "${control_dir}/expected-staging-sha")
expected_production_sha=$(< "${control_dir}/expected-production-sha")
expected_commit=$(< "${control_dir}/expected-commit")
expected_manifest_sha=$(< "${control_dir}/expected-manifest-sha")
expected_policy_sha=$(< "${control_dir}/expected-policy-sha")
expected_governance_mode=$(< "${control_dir}/expected-governance-mode")
expected_operator_identity=$(< "${control_dir}/expected-operator-identity")
expected_first_confirmation_sha=$(< "${control_dir}/expected-first-confirmation-sha")
expected_second_confirmation_sha=$(< "${control_dir}/expected-second-confirmation-sha")
expected_effective_first_attestation=$(< "${control_dir}/expected-effective-first-attestation")
expected_minimum_cooldown=$(< "${control_dir}/expected-minimum-cooldown")
expected_destructive_actions=$(< "${control_dir}/expected-destructive-actions")
psql_mode=$(< "${control_dir}/psql-mode")

printf '%s\n' called >> "${psql_log}"
[[ "${PGHOST:-}" == "db.${expected_staging_ref}.supabase.co" ]] || exit 91
[[ "${PGPORT:-}" == 5432 ]] || exit 128
[[ "${PGDATABASE:-}" == postgres ]] || exit 129
[[ "${PGUSER:-}" == postgres ]] || exit 130
[[ "${PGSSLMODE:-}" == verify-full ]] || exit 92
[[ "${PGGSSENCMODE:-}" == disable ]] || exit 142
[[ "${PGSSLCERTMODE:-}" == disable ]] || exit 143
[[ -n "${PGPASSFILE:-}" && "${PGPASSFILE}" != /dev/null ]] || exit 93
[[ -f "${PGPASSFILE}" && ! -L "${PGPASSFILE}" && -O "${PGPASSFILE}" ]] || exit 131
passfile_mode=$(portable_stat_mode "${PGPASSFILE}")
[[ "${passfile_mode}" == 600 ]] || exit 132
[[ "$(wc -l < "${PGPASSFILE}" | tr -d ' ')" == 1 ]] || exit 133
[[ "$(< "${PGPASSFILE}")" == \
  "db.${expected_staging_ref}.supabase.co:5432:postgres:postgres:${expected_pgpass_password}" ]] \
  || exit 134
[[ -n "${PGSSLROOTCERT:-}" \
   && "${PGSSLROOTCERT}" != "${source_ca_path}" \
   && -f "${PGSSLROOTCERT}" \
   && ! -L "${PGSSLROOTCERT}" \
   && -O "${PGSSLROOTCERT}" ]] || exit 135
certificate_mode=$(portable_stat_mode "${PGSSLROOTCERT}")
certificate_parent_mode=$(portable_stat_mode "$(dirname "${PGSSLROOTCERT}")")
[[ "${certificate_mode}" == 400 && "${certificate_parent_mode}" == 700 ]] || exit 136
cmp -s "${PGSSLROOTCERT}" "${source_ca_path}" || exit 137
[[ "${PGCONNECT_TIMEOUT:-}" == 15 ]] || exit 94
[[ "${PGAPPNAME:-}" == gallr-disposable-clone-marker-install ]] || exit 138
[[ "${PGOPTIONS:-}" == \
  '-c statement_timeout=15000 -c lock_timeout=3000' ]] || exit 95
[[ -z "${PGHOSTADDR+x}" ]] || exit 97
[[ -z "${PGPASSWORD+x}" && -z "${PGSERVICE+x}" && -z "${PGSERVICEFILE+x}" ]] || exit 98
[[ -z "${GALLR_STAGING_DATABASE_URL+x}" ]] || exit 99
[[ -z "${GALLR_EXPECTED_STAGING_PROJECT_REF+x}" ]] || exit 100
[[ -z "${GALLR_VALIDATION_DATABASE_URL+x}" \
   && -z "${GALLR_VALIDATION_PROJECT_REF+x}" \
   && -z "${GALLR_VALIDATION_REQUIRE_DIRECT+x}" \
   && -z "${GALLR_VALIDATION_SSLROOTCERT_SHA256+x}" ]] || exit 139
[[ -z "${GALLR_PSQL_APPNAME+x}" \
   && -z "${GALLR_PSQL_CONNECT_TIMEOUT+x}" \
   && -z "${GALLR_PSQL_OPTIONS+x}" \
   && -z "${GALLR_VALIDATED_PSQL_PATH+x}" \
   && -z "${GALLR_VALIDATED_PSQL_SHA256+x}" ]] || exit 140
[[ -z "${GALLR_DISPOSABLE_CLONE_MARKER_INSTALL_CONFIRMATION+x}" ]] || exit 101
[[ -z "${GALLR_GOVERNANCE_MODE+x}" ]] || exit 117
[[ -z "${GALLR_SOLO_OPERATOR_FIRST_CONFIRMATION+x}" ]] || exit 118
[[ -z "${GALLR_EXECUTOR+x}" && -z "${GALLR_REVIEWER+x}" ]] || exit 126
[[ -z "${GALLR_CHANGE_RECORD+x}" && -z "${GALLR_REHEARSAL_RUN_ID+x}" ]] || exit 127
[[ -z "${DATABASE_URL+x}" && -z "${SUPABASE_SERVICE_ROLE_KEY+x}" ]] || exit 102
[[ -z "${database_url+x}" && -z "${staging_ref+x}" ]] || exit 103
[[ -z "${policy_record+x}" && -z "${marker_id+x}" ]] || exit 104
while IFS='=' read -r environment_name environment_value; do
  [[ "${environment_value}" != *postgresql://* \
     && "${environment_value}" != *postgres://* ]] || exit 141
done < <(env)

installation_confirmation=
marker_id_value=
staging_sha=
production_sha=
commit_value=
manifest_sha=
policy_sha=
governance_mode=
operator_identity=
first_confirmation_sha=
second_confirmation_sha=
effective_first_attestation=
minimum_cooldown=
destructive_actions=
sql_file=
for argument in "$@"; do
  [[ "${argument}" != *postgresql://* && "${argument}" != *postgres://* ]] || exit 105
  [[ "${argument}" != *"${expected_staging_ref}"* ]] || exit 106
  [[ "${argument}" != *"${expected_production_ref}"* ]] || exit 107
  case "${argument}" in
    installation_confirmation=*) installation_confirmation=${argument#*=} ;;
    marker_id=*) marker_id_value=${argument#*=} ;;
    staging_ref_sha256=*) staging_sha=${argument#*=} ;;
    production_ref_sha256=*) production_sha=${argument#*=} ;;
    repository_commit=*) commit_value=${argument#*=} ;;
    operator_manifest_sha256=*) manifest_sha=${argument#*=} ;;
    policy_sha256=*) policy_sha=${argument#*=} ;;
    governance_mode=*) governance_mode=${argument#*=} ;;
    operator_identity=*) operator_identity=${argument#*=} ;;
    first_confirmation_sha256=*) first_confirmation_sha=${argument#*=} ;;
    second_confirmation_sha256=*) second_confirmation_sha=${argument#*=} ;;
    effective_first_attestation_utc=*) effective_first_attestation=${argument#*=} ;;
    minimum_cooldown_seconds=*) minimum_cooldown=${argument#*=} ;;
    destructive_actions=*) destructive_actions=${argument#*=} ;;
    */install-disposable-clone-marker.sql) sql_file=${argument} ;;
  esac
done

[[ "${installation_confirmation}" == INSTALL_GALLR_DISPOSABLE_CLONE_MARKER ]] || exit 108
[[ "${marker_id_value}" == "${expected_marker_id}" ]] || exit 109
[[ "${staging_sha}" == "${expected_staging_sha}" ]] || exit 110
[[ "${production_sha}" == "${expected_production_sha}" ]] || exit 111
[[ "${commit_value}" == "${expected_commit}" ]] || exit 112
[[ "${manifest_sha}" == "${expected_manifest_sha}" ]] || exit 113
[[ "${policy_sha}" == "${expected_policy_sha}" ]] || exit 114
[[ -n "${sql_file}" && "$(basename -- "${sql_file}")" == \
  install-disposable-clone-marker.sql ]] || exit 115
[[ "${sql_file}" != "${marker_sql}" \
   && -f "${sql_file}" && ! -L "${sql_file}" && -O "${sql_file}" ]] || exit 144
case "${sql_file}" in
  /tmp/gallr-validated-psql-*/sql-0/install-disposable-clone-marker.sql|\
  /private/tmp/gallr-validated-psql-*/sql-0/install-disposable-clone-marker.sql) ;;
  *) exit 145 ;;
esac
sql_mode=$(portable_stat_mode "${sql_file}")
sql_parent_mode=$(portable_stat_mode "$(dirname -- "${sql_file}")")
[[ "${sql_mode}" == 400 && "${sql_parent_mode}" == 700 ]] || exit 146
cmp -s "${sql_file}" "${marker_sql}" || exit 147
[[ "${governance_mode}" == "${expected_governance_mode}" ]] || exit 119
[[ "${operator_identity}" == "${expected_operator_identity}" ]] || exit 120
[[ "${first_confirmation_sha}" == "${expected_first_confirmation_sha}" ]] || exit 121
[[ "${second_confirmation_sha}" == "${expected_second_confirmation_sha}" ]] || exit 122
[[ "${effective_first_attestation}" == "${expected_effective_first_attestation}" ]] || exit 123
[[ "${minimum_cooldown}" == "${expected_minimum_cooldown}" ]] || exit 124
[[ "${destructive_actions}" == "${expected_destructive_actions}" ]] || exit 125

[[ "${psql_mode}" != fail ]] || exit 116
if [[ "${psql_mode}" == missing-token ]]; then
  printf '%s\n' 'simulated marker installation without completion token'
else
  printf '%s\n' 'GALLR_DISPOSABLE_CLONE_MARKER_INSTALL_COMPLETE'
fi
EOF

chmod +x "${FAKE_BIN}/git" "${FAKE_BIN}/node" "${FAKE_BIN}/psql"

REVIEWED_NODE_PATH="${FAKE_BIN}/node"
REVIEWED_PSQL_PATH="${FAKE_BIN}/psql"
REVIEWED_NODE_SHA256=$(sha256_file "${REVIEWED_NODE_PATH}")
REVIEWED_PSQL_SHA256=$(sha256_file "${REVIEWED_PSQL_PATH}")
printf '%s\n' "${REAL_GIT}" > "${CONTROL_DIR}/real-git"
printf '%s\n' "${REAL_NODE}" > "${CONTROL_DIR}/real-node"
printf '%s\n' "${STAGING_REF}" > "${CONTROL_DIR}/staging-ref"
printf '%s\n' "${PRODUCTION_REF}" > "${CONTROL_DIR}/production-ref"

{
  printf 'manifest_schema=1\n'
  printf 'run_id=marker-installer-test\n'
  printf 'generated_at_utc=%s\n' "$(utc_after -60000)"
  printf 'target=staging\n'
  printf 'production_target_mode=staging_rehearsal\n'
  printf 'change_record=%s\n' "${CHANGE_RECORD}"
  printf 'executor=%s\n' "${EXECUTOR}"
  printf 'reviewer=%s\n' "${REVIEWER}"
  printf 'repository_commit=%s\n' "${COMMIT}"
  printf 'staging_project_ref_sha256=%s\n' "${STAGING_SHA256}"
  printf 'production_project_ref_sha256=%s\n' "${PRODUCTION_SHA256}"
  printf 'reviewed_node_path=%s\n' "${REVIEWED_NODE_PATH}"
  printf 'reviewed_node_sha256=%s\n' "${REVIEWED_NODE_SHA256}"
  printf 'reviewed_psql_path=%s\n' "${REVIEWED_PSQL_PATH}"
  printf 'reviewed_psql_sha256=%s\n' "${REVIEWED_PSQL_SHA256}"
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

run_with_tty_confirmation() {
  local prompt="$1"
  local confirmation="$2"
  shift 2

  "${REAL_EXPECT}" -f - "${prompt}" "${confirmation}" "$@" <<'EXPECT' |
set timeout 30
set prompt [lindex $argv 0]
set confirmation [lindex $argv 1]
set command [lrange $argv 2 end]
log_user 0
spawn -noecho /bin/bash -c {/bin/stty -echo; exec "$@"} gallr-pty {*}$command
expect {
  -exact $prompt {}
  eof {
    puts -nonewline $expect_out(buffer)
    set result [wait]
    exit [lindex $result 3]
  }
  timeout {
    puts stderr "timed out before marker confirmation prompt"
    exit 124
  }
}
send -- "$confirmation\r"
expect {
  eof { set transcript $expect_out(buffer) }
  timeout {
    puts stderr "timed out after marker confirmation prompt"
    exit 124
  }
}
puts -nonewline $transcript
set result [wait]
exit [lindex $result 3]
EXPECT
    tr -d '\r'
}

run_installer() {
  local database_url="${1:-${DATABASE_URL}}"
  local confirmation="${2:-${INSTALL_CONFIRMATION}}"
  local node_mode="${3:-normal}"
  local psql_mode="${4:-success}"
  local reviewed_commit="${5:-${COMMIT}}"
  local governance_mode="${6:-separated_humans}"
  local execution_confirmation="${7:-}"
  local operator_identity="${8:-}"
  local first_confirmation_sha="${9:-}"
  local second_confirmation_sha="${10:-}"
  local effective_first_attestation="${11:-}"
  local minimum_cooldown="${12:-}"
  local destructive_actions="${13:-}"
  local input_mode="${14:-pipe}"
  local -a governance_environment installer_command

  if [[ "${governance_mode}" == 'solo_operator' ]]; then
    governance_environment=("GALLR_GOVERNANCE_MODE=solo_operator")
  else
    governance_environment=(
      "GALLR_DISPOSABLE_CLONE_MARKER_INSTALL_CONFIRMATION=${confirmation}"
    )
  fi

  : > "${PSQL_LOG}"
  : > "${NODE_LOG}"
  rm -f -- "${SANITIZE_LOG}"
  printf '%s' "${node_mode}" > "${CONTROL_DIR}/node-mode"
  printf '%s' "${psql_mode}" > "${CONTROL_DIR}/psql-mode"
  printf '%s' "${EXPECTED_PGPASS_PASSWORD}" \
    > "${CONTROL_DIR}/expected-pgpass-password"
  printf '%s' "${MARKER_ID}" > "${CONTROL_DIR}/expected-marker-id"
  printf '%s' "${STAGING_SHA256}" > "${CONTROL_DIR}/expected-staging-sha"
  printf '%s' "${PRODUCTION_SHA256}" > "${CONTROL_DIR}/expected-production-sha"
  printf '%s' "${COMMIT}" > "${CONTROL_DIR}/expected-commit"
  printf '%s' "${MANIFEST_SHA256}" > "${CONTROL_DIR}/expected-manifest-sha"
  printf '%s' "${POLICY_SHA256}" > "${CONTROL_DIR}/expected-policy-sha"
  printf '%s' "${governance_mode}" > "${CONTROL_DIR}/expected-governance-mode"
  printf '%s' "${operator_identity}" > "${CONTROL_DIR}/expected-operator-identity"
  printf '%s' "${first_confirmation_sha}" \
    > "${CONTROL_DIR}/expected-first-confirmation-sha"
  printf '%s' "${second_confirmation_sha}" \
    > "${CONTROL_DIR}/expected-second-confirmation-sha"
  printf '%s' "${effective_first_attestation}" \
    > "${CONTROL_DIR}/expected-effective-first-attestation"
  printf '%s' "${minimum_cooldown}" \
    > "${CONTROL_DIR}/expected-minimum-cooldown"
  printf '%s' "${destructive_actions}" \
    > "${CONTROL_DIR}/expected-destructive-actions"

  installer_command=(env -i \
    "PATH=${FAKE_BIN}:${PATH}" \
    "GALLR_EXPECTED_STAGING_PROJECT_REF=${STAGING_REF}" \
    "GALLR_PRODUCTION_PROJECT_REF=${PRODUCTION_REF}" \
    "GALLR_STAGING_DATABASE_URL=${database_url}" \
    "GALLR_STAGING_REHEARSAL_CONFIRM=${STAGING_REF}" \
    "GALLR_STAGING_EVIDENCE_DIR=${CURRENT_EVIDENCE_DIR}" \
    "GALLR_STAGING_IDENTITY_POLICY_PATH=${POLICY_PATH}" \
    "GALLR_REVIEWED_COMMIT=${reviewed_commit}" \
    "${governance_environment[@]}" \
    'GALLR_EXECUTOR=poison-executor' \
    'GALLR_REVIEWER=poison-reviewer' \
    'GALLR_CHANGE_RECORD=poison-change-record' \
    'GALLR_REHEARSAL_RUN_ID=poison-run-id' \
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
    bash "${INSTALLER}")

  case "${input_mode}" in
    pipe)
      printf '%s\n' "${execution_confirmation}" |
        "${installer_command[@]}" 2>&1
      ;;
    tty)
      run_with_tty_confirmation \
        'Type the solo-operator execution confirmation, then press Return: ' \
        "${execution_confirmation}" \
        "${installer_command[@]}" 2>&1
      ;;
    *)
      printf 'Unsupported marker installer test input mode: %s\n' "${input_mode}" >&2
      return 2
      ;;
  esac
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
grep -Fxq 'database_postcondition=committed_marker_verified' "${VALID_EVIDENCE}"
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
expect_fail 'writable independent policy' 0 \
  "${DATABASE_URL}" "${INSTALL_CONFIRMATION}" normal success "${COMMIT}" false

expect_fail 'malformed policy validator record' 0 \
  "${DATABASE_URL}" "${INSTALL_CONFIRMATION}" malformed-policy-record \
  success "${COMMIT}" false
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
expect_fail 'missing post-commit token' 1 \
  "${DATABASE_URL}" "${INSTALL_CONFIRMATION}" normal missing-token

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

# Exercise the explicit solo-operator path with a policy and file timestamp
# older than the code-fixed 15-minute cooldown. The execution literal is fed
# on stdin so it cannot be pre-exported before the cooldown.
SOLO_OPERATOR='hanshin-lee'
SOLO_INTENT="INTENT STAGING ${STAGING_REF} NOT PRODUCTION ${PRODUCTION_REF} ${COMMIT} ACCEPT_NO_INDEPENDENT_REVIEW"
SOLO_EXECUTION="EXECUTE STAGING ${STAGING_REF} NOT PRODUCTION ${PRODUCTION_REF} ${COMMIT} ACCEPT_NO_INDEPENDENT_REVIEW"
SOLO_FIRST_SHA=$(sha256_text "${SOLO_INTENT}")
SOLO_SECOND_SHA=$(sha256_text "${SOLO_EXECUTION}")
SOLO_GENERATED_AT=$(utc_after -1800000)
SOLO_ISSUED_AT=$(utc_after -1200000)
SOLO_VALID_UNTIL=$(utc_after 3600000)
SOLO_MANIFEST_TEMPLATE="${TEST_ROOT}/operator-manifest-solo.txt"

{
  printf 'manifest_schema=2\n'
  printf 'run_id=marker-installer-solo-test\n'
  printf 'generated_at_utc=%s\n' "${SOLO_GENERATED_AT}"
  printf 'target=staging\n'
  printf 'production_target_mode=staging_rehearsal\n'
  printf 'change_record=%s\n' "${CHANGE_RECORD}"
  printf 'executor=%s\n' "${SOLO_OPERATOR}"
  printf 'reviewer=%s\n' "${SOLO_OPERATOR}"
  printf 'repository_commit=%s\n' "${COMMIT}"
  printf 'staging_project_ref_sha256=%s\n' "${STAGING_SHA256}"
  printf 'production_project_ref_sha256=%s\n' "${PRODUCTION_SHA256}"
  printf 'reviewed_node_path=%s\n' "${REVIEWED_NODE_PATH}"
  printf 'reviewed_node_sha256=%s\n' "${REVIEWED_NODE_SHA256}"
  printf 'reviewed_psql_path=%s\n' "${REVIEWED_PSQL_PATH}"
  printf 'reviewed_psql_sha256=%s\n' "${REVIEWED_PSQL_SHA256}"
  printf 'governance_mode=solo_operator\n'
  printf 'human_reviewer_count=0\n'
  printf 'automation_is_independent_human_review=false\n'
  printf 'residual_risk_accepted=true\n'
  printf 'minimum_cooldown_seconds=900\n'
  printf 'destructive_actions=forbidden\n'
  printf 'first_confirmation_sha256=%s\n' "${SOLO_FIRST_SHA}"
  printf 'migration_count=1\n'
  printf '\n[migration_sha256]\n'
  printf '%s  supabase/migrations/20260722000000_test.sql\n' \
    "${MIGRATION_SHA256}"
} > "${SOLO_MANIFEST_TEMPLATE}"
chmod 0444 "${SOLO_MANIFEST_TEMPLATE}"
MANIFEST_TEMPLATE="${SOLO_MANIFEST_TEMPLATE}"
MANIFEST_SHA256=$(sha256_file "${MANIFEST_TEMPLATE}")

chmod 0600 "${POLICY_PATH}"
{
  printf 'policy_schema=2\n'
  printf 'policy_kind=gallr_disposable_clone_target\n'
  printf 'governance_mode=solo_operator\n'
  printf 'issued_at_utc=%s\n' "${SOLO_ISSUED_AT}"
  printf 'valid_until_utc=%s\n' "${SOLO_VALID_UNTIL}"
  printf 'minimum_cooldown_seconds=900\n'
  printf 'destructive_actions=forbidden\n'
  printf 'staging_project_ref_sha256=%s\n' "${STAGING_SHA256}"
  printf 'production_project_ref_sha256=%s\n' "${PRODUCTION_SHA256}"
  printf 'repository_commit=%s\n' "${COMMIT}"
  printf 'operator_manifest_sha256=%s\n' "${MANIFEST_SHA256}"
  printf 'change_record=%s\n' "${CHANGE_RECORD}"
  printf 'operator_identity=%s\n' "${SOLO_OPERATOR}"
  printf 'first_confirmation_sha256=%s\n' "${SOLO_FIRST_SHA}"
  printf 'marker_id=%s\n' "${MARKER_ID}"
} > "${POLICY_PATH}"
chmod 0400 "${POLICY_PATH}"
"${REAL_NODE}" -e \
  'const fs=require("node:fs"); const at=new Date(process.argv[2]); fs.utimesSync(process.argv[1], at, at);' \
  "${POLICY_PATH}" "${SOLO_ISSUED_AT}"
"${REAL_NODE}" -e \
  'process.stdout.write(String(Date.parse(process.argv[1])))' \
  "${SOLO_ISSUED_AT}" > "${CONTROL_DIR}/policy-metadata-time-ms"
POLICY_SHA256=$(sha256_file "${POLICY_PATH}")

prepare_evidence solo-piped-execution-confirmation
if output=$(run_installer \
  "${DATABASE_URL}" '' normal success "${COMMIT}" solo_operator \
  "${SOLO_EXECUTION}" "${SOLO_OPERATOR}" "${SOLO_FIRST_SHA}" \
  "${SOLO_SECOND_SHA}" "${SOLO_ISSUED_AT}" 900 forbidden pipe); then
  printf 'piped solo execution confirmation unexpectedly passed\n' >&2
  exit 1
fi
assert_no_sensitive_output "${output}" 'piped solo execution confirmation'
[[ "$(psql_calls)" == 0 \
   && ! -e "${CURRENT_EVIDENCE_DIR}/disposable-clone-marker-installation.txt" ]] || {
  printf 'piped solo confirmation crossed the pre-evidence boundary\n' >&2
  exit 1
}
[[ "${output}" == *'solo-operator execution confirmation requires an interactive terminal'* ]] || {
  printf 'piped solo confirmation failed for an unexpected reason\n' >&2
  exit 1
}

prepare_evidence solo-valid
output=$(run_installer \
  "${DATABASE_URL}" '' normal success "${COMMIT}" solo_operator \
  "${SOLO_EXECUTION}" "${SOLO_OPERATOR}" "${SOLO_FIRST_SHA}" \
  "${SOLO_SECOND_SHA}" "${SOLO_ISSUED_AT}" 900 forbidden tty)
assert_no_sensitive_output "${output}" 'valid solo installation'
[[ "${output}" == *'PASS: installed the approved disposable-clone marker and sealed evidence'* ]] || {
  printf 'valid solo installation returned unexpected output\n' >&2
  exit 1
}
[[ "$(psql_calls)" == 1 ]] || {
  printf 'valid solo installation did not make exactly one psql call\n' >&2
  exit 1
}
SOLO_EVIDENCE="${CURRENT_EVIDENCE_DIR}/disposable-clone-marker-installation.txt"
grep -Fxq 'evidence_schema=2' "${SOLO_EVIDENCE}"
grep -Fxq 'governance_mode=solo_operator' "${SOLO_EVIDENCE}"
grep -Fxq 'human_reviewer_count=0' "${SOLO_EVIDENCE}"
grep -Fxq 'automation_is_independent_human_review=false' "${SOLO_EVIDENCE}"
grep -Fxq 'destructive_actions=forbidden' "${SOLO_EVIDENCE}"
grep -Fxq "first_confirmation_sha256=${SOLO_FIRST_SHA}" "${SOLO_EVIDENCE}"
grep -Fxq "second_confirmation_sha256=${SOLO_SECOND_SHA}" "${SOLO_EVIDENCE}"

prepare_evidence solo-wrong-execution-confirmation
: > "${PSQL_LOG}"
if output=$(run_installer \
  "${DATABASE_URL}" '' normal success "${COMMIT}" solo_operator \
  'wrong-confirmation' "${SOLO_OPERATOR}" "${SOLO_FIRST_SHA}" \
  "${SOLO_SECOND_SHA}" "${SOLO_ISSUED_AT}" 900 forbidden tty); then
  printf 'wrong solo execution confirmation unexpectedly passed\n' >&2
  exit 1
fi
assert_no_sensitive_output "${output}" 'wrong solo execution confirmation'
[[ "$(psql_calls)" == 0 ]] || {
  printf 'wrong solo execution confirmation reached psql\n' >&2
  exit 1
}
[[ ! -e "${CURRENT_EVIDENCE_DIR}/disposable-clone-marker-installation.txt" ]] || {
  printf 'wrong solo execution confirmation created evidence\n' >&2
  exit 1
}

prepare_evidence solo-policy-too-recent
"${REAL_NODE}" -e \
  'const fs=require("node:fs"); const at=new Date(); fs.utimesSync(process.argv[1], at, at);' \
  "${POLICY_PATH}"
: > "${PSQL_LOG}"
if output=$(run_installer \
  "${DATABASE_URL}" '' normal success "${COMMIT}" solo_operator \
  "${SOLO_EXECUTION}" "${SOLO_OPERATOR}" "${SOLO_FIRST_SHA}" \
  "${SOLO_SECOND_SHA}" "${SOLO_ISSUED_AT}" 900 forbidden); then
  printf 'too-recent solo policy unexpectedly passed\n' >&2
  exit 1
fi
assert_no_sensitive_output "${output}" 'too-recent solo policy'
[[ "$(psql_calls)" == 0 \
   && ! -e "${CURRENT_EVIDENCE_DIR}/disposable-clone-marker-installation.txt" ]] || {
  printf 'too-recent solo policy crossed the pre-evidence boundary\n' >&2
  exit 1
}

grep -Fq "governance_mode in ('separated_humans', 'solo_operator')" "${MARKER_SQL}"
grep -Fq "destructive_actions = 'forbidden'" "${MARKER_SQL}"

printf 'PASS: marker bootstrap is target-bound, single-connection, sanitized, and fail-closed\n'
