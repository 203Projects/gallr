#!/usr/bin/env bash

# Network-free orchestration test for the repeating migration writer probe.
# Git, Node, the linked/identity guards, sleep, and psql are local fakes. The
# test proves a fresh target-identity pass immediately precedes every psql
# attempt and that a later identity failure stops the loop before another one.

set -euo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
REHEARSAL_DIR=$(cd "$SCRIPT_DIR/.." && pwd -P)
SOURCE_RUNNER="$REHEARSAL_DIR/run-migration-writer-probe.sh"

TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/gallr-writer-probe.XXXXXX")
TEST_ROOT=$(cd "$TEST_ROOT" && pwd -P)
case "$TEST_ROOT" in
  /tmp/*|/private/tmp/*|/private/var/*) ;;
  *) printf 'unexpected temporary path\n' >&2; exit 1 ;;
esac
trap 'rm -rf -- "$TEST_ROOT"' EXIT HUP INT TERM

STAGING_REF='ssssssssssssssssssss'
PRODUCTION_REF='pppppppppppppppppppp'
DATABASE_URL="postgresql://postgres:test@db.${STAGING_REF}.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=%2Ftmp%2Fgallr-staging-root-ca.pem"
COMMIT='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
TARGET_ID='legacy-probe-target'

file_mode() {
  if stat -f '%Lp' "$1" >/dev/null 2>&1; then
    stat -f '%Lp' "$1"
  else
    stat -c '%a' "$1"
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
  local fake_bin="$case_root/bin"
  local sequence_path="$case_root/sequence.log"
  local guard_count_path="$case_root/guard-count"
  local psql_count_path="$case_root/psql-count"
  local policy_path="$case_root/identity-policy.txt"
  local output_path="$case_root/runner-output.log"
  local runner_status

  mkdir -m 700 "$case_root" "$repo_root" "$evidence_root" "$fake_bin"
  mkdir -p "$rehearsal_root/lib" "$rehearsal_root/sql"
  cp "$SOURCE_RUNNER" "$rehearsal_root/run-migration-writer-probe.sh"
  chmod +x "$rehearsal_root/run-migration-writer-probe.sh"
  printf '%s\n' '// fake validator; execution is intercepted by fake node' \
    > "$rehearsal_root/lib/validate-database-target.mjs"
  printf '%s\n' '-- fake rollback-only writer SQL' \
    > "$rehearsal_root/sql/migration-writer-probe.sql"
  printf '%s\n' 'manifest=test-only' > "$evidence_root/operator-manifest.txt"
  chmod 444 "$evidence_root/operator-manifest.txt"
  printf '%s\n' 'test-only-policy' > "$policy_path"
  chmod 400 "$policy_path"

  cat > "$rehearsal_root/assert-linked-staging.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'PASS: linked project matches the reviewed staging manifest\n'
EOF

  cat > "$rehearsal_root/assert-disposable-clone-target.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
count=0
if [[ -f "$FAKE_GUARD_COUNT_PATH" ]]; then
  IFS= read -r count < "$FAKE_GUARD_COUNT_PATH"
fi
count=$((count + 1))
printf '%s\n' "$count" > "$FAKE_GUARD_COUNT_PATH"
printf 'guard\n' >> "$FAKE_SEQUENCE_PATH"
if [[ "$count" -eq "$FAKE_GUARD_FAIL_AT" ]]; then
  exit 71
fi
printf 'PASS: independent policy and disposable-clone marker identify staging\n'
EOF
  chmod +x \
    "$rehearsal_root/assert-linked-staging.sh" \
    "$rehearsal_root/assert-disposable-clone-target.sh"

  cat > "$fake_bin/node" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
for forbidden in \
  staging_database_url staging_ref_raw production_ref_raw \
  DATABASE_URL SUPABASE_ANON_KEY SUPABASE_SERVICE_ROLE_KEY; do
  [[ "${!forbidden+x}" != x ]] || exit 92
done
exit 0
EOF

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

  cat > "$fake_bin/psql" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
psql_count=0
guard_count=0
if [[ -f "$FAKE_PSQL_COUNT_PATH" ]]; then
  IFS= read -r psql_count < "$FAKE_PSQL_COUNT_PATH"
fi
if [[ -f "$FAKE_GUARD_COUNT_PATH" ]]; then
  IFS= read -r guard_count < "$FAKE_GUARD_COUNT_PATH"
fi
psql_count=$((psql_count + 1))

# The probe has one initial identity check, then one immediately before each
# writer attempt. Any other relationship is a fail-closed ordering violation.
[[ "$guard_count" -eq $((psql_count + 1)) ]] || exit 81
[[ "${PGDATABASE:-}" == "$FAKE_EXPECTED_DATABASE_URL" ]] || exit 82
[[ "${PGSSLMODE:-}" == 'verify-full' ]] || exit 83
for argument in "$@"; do
  [[ "$argument" != *'postgresql://'* && "$argument" != *'postgres://'* ]] || exit 84
  [[ "$argument" != *"$FAKE_STAGING_REF"* ]] || exit 85
  [[ "$argument" != *"$FAKE_PRODUCTION_REF"* ]] || exit 86
done

printf '%s\n' "$psql_count" > "$FAKE_PSQL_COUNT_PATH"
printf 'psql\n' >> "$FAKE_SEQUENCE_PATH"
printf 'probe_result=rolled_back\n'
EOF
  chmod +x "$fake_bin/node" "$fake_bin/git" "$fake_bin/sleep" "$fake_bin/psql"

  : > "$sequence_path"
  set +e
  env -i \
    "PATH=$fake_bin:$PATH" \
    BASH_ENV=/dev/null \
    ENV=/dev/null \
    "FAKE_REPO_ROOT=$repo_root" \
    "FAKE_COMMIT=$COMMIT" \
    "FAKE_SEQUENCE_PATH=$sequence_path" \
    "FAKE_GUARD_COUNT_PATH=$guard_count_path" \
    "FAKE_PSQL_COUNT_PATH=$psql_count_path" \
    "FAKE_GUARD_FAIL_AT=$guard_fail_at" \
    "FAKE_EXPECTED_DATABASE_URL=$DATABASE_URL" \
    "FAKE_STAGING_REF=$STAGING_REF" \
    "FAKE_PRODUCTION_REF=$PRODUCTION_REF" \
    "GALLR_EXPECTED_STAGING_PROJECT_REF=$STAGING_REF" \
    "GALLR_PRODUCTION_PROJECT_REF=$PRODUCTION_REF" \
    "GALLR_STAGING_DATABASE_URL=$DATABASE_URL" \
    "GALLR_STAGING_REHEARSAL_CONFIRM=$STAGING_REF" \
    "GALLR_STAGING_EVIDENCE_DIR=$evidence_root" \
    "GALLR_STAGING_IDENTITY_POLICY_PATH=$policy_path" \
    "GALLR_LEGACY_PROBE_EXHIBITION_ID=$TARGET_ID" \
    "staging_database_url=$DATABASE_URL" \
    "staging_ref_raw=$STAGING_REF" \
    "production_ref_raw=$PRODUCTION_REF" \
    "DATABASE_URL=$DATABASE_URL" \
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
