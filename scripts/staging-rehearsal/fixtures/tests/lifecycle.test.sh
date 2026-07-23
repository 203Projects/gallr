#!/usr/bin/env bash
set -euo pipefail

TEST_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
SOURCE_FIXTURE_DIR=$(cd "$TEST_DIR/.." && pwd -P)
SOURCE_REHEARSAL_DIR=$(cd "$SOURCE_FIXTURE_DIR/.." && pwd -P)
TEST_CA_SOURCE="$SOURCE_REHEARSAL_DIR/tests/fixtures/test-root-ca.pem"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/gallr-fixture-lifecycle.XXXXXX")
TEST_ROOT=$(cd "$TEST_ROOT" && pwd -P)
REPO_ROOT="$TEST_ROOT/repo"
REHEARSAL_DIR="$REPO_ROOT/scripts/staging-rehearsal"
FIXTURE_DIR="$REHEARSAL_DIR/fixtures"
FAKE_BIN="$TEST_ROOT/bin"
EVIDENCE_ROOT="$TEST_ROOT/evidence"
SECURE_ROOT="$TEST_ROOT/secure"
TEST_CA_PATH="$SECURE_ROOT/test-root-ca.pem"
SEQUENCE_LOG="$TEST_ROOT/sequence.log"
BASELINE_DRIFT_MARKER="$TEST_ROOT/baseline-drift"
IDENTITY_GUARD_BLOCK_MARKER="$TEST_ROOT/identity-guard-block"
IDENTITY_GUARD_STARTED_MARKER="$TEST_ROOT/identity-guard-started"
IDENTITY_GUARD_FAILURE_MARKER="$TEST_ROOT/identity-guard-failure"
FAKE_EVIDENCE_GENERATOR="$TEST_DIR/fake-evidence.mjs"

cleanup() {
  case "$TEST_ROOT" in
    /tmp/gallr-fixture-lifecycle.*|/private/tmp/gallr-fixture-lifecycle.*|\
      /private/var/*/gallr-fixture-lifecycle.*)
      rm -rf -- "$TEST_ROOT"
      ;;
    *)
      printf 'Refusing unsafe test cleanup path: %s\n' "$TEST_ROOT" >&2
      ;;
  esac
}
trap cleanup EXIT HUP INT TERM

STAGING_REF='aaaaaaaaaaaaaaaaaaaa'
PRODUCTION_REF='bbbbbbbbbbbbbbbbbbbb'
RUN_ID='lifecycle-test-0001'
ENCODED_DATABASE_PASSWORD='test%3Apass%5Cword'
EXPECTED_PGPASS_PASSWORD='test\:pass\\word'
REAL_NODE_SOURCE=$(node -p 'require("node:fs").realpathSync.native(process.execPath)')
REVIEWED_NODE="$FAKE_BIN/node"

mkdir -m 700 "$FAKE_BIN" "$EVIDENCE_ROOT" "$SECURE_ROOT"
cp "$REAL_NODE_SOURCE" "$REVIEWED_NODE"
chmod 500 "$REVIEWED_NODE"
NODE_LIBRARY_SOURCE=$(
  find "$(dirname "$REAL_NODE_SOURCE")/../lib" \
    -maxdepth 1 -type f -name 'libnode.*.dylib' -print -quit 2>/dev/null || true
)
if [[ -n "$NODE_LIBRARY_SOURCE" ]]; then
  mkdir -m 700 "$TEST_ROOT/lib"
  cp "$NODE_LIBRARY_SOURCE" "$TEST_ROOT/lib/"
  chmod 400 "$TEST_ROOT/lib/$(basename "$NODE_LIBRARY_SOURCE")"
fi
mkdir -p "$FIXTURE_DIR" "$REHEARSAL_DIR/lib"
chmod 700 "$REPO_ROOT" "$REPO_ROOT/scripts" "$REHEARSAL_DIR" \
  "$FIXTURE_DIR" "$REHEARSAL_DIR/lib"
cp "$TEST_CA_SOURCE" "$TEST_CA_PATH"
chmod 0400 "$TEST_CA_PATH"
cp \
  "$SOURCE_FIXTURE_DIR/common.sh" \
  "$SOURCE_FIXTURE_DIR/provision.sh" \
  "$SOURCE_FIXTURE_DIR/cleanup.sh" \
  "$SOURCE_FIXTURE_DIR/baseline.sql" \
  "$SOURCE_FIXTURE_DIR/provision.sql" \
  "$SOURCE_FIXTURE_DIR/cleanup.sql" \
  "$SOURCE_FIXTURE_DIR/tracked-state.sql" \
  "$FIXTURE_DIR/"
cp \
  "$SOURCE_REHEARSAL_DIR/lib/database-target.mjs" \
  "$SOURCE_REHEARSAL_DIR/lib/validate-database-target.mjs" \
  "$SOURCE_REHEARSAL_DIR/lib/run-psql-with-validated-target.mjs" \
  "$SOURCE_REHEARSAL_DIR/lib/reviewed-toolchain.sh" \
  "$REHEARSAL_DIR/lib/"

HELPER_METADATA_PROBE="$TEST_ROOT/helper-metadata-probe"
: > "$HELPER_METADATA_PROBE"
chmod 400 "$HELPER_METADATA_PROBE"

helper_metadata_probe_on_exit() {
  local expected_status=$?
  local links mode

  trap - EXIT
  mode=$(fixture_mode "$HELPER_METADATA_PROBE") || exit 120
  links=$(fixture_nlink "$HELPER_METADATA_PROBE") || exit 121
  [[ "$mode" == 400 && "$links" == 1 ]] || exit 122
  exit "$expected_status"
}

if (
  # shellcheck source=../common.sh
  source "$FIXTURE_DIR/common.sh"
  trap helper_metadata_probe_on_exit EXIT
  exit 73
); then
  printf 'Metadata helper trap probe unexpectedly replaced its entry status.\n' >&2
  exit 78
else
  helper_metadata_probe_status=$?
fi
if [[ "$helper_metadata_probe_status" -ne 73 ]]; then
  printf 'Metadata helpers failed inside an EXIT trap (status %s).\n' \
    "$helper_metadata_probe_status" >&2
  exit 79
fi

{
  printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail'
  printf 'expected_staging_ref=%q\n' "$STAGING_REF"
  printf 'expected_pgpass_password=%q\n' "$EXPECTED_PGPASS_PASSWORD"
  printf 'source_ca_path=%q\n' "$TEST_CA_PATH"
  printf 'sequence_log=%q\n' "$SEQUENCE_LOG"
  printf 'reviewed_node=%q\n' "$REVIEWED_NODE"
  printf 'evidence_generator=%q\n' "$FAKE_EVIDENCE_GENERATOR"
  printf 'baseline_drift_marker=%q\n' "$BASELINE_DRIFT_MARKER"
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

[[ "${PGHOST:-}" == "db.${expected_staging_ref}.supabase.co" ]] || exit 91
[[ "${PGPORT:-}" == 5432 && "${PGDATABASE:-}" == postgres \
   && "${PGUSER:-}" == postgres ]] || exit 92
[[ "${PGSSLMODE:-}" == verify-full \
   && "${PGGSSENCMODE:-}" == disable \
   && "${PGSSLCERTMODE:-}" == disable ]] || exit 93
[[ "${PGAPPNAME:-}" == gallr-staging-fixture-lifecycle-* \
   && "${PGCONNECT_TIMEOUT:-}" == 15 ]] || exit 94
[[ -z "${PGOPTIONS+x}" && -z "${PGPASSWORD+x}" \
   && -z "${PGHOSTADDR+x}" && -z "${PGSERVICE+x}" \
   && -z "${PGSERVICEFILE+x}" && -z "${PGSSLCERT+x}" \
   && -z "${PGSSLKEY+x}" && -z "${PGGSSLIB+x}" ]] || exit 95
for forbidden in \
  GALLR_EXPECTED_STAGING_PROJECT_REF GALLR_PRODUCTION_PROJECT_REF \
  GALLR_STAGING_DATABASE_URL GALLR_STAGING_REHEARSAL_CONFIRM \
  GALLR_VALIDATION_DATABASE_URL GALLR_VALIDATION_PROJECT_REF \
  GALLR_VALIDATION_REQUIRE_DIRECT GALLR_VALIDATION_SSLROOTCERT_SHA256 \
  GALLR_PSQL_APPNAME GALLR_PSQL_CONNECT_TIMEOUT GALLR_PSQL_OPTIONS \
  GALLR_VALIDATED_PSQL_PATH GALLR_VALIDATED_PSQL_SHA256 \
  FAKE_STAGING_REF FAKE_RUN_ID FAKE_EXPECTED_PGPASS_PASSWORD \
  FAKE_SOURCE_CA_PATH FAKE_EVIDENCE_GENERATOR FAKE_BASELINE_DRIFT \
  SEQUENCE_LOG; do
  [[ "${!forbidden+x}" != x ]] || exit 96
done
[[ -n "${PGPASSFILE:-}" && "${PGPASSFILE}" != /dev/null \
   && -f "${PGPASSFILE}" && ! -L "${PGPASSFILE}" && -O "${PGPASSFILE}" ]] \
  || exit 97
passfile_mode=$(portable_stat_mode "${PGPASSFILE}")
[[ "${passfile_mode}" == 600 \
   && "$(wc -l < "${PGPASSFILE}" | tr -d ' ')" == 1 \
   && "$(< "${PGPASSFILE}")" == \
      "db.${expected_staging_ref}.supabase.co:5432:postgres:postgres:${expected_pgpass_password}" ]] \
  || exit 98
[[ -n "${PGSSLROOTCERT:-}" && "${PGSSLROOTCERT}" != "${source_ca_path}" \
   && -f "${PGSSLROOTCERT}" && ! -L "${PGSSLROOTCERT}" \
   && -O "${PGSSLROOTCERT}" ]] || exit 99
certificate_mode=$(portable_stat_mode "${PGSSLROOTCERT}")
certificate_parent_mode=$(portable_stat_mode "$(dirname "${PGSSLROOTCERT}")")
[[ "${certificate_mode}" == 400 && "${certificate_parent_mode}" == 700 ]] || exit 100
cmp -s "${PGSSLROOTCERT}" "${source_ca_path}" || exit 101
while IFS='=' read -r environment_name environment_value; do
  [[ "${environment_value}" != *postgresql://* \
     && "${environment_value}" != *postgres://* ]] || exit 102
done < <(env)
for argument in "$@"; do
  [[ "$argument" != *postgresql://* && "$argument" != *postgres://* ]] || exit 103
done

sql_file=
fixture_prefix=
load_event_id=
empty_event_id=
editor_id=
boundary_id=
mutation_id=
media_object_path=
while (($#)); do
  case "$1" in
    -f) shift; sql_file="$1" ;;
    -v)
      shift
      case "$1" in
        fixture_prefix=*) fixture_prefix=${1#fixture_prefix=} ;;
        load_event_id=*) load_event_id=${1#load_event_id=} ;;
        empty_event_id=*) empty_event_id=${1#empty_event_id=} ;;
        editor_id=*) editor_id=${1#editor_id=} ;;
        boundary_id=*) boundary_id=${1#boundary_id=} ;;
        mutation_id=*) mutation_id=${1#mutation_id=} ;;
        media_object_path=*) media_object_path=${1#media_object_path=} ;;
      esac
      ;;
  esac
  shift
done
printf 'psql:%s\n' "$(basename "$sql_file")" >> "$sequence_log"
case "$(basename "$sql_file")" in
  baseline.sql)
    if [[ -e "$baseline_drift_marker" ]]; then
      FAKE_BASELINE_DRIFT=1 "$reviewed_node" "$evidence_generator" baseline
    else
      "$reviewed_node" "$evidence_generator" baseline
    fi
    ;;
  provision.sql)
    "$reviewed_node" "$evidence_generator" provision \
      "$fixture_prefix" "$load_event_id" "$empty_event_id" "$editor_id" \
      "$boundary_id" "$mutation_id" "$media_object_path"
    ;;
  cleanup.sql)
    "$reviewed_node" "$evidence_generator" cleanup
    ;;
  *) exit 104 ;;
esac
EOF
} > "$FAKE_BIN/psql"
chmod 700 "$FAKE_BIN/psql"

LINKED_GUARD_PATH="$REHEARSAL_DIR/assert-linked-staging.sh"
IDENTITY_GUARD_PATH="$REHEARSAL_DIR/assert-disposable-clone-target.sh"
{
  printf '%s\n' '#!/bin/sh' 'set -eu'
  printf 'sequence_log=%q\n' "$SEQUENCE_LOG"
  cat <<'EOF'
[ "${BASH_ENV:-}" = /dev/null ] || exit 86
[ "${ENV:-}" = /dev/null ] || exit 85
printf 'guard\n' >> "$sequence_log"
printf 'PASS: stubbed linked staging guard\n'
EOF
} > "$LINKED_GUARD_PATH"
chmod 700 "$LINKED_GUARD_PATH"
{
  printf '%s\n' '#!/bin/sh' 'set -eu'
  printf 'sequence_log=%q\n' "$SEQUENCE_LOG"
  printf 'block_marker=%q\n' "$IDENTITY_GUARD_BLOCK_MARKER"
  printf 'started_marker=%q\n' "$IDENTITY_GUARD_STARTED_MARKER"
  printf 'failure_marker=%q\n' "$IDENTITY_GUARD_FAILURE_MARKER"
  cat <<'EOF'
[ -z "${BASH_ENV+x}" ] || exit 84
[ -z "${ENV+x}" ] || exit 83
[ -z "${FAKE_BASELINE_DRIFT+x}" ] || exit 82
printf 'identity\n' >> "$sequence_log"
if [ -e "$block_marker" ]; then
  : > "$started_marker"
  while [ -e "$block_marker" ]; do
    sleep 0.05
  done
fi
if [ -e "$failure_marker" ]; then
  printf 'FAIL: simulated completed identity guard failure\n'
  exit 73
fi
printf 'PASS: independent policy and disposable-clone marker identify staging\n'
EOF
} > "$IDENTITY_GUARD_PATH"
chmod 700 "$IDENTITY_GUARD_PATH"

TEST_CA_URI_PATH="${TEST_CA_PATH//\//%2F}"
DATABASE_URL="postgresql://postgres:${ENCODED_DATABASE_PASSWORD}@db.$STAGING_REF.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=${TEST_CA_URI_PATH}"

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

printf '%s\n' \
  'manifest_schema=1' \
  "reviewed_node_path=$REVIEWED_NODE" \
  "reviewed_node_sha256=$(sha256_file "$REVIEWED_NODE")" \
  "reviewed_psql_path=$FAKE_BIN/psql" \
  "reviewed_psql_sha256=$(sha256_file "$FAKE_BIN/psql")" \
  > "$EVIDENCE_ROOT/operator-manifest.txt"
chmod 444 "$EVIDENCE_ROOT/operator-manifest.txt"

run_wrapper() {
  local wrapper="$1"
  env \
    PATH="$FAKE_BIN:$PATH" \
    PGHOST='production.invalid' \
    PGHOSTADDR='203.0.113.10' \
    PGPASSWORD='must-not-reach-psql' \
    GALLR_EXPECTED_STAGING_PROJECT_REF="$STAGING_REF" \
    GALLR_PRODUCTION_PROJECT_REF="$PRODUCTION_REF" \
    GALLR_STAGING_DATABASE_URL="$DATABASE_URL" \
    GALLR_STAGING_REHEARSAL_CONFIRM="$STAGING_REF" \
    GALLR_STAGING_EVIDENCE_DIR="$EVIDENCE_ROOT" \
    GALLR_STAGING_IDENTITY_POLICY_PATH="$TEST_ROOT/identity-policy.txt" \
    GALLR_FIXTURE_RUN_ID="$RUN_ID" \
    /bin/bash "$wrapper"
}

run_wrapper "$FIXTURE_DIR/provision.sh" > "$TEST_ROOT/provision.stdout"
RUN_DIR="$EVIDENCE_ROOT/fixtures-$RUN_ID"
for evidence_path in \
  "$RUN_DIR/identity.tsv" \
  "$RUN_DIR/baseline.tsv" \
  "$RUN_DIR/provisioned.json" \
  "$RUN_DIR/manifest.json"; do
  [[ -f "$evidence_path" && ! -L "$evidence_path" ]] || exit 81
  mode=$(file_mode "$evidence_path")
  [[ "$mode" == 400 ]] || exit 82
done

EXPECTED_MUTATION_ID="gallr-rehearsal-$RUN_ID-catalog-0750.mutate,(same-id):한글"
node - "$RUN_DIR/manifest.json" "$EXPECTED_MUTATION_ID" <<'NODE'
const fs = require("node:fs");
const manifest = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
if (manifest.mutation_target_id !== process.argv[3]) process.exit(1);
if (manifest.database_evidence.mutation_target_id !== process.argv[3]) process.exit(1);
if (manifest.fixture_count !== 1205) process.exit(1);
NODE

run_wrapper "$FIXTURE_DIR/cleanup.sh" > "$TEST_ROOT/cleanup.stdout"
[[ -f "$RUN_DIR/cleaned.json" && ! -L "$RUN_DIR/cleaned.json" ]] || exit 83
cleaned_mode=$(file_mode "$RUN_DIR/cleaned.json")
[[ "$cleaned_mode" == 400 ]] || exit 84
node -e '
  const fs = require("node:fs");
  const value = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
  if (value.state !== "cleaned" || value.baseline_restored !== true) process.exit(1);
' "$RUN_DIR/cleaned.json"

expected_sequence=$'guard\nidentity\npsql:baseline.sql\npsql:provision.sql\nguard\nidentity\npsql:cleanup.sql'
[[ "$(< "$SEQUENCE_LOG")" == "$expected_sequence" ]] || {
  printf 'Unexpected guard/psql sequence:\n%s\n' "$(< "$SEQUENCE_LOG")" >&2
  exit 85
}

# A complete psql payload can exist before COMMIT or before local manifest
# finalization. Cleanup may consume it, but local evidence is sealed only after
# the cleanup transaction itself succeeds.
: > "$SEQUENCE_LOG"
RUN_ID='lifecycle-recover-provision'
run_wrapper "$FIXTURE_DIR/provision.sh" > "$TEST_ROOT/recover-provision.stdout"
RECOVERY_RUN_DIR="$EVIDENCE_ROOT/fixtures-$RUN_ID"
mv "$RECOVERY_RUN_DIR/provisioned.json" "$RECOVERY_RUN_DIR/provisioned.json.incomplete"
mv "$RECOVERY_RUN_DIR/manifest.json" "$RECOVERY_RUN_DIR/manifest.json.incomplete"
chmod 600 \
  "$RECOVERY_RUN_DIR/identity.tsv" \
  "$RECOVERY_RUN_DIR/baseline.tsv" \
  "$RECOVERY_RUN_DIR/provisioned.json.incomplete" \
  "$RECOVERY_RUN_DIR/manifest.json.incomplete"
run_wrapper "$FIXTURE_DIR/cleanup.sh" > "$TEST_ROOT/recover-provision-cleanup.stdout"
for evidence_path in \
  "$RECOVERY_RUN_DIR/identity.tsv" \
  "$RECOVERY_RUN_DIR/baseline.tsv" \
  "$RECOVERY_RUN_DIR/provisioned.json" \
  "$RECOVERY_RUN_DIR/manifest.json" \
  "$RECOVERY_RUN_DIR/cleaned.json"; do
  [[ -f "$evidence_path" && ! -L "$evidence_path" ]] || exit 86
  mode=$(file_mode "$evidence_path")
  [[ "$mode" == 400 ]] || exit 87
done
[[ ! -e "$RECOVERY_RUN_DIR/provisioned.json.incomplete" ]] || exit 88
[[ ! -e "$RECOVERY_RUN_DIR/manifest.json.incomplete" ]] || exit 89
[[ "$(< "$SEQUENCE_LOG")" == "$expected_sequence" ]] || {
  printf 'Unexpected interrupted-provision recovery sequence:\n%s\n' \
    "$(< "$SEQUENCE_LOG")" >&2
  exit 90
}

# A valid pre-COMMIT cleanup payload is finalized only after a new, read-only
# baseline capture exactly matches every stored count/hash (including audit
# count). The cleanup mutation must not run a second time.
: > "$SEQUENCE_LOG"
RUN_ID='lifecycle-recover-cleanup'
run_wrapper "$FIXTURE_DIR/provision.sh" > "$TEST_ROOT/recover-cleanup-provision.stdout"
RECOVERY_RUN_DIR="$EVIDENCE_ROOT/fixtures-$RUN_ID"
run_wrapper "$FIXTURE_DIR/cleanup.sh" > "$TEST_ROOT/recover-cleanup-first.stdout"
mv "$RECOVERY_RUN_DIR/cleaned.json" "$RECOVERY_RUN_DIR/cleaned.json.incomplete"
: > "$SEQUENCE_LOG"
run_wrapper "$FIXTURE_DIR/cleanup.sh" > "$TEST_ROOT/recover-cleanup-finalize.stdout"
[[ -f "$RECOVERY_RUN_DIR/cleaned.json" && ! -L "$RECOVERY_RUN_DIR/cleaned.json" ]] || exit 91
[[ ! -e "$RECOVERY_RUN_DIR/cleaned.json.incomplete" ]] || exit 92
[[ -f "$RECOVERY_RUN_DIR/baseline-restore-verification.tsv" ]] || exit 93
recovery_sequence=$'guard\nidentity\npsql:baseline.sql'
[[ "$(< "$SEQUENCE_LOG")" == "$recovery_sequence" ]] || {
  printf 'Unexpected interrupted-cleanup recovery sequence:\n%s\n' \
    "$(< "$SEQUENCE_LOG")" >&2
  exit 94
}

# Baseline drift leaves the valid incomplete payload and the failed read-only
# verification sealed for review. It neither reruns cleanup.sql nor promotes
# cleaned.json.
: > "$SEQUENCE_LOG"
RUN_ID='lifecycle-reject-drift'
run_wrapper "$FIXTURE_DIR/provision.sh" > "$TEST_ROOT/drift-provision.stdout"
DRIFT_RUN_DIR="$EVIDENCE_ROOT/fixtures-$RUN_ID"
run_wrapper "$FIXTURE_DIR/cleanup.sh" > "$TEST_ROOT/drift-cleanup-first.stdout"
mv "$DRIFT_RUN_DIR/cleaned.json" "$DRIFT_RUN_DIR/cleaned.json.incomplete"
: > "$SEQUENCE_LOG"
touch "$BASELINE_DRIFT_MARKER"
if run_wrapper "$FIXTURE_DIR/cleanup.sh" \
  > "$TEST_ROOT/drift-recovery.stdout" 2> "$TEST_ROOT/drift-recovery.stderr"; then
  printf 'Drifted baseline unexpectedly finalized incomplete cleanup evidence.\n' >&2
  exit 95
fi
rm "$BASELINE_DRIFT_MARKER"
grep -Fq 'exact baseline is not restored' "$TEST_ROOT/drift-recovery.stderr"
[[ -f "$DRIFT_RUN_DIR/cleaned.json.incomplete" ]] || exit 96
[[ ! -e "$DRIFT_RUN_DIR/cleaned.json" ]] || exit 97
[[ -f "$DRIFT_RUN_DIR/baseline-restore-verification.tsv.incomplete" ]] || exit 98
drift_mode=$(file_mode \
  "$DRIFT_RUN_DIR/baseline-restore-verification.tsv.incomplete")
[[ "$drift_mode" == 400 ]] || exit 99
[[ "$(< "$SEQUENCE_LOG")" == "$recovery_sequence" ]] || {
  printf 'Unexpected drift-rejection sequence:\n%s\n' "$(< "$SEQUENCE_LOG")" >&2
  exit 100
}

# The run directory is the atomic ownership boundary. A losing same-run
# provisioner must not unlink or otherwise disturb the winner's live guard log.
: > "$SEQUENCE_LOG"
RUN_ID='lifecycle-concurrent-owner'
CONCURRENT_RUN_DIR="$EVIDENCE_ROOT/fixtures-$RUN_ID"
CONCURRENT_LOG="$CONCURRENT_RUN_DIR/target-identity-provision.log"
touch "$IDENTITY_GUARD_BLOCK_MARKER"
rm -f -- "$IDENTITY_GUARD_STARTED_MARKER"
run_wrapper "$FIXTURE_DIR/provision.sh" \
  > "$TEST_ROOT/concurrent-owner.stdout" \
  2> "$TEST_ROOT/concurrent-owner.stderr" &
owner_pid=$!
owner_started=0
for _ in {1..200}; do
  if [[ -e "$IDENTITY_GUARD_STARTED_MARKER" ]]; then
    owner_started=1
    break
  fi
  if ! kill -0 "$owner_pid" 2>/dev/null; then
    break
  fi
  sleep 0.05
done
if [[ "$owner_started" -ne 1 ]]; then
  rm -f -- "$IDENTITY_GUARD_BLOCK_MARKER"
  wait "$owner_pid" || true
  printf 'Concurrent owner did not enter the blocked identity guard.\n' >&2
  cat "$TEST_ROOT/concurrent-owner.stderr" >&2
  exit 101
fi
[[ -f "$CONCURRENT_LOG" && ! -L "$CONCURRENT_LOG" ]] || exit 102
if run_wrapper "$FIXTURE_DIR/provision.sh" \
  > "$TEST_ROOT/concurrent-loser.stdout" \
  2> "$TEST_ROOT/concurrent-loser.stderr"; then
  rm -f -- "$IDENTITY_GUARD_BLOCK_MARKER"
  wait "$owner_pid" || true
  printf 'Concurrent same-run provision unexpectedly succeeded.\n' >&2
  exit 103
fi
grep -Fq 'evidence run already exists' "$TEST_ROOT/concurrent-loser.stderr"
[[ -f "$CONCURRENT_LOG" && ! -L "$CONCURRENT_LOG" ]] || {
  rm -f -- "$IDENTITY_GUARD_BLOCK_MARKER"
  wait "$owner_pid" || true
  printf 'Concurrent loser removed the owner identity log.\n' >&2
  exit 104
}
kill -0 "$owner_pid" 2>/dev/null || {
  rm -f -- "$IDENTITY_GUARD_BLOCK_MARKER"
  wait "$owner_pid" || true
  printf 'Concurrent loser disturbed the active owner process.\n' >&2
  exit 105
}
rm -f -- "$IDENTITY_GUARD_BLOCK_MARKER"
if ! wait "$owner_pid"; then
  printf 'Concurrent owner failed after the loser exited.\n' >&2
  cat "$TEST_ROOT/concurrent-owner.stderr" >&2
  exit 106
fi
[[ -f "$CONCURRENT_RUN_DIR/manifest.json" ]] || exit 107
[[ "$(file_mode "$CONCURRENT_LOG")" == 400 ]] || exit 108

# A completed identity-guard failure is durable review evidence. It remains a
# sealed, single-link file and a retry with the same run ID cannot replace it.
: > "$SEQUENCE_LOG"
RUN_ID='lifecycle-guard-failure'
FAILED_RUN_DIR="$EVIDENCE_ROOT/fixtures-$RUN_ID"
FAILED_GUARD_LOG="$FAILED_RUN_DIR/target-identity-provision.log"
touch "$IDENTITY_GUARD_FAILURE_MARKER"
if run_wrapper "$FIXTURE_DIR/provision.sh" \
  > "$TEST_ROOT/guard-failure.stdout" \
  2> "$TEST_ROOT/guard-failure.stderr"; then
  rm -f -- "$IDENTITY_GUARD_FAILURE_MARKER"
  printf 'Provision unexpectedly ignored the completed identity guard failure.\n' >&2
  exit 109
fi
rm -f -- "$IDENTITY_GUARD_FAILURE_MARKER"
grep -Fq 'disposable-clone target identity failed' \
  "$TEST_ROOT/guard-failure.stderr"
[[ -d "$FAILED_RUN_DIR" && ! -L "$FAILED_RUN_DIR" && -O "$FAILED_RUN_DIR" ]] ||
  exit 110
[[ "$(file_mode "$FAILED_RUN_DIR")" == 700 ]] || exit 111
[[ -f "$FAILED_GUARD_LOG" && ! -L "$FAILED_GUARD_LOG" &&
   -O "$FAILED_GUARD_LOG" ]] || exit 112
[[ "$(file_nlink "$FAILED_GUARD_LOG")" == 1 ]] || exit 113
[[ "$(file_mode "$FAILED_GUARD_LOG")" == 400 ]] || exit 114
grep -Fq 'FAIL: simulated completed identity guard failure' \
  "$FAILED_GUARD_LOG"
[[ ! -e "$FAILED_RUN_DIR/identity.tsv" &&
   ! -e "$FAILED_RUN_DIR/identity.tsv.incomplete" &&
   ! -e "$FAILED_RUN_DIR/baseline.tsv" &&
   ! -e "$FAILED_RUN_DIR/baseline.tsv.incomplete" &&
   ! -e "$FAILED_RUN_DIR/manifest.json" ]] || exit 115
failed_guard_digest=$(sha256_file "$FAILED_GUARD_LOG")
if run_wrapper "$FIXTURE_DIR/provision.sh" \
  > "$TEST_ROOT/guard-failure-retry.stdout" \
  2> "$TEST_ROOT/guard-failure-retry.stderr"; then
  printf 'Provision unexpectedly replaced completed guard-failure evidence.\n' >&2
  exit 116
fi
grep -Fq 'evidence run already exists' \
  "$TEST_ROOT/guard-failure-retry.stderr"
[[ -f "$FAILED_GUARD_LOG" && ! -L "$FAILED_GUARD_LOG" ]] || exit 117
[[ "$(sha256_file "$FAILED_GUARD_LOG")" == "$failed_guard_digest" ]] ||
  exit 118
[[ "$(file_nlink "$FAILED_GUARD_LOG")" == 1 &&
   "$(file_mode "$FAILED_GUARD_LOG")" == 400 ]] || exit 119

printf 'PASS: fixture wrappers preserve run ownership, recover interrupted evidence, and reject drift without a network.\n'
