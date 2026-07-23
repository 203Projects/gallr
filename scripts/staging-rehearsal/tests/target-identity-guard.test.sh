#!/usr/bin/env bash

# Network-free behavioral tests. `psql` and `git` are local fakes; any attempt
# to use the credential-bearing URL as an argv value fails the test.

set -euo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
REHEARSAL_DIR=$(cd "$SCRIPT_DIR/.." && pwd -P)
SOURCE_GUARD="$REHEARSAL_DIR/assert-disposable-clone-target.sh"

TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/gallr-target-identity.XXXXXX")
TEST_ROOT=$(cd "$TEST_ROOT" && pwd -P)
case "$TEST_ROOT" in
  /tmp/*|/private/tmp/*|/private/var/*) ;;
  *) printf 'unexpected temporary path\n' >&2; exit 1 ;;
esac
trap 'rm -rf -- "$TEST_ROOT"' EXIT HUP INT TERM

FAKE_REPO_ROOT="$TEST_ROOT/repository"
EVIDENCE_ROOT="$TEST_ROOT/evidence"
SECURE_ROOT="$TEST_ROOT/secure"
FAKE_BIN="$TEST_ROOT/bin"
FAKE_PSQL_LOG="$TEST_ROOT/psql-called"
mkdir -m 700 "$FAKE_REPO_ROOT" "$EVIDENCE_ROOT" "$SECURE_ROOT" "$FAKE_BIN"
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
DATABASE_URL="postgresql://postgres:test@db.${STAGING_REF}.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=%2Ftmp%2Fgallr-staging-root-ca.pem"
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
  node -e 'process.stdout.write(new Date(Date.now() + Number(process.argv[1])).toISOString().replace(/\.\d{3}Z$/, "Z"))' -- "$1"
}

printf '%s\n' '-- test migration' > "$MIGRATION_PATH"
printf '%s\n' "$STAGING_REF" > "$FAKE_REPO_ROOT/supabase/.temp/project-ref"
STAGING_SHA=$(sha256_text "$STAGING_REF")
PRODUCTION_SHA=$(sha256_text "$PRODUCTION_REF")
MIGRATION_SHA=$(sha256_file "$MIGRATION_PATH")

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
for forbidden in \
  database_url staging_ref production_ref policy_record marker_id \
  DATABASE_URL SUPABASE_ANON_KEY SUPABASE_SERVICE_ROLE_KEY \
  GALLR_GOVERNANCE_MODE GALLR_SOLO_OPERATOR_FIRST_CONFIRMATION \
  GALLR_EXECUTOR GALLR_REVIEWER GALLR_CHANGE_RECORD; do
  [[ "${!forbidden+x}" != x ]] || exit 92
done
case "$*" in
  *'rev-parse --show-toplevel') printf '%s\n' "$FAKE_REPO_ROOT" ;;
  *'rev-parse HEAD') printf '%s\n' "$FAKE_COMMIT" ;;
  *'status --porcelain=v1 --untracked-files=all') : ;;
  *) printf 'unexpected fake git invocation\n' >&2; exit 91 ;;
esac
EOF

cat > "$FAKE_BIN/psql" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'called\n' >> "$FAKE_PSQL_LOG"
for forbidden in \
  database_url staging_ref production_ref policy_record \
  DATABASE_URL SUPABASE_ANON_KEY SUPABASE_SERVICE_ROLE_KEY \
  GALLR_GOVERNANCE_MODE GALLR_SOLO_OPERATOR_FIRST_CONFIRMATION \
  GALLR_EXECUTOR GALLR_REVIEWER GALLR_CHANGE_RECORD; do
  [[ "${!forbidden+x}" != x ]] || exit 80
done
[[ "${PGDATABASE:-}" == "$FAKE_EXPECTED_DATABASE_URL" ]] || exit 81
[[ "${PGOPTIONS:-}" == *'default_transaction_read_only=on'* ]] || exit 82
[[ "${PGSSLMODE:-}" == 'verify-full' ]] || exit 83
for argument in "$@"; do
  [[ "$argument" != *'postgresql://'* && "$argument" != *'postgres://'* ]] || exit 84
  [[ "$argument" != *"$FAKE_STAGING_REF"* ]] || exit 85
  [[ "$argument" != *"$FAKE_PRODUCTION_REF"* ]] || exit 86
  case "$argument" in
    expected_governance_mode=*) governance_mode=${argument#*=} ;;
    expected_operator_identity=*) operator_identity=${argument#*=} ;;
    expected_first_confirmation_sha256=*) first_confirmation_sha=${argument#*=} ;;
    expected_second_confirmation_sha256=*) second_confirmation_sha=${argument#*=} ;;
    expected_effective_first_attestation_utc=*) effective_first_attestation=${argument#*=} ;;
    expected_minimum_cooldown_seconds=*) minimum_cooldown=${argument#*=} ;;
    expected_destructive_actions=*) destructive_actions=${argument#*=} ;;
  esac
done
[[ "${governance_mode:-}" == "${FAKE_EXPECTED_GOVERNANCE_MODE}" ]] || exit 89
[[ "${operator_identity:-}" == "${FAKE_EXPECTED_OPERATOR_IDENTITY}" ]] || exit 90
[[ "${first_confirmation_sha:-}" == "${FAKE_EXPECTED_FIRST_CONFIRMATION_SHA}" ]] || exit 93
[[ "${second_confirmation_sha:-}" == "${FAKE_EXPECTED_SECOND_CONFIRMATION_SHA}" ]] || exit 94
[[ "${effective_first_attestation:-}" == "${FAKE_EXPECTED_EFFECTIVE_FIRST_ATTESTATION}" ]] || exit 95
[[ "${minimum_cooldown:-}" == "${FAKE_EXPECTED_MINIMUM_COOLDOWN}" ]] || exit 96
[[ "${destructive_actions:-}" == "${FAKE_EXPECTED_DESTRUCTIVE_ACTIONS}" ]] || exit 97
case "${FAKE_MARKER_MODE:-valid}" in
  valid) printf 'relation\t1\ttrue\nmarker\t1\t1\n' ;;
  missing) printf 'relation\t1\ttrue\nmarker\t0\t0\n' ;;
  exposed) printf 'relation\t1\tfalse\nmarker\t1\t1\n' ;;
  error) exit 87 ;;
  *) exit 88 ;;
esac
EOF
chmod +x "$FAKE_BIN/git" "$FAKE_BIN/psql"

write_manifest
write_policy "$(utc_after -60000)" "$(utc_after 3600000)"

base_env=(
  "PATH=$FAKE_BIN:$PATH"
  "FAKE_REPO_ROOT=$FAKE_REPO_ROOT"
  "FAKE_COMMIT=$COMMIT"
  "FAKE_PSQL_LOG=$FAKE_PSQL_LOG"
  "FAKE_EXPECTED_DATABASE_URL=$DATABASE_URL"
  "FAKE_STAGING_REF=$STAGING_REF"
  "FAKE_PRODUCTION_REF=$PRODUCTION_REF"
  'FAKE_EXPECTED_GOVERNANCE_MODE=separated_humans'
  'FAKE_EXPECTED_OPERATOR_IDENTITY='
  'FAKE_EXPECTED_FIRST_CONFIRMATION_SHA='
  'FAKE_EXPECTED_SECOND_CONFIRMATION_SHA='
  'FAKE_EXPECTED_EFFECTIVE_FIRST_ATTESTATION='
  'FAKE_EXPECTED_MINIMUM_COOLDOWN='
  'FAKE_EXPECTED_DESTRUCTIVE_ACTIONS='
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

run_guard() {
  env -i "${base_env[@]}" "FAKE_MARKER_MODE=${1:-valid}" bash "$GUARD" 2>&1
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
node -e \
  'const fs=require("node:fs"); const at=new Date(process.argv[2]); fs.utimesSync(process.argv[1], at, at);' \
  "${POLICY_PATH}" "${SOLO_ISSUED_AT}"

base_env=("${base_env[@]/FAKE_EXPECTED_GOVERNANCE_MODE=separated_humans/FAKE_EXPECTED_GOVERNANCE_MODE=solo_operator}")
base_env=("${base_env[@]/FAKE_EXPECTED_OPERATOR_IDENTITY=/FAKE_EXPECTED_OPERATOR_IDENTITY=$SOLO_OPERATOR}")
base_env=("${base_env[@]/FAKE_EXPECTED_FIRST_CONFIRMATION_SHA=/FAKE_EXPECTED_FIRST_CONFIRMATION_SHA=$SOLO_FIRST_SHA}")
base_env=("${base_env[@]/FAKE_EXPECTED_SECOND_CONFIRMATION_SHA=/FAKE_EXPECTED_SECOND_CONFIRMATION_SHA=$SOLO_SECOND_SHA}")
base_env=("${base_env[@]/FAKE_EXPECTED_EFFECTIVE_FIRST_ATTESTATION=/FAKE_EXPECTED_EFFECTIVE_FIRST_ATTESTATION=$SOLO_ISSUED_AT}")
base_env=("${base_env[@]/FAKE_EXPECTED_MINIMUM_COOLDOWN=/FAKE_EXPECTED_MINIMUM_COOLDOWN=900}")
base_env=("${base_env[@]/FAKE_EXPECTED_DESTRUCTIVE_ACTIONS=/FAKE_EXPECTED_DESTRUCTIVE_ACTIONS=forbidden}")

expect_pass
expect_fail 'missing solo marker' missing 1

printf 'target identity guard tests passed\n'
