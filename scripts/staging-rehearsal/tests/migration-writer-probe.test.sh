#!/usr/bin/env bash

# Network-free orchestration test for the repeating migration writer probe.
# Git, Node, the linked/identity guards, sleep, and psql are local fakes. The
# test proves a fresh target-identity pass immediately precedes every psql
# attempt and that a later identity failure stops the loop before another one.

set -euo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
REHEARSAL_DIR=$(cd "$SCRIPT_DIR/.." && pwd -P)
TEST_CA_SOURCE="$SCRIPT_DIR/fixtures/test-root-ca.pem"
SOURCE_RUNNER="$REHEARSAL_DIR/run-migration-writer-probe.sh"
REAL_NODE=$(command -v node)

TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/gallr-writer-probe.XXXXXX")
TEST_ROOT=$(cd "$TEST_ROOT" && pwd -P)
case "$TEST_ROOT" in
  /tmp/*|/private/tmp/*|/private/var/*) ;;
  *) printf 'unexpected temporary path\n' >&2; exit 1 ;;
esac
trap 'rm -rf -- "$TEST_ROOT"' EXIT HUP INT TERM

STAGING_REF='ssssssssssssssssssss'
PRODUCTION_REF='pppppppppppppppppppp'
ENCODED_DATABASE_PASSWORD='test%3Apass%5Cword'
COMMIT='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
TARGET_ID='legacy-probe-target'

file_mode() {
  if stat -f '%Lp' "$1" >/dev/null 2>&1; then
    stat -f '%Lp' "$1"
  else
    stat -c '%a' "$1"
  fi
}

sha256_file() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

read_counter() {
  local path="$1"
  if [[ -f "$path" ]]; then
    sed -n '1p' "$path"
  else
    printf '0\n'
  fi
}

run_case() {
  local case_name="$1"
  local guard_fail_at="$2"
  local expected_psql_calls="$3"
  local expected_sequence="$4"
  local case_root="$TEST_ROOT/$case_name"
  local repo_root="$case_root/repository"
  local rehearsal_root="$repo_root/scripts/staging-rehearsal"
  local evidence_root="$case_root/evidence"
  local secure_root="$case_root/secure"
  local fake_bin="$case_root/bin"
  local sequence_path="$case_root/sequence.log"
  local guard_count_path="$case_root/guard-count"
  local psql_count_path="$case_root/psql-count"
  local policy_path="$case_root/identity-policy.txt"
  local output_path="$case_root/runner-output.log"
  local test_ca_path="$secure_root/test-root-ca.pem"
  local test_ca_uri_path
  local database_url
  local runner_status

  mkdir -m 700 "$case_root" "$repo_root" "$evidence_root" "$secure_root" "$fake_bin"
  cp "$TEST_CA_SOURCE" "$test_ca_path"
  chmod 0400 "$test_ca_path"
  test_ca_uri_path="${test_ca_path//\//%2F}"
  database_url="postgresql://postgres:${ENCODED_DATABASE_PASSWORD}@db.${STAGING_REF}.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=${test_ca_uri_path}"
  mkdir -p "$rehearsal_root/lib" "$rehearsal_root/sql"
  cp "$SOURCE_RUNNER" "$rehearsal_root/run-migration-writer-probe.sh"
  chmod +x "$rehearsal_root/run-migration-writer-probe.sh"
  cp "$REHEARSAL_DIR/lib/validate-database-target.mjs" \
    "$rehearsal_root/lib/validate-database-target.mjs"
  cp "$REHEARSAL_DIR/lib/database-target.mjs" \
    "$rehearsal_root/lib/database-target.mjs"
  cp "$REHEARSAL_DIR/lib/run-psql-with-validated-target.mjs" \
    "$rehearsal_root/lib/run-psql-with-validated-target.mjs"
  cp "$REHEARSAL_DIR/lib/reviewed-toolchain.sh" \
    "$rehearsal_root/lib/reviewed-toolchain.sh"
  printf '%s\n' '-- fake rollback-only writer SQL' \
    > "$rehearsal_root/sql/migration-writer-probe.sql"
  printf '%s\n' 'test-only-policy' > "$policy_path"
  chmod 400 "$policy_path"

  cat > "$rehearsal_root/assert-linked-staging.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'PASS: linked project matches the reviewed staging manifest\n'
EOF

  {
    printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail'
    printf 'readonly fake_guard_count_path=%q\n' "$guard_count_path"
    printf 'readonly fake_sequence_path=%q\n' "$sequence_path"
    printf 'readonly fake_guard_fail_at=%q\n' "$guard_fail_at"
    cat <<'EOF'
count=0
if [[ -f "${fake_guard_count_path}" ]]; then
  IFS= read -r count < "${fake_guard_count_path}"
fi
count=$((count + 1))
printf '%s\n' "$count" > "${fake_guard_count_path}"
printf 'guard\n' >> "${fake_sequence_path}"
if [[ "$count" -eq "${fake_guard_fail_at}" ]]; then
  exit 71
fi
printf 'PASS: independent policy and disposable-clone marker identify staging\n'
EOF
  } > "$rehearsal_root/assert-disposable-clone-target.sh"
  chmod +x \
    "$rehearsal_root/assert-linked-staging.sh" \
    "$rehearsal_root/assert-disposable-clone-target.sh"

  {
    printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail'
    printf 'readonly real_node=%q\n' "$REAL_NODE"
    cat <<'EOF'
for forbidden in \
  staging_database_url staging_ref_raw production_ref_raw \
  DATABASE_URL SUPABASE_ANON_KEY SUPABASE_SERVICE_ROLE_KEY; do
  [[ "${!forbidden+x}" != x ]] || exit 92
done
exec "${real_node}" "$@"
EOF
  } > "$fake_bin/node"

  cat > "$fake_bin/git" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
for forbidden in \
  staging_database_url staging_ref_raw production_ref_raw \
  DATABASE_URL SUPABASE_ANON_KEY SUPABASE_SERVICE_ROLE_KEY; do
  [[ "${!forbidden+x}" != x ]] || exit 92
done
case "$*" in
  *'rev-parse --show-toplevel') printf '%s\n' "$FAKE_REPO_ROOT" ;;
  *'rev-parse HEAD') printf '%s\n' "$FAKE_COMMIT" ;;
  *) printf 'unexpected fake git invocation\n' >&2; exit 91 ;;
esac
EOF

  cat > "$fake_bin/sleep" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
exit 0
EOF

  {
    printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail'
    printf 'readonly fake_psql_count_path=%q\n' "$psql_count_path"
    printf 'readonly fake_guard_count_path=%q\n' "$guard_count_path"
    printf 'readonly fake_sequence_path=%q\n' "$sequence_path"
    printf 'readonly fake_source_ca_path=%q\n' "$test_ca_path"
    printf '%s\n' \
      "readonly fake_staging_ref='${STAGING_REF}'" \
      "readonly fake_production_ref='${PRODUCTION_REF}'" \
      "readonly fake_expected_pgpass_password='test\\:pass\\\\word'"
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

psql_count=0
guard_count=0
if [[ -f "${fake_psql_count_path}" ]]; then
  IFS= read -r psql_count < "${fake_psql_count_path}"
fi
if [[ -f "${fake_guard_count_path}" ]]; then
  IFS= read -r guard_count < "${fake_guard_count_path}"
fi
psql_count=$((psql_count + 1))

# The probe has one initial identity check, then one immediately before each
# writer attempt. Any other relationship is a fail-closed ordering violation.
[[ "$guard_count" -eq $((psql_count + 1)) ]] || exit 81
[[ "${PGHOST:-}" == "db.${fake_staging_ref}.supabase.co" ]] || exit 82
[[ "${PGPORT:-}" == 5432 && "${PGDATABASE:-}" == postgres \
   && "${PGUSER:-}" == postgres ]] || exit 83
[[ "${PGSSLMODE:-}" == 'verify-full' \
   && "${PGGSSENCMODE:-}" == disable \
   && "${PGSSLCERTMODE:-}" == disable ]] || exit 83
[[ "${PGAPPNAME:-}" == gallr_staging_migration_writer_probe \
   && "${PGCONNECT_TIMEOUT:-}" == 10 ]] || exit 87
[[ -z "${PGOPTIONS+x}" && -z "${PGPASSWORD+x}" \
   && -z "${PGHOSTADDR+x}" && -z "${PGSERVICE+x}" \
   && -z "${PGSERVICEFILE+x}" ]] || exit 88
for forbidden in \
  GALLR_VALIDATION_DATABASE_URL GALLR_VALIDATION_PROJECT_REF \
  GALLR_VALIDATION_REQUIRE_DIRECT GALLR_VALIDATION_SSLROOTCERT_SHA256 \
  GALLR_PSQL_APPNAME GALLR_PSQL_CONNECT_TIMEOUT GALLR_PSQL_OPTIONS \
  GALLR_STAGING_DATABASE_URL; do
  [[ "${!forbidden+x}" != x ]] || exit 89
done
[[ -n "${PGPASSFILE:-}" && "${PGPASSFILE}" != /dev/null \
   && -f "${PGPASSFILE}" && ! -L "${PGPASSFILE}" && -O "${PGPASSFILE}" ]] \
  || exit 90
passfile_mode=$(portable_stat_mode "${PGPASSFILE}")
[[ "${passfile_mode}" == 600 \
   && "$(wc -l < "${PGPASSFILE}" | tr -d ' ')" == 1 \
   && "$(< "${PGPASSFILE}")" == \
      "db.${fake_staging_ref}.supabase.co:5432:postgres:postgres:${fake_expected_pgpass_password}" ]] \
  || exit 91
[[ -n "${PGSSLROOTCERT:-}" && "${PGSSLROOTCERT}" != "${fake_source_ca_path}" \
   && -f "${PGSSLROOTCERT}" && ! -L "${PGSSLROOTCERT}" \
   && -O "${PGSSLROOTCERT}" ]] || exit 92
certificate_mode=$(portable_stat_mode "${PGSSLROOTCERT}")
certificate_parent_mode=$(portable_stat_mode "$(dirname "${PGSSLROOTCERT}")")
[[ "${certificate_mode}" == 400 && "${certificate_parent_mode}" == 700 ]] || exit 93
cmp -s "${PGSSLROOTCERT}" "${fake_source_ca_path}" || exit 94
while IFS='=' read -r environment_name environment_value; do
  [[ "${environment_name}" != FAKE_* \
     && "${environment_name}" != GALLR_* \
     && "${environment_name}" != SUPABASE_* ]] || exit 95
  [[ "${environment_value}" != *postgresql://* \
     && "${environment_value}" != *postgres://* ]] || exit 95
done < <(env)
for argument in "$@"; do
  [[ "$argument" != *'postgresql://'* && "$argument" != *'postgres://'* ]] || exit 84
  [[ "$argument" != *"$fake_staging_ref"* ]] || exit 85
  [[ "$argument" != *"$fake_production_ref"* ]] || exit 86
done

printf '%s\n' "$psql_count" > "${fake_psql_count_path}"
printf 'psql\n' >> "${fake_sequence_path}"
printf 'probe_result=rolled_back\n'
EOF
  } > "$fake_bin/psql"
  chmod +x "$fake_bin/node" "$fake_bin/git" "$fake_bin/sleep" "$fake_bin/psql"

  fake_node_path="$(cd "$fake_bin" && pwd -P)/node"
  fake_psql_path="$(cd "$fake_bin" && pwd -P)/psql"
  printf '%s\n' \
    'manifest=test-only' \
    "reviewed_node_path=${fake_node_path}" \
    "reviewed_node_sha256=$(sha256_file "$fake_node_path")" \
    "reviewed_psql_path=${fake_psql_path}" \
    "reviewed_psql_sha256=$(sha256_file "$fake_psql_path")" \
    > "$evidence_root/operator-manifest.txt"
  chmod 444 "$evidence_root/operator-manifest.txt"

  : > "$sequence_path"
  set +e
  env -i \
    "PATH=$fake_bin:$PATH" \
    BASH_ENV=/dev/null \
    ENV=/dev/null \
    "FAKE_REPO_ROOT=$repo_root" \
    "FAKE_COMMIT=$COMMIT" \
    "GALLR_EXPECTED_STAGING_PROJECT_REF=$STAGING_REF" \
    "GALLR_PRODUCTION_PROJECT_REF=$PRODUCTION_REF" \
    "GALLR_STAGING_DATABASE_URL=$database_url" \
    "GALLR_STAGING_REHEARSAL_CONFIRM=$STAGING_REF" \
    "GALLR_STAGING_EVIDENCE_DIR=$evidence_root" \
    "GALLR_STAGING_IDENTITY_POLICY_PATH=$policy_path" \
    "GALLR_LEGACY_PROBE_EXHIBITION_ID=$TARGET_ID" \
    "staging_database_url=$database_url" \
    "staging_ref_raw=$STAGING_REF" \
    "production_ref_raw=$PRODUCTION_REF" \
    "DATABASE_URL=$database_url" \
    SUPABASE_ANON_KEY=must-not-reach-child \
    SUPABASE_SERVICE_ROLE_KEY=must-not-reach-child \
      bash "$rehearsal_root/run-migration-writer-probe.sh" \
      > "$output_path" 2>&1
  runner_status=$?
  set -e

  [[ "$runner_status" -ne 0 ]] || {
    printf '%s: probe unexpectedly continued after guard rejection\n' "$case_name" >&2
    exit 1
  }
  grep -Fq 'ERROR: disposable-clone target identity failed' "$output_path" || {
    printf '%s: expected identity failure was not reported\n' "$case_name" >&2
    sed -n '1,120p' "$output_path" >&2
    exit 1
  }
  [[ "$(read_counter "$psql_count_path")" == "$expected_psql_calls" ]] || {
    printf '%s: unexpected psql attempt count\n' "$case_name" >&2
    exit 1
  }
  [[ "$(cat "$sequence_path")" == "$expected_sequence" ]] || {
    printf '%s: guard/psql event order was unsafe\n' "$case_name" >&2
    cat "$sequence_path" >&2
    exit 1
  }
  [[ -f "$evidence_root/migration-writer-probe.txt" ]] || {
    printf '%s: probe evidence was not retained\n' "$case_name" >&2
    exit 1
  }
  [[ "$(file_mode "$evidence_root/migration-writer-probe.txt")" == '400' ]] || {
    printf '%s: failed probe evidence was not sealed mode 0400\n' "$case_name" >&2
    exit 1
  }
  if grep -Eq "$STAGING_REF|$PRODUCTION_REF|postgresql://|postgres://" \
    "$output_path" "$evidence_root/migration-writer-probe.txt"; then
    printf '%s: probe disclosed raw target details\n' "$case_name" >&2
    exit 1
  fi
}

run_case \
  reject-before-first-writer \
  2 \
  0 \
  $'guard\nguard'

run_case \
  recheck-before-every-writer \
  4 \
  2 \
  $'guard\nguard\npsql\nguard\npsql\nguard'

printf 'migration writer probe identity-order tests passed\n'
