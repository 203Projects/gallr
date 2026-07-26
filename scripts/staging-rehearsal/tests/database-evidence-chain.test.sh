#!/usr/bin/env bash

# Offline behavioral tests for the sealed pre-migration evidence chain. The
# synthetic repository and fake psql process make any remote database contact
# impossible while still exercising the production runner end to end.

set -euo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
REHEARSAL_DIR=$(cd "${SCRIPT_DIR}/.." && pwd -P)
TEST_CA_SOURCE="${SCRIPT_DIR}/fixtures/test-root-ca.pem"
REAL_NODE=$(command -v node)

TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/gallr-database-evidence.XXXXXX")
TEST_ROOT=$(cd "${TEST_ROOT}" && pwd -P)
case "${TEST_ROOT}" in
  /tmp/*|/private/tmp/*|/private/var/*) ;;
  *) printf 'unexpected temporary path\n' >&2; exit 1 ;;
esac
trap 'rm -rf -- "${TEST_ROOT}"' EXIT HUP INT TERM

REPO_ROOT="${TEST_ROOT}/repository"
RUNNER_DIR="${REPO_ROOT}/scripts/staging-rehearsal"
EVIDENCE_ROOT="${TEST_ROOT}/evidence"
FAILED_EVIDENCE_ROOT="${TEST_ROOT}/failed-evidence"
QUIT_EVIDENCE_ROOT="${TEST_ROOT}/quit-evidence"
INTERRUPT_EVIDENCE_ROOT="${TEST_ROOT}/interrupt-evidence"
INTERRUPT_FAILURE_EVIDENCE_ROOT="${TEST_ROOT}/interrupt-failure-evidence"
SECURE_ROOT="${TEST_ROOT}/secure"
FAKE_BIN="${TEST_ROOT}/bin"
PSQL_LOG="${TEST_ROOT}/psql.log"
PSQL_FAIL_PRE_CONTROL="${TEST_ROOT}/psql-fail-pre"
PSQL_BLOCK_CONTROL="${TEST_ROOT}/psql-block"
PSQL_PID_CAPTURE="${TEST_ROOT}/psql-pid"

mkdir -m 700 \
  "${REPO_ROOT}" \
  "${EVIDENCE_ROOT}" \
  "${FAILED_EVIDENCE_ROOT}" \
  "${QUIT_EVIDENCE_ROOT}" \
  "${INTERRUPT_EVIDENCE_ROOT}" \
  "${INTERRUPT_FAILURE_EVIDENCE_ROOT}" \
  "${SECURE_ROOT}" \
  "${FAKE_BIN}"
TEST_CA_PATH="${SECURE_ROOT}/test-root-ca.pem"
cp "${TEST_CA_SOURCE}" "${TEST_CA_PATH}"
chmod 0400 "${TEST_CA_PATH}"
TEST_CA_URI_PATH="${TEST_CA_PATH//\//%2F}"
mkdir -p "${RUNNER_DIR}/lib" "${RUNNER_DIR}/sql"

cp "${REHEARSAL_DIR}/run-database-evidence.sh" \
  "${RUNNER_DIR}/run-database-evidence.sh"
cp "${REHEARSAL_DIR}/lib/validate-database-target.mjs" \
  "${RUNNER_DIR}/lib/validate-database-target.mjs"
cp "${REHEARSAL_DIR}/lib/database-target.mjs" \
  "${RUNNER_DIR}/lib/database-target.mjs"
cp "${REHEARSAL_DIR}/lib/run-psql-with-validated-target.mjs" \
  "${RUNNER_DIR}/lib/run-psql-with-validated-target.mjs"
cp "${REHEARSAL_DIR}/lib/reviewed-toolchain.sh" \
  "${RUNNER_DIR}/lib/reviewed-toolchain.sh"
cp "${REHEARSAL_DIR}/sql/pre-migration-inventory.sql" \
  "${RUNNER_DIR}/sql/pre-migration-inventory.sql"
cp "${REHEARSAL_DIR}/sql/post-migration-validation.sql" \
  "${RUNNER_DIR}/sql/post-migration-validation.sql"
cp "${REHEARSAL_DIR}/sql/migration-lock-observer.sql" \
  "${RUNNER_DIR}/sql/migration-lock-observer.sql"

printf '%s\n' \
  '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  "printf '%s\\n' 'PASS: linked project matches the reviewed staging manifest'" \
  > "${RUNNER_DIR}/assert-linked-staging.sh"

{
  printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail'
  printf 'readonly real_node=%q\n' "${REAL_NODE}"
  cat <<'EOF'
for forbidden in \
  staging_database_url staging_ref_raw production_ref_raw \
  DATABASE_URL SUPABASE_ANON_KEY SUPABASE_SERVICE_ROLE_KEY; do
  [[ "${!forbidden+x}" != x ]] || exit 79
done
exec "${real_node}" "$@"
EOF
} > "${FAKE_BIN}/node"

{
  printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail'
  printf 'readonly fake_psql_log=%q\n' "${PSQL_LOG}"
  printf 'readonly fake_fail_pre_control=%q\n' "${PSQL_FAIL_PRE_CONTROL}"
  printf 'readonly fake_block_control=%q\n' "${PSQL_BLOCK_CONTROL}"
  printf 'readonly fake_pid_capture=%q\n' "${PSQL_PID_CAPTURE}"
  printf 'readonly fake_source_ca_path=%q\n' "${TEST_CA_PATH}"
  printf '%s\n' \
    "readonly fake_staging_ref='aaaaaaaaaaaaaaaaaaaa'" \
    "readonly fake_production_ref='bbbbbbbbbbbbbbbbbbbb'" \
    "readonly fake_expected_pgpass_password='test\\:pass\\\\word'" \
    "readonly fake_legacy_sha256='cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc'"
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

printf '%s\n' called >> "${fake_psql_log}"
[[ "${PGHOST:-}" == "db.${fake_staging_ref}.supabase.co" ]] || exit 81
[[ "${PGPORT:-}" == 5432 && "${PGDATABASE:-}" == postgres \
   && "${PGUSER:-}" == postgres ]] || exit 82
[[ "${PGSSLMODE:-}" == verify-full \
   && "${PGGSSENCMODE:-}" == disable \
   && "${PGSSLCERTMODE:-}" == disable ]] || exit 83
[[ "${PGAPPNAME:-}" == gallr_staging_evidence_* \
   && "${PGCONNECT_TIMEOUT:-}" == 10 ]] || exit 84
[[ -z "${PGOPTIONS+x}" && -z "${PGPASSWORD+x}" \
   && -z "${PGHOSTADDR+x}" && -z "${PGSERVICE+x}" \
   && -z "${PGSERVICEFILE+x}" ]] || exit 85
for forbidden in \
  GALLR_STAGING_DATABASE_URL GALLR_EXPECTED_STAGING_PROJECT_REF \
  GALLR_PRODUCTION_PROJECT_REF GALLR_STAGING_REHEARSAL_CONFIRM \
  GALLR_STAGING_EVIDENCE_DIR GALLR_VALIDATION_DATABASE_URL \
  GALLR_VALIDATION_PROJECT_REF GALLR_VALIDATION_REQUIRE_DIRECT \
  GALLR_VALIDATION_SSLROOTCERT_SHA256 GALLR_PSQL_APPNAME \
  GALLR_PSQL_CONNECT_TIMEOUT GALLR_PSQL_OPTIONS; do
  [[ "${!forbidden+x}" != x ]] || exit 86
done
[[ -n "${PGPASSFILE:-}" && "${PGPASSFILE}" != /dev/null \
   && -f "${PGPASSFILE}" && ! -L "${PGPASSFILE}" && -O "${PGPASSFILE}" ]] \
  || exit 87
passfile_mode=$(portable_stat_mode "${PGPASSFILE}")
[[ "${passfile_mode}" == 600 \
   && "$(wc -l < "${PGPASSFILE}" | tr -d ' ')" == 1 \
   && "$(< "${PGPASSFILE}")" == \
      "db.${fake_staging_ref}.supabase.co:5432:postgres:postgres:${fake_expected_pgpass_password}" ]] \
  || exit 88
[[ -n "${PGSSLROOTCERT:-}" && "${PGSSLROOTCERT}" != "${fake_source_ca_path}" \
   && -f "${PGSSLROOTCERT}" && ! -L "${PGSSLROOTCERT}" \
   && -O "${PGSSLROOTCERT}" ]] || exit 89
certificate_mode=$(portable_stat_mode "${PGSSLROOTCERT}")
certificate_parent_mode=$(portable_stat_mode "$(dirname "${PGSSLROOTCERT}")")
[[ "${certificate_mode}" == 400 && "${certificate_parent_mode}" == 700 ]] || exit 90
cmp -s "${PGSSLROOTCERT}" "${fake_source_ca_path}" || exit 91
while IFS='=' read -r environment_name environment_value; do
  [[ "${environment_name}" != FAKE_* \
     && "${environment_name}" != GALLR_* \
     && "${environment_name}" != SUPABASE_* ]] || exit 92
  [[ "${environment_value}" != *postgresql://* \
     && "${environment_value}" != *postgres://* ]] || exit 92
done < <(env)

sql_file=
expected_legacy_payload_sha256=
for argument in "$@"; do
  [[ "${argument}" != *postgresql://* && "${argument}" != *postgres://* ]] || exit 93
  [[ "${argument}" != *"${fake_staging_ref}"* ]] || exit 94
  [[ "${argument}" != *"${fake_production_ref}"* ]] || exit 95
done
while [[ $# -gt 0 ]]; do
  case "$1" in
    -f)
      shift
      [[ $# -gt 0 ]] || exit 96
      sql_file="$1"
      ;;
    expected_legacy_payload_sha256=*)
      expected_legacy_payload_sha256=${1#*=}
      ;;
  esac
  shift
done
if [[ "$(<"${fake_block_control}")" == true ]]; then
  case "${sql_file}" in
    */migration-lock-observer.sql)
      printf 'observer_sample=%s observed_at_utc=2026-07-23T12:40:49.500000Z\n' \
        "${fake_staging_ref}"
      printf 'observer_padding=%02500d\n' 0
      printf 'postgresql://postgres:observer-secret-password@db.%s.supabase.co:5432/postgres\n' \
        "${fake_staging_ref}"
      ;;
  esac
  printf '%s\n' "$$" >"${fake_pid_capture}"
  trap 'exit 0' TERM
  while :; do :; done
fi
case "${sql_file}" in
  */pre-migration-inventory.sql)
    if [[ "$(<"${fake_fail_pre_control}")" == true ]]; then
      printf '%s\n' 'simulated pre-migration failure'
      exit 97
    fi
    printf 'legacy_full_payload_sha256=%s\n' "${fake_legacy_sha256}"
    ;;
  */post-migration-validation.sql)
    [[ "${expected_legacy_payload_sha256}" == "${fake_legacy_sha256}" ]] || exit 98
    printf '%s\n' 'post-migration validation complete'
    ;;
  *) exit 99 ;;
esac
EOF
} > "${FAKE_BIN}/psql"

chmod +x \
  "${RUNNER_DIR}/run-database-evidence.sh" \
  "${RUNNER_DIR}/assert-linked-staging.sh" \
  "${FAKE_BIN}/node" \
  "${FAKE_BIN}/psql"

git -C "${REPO_ROOT}" init -q
git -C "${REPO_ROOT}" config user.name 'Evidence Chain Test'
git -C "${REPO_ROOT}" config user.email 'evidence-chain@example.test'
git -C "${REPO_ROOT}" add scripts
git -C "${REPO_ROOT}" commit -qm 'test baseline'

STAGING_REF='aaaaaaaaaaaaaaaaaaaa'
PRODUCTION_REF='bbbbbbbbbbbbbbbbbbbb'
ENCODED_DATABASE_PASSWORD='test%3Apass%5Cword'
DATABASE_URL="postgresql://postgres:${ENCODED_DATABASE_PASSWORD}@db.${STAGING_REF}.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=${TEST_CA_URI_PATH}"
RUNNER="${RUNNER_DIR}/run-database-evidence.sh"
PRE_EVIDENCE="${EVIDENCE_ROOT}/pre-migration-inventory.txt"
POST_MIGRATION_EVIDENCE="${EVIDENCE_ROOT}/post-migration-validation.txt"
POST_IMPORT_EVIDENCE="${EVIDENCE_ROOT}/post-import-validation.txt"
GOLDEN_PRE="${TEST_ROOT}/pre-migration-inventory.golden"

sha256_file() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

FAKE_NODE_PATH="$(cd "${FAKE_BIN}" && pwd -P)/node"
FAKE_PSQL_PATH="$(cd "${FAKE_BIN}" && pwd -P)/psql"
FAKE_NODE_SHA256=$(sha256_file "${FAKE_NODE_PATH}")
FAKE_PSQL_SHA256=$(sha256_file "${FAKE_PSQL_PATH}")
printf '%s\n' \
  'operator_manifest=test-fixture' \
  "reviewed_node_path=${FAKE_NODE_PATH}" \
  "reviewed_node_sha256=${FAKE_NODE_SHA256}" \
  "reviewed_psql_path=${FAKE_PSQL_PATH}" \
  "reviewed_psql_sha256=${FAKE_PSQL_SHA256}" \
  > "${EVIDENCE_ROOT}/operator-manifest.txt"
cp "${EVIDENCE_ROOT}/operator-manifest.txt" \
  "${FAILED_EVIDENCE_ROOT}/operator-manifest.txt"
cp "${EVIDENCE_ROOT}/operator-manifest.txt" \
  "${QUIT_EVIDENCE_ROOT}/operator-manifest.txt"
cp "${EVIDENCE_ROOT}/operator-manifest.txt" \
  "${INTERRUPT_EVIDENCE_ROOT}/operator-manifest.txt"
cp "${EVIDENCE_ROOT}/operator-manifest.txt" \
  "${INTERRUPT_FAILURE_EVIDENCE_ROOT}/operator-manifest.txt"
chmod 0444 \
  "${EVIDENCE_ROOT}/operator-manifest.txt" \
  "${FAILED_EVIDENCE_ROOT}/operator-manifest.txt" \
  "${QUIT_EVIDENCE_ROOT}/operator-manifest.txt" \
  "${INTERRUPT_EVIDENCE_ROOT}/operator-manifest.txt" \
  "${INTERRUPT_FAILURE_EVIDENCE_ROOT}/operator-manifest.txt"
: > "${PSQL_LOG}"
: > "${PSQL_FAIL_PRE_CONTROL}"
: > "${PSQL_BLOCK_CONTROL}"

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

psql_calls() {
  wc -l < "${PSQL_LOG}" | tr -d ' '
}

run_phase() {
  local phase="$1"
  local evidence_dir="${2:-${EVIDENCE_ROOT}}"
  local fail_pre="${3:-false}"

  printf '%s\n' "${fail_pre}" > "${PSQL_FAIL_PRE_CONTROL}"
  env \
    "PATH=${FAKE_BIN}:${PATH}" \
    "GALLR_EXPECTED_STAGING_PROJECT_REF=${STAGING_REF}" \
    "GALLR_PRODUCTION_PROJECT_REF=${PRODUCTION_REF}" \
    "GALLR_STAGING_DATABASE_URL=${DATABASE_URL}" \
    "GALLR_STAGING_REHEARSAL_CONFIRM=${STAGING_REF}" \
    "GALLR_STAGING_EVIDENCE_DIR=${evidence_dir}" \
    bash "${RUNNER}" "${phase}" 2>&1
}

assert_no_raw_refs() {
  local output="$1"
  local label="$2"
  [[ "${output}" != *"${STAGING_REF}"* && "${output}" != *"${PRODUCTION_REF}"* ]] || {
    printf '%s disclosed a raw project ref\n' "${label}" >&2
    exit 1
  }
}

restore_golden_pre() {
  rm -f -- "${PRE_EVIDENCE}"
  cp "${GOLDEN_PRE}" "${PRE_EVIDENCE}"
  chmod 0400 "${PRE_EVIDENCE}"
}

rewrite_field() {
  local key="$1"
  local value="$2"
  local temporary="${TEST_ROOT}/rewrite-field.tmp"

  awk -v key="${key}" -v value="${value}" \
    'index($0, key "=") == 1 { $0 = key "=" value } { print }' \
    "${PRE_EVIDENCE}" > "${temporary}"
  chmod 0400 "${temporary}"
  mv -f -- "${temporary}" "${PRE_EVIDENCE}"
}

insert_before_success() {
  local line="$1"
  local temporary="${TEST_ROOT}/insert-field.tmp"

  awk -v line="${line}" \
    '$0 == "evidence_success=pre-migration" { print line } { print }' \
    "${PRE_EVIDENCE}" > "${temporary}"
  chmod 0400 "${temporary}"
  mv -f -- "${temporary}" "${PRE_EVIDENCE}"
}

expect_chain_reject() {
  local label="$1"
  local evidence_dir="${2:-${EVIDENCE_ROOT}}"
  local phase="${3:-post-migration}"
  local evidence_name
  local before
  local after
  local output

  case "${phase}" in
    post-migration) evidence_name='post-migration-validation.txt' ;;
    post-import) evidence_name='post-import-validation.txt' ;;
    *) printf 'unsupported rejection-test phase: %s\n' "${phase}" >&2; exit 1 ;;
  esac

  before=$(psql_calls)
  if output=$(run_phase "${phase}" "${evidence_dir}"); then
    printf '%s unexpectedly passed\n' "${label}" >&2
    exit 1
  fi
  after=$(psql_calls)
  [[ "${after}" == "${before}" ]] || {
    printf '%s reached psql before rejecting the evidence chain\n' "${label}" >&2
    exit 1
  }
  [[ ! -e "${evidence_dir}/${evidence_name}" ]] || {
    printf '%s created post-phase evidence before rejecting the chain\n' "${label}" >&2
    exit 1
  }
  assert_no_raw_refs "${output}" "${label}"
}

output=$(run_phase pre-migration)
assert_no_raw_refs "${output}" 'valid pre-migration run'
[[ "$(psql_calls)" == 1 ]] || {
  printf 'valid pre-migration run did not call psql exactly once\n' >&2
  exit 1
}
[[ -f "${PRE_EVIDENCE}" && ! -L "${PRE_EVIDENCE}" ]] || {
  printf 'pre-migration evidence is not a regular non-symlink file\n' >&2
  exit 1
}
[[ "$(file_mode "${PRE_EVIDENCE}")" == 400 ]] || {
  printf 'pre-migration evidence is not sealed 0400\n' >&2
  exit 1
}
[[ "$(file_nlink "${PRE_EVIDENCE}")" == 1 ]] || {
  printf 'pre-migration evidence does not have one hard link\n' >&2
  exit 1
}
[[ "$(tail -n 1 "${PRE_EVIDENCE}")" == 'evidence_success=pre-migration' ]] || {
  printf 'pre-migration evidence lacks a final success marker\n' >&2
  exit 1
}
grep -Fxq 'evidence_schema=2' "${PRE_EVIDENCE}"
cp "${PRE_EVIDENCE}" "${GOLDEN_PRE}"
chmod 0400 "${GOLDEN_PRE}"

chmod 0600 "${PRE_EVIDENCE}"
expect_chain_reject 'writable pre-migration evidence'
restore_golden_pre

HARD_LINK="${TEST_ROOT}/pre-migration-hard-link"
ln "${PRE_EVIDENCE}" "${HARD_LINK}"
expect_chain_reject 'hard-linked pre-migration evidence'
rm -f -- "${HARD_LINK}"
restore_golden_pre

SYMLINK_TARGET="${TEST_ROOT}/pre-migration-symlink-target"
mv "${PRE_EVIDENCE}" "${SYMLINK_TARGET}"
ln -s "${SYMLINK_TARGET}" "${PRE_EVIDENCE}"
expect_chain_reject 'symlinked pre-migration evidence'
rm -f -- "${PRE_EVIDENCE}"
mv "${SYMLINK_TARGET}" "${PRE_EVIDENCE}"
restore_golden_pre

rewrite_field evidence_schema 1
expect_chain_reject 'wrong evidence schema'
expect_chain_reject 'wrong evidence schema for post-import' \
  "${EVIDENCE_ROOT}" post-import
restore_golden_pre

rewrite_field phase post-migration
expect_chain_reject 'wrong evidence phase'
restore_golden_pre

rewrite_field repository_commit 0000000000000000000000000000000000000000
expect_chain_reject 'stale repository commit'
restore_golden_pre

chmod 0600 "${EVIDENCE_ROOT}/operator-manifest.txt"
printf '%s\n' 'manifest changed after pre-migration' \
  >> "${EVIDENCE_ROOT}/operator-manifest.txt"
chmod 0444 "${EVIDENCE_ROOT}/operator-manifest.txt"
expect_chain_reject 'stale operator manifest digest'
chmod 0600 "${EVIDENCE_ROOT}/operator-manifest.txt"
cp "${FAILED_EVIDENCE_ROOT}/operator-manifest.txt" \
  "${EVIDENCE_ROOT}/operator-manifest.txt"
chmod 0444 "${EVIDENCE_ROOT}/operator-manifest.txt"
restore_golden_pre

rewrite_field staging_project_ref_sha256 0000000000000000000000000000000000000000000000000000000000000000
expect_chain_reject 'wrong staging project fingerprint'
restore_golden_pre

rewrite_field production_project_ref_sha256 0000000000000000000000000000000000000000000000000000000000000000
expect_chain_reject 'wrong production project fingerprint'
restore_golden_pre

cp "${RUNNER}" "${TEST_ROOT}/runner.backup"
printf '%s\n' '# changed after pre-migration' >> "${RUNNER}"
expect_chain_reject 'stale runner digest'
cp "${TEST_ROOT}/runner.backup" "${RUNNER}"
chmod +x "${RUNNER}"
restore_golden_pre

PRE_SQL="${RUNNER_DIR}/sql/pre-migration-inventory.sql"
cp "${PRE_SQL}" "${TEST_ROOT}/pre-migration-inventory.sql.backup"
printf '%s\n' '-- changed after pre-migration' >> "${PRE_SQL}"
expect_chain_reject 'stale pre-migration SQL digest'
cp "${TEST_ROOT}/pre-migration-inventory.sql.backup" "${PRE_SQL}"
restore_golden_pre

rewrite_field linked_guard 'PASS: unexpected guard result'
expect_chain_reject 'wrong linked guard marker'
restore_golden_pre

rewrite_field legacy_full_payload_sha256 invalid
expect_chain_reject 'malformed legacy payload digest'
restore_golden_pre

insert_before_success 'legacy_full_payload_sha256=dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd'
expect_chain_reject 'duplicate legacy payload digest'
restore_golden_pre

rewrite_field evidence_success post-migration
expect_chain_reject 'wrong evidence success marker'
restore_golden_pre

chmod 0600 "${PRE_EVIDENCE}"
awk '$0 != "evidence_success=pre-migration" { print }' \
  "${PRE_EVIDENCE}" > "${TEST_ROOT}/missing-success.tmp"
chmod 0400 "${TEST_ROOT}/missing-success.tmp"
mv -f -- "${TEST_ROOT}/missing-success.tmp" "${PRE_EVIDENCE}"
expect_chain_reject 'missing evidence success marker'
restore_golden_pre

chmod 0600 "${PRE_EVIDENCE}"
printf '%s\n' 'trailing-data-after-success=true' >> "${PRE_EVIDENCE}"
chmod 0400 "${PRE_EVIDENCE}"
expect_chain_reject 'non-final evidence success marker'
restore_golden_pre

insert_before_success 'evidence_schema=2'
expect_chain_reject 'duplicate schema field'
restore_golden_pre

if output=$(run_phase pre-migration "${FAILED_EVIDENCE_ROOT}" true); then
  printf 'failing pre-migration psql unexpectedly passed\n' >&2
  exit 1
fi
assert_no_raw_refs "${output}" 'failed pre-migration run'
FAILED_PRE="${FAILED_EVIDENCE_ROOT}/pre-migration-inventory.txt"
[[ -f "${FAILED_PRE}" && "$(file_mode "${FAILED_PRE}")" == 400 ]] || {
  printf 'failed pre-migration evidence was not sealed\n' >&2
  exit 1
}
! grep -q '^evidence_success=' "${FAILED_PRE}"
expect_chain_reject 'failed pre-migration evidence' "${FAILED_EVIDENCE_ROOT}"

# SIGQUIT must take the same cleanup path as the handled stop signals. Start
# the literal runner as its own job so Bash does not inherit ignored QUIT,
# block only inside the local fake psql, then signal the production entrypoint.
printf '%s\n' false >"${PSQL_FAIL_PRE_CONTROL}"
printf '%s\n' true >"${PSQL_BLOCK_CONTROL}"
rm -f -- "${PSQL_PID_CAPTURE}"
set -m
env \
  "PATH=${FAKE_BIN}:${PATH}" \
  "GALLR_EXPECTED_STAGING_PROJECT_REF=${STAGING_REF}" \
  "GALLR_PRODUCTION_PROJECT_REF=${PRODUCTION_REF}" \
  "GALLR_STAGING_DATABASE_URL=${DATABASE_URL}" \
  "GALLR_STAGING_REHEARSAL_CONFIRM=${STAGING_REF}" \
  "GALLR_STAGING_EVIDENCE_DIR=${QUIT_EVIDENCE_ROOT}" \
  /bin/bash "${RUNNER}" pre-migration \
  >"${TEST_ROOT}/quit.stdout" 2>"${TEST_ROOT}/quit.stderr" &
QUIT_RUNNER_PID=$!
set +m
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
  [[ -s "${PSQL_PID_CAPTURE}" ]] && break
  /bin/sleep 0.1
done
if [[ ! -s "${PSQL_PID_CAPTURE}" ]]; then
  kill -TERM "${QUIT_RUNNER_PID}" 2>/dev/null || true
  wait "${QUIT_RUNNER_PID}" 2>/dev/null || true
  printf 'SIGQUIT regression did not reach fake psql\n' >&2
  exit 1
fi
QUIT_PSQL_PID=$(<"${PSQL_PID_CAPTURE}")
kill -QUIT "${QUIT_RUNNER_PID}"
set +e
wait "${QUIT_RUNNER_PID}"
QUIT_RUNNER_STATUS=$?
set -e
printf '%s\n' false >"${PSQL_BLOCK_CONTROL}"
[[ "${QUIT_RUNNER_STATUS}" -eq 131 ]] || {
  printf 'SIGQUIT runner returned %s instead of 131\n' \
    "${QUIT_RUNNER_STATUS}" >&2
  exit 1
}
! kill -0 "${QUIT_PSQL_PID}" 2>/dev/null || {
  printf 'SIGQUIT left fake psql running\n' >&2
  exit 1
}
QUIT_PRE="${QUIT_EVIDENCE_ROOT}/pre-migration-inventory.txt"
[[ -f "${QUIT_PRE}" && ! -L "${QUIT_PRE}" \
   && "$(file_mode "${QUIT_PRE}")" == 400 ]] || {
  printf 'SIGQUIT did not seal partial database evidence\n' >&2
  exit 1
}
! grep -q '^evidence_success=' "${QUIT_PRE}"
QUIT_SCRATCH=$(
  find "${QUIT_EVIDENCE_ROOT}" -maxdepth 1 \
    -name '.pre-migration-inventory.txt.psql-output.*' -print -quit
)
[[ -z "${QUIT_SCRATCH}" ]] || {
  printf 'SIGQUIT left database-evidence scratch output\n' >&2
  exit 1
}
assert_no_raw_refs "$(<"${TEST_ROOT}/quit.stdout")" 'SIGQUIT stdout'
assert_no_raw_refs "$(<"${TEST_ROOT}/quit.stderr")" 'SIGQUIT stderr'

# The long-running lock observer must retain already-emitted samples through
# the same redaction path when the documented Ctrl-C stop interrupts psql.
printf '%s\n' false >"${PSQL_FAIL_PRE_CONTROL}"
printf '%s\n' true >"${PSQL_BLOCK_CONTROL}"
rm -f -- "${PSQL_PID_CAPTURE}"
set -m
env \
  "PATH=${FAKE_BIN}:${PATH}" \
  "GALLR_EXPECTED_STAGING_PROJECT_REF=${STAGING_REF}" \
  "GALLR_PRODUCTION_PROJECT_REF=${PRODUCTION_REF}" \
  "GALLR_STAGING_DATABASE_URL=${DATABASE_URL}" \
  "GALLR_STAGING_REHEARSAL_CONFIRM=${STAGING_REF}" \
  "GALLR_STAGING_EVIDENCE_DIR=${INTERRUPT_EVIDENCE_ROOT}" \
  /bin/bash "${RUNNER}" observe-locks \
  >"${TEST_ROOT}/interrupt.stdout" 2>"${TEST_ROOT}/interrupt.stderr" &
INTERRUPT_RUNNER_PID=$!
set +m
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
  [[ -s "${PSQL_PID_CAPTURE}" ]] && break
  /bin/sleep 0.1
done
if [[ ! -s "${PSQL_PID_CAPTURE}" ]]; then
  kill -TERM "${INTERRUPT_RUNNER_PID}" 2>/dev/null || true
  wait "${INTERRUPT_RUNNER_PID}" 2>/dev/null || true
  printf 'SIGINT observer regression did not reach fake psql\n' >&2
  exit 1
fi
INTERRUPT_PSQL_PID=$(<"${PSQL_PID_CAPTURE}")
kill -INT "${INTERRUPT_RUNNER_PID}"
set +e
wait "${INTERRUPT_RUNNER_PID}"
INTERRUPT_RUNNER_STATUS=$?
set -e
printf '%s\n' false >"${PSQL_BLOCK_CONTROL}"
[[ "${INTERRUPT_RUNNER_STATUS}" -eq 130 ]] || {
  printf 'SIGINT observer runner returned %s instead of 130\n' \
    "${INTERRUPT_RUNNER_STATUS}" >&2
  exit 1
}
! kill -0 "${INTERRUPT_PSQL_PID}" 2>/dev/null || {
  printf 'SIGINT observer left fake psql running\n' >&2
  exit 1
}
INTERRUPT_OBSERVER="${INTERRUPT_EVIDENCE_ROOT}/migration-lock-observer.txt"
[[ -f "${INTERRUPT_OBSERVER}" && ! -L "${INTERRUPT_OBSERVER}" \
   && "$(file_mode "${INTERRUPT_OBSERVER}")" == 400 \
   && "$(file_nlink "${INTERRUPT_OBSERVER}")" == 1 ]] || {
  printf 'SIGINT observer did not seal partial database evidence\n' >&2
  exit 1
}
grep -Fxq \
  'observer_sample=<staging-ref> observed_at_utc=2026-07-23T12:40:49.500000Z' \
  "${INTERRUPT_OBSERVER}" || {
  printf 'SIGINT observer did not retain its redacted sample\n' >&2
  exit 1
}
grep -Fxq '<psql connection detail redacted>' "${INTERRUPT_OBSERVER}" || {
  printf 'SIGINT observer did not redact its connection detail\n' >&2
  exit 1
}
! grep -Fq 'observer-secret-password' "${INTERRUPT_OBSERVER}"
! grep -Fq 'postgresql://' "${INTERRUPT_OBSERVER}"
! grep -Fq "${STAGING_REF}" "${INTERRUPT_OBSERVER}"
! grep -Fq "${PRODUCTION_REF}" "${INTERRUPT_OBSERVER}"
! grep -q '^evidence_success=' "${INTERRUPT_OBSERVER}"
INTERRUPT_SCRATCH=$(
  find "${INTERRUPT_EVIDENCE_ROOT}" -maxdepth 1 \
    -name '.migration-lock-observer.txt.psql-output.*' -print -quit
)
[[ -z "${INTERRUPT_SCRATCH}" ]] || {
  printf 'SIGINT observer left database-evidence scratch output after retention\n' >&2
  exit 1
}
assert_no_raw_refs "$(<"${TEST_ROOT}/interrupt.stdout")" 'SIGINT observer stdout'
assert_no_raw_refs "$(<"${TEST_ROOT}/interrupt.stderr")" 'SIGINT observer stderr'

# If a write fails after the protected evidence opens, fail closed and preserve
# the mode-0600 scratch rather than deleting the only complete copy.
printf '%s\n' false >"${PSQL_FAIL_PRE_CONTROL}"
printf '%s\n' true >"${PSQL_BLOCK_CONTROL}"
rm -f -- "${PSQL_PID_CAPTURE}"
set -m
env \
  "PATH=${FAKE_BIN}:${PATH}" \
  "GALLR_EXPECTED_STAGING_PROJECT_REF=${STAGING_REF}" \
  "GALLR_PRODUCTION_PROJECT_REF=${PRODUCTION_REF}" \
  "GALLR_STAGING_DATABASE_URL=${DATABASE_URL}" \
  "GALLR_STAGING_REHEARSAL_CONFIRM=${STAGING_REF}" \
  "GALLR_STAGING_EVIDENCE_DIR=${INTERRUPT_FAILURE_EVIDENCE_ROOT}" \
  /bin/bash -c \
    '
      printf() {
        if [[ "${1:-}" == "%s\n" \
           && "${2:-}" == observer_padding=* ]]; then
          builtin printf "%s" "${2:0:32}"
          return 1
        fi
        builtin printf "$@"
      }
      source "$1" observe-locks
    ' \
    bash "${RUNNER}" \
  >"${TEST_ROOT}/interrupt-failure.stdout" \
  2>"${TEST_ROOT}/interrupt-failure.stderr" &
INTERRUPT_FAILURE_RUNNER_PID=$!
set +m
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
  if [[ -s "${PSQL_PID_CAPTURE}" \
     && -f "${INTERRUPT_FAILURE_EVIDENCE_ROOT}/migration-lock-observer.txt" ]]; then
    break
  fi
  /bin/sleep 0.1
done
if [[ ! -s "${PSQL_PID_CAPTURE}" ]]; then
  kill -TERM "${INTERRUPT_FAILURE_RUNNER_PID}" 2>/dev/null || true
  wait "${INTERRUPT_FAILURE_RUNNER_PID}" 2>/dev/null || true
  printf 'SIGINT retention-failure regression did not reach fake psql\n' >&2
  exit 1
fi
INTERRUPT_FAILURE_PSQL_PID=$(<"${PSQL_PID_CAPTURE}")
INTERRUPT_FAILURE_OBSERVER="${INTERRUPT_FAILURE_EVIDENCE_ROOT}/migration-lock-observer.txt"
kill -INT "${INTERRUPT_FAILURE_RUNNER_PID}"
set +e
wait "${INTERRUPT_FAILURE_RUNNER_PID}"
INTERRUPT_FAILURE_RUNNER_STATUS=$?
set -e
printf '%s\n' false >"${PSQL_BLOCK_CONTROL}"
[[ "${INTERRUPT_FAILURE_RUNNER_STATUS}" -eq 1 ]] || {
  printf 'SIGINT retention-failure runner returned %s instead of 1\n' \
    "${INTERRUPT_FAILURE_RUNNER_STATUS}" >&2
  exit 1
}
! kill -0 "${INTERRUPT_FAILURE_PSQL_PID}" 2>/dev/null || {
  printf 'SIGINT retention failure left fake psql running\n' >&2
  exit 1
}
[[ -f "${INTERRUPT_FAILURE_OBSERVER}" \
   && "$(file_mode "${INTERRUPT_FAILURE_OBSERVER}")" == 400 ]] || {
  printf 'SIGINT retention failure did not seal partial evidence\n' >&2
  exit 1
}
INTERRUPT_FAILURE_SCRATCH=$(
  find "${INTERRUPT_FAILURE_EVIDENCE_ROOT}" -maxdepth 1 \
    -name '.migration-lock-observer.txt.psql-output.*' -print -quit
)
[[ -n "${INTERRUPT_FAILURE_SCRATCH}" \
   && -f "${INTERRUPT_FAILURE_SCRATCH}" \
   && ! -L "${INTERRUPT_FAILURE_SCRATCH}" \
   && "$(file_mode "${INTERRUPT_FAILURE_SCRATCH}")" == 600 \
   && "$(file_nlink "${INTERRUPT_FAILURE_SCRATCH}")" == 1 ]] || {
  printf 'SIGINT retention failure did not preserve protected scratch output\n' >&2
  exit 1
}
grep -Fq 'observer-secret-password' "${INTERRUPT_FAILURE_SCRATCH}"
assert_no_raw_refs \
  "$(<"${TEST_ROOT}/interrupt-failure.stdout")" \
  'SIGINT retention-failure stdout'
assert_no_raw_refs \
  "$(<"${TEST_ROOT}/interrupt-failure.stderr")" \
  'SIGINT retention-failure stderr'

printf '%s\n' 'new committed context' > "${REPO_ROOT}/commit-marker.txt"
git -C "${REPO_ROOT}" add commit-marker.txt
git -C "${REPO_ROOT}" commit -qm 'change reviewed context'
expect_chain_reject 'pre-migration evidence from an earlier commit'

mv "${PRE_EVIDENCE}" "${TEST_ROOT}/pre-migration-inventory.previous"
output=$(run_phase pre-migration)
assert_no_raw_refs "${output}" 'refreshed pre-migration run'
PRE_SHA256=$(sha256_file "${PRE_EVIDENCE}")

output=$(run_phase post-migration)
assert_no_raw_refs "${output}" 'valid post-migration run'
[[ "$(file_mode "${POST_MIGRATION_EVIDENCE}")" == 400 ]] || {
  printf 'post-migration evidence is not sealed 0400\n' >&2
  exit 1
}
grep -Fxq "pre_migration_evidence_sha256=${PRE_SHA256}" \
  "${POST_MIGRATION_EVIDENCE}"
[[ "$(tail -n 1 "${POST_MIGRATION_EVIDENCE}")" == \
   'evidence_success=post-migration' ]] || {
  printf 'post-migration evidence lacks a final success marker\n' >&2
  exit 1
}

output=$(run_phase post-import)
assert_no_raw_refs "${output}" 'valid post-import run'
[[ "$(file_mode "${POST_IMPORT_EVIDENCE}")" == 400 ]] || {
  printf 'post-import evidence is not sealed 0400\n' >&2
  exit 1
}
grep -Fxq "pre_migration_evidence_sha256=${PRE_SHA256}" \
  "${POST_IMPORT_EVIDENCE}"
[[ "$(tail -n 1 "${POST_IMPORT_EVIDENCE}")" == \
   'evidence_success=post-import' ]] || {
  printf 'post-import evidence lacks a final success marker\n' >&2
  exit 1
}

printf 'PASS: sealed pre-migration evidence chain rejects stale or malformed evidence before psql\n'
