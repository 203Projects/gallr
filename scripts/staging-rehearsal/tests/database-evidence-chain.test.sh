#!/usr/bin/env bash

# Offline behavioral tests for the sealed pre-migration evidence chain. The
# synthetic repository and fake psql process make any remote database contact
# impossible while still exercising the production runner end to end.

set -euo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
REHEARSAL_DIR=$(cd "${SCRIPT_DIR}/.." && pwd -P)

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
FAKE_BIN="${TEST_ROOT}/bin"
PSQL_LOG="${TEST_ROOT}/psql.log"

mkdir -m 700 \
  "${REPO_ROOT}" \
  "${EVIDENCE_ROOT}" \
  "${FAILED_EVIDENCE_ROOT}" \
  "${FAKE_BIN}"
mkdir -p "${RUNNER_DIR}/lib" "${RUNNER_DIR}/sql"

cp "${REHEARSAL_DIR}/run-database-evidence.sh" \
  "${RUNNER_DIR}/run-database-evidence.sh"
cp "${REHEARSAL_DIR}/lib/validate-database-target.mjs" \
  "${RUNNER_DIR}/lib/validate-database-target.mjs"
cp "${REHEARSAL_DIR}/sql/pre-migration-inventory.sql" \
  "${RUNNER_DIR}/sql/pre-migration-inventory.sql"
cp "${REHEARSAL_DIR}/sql/post-migration-validation.sql" \
  "${RUNNER_DIR}/sql/post-migration-validation.sql"

printf '%s\n' \
  '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  "printf '%s\\n' 'PASS: linked project matches the reviewed staging manifest'" \
  > "${RUNNER_DIR}/assert-linked-staging.sh"

printf '%s\n' \
  '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  '' \
  "printf '%s\\n' called >> \"\${FAKE_PSQL_LOG}\"" \
  '[[ "${PGDATABASE:-}" == "${FAKE_EXPECTED_DATABASE_URL}" ]] || exit 81' \
  '[[ "${PGSSLMODE:-}" == verify-full ]] || exit 82' \
  '[[ -z "${GALLR_STAGING_DATABASE_URL+x}" ]] || exit 83' \
  '[[ -z "${GALLR_EXPECTED_STAGING_PROJECT_REF+x}" ]] || exit 84' \
  '[[ -z "${GALLR_PRODUCTION_PROJECT_REF+x}" ]] || exit 85' \
  '[[ -z "${GALLR_STAGING_REHEARSAL_CONFIRM+x}" ]] || exit 86' \
  '[[ -z "${GALLR_STAGING_EVIDENCE_DIR+x}" ]] || exit 87' \
  '' \
  'sql_file=' \
  'expected_legacy_payload_sha256=' \
  'for argument in "$@"; do' \
  '  [[ "${argument}" != *postgresql://* && "${argument}" != *postgres://* ]] || exit 88' \
  '  [[ "${argument}" != *"${FAKE_STAGING_REF}"* ]] || exit 89' \
  '  [[ "${argument}" != *"${FAKE_PRODUCTION_REF}"* ]] || exit 90' \
  'done' \
  'while [[ $# -gt 0 ]]; do' \
  '  case "$1" in' \
  '    -f)' \
  '      shift' \
  '      [[ $# -gt 0 ]] || exit 91' \
  '      sql_file="$1"' \
  '      ;;' \
  '    expected_legacy_payload_sha256=*)' \
  '      expected_legacy_payload_sha256=${1#*=}' \
  '      ;;' \
  '  esac' \
  '  shift' \
  'done' \
  'case "${sql_file}" in' \
  '  */pre-migration-inventory.sql)' \
  '    if [[ "${FAKE_PSQL_FAIL_PRE:-false}" == true ]]; then' \
  "      printf '%s\\n' 'simulated pre-migration failure'" \
  '      exit 92' \
  '    fi' \
  "    printf 'legacy_full_payload_sha256=%s\\n' \"\${FAKE_LEGACY_SHA256}\"" \
  '    ;;' \
  '  */post-migration-validation.sql)' \
  '    [[ "${expected_legacy_payload_sha256}" == "${FAKE_LEGACY_SHA256}" ]] || exit 93' \
  "    printf '%s\\n' 'post-migration validation complete'" \
  '    ;;' \
  '  *) exit 94 ;;' \
  'esac' \
  > "${FAKE_BIN}/psql"

chmod +x \
  "${RUNNER_DIR}/run-database-evidence.sh" \
  "${RUNNER_DIR}/assert-linked-staging.sh" \
  "${FAKE_BIN}/psql"

git -C "${REPO_ROOT}" init -q
git -C "${REPO_ROOT}" config user.name 'Evidence Chain Test'
git -C "${REPO_ROOT}" config user.email 'evidence-chain@example.test'
git -C "${REPO_ROOT}" add scripts
git -C "${REPO_ROOT}" commit -qm 'test baseline'

STAGING_REF='aaaaaaaaaaaaaaaaaaaa'
PRODUCTION_REF='bbbbbbbbbbbbbbbbbbbb'
DATABASE_URL="postgresql://postgres:test@db.${STAGING_REF}.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=%2Ftmp%2Fgallr-staging-root-ca.pem"
LEGACY_SHA256='cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc'
RUNNER="${RUNNER_DIR}/run-database-evidence.sh"
PRE_EVIDENCE="${EVIDENCE_ROOT}/pre-migration-inventory.txt"
POST_MIGRATION_EVIDENCE="${EVIDENCE_ROOT}/post-migration-validation.txt"
POST_IMPORT_EVIDENCE="${EVIDENCE_ROOT}/post-import-validation.txt"
GOLDEN_PRE="${TEST_ROOT}/pre-migration-inventory.golden"

printf '%s\n' 'operator_manifest=test-fixture' \
  > "${EVIDENCE_ROOT}/operator-manifest.txt"
cp "${EVIDENCE_ROOT}/operator-manifest.txt" \
  "${FAILED_EVIDENCE_ROOT}/operator-manifest.txt"
: > "${PSQL_LOG}"

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

psql_calls() {
  wc -l < "${PSQL_LOG}" | tr -d ' '
}

run_phase() {
  local phase="$1"
  local evidence_dir="${2:-${EVIDENCE_ROOT}}"
  local fail_pre="${3:-false}"

  env \
    "PATH=${FAKE_BIN}:${PATH}" \
    "FAKE_PSQL_LOG=${PSQL_LOG}" \
    "FAKE_EXPECTED_DATABASE_URL=${DATABASE_URL}" \
    "FAKE_STAGING_REF=${STAGING_REF}" \
    "FAKE_PRODUCTION_REF=${PRODUCTION_REF}" \
    "FAKE_LEGACY_SHA256=${LEGACY_SHA256}" \
    "FAKE_PSQL_FAIL_PRE=${fail_pre}" \
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

printf '%s\n' 'manifest changed after pre-migration' \
  >> "${EVIDENCE_ROOT}/operator-manifest.txt"
expect_chain_reject 'stale operator manifest digest'
cp "${FAILED_EVIDENCE_ROOT}/operator-manifest.txt" \
  "${EVIDENCE_ROOT}/operator-manifest.txt"
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
