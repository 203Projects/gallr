#!/usr/bin/env bash
set -euo pipefail

TEST_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
FIXTURE_DIR=$(cd "$TEST_DIR/.." && pwd -P)
REPO_ROOT=$(cd "$FIXTURE_DIR/../../.." && pwd -P)
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/gallr-fixture-lifecycle.XXXXXX")
FAKE_BIN="$TEST_ROOT/bin"
EVIDENCE_ROOT="$TEST_ROOT/evidence"
SEQUENCE_LOG="$TEST_ROOT/sequence.log"
FAKE_EVIDENCE_GENERATOR="$TEST_DIR/fake-evidence.mjs"

cleanup() {
  case "$TEST_ROOT" in
    "${TMPDIR:-/tmp}"/gallr-fixture-lifecycle.*)
      rm -rf -- "$TEST_ROOT"
      ;;
    *)
      printf 'Refusing unsafe test cleanup path: %s\n' "$TEST_ROOT" >&2
      ;;
  esac
}
trap cleanup EXIT HUP INT TERM

mkdir -m 700 "$FAKE_BIN" "$EVIDENCE_ROOT"
printf '%s\n' \
  '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  'for forbidden in PGHOST PGHOSTADDR PGPASSWORD PGPORT PGSERVICE PGSERVICEFILE PGUSER; do' \
  '  [[ -z "${!forbidden:-}" ]] || exit 91' \
  'done' \
  '[[ "${PGDATABASE:-}" == "$EXPECTED_DATABASE_URL" ]] || exit 92' \
  '[[ "${PGSSLMODE:-}" == verify-full ]] || exit 93' \
  '[[ "${PGPASSFILE:-}" == /dev/null ]] || exit 89' \
  '[[ -z "${GALLR_EXPECTED_STAGING_PROJECT_REF:-}" ]] || exit 94' \
  '[[ -z "${GALLR_PRODUCTION_PROJECT_REF:-}" ]] || exit 95' \
  '[[ -z "${GALLR_STAGING_DATABASE_URL:-}" ]] || exit 96' \
  '[[ -z "${GALLR_STAGING_REHEARSAL_CONFIRM:-}" ]] || exit 87' \
  'sql_file=' \
  'fixture_prefix=' \
  'load_event_id=' \
  'empty_event_id=' \
  'editor_id=' \
  'boundary_id=' \
  'mutation_id=' \
  'media_object_path=' \
  'while (($#)); do' \
  '  case "$1" in' \
  '    -f) shift; sql_file="$1" ;;' \
  '    -v) shift; case "$1" in' \
  '      fixture_prefix=*) fixture_prefix=${1#fixture_prefix=} ;;' \
  '      load_event_id=*) load_event_id=${1#load_event_id=} ;;' \
  '      empty_event_id=*) empty_event_id=${1#empty_event_id=} ;;' \
  '      editor_id=*) editor_id=${1#editor_id=} ;;' \
  '      boundary_id=*) boundary_id=${1#boundary_id=} ;;' \
  '      mutation_id=*) mutation_id=${1#mutation_id=} ;;' \
  '      media_object_path=*) media_object_path=${1#media_object_path=} ;;' \
  '    esac ;;' \
  '    "$EXPECTED_DATABASE_URL") exit 90 ;;' \
  '  esac' \
  '  shift' \
  'done' \
  'printf "psql:%s\n" "$(basename "$sql_file")" >> "$SEQUENCE_LOG"' \
  'case "$(basename "$sql_file")" in' \
  '  baseline.sql)' \
  '    node "$FAKE_EVIDENCE_GENERATOR" baseline' \
  '    ;;' \
  '  provision.sql)' \
  '    node "$FAKE_EVIDENCE_GENERATOR" provision \' \
  '      "$fixture_prefix" "$load_event_id" "$empty_event_id" "$editor_id" \' \
  '      "$boundary_id" "$mutation_id" "$media_object_path"' \
  '    ;;' \
  '  cleanup.sql)' \
  '    node "$FAKE_EVIDENCE_GENERATOR" cleanup' \
  '    ;;' \
  '  *) exit 88 ;;' \
  'esac' > "$FAKE_BIN/psql"
chmod 700 "$FAKE_BIN/psql"

printf '%s\n' \
  '#!/bin/sh' \
  'if [ "${1:-}" = "$LINKED_GUARD_EXPECTED_PATH" ]; then' \
  '  [ "${BASH_ENV:-}" = /dev/null ] || exit 86' \
  '  [ "${ENV:-}" = /dev/null ] || exit 85' \
  '  printf "guard\n" >> "$SEQUENCE_LOG"' \
  '  printf "PASS: stubbed linked staging guard\n"' \
  '  exit 0' \
  'fi' \
  'if [ "${1:-}" = "$IDENTITY_GUARD_EXPECTED_PATH" ]; then' \
  '  [ "${BASH_ENV:-}" = /dev/null ] || exit 84' \
  '  [ "${ENV:-}" = /dev/null ] || exit 83' \
  '  printf "identity\n" >> "$SEQUENCE_LOG"' \
  '  printf "PASS: independent policy and disposable-clone marker identify staging\n"' \
  '  exit 0' \
  'fi' \
  'exec /bin/bash "$@"' > "$FAKE_BIN/bash"
chmod 700 "$FAKE_BIN/bash"

STAGING_REF='aaaaaaaaaaaaaaaaaaaa'
PRODUCTION_REF='bbbbbbbbbbbbbbbbbbbb'
RUN_ID='lifecycle-test-0001'
DATABASE_URL="postgresql://postgres:redacted@db.$STAGING_REF.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=%2Ftmp%2Fgallr-staging-root-ca.pem"
LINKED_GUARD_PATH="$REPO_ROOT/scripts/staging-rehearsal/assert-linked-staging.sh"
IDENTITY_GUARD_PATH="$REPO_ROOT/scripts/staging-rehearsal/assert-disposable-clone-target.sh"

run_wrapper() {
  local wrapper="$1"
  env \
    PATH="$FAKE_BIN:$PATH" \
    EXPECTED_DATABASE_URL="$DATABASE_URL" \
    FAKE_EVIDENCE_GENERATOR="$FAKE_EVIDENCE_GENERATOR" \
    FAKE_BASELINE_DRIFT="${FAKE_BASELINE_DRIFT:-0}" \
    SEQUENCE_LOG="$SEQUENCE_LOG" \
    LINKED_GUARD_EXPECTED_PATH="$LINKED_GUARD_PATH" \
    IDENTITY_GUARD_EXPECTED_PATH="$IDENTITY_GUARD_PATH" \
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
  mode=$(stat -f '%Lp' "$evidence_path" 2>/dev/null || stat -c '%a' "$evidence_path")
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
cleaned_mode=$(stat -f '%Lp' "$RUN_DIR/cleaned.json" 2>/dev/null || stat -c '%a' "$RUN_DIR/cleaned.json")
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
  mode=$(stat -f '%Lp' "$evidence_path" 2>/dev/null || stat -c '%a' "$evidence_path")
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
if FAKE_BASELINE_DRIFT=1 run_wrapper "$FIXTURE_DIR/cleanup.sh" \
  > "$TEST_ROOT/drift-recovery.stdout" 2> "$TEST_ROOT/drift-recovery.stderr"; then
  printf 'Drifted baseline unexpectedly finalized incomplete cleanup evidence.\n' >&2
  exit 95
fi
grep -Fq 'exact baseline is not restored' "$TEST_ROOT/drift-recovery.stderr"
[[ -f "$DRIFT_RUN_DIR/cleaned.json.incomplete" ]] || exit 96
[[ ! -e "$DRIFT_RUN_DIR/cleaned.json" ]] || exit 97
[[ -f "$DRIFT_RUN_DIR/baseline-restore-verification.tsv.incomplete" ]] || exit 98
drift_mode=$(
  stat -f '%Lp' "$DRIFT_RUN_DIR/baseline-restore-verification.tsv.incomplete" 2>/dev/null ||
    stat -c '%a' "$DRIFT_RUN_DIR/baseline-restore-verification.tsv.incomplete"
)
[[ "$drift_mode" == 400 ]] || exit 99
[[ "$(< "$SEQUENCE_LOG")" == "$recovery_sequence" ]] || {
  printf 'Unexpected drift-rejection sequence:\n%s\n' "$(< "$SEQUENCE_LOG")" >&2
  exit 100
}

printf 'PASS: fixture wrappers recover interrupted evidence and reject drift without a network.\n'
