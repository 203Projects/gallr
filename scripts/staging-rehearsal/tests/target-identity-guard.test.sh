#!/usr/bin/env bash

# Network-free behavioral tests. `psql` and `git` are local fakes; any attempt
# to use the credential-bearing URL as an argv value fails the test.

set -euo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
REHEARSAL_DIR=$(cd "$SCRIPT_DIR/.." && pwd -P)
TEST_CA_SOURCE="$SCRIPT_DIR/fixtures/test-root-ca.pem"
SOURCE_GUARD="$REHEARSAL_DIR/assert-disposable-clone-target.sh"
REAL_NODE=$(command -v node)

TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/gallr-target-identity.XXXXXX")
TEST_ROOT=$(cd "$TEST_ROOT" && pwd -P)
case "$TEST_ROOT" in
  /tmp/*|/private/tmp/*|/private/var/*) ;;
  *) printf 'unexpected temporary path\n' >&2; exit 1 ;;
esac

cleanup() {
  local status=$1
  local failed_command=$2
  trap - EXIT
  trap '' HUP INT QUIT TERM
  rm -rf -- "$TEST_ROOT"
  if ((status != 0)); then
    printf 'target identity guard test failed after command: %s\n' \
      "$failed_command" >&2
  fi
  exit "$status"
}
trap 'cleanup "$?" "${BASH_COMMAND-unknown}"' EXIT
trap 'cleanup 129 "signal HUP"' HUP
trap 'cleanup 130 "signal INT"' INT
trap 'cleanup 131 "signal QUIT"' QUIT
trap 'cleanup 143 "signal TERM"' TERM

FAKE_REPO_ROOT="$TEST_ROOT/repository"
EVIDENCE_ROOT="$TEST_ROOT/evidence"
SECURE_ROOT="$TEST_ROOT/secure"
FAKE_BIN="$TEST_ROOT/bin"
CONTROL_DIR="$TEST_ROOT/control"
FAKE_PSQL_LOG="$TEST_ROOT/psql-called"
mkdir -m 700 \
  "$FAKE_REPO_ROOT" "$EVIDENCE_ROOT" "$SECURE_ROOT" "$FAKE_BIN" "$CONTROL_DIR"
TEST_CA_PATH="$SECURE_ROOT/test-root-ca.pem"
cp "$TEST_CA_SOURCE" "$TEST_CA_PATH"
chmod 0400 "$TEST_CA_PATH"
TEST_CA_URI_PATH="${TEST_CA_PATH//\//%2F}"
mkdir -p "$FAKE_REPO_ROOT/supabase/migrations" "$FAKE_REPO_ROOT/supabase/.temp"
mkdir -p "$FAKE_REPO_ROOT/scripts/staging-rehearsal/lib"
mkdir -p "$FAKE_REPO_ROOT/scripts/staging-rehearsal/sql"

# The production guard now proves that its checked-in location belongs to the
# Git root it is reviewing. Mirror that structure in the synthetic repository
# instead of weakening the guard or lying about a different source tree.
cp "$SOURCE_GUARD" \
  "$FAKE_REPO_ROOT/scripts/staging-rehearsal/assert-disposable-clone-target.sh"
cp "$REHEARSAL_DIR/assert-linked-staging.sh" \
  "$FAKE_REPO_ROOT/scripts/staging-rehearsal/assert-linked-staging.sh"
cp "$REHEARSAL_DIR/lib/validate-database-target.mjs" \
  "$FAKE_REPO_ROOT/scripts/staging-rehearsal/lib/validate-database-target.mjs"
cp "$REHEARSAL_DIR/lib/database-target.mjs" \
  "$FAKE_REPO_ROOT/scripts/staging-rehearsal/lib/database-target.mjs"
cp "$REHEARSAL_DIR/lib/run-psql-with-validated-target.mjs" \
  "$FAKE_REPO_ROOT/scripts/staging-rehearsal/lib/run-psql-with-validated-target.mjs"
cp "$REHEARSAL_DIR/lib/reviewed-toolchain.sh" \
  "$FAKE_REPO_ROOT/scripts/staging-rehearsal/lib/reviewed-toolchain.sh"
cp "$REHEARSAL_DIR/lib/validate-target-identity-policy.mjs" \
  "$FAKE_REPO_ROOT/scripts/staging-rehearsal/lib/validate-target-identity-policy.mjs"
cp "$REHEARSAL_DIR/sql/assert-disposable-clone-marker.sql" \
  "$FAKE_REPO_ROOT/scripts/staging-rehearsal/sql/assert-disposable-clone-marker.sql"
chmod +x \
  "$FAKE_REPO_ROOT/scripts/staging-rehearsal/assert-disposable-clone-target.sh" \
  "$FAKE_REPO_ROOT/scripts/staging-rehearsal/assert-linked-staging.sh"
GUARD="$FAKE_REPO_ROOT/scripts/staging-rehearsal/assert-disposable-clone-target.sh"

STAGING_REF='ssssssssssssssssssss'
PRODUCTION_REF='pppppppppppppppppppp'
COMMIT='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
CHANGE_RECORD='CHG-IDENTITY-001'
EXECUTOR='executor@example.test'
REVIEWER='reviewer@example.test'
APPROVER_TWO='identity@example.test'
MARKER_ID='123e4567-e89b-42d3-a456-426614174000'
ENCODED_DATABASE_PASSWORD='test%3Apass%5Cword'
EXPECTED_PGPASS_PASSWORD='test\:pass\\word'
DATABASE_URL="postgresql://postgres:${ENCODED_DATABASE_PASSWORD}@db.${STAGING_REF}.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=${TEST_CA_URI_PATH}"
POLICY_PATH="$SECURE_ROOT/identity-policy.txt"
MANIFEST_PATH="$EVIDENCE_ROOT/operator-manifest.txt"
MIGRATION_PATH="$FAKE_REPO_ROOT/supabase/migrations/example.sql"

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

utc_after() {
  "${REAL_NODE}" -e \
    'process.stdout.write(new Date(Date.now() + Number(process.argv[1])).toISOString().replace(/\.\d{3}Z$/, "Z"))' \
    -- "$1"
}

printf '%s\n' '-- test migration' > "$MIGRATION_PATH"
printf '%s\n' "$STAGING_REF" > "$FAKE_REPO_ROOT/supabase/.temp/project-ref"
STAGING_SHA=$(sha256_text "$STAGING_REF")
PRODUCTION_SHA=$(sha256_text "$PRODUCTION_REF")
MIGRATION_SHA=$(sha256_file "$MIGRATION_PATH")
printf '%s\n' "$PRODUCTION_SHA" \
  > "$FAKE_REPO_ROOT/scripts/staging-rehearsal/production-project-ref.sha256"

write_manifest() {
  chmod 600 "$MANIFEST_PATH" 2>/dev/null || true
  {
    printf 'manifest_schema=1\n'
    printf 'run_id=identity-guard-test\n'
    printf 'generated_at_utc=%s\n' "$(utc_after -60000)"
    printf 'target=staging\n'
    printf 'change_record=%s\n' "$CHANGE_RECORD"
    printf 'executor=%s\n' "$EXECUTOR"
    printf 'reviewer=%s\n' "$REVIEWER"
    printf 'repository_commit=%s\n' "$COMMIT"
    printf 'staging_project_ref_sha256=%s\n' "$STAGING_SHA"
    printf 'production_project_ref_sha256=%s\n' "$PRODUCTION_SHA"
    printf 'reviewed_node_path=%s\n' "$REVIEWED_NODE_PATH"
    printf 'reviewed_node_sha256=%s\n' "$REVIEWED_NODE_SHA256"
    printf 'reviewed_psql_path=%s\n' "$REVIEWED_PSQL_PATH"
    printf 'reviewed_psql_sha256=%s\n' "$REVIEWED_PSQL_SHA256"
    printf 'migration_count=1\n'
    printf '\n[migration_sha256]\n'
    printf '%s  supabase/migrations/example.sql\n' "$MIGRATION_SHA"
  } > "$MANIFEST_PATH"
  chmod 444 "$MANIFEST_PATH"
}

write_policy() {
  local issued_at="$1"
  local valid_until="$2"
  local approver_one="${3:-$REVIEWER}"
  local approver_two="${4:-$APPROVER_TWO}"
  chmod 600 "$POLICY_PATH" 2>/dev/null || true
  {
    printf 'policy_schema=1\n'
    printf 'policy_kind=gallr_disposable_clone_target\n'
    printf 'issued_at_utc=%s\n' "$issued_at"
    printf 'valid_until_utc=%s\n' "$valid_until"
    printf 'staging_project_ref_sha256=%s\n' "$STAGING_SHA"
    printf 'production_project_ref_sha256=%s\n' "$PRODUCTION_SHA"
    printf 'repository_commit=%s\n' "$COMMIT"
    printf 'operator_manifest_sha256=%s\n' "$(sha256_file "$MANIFEST_PATH")"
    printf 'change_record=%s\n' "$CHANGE_RECORD"
    printf 'approver_one=%s\n' "$approver_one"
    printf 'approver_two=%s\n' "$approver_two"
    printf 'marker_id=%s\n' "$MARKER_ID"
  } > "$POLICY_PATH"
  chmod 400 "$POLICY_PATH"
}

cat > "$FAKE_BIN/git" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
fake_root=$(CDPATH= cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
for forbidden in \
  database_url staging_ref production_ref policy_record marker_id \
  DATABASE_URL SUPABASE_ANON_KEY SUPABASE_SERVICE_ROLE_KEY \
  GALLR_GOVERNANCE_MODE GALLR_SOLO_OPERATOR_FIRST_CONFIRMATION \
  GALLR_EXECUTOR GALLR_REVIEWER GALLR_CHANGE_RECORD; do
  [[ "${!forbidden+x}" != x ]] || exit 92
done
case "$*" in
  *'rev-parse --show-toplevel') printf '%s\n' "${fake_root}/repository" ;;
  *'rev-parse HEAD') cat "${fake_root}/control/commit" ;;
  *'status --porcelain=v1 --untracked-files=all') : ;;
  *) printf 'unexpected fake git invocation\n' >&2; exit 91 ;;
esac
EOF

cat > "$FAKE_BIN/node" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
fake_root=$(CDPATH= cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
real_node=$(< "${fake_root}/control/real-node")
exec "${real_node}" \
  --require "${fake_root}/control/policy-stat-preload.cjs" \
  "$@"
EOF

cat > "$FAKE_BIN/psql" <<'EOF'
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
psql_log="${fake_root}/psql-called"
source_ca_path="${fake_root}/secure/test-root-ca.pem"
marker_sql="${fake_root}/repository/scripts/staging-rehearsal/sql/assert-disposable-clone-marker.sql"
expected_staging_ref=$(< "${control_dir}/staging-ref")
expected_production_ref=$(< "${control_dir}/production-ref")
expected_pgpass_password=$(< "${control_dir}/expected-pgpass-password")
expected_governance_mode=$(< "${control_dir}/expected-governance-mode")
expected_operator_identity=$(< "${control_dir}/expected-operator-identity")
expected_first_confirmation_sha=$(< "${control_dir}/expected-first-confirmation-sha")
expected_second_confirmation_sha=$(< "${control_dir}/expected-second-confirmation-sha")
expected_effective_first_attestation=$(< "${control_dir}/expected-effective-first-attestation")
expected_minimum_cooldown=$(< "${control_dir}/expected-minimum-cooldown")
expected_destructive_actions=$(< "${control_dir}/expected-destructive-actions")
marker_mode=$(< "${control_dir}/marker-mode")

printf 'called\n' >> "${psql_log}"
for forbidden in \
  database_url staging_ref production_ref policy_record \
  DATABASE_URL SUPABASE_ANON_KEY SUPABASE_SERVICE_ROLE_KEY \
  GALLR_VALIDATION_DATABASE_URL GALLR_VALIDATION_PROJECT_REF \
  GALLR_VALIDATION_REQUIRE_DIRECT GALLR_VALIDATION_SSLROOTCERT_SHA256 \
  GALLR_PSQL_APPNAME GALLR_PSQL_CONNECT_TIMEOUT GALLR_PSQL_OPTIONS \
  GALLR_VALIDATED_PSQL_PATH GALLR_VALIDATED_PSQL_SHA256 \
  GALLR_GOVERNANCE_MODE GALLR_SOLO_OPERATOR_FIRST_CONFIRMATION \
  GALLR_EXECUTOR GALLR_REVIEWER GALLR_CHANGE_RECORD; do
  [[ "${!forbidden+x}" != x ]] || exit 80
done
[[ "${PGHOST:-}" == "db.${expected_staging_ref}.supabase.co" ]] || exit 81
[[ "${PGPORT:-}" == 5432 && "${PGDATABASE:-}" == postgres \
   && "${PGUSER:-}" == postgres ]] || exit 98
[[ "${PGOPTIONS:-}" == \
  '-c default_transaction_read_only=on -c statement_timeout=10000 -c lock_timeout=3000' ]] \
  || exit 82
[[ "${PGSSLMODE:-}" == 'verify-full' ]] || exit 83
[[ "${PGGSSENCMODE:-}" == disable ]] || exit 107
[[ "${PGSSLCERTMODE:-}" == disable ]] || exit 108
[[ "${PGAPPNAME:-}" == gallr-disposable-clone-identity-check \
   && "${PGCONNECT_TIMEOUT:-}" == 15 ]] || exit 99
[[ -z "${PGPASSWORD+x}" && -z "${PGHOSTADDR+x}" \
   && -z "${PGSERVICE+x}" && -z "${PGSERVICEFILE+x}" ]] || exit 100
[[ -n "${PGPASSFILE:-}" && "${PGPASSFILE}" != /dev/null \
   && -f "${PGPASSFILE}" && ! -L "${PGPASSFILE}" && -O "${PGPASSFILE}" ]] \
  || exit 101
passfile_mode=$(portable_stat_mode "${PGPASSFILE}")
[[ "${passfile_mode}" == 600 \
   && "$(wc -l < "${PGPASSFILE}" | tr -d ' ')" == 1 \
   && "$(< "${PGPASSFILE}")" == \
      "db.${expected_staging_ref}.supabase.co:5432:postgres:postgres:${expected_pgpass_password}" ]] \
  || exit 102
[[ -n "${PGSSLROOTCERT:-}" && "${PGSSLROOTCERT}" != "${source_ca_path}" \
   && -f "${PGSSLROOTCERT}" && ! -L "${PGSSLROOTCERT}" \
   && -O "${PGSSLROOTCERT}" ]] || exit 103
certificate_mode=$(portable_stat_mode "${PGSSLROOTCERT}")
certificate_parent_mode=$(portable_stat_mode "$(dirname "${PGSSLROOTCERT}")")
[[ "${certificate_mode}" == 400 && "${certificate_parent_mode}" == 700 ]] || exit 104
cmp -s "${PGSSLROOTCERT}" "${source_ca_path}" || exit 105
while IFS='=' read -r environment_name environment_value; do
  [[ "${environment_value}" != *postgresql://* \
     && "${environment_value}" != *postgres://* ]] || exit 106
done < <(env)
sql_file=
for argument in "$@"; do
  [[ "$argument" != *'postgresql://'* && "$argument" != *'postgres://'* ]] || exit 84
  [[ "$argument" != *"$expected_staging_ref"* ]] || exit 85
  [[ "$argument" != *"$expected_production_ref"* ]] || exit 86
  case "$argument" in
    expected_governance_mode=*) governance_mode=${argument#*=} ;;
    expected_operator_identity=*) operator_identity=${argument#*=} ;;
    expected_first_confirmation_sha256=*) first_confirmation_sha=${argument#*=} ;;
    expected_second_confirmation_sha256=*) second_confirmation_sha=${argument#*=} ;;
    expected_effective_first_attestation_utc=*) effective_first_attestation=${argument#*=} ;;
    expected_minimum_cooldown_seconds=*) minimum_cooldown=${argument#*=} ;;
    expected_destructive_actions=*) destructive_actions=${argument#*=} ;;
    */assert-disposable-clone-marker.sql) sql_file=${argument} ;;
  esac
done
[[ -n "${sql_file}" && "$(basename -- "${sql_file}")" == \
  assert-disposable-clone-marker.sql ]] || exit 109
[[ "${sql_file}" != "${marker_sql}" \
   && -f "${sql_file}" && ! -L "${sql_file}" && -O "${sql_file}" ]] || exit 110
case "${sql_file}" in
  /tmp/gallr-validated-psql-*/sql-0/assert-disposable-clone-marker.sql|\
  /private/tmp/gallr-validated-psql-*/sql-0/assert-disposable-clone-marker.sql) ;;
  *) exit 111 ;;
esac
sql_mode=$(portable_stat_mode "${sql_file}")
sql_parent_mode=$(portable_stat_mode "$(dirname -- "${sql_file}")")
[[ "${sql_mode}" == 400 && "${sql_parent_mode}" == 700 ]] || exit 112
cmp -s "${sql_file}" "${marker_sql}" || exit 113
[[ "${governance_mode:-}" == "${expected_governance_mode}" ]] || exit 89
[[ "${operator_identity:-}" == "${expected_operator_identity}" ]] || exit 90
[[ "${first_confirmation_sha:-}" == "${expected_first_confirmation_sha}" ]] || exit 93
[[ "${second_confirmation_sha:-}" == "${expected_second_confirmation_sha}" ]] || exit 94
[[ "${effective_first_attestation:-}" == "${expected_effective_first_attestation}" ]] || exit 95
[[ "${minimum_cooldown:-}" == "${expected_minimum_cooldown}" ]] || exit 96
[[ "${destructive_actions:-}" == "${expected_destructive_actions}" ]] || exit 97
case "${marker_mode}" in
  valid) printf 'relation\t1\ttrue\nmarker\t1\t1\n' ;;
  missing) printf 'relation\t1\ttrue\nmarker\t0\t0\n' ;;
  exposed) printf 'relation\t1\tfalse\nmarker\t1\t1\n' ;;
  error) exit 87 ;;
  *) exit 88 ;;
esac
EOF
chmod +x "$FAKE_BIN/git" "$FAKE_BIN/node" "$FAKE_BIN/psql"

REVIEWED_NODE_PATH="$FAKE_BIN/node"
REVIEWED_PSQL_PATH="$FAKE_BIN/psql"
REVIEWED_NODE_SHA256=$(sha256_file "$REVIEWED_NODE_PATH")
REVIEWED_PSQL_SHA256=$(sha256_file "$REVIEWED_PSQL_PATH")

# The linked-staging guard runs through the fixed clean PATH. Give it a real,
# clean synthetic repository instead of relying on the PATH fake used by the
# outer guard's isolated command assertions.
git -C "$FAKE_REPO_ROOT" init -q
git -C "$FAKE_REPO_ROOT" config user.name 'Gallr Test'
git -C "$FAKE_REPO_ROOT" config user.email 'gallr-test@example.invalid'
git -C "$FAKE_REPO_ROOT" add .
GIT_AUTHOR_DATE='2026-07-01T00:00:00Z' \
GIT_COMMITTER_DATE='2026-07-01T00:00:00Z' \
  git -C "$FAKE_REPO_ROOT" commit -q -m 'synthetic guard fixture'
COMMIT=$(git -C "$FAKE_REPO_ROOT" rev-parse HEAD)

printf '%s\n' "${REAL_NODE}" > "${CONTROL_DIR}/real-node"
cat > "${CONTROL_DIR}/policy-stat-preload.cjs" <<'EOF'
"use strict";
const fs = require("node:fs");
const path = require("node:path");
const originalCloseSync = fs.closeSync;
const originalOpenSync = fs.openSync;
const originalFstatSync = fs.fstatSync;
const originalStatSync = fs.statSync;
const policyDescriptors = new Set();
const fakeRoot = path.resolve(__dirname, "..");
const policyPath = path.join(fakeRoot, "secure", "identity-policy.txt");
function applyPolicyMetadataTime(stat) {
  const metadataTime = Number(
    fs.readFileSync(path.join(__dirname, "policy-metadata-time-ms"), "utf8")
  );
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
fs.statSync = function patchedStatSync(target, ...args) {
  const stat = originalStatSync.call(fs, target, ...args);
  return String(target) === policyPath ? applyPolicyMetadataTime(stat) : stat;
};
EOF
"${REAL_NODE}" -e \
  'process.stdout.write(String(Date.now() - 20 * 60 * 1000))' \
  > "${CONTROL_DIR}/policy-metadata-time-ms"
printf '%s\n' "${COMMIT}" > "${CONTROL_DIR}/commit"
printf '%s\n' "${STAGING_REF}" > "${CONTROL_DIR}/staging-ref"
printf '%s\n' "${PRODUCTION_REF}" > "${CONTROL_DIR}/production-ref"

write_manifest
write_policy "$(utc_after -60000)" "$(utc_after 3600000)"

base_env=(
  "PATH=$FAKE_BIN:$PATH"
  "GALLR_EXPECTED_STAGING_PROJECT_REF=$STAGING_REF"
  "GALLR_PRODUCTION_PROJECT_REF=$PRODUCTION_REF"
  "GALLR_STAGING_DATABASE_URL=$DATABASE_URL"
  "GALLR_STAGING_REHEARSAL_CONFIRM=$STAGING_REF"
  "GALLR_STAGING_EVIDENCE_DIR=$EVIDENCE_ROOT"
  "GALLR_STAGING_IDENTITY_POLICY_PATH=$POLICY_PATH"
  "database_url=$DATABASE_URL"
  "staging_ref=$STAGING_REF"
  "production_ref=$PRODUCTION_REF"
  'policy_record=must-not-reach-child'
  'marker_id=must-not-reach-child'
  "DATABASE_URL=$DATABASE_URL"
  'SUPABASE_ANON_KEY=must-not-reach-child'
  'SUPABASE_SERVICE_ROLE_KEY=must-not-reach-child'
  'GALLR_GOVERNANCE_MODE=must-not-reach-child'
  'GALLR_SOLO_OPERATOR_FIRST_CONFIRMATION=must-not-reach-child'
  'GALLR_EXECUTOR=must-not-reach-child'
  'GALLR_REVIEWER=must-not-reach-child'
  'GALLR_CHANGE_RECORD=must-not-reach-child'
)

EXPECTED_GOVERNANCE_MODE=separated_humans
EXPECTED_OPERATOR_IDENTITY=
EXPECTED_FIRST_CONFIRMATION_SHA=
EXPECTED_SECOND_CONFIRMATION_SHA=
EXPECTED_EFFECTIVE_FIRST_ATTESTATION=
EXPECTED_MINIMUM_COOLDOWN=
EXPECTED_DESTRUCTIVE_ACTIONS=

run_guard() {
  printf '%s' "${1:-valid}" > "${CONTROL_DIR}/marker-mode"
  printf '%s' "${EXPECTED_PGPASS_PASSWORD}" \
    > "${CONTROL_DIR}/expected-pgpass-password"
  printf '%s' "${EXPECTED_GOVERNANCE_MODE}" \
    > "${CONTROL_DIR}/expected-governance-mode"
  printf '%s' "${EXPECTED_OPERATOR_IDENTITY}" \
    > "${CONTROL_DIR}/expected-operator-identity"
  printf '%s' "${EXPECTED_FIRST_CONFIRMATION_SHA}" \
    > "${CONTROL_DIR}/expected-first-confirmation-sha"
  printf '%s' "${EXPECTED_SECOND_CONFIRMATION_SHA}" \
    > "${CONTROL_DIR}/expected-second-confirmation-sha"
  printf '%s' "${EXPECTED_EFFECTIVE_FIRST_ATTESTATION}" \
    > "${CONTROL_DIR}/expected-effective-first-attestation"
  printf '%s' "${EXPECTED_MINIMUM_COOLDOWN}" \
    > "${CONTROL_DIR}/expected-minimum-cooldown"
  printf '%s' "${EXPECTED_DESTRUCTIVE_ACTIONS}" \
    > "${CONTROL_DIR}/expected-destructive-actions"
  env -i "${base_env[@]}" bash "$GUARD" 2>&1
}

expect_pass() {
  local output
  : > "$FAKE_PSQL_LOG"
  if ! output=$(run_guard valid); then
    printf 'expected pass, guard failed: %s\n' "$output" >&2
    exit 1
  fi
  [[ "$output" == *'PASS: independent policy and disposable-clone marker identify staging'* ]] || {
    printf 'expected pass, got: %s\n' "$output" >&2
    exit 1
  }
  [[ "$(wc -l < "$FAKE_PSQL_LOG" | tr -d ' ')" == '1' ]] || {
    printf 'expected exactly one fake psql call\n' >&2
    exit 1
  }
  [[ "$output" != *"$STAGING_REF"* && "$output" != *"$PRODUCTION_REF"* ]] || {
    printf 'guard disclosed a raw project ref\n' >&2
    exit 1
  }
}

expect_fail() {
  local name="$1"
  local mode="${2:-valid}"
  local expected_calls="${3:-0}"
  local output
  : > "$FAKE_PSQL_LOG"
  if output=$(run_guard "$mode"); then
    printf '%s unexpectedly passed\n' "$name" >&2
    exit 1
  fi
  [[ "$output" != *"$STAGING_REF"* && "$output" != *"$PRODUCTION_REF"* ]] || {
    printf '%s disclosed a raw project ref\n' "$name" >&2
    exit 1
  }
  [[ "$(wc -l < "$FAKE_PSQL_LOG" | tr -d ' ')" == "$expected_calls" ]] || {
    printf '%s made an unexpected number of database calls\n' "$name" >&2
    exit 1
  }
}

expect_pass
expect_fail 'missing marker' missing 1
expect_fail 'exposed marker relation' exposed 1
expect_fail 'marker query error' error 1

# A repository-local clean filter must neither hide a modified marker query nor
# execute inside the credential-bearing guard. Refresh the index stat cache so
# ordinary porcelain reports clean, then require rejection before psql.
FILTERED_MARKER_RELATIVE=scripts/staging-rehearsal/sql/assert-disposable-clone-marker.sql
FILTERED_MARKER_PATH="$FAKE_REPO_ROOT/$FILTERED_MARKER_RELATIVE"
FILTER_ROOT="$TEST_ROOT/linked-clean-filter"
FILTER_ORIGINAL="$FILTER_ROOT/original"
FILTER_PROGRAM="$FILTER_ROOT/clean"
FILTER_SIDE_EFFECT="$FILTER_ROOT/filter-invoked"
mkdir -m 700 "$FILTER_ROOT"
cp -p "$FILTERED_MARKER_PATH" "$FILTER_ORIGINAL"
cat > "$FILTER_PROGRAM" <<'EOF'
#!/bin/sh
filter_directory=${0%/*}
/bin/cat >/dev/null
: > "$filter_directory/filter-invoked"
exec /bin/cat "$filter_directory/original"
EOF
chmod 700 "$FILTER_PROGRAM"
[[ ! -e "$FAKE_REPO_ROOT/.git/info/attributes" ]]
printf '%s filter=gallr-linked-hide\n' "$FILTERED_MARKER_RELATIVE" \
  > "$FAKE_REPO_ROOT/.git/info/attributes"
git -C "$FAKE_REPO_ROOT" config \
  filter.gallr-linked-hide.clean "\"$FILTER_PROGRAM\""
git -C "$FAKE_REPO_ROOT" config filter.gallr-linked-hide.required true
printf '%s\n' '\echo unreviewed-marker-query' > "$FILTERED_MARKER_PATH"
FILTERED_MARKER_HEAD_BLOB=$(git -C "$FAKE_REPO_ROOT" rev-parse \
  "$COMMIT:$FILTERED_MARKER_RELATIVE")
[[ "$(git -C "$FAKE_REPO_ROOT" hash-object -- \
  "$FILTERED_MARKER_RELATIVE")" == "$FILTERED_MARKER_HEAD_BLOB" ]]
git -C "$FAKE_REPO_ROOT" add --renormalize -- \
  "$FILTERED_MARKER_RELATIVE" >/dev/null
[[ -z "$(git -C "$FAKE_REPO_ROOT" status --porcelain=v1 -- \
  "$FILTERED_MARKER_RELATIVE")" ]]
rm -f -- "$FILTER_SIDE_EFFECT"
expect_fail 'marker SQL clean-filter bypass' valid 0
[[ ! -e "$FILTER_SIDE_EFFECT" ]]
cp -p "$FILTER_ORIGINAL" "$FILTERED_MARKER_PATH"
git -C "$FAKE_REPO_ROOT" config --unset-all \
  filter.gallr-linked-hide.clean
git -C "$FAKE_REPO_ROOT" config --unset-all \
  filter.gallr-linked-hide.required
rm -f -- "$FAKE_REPO_ROOT/.git/info/attributes"
# Linux can retain a racy worktree stat entry after the clean filter is
# removed even though the restored bytes exactly match the index. Re-adding
# the restored path refreshes that cache; a bad restoration would instead
# stage a real difference and remain visible to the status assertion.
git -C "$FAKE_REPO_ROOT" add -- "$FILTERED_MARKER_RELATIVE"
[[ -z "$(git -C "$FAKE_REPO_ROOT" status --porcelain=v1 -- \
  "$FILTERED_MARKER_RELATIVE")" ]]
expect_pass

# Git's ordinary porcelain status hides worktree changes for paths marked
# assume-unchanged. The linked-target guard must reject the index flag itself
# before any database connection.
git -C "$FAKE_REPO_ROOT" update-index --assume-unchanged -- \
  supabase/migrations/example.sql
printf '%s\n' '-- hidden mutation' > "$MIGRATION_PATH"
expect_fail 'assume-unchanged index bypass'
printf '%s\n' '-- test migration' > "$MIGRATION_PATH"
git -C "$FAKE_REPO_ROOT" update-index --no-assume-unchanged -- \
  supabase/migrations/example.sql
expect_pass

# Replacement refs can alter a blob returned by ordinary `git show HEAD:path`
# without changing HEAD or the worktree. The linked-target guard must ignore the
# spoofed production-anchor blob and continue validating the reviewed bytes.
ANCHOR_RELATIVE_PATH=scripts/staging-rehearsal/production-project-ref.sha256
ANCHOR_BLOB=$(git -C "$FAKE_REPO_ROOT" rev-parse \
  "$COMMIT:$ANCHOR_RELATIVE_PATH")
SPOOFED_PRODUCTION_REF=cccccccccccccccccccc
SPOOFED_PRODUCTION_SHA=$(sha256_text "$SPOOFED_PRODUCTION_REF")
SPOOFED_ANCHOR_BLOB=$(
  printf '%s\n' "$SPOOFED_PRODUCTION_SHA" |
    git -C "$FAKE_REPO_ROOT" hash-object -w --stdin
)
git -C "$FAKE_REPO_ROOT" replace "$ANCHOR_BLOB" "$SPOOFED_ANCHOR_BLOB"
[[ "$(git -C "$FAKE_REPO_ROOT" show \
  "$COMMIT:$ANCHOR_RELATIVE_PATH")" == "$SPOOFED_PRODUCTION_SHA" ]]
expect_pass
git -C "$FAKE_REPO_ROOT" replace -d "$ANCHOR_BLOB" >/dev/null

chmod 600 "$POLICY_PATH"
expect_fail 'writable policy'
chmod 400 "$POLICY_PATH"

write_policy "$(utc_after -7200000)" "$(utc_after -3600000)"
expect_fail 'expired policy'

write_policy "$(utc_after -60000)" "$(utc_after 3600000)" "$REVIEWER" "$REVIEWER"
expect_fail 'duplicate approvers'

write_policy "$(utc_after -60000)" "$(utc_after 3600000)"
printf '%s\n' "$PRODUCTION_REF" > "$FAKE_REPO_ROOT/supabase/.temp/project-ref"
expect_fail 'linked target changed'
printf '%s\n' "$STAGING_REF" > "$FAKE_REPO_ROOT/supabase/.temp/project-ref"

base_env=("${base_env[@]/GALLR_EXPECTED_STAGING_PROJECT_REF=$STAGING_REF/GALLR_EXPECTED_STAGING_PROJECT_REF=$PRODUCTION_REF}")
base_env=("${base_env[@]/GALLR_PRODUCTION_PROJECT_REF=$PRODUCTION_REF/GALLR_PRODUCTION_PROJECT_REF=$STAGING_REF}")
base_env=("${base_env[@]/GALLR_STAGING_REHEARSAL_CONFIRM=$STAGING_REF/GALLR_STAGING_REHEARSAL_CONFIRM=$PRODUCTION_REF}")
expect_fail 'swapped staging and production labels'

# Restore the target labels and validate the schema-2 solo marker contract.
base_env=("${base_env[@]/GALLR_EXPECTED_STAGING_PROJECT_REF=$PRODUCTION_REF/GALLR_EXPECTED_STAGING_PROJECT_REF=$STAGING_REF}")
base_env=("${base_env[@]/GALLR_PRODUCTION_PROJECT_REF=$STAGING_REF/GALLR_PRODUCTION_PROJECT_REF=$PRODUCTION_REF}")
base_env=("${base_env[@]/GALLR_STAGING_REHEARSAL_CONFIRM=$PRODUCTION_REF/GALLR_STAGING_REHEARSAL_CONFIRM=$STAGING_REF}")

SOLO_OPERATOR='hanshin-lee'
SOLO_INTENT="INTENT STAGING ${STAGING_REF} NOT PRODUCTION ${PRODUCTION_REF} ${COMMIT} ACCEPT_NO_INDEPENDENT_REVIEW"
SOLO_EXECUTION="EXECUTE STAGING ${STAGING_REF} NOT PRODUCTION ${PRODUCTION_REF} ${COMMIT} ACCEPT_NO_INDEPENDENT_REVIEW"
SOLO_FIRST_SHA=$(sha256_text "${SOLO_INTENT}")
SOLO_SECOND_SHA=$(sha256_text "${SOLO_EXECUTION}")
SOLO_GENERATED_AT=$(utc_after -1800000)
SOLO_ISSUED_AT=$(utc_after -1200000)
SOLO_VALID_UNTIL=$(utc_after 3600000)

chmod 600 "${MANIFEST_PATH}"
{
  printf 'manifest_schema=2\n'
  printf 'run_id=identity-guard-solo-test\n'
  printf 'generated_at_utc=%s\n' "${SOLO_GENERATED_AT}"
  printf 'target=staging\n'
  printf 'change_record=%s\n' "${CHANGE_RECORD}"
  printf 'executor=%s\n' "${SOLO_OPERATOR}"
  printf 'reviewer=%s\n' "${SOLO_OPERATOR}"
  printf 'repository_commit=%s\n' "${COMMIT}"
  printf 'staging_project_ref_sha256=%s\n' "${STAGING_SHA}"
  printf 'production_project_ref_sha256=%s\n' "${PRODUCTION_SHA}"
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
  printf '%s  supabase/migrations/example.sql\n' "${MIGRATION_SHA}"
} > "${MANIFEST_PATH}"
chmod 444 "${MANIFEST_PATH}"

chmod 600 "${POLICY_PATH}"
{
  printf 'policy_schema=2\n'
  printf 'policy_kind=gallr_disposable_clone_target\n'
  printf 'governance_mode=solo_operator\n'
  printf 'issued_at_utc=%s\n' "${SOLO_ISSUED_AT}"
  printf 'valid_until_utc=%s\n' "${SOLO_VALID_UNTIL}"
  printf 'minimum_cooldown_seconds=900\n'
  printf 'destructive_actions=forbidden\n'
  printf 'staging_project_ref_sha256=%s\n' "${STAGING_SHA}"
  printf 'production_project_ref_sha256=%s\n' "${PRODUCTION_SHA}"
  printf 'repository_commit=%s\n' "${COMMIT}"
  printf 'operator_manifest_sha256=%s\n' "$(sha256_file "${MANIFEST_PATH}")"
  printf 'change_record=%s\n' "${CHANGE_RECORD}"
  printf 'operator_identity=%s\n' "${SOLO_OPERATOR}"
  printf 'first_confirmation_sha256=%s\n' "${SOLO_FIRST_SHA}"
  printf 'marker_id=%s\n' "${MARKER_ID}"
} > "${POLICY_PATH}"
chmod 400 "${POLICY_PATH}"
"${REAL_NODE}" -e \
  'const fs=require("node:fs"); const at=new Date(process.argv[2]); fs.utimesSync(process.argv[1], at, at);' \
  "${POLICY_PATH}" "${SOLO_ISSUED_AT}"

EXPECTED_GOVERNANCE_MODE=solo_operator
EXPECTED_OPERATOR_IDENTITY="${SOLO_OPERATOR}"
EXPECTED_FIRST_CONFIRMATION_SHA="${SOLO_FIRST_SHA}"
EXPECTED_SECOND_CONFIRMATION_SHA="${SOLO_SECOND_SHA}"
EXPECTED_EFFECTIVE_FIRST_ATTESTATION="${SOLO_ISSUED_AT}"
EXPECTED_MINIMUM_COOLDOWN=900
EXPECTED_DESTRUCTIVE_ACTIONS=forbidden

expect_pass
expect_fail 'missing solo marker' missing 1

printf 'target identity guard tests passed\n'
